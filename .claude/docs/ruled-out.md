# Ruled Out

Adjudicated mechanisms and their scope. Most are accepted behavior; entries that describe a
repaired mechanism are marked as historical. Use the reason and current code, not an earlier
review's outcome.

**When to read this**: Phase 1.5, after your own findings are written down, alongside
`design-decisions.md`. Never before Phase 1 analysis. Read your module's section plus
*Standing principles*; the rest is not yours.

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
  bounded pool, `shutdownNow()` dropping accepted tasks, a never-completing loader. The
  executor is user-owned and its lifecycle is the user's choice.
- **Statistics are best-effort and lowest priority.** A counter that races, drifts, or
  double-counts is not a correctness defect.
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
  excess is capped by the write buffer (`estimatedSize() <= maximum + WRITE_BUFFER_MAX`),
  because a full buffer forces `afterWrite`'s inline assist. Do not add a third re-arm and
  do not move the resubmission into `PerformCleanupTask`; both were built and declined.
- `rescheduleCleanUpIfIncomplete`'s `!pacer.isScheduled()` gate deferring a REQUIRED backlog
  to the pacer's horizon is the same design. Same for the executor-reject catch in
  `scheduleDrainBuffers` not calling it.
- `rescheduleCleanUpIfIncomplete` losing the last driver entirely. Its peek holds the eviction
  lock without draining, which is what a concurrent writer's `scheduleDrainBuffers` needs for its
  `tryLock`, so a write that flips the status to REQUIRED between the peek's read and its unlock is
  left with nothing scheduled. This is accepted best-effort scheduling: buffered work remains
  available to the next operation. A loss mid-burst heals on the next write, which finds REQUIRED
  and re-arms; only a loss on the final write before quiescence
  persists, and the debt is bounded by the write buffer. The controlled witness paused a generated
  subclass after the status reads; 60 bursts on the real configuration produced none. A size-only
  cache has **no pacer at all**, since the generated class carries one only when expiration is
  configured, so a stuck size there is the accepted no-driver design rather than this claim.
- The expiration and window scans are O(N) in the pending-async population. The walk is real
  and 100k pending entries cost ~410 us per `cleanUp`, but the reorder is self-correcting:
  pending entries migrate to the MRU end and one completed entry takes `cleanUp` from
  410,208 ns to 41 ns. Under the default executor p50/p99/p99.9 are unchanged. Both
  candidate fixes measured worse. Residue that is real and documented: under
  `executor(Runnable::run)` the cost is linear per pending entry, so a burst is quadratic.
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
- Neither write-buffer consumer waiting for a producer's publication (`relaxedPoll`). The
  task is not stranded: `scheduleAfterWrite` runs after `offer` returns and re-arms.
- `clear()`'s write-buffer drain loop being unbounded under `evictionLock`.
- The write buffer's backpressure is a capacity limit, not a CAS.

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
- `Pacer.calculateSchedule`'s 0L sentinel collision.
- `Pacer.schedule`'s reschedule arm must call `cancel()`, not `future.cancel(...)`: the
  immediate-scheduler recursion guard is `future == null && nextFireTime != 0L` and only
  `cancel()` reaches it. Do not simplify it back.
- `Pacer` skipping its own re-arm from inside the fire, so a cache with a `Scheduler` goes
  dormant until the next operation. The scheduler measures its delay on its own clock while the
  pacer compares against the cache's ticker, so a ticker that has not reached `nextFireTime` when
  the fire arrives makes the still-running future look like a fire that is still to come. It needs
  a ticker coarse enough to lag the fire's dispatch latency: a one-second-granularity ticker
  reproduces on the default scheduler, while `systemTicker` and a one-millisecond cached clock did
  not in 20 trials each. **A `Ticker` is expected to advance between reads.** A clock with a
  resolution that repeats a value for a second is a test instrument, and a user who wants a cheap
  clock can increment per `read()` and resynchronise periodically. The `fired` flag that
  excludes an executing fire from pending work was declined for this trigger.
- `Pacer` self-poison ordering (`nextFireTime` committed before `scheduler.schedule()`).
  User schedulers get `GuardedScheduler`'s no-throw/no-null guarantee; built-ins satisfy it
  directly. Do not add a catch for an unreachable synchronous scheduler failure.
