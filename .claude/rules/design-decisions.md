---
paths:
  - "caffeine/src/main/java/**"
---

# Design Decisions (Quick Reference)

Check the relevant section before changing behavior or reporting a defect. These are review
invariants; the linked documents retain the rationale, measurements, and rejected alternatives.
For adjudication, also read your module's section of [ruled-out](../docs/ruled-out.md).

## Eviction and maintenance

- Weight 0 pins entries. Transient negative `weightedSize` and `policyWeight` are accepted;
  verify convergence through the telescoping sum. `makeDead` subtracts `getWeight`, and late
  `UpdateTask`s must run even on dead nodes. Keep these together.
- Policy weight is 64 bits, packed between `policyWeight` and `metadata`. Truncation leaves a
  permanent region-size residue; a sign guard cannot detect every wrap.
- Expiration evicts at most `EXPIRATION_THRESHOLD` (1000) per cycle. Reference draining polls
  at most `REFERENCE_THRESHOLD` (1000) per queue. Preserve `PROCESSING_TO_REQUIRED` re-arms.
  These caps limit eviction/reference work per lock hold, not a waiter's latency on the
  non-fair lock.
- The wheel's expiration budget counts evictions, not cascades. Rewinding after a cascade cap
  would repeatedly reprocess the moved nodes and can livelock.
- With a custom executor and no `Scheduler`, an idle cache can remain over maximum by the
  bounded write-buffer debt. A scheduler requests prompt expiration; size-only caches have
  no pacer. Do not add another immediate re-arm or move submission into `PerformCleanupTask`.
- The sketch's shrink retrack and reset's zero clamp are a pair. Keep the table grow-only;
  retained-table reset cost after a large shrink is accepted.
- Both write-buffer consumers (`drainWriteBuffer` and `clear`) use `relaxedPoll` so a stalled
  producer cannot spin the lock holder. The producer re-arms after publishing its task.
- `StripedBuffer` expands on `FAILED` contention, not `FULL` backlog. A full home stripe
  returns `FULL` to request a drain; it does not search other stripes. A thread's starting
  stripe is fixed because the JDK's mutable thread probe is inaccessible.
- The shaded JCTools write queue's index overflow after 2^62 offers is accepted; a subtraction
  check alone does not fix the wrapping producer limit, and rollback can strand the consumer.

