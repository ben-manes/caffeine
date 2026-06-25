# Design Decisions

Non-obvious choices that are intentional, not bugs. If you're tempted to "fix" any of
these, stop — they're load-bearing.

## Eviction

**Weight=0 is a pinning feature.** Entries with weight=0 are skipped during eviction
(`evictFromWindow`, `evictFromMain`, `evictEntry`). This is an intentional user-facing
API (inherited from ConcurrentLinkedHashMap where weight was >=1, Guava's Cache where >= 0).
Used internally for in-flight async futures.

**Transient negative weightedSize is acceptable.** `maximumSize` allows eviction
before/after threshold. Eventual consistency is fine given documented promises.
Weight convergence is guaranteed by the telescoping sum property across all write
buffer task orderings.

**Weights are static after computation.** The weigher is called at creation/update
time. The weight is not recalculated afterward — relative weights don't influence
eviction ordering, only total capacity accounting.

**Small-cache climber adaptation (≤512 entries).** At small cache sizes, the
per-sample hit-rate signal is noisy and the default window (1% of max) is so tiny
that the climber's initial shrink is a no-op, locking it into a direction that
never flips. Three coordinated fixes: (1) positive initial step — small caches
grow the window first instead of shrinking; (2) slower step decay (0.995 vs 0.98)
so the step stays large enough for HR shifts to trip the restart threshold on
workload transitions; (3) min initial step floor (2 entries) so very small caches
can move the integer window. The sample-period growth (proportional to step decay,
capped at 4×) reduces noise when fine-tuning near the optimum.

**Climber `adjustment` is a multi-cycle carry-over, not stale state.** `increaseWindow` /
`decreaseWindow` transfer at most `QUEUE_TRANSFER_THRESHOLD` (e.g. 1000) nodes per maintenance
cycle, then store the *unfulfilled* remainder back via `setAdjustment(quota)` /
`setAdjustment(-quota)` — the leftover, not zero. On a large cache the per-decision step
(`≈ 0.0625 × maximum`, e.g. 62,500 at a 1M maximum) dwarfs the (e.g 1000) node cap, so a single
climber decision is deliberately drained across many later cycles. Each of those cycles
`determineAdjustment` early-returns at `requestCount < effectiveSampleSize` (the sample was
reset and has not refilled) **without touching `adjustment`**, and `climb` re-applies the
carried remainder. Once the sample refills, a fresh `determineAdjustment` overwrites
`adjustment` with a new decision. This looks like "a stale adjustment re-applied without a
fresh hit-rate sample," but it is the completion mechanism for a work-capped transfer.

The symmetric give-back after the transfer loop (`mainProtectedMaximum += quota;
windowMaximum -= quota`) keeps the partition sum (`windowMaximum + mainProtectedMaximum +
implicit-probation == maximum`) constant and the region maxima non-negative on every
re-application — the maxima track the *partial* transfer that actually happened (added in
`3a217b22c`, "Fix bugs in adaptive policy"). Two consequences worth not flagging:
- **Pinned leftover.** If the carried `quota` is smaller than the policy weight of every
  candidate (e.g. `quota = 1` while all entries weigh 100), the loop moves nothing and
  re-stores the same value, so the window stays put until a real sample overwrites
  `adjustment`. The window genuinely cannot grow by a fraction of an indivisible heavy entry.
- **Probation is the implicit slack region.** The transfer draws from both probation and
  protected but only decrements `mainProtectedWeightedSize` for protected moves, so probation
  absorbs the difference between the window-maximum shift and the protected weight moved
  (`Δ windowMaximum == total weight transferred` holds exactly).

The hardening companion to this: `determineAdjustment` guards the small-cache sample-period
`ratio` against a `0/0` NaN (when both the maximum and step size are zero). The NaN would
otherwise zero `effectiveSampleSize`, defeat the sample guard, and poison
`previousSampleHitRate`. The state is unreachable in practice (the step size never decays to
exactly `0.0`), so this is defense-in-depth, not a live fix.

**~1% random admission of rejected candidates.** The TinyLFU admission filter
randomly admits ~1% of candidates that would otherwise be rejected. This provides
HashDoS protection by making frequency estimation attacks non-deterministic.

## Expiration

**EXPIRE_TOLERANCE = 1 second.** Expiration is a maximum lifetime,
not a minimum hold time. Like ScheduledExecutorService, the timing is never exact.
The tolerance applies to multiple per-entry timestamps:
- `writeTime` reorder decisions in remap (`exceedsWriteTimeTolerance`) — avoids
  write buffer saturation from rapid timer wheel rescheduling, ~4x throughput on
  write-heavy workloads.
- `accessTime` updates on the read path — avoids cache-line true-sharing on a hot
  entry under `expireAfterAccess`. When the configured duration is `<= tolerance`
  the skip is bypassed so tiny expiration windows still behave exactly.

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

**Read-path expiry extension can briefly resurrect a just-expired entry — accepted.**
A reader that observed an entry live and then extends it (`tryExpireAfterRead`'s
`casVariableTime` for variable expiry, or `setAccessTime` for `expireAfterAccess`) can land
the extension just after the entry crossed its boundary, leaving it visible slightly later than
expiry. This is inherent to lock-free read-extension over lazy expiration: the expired entry
keeps its timestamp until maintenance removes it, so the CAS — which only checks the field is
unchanged — cannot reject an expired entry, and any fresh-clock guard before the write still
races a context switch between the read and the write; only a read-path lock (rejected) would
close it. The window is a few instructions for a normal `Expiry` callback; a wide one requires a
slow `expireAfterRead` (callback misuse, like a slow `Weigher`). So "never visible later than
expiry" is best-effort for read-extension — the over-stay is bounded by one duration and
self-heals on the next maintenance. Don't add a re-check guard.

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

**`remap` same-instance return is a setter no-op, NOT a metadata no-op**. When a
user `compute`/`merge` remapping function returns the same value instance as the
current value, `setValue` is skipped, but `weight`, `accessTime`, `variableTime`,
and `writeTime` still update. This is intentional: `compute` is a mutation API,
so a same-value return is still treated as a write for eviction-policy purposes
(the entry's age/weight/access are refreshed). The only documented full no-op is
the explicit `preserveTimestamps` path. A reader expecting
`compute(k, (k, v) -> v)` to leave eviction ordering undisturbed would be
surprised; the source does not call this out, so this entry is the canonical
place the behavior is documented (preferred over a source comment).

## References

**Non-volatile keyReference in WeakValueReference** is a plain field. It is set
during construction and, under `synchronized(node)`, mutated to a sentinel value
(`RETIRED_*_KEY` / `DEAD_*_KEY`) when the node is retired or dies. Lock-free
readers tolerate the resulting staleness window as weakly-consistent observation.

In `setValue`, a new `WeakValueReference` is installed via
`setRelease` followed by `VarHandle.storeStoreFence()`, then the old reference is
cleared via `ref.clear()`. The fence prevents the old reference's `clear()` from
being reordered before the publication of the new reference. Without the fence, a
reader that re-reads the same reference and observes a cleared referent cannot
distinguish "the clear was already committed" from "the clear's store buffer is
ahead of the new reference's publication" — breaking the `getValue` re-check loop
invariant. `setRelease` alone orders the new reference's constructor writes before
the publication, but does not constrain the subsequent `ref.clear()` against any
racing reader (#1820, confirmed on aarch64 M3 Max via JCStress IntermittentNull test).

In the constructor, a plain `VALUE.set` is used since the object itself is not yet
published. Strong value caches use `setRelease` without the fence since there is
no inner object to publish and no `ref.clear()` to order against.

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

`nextFireTime = 0L` is the unscheduled/cancelled sentinel. `calculateSchedule`
bumps any computed fire time that would equal `0L` up to `1L` to prevent a
collision with the sentinel — needed only in edge cases where `now + TOLERANCE`
or `scheduleAt` lands exactly on zero, but the guard removes the ambiguity for
readers of `schedule()`'s recursion check.

## Refresh

**`refreshIfNeeded` is intentionally lock-free.** Reads of `writeTime`, `getKey`,
`getValue`, `getKeyReference`, `isAlive`, and the CAS of `writeTime` happen
without `synchronized(node)`. A stale observation could let `asyncReload` fire
on a just-retired node, but the completion-path ABA guards (`currentValue ==
oldValue` + `(node.getWriteTime() & ~1L) == writeTime`) discard the result. Cost
of the rare spurious loader call is accepted to keep the refresh fast path lock-free.

**The low bit of `writeTime` is a soft-lock marker, and the completion ABA check
must mask it.** A reader probing for a refresh CASes `writeTime → writeTime | 1`
while it registers the token in `refreshes`, then resets it; it starts no load if
the token already exists, so its transient marker is invisible to any stampede check.
The completion compares the *base* write time (`& ~1L`), not the raw value — a
concurrent reader's transient soft-lock is not a modification, and comparing the raw
value discards a perfectly good reload (issue #1970). The completion also keeps the
token registered in `refreshes` across the value swap: it reads the token to confirm
ownership rather than removing it, and the compute machinery's `discardRefresh` clears
it *after* `setWriteTime`. Holding it for the swap keeps concurrent reads debounced so
none can trigger a stampede in the remove-then-refresh window. Finally, the
`computeIfAbsent` lambda re-validates the marker under its per-key atomicity and aborts
when a concurrent refresh already completed (its `setWriteTime` cleared our marker),
so a delayed reader that passed the `containsKey` gate does not launch a duplicate,
stale reload from the same prior value. On the absent-create path the token clear runs
in a `finally`, so a `weigher` or `expiry` callback that throws while creating the entry
cannot orphan the token (#1970).

**`discardRefresh` is deliberately over-aggressive.** A mutation that races a
refresh discards whatever token is in `refreshes` without trying to prove it's
the same generation. Any refresh in flight was launched against a pre-mutation
snapshot, so killing it is correct for linearizability even if it happens to be
a "newer" generation from a later reader.

The one exception is a **query-style no-op**, flagged with `RemapHints.preserveRefresh`:
`putIfAbsent` on a present key, a non-matching conditional `remove`/`replace`, or a
same-instance `compute` return routed through the async synchronous view. These don't
actually mutate the entry, so they leave a racing refresh intact. Both
`BoundedLocalCache.remap` and `UnboundedLocalCache.remap` honor the hint (a same-instance
return with `preserveRefresh` set skips `discardRefresh`); a real mutation still discards.
The unbounded cache used to drop the hint and cancel the reload — the sibling caches must
stay in sync here.

## Async Synchronous View

**`AsyncCache.synchronous().asMap()` queries are logical, mutations are
physical.** `containsKey`, `get`, iteration, and `containsValue` treat in-flight
entries as absent (`Async.isReady` / `Async.getIfReady`). But `KeySet.remove`,
`removeAll`, `removeIf`, `retainAll`, and `EntryIterator.remove` operate on the
raw delegate map without blocking on in-flight futures. Blocking everywhere
would invite deadlock and non-linearizable observations; the split is the
inherent sync-over-async tradeoff. `keySet().contains(k) != keySet().remove(k)`
on a loading entry is accepted.

**`size()` and `isEmpty()` are physical**, delegating straight to the backing map
(`AsMapView` → `delegate.size()` / `delegate.isEmpty()`). They count in-flight
(still-loading) entries that `containsKey` / `get` / iteration treat as absent, so
`size()` can disagree with what iteration yields. This is the same logical-query /
physical-bookkeeping split and matches the documented "`size()` is an estimate" stance —
they belong on the physical side alongside the mutations above.

## TimerWheel

**Sub-tick advances correctly produce `delta = 0`.** Advancing `nanos` by less
than `2^SHIFT[i]` nanoseconds (e.g., `-1 → 0`) shifts to the same unsigned tick
index, so no buckets are processed. This is correct: no tick boundary was
crossed. Entries whose `variableTime` maps to the "last" bucket of a wheel are
visited on the next full wheel cycle (~68s for `wheel[0]`), which is within
expiration's documented best-effort amortization. Read-path `hasExpired` also
evicts on access.

**Interner `drainKeyReferences` does not need a value-identity check.** Unlike
`drainValueReferences`, which guards against the value being replaced on the
same node, keys on Interned nodes never rebind. If two hash-colliding weak keys
are both cleared and aliased via `WeakKeyEqualsReference.equals` (which becomes
`null.equals(null) == true` post-clear), both queue polls still complete and
both nodes still evict — only the attribution is swapped, which is unobservable
(uniform `Boolean.TRUE` values, no listener on the interner).