- `TimerWheel.Traverser` detecting concurrent modification via `nanos` rather than a
  `modCount`, unlike the deque-backed `Policy` families. The "spins forever holding
  `evictionLock`" consequence is a frozen-ticker artifact: `advance()` sets
  `nanos = currentTimeNanos` unconditionally, so under `systemTicker` any re-entrant
  operation reaching maintenance throws CME. Only `clear()` does not, and its result is a
  truncated snapshot, which is the weakly-consistent contract `Policy`'s javadoc already
  documents.
- `TimerWheel.expire()`'s catch block holding a stale `prev` pointer.

**Node lifecycle and access modes**

- Opaque `accessTime` writes, including "it avoids cache-line invalidation" framings and
  hot-entry true-sharing under `expireAfterAccess`. Deliberate, to avoid contention storms.
- Drain status terminal arms that skip the CAS, and a stale opaque read settling IDLE with a
  buffered task.
- `scheduleAfterWrite`'s weak-memory IDLE strand.
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
  `invalidateAll(Iterable)` returns void, has no such limit, and does reach both keys.
- Weak-key lookups allocating a `LookupKeyReference` (24 B/op). A thread-local mutable
  wrapper pins the instance to the thread, rejected in #294 for virtual threads and
  classloader pinning. Young-gen allocation is the better trade.
- A never-completing async or refresh loader retaining weak keys and values via callback
  capture.
- `BoundedLocalCache.containsKey` and `EntrySetView.contains` lacking an `isAlive()` filter;
  `computeIfPresent`'s fast path bypassing `requireIsAlive` for value==null nodes;
  `getKey(K)` lacking expiry, value and alive filtering.
- `BoundedLocalCache.put` missing an `isAlive()` re-check after the `Expiry` callback.
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
- `LoadingCache.refresh`'s "exceptions logged and swallowed" javadoc when `asyncReload`
  throws synchronously. The promise is about the *future's result*; a throw while producing
  the future is a distinct, real caller bug.
- The `refreshes` to `data` lock inversion deadlock.
- `LocalAsyncLoadingCache.refresh(key)` retrying without bound or backoff. Each pass rereads,
  so a retry needs the entry to change between the two probes. The exception was a completed
  future holding no value, which satisfied both probes at once and could not terminate; the
  optimistic path now treats such a mapping as absent.
- Refresh eligibility using strict `>` while expiration uses `>=`.
- Refresh discard notification using the discarded value; refresh commit failure not
  surfaced on the future; `discardRefresh`'s `containsKey` prescreen missing a CHM
  `computeIfAbsent` reservation; `discardRefresh` invalidating a newer refresh generation.
