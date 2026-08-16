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

## Key Gotchas
- Null values are never cached — null or failed futures remove the entry
- In-flight futures report weight=0 (re-inserted post-completion to update)
- Weak/soft value references are incompatible with AsyncCache (references would track the future, not the value)
- CancellationException and TimeoutException are suppressed in handleCompletion logging — only when the future completes with them *directly* (bare). A `CompletionException`-wrapped timeout/cancel arriving through a user dependent stage (e.g. `orTimeout().thenApply(...)`) is intentionally NOT unwrapped: it's indistinguishable from a timeout thrown by loader code, so unwrapping would silently swallow a real failure (provenance boundary — direct completion is ours to suppress, a wrapped cause is the user's pipeline)
