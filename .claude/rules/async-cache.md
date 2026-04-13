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
- CancellationException and TimeoutException are suppressed in handleCompletion logging
