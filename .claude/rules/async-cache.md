---
paths:
  - "caffeine/src/**/Async*.java"
  - "caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalCache.java"
  - "caffeine/src/main/java/com/github/benmanes/caffeine/cache/BoundedLocalCache.java"
---

# Async Cache Patterns

## Delegation
- Values stored as `CompletableFuture<V>` in wrapped `LocalCache<K, CompletableFuture<V>>`
- Listeners, weighers, and expiry auto-wrapped: AsyncRemovalListener, AsyncWeigher, AsyncExpiry
- `synchronous()` view unwraps futures via `Async.getIfReady()` — blocks on join, returns null for in-flight/failed, and for a future obtruded between the readiness check and the join

## Future Lifecycle
- In-flight futures receive ASYNC_EXPIRY (~220 years) to prevent premature eviction during loading
- On completion: `handleCompletion()` calls `replace()` to update weight and expiry for the real value
- The completion finalizes **only what the insertion deferred**: a future that was already
  complete when inserted was weighed and dated by that write, so `handleCompletion` skips the
  replace rather than charging the creation again as an update. Its `deferred` argument must be
  read **before** the store at every call site, since a future observed as ready afterwards may
  have completed after the install evaluated it, and skipping then leaves the entry holding
  weight 0 and the sentinel forever. Don't move the decision into `AsyncExpiry`, which cannot
  tell a completion from a genuine update, and don't make `expireAfterCreate` defer every
  creation: the synchronous view's `asMap()` compute family installs completed futures with no
  completion handler and would strand them at the sentinel
- Completion replaces are **quiet** (`replace(..., quietly=true)` from `handleCompletion` and
  `AsyncBulkCompleter.fillProxies`): the UpdateTask does weight/expiry bookkeeping but skips the
  sketch increment and the climber's hit counters — a completion is bookkeeping, not a usage.
  Loud completions doubled per-load admission frequency and window-attributed a synthetic hit per
  miss, measurably degrading eviction (w50 −38.6pp, stress@512 −12.7pp; see
  the async-completion-noise workspace report, local-only). Don't re-add access
  recording there, and
  don't flag the loud-user-replace vs quiet-completion asymmetry as an inconsistency
- Null result or failed future → entry removed; user removal listener does NOT fire
- **A load is recorded for a computation, and for a write whose value materialized while the cache
  held it.** `handleCompletion` takes `computed` from its caller and records when
  `computed || deferred`: `get`/`getAll` and the `asMap()` compute family always record, since the
  mapping function ran; `put`, `putIfAbsent` and `replace` on either surface record only when the
  future was still in flight when it was inserted, because then the entry sat in the loading state
  and the elapsed time is a real wait. A write of an already-resolved future loaded nothing and
  records nothing, which is what `CacheStats` means by a non-computing operation. Don't simplify
  this to "writes never record": the suite pins the in-flight cases
  (`computeIfAbsent_present_failed`, `handleCompletion_brokenFuture_*`), where a future handed to
  the cache and then failed must show up as a load failure
- A **bulk proxy** is owned by `AsyncBulkCompleter`, not by `handleCompletion`, so cancelling one
  leaves the entry mapped until the load settles and `fillProxies` obtrudes the value onto it. That
  is accepted: cancelling means downstream chained actions may be abandoned, not that the value is
  uncacheable, and the computation still materializes a value that a dropped entry could never hand
  to the removal listener. Don't add cancel-aware completion logic to the bulk path
- Refresh failures preserve the old value (not removed)

## Removal Listener Timing
- AsyncRemovalListener chains `thenAccept()` then dispatches to the executor with inline fallback on rejection
- Only fires if the future succeeded and value is non-null
- Exceptions in the listener are logged at WARNING and swallowed
- **A replaced future is retained until its successor completes, and that is #593's price.**
  `LocalCache.notifyOnReplace` registers `newFuture.whenComplete(...)` capturing `oldFuture`, so
  successive replacements of an in-flight value chain the history behind the live entry. Measured
  over 50 replacements on one key: 50/50 predecessors retained while the newest future is
  incomplete, 0/50 once it completes, 0/50 with no removal listener (the null-listener guard).
  The comparison it waits for is load-bearing, since "Avoid notifying the removal listener for
  no-op replacements (fixes #593)" suppresses the notification when two distinct futures resolve
  to the same value instance and a listener may be closing that value's resource. Declined
  2026-08-15. Don't hold the predecessor weakly, as one that completed and was then dropped would
  lose its REPLACED notification, which is the resource-closing case itself; don't invert the
  registration onto the old future, since the inner lambda still captures it and the chain returns
  once predecessors complete; don't notify eagerly for an in-flight replacement, which re-opens
  #593. Cache-driven paths cannot grow it (refresh starts only on a ready future), so it takes
  user-supplied futures that never complete.

## Map Views
- **Both async views override `replaceAll` to compute per key** rather than inheriting
  `ConcurrentMap`'s default. The default reads the value, applies the function outside any lock,
  and CASes, re-invoking the function for that key on every lost race, which CHM's own override
  also does. Caffeine's native caches instead apply the function inside their atomic remap, so the
  views were the odd ones out: with removal listeners and eviction bookkeeping, a value computed
  into a lost CAS is silently dropped without notification. The sync view passes
  `recordLoad = false` so that, like `Cache.asMap().replaceAll`, it records no load statistics.
  Both iterate `keySet()`, whose iterator filters in-flight entries through `getIfReady`, matching
  what the default's `forEach` skipped

## Key Gotchas
- Null values are never cached — null or failed futures remove the entry
- In-flight futures report weight=0 (re-inserted post-completion to update)
- Weak/soft value references are incompatible with AsyncCache (references would track the future, not the value)
- CancellationException and TimeoutException are suppressed in handleCompletion logging — only when the future completes with them *directly* (bare). A `CompletionException`-wrapped timeout/cancel arriving through a user dependent stage (e.g. `orTimeout().thenApply(...)`) is intentionally NOT unwrapped: it's indistinguishable from a timeout thrown by loader code, so unwrapping would silently swallow a real failure (provenance boundary — direct completion is ours to suppress, a wrapped cause is the user's pipeline)