- A manual `refresh(k)` that started while the key was absent committing across a racing
  `put` then `invalidate`. Its only ownership test is `currentValue == oldValue`, vacuous at
  `null == null`, and the prescreen cannot see the registration reservation. Reproduced 5/5
  with the loader running inside `refreshes.compute`, discarded 0/5 on a pool executor, and
  the history is still a legal linearization; see [refresh internals](design-decisions.md#refresh-internals).
- `UnboundedLocalCache.discardRefresh` removing unconditionally, so a write waits out an
  inline refresh load on the same key (measured 2004 ms). It is a light `ConcurrentHashMap`
  wrapper and CHM's `put` is pessimistic regardless, so the bounded cache's prescreen is not
  a repair to port.
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
- `Map.getOrDefault` not being overridden, so the JDK default makes two calls.
- `ConcurrentMap.remove(k, null)` returning false rather than throwing NPE.
- `entrySet().add` throwing UOE rather than putting through. It matches
  `ConcurrentSkipListMap` and pre-v8 CHM; CHM's put-through violates `Set.add` by returning
  false yet replacing.
- `Policy.hottest`/`coldest` map overloads collapsing equal-but-distinct weak keys.
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
- A `getAll` bulk-load loop lacking per-entry containment. The only triggers are a throwing
  `Ticker` or broken key equality, which poison every cache operation; the containment
  operation is itself throw-prone on the same trigger.
- Async `put(k, future)` completion-handler registration not being contained. For any
  spec-abiding `CompletableFuture`, `whenComplete` never throws at the registration site.
- A `loadAll` returning a map with null keys or values causing a partial commit, and null
  loader maps giving inconsistent diagnostics across `getAll` paths.
  `NullMapCompletionException` is an internal marker translated to
  `NullPointerException("null map")`; the sync path's JEP 358 helpful NPE names the variable.
- `loadAll` retaining the caller's mutable `Set` across the async boundary.
- A dropped or hung async load leaving a permanent in-flight mapping. The remedy is to cancel
  the future, which `async-cache.md` documents.

---

## jcache

Read `jsr107-conformance.md`'s topic sections with this section.

- **The 1.0 PDF is not authoritative.** The 1.1 and 1.1.1 maintenance releases revised
  normative behaviour without regenerating the formal PDF. Cross-check the 1.1.1 Maintenance
  Release and its revision history before treating a 1.0 sentence as load-bearing. Confirmed
  relaxations: `getCacheNames` iterator IAE to UOE; `getCache(String)` typed-cache IAE
  removed; the `CacheLoader` exception-wrapping rule removed; the iterator EXPIRED firing
  requirement removed.
- Operations racing `close()`. The spec explicitly permits a closed cache to retain
  contents, governs only *future* use, and punts concurrent behaviour to implementation
  dependent. Local in-memory means no OS resource leaks.
- `EventDispatcher.publish`'s first-event-per-key path throwing `RejectedExecutionException`
  synchronously while subsequent events capture it in the future. The only trigger is a
  rejecting executor. The symmetry tidy was declined.
- `CacheProxy.close()` calling `executor.shutdown()` and `tryClose`. Spec-silent rather than
  spec-required; defensible as a cache-owned resource.
- A jcache proxy "leaking" when abandoned without `close()`.
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
- `LoadingCacheProxy.loadAll` not wrapping a `CacheLoader` `RuntimeException` in
  `CacheLoaderException` (rule removed in 1.1.1).
- `LoadingCacheProxy.getAll` skipping access-expiry on loaded entries, unlike `get`.
- `CacheProxy.EntryIterator.hasNext` skipping expired entries without firing EXPIRED
  (requirement removed in 1.1.1).
- `JCacheLoaderAdapter.expireTimeMillis` returning `Long.MAX_VALUE` when the `ExpiryPolicy`
  throws, and `getWriteExpireTimeMillis` returning `Long.MIN_VALUE` on a creation-policy
  exception.
- `getAverageGetTime()` going permanently negative. Reachable only by driving the unwrapped
  native cache; reproduced at -2545us and still out of scope under the `unwrap` rule.
- The provider's `WeakHashMap` ClassLoader retention. Proven, and not fixable: the value
  chain reaches its own key, and weak values would collect a live manager. JSR-107 provides
  `CachingProvider.close(ClassLoader)` for exactly this. Documentation only.

---

## guava adapter

- Guava-facade exception-translation divergences.
- The two Guava-facade statistics divergences, under the best-effort-stats rule.
- `CacheLoader.asyncReloading` fooling `hasLoadAll`, so `getAll` throws where native Guava
  falls back to per-key.
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
  that up to match. Migrating users do hit it.

---

## simulator

The simulator is a testing tool. Its correctness matters only to avoid misleading benchmark
claims, not user harm. Weight effort toward the core and the adapters; sibling-divergence is
the one lens that stays productive here.

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

---

## examples

- The RxJava and Reactor examples having no backpressure or an unbounded buffer under a slow
  sink.

Examples otherwise hold the full quality bar, including unhappy-path test coverage.

---

## build and CI

- `tests-latest` (JDK 25) gated to default-branch push only.
- `run-gradle`'s blanket `attempt-limit: 2` retry.
- jcstress and lincheck tasks being cacheable rather than `cacheIf { false }`.
- `EclipseJavaCompile`'s `argumentProviders.add { lambda }` emitting absolute paths.
- `ShardedTestFilter` running non-`MethodSource` descriptors in every shard.
- `configureondemand` is intentional.
- Dependency verification is not wanted; the egress allowances are intentional.

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

---

## Low-yield lenses

Recorded so a run order can price them, not to discourage a fresh look.

- Re-entrancy as a whole has closed at zero for a full pass. Callback re-entrancy warnings
  are explicitly not wanted, which removes the remedy from most of what it finds.
- Simulator periphery, per the section above.
- Formal-shape lenses (jmm, linearizability, arithmetic, correctness-proof, map-contract)
  have gone several passes without a defect on the core. They are cheap; run them, but do
  not spend a scarce second model on them first.
