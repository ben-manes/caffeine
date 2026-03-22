---
name: audit-exception-safety
description: Audit exception safety and failure atomicity across all throw sites
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the cache for exception safety defects. For every code path where
exceptions can be thrown, determine whether the cache is left consistent.

User-provided code that can throw:
1. CacheLoader.load / loadAll / reload
2. Weigher.weigh
3. Expiry.expireAfterCreate / expireAfterUpdate / expireAfterRead
4. Mapping functions passed to compute, computeIfAbsent, merge
5. RemovalListener.onRemoval / EvictionListener

Runtime exceptions:
6. OutOfMemoryError during node/reference allocation
7. StackOverflowError from deep re-entrancy
8. RejectedExecutionException from executor

For each throw site:

1. List every mutation already committed before the throw point.
2. Determine whether the catch block rolls back or commits.
3. Check for:
   - **Phantom entries**: Node in CHM but invisible to eviction/expiration
   - **Orphaned references**: WeakReference created but node rolled back
   - **Counter drift**: weightedSize out of sync with actual entries
   - **Lost notifications**: notifyEviction without notifyRemoval, or vice versa
   - **Leaked futures**: CompletableFuture never completed
   - **Stuck refresh**: refresh flag set but never cleared

4. For catch-commit-rethrow (doComputeIfAbsent, remap), verify:
   - Catches Throwable, not just RuntimeException
   - Committed state is fully consistent
   - Original exception is preserved

5. For OutOfMemoryError specifically:
   - Can AddTask/UpdateTask OOME orphan a CHM entry?
   - Can WeakKeyReference/WeakValueReference OOME leave half-constructed node?

For each defect: state the throw site, mutations committed, inconsistent
state, and a concrete triggering scenario.
