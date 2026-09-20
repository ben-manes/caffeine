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
  An incomplete replacement releases its predecessor's weight and receives creation expiry when
  its value materializes; it does not reserve the old weight or preserve update classification.
  Finalization is a dependent action, so a caller can read the value while `Policy` reports weight
  0, and a `Weigher` or `Expiry` that throws there is logged, counted as a load failure, and
  removes the mapping the caller already received. Single-key `handleCompletion` leaves the
  completed loader future successful. Bulk finalization continues through the proxies and fails
  the aggregate `getAll` future if it encounters an error.
  Failure cleanup preserves a distinct successor future in both single and bulk completions;
  the two `AsyncCacheTest.*weigherFails_preservesConcurrentReplacement` methods pin that boundary.
  Reinserting the same future creates no separate cleanup owner; its earlier failing finalizer
  may still remove it. Do not add per-insertion generation tracking for that case.
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
  in flight at insertion. Already-resolved writes do not load anything, even when the future is
  later obtruded or broken (`handleCompletion_completedWrite_*`). Preserve failure
  accounting for in-flight writes (`computeIfAbsent_present_failed`,
  `handleCompletion_brokenFuture_*`); "writes never record loads" is incorrect.
- Null results and failed futures remove the mapping without invoking the user's removal
  listener. Refresh failure preserves the old value.
- A bulk proxy belongs to `AsyncBulkCompleter`, not `handleCompletion`. Cancellation leaves it
  mapped until `fillProxies` obtrudes the loaded value, and a caller's own completion of the proxy
  (`completeOnTimeout`) is served, without expiring, until then. This is accepted: cancellation may
  abandon dependent actions without making the value uncacheable, and dropping the mapping
  would prevent its eventual value from reaching a removal listener. Do not add cancellation
  cleanup to the bulk path.
- If bulk setup fails before loader dispatch, remove only that call's installed proxies and
  complete them exceptionally. A read-expiry throw on another key must not leave those futures
  ownerless; conditional removal preserves a concurrent replacement. Propagate the original
  failure without a load statistic or load-failure log, since loading never started. Cleanup
  remains unguarded against a second failure from broken ticker or key operations.
- Treat a completed valueless mapping as absent. Completion precedes removal, and
  `fillProxies` can run a caller's dependent action between them. Waiting for that mapping in
  `tryComputeRefresh` cannot make progress; `AsyncLoadingCacheTest.refresh_bulkAbsentKey`
  pins refresh of an unfulfilled bulk key.
- `fillProxies` fills a batch's proxies one at a time on the thread running the completer, and
  each completion runs that proxy's non-async dependent actions before the next proxy is filled.
  A dependent that waits for a sibling proxy, directly or through a `synchronous()` read of its
  key, waits on its own completer, so the batch stalls until that wait ends or the sibling is
  completed elsewhere. Every `CompletableFuture` completion method runs dependents inline, so no
  fill order avoids it; it is the hazard of blocking in a non-async stage, which `thenCombine` or
  an async stage avoids. Filling proxies through the executor would isolate them, but moves every
  caller's dependents off the completing thread and adds a task per proxy.
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
