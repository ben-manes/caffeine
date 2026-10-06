# Ruled Out

Adjudicated mechanisms and their scope. Most are accepted behavior; entries that describe a
repaired mechanism are marked as historical. Use the reason and current code, not an earlier
review's outcome.

**When to read this for an audit**: Phase 1.5, after your own findings are written down, alongside
`design-decisions.md`. Never before Phase 1 analysis. Read your module's section plus
*Standing principles*; the rest is not yours.

Optimization experiments read *Performance* before selecting hypotheses, to avoid repeating
adjudicated experiments. This does not change the independent first pass of an audit.

**What a match means**: label the finding "ruled out: <entry>" and **keep it in the report**.
A ruling is about a mechanism and a consequence. If you have the same mechanism with a
*different* consequence, a reachable trigger the entry does not name, or a configuration it
does not cover, that is a new finding and the entry does not dispose of it. Say which part
differs.

Check claimed public wording mismatches against the public text as well as the implementation
ruling. Acceptance of behavior does not establish that the public text describes it, but silence
alone is not a contradiction. Preserve explicit decisions not to add qualifications or warnings.

Rulings can be overturned. Address the stated reason when new evidence changes the consequence,
trigger, or configuration.

---

## Standing principles

These dispose of whole families. Check them first.

- **A JVM `Error` is not our problem.** OOME and SOE leave the JVM corrupt. A window that
  opens only between an OOME and the next statement is not a defect, and defensive
  containment for it is not wanted.
- **A contract-violating user component is the user's bug.** A hostile `CompletableFuture`
  (throwing `isDone`/`whenComplete`), a throwing `Ticker`, broken `equals`/`hashCode`, a
  mutating `Weigher`. Caffeine breaks reasonably and pushes back; it does not add defensive
  ceremony or a javadoc note. Precedent: Quarkus shipped a broken `CompletableFuture`,
  Caffeine pushed back, Quarkus fixed it.
- **Value-bearing callbacks propagate; fire-and-forget callbacks are guarded.** A loader,
  weigher, expiry or ticker returns a value, so there is no default to recover to and the
  throw propagates. Listeners and `StatsCounter` return nothing, so they are guarded. This
  asymmetry is deliberate; do not report it as inconsistency.
- **A broken or misconfigured executor is user error.** Silent-discard, `AbortPolicy` on a
  bounded pool, `shutdownNow()` dropping accepted tasks, a never-completing loader, or an
  `execute` that waits for its task or for queue room (one that joins the thread it dispatched
  to, a bounded pool whose rejection handler blocks). The executor is user-owned and its
  lifecycle is the user's choice. A waiting `execute` deadlocks because the drain task and
  eviction's removal notifications are submitted under `evictionLock` (`synchronization.md`,
  *notifyRemoval*). Submitting outside the lock would fix only the joining executor: maintenance
  runs on the executor, so a lone worker still waits on its own full queue. The same holds for the
  default common pool disabled JVM-wide (`java.util.concurrent.ForkJoinPool.common.parallelism=0`
  or a common-pool thread factory returning null), where maintenance, notifications and async loads
  stop. The library does not detect it ("Remove detection of JDK-8274349": the application is
  broken in every other common-pool use too); a parallelism check, a caller-runs fallback, the
  `AsynchronousCompletionTask` marker and routing through `CompletableFuture.runAsync` were
  declined. So was more `Caffeine.executor` javadoc for any of these cases, a rejecting or
  caller-runs executor running listeners under the eviction lock included: its existing caution
  and the internal guidance suffice.
- **Statistics are best-effort and lowest priority.** A counter that races, drifts, or
  double-counts is not a correctness defect. `CacheStats.missRate`'s
  `missCount >= loadSuccessCount + loadFailureCount` says a miss need not load (`getIfPresent`);
  a refresh or `asMap` compute counting a load without a miss does not make it a defect, and the
  same reading covers `loadSuccessCount`/`loadFailureCount`'s "always" wording, which the javadoc
  keeps deliberately rather than taking Guava's "usually".
- **Anything reachable only through `Cache.unwrap(...)` is out of scope.** By the JCache
  spec `unwrap` is an ill-defined hack; once used, behaviour is undefined, however real the
  symptom.
- **Do not construct the trigger and then report the result.** A `Factory` that calls
  `getCache`, a `Weigher` that mutates the cache, a loader that throws synchronously.
  Writing the misuse is manufacturing the defect. "Nothing warns the user" is not an
  argument: the omission is deliberate because the callback has no reason to do it.
- **Price severity on a production configuration.** A frozen `FakeTicker` and
  `executor(Runnable::run)` are instruments that manufacture impact nobody can reach. See
  `finding-taxonomy.md`, *Severity must be priced on a realistic configuration*. A real
  mechanism and a reachable impact are two separate claims.
- **Lossy and approximate by design.** Read-buffer drops, sketch counter saturation,
  eventual consistency of weight and size. Sub-1% hit-rate deltas on a probabilistic sketch
  are noise, not signal.

---

## Performance

These rulings concern an optimization mechanism and its evidence, not a permanent claim about
which code can become faster. Add a confirmed negative result with its affected path/configurations,
observations, reason for rejection, and the evidence that would justify reopening it. Include
enough detail to assess the ruling without local artifacts; raw runs stay in the experiment
workspace, and no `.local/` path is a durable evidence reference. Keep inconclusive measurements
labelled inconclusive rather than turning absence of a signal into a dead-code claim.

- **Caller-side relocation of constant-foldable expiration conditions is not demonstrated removed
  work.** `BoundedLocalCache.expireAfterUpdate` returns zero when variable expiry is disabled;
  `exceedsWriteTimeTolerance` returns false when write expiry, refresh, and variable expiry are
  absent. Duplicating those conditions in `put` changes compilation shape, but the disabled paths
  already have constant results. The source transformation and a better stress score do not
  establish useful work eliminated by C2. Reopen only with native-code evidence of an executed
  cost and paired confirmation across the affected read/write cells, not another source-layout
  sweep. A compiler-directed improvement with that evidence is a different claim.
- **Resident overwrite throughput does not establish write-queue contention.** In the size-only,
  strong-reference `GetPutBenchmark` configuration, existing unchanged-weight updates in `put`
  reach `afterRead`, without submitting an `UpdateTask`. Sharding the MPSC write queue cannot
  remove cost from an operation that does not enqueue there. Reopen for a workload that exercises
  actual write-buffer traffic, such as insertion or weight/expiry updates, with queue-specific
  contention or backpressure evidence. This does not rule out write-queue improvements generally.
- **Producer-side MPSC tricks in the read buffer do not help the cache.** Measured on an Apple M3 Max
  (JDK 27, 128-byte lines) with `GetPutBenchmark`-shaped read_only and readwrite cells, each arm a
  separate `RingBuffer`/`StripedBuffer` class selected per fork. On a quiet host, five paired rounds
  put a drain that never reads the write counter, with the array reference on the header's line and
  the array padded off the write counter's line, at +0.5% [-1.4, +2.5] (read_only) and -0.7%
  [-3.4, +2.0] (readwrite gets), and a cached consumer index beside the write counter (JCTools'
  `producerLimit`) at -0.1% [-1.8, +1.6] (readwrite gets). A FastForward slot check in place of the
  read counter also gained nothing and is lossy under a producer stall. The drain runs in scheduled
  batches, so the counter lines move once per batch per stripe; in saturation about 98% of offers
  return `FULL` from L1-resident lines. Only a stress where one producer races a continuously polling
  consumer rewarded keeping the consumer off the producers' line (offers from 33-54 ns to 4 ns).
  Reopen for a design that drains continuously against producers.
- **A different stripe hash only reshuffles which thread ids collide.** A one-multiply Fibonacci hash
  in place of `mix64` confirmed at +5.5% [+0.4, +10.9] on read_only and +6.3% [+2.2, +10.6] on
  readwrite gets (10 pairs), but because JMH's worker ids (31-38) share two home stripes under
  `mix64` and one under the new hash in the 16-stripe table the benchmark settles at. It is not a
  latency effect: the non-recording offer path moved +0.6% [-4.0, +5.4]. Over arbitrary runs of
  consecutive ids both hashes collide equally at 16 stripes, and the new hash is worse for
  power-of-two id strides. Growing the table on the first failed home CAS, which removed every home
  collision for those ids at 64 stripes, moved readwrite gets +3.0% [-1.1, +7.2] (5 pairs):
  inconclusive, and it creates more stripes. Reopen with a per-stripe CAS-failure count showing that
  home collisions cost throughput across thread-id sets, not for one benchmark's ids.
