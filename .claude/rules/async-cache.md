---
paths:
  - "caffeine/src/**/Async*.java"
  - "caffeine/src/**/LocalAsync*.java"
  - "caffeine/src/main/java/com/github/benmanes/caffeine/cache/LocalCache.java"
  - "caffeine/src/main/java/com/github/benmanes/caffeine/cache/BoundedLocalCache.java"
---

# Async Cache Patterns

## Delegation

- Values are `CompletableFuture<V>` in a wrapped `LocalCache<K, CompletableFuture<V>>`.
- `AsyncRemovalListener`, `AsyncWeigher`, and `AsyncExpiry` adapt user callbacks.
- `synchronous()` unwraps with `Async.getIfReady()`: readiness checks followed by `join()`,
  returning null for in-flight/failed futures or a future obtruded between the checks and join.
- Weak/soft values are unsupported: references would track the future, not its value.

## Future Lifecycle

- In-flight futures have weight 0 and `ASYNC_EXPIRY` (~220 years). Completion finalizes only
  deferred weight/expiry work; a future already complete at insertion was weighed and dated then.
- Read `handleCompletion`'s `deferred` flag **before storing the future**. A later readiness
  check could miss completion after insertion and leave weight 0 and the sentinel indefinitely.
  The flag is conservative: the future can complete before insertion reads it, so a quiet
  `replace` rechecks the entry's sentinel. `AsyncExpiry` cannot distinguish completion from a
  genuine update. Do not defer every creation there: the synchronous `asMap()` compute family
  installs completed futures without completion handlers.
- `handleCompletion` and `AsyncBulkCompleter.fillProxies` use `replace(..., quietly=true)`.
  `UpdateTask` updates weight/expiry without incrementing the sketch or climber hit counters.
  Loud completion double-counted admission frequency and added a synthetic window hit per miss,
  degrading measured hit rate (w50 -38.6pp; stress@512 -12.7pp). User replacements remain loud.
- Record a load when `computed || deferred`: `get`/`getAll` and map computations record their
  computation; `put`, `putIfAbsent`, and `replace` on either surface record only futures still
  in flight at insertion. Already-resolved writes do not load anything. Preserve failure
  accounting for in-flight writes (`computeIfAbsent_present_failed`,
  `handleCompletion_brokenFuture_*`); "writes never record loads" is incorrect.
- Null results and failed futures remove the mapping without invoking the user's removal
  listener. Refresh failure preserves the old value.
- A bulk proxy belongs to `AsyncBulkCompleter`, not `handleCompletion`. Cancellation leaves it
  mapped until `fillProxies` obtrudes the loaded value. This is accepted: cancellation may
  abandon dependent actions without making the value uncacheable, and dropping the mapping
  would prevent its eventual value from reaching a removal listener. Do not add cancellation
  cleanup to the bulk path.
- Treat a completed valueless mapping as absent. Completion precedes removal, and
  `fillProxies` can run a caller's dependent action between them. Waiting for that mapping in
  `tryComputeRefresh` cannot make progress; `AsyncLoadingCacheTest.refresh_bulkAbsentKey`
  pins refresh of an unfulfilled bulk key.
- `handleCompletion` suppresses logging for bare `CancellationException`/`TimeoutException`.
  Do not unwrap a `CompletionException` from a user stage such as `orTimeout().thenApply(...)`:
  it is indistinguishable from a timeout thrown by loader code, which must remain reportable.

## Removal Listeners

- `AsyncRemovalListener` chains `thenAccept()` and dispatches through the executor, with inline
  fallback on rejection. It invokes the listener only for a successful, non-null value; listener
  exceptions are logged at WARNING and swallowed.
- `LocalCache.notifyOnReplace` captures `oldFuture` in `newFuture.whenComplete(...)` to suppress
  notifications when distinct futures resolve to the same value instance (#593). A listener may
  close that value, so early notification is unsafe. This retains predecessors until their
  successors complete: 50 replacements retained 50 predecessors while the newest was incomplete,
  and none after completion or with no listener.
- This retention is accepted for user-supplied futures that never complete; refresh begins only
  on ready futures and cannot grow the chain. Weak predecessors lose required REPLACED
  notifications, reversing the registration still captures them once they complete, and eager
  notification reopens #593. None is an acceptable repair.

## Map Views

Both async views override `replaceAll` with per-key computation. `ConcurrentMap`'s default
applies the function outside its CAS and retries on a lost race, as CHM's override also does.
That can discard computed values without removal notifications. The views use atomic remapping
like the native caches; the synchronous view passes `recordLoad = false` to match
`Cache.asMap().replaceAll`. Iterating `keySet()` preserves each view's entry eligibility,
including the synchronous view's filtering of in-flight values through `getIfReady`.
