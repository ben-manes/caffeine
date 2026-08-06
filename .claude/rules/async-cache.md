---
paths:
  - "caffeine/src/main/java/com/github/benmanes/caffeine/cache/*Async*"
  - "caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalAsyncCache.java"
  - "caffeine/src/test/java/com/github/benmanes/caffeine/cache/Async*"
---

# Async Cache Patterns

## Delegation
- Values stored as `CompletableFuture<V>` in wrapped `LocalCache<K, CompletableFuture<V>>`
- Listeners, weighers, and expiry auto-wrapped: AsyncRemovalListener, AsyncWeigher, AsyncExpiry
- `synchronous()` view unwraps futures via `Async.getIfReady()` — blocks on join, returns null for in-flight/failed

## Future Lifecycle
- In-flight futures receive ASYNC_EXPIRY (~220 years) to prevent premature eviction during loading
- On completion: `handleCompletion()` calls `replace()` to update weight and expiry for the real value
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

## Key Gotchas
- Null values are never cached — null or failed futures remove the entry
- In-flight futures report weight=0 (re-inserted post-completion to update)
- Weak/soft value references are incompatible with AsyncCache (references would track the future, not the value)
- CancellationException and TimeoutException are suppressed in handleCompletion logging — only when the future completes with them *directly* (bare). A `CompletionException`-wrapped timeout/cancel arriving through a user dependent stage (e.g. `orTimeout().thenApply(...)`) is intentionally NOT unwrapped: it's indistinguishable from a timeout thrown by loader code, so unwrapping would silently swallow a real failure (provenance boundary — direct completion is ours to suppress, a wrapped cause is the user's pipeline)
