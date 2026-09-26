---
name: audit-reentrancy
description: Analyze user callbacks for re-entrancy defects (deadlock, corruption)
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for defects caused by user callbacks re-entering the cache.

User-provided callbacks:
1. CacheLoader.load(key) / loadAll(keys)
2. CacheLoader.reload(key, oldValue)
3. Weigher.weigh(key, value)
4. Expiry.expireAfterCreate / expireAfterUpdate / expireAfterRead
5. RemovalListener.onRemoval(key, value, cause)
6. EvictionListener (synchronous variant)
7. Mapping functions passed to compute, computeIfAbsent, merge
8. jcache: synchronous CacheEntryListener (historically caused double refresh),
   CacheWriter, EntryProcessor.process, ExpiryPolicy

For each callback:
1. List every lock held at the point the callback is invoked.
   Include: evictionLock, CHM bin lock, synchronized(node), any other.
2. Determine what happens if the callback calls EACH of these cache
   methods: get, put, remove, compute, computeIfAbsent, size, clear,
   cleanUp, asMap().entrySet().
3. For each (callback, cache method) pair where locks are held:
   - Can it deadlock? (Same lock re-acquired? Lock ordering violated?)
   - Can it corrupt state? (Re-entering a method mid-mutation?)
   - Can it observe partially-constructed state?
4. If the cache defends against re-entrancy (e.g., by deferring work),
   explain the mechanism and verify it is complete.

For each defect: state the callback, re-entrant method, locks involved,
call stack, and observable incorrect behavior.

Treat the executor as a matrix dimension. A read nudges maintenance, and an executor that runs
the drain on the caller (`Runnable::run`, a direct executor, a saturated `CallerRunsPolicy` pool)
runs the whole cycle inside whatever callback performed the read. Analyze each read cell under
the default executor and under caller-runs separately.

Witness notes:
- Under a caller-runs executor, any view access or read of an expired entry before the operation
  under test (printing `asMap()`, iterating) reaps it inline and removes the precondition. Print
  diagnostics only afterwards.
- With the default executor, let the pool's maintenance task finish (`cleanUp()` or
  `ForkJoinPool.commonPool().awaitQuiescence`) before advancing a controllable ticker, or that task
  reaps the entry the scenario depends on.
- Judge a lost write with an oracle that does not filter expired entries: a distinct value object
  per write, then check whether that value is ever notified after time advances and the key is
  overwritten.