- **Plain slot clears in `BoundedBuffer.drainTo`.** Clearing each slot with a plain store instead
  of `setRelease(null)` is correct, since the release store of `readCounter` after the loop orders
  the clears, and on aarch64 it roughly halves the drain's cost per record: C2 emits each release
  store as a barrier plus a store, and the barrier waits on the previous element's `onAccess`
  writes to node lines that readers hold. Rejected anyway: tooling flags plain stores in the ring,
  and the gain is only maintenance time. When unsaturated, readers are unchanged. When saturated,
  the cheaper drain records more reads, so GetPut read cells score lower. Don't re-raise without
  a user-visible cost that the release clears cause.
- **Avoiding the policy's writes to hot nodes' cache lines.** A hit loads the node's `value`, which
  shares a line with the access-order links that `moveToBack` rewrites on the node, its neighbors,
  and the old tail, so a saturated read benchmark pays coherence misses on its hottest keys (the
  cost was never isolated). Deduplicating reorders within a drain needs a membership structure,
  such as a set of nodes or key hashes, whose allocation and lookup cost more than the moves; the
  sequential check is already there (`moveToBack` skips the tail). Moving the links off the node
  adds a mapping and a structure to size. In applications, the work between reads absorbs the
  invalidations. Accepted as the cost of LRU ordering.

---

## Core

**Eviction and maintenance**

- Colliding keys inflating a new candidate's frequency so that TinyLFU admits it outright, before
  the jitter is consulted. Keys with equal `hashCode()` share every sketch counter, so distinct
  one-shot colliding keys read frequency 15 and displace colder probation victims; at 1% of traffic
  on a Zipf 0.99 workload with a 10,000-entry cache they cost about 4.6 points of hit rate
  (research-foundations, *Security*). The jitter exists to let an inflated entry be evicted, not to
  prevent its admission: an admitted candidate becomes an inflated probation victim like any other,
  and keys that are not reused never reach protected. No seed separates equal hash codes, and
  naming the candidate side in `BoundedLocalCache`'s class comment was declined.
- A weight change replayed after the fact evicting the mapping that replaced it. A weighted
  entry rewritten oversize and then rewritten small again can be removed as SIZE once the
  buffered deltas drain, because `UpdateTask` decides from the accumulated `policyWeight`
  while the node already carries the new weight. Deterministic with a discarding executor:
  under `maximumWeight(100)`, three puts of weight 1, 1000 and 1 leave the cache empty, and
  the notification carries the small value. `AddTask` does the same from its captured insert
  weight when an oversize insert is rewritten small before the drain (puts of 1000 then 1).
  `weight` and `policyWeight` are owned by
  different locking protocols, so the replay is inherent, and the javadoc's "may evict an
  entry before this limit is exceeded" covers the result. The price is an extra miss on a
  self-healing transient. Deciding either task's check from `node.getWeight()` closes only
  that path: the same replay inflates the global `weightedSize` across drain cycles and
  evicts through `evictEntries` instead, measured still losing the entry in 2 of 3
  default-executor runs, so it is a special case rather than a repair. Resurrecting a victim
  when size eviction no longer holds has the same gap, and does nothing for an update that
  is not itself oversize and pushes other entries out. Weight-0 pinning is unaffected, which
  is the case that would have made it more than premature eviction.
- `lock()`'s timed retry never reaching its uninterruptible fallback while another thread
  re-interrupts the writer faster than it loops, since `tryLock` checks the flag before trying.
  The loop rides out ordinary interrupts and restores the flag; a storm is a constructed trigger,
  and the writer acquires once it stops.
- The `Caffeine` class javadoc's list of configurations that "perform periodic maintenance"
  omitting `refreshAfterWrite`. A refresh-only cache is bounded so that it can refresh and
  submits a drain after writes, but that drain has nothing to evict, expire or collect; the list
  names maintenance that does work.
- Replacing `EvictContext.removed` with `ctx.cause != null` at `evictEntry`'s tail, as the
  siblings classify into a local cause. Equivalent today only because the resurrect return
  comes first; the explicit flag keeps the tail correct if an edit sets a cause on a path that
  neither removes nor resurrects, which is worth more than one field.
- Size eviction is uncapped and drains the whole excess in one cycle under `evictionLock`.
  Eager shrink is the published `Policy.Eviction.setMaximum` contract, and the uncapped
  property is load-bearing for the `rescheduleCleanUpIfIncomplete` piggyback: size eviction
  never arms the pacer, so a capped drain would be the one backlog shape with no driver.
- A `setMaximum` shrink below a resident's weight evicting entries that fit before it reaches
  that resident. Only the candidate has an oversize check, and every write that grows a weight
  past the maximum evicts that entry at once, so a shrink is the only trigger. The resident goes
  when the victim cursor reaches it: in probation when it loses a frequency duel, in protected in
  LRU order whatever its frequency, so a hot one can outlast the rest of the cache. The eager
  shrink holds, and weight "has no effect on selecting which entry should be evicted next". With
  Pareto weights capped at 3% of the old maximum no resident was oversized down to a 3% shrink,
  and at 2% and 1% the kept weight matched evicting the oversized residents first; only
  constructed populations flush (20-51 of 500 kept). Mirroring the candidate check on the victim
  fixes a probation victim only; a protected resident needs an O(n) pass per shrink.
- Without a `Scheduler`, maintenance is amortized onto callers and a quiesced cache stays
  over `maximumSize`. A `Scheduler` requests prompt expiration; a size-only cache has no pacer. The
  quiesced excess is capped by the write buffer (`estimatedSize() <= maximum + WRITE_BUFFER_MAX`),
  because a full buffer forces `afterWrite`'s inline assist. The live peak under concurrent
  writers is `maximum + 2 * WRITE_BUFFER_MAX + 1` plus the writers, since a drain applies its
  whole batch before evicting. The same debt can keep a removed value reachable: a removal that
  lands after a cycle's write-buffer drain leaves its `RemovalTask` queued, and the retired node
  holds a strong value until the next operation or `cleanUp()`. The removal is already notified,
  and weak or soft values are cleared at retirement. Do not add a third re-arm and do not move the
  resubmission into `PerformCleanupTask`; both were built and declined.
- `rescheduleCleanUpIfIncomplete`'s `!pacer.isScheduled()` gate deferring a REQUIRED backlog
  to the pacer's horizon is the same design. Same for the executor-reject catch in
  `scheduleDrainBuffers` not calling it.
- `rescheduleCleanUpIfIncomplete` missing a concurrent write's re-arm. Its inspection holds
  `evictionLock`, so a writer that sets REQUIRED after the status read can fail to schedule a
  drain. Accepted: the next write re-arms, and any debt left at quiescence is bounded by the
  write buffer. The controlled witness paused a generated subclass; 60 ordinary-runtime bursts
  did not reproduce it. Size-only caches have no pacer, as described above.
- The expiration and window scans are O(N) in the pending-async population. The walk is real
  (100k pending cost ~410 us per `cleanUp`). The expiration scans relink pending entries to the
  MRU end, so one completed entry takes `cleanUp` from 410,208 ns to 41 ns. `evictFromWindow`
  relinks nothing, so without `expireAfterAccess` a never-completing future or a weight-0 entry
  is walked on every over-budget cycle. Callers are unaffected unless writes saturate the write
  buffer (then 14-35x slower); the price is maintenance that never idles, a full core at 200k
  such entries even at 1k writes/s (18-30% at 10k). Both candidate fixes measured worse, and a
  pending-only relink would not reach weight-0 pinning. Residue that is real and documented:
  under `executor(Runnable::run)` the cost is linear per pending entry, so a burst is quadratic.