Details: [eviction](../docs/design-decisions.md#eviction),
[node state](../docs/design-decisions.md#node-state),
[expiration budgets](../docs/design-decisions.md#expiration),
[buffers](../docs/design-decisions.md#buffers), and [pacer](../docs/design-decisions.md#pacer).

## Expiration and publication

- The 1s tolerance can expire entries early and is bypassed for durations at or below it.
  Opaque access-time writes avoid read-path contention. Read-extension can briefly revive an
  expired entry; a fresh-clock check still races. Keep the value-identity guard, which prevents
  applying a read duration to a replacement value.
- A removal's cause is attributed when that removal happens. `clear()`'s single ticker read
  amortizes the call across the entries it removes under the eviction lock, not a point-in-time
  snapshot; its straggler fallback uses the public per-key `remove`, so cause counts are neither
  comparable across the bulk calls nor stable within one `clear()`.
- Readers load timestamps before values; writers store values before timestamps. Preserve
  `hasExpired`'s load-load fence and generated `setValue`'s store-store fence. Probe async
  readiness using a value loaded after an expired verdict, and only where it is consumed.
  Do not reuse readiness across observations: completion or obtrusion may intervene.
- The async expiry sentinel also means accounting is deferred. Both read-extension paths
  preserve it. Completion checks readiness before installation and quiet replacement checks
  the sentinel again, so a creation is neither skipped nor charged again as an update.
- Weak/soft references' `keyReference` accessors are opaque, not plain or volatile. Preserve
  the fence between publishing a replacement value reference and clearing the old one.
- Outside `evictionLock`, use `maximumAcquire` / `weightedSizeAcquire`. Plain long reads can
  tear on 32-bit JVMs; accessor names alone do not establish the caller's lock state.
- `scheduleAfterWrite` retries a failed IDLE-state CAS against the observed status. Scheduling
  from the stale read can miss an in-flight drain that already passed the write's task.
- A wheel tick is `2^30` ns (~1.074s). Queries filter expired entries immediately; physical
  removal and notification may wait one tick, and longer when the deadline moved after the node
  was scheduled. A variable-expiry read that shortens a duration reschedules only if the lossy
  read buffer accepted the node, so a dropped offer defers the reaping to the previous deadline's
  bucket sweep and a `Scheduler` does not shorten that. Eager current-bucket sweeps need a cost
  measurement before replacing `delta <= 0`.
- `TimerWheel.expire` detaches onto the field-backed, circular `pending` sentinel and re-reads
  its head each iteration. `advance` defers on the explicit `advancing` flag, even when
  `pending` is empty. Deque scans instead re-check their captured tail after callbacks;
  access-order checks need both membership and queue type.
- `getExpirationDelay` finds the next bucket flush, including cascades. The current bucket
  flushes at offset 1; retain both the main-scan clamp and `peekAhead`'s current-bucket check.
- `Pacer.schedule` calls `cancel()`, not merely `future.cancel(...)`, before rescheduling.
  Preserve the `future == null && nextFireTime != 0L` recursion guard and the 0L-to-1L bump.
  User schedulers are guarded against throws/null; built-ins satisfy that contract directly.

Details: [expiration](../docs/design-decisions.md#expiration),
[references](../docs/design-decisions.md#references),
[concurrency](../docs/design-decisions.md#concurrency),
[timer wheel](../docs/design-decisions.md#timerwheel), and [pacer](../docs/design-decisions.md#pacer).

## Exceptions and refresh

- `notifyEviction` precedes user code for linearizability. Catch-commit-rethrow makes that
  irrevocable notification real when subsequent user code throws. `remap` dispatch uses
  `ComputeContext.unmodified`, not a second approximation of the no-op predicate.
- Value-bearing callbacks propagate failures; fire-and-forget callbacks are guarded. Do not
  add broad containment for broken tickers, equality, hostile futures, or JVM errors.
  A maintenance throw defers buffered work; it does not require forcing `REQUIRED`.
- `Caffeine.toUnchecked` restores interruption when converting `InterruptedException`.
  Functional interfaces can throw checked exceptions from other JVM languages. The absent-key
  and unbounded paths propagate unchanged rather than converting.
- Concurrent standard-future obtrusion reads as not-ready. Keep `Async.getIfReady`'s narrow
  cancellation/completion catch; a later obtrusion need not remove the physical entry.
- Automatic refresh stays lock-free. Commit requires the captured node alive, the same value,
  and matching write time with its soft-lock bit masked. Completion catches conditionally
  release their own token, including failures before `remap` reaches its discard.
- Real mutations discard refreshes. Query-style no-ops preserve them in both bounded and
  unbounded caches. Rejected completions set `preserveRefresh = !owned` and preserve timestamps;
  a stale completion must neither steal a successor's token nor reset its write clock.
  Absent creates and purges still discard. Read the exit table before editing these paths.
- All refresh registrations use `referenceKey(key)`, never a node-owned weak reference that
  retirement clears. Keep the bounded cache's `containsKey` prescreen: its reservation race
  can defer one load but cannot commit a stale value through the ABA guards.
- The token and public result are the loader's original future. Reusing one pending future
  across generations or overriding future equality is outside the supported model; wrapping
  per generation changes identity and cancellation semantics.
- Async load and automatic-refresh completions are quiet bookkeeping updates. Manual refresh
  stays loud. Keep quiet replacement and remap behavior aligned, including immaterial updates.

Details: [exceptions](../docs/design-decisions.md#exception-handling),
[refresh internals](../docs/design-decisions.md#refresh-internals), and
[quiet completion accounting](../docs/design-decisions.md#eviction).

## API and views

- Async synchronous-view queries are logical; size and key removals are physical. Conditional
  value mutations and computes block on in-flight values. Logical reads may return the found
  future's value after replacement; `get(k, fn)` / `getAll` coalesce rather than recompute.
  Do not add a double-collect check or route `get` through the map's compute retry loop.
- `EntrySet.removeIf` predicates receive immutable snapshots and removal is conditional.
  Write-through entries belong to iteration, spliteration, and arrays. Preserve this in all
  four views.
- Map equality uses size, iteration over this map, and `count == expectedSize`. The final count
  prevents a surviving subset from passing after maintenance; do not replace it with CHM's
  two-sided iteration. The async future-typed view mirrors it.
- `getAll` is not atomic. Per-key null results omit that key; bulk maps reject explicit null
  values while valid entries may already have committed. An omitted bulk key means no value.
- Builder validation follows Guava, including duration/null-check order. `from(CaffeineSpec)`
  disables strict parsing for programmatic overrides; its disabled-eviction footgun is accepted.

Details: [async synchronous view](../docs/design-decisions.md#async-synchronous-view),
[iteration](../docs/design-decisions.md#iteration), and
[builder and bulk loading](../docs/design-decisions.md#builder-and-bulk-loading).

## Adaptive window

- Hold adaptation below half occupancy; discard filled samples without adapting. Do not latch
  the gate or narrow it to sketch initialization. `adjustment` carries unfinished transfers
  across maintenance cycles; a new sample replaces it. Transient negative policy weights can
  move region targets out of range; do not clamp the quota.
- Tiers use the configured maximum in native weight/entry units: slow reactive through 512,
  standard reactive through 4096, density above 4096. Density uses a 2% floor, 4× maximum
  sample period, gain 0.03, and 30% step cap. The reactive tier has no such floor; extending
  its runs requires revisiting that assumption.
- Density is resident-only and uses region setpoints, not occupancy. Preserve blind-corner
  probing, the refractory ladder, and the below-floor lift. A starved large region does not
  justify a probe. Count available samples before calling a slow descent defective.
- Probe, audit, and anchor state have different ownership and reference times. Preserve the
  shipped router order, frozen claim/baseline boundaries, separate crash and reversal bars,
  return retests, and confirm/refractory rules; do not unify them by appearance.
- Keep SLRU and its 80/20 split with one-hit promotion. Alternative splits, promotion gates,
  and plain-LRU main space have been measured. Probation is fixed near 19.8%; window growth
  borrows protected capacity, not probation.

Before a change, read [eviction](../docs/design-decisions.md#eviction) and
[climber review constraints](../docs/design-decisions.md#climber-review-constraints), then
[hill-climber](../docs/hill-climber.md) §4 (machine), §5 (rejected alternatives), and §6
(methodology). Run `/climber-gate` after a climber or resize change; use seeded adjudication
or N=8 for bimodal cells.
