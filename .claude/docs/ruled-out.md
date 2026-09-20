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
  runs on the executor, so a lone worker still waits on its own full queue.
- **Statistics are best-effort and lowest priority.** A counter that races, drifts, or
  double-counts is not a correctness defect. `CacheStats.missRate`'s
  `missCount >= loadSuccessCount + loadFailureCount` says a miss need not load (`getIfPresent`);
  a refresh or `asMap` compute counting a load without a miss does not make it a defect.
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

---

## Core

**Eviction and maintenance**

- A weight change replayed after the fact evicting the mapping that replaced it. A weighted
  entry rewritten oversize and then rewritten small again can be removed as SIZE once the
  buffered deltas drain, because `UpdateTask` decides from the accumulated `policyWeight`
  while the node already carries the new weight. Deterministic with a discarding executor:
  under `maximumWeight(100)`, three puts of weight 1, 1000 and 1 leave the cache empty, and
  the notification carries the small value. `weight` and `policyWeight` are owned by
  different locking protocols, so the replay is inherent, and the javadoc's "may evict an
  entry before this limit is exceeded" covers the result. The price is an extra miss on a
  self-healing transient. Deciding the per-node check from `node.getWeight()` closes only
  that path: the same replay inflates the global `weightedSize` across drain cycles and
  evicts through `evictEntries` instead, measured still losing the entry in 2 of 3
  default-executor runs, so it is a special case rather than a repair. Resurrecting a victim
  when size eviction no longer holds has the same gap, and does nothing for an update that
  is not itself oversize and pushes other entries out. Weight-0 pinning is unaffected, which
  is the case that would have made it more than premature eviction.
- Size eviction is uncapped and drains the whole excess in one cycle under `evictionLock`.
  Eager shrink is the published `Policy.Eviction.setMaximum` contract, and the uncapped
  property is load-bearing for the `rescheduleCleanUpIfIncomplete` piggyback: size eviction
  never arms the pacer, so a capped drain would be the one backlog shape with no driver.
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
  instead.
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
  CAS is contention, which striping fixes; a full buffer means the drain is behind, which it
  does not. The `FULL` return is the signal that tells `afterRead` to drain.
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
- `clear()`'s write-buffer drain loop being unbounded under `evictionLock`.
- The write buffer's backpressure is a capacity limit, not a CAS.
- The constructor's read-buffer and access-policy conditions not naming `expiresVariable()`.
  Variable expiry shares the `A` classes with access expiry, and their `expiresAfterAccess()`
  (`timerWheel == null`) answers true inside the `BoundedLocalCache` constructor because the
  subclass assigns `timerWheel` after `super`, so a variable-expiry cache gets both. Naming
  `expiresVariable()` would not help: it reads false at that point.

**Timing and arithmetic**