- Recursive and nested maintenance in `afterWrite`.
- Maintenance that a read inside a computation or eviction listener runs inline on a caller-runs
  executor, and the corruption or deadlock that follows; see
  [ConcurrentHashMap Constraints](design-decisions.md#concurrenthashmap-constraints).
- Expired entries persisting in an idle cache.
- `FrequencySketch.reset()` sweeping under `evictionLock` (238 ms at 100M). Amortized to
  0.24 ns/read, once per 1e9 reads. Chunking lowers quality; SIMD is the answer.
- `FrequencySketch.reset()`'s signed-shift quirk at extreme `maximumSize`, and
  `ensureCapacity` keeping a stale large `sampleSize` after a `setMaximum` shrink.
- `FrequencySketch` table/blockMask race via the `Policy` API. It is entirely under
  `evictionLock`: single-writer under one lock cannot race, and escalating it is a false
  positive.
- Transient negative `weightedSize`, and transient negative `policyWeight` over-shifting the
  climb transfer quotas or region caps. Convergence is via the telescoping sum; verify that
  instead. Confirming at a negative window can leave an anchor held but unplanted under its
  sentinel test. This lies outside the fuzzer's bounded geometry; no distinct harmful adaptation
  consequence was established, so the flag pairing alone does not justify a policy change.
- `DENSITY_EPSILON` attenuating proportional steering at extreme weight maxima. Production
  consumers use the verdict's sign; `steeringError()` contains no epsilon. Floating-point
  rounding can tie sufficiently close densities, but no harmful reachable tie was established.
- Weight=0 entries are a user-facing pinning feature.
- In-flight async entries are uncounted by any bound (weight 0 plus `ASYNC_EXPIRY`).
- The eviction-listener same-key mutation "corrupting or silently losing the write".

**Buffers and queues**

- `MpscGrowableArrayQueue.resize` stranding the odd producer-index marker on a non-OOME
  throw. It is a shaded JCTools port and is not wrap-safe; upstream master is identical. It
  needs 2^62 offers on one never-reset queue, millennia at 10-20M put/sec. Do not harden it:
  `(pIndex - cIndex) < bufferCapacity` does not suffice because `producerLimit` stores the
  same wrapping sum, and rolling the index back strands the consumer, which is worse.
- `StripedBuffer.offer` treating `FULL` as success and expanding only on `FAILED`. A failed
  CAS is a contention hint and may be spurious; a full buffer means the drain is behind, which
  striping does not fix. The `FULL` return is the signal that tells `afterRead` to drain.
- Replacing the read buffer's weak CAS solely because it can fail spuriously. No correctness
  failure or material cost was established; stronger CAS needs a measured benefit, not an
  assumption that its cost is identical on every architecture.
- A producer spinning on another producer's write-queue resize, including from an inline removal
  listener holding `evictionLock`. The resizer needs no cache lock and clears the marker before
  scheduling maintenance, so this is an owner-progress stall, not a lock cycle. Growth ends at
  maximum capacity; `onSpinWait()` would retain the dependency and has no demonstrated benefit.
- A thread's starting stripe never moving. `ThreadLocalRandom.getProbe`/`advanceProbe` are
  package-private to `java.util.concurrent`, so the permanent-move search is unavailable.
  The only alternative is a `ThreadLocal` on the read hot path.
- `BoundedBuffer.RingBuffer` stripes unusable during a producer stall, and
  `drainTo` leaving the slot nulled before `consumer.accept` stalls `readCounter` on a
  consumer throw.
- `StripedBuffer.expandOrRetry` attaching a stripe with a plain array store while the `RingBuffer`
  constructor stores its write counter plainly. A producer racing the attach can step that counter
  back and drop a bounded number of read records on the stripe until it passes the read counter;
  the drain does not spin and producers do not block, so the cost is the lossy read buffer's.
- Neither write-buffer consumer waiting for a producer's publication (`relaxedPoll`). The
  task is not lost: `scheduleAfterWrite` runs after `offer` returns and re-arms. Where a
  weak-memory interleaving defeats that re-arm (the IDLE strand below), the task waits in the
  buffer for the next write, `cleanUp`, or a read that fills a stripe.
- `clear()`'s write-buffer drain loop being unbounded under `evictionLock`. Its `AddTask`s can
  accumulate sample observations without `climb`, so sample counters have no all-history bound;
  no practical long-counter overflow or impact was established.
- The write buffer's backpressure is a capacity limit, not a CAS.
- The constructor's read-buffer and access-policy conditions not naming `expiresVariable()`.
  Variable expiry shares the `A` classes with access expiry, and their `expiresAfterAccess()`
  (`timerWheel == null`) answers true inside the `BoundedLocalCache` constructor because the
  subclass assigns `timerWheel` after `super`, so a variable-expiry cache gets both. Naming
  `expiresVariable()` would not help: it reads false at that point.

**Timing and arithmetic**

- `EXPIRE_TOLERANCE` (1s) inexactness. Expiration is a maximum lifetime, not a minimum hold
  time; entries may expire up to 1s early from timestamp tolerance. Applies to `writeTime` reorder
  decisions and read-path `accessTime` and `variableTime` updates; a read that shortens the
  variable deadline is always stored, so the tolerance never makes an entry expire late. Read-extension's accepted over-stay is a
  separate race described in [expiration](design-decisions.md#expiration).
- The fixed and refresh `ageOf` accessors returning empty for a present entry whose timestamp a
  concurrent read or write moved past the query's clock, while `getEntryIfPresentQuietly` and
  the ordered snapshots clamp that negative age to 0 and report the entry. Both are deliberate
  ("hide incomplete mappings from policy metadata queries"; `ageOf_negative`): the accessor
  answers for its own instant, where that stamp is not yet visible, as it does for a pending
  mapping, and the entry view reports a present entry with its full duration.
- Expiration eviction capped at `EXPIRATION_THRESHOLD` (1000) per cycle, re-armed via
  `PROCESSING_TO_REQUIRED`. The wheel rewinds `nanos` and re-links the remainder, so a
  `schedule` inside that window measures against a behind clock. The budget counts only
  evictions, never the cascade, and a cascade cap must not reuse the rewind (it livelocks).
- Collected references drained at `REFERENCE_THRESHOLD` (1000) per queue per cycle, counting
  polls rather than evictions. The cap bounds the lock *hold*, not a waiter's *wait*; the
  lock is not fair, so a backlog still monopolizes it. That residual is not the cap failing.
- `nanoTime` overflow at ~73 years.
- `TimerWheel.advance` returning delta=0 within a tick. The rebased `-1 -> 0` crossing is a
  tick boundary and returns 1; see [TimerWheel](design-decisions.md#timerwheel).
- A dropped read-buffer offer deferring a variable-expiry reschedule. A read that shortens the
  duration CASes `variableTime` but leaves the node in its previous deadline's bucket, so the
  entry stays resident and unreadable until that bucket is swept (measured: 3574s against a 3s
  request, with a `Scheduler` arming 3569s out). Bounded by the previous deadline, so it never
  extends an entry's life; see [TimerWheel](design-decisions.md#timerwheel).
- A dropped read-buffer reorder leaving a live `expireAfterAccess` head in front of expired entries.
  The scan moves a live head to the back only when its access time is newer than the tail's, so
  when the tail was read later, the entries behind a head whose reorder was lost wait for that
  head's next recorded read or its own expiry. Measured with twelve threads keeping their
  read-buffer stripes full, the delay exceeded a second; without that contention it stayed under
  25 ms. The timer wheel sweeps by bucket and has no such stop; see
  [TimerWheel](design-decisions.md#timerwheel). The write-order deque has the same face: a write
  time kept by the tolerance plus a weight change's `UpdateTask` reorder can leave an expired node
  behind a live head until that head expires, under the tolerance.
- `Pacer.calculateSchedule`'s 0L sentinel collision.
- `Pacer.schedule`'s reschedule arm must call `cancel()`, not `future.cancel(...)`: the
  immediate-scheduler recursion guard is `future == null && nextFireTime != 0L` and only
  `cancel()` reaches it. Do not simplify it back. For a custom scheduler executing through an
  inline executor, recursion must terminate; autonomous rearming of a deadline discovered inside
  that callback is not guaranteed. This includes a scheduler that honors the requested delay.
  Later independent scheduling can recover; synchronous scheduling is not itself a contract violation.
- `Pacer` skipping its re-arm when the scheduler fires before the cache ticker reaches
  `nextFireTime`. The executing future still appears pending, so maintenance waits for the next
  operation. A one-second-granularity ticker reproduces; `systemTicker` and a one-millisecond
  cached clock did not in 20 trials each. Tickers are expected to advance between reads; a cheap
  clock can increment per read and resynchronise periodically. The `fired` flag was declined
  for this coarse-clock trigger, and for a monotonic `Ticker` that runs slower than the
  scheduler's clock, whose lag grows with the delay: `Caffeine.ticker` states a testing intent,
  the default pairs two `System.nanoTime` clocks, scheduling is best-effort, and the loss ends
  at the next cache operation.
- `Pacer` self-poison ordering (`nextFireTime` committed before `scheduler.schedule()`).
  User schedulers get `GuardedScheduler`'s no-throw/no-null guarantee; built-ins satisfy it
  directly. Do not add a catch for an unreachable synchronous scheduler failure.
- `forScheduledExecutorService` allowing a standalone shutdown-race rejection to escape. Its
  factory description states the task-drop policy, not blanket exception suppression;
  `guardedScheduler` supplies that guarantee and configured caches already use it. The shutdown
  precheck avoids log noise during orderly shutdown (#1449), without making submission atomic.
- A nested wheel sweep transiently cancelling the pacer while a bucket is detached. The normal
  outer pass recomputes scheduling, and listener/counter exceptions cannot bypass that step because
  they are caught. No supported escaping callback was established; broken keys, hostile futures
  and JVM failures remain within the standing exception exclusions.
- `TimerWheel.Traverser` detecting concurrent modification via `nanos` rather than a
  `modCount`, unlike the deque-backed `Policy` families. The "spins forever holding
  `evictionLock`" consequence is a frozen-ticker artifact: `advance()` sets
  `nanos = currentTimeNanos` unconditionally, so under `systemTicker` a re-entrant operation
  reaching maintenance throws CME. Two do not: `clear()`, whose result is a truncated snapshot,
  and one whose maintenance cycle exhausts the expiration budget, since the rewind restores the
  `nanos` the traverser captured. That second route need not truncate: when the write that reached
  maintenance rescheduled the node the traverser returned last, as a per-element `setExpiresAfter`
  does, the walk follows it into its new bucket and yields that bucket again on every
  budget-exhausting cycle, throwing CME on the first cycle that leaves budget unused. Measured on
  the system ticker and common pool, with a mapping function calling `setExpiresAfter` per element
  and 6,000 entries due: 12,294 elements over 2,049 distinct keys (the write buffer's capacity plus
  one), each six times (the backlog over the 1,000-entry expiration budget), then CME. Both results
  fall within the best-effort view the `Policy` snapshot entry under *Views, iteration, and the Map
  contract* accepts, for a computation `Policy` already documents as throwing CME when it
  detectably writes an entry.
- `TimerWheel.expire()`'s catch block holding a stale `prev` pointer.

**Node lifecycle and access modes**

- Opaque `accessTime` writes, including "it avoids cache-line invalidation" framings and
  hot-entry true-sharing under `expireAfterAccess`. Deliberate, to avoid contention storms.
- Drain status terminal arms that skip the CAS, and a stale opaque read settling IDLE with a
  buffered task.
- `scheduleAfterWrite`'s weak-memory IDLE strand.
- `maintenance`'s entry store overwriting a writer's `PROCESSING_TO_REQUIRED` while the drain
  passes over that writer's slot, so the exit swap settles `IDLE` with the task buffered. It is the
  executor-run maintenance task's route to the same end state and heals the same way; jcstress
  reached it on aarch64 at about two per million racing pairs, where the direct `cleanUp` route
  measured about four per ten thousand. Two unmeasured routes the JMM permits end the same way or
  milder: `scheduleDrainBuffers`' blind `PROCESSING_TO_IDLE` store erasing a `REQUIRED` that a
  writer set after the store's status read, and `rescheduleCleanUpIfIncomplete`'s post-unlock
  opaque read missing a writer's `REQUIRED`, which stays `REQUIRED` for the next read or write.
- Weak key identity semantics. Historical cleared-reference aliasing was harmless to the
  interner's uniform values; cleared references now compare equal only to themselves. See
  [refresh internals](design-decisions.md#refresh-internals).
- `weakKeys()` spliterators advertising `Spliterator.DISTINCT`. `IdentityHashMap`, the class
  the `weakKeys` javadoc names as its model, does the same on key and entry and omits it on
  values. Removing it makes `distinct()` merge distinct live entries.
- Bulk `Iterable` operations collapsing equal-but-distinct keys under `weakKeys()`. Measured
  2026-09-08 on two live keys equal by `equals` but distinct by identity: `refreshAll` issues one
  load and leaves the second entry stale, `getAll` issues one load and leaves the second key
  absent, `getAllPresent` reports one of the two. None of these methods documents the type of the
  map it returns, and a `Map<K, V>` cannot hold both keys, so the collapse is undefined behaviour
  rather than a defect (Ben, 2026-09-08). A caller who needs both operates per key; `refresh(key)`
  reaches each one. The equals-based dedup in `refreshAll` is not incidental either: dropping it
  costs a second load for a key repeated in the input, on the default and direct executors alike.
  `invalidateAll(Iterable)` returns void, has no such limit, and does reach both keys. A bulk
  loader's result is a `Map` too: the asynchronous `getAll` snapshots it with `Map.copyOf`, which
  rejects an `IdentityHashMap` holding equal-but-distinct keys under either key strength, so that
  load fails loudly while the synchronous path, which iterates without a snapshot, stores both.
  Loaded entries are stored as returned, as Guava's `getAll` stores them: a requested hit the
  loader also returns comes back with its pre-load value while the loaded one replaces it (or is
  evicted as oversized), and under `weakKeys()` a rebuilt key is a distinct entry, so an
  over-delivered hit is duplicated and the synchronous path's requested key misses its own load.
  Weak keys and bulk loading follow Guava's precedent rather than a model of their own (Ben,
  2026-09-16). Guava's `getAll` returns an equals-based, ordered `ImmutableMap` and dedupes by
  `equals`; Caffeine returns a `LinkedHashMap` because users asked for that ordering, an identity
  result would need an `IdentityLinkedHashMap` the JDK lacks, and neither project has had a user
  report of what weak-key users expect. Match Guava's observable behavior before proposing identity
  semantics for a bulk path; the facade's `IdentityHashMap` copy of a loader's result does that for
  the extras Guava stores.
- Weak/soft value wrappers allegedly reaching their reference queue before the bookkeeping key
  is initialized. No supported-runtime null-key dequeue or cache failure has been established;
  the abstract construction window alone does not justify a change. Reconsider on a concrete
  runtime witness. This is separate from the fixed predecessor-clear ordering issue (#1820).
- Weak-key lookups allocating a `LookupKeyReference` (24 B/op). A thread-local mutable
  wrapper pins the instance to the thread, rejected in #294 for virtual threads and
  classloader pinning. Young-gen allocation is the better trade.
- `Interner.newWeakInterner()` losing `ConcurrentHashMap`'s tree-bin ordering for `Comparable`
  elements, so interning many same-hash strings is O(n) each: 4.7 s for 16,384 against 10 ms for
  the strong interner. Guava's weak interner chains its bins and degrades for every key type, and
  no contract promises collision resistance. Comparable wrappers are not a repair: CHM orders only
  a probe and stored key of one class, and a cleared referent cannot be ordered, so later inserts
  are tie-broken around it and, once it drains, live keys sit on the wrong side of each other.
  Measured with one comparable weak class in a plain CHM, that missed live keys and inserted
  duplicate equal keys in every trial; without comparison, or without clearing, never.
- A never-completing async or refresh loader retaining weak keys and values via callback
  capture.
- `BoundedLocalCache.containsKey` and `EntrySetView.contains` lacking an `isAlive()` filter;
  `computeIfPresent`'s fast path bypassing `requireIsAlive` for value==null nodes;
  `getKey(K)` lacking expiry, value and alive filtering.
- `BoundedLocalCache.put` missing an `isAlive()` re-check after the `Expiry` callback.
- `put` and `putIfAbsent` weighing and dating a candidate node before `data.putIfAbsent`, so a
  writer that loses that race has called `Weigher.weigh` and `Expiry.expireAfterCreate` for a node
  it discards (two of each for one installed entry, where `computeIfAbsent` makes one). The node
  must be complete when that call publishes it, and neither callback promises one call per entry.
  The same placement dates a new entry from the call, so an insertion that waits on another
  thread's bin or node monitor publishes it aged by the wait; see
  [expiration](design-decisions.md#expiration).
- `BoundedLocalCache.replace(K, V, V)` calling the weigher before the oldValue check.
- `BoundedLocalCache.getIfPresent` casting the lookup `Object` to `K` for
  `tryExpireAfterRead`.
- Read-path expiry extension resurrecting a just-expired entry.
- A read that returns a value already reported EXPIRED, during a concurrent rewrite. Real and
  historically deferred, then repaired by the timestamps-before-value protocol. The protocol
  orders a timestamp stored by the rewriting thread; a fixed `expireAfterAccess` reader's opaque
  `accessTime` store is ordered after the value only on multi-copy-atomic hardware (x86, ARMv8),
  not on POWER, where a third reader can pair it with the superseded value. Unmeasured and not
  repaired: POWER is not a tested target.

**Refresh**

- `refreshIfNeeded` being lock-free. A stale observation can fire `asyncReload` on a
  just-retired node; the completion-path ABA guards (`currentValue == oldValue` plus
  `(node.getWriteTime() & ~1L) == writeTime`) discard the result. The rare spurious loader
  call is accepted to keep the fast path lock-free.
- The "exceptions logged and swallowed" Javadocs on `LoadingCache.refresh` and the loader
  `asyncReload` methods when producing the future throws synchronously. The notes describe the
  refresh itself, not producing its future. Preserve the Javadocs; a construction-time throw
  remains a distinct caller failure.
- The `refreshes` to `data` lock inversion deadlock.
- `LocalAsyncLoadingCache.refresh(key)` retrying without bound or backoff. Each pass rereads,
  so a retry needs the entry to change between the two probes. The exception was a completed
  future holding no value, which satisfied both probes at once and could not terminate; the
  optimistic path now treats such a mapping as absent.
- Refresh eligibility using strict `>` while expiration uses `>=`.
- Refresh discard notification using the discarded value; refresh commit failure not
  surfaced on the future; `discardRefresh`'s `containsKey` prescreen missing a CHM
  `computeIfAbsent` reservation; `discardRefresh` invalidating a newer refresh generation.
- A rejected reload notifying again for the captured old instance after its removal. Returning
  that instance offers it for retention again, so rejection disposes of the new offer. Disposal
  after a throwing user weigher/expiry aborts installation is outside the cache's responsibilities.
- A successful refresh completion discarding a successor that registered after it published the
  new value, so that successor's reload is declined and the value waits for the next refresh.
  Every bounded update of an existing entry has that overlap between publishing its value and
  releasing the registration. Releasing only the completing token also keeps successors that
  loaded the replaced value, which later refreshes then join; see
  [refresh internals](design-decisions.md#refresh-internals).
- A manual `refresh(k)` that started while the key was absent committing across a racing
  `put` then `invalidate`. Its only ownership test is `currentValue == oldValue`, vacuous at
  `null == null`, and the prescreen cannot see the registration reservation. Reproduced 5/5
  with the loader running inside `refreshes.compute`, discarded 0/5 on a pool executor, and
  the history is still a legal linearization; see [refresh internals](design-decisions.md#refresh-internals).
  The same holds for a present start whose racing writes end on the instance it read, which
  the identity test cannot tell from no write.
- `UnboundedLocalCache.discardRefresh` removing unconditionally, so a write waits out an
  inline refresh load on the same key (measured 2004 ms). It is a light `ConcurrentHashMap`
  wrapper and CHM's `put` is pessimistic regardless, so the bounded cache's prescreen is not
  a repair to port. A write to any key in the same `refreshes` bin also waits, so during k
  inline reloads about k/16 of writes stall in a 16-bin table; with the default executor the bin
  is held only for the submission (76 us measured). The prescreen would also bring the bounded
  cache's reservation-window escape, which the unconditional `remove` waits out.
- Removing the bounded `discardRefresh`'s `containsKey` prescreen as redundant work. `remove`
  reaches CHM's `replaceNode`, which returns without locking only when the target bin is empty,
  so an absent key that collides into an occupied bin still takes the bin monitor and walks the
  chain. The lock-free probe buys that miss, and on a hit it is not wasted either, since it warms
  the bin `remove` then touches. The `RedundantCollectionOperation` suppression is deliberate.
- A same-instance refresh leaking the completed future in `refreshes`.
- A user-initiated `LocalLoadingCache.refresh` lacking the `getWriteTime() == writeTime` ABA
  guard.
- `put()`'s insert path missing `discardRefresh`.
- `doComputeIfAbsent`'s first-load path not calling `discardRefresh` when the mapping
  function throws.
- Quiet refresh or async completion skipping the timer-wheel reschedule under variable
  expiry plus `refreshAfterWrite`.
- Refresh only triggering on access, and returning the stale value rather than the fresh one.

**Views, iteration, and the Map contract**

- `ValuesView.remove(o)` testing the node's current value and conditionally removing the
  iterator's captured value, so a writer alternating A and B can make `remove(B)` delete a
  mapping holding A. Measured on the bounded cache, 8,254 of 710,733 explicit removals
  against a B-only control of 0. Not a defect: the predicate established the node's value was
  equivalent to what was passed, and a conditional removal against either operand means
  "remove this entry if it still holds an equivalent value". The outcome is the ordinary
  value-changed-mid-call one, and `ConcurrentHashMap` produces it too, more freely, since its
  iterator removes by key unconditionally (`replaceNode(p.key, null, null)`) where ours is
  conditional. Two consequences that look distinguishing are not: failing to remove a
  stably-present B happens under CHM and under a matched-operand variant as well. The
  per-node predicate is deliberate, from "Fix removal in identity views", which gave weak and
  soft value caches `IdentityHashMap` equivalence; do not revert it to `o.equals(value)`. The
  open direction, if this is ever revisited, is the opposite one: removing by key only, to
  match what `AbstractCollection` does for non-concurrent usage, against which CHM's
  conditional `removeIf` is the counterweight.

- Map, entry-set and entry-object equality disagreeing under `weakValues()`/`softValues()`.
  The value-bearing queries are identity-based and `equals`/`hashCode`/emitted entries are
  `equals`-based, so `entrySet().equals` is false one way and true the other while `equals` is
  true both ways. Guava's `weakValues()` cache reproduces the row exactly, and `IdentityHashMap`
  buys coherence only by being asymmetric against a `HashMap` instead. Identity belongs to
  one-sided queries and never to a bilateral contract; see
  [iteration](design-decisions.md#iteration).

- `clear()` attributing `EXPLICIT` where `invalidateAll(keys)` attributes `EXPIRED` for an entry
  crossing its deadline during the sweep. The captured clock amortizes the ticker call under the
  eviction lock, and the straggler fallback reads per key anyway, so the same `clear()` reported 5
  `EXPLICIT` then 199,995 `EXPIRED` under a concurrent writer; see
  [expiration](design-decisions.md#expiration).
- `getAllPresent` and `containsValue` using one scan-wide `now` for every element's expiry
  check. This covers bounded single calls whose staleness window is the call. It does **not**
  extend to a user-paced traversal (an iterator or spliterator), where a slow terminal
  operation makes the window unbounded and `EntryIterator.hasNext()` reads per element.
- `AsMapView.KeySet.remove(k)`, `removeAll`, `removeIf` and `retainAll` bypassing the
  block-on-in-flight contract.
- View bulk-removal infinite-looping with a write-back removal listener.
- `clear()` / `invalidateAll()` preserving nodes recreated after its snapshot. Identity-checked
  removal targets the captured node and avoids reprocessing listener write-backs (#872).
  The by-key straggler pass and `invalidateAll(keys)` can remove newer mappings; these operations
  need not remove the same generation during concurrent writes.
- A prefetched iterator cursor returning an entry a fresh traversal skips.
- `ConcurrentMap.getOrDefault` not being overridden; the inherited default performs one `get` call.
- `ConcurrentMap.remove(k, null)` returning false rather than throwing NPE.
- `entrySet().add` throwing UOE rather than putting through. It matches
  `ConcurrentSkipListMap` and pre-v8 CHM; CHM's put-through violates `Set.add` by returning
  false yet replacing.
- Lazily created views (`AsyncCache.asMap()` and `synchronous()`, the synchronous view's `asMap()`,
  the Guava facade's `asMap()`) stored without synchronization, so racing first callers can
  receive distinct, equivalent instances (1 to 6 trials in 50,000). `ConcurrentHashMap.keySet()`
  and Guava's cache `keySet()` publish the same way; Guava's `asMap()` is the cache itself.
  Capture each lazy holder once and return that local or the newly created instance. Final-field
  initialization protects a view's backing state, but does not make repeated plain holder reads
  coherent; a second read could return null after the check observed a peer's initialized view.
- The access-reset javadoc ("reset by all cache read and write operations") not naming
  `containsKey`, `containsValue` and `forEach`. A cache read means get-style access, as its get and
  put examples show, and membership checks and traversal stay quiet by design; enumerating the
  exceptions across the four javadocs was declined.
- Every `Policy` map overload (`coldest`/`hottest`, their weighted forms, `oldest`/`youngest`)
  collapsing equal-but-distinct weak keys. The collecting `LinkedHashMap.put` keeps the first
  key instance with the later key's value, a pair the cache does not hold, and the limit or
  weight budget counts both, so the map can come back short. The `Map` cannot hold both keys,
  so which pair survives is the same undefined collapse; the `Stream` overloads return all.
- `Policy` snapshots pairing a value with a weight from another moment. The snapshot reports the
  policy's weight under `evictionLock` while writers publish values under the node's monitor, so
  a concurrent update can hand `coldestWeighted`/`hottestWeighted` a stale weight, and the
  expiration timestamps are read after the value. It is a best-effort view of what the policy
  sees, which also omits an entry whose `AddTask` is still in the write buffer when the
  snapshot's maintenance pass ends; synchronizing every node to pair them was declined.
  The same replay can hold a weight no entry has, which the snapshot clamps into the `int` range: a
  negative transient reads as 0, and an over-count such as `2W - w` stays within this ruling.
- `expireAfterAccess().oldest()` misordering an evicting cache's entries. The snapshot merges the
  window, probation and protected deques by access time, but probation receives window victims and
  protected demotions out of access order, and a pending async entry's `ASYNC_EXPIRY` access time
  holds back the rest of its deque until the others are exhausted, after which the entry is
  filtered out. The order is the policy's best guess; each entry is still listed once.
- Message-less `requireArgument` on public API.

**Notifications**

- `notifyEviction` to `discardRefresh` ordering, and an exception during user
  `equals`/`hashCode`.
- The unbounded cache notifying with the caller's key instance, losing the notification when a
  cross-type equal key reaches a typed listener. `ConcurrentHashMap` never returns a stored key,
  and recovering it needs a scan or a per-entry node; see
  [CHM constraints](design-decisions.md#concurrenthashmap-constraints).
- `AsyncRemovalListener` notification on executor rejection.
- `LocalCache.notifyOnReplace` dropped when both old and new are async futures and the old
  completed exceptionally.
- A same-instance write over an expired entry notifying `EXPIRED` for the value it reinstalls. The
  mapping expired, and maintenance reaping it before the write delivers the same notification, so
  suppressing it would only make the notification depend on timing. `notifyOnReplace`'s identity
  check is for replacing a live value, which removes nothing, and on `compute` and
  `computeIfAbsent` the eviction listener runs before the function returns its value.
- Historical `afterWrite` inline-fallback loss when maintenance throws. The repair runs
  the write's own task in `maintenance`'s `finally`; buffered work remains deferred.
- `RemovalCause.EXPLICIT` and `REPLACED` saying "by the user" while an automatic refresh notifies
  both (`REPLACED` for a reloaded value, `EXPLICIT` for a `null` reload that removes the entry).
  "By the user" separates the two non-eviction causes from the eviction ones, and
  `Caffeine#refreshAfterWrite` delegates its semantics to `LoadingCache#refresh`, which both lists
  name; Guava's identical wording describes its identical refresh. Naming `refreshAfterWrite` in
  either javadoc was declined.

---

## Async

- In-flight entries uncounted by any bound; async sync-view `size()` vs `containsKey()`
  divergence; `size()` counting stale or in-flight entries.
- Async sync-view quiet-read divergences generally, and `remove(k,v)` short-circuiting on
  in-flight while `replace` and compute block.
- `AsyncAsMapView.computeIfAbsent` stats diverging from `AsyncCache.get`.
- `AsyncBulkCompleter.fillProxies` using 3-arg `replace` while `handleCompletion` uses the
  4-arg form with `shouldDiscardRefresh=false`.
- `AsyncBulkCompleter.fillProxies`'s `obtrudeValue` overriding a caller's `cancel()` on a
  shared proxy future.
- `AsyncBulkCompleter` double-evaluating a lazy bulk-load result.
- `LocalAsyncCache.put(k, null-future)` calling unconditional `cache().remove(key)`.
- `AsyncCache.asMap().entrySet()`'s `WriteThroughEntry.setValue(incompleteFuture)`
  divergence, and `WriteThroughEntry.setValue` not being fully atomic.
- Async load-failure WARNING not unwrapping `CompletionException` before the
  `instanceof Timeout/Cancellation` suppression check.
- General containment for `getAll` setup failures caused by a throwing `Ticker` or broken key
  equality: cleanup can fail on the same component. Setup does settle its earlier proxies when
  a later read-expiry callback throws, because conditional removal does not invoke that callback.
  This limited cleanup does not establish recovery from broken clocks, keys, or cleanup itself.
- Async `put(k, future)` completion-handler registration not being contained. For any
  spec-abiding `CompletableFuture`, `whenComplete` never throws at the registration site.
- A failing async finalizer removing a later reinsertion of the same completed future.
  Cleanup is conditional on the future object, not an insertion generation; a distinct
  successor is preserved. See [async re-registration](design-decisions.md#async-put-re-registration).
- A `loadAll` returning a map with null keys or values causing a partial commit, and null
  loader maps giving inconsistent diagnostics across `getAll` paths.
  `NullMapCompletionException` is an internal marker translated to
  `NullPointerException("null map")`; the sync path's JEP 358 helpful NPE names the variable.
  Preserve `LoadingCache.getAll`'s existing exception wording; adding a partial-commit
  qualification was declined.
- `loadAll` retaining the caller's mutable `Set` across the async boundary.
- A dropped or hung async load leaving a permanent in-flight mapping. Cancelling a per-key
  load's future removes its mapping; a cancelled bulk proxy stays mapped until the bulk loader's
  own future completes (see design-decisions, *Async Put Re-registration*), so a hung bulk load
  is released by completing that future or by `invalidate`.
- `synchronous().refresh(k)` on an absent key returning a write that completed between its
  absence check and its load, without calling the loader. A refresh of an absent key is a
  `get(key)`, which adopts the mapping it finds, and a load in that race could equally have been
  discarded by the write.
- An overlapping refresh adopting an older failed future while its original caller is still
  registering the absent-key load. Conditional registry cleanup preserves newer cached mappings
  and distinct refresh tokens; refresh does not promise the newest cached future or an immediate
  retry. Cleanup runs when the original invocation resumes; this is not permanent token poisoning.
- Overlapping `getAll` calls sharing a per-key proxy and its failure or omitted result. Each loader
  owns only the proxies it installed; aggregates retain shared futures even after cache removal.
  A caller that owns additional missing keys records its own bulk load, not another load for the
  shared keys. Outcome sharing is the accepted coalescing policy, not a bulk-atomicity guarantee.
- The synchronous view's `asMap()` being equal to no other map while a load is in flight, and a
  `ConcurrentHashMap` or an unbounded cache comparing equal to it in one direction only. See
  [iteration](design-decisions.md#iteration).
- `synchronous()`'s javadoc that a modification to a loading mapping blocks, while `Cache.put`,
  `invalidate`, `invalidateAll`, `asMap().clear()` and key-set removals return at once: they
  return nothing that needs the loaded value. `asMap().put` and `remove(k)` store first and then
  wait for the displaced value they return; the wait is `join`'s, uninterruptible with the
  interrupt status kept, and a load that fails during it yields null. `WriteThroughEntry.setValue`
  waits the same way although it returns the traversal-time value, which the blanket blocking
  sentence permits.
  Callers occupying every worker of the load executor while waiting create thread starvation,
  even when the executor accepts and queues tasks correctly. That application dependency is
  covered by the executor-responsibility rule; the synchronous view promises no independent
  progress or finite wait for the load.
- `AsyncCache.synchronous().get(k, fn)` and `getAll(keys, fn)` running the function on the cache
  executor through `supplyAsync` (or on the caller when a blocked common-pool worker helps with
  its own task), so it can see none, its own, or another task's leftover thread-bound state, and
  interrupt status the function sets stays on that thread; the same view's
  `asMap().computeIfAbsent` runs it on the caller. The `AsyncLoadingCache` view's loads are
  documented to run on the executor through `CacheLoader.asyncLoad`.
- The synchronous view logging a failed load at WARNING that it also throws to the caller.
  Async load failures are logged because a future may go unobserved; the view reuses that
  completion, and the logger can be configured to drop it.
- `synchronous().get(k)`, `get(k, fn)` and `getAll` waiting on a load in flight without responding
  to interruption, the interrupt status kept. A synchronous cache's caller waits the same way at the
  bin lock for another thread's load, as Guava's does; only a thread running an interruptible
  loader returns early.
- `asMap().putIfAbsent` and `computeIfAbsent` returning an existing value without read expiry when
  they waited on a load or found the value after their first lookup missed. See
  [async synchronous view](design-decisions.md#async-synchronous-view).
- `AsyncCache.get(K, Function)` allocating its function adapter on a hit (16 B) once misses share
  the compiled profile. Avoiding it takes a second hit probe ahead of the one in
  `get(K, BiFunction)`, which a lambda allocation does not justify.
- `synchronous().get(k, fn)` surfacing the cause of a `CompletionException` that the function threw
  itself, where the synchronous cache rethrows it unchanged. `supplyAsync` stores a thrown
  `CompletionException` as the wrapper it would otherwise add, so `resolve` cannot tell them apart.
  The view rethrows a `RuntimeException` or `Error` cause and otherwise retains the wrapper;
  a user-supplied wrapper therefore does not preserve its identity or exception category.

---

## jcache

Read `jsr107-conformance.md`'s topic sections with this section.

- **The 1.0 PDF is not authoritative.** The 1.1 and 1.1.1 maintenance releases revised
  normative behaviour without regenerating the formal PDF. Cross-check the 1.1.1 API javadoc
  before treating a 1.0 sentence as load-bearing; the specification's revision history records
  no 1.1 behaviour change. Confirmed relaxations: the `getCacheNames` iterator's ISE on
  modification removed; `getCache(String)` typed-cache IAE removed; the iterator EXPIRED firing
  requirement removed. Loader exception wrapping was not relaxed: the 1.1.1
  `CacheLoaderException` javadoc still requires it, and the TCK asserts it for `get` and `loadAll`.
- A listener, filter, loader, writer or other configured factory whose `create()` calls back into
  the caching API while the adapter holds a monitor or a registry computation: `getCache` or
  `createCache` on the same manager, or `CachingProvider.getCacheManager`. That is the standing
  principle's constructed trigger whatever the outcome: a deadlock against destroy or close, a
  loud `Recursive update`, or, when the nested insert resizes the registry under the outer
  computation's bin, a silently corrupted registry that loses a cache or holds two for one name.
  Moving construction out of the registry's mapping function was not proposed.
- Operations racing `close()`. The spec explicitly permits a closed cache to retain
  contents, governs only *future* use, and punts concurrent behaviour to implementation
  dependent. Local in-memory means no OS resource leaks.
- Manager close holding its monitor while awaiting child close, so an ordinary completion
  callback's manager lookup can wait for the ten-second child timeout. Accepted bounded
  shutdown delay, not an unbounded deadlock or a promised callback-disposal barrier.
- `CacheProxy.close()` calling `executor.shutdown()` and `tryClose`. Spec-silent rather than
  spec-required; defensible as a cache-owned resource.
- A jcache proxy "leaking" when abandoned without `close()`.
- Lifecycle-changing reentry from a user resource's `close()`, including creating another
  cache while its manager closes or reentering provider lifecycle under manager close. These
  user-created shutdown dependencies fall under the callback-trigger boundary; this does not
  classify an ordinary CompletionListener manager lookup as invalid.
- `CacheFactory` construction orphaning an owned executor, expiry, writer, loader or
  listeners when a later config validation throws. The trigger is a config error plus a user
  factory creating an owned resource. The centralize-ownership refactor was built, verified
  green, and discarded.
- The JMX `ObjectName` sanitize collision (`a:b` and `a=b` both to `a.b`). Inherent to any
  lossy sanitize and matches the RI. Never switch to `ObjectName.quote()`: the TCK's
  `TestSupport.calculateObjectName` hardcodes the unquoted RI-style format and looks the
  MBean up by it, so quoting fails the TCK.
- OSGi TCCL swap missing on `destroyCache` and `close`.
- `putNoCopyOrAwait` copying the value under the CHM bin lock, and lazy-expire
  `recordEvictions` drift.
- `CacheManagerImpl.getCache(String)` not throwing IAE for typed caches (relaxed in 1.1.1),
  and `getCacheNames()`'s iterator throwing UOE rather than ISE on `remove()` (relaxed).
- `LoadingCacheProxy.getAll` skipping access-expiry on loaded entries, unlike `get`.
- Read-through `get`/`getAll` finding a concurrent insertion or replacement on the loading
  lookup and skipping one JCache access-policy call. Native expiry still applies; includes
  `getOrLoad`'s expired-wrapper recovery. See [access expiry](jsr107-conformance.md#access-expiry).
- `CacheProxy.EntryIterator.hasNext` skipping expired entries without firing EXPIRED
  (requirement removed in 1.1.1).
- `JCacheLoaderAdapter.expireTimeMillis` and `CacheProxy.getWriteExpireTimeMillis` falling back
  to `Long.MAX_VALUE` (eternal) when `getExpiryForCreation` throws, and to `Long.MIN_VALUE`
  (unchanged) when `getExpiryForUpdate` throws.
- The provider's `WeakHashMap` ClassLoader retention. Proven, and not fixable: the value
  chain reaches its own key, and weak values would collect a live manager. JSR-107 provides
  `CachingProvider.close(ClassLoader)` for exactly this. Documentation only.
- `CacheManagerImpl.close()` deregistering by URI after it releases its lock. A lookup
  overlapping the close can return the closing manager, and a close racing a provider close and a
  fresh lookup can close the successor. Deregistering under the lock deadlocked against the
  provider's registry monitor, and the RI also releases by URI.
- `AbstractCopier` trusting an array's declared component type, so a `BigDecimal[]` holding a
  mutable subclass is copied shallowly. The subclass breaks the declared type's immutability.
- `JavaSerializationCopier` defining a proxy class in the manager's loader, which fails for a
  non-public interface from another loader when Caffeine is loaded above that loader. No report.
- A deserialized `CaffeineConfiguration` not equal to its original, as the default factories are
  method-reference lambdas with identity equality. Nothing compares a round-tripped configuration.

---

## guava adapter

- Guava-facade exception-translation divergences.
- Guava-facade statistics divergences, under the best-effort-stats rule.
- `CacheLoader.asyncReloading` making a scalar loader appear bulk-capable. Unsupported bulk
  loading falls back to direct per-key loader calls, followed by bulk insertion. It can duplicate
  concurrent loads or replace concurrent writes; these follow the adapter's bulk semantics.
- `caffeinate()`'s `ExternalBulkLoader` returning the loader's map uncopied, so a lazy view is
  evaluated more than once. Core tries to evaluate once, but Guava promises no single evaluation
  and itself evaluates twice in rare cases.
- The facade overrides only where native diverges from Guava. It overrides `contains`,
  `containsKey` and `remove` because native `contains(null)` throws NPE; it does not override
  `containsAll`, because native `containsAll` is null-lenient. `containsKey(null)` throwing
  NPE is deliberate null-hostility on a direct query.
- A loader that loads another key, deadlocking two threads on the bin lock or failing with
  CHM's `Recursive update`, where native Guava releases its segment lock before loading.
  Caffeine's `LoadingCache.get` forbids modifying this cache during the computation, and its
  documented `IllegalStateException` covers only detectably recursive updates. The facade
  retains this restriction as an accepted compatibility limit, including a loader or `Callable`
  that writes or invalidates its own key. Detection is best-effort, not a safety guarantee.
  Guava's `LoadingCache.get` and `Cache.get(Callable)` do not state Caffeine's prohibition.
  Guava does detect some same-entry recursive loads: `LocalCache.waitForLoadingValue` checks
  `Thread.holdsLock(e)` and throws `Recursive load of: ...` when the caller holds that entry's
  monitor. This does not reject a different key solely for sharing a segment, and plain `put`
  does not use that loading path. Guava's successful cases do not change the facade's accepted
  restriction. Migrating users do hit it. A `reload` that refreshes its own key fails the
  same way, because the facade calls `reload` inside the refresh registration, while native
  Guava's loading reference turns the nested refresh into a no-op. Three further outcomes are
  covered by the same acceptance. A nested insert that crosses the map's resize threshold runs
  the resize on the loading thread and re-enters the held bin, so the resize is abandoned for the
  life of the map and misplaced keys are iterated but not gettable and survive `invalidateAll()`,
  leaving a bounded cache with an entry outside the policy and a `weightedSize` below the true
  weight. JDK 11 livelocks the loading thread in that resize instead. In a size-bounded cache a
  nested write that finds the write buffer full waits for `evictionLock` while holding the outer
  bin, which `evictEntry` waits on under that lock, so one thread stops eviction cache-wide. Under
  `refreshAfterWrite`, Guava's default `reload` runs a `load` that reads other keys inside the
  refresh registration, so readers can deadlock in the refresh map; `CacheLoader.asyncReloading`
  avoids it. Supporting nested loads means loading outside the bin lock as Guava does, which would
  rebuild the facade on another loading model.

---

## simulator

The simulator is a testing tool. Its correctness matters only to avoid misleading benchmark
claims, not user harm. Weight effort toward the core and the adapters; sibling-divergence is
the one lens that stays productive here. A mechanism that no bundled trace, default run, or
reported issue reaches is won't-do until one does, however cleanly it reproduces. A loud abort
takes no number with it, so it waits for a report too. A documented but non-default setting is
not reach either.

- Approximate and lossy policies are intentional. `membership.bloom.FastFilter` is opt-in.
- `product.*` policies inheriting third-party libraries' wall-clock expiry defaults.
- `ClockProSimplePolicy` omitting the CLOCK-Pro hot-warmup phase, or collapsing on scan-loop
  workloads.
- `Cache2kPolicy.finished()` not failing; LIRS and LIRS2 at `percent-hot = 1.0`;
  `(int) (percentSample * maximumSize)` truncating to zero in eight climbers, which is
  already loud.
- `GDWheelPolicy` reading `event.missPenalty()` and degenerating to LRU on equal or uniform
  penalties. Verified against both GD-Wheel papers; a mixed penalty/none trace is undefined
  input for a penalty-aware policy.
- Synthetic workloads being unseeded, so `random-seed` does not cover them.
- Dedup-of-duplicates in ClockPro is author-sanctioned (confirmed with Song Jiang).
- A multi-member gzip or bzip2 trace (`pbzip2`, `bgzip`, `cat`) reading only its first member, and
  a truncated gzip or xz trace of 4- or 8-byte records ending at a 64 KiB buffer boundary without an
  error. Neither has a use case; revisit when a real trace needs one.
- The TinyCache policies, Gil Einziger's contribution, are kept as contributed: neither repaired
  nor removed. That includes building `ceil(maximumSize / 64)` sets of 64 entries, so a cell runs
  at the next multiple of 64.

---

## examples

The examples are simple, illustrative starting points that show how to think about a problem, so
a need can be met without adding a feature to the library. They should not be bad code, but they
are not production code the project owns. A defect is an example failing at what it shows; missing
lifecycle handling, incomplete READMEs, extreme inputs, and unused configuration are not.

- The RxJava and Reactor examples having no backpressure or an unbounded buffer under a slow
  sink.
- `IndexedCache` enforcing unique secondary keys only sequentially: two values sharing a unique key
  are user error. Its alias lookups and `invalidate` read two maps under no shared lock, so a
  concurrent same-primary key change can briefly return or remove the entity through the alias it
  just left; an exact check would run every indexer on each hit.

---

## build and CI

- `tests-latest` (`LATEST_JDK`) gated to default-branch push only.
- `run-gradle`'s blanket `attempt-limit: 2` retry.
- jcstress, lincheck, `test`, `fuzzTest` and `frayTest` tasks being cacheable rather than
  `cacheIf { false }`, so identical inputs (one tree pushed to two branches, a re-run, a
  version-only change) replay a cached pass without re-fuzzing or re-exploring. The weekly canaries
  run with `--rerun-tasks` for fresh executions.
- `EclipseJavaCompile`'s `argumentProviders.add { lambda }` emitting absolute paths.
- `ShardedTestFilter` running non-`MethodSource` descriptors in every shard.
- `ShardedTestFilter` dropping every method without `@CacheSpec` when a `-P` filter is set. The
  filters select local subsets of the parameterized matrix; CI shards without them, so the plain
  tests run there.
- `configureondemand` is intentional.
- `run-gradle` and `build-ea.yml` expanding the suite list unquoted, so a `--tests` wildcard would
  glob against the repository root. Nothing there matches either wildcard, and a match would most
  likely fail loudly.
- The `Stress Tests` job asserting nothing: `Stresser` prints status and throughput and exits 0
  after `--duration`, so it is a smoke run that fails only if the JVM dies, not an invariant check.
- Dependency verification is not wanted; the egress allowances are intentional.
- `coverage` and `test-results` skipping after a failed `tests-minimum` matrix. The failed shard
  makes the Build fail; fix that failure. Keep downstream summaries skipped, since publishing
  partial results lowers test counts and coverage. Do not add an always-running downstream result
  gate solely because the required summary checks accept a skipped conclusion.
- The cacheable `jmh` task behind the benchmark gists, `analysis.yml`'s SARIF merge keeping only
  the first input's `tool`, the `git diff` metadata freshness check missing a deleted or added file,
  and the opt-in `-Pjfr` profile (JDK 16+ event settings, no declared recording output).

---

## Code generation

- `AddFastPath.java` emitting `fastpath()` without `final`.
- Dropping `WEAK_VALUES` and `SOFT_VALUES` from `Feature.fastPathIncompatible`, which the
  local-cache generator never passes, and `AddFastPath.execute` recomputing the predicate
  `applies` evaluated. Output-neutral, with no clarity gain worth the edit; the `Rule` split
  between `applies` and `execute` is deliberate.
- Field declarations and method shapes for evicting caches live in the generators, not in
  `BoundedLocalCache`. Trace a generated field back to its `AddX.java` before drawing a
  conclusion about its type or storage.

---

## Serialization

- The serialization proxy is internal. Its wire format is not a compatibility surface, and
  disclosure of it was declined.
- Serialization of an executor, scheduler, or non-serializable `BiFunction`.
- The proxy's `asMap()` view not being `Serializable`.
- A serializable component that refers back to its own cache. The component is read before
  `readResolve` rebuilds the cache, so its field receives the proxy: a cache-typed field throws
  `ClassCastException` and an untyped one keeps the stale proxy. It fails only when the stream
  reaches the cache before the rest of the cycle. Guava's proxy extends `ForwardingCache` for
  this case; both that shape and a javadoc qualifier were declined.
- Hand-built streams that no `writeReplace` produces: an internal async adapter
  (`AsyncRemovalListener`, `AsyncEvictionListener`, `AsyncWeigher`, `AsyncExpiry`) placed in a
  proxy's callback field, or a Guava facade whose `cache` is null or not a `LoadingCache`. A
  crafted stream already chooses its callbacks, so an adapter or a broken facade gives it nothing
  a hostile callback lacks; it fails on use rather than on read. `readObject` guards were
  declined.

---

## Low-yield lenses

Recorded so a run order can price them, not to discourage a fresh look.

- Re-entrancy as a whole has closed at zero for a full pass. Callback re-entrancy warnings
  are explicitly not wanted, which removes the remedy from most of what it finds.
- Simulator periphery, per the section above.
- Formal-shape lenses (jmm, linearizability, arithmetic, correctness-proof, map-contract)
  have gone several passes without a defect on the core. They are cheap; run them, but do
  not spend a scarce second model on them first.
