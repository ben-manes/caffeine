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
