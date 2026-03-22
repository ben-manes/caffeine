# Design Decisions

Non-obvious choices that are intentional, not bugs. If you're tempted to "fix" any of
these, stop — they're load-bearing.

## Eviction

**Weight=0 is a pinning feature.** Entries with weight=0 are skipped during eviction
(`evictFromWindow`, `evictFromMain`, `evictEntry`). This is an intentional user-facing
API (inherited from Guava's
ConcurrentLinkedHashMap where weight was >=1). Used internally for in-flight async
futures.

**Transient negative weightedSize is acceptable.** `maximumSize` allows eviction
before/after threshold. Eventual consistency is fine given documented promises.
Weight convergence is guaranteed by the telescoping sum property across all write
buffer task orderings.

**Weights are static after computation.** The weigher is called at creation/update
time. The weight is not recalculated afterward — relative weights don't influence
eviction ordering, only total capacity accounting.

**~1% random admission of rejected candidates.** The TinyLFU admission filter
randomly admits ~1% of candidates that would otherwise be rejected. This provides
HashDoS protection by making frequency estimation attacks non-deterministic.

## Expiration

**EXPIRE_WRITE_TOLERANCE = 1 second.** Expiration is a maximum lifetime,
not a minimum hold time. Like ScheduledExecutorService, the timing is never exact.
This tolerance provides a 4x throughput improvement (23M/s vs 5.9M/s) on write-heavy
workloads by avoiding write buffer saturation from rapid timer wheel rescheduling.

**ASYNC_EXPIRY = ~220 years** (`Async.java`). Computing futures get this sentinel
duration to prevent expiration during async computation. The `isComputingAsync()`
check tests both the `isAsync` flag AND whether the future is complete.

**MAXIMUM_EXPIRY = ~150 years** (`Long.MAX_VALUE >> 1`). User-provided expiration
durations are clamped to prevent nanoTime arithmetic overflow. `now + ASYNC_EXPIRY`
overflows to negative after ~73 years of JVM uptime, but this is within the
documented assumption that JVM instances don't run for 73+ years continuously.

**accessTime uses opaque write, not CAS.** CAS on every read would cause contention
storms on hot entries. Backward movement only causes benign early expiration, which
is acceptable. Variable time CAS (`casVariableTime`) is justified because
`expireAfterRead` can change duration arbitrarily.

**writeTime uses plain write** (not opaque like accessTime). It's always written
under `synchronized(node)`, which provides stronger guarantees than opaque.

## Exception Handling

**Catch-commit-rethrow pattern** in `doComputeIfAbsent` and `remap`. Both catch
`Throwable`, not just RuntimeException. When user code
(mapping function, weigher, expiry) throws after `notifyEviction` was called, the
phantom eviction is made real: the node is retired, null is returned to CHM, and the
exception is deferred past cleanup.

**notifyEviction is called BEFORE user code**, not after. This can't be reordered —
it preserves linearizability for resource-based listeners (e.g., file delete before
recreate). The catch-commit-rethrow pattern handles the case where user code then
throws.

**wasEvicted flag** in `remap`: `boolean wasEvicted = (ctx.cause != null)` is captured
BEFORE the try block because `ctx.cause` can change from null to REPLACED
inside the try. The catch block uses `!wasEvicted` to distinguish eviction-path
exceptions (commit+defer) from non-eviction exceptions (immediate rethrow).

## References