- `EXPIRE_TOLERANCE` (1s) inexactness. Expiration is a maximum lifetime, not a minimum hold
  time; entries may expire up to 1s early from timestamp tolerance. Applies to `writeTime` reorder
  decisions and `accessTime` read-path updates. Read-extension's accepted over-stay is a
  separate race described in [expiration](design-decisions.md#expiration).
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
  [TimerWheel](design-decisions.md#timerwheel).
- `Pacer.calculateSchedule`'s 0L sentinel collision.
- `Pacer.schedule`'s reschedule arm must call `cancel()`, not `future.cancel(...)`: the
  immediate-scheduler recursion guard is `future == null && nextFireTime != 0L` and only
  `cancel()` reaches it. Do not simplify it back.
- `Pacer` skipping its re-arm when the scheduler fires before the cache ticker reaches
  `nextFireTime`. The executing future still appears pending, so maintenance waits for the next
  operation. A one-second-granularity ticker reproduces; `systemTicker` and a one-millisecond
  cached clock did not in 20 trials each. Tickers are expected to advance between reads; a cheap
  clock can increment per read and resynchronise periodically. The `fired` flag was declined
  for this coarse-clock trigger.
- `Pacer` self-poison ordering (`nextFireTime` committed before `scheduler.schedule()`).
  User schedulers get `GuardedScheduler`'s no-throw/no-null guarantee; built-ins satisfy it
  directly. Do not add a catch for an unreachable synchronous scheduler failure.
- `TimerWheel.Traverser` detecting concurrent modification via `nanos` rather than a
  `modCount`, unlike the deque-backed `Policy` families. The "spins forever holding
  `evictionLock`" consequence is a frozen-ticker artifact: `advance()` sets
  `nanos = currentTimeNanos` unconditionally, so under `systemTicker` a re-entrant operation
  reaching maintenance throws CME. Two do not: `clear()`, whose result is a truncated snapshot,
  and one whose maintenance cycle exhausts the expiration budget, since the rewind restores the
  `nanos` the traverser captured. Both results fall within the best-effort view the `Policy`
  snapshot entry under *Views, iteration, and the Map contract* accepts.
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
  measured about four per ten thousand.
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
- `BoundedLocalCache.replace(K, V, V)` calling the weigher before the oldValue check.
- `BoundedLocalCache.getIfPresent` casting the lookup `Object` to `K` for
  `tryExpireAfterRead`.
- Read-path expiry extension resurrecting a just-expired entry.
- A read that returns a value already reported EXPIRED, during a concurrent rewrite. Real and
  historically deferred, then repaired by the timestamps-before-value protocol.

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
- `UnboundedLocalCache.discardRefresh` removing unconditionally, so a write waits out an
  inline refresh load on the same key (measured 2004 ms). It is a light `ConcurrentHashMap`
  wrapper and CHM's `put` is pessimistic regardless, so the bounded cache's prescreen is not
  a repair to port.
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
- `Policy.hottest`/`coldest` map overloads collapsing equal-but-distinct weak keys.
- `Policy` snapshots pairing a value with a weight from another moment. The snapshot reports the
  policy's weight under `evictionLock` while writers publish values under the node's monitor, so
  a concurrent update can hand `coldestWeighted`/`hottestWeighted` a stale weight, and the
  expiration timestamps are read after the value. It is a best-effort view of what the policy
  sees, which also omits an entry whose `AddTask` is still in the write buffer when the
  snapshot's maintenance pass ends; synchronizing every node to pair them was declined.
  The same replay can hold a weight no entry has, which the snapshot clamps into the `int` range: a
  negative transient reads as 0, and an over-count such as `2W - w` stays within this ruling.
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
- `loadAll` retaining the caller's mutable `Set` across the async boundary.
- A dropped or hung async load leaving a permanent in-flight mapping. The remedy is to cancel
  the future, which `async-cache.md` documents.
- `synchronous().refresh(k)` on an absent key returning a write that completed between its
  absence check and its load, without calling the loader. A refresh of an absent key is a
  `get(key)`, which adopts the mapping it finds, and a load in that race could equally have been
  discarded by the write.
- The synchronous view's `asMap()` being equal to no other map while a load is in flight, and a
  `ConcurrentHashMap` or an unbounded cache comparing equal to it in one direction only. See
  [iteration](design-decisions.md#iteration).
- `synchronous()`'s javadoc that a modification to a loading mapping blocks, while `Cache.put`,
  `invalidate`, `invalidateAll`, `asMap().clear()` and key-set removals return at once: they
  return nothing that needs the loaded value. `asMap().put` and `remove(k)` store first and then
  wait for the displaced value they return; the wait is `join`'s, uninterruptible with the
  interrupt status kept, and a load that fails during it yields null.
  Callers occupying every worker of the load executor while waiting create thread starvation,
  even when the executor accepts and queues tasks correctly. That application dependency is
  covered by the executor-responsibility rule; the synchronous view promises no independent
  progress or finite wait for the load.
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
- `JCacheLoaderAdapter.expireTimeMillis` returning `Long.MAX_VALUE` when the `ExpiryPolicy`
  throws, and `getWriteExpireTimeMillis` returning `Long.MIN_VALUE` on a creation-policy
  exception.
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
  Guava refuses recursive loading too, just at a different granularity:
  `LocalCache.waitForLoadingValue` guards with `checkState(!Thread.holdsLock(e), "Recursive
  load of: %s", key)`, added by Ben after it used to deadlock. So the difference is per-entry
  against per-bin, not permitted against forbidden, and "but Guava supports it" is not
  available as a counter-argument.
  `LoadingCache.get`'s own javadoc is the ruling: the computation "must not modify this cache
  during the computation", and the documented `IllegalStateException` is scoped to a
  **detectably** recursive update, which leaves the undetectable cases unpromised rather than
  broken. Recursive loading is an implementation hole left undefined, not a contract, and
  Guava never promised it either. Drop-in compatibility is about honoring their API contracts,
  not reproducing their implementation: Guava is not linearizable and Caffeine does not give
  that up to match. Migrating users do hit it. A `reload` that refreshes its own key fails the
  same way, because the facade calls `reload` inside the refresh registration, while native
  Guava's loading reference turns the nested refresh into a no-op.

---

## simulator

The simulator is a testing tool. Its correctness matters only to avoid misleading benchmark
claims, not user harm. Weight effort toward the core and the adapters; sibling-divergence is
the one lens that stays productive here. A mechanism that no bundled trace, default run, or
reported issue reaches is won't-do until one does, however cleanly it reproduces. A loud abort
takes no number with it, so it waits for a report too.

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
- jcstress and lincheck tasks being cacheable rather than `cacheIf { false }`.
- `EclipseJavaCompile`'s `argumentProviders.add { lambda }` emitting absolute paths.
- `ShardedTestFilter` running non-`MethodSource` descriptors in every shard.
- `ShardedTestFilter` dropping every method without `@CacheSpec` when a `-P` filter is set. The
  filters select local subsets of the parameterized matrix; CI shards without them, so the plain
  tests run there.
- `configureondemand` is intentional.
- Dependency verification is not wanted; the egress allowances are intentional.
- `coverage` and `test-results` skipping after a failed `tests-minimum` matrix. The failed shard
  makes the Build fail; fix that failure. Keep downstream summaries skipped, since publishing
  partial results lowers test counts and coverage. Do not add an always-running downstream result
  gate solely because the required summary checks accept a skipped conclusion.
- The cacheable `jmh` task behind the benchmark gists, `analysis.yml`'s SARIF merge keeping only
  the first input's `tool`, the `git diff` metadata freshness check missing a deleted file,
  and the opt-in `-Pjfr` profile (JDK 16+ event settings, no declared recording output).

---

## Code generation

- `AddFastPath.java` emitting `fastpath()` without `final`.
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

---

## Low-yield lenses

Recorded so a run order can price them, not to discourage a fresh look.

- Re-entrancy as a whole has closed at zero for a full pass. Callback re-entrancy warnings
  are explicitly not wanted, which removes the remedy from most of what it finds.
- Simulator periphery, per the section above.
- Formal-shape lenses (jmm, linearizability, arithmetic, correctness-proof, map-contract)
  have gone several passes without a defect on the core. They are cheap; run them, but do
  not spend a scarce second model on them first.