**Non-volatile keyReference in WeakValueReference** is a plain field set once during
construction and never mutated. In `setValue`, visibility is guaranteed by
`setRelease` followed by `VarHandle.storeStoreFence()` — the fence ensures the
non-final keyReference field of the newly constructed reference is published before
subsequent operations. `setRelease` alone was insufficient because release semantics
only apply to the field itself, not to non-final fields of the newly constructed
object (#1820, confirmed on aarch64 M3 Max via JCStress IntermittentNull test).
In the constructor, a plain `VALUE.set` is used since the object itself is not yet
published. Strong value caches use `setRelease` without the fence since there is
no inner object with non-final fields to publish.

**Weigher.boundedWeigher** wraps all user weighers and enforces `weight >= 0` at
runtime via `requireArgument`.

## Concurrency

**No debug-mode assertions.** Runtime invariant assertions are impractical for
concurrent code — too hard to assert on a running system. Correctness relies on
testing (Fray, LinCheck, JCStress) and static analysis (ErrorProne `@GuardedBy`).

**nanoTime is monotonic.** Per JVM spec, `System.nanoTime()` is monotonic. Backward
movement would be a JVM bug, not a cache issue.

**skipReadBuffer optimization.** When the cache is less than half full with strong
keys/values and no expiration, `skipReadBuffer()` returns true, avoiding read buffer
overhead entirely. This means frequency tracking is disabled until the cache is
sufficiently populated — the eviction policy bootstraps without frequency data.

## Node State

**Two weight fields**: `weight` (entry's perspective, guarded by `synchronized(node)`)
and `policyWeight` (policy's perspective, guarded by evictionLock). They're correlated
but updated at different times — this is intentional for the telescoping sum to work.

**Queue type constants** are plain ints, not enums: WINDOW=0, PROBATION=1, PROTECTED=2.
The field is plain (not volatile), guarded by evictionLock.

## ConcurrentHashMap Constraints

**No recursive computations.** Writing to the cache from inside an atomic
compute/computeIfAbsent/merge callback throws `IllegalStateException`. This is a
ConcurrentHashMap contract constraint, not a Caffeine bug.

**CHM bin blocking is not a Caffeine bug.** `compute()` locks the hash bin. If the
mapping function (cache loader) is slow, all other operations on keys in the same
bin are blocked. This is the #1 recurring user issue (~20 reports). The answer is
always: use `AsyncCache` for slow loaders, increase `initialCapacity` to reduce
collisions, or make loaders faster.

**Eviction is async, not immediate.** After `put`, the cache may temporarily exceed
`maximumSize` until the executor runs maintenance. Use `executor(Runnable::run)` for
inline eviction in tests, or call `cleanUp()` before assertions.

**Expiration and cleanup are amortized, not instant.** Caffeine performs maintenance
during write operations and occasionally during reads. For idle caches, use
`Scheduler.systemScheduler()` to get prompt expiration. This is best-effort with
no hard timing guarantees.

**No close() by design.** The `Cache` interface deliberately does not extend
`Closeable`. The cache is a data structure that becomes GC-eligible when
unreferenced. The `WeakReference` in `PerformCleanupTask` breaks the
scheduler→cache reference chain, so scheduled maintenance becomes a no-op
when the cache is unreachable. (JCache's `CacheProxy` is the only component
with explicit close semantics, as required by JSR-107.)

## Refresh

**Refresh returns the stale value, not the fresh one.** `get()` returns the current
value immediately and triggers an async reload. The next `get()` returns the
refreshed value. This is the entire point — hiding reload latency from callers.

**Refresh only triggers on access.** An idle cache with no reads will never refresh.
For proactive refresh, use `ScheduledExecutorService` with `cache.refresh(key)`.

**`expireAfterAccess` + `expireAfterWrite` together is discouraged.** Inherited from
Guava for compatibility. The two timestamps are independent; whichever has the
shortest remaining duration wins. Prefer `expireAfter(Expiry)` for custom logic.

## Iteration

**`asMap()` iteration is not a cache read.** Iterators do not update access times or
frequency counters. This prevents iteration from polluting the eviction policy.
Expired entries are skipped during iteration.

## Known JDK Interactions

**StackOverflowError can leak the eviction lock.** If user code causes a
`StackOverflowError` inside a cache operation, `ReentrantLock.unlock()` can fail
to execute (JDK bug JDK-8319309), leaving the eviction lock permanently held and
blocking all subsequent writes.

**PerformCleanupTask.exec() returns false.** This is an optimization — the task is
allocated once and reused instead of creating a new `Runnable` wrapper per executor
submission (which showed up as a memory hotspot in profiling).

## Pacer

The Pacer rate-limits expiration maintenance scheduling. It uses
`TOLERANCE = ceilingPowerOfTwo(1 second)` (~1.07s) as a minimum delay threshold,
preventing scheduling storms from rapid expirations.
