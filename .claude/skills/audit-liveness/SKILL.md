---
name: audit-liveness
description: Analyze the cache for liveness defects (progress, termination, starvation)
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for liveness defects. Safety (no bad states) has already been
verified. Focus exclusively on liveness (good things eventually happen).

For each of the following properties, either prove it holds or construct
a violating execution:

1. TERMINATION: If no new operations are submitted, does the maintenance
   loop eventually reach IDLE state? Can drainWriteBuffer loop indefinitely?
   Can any spin loop fail to terminate?

2. EVICTION PROGRESS: If weightedSize > maximum and no new entries are
   added, does eviction eventually reduce weightedSize to <= maximum?
   Can an entry that should be evicted survive indefinitely?

3. EXPIRATION PROGRESS: If an entry has expired and no new operations
   touch it, is it eventually removed from the map? Under what conditions
   can an expired entry persist indefinitely?

4. REFRESH PROGRESS: If a refresh is triggered, does it eventually
   complete (either successfully or by giving up)? Can a refresh remain
   in-flight indefinitely?

5. BUFFER DRAIN PROGRESS: Can the write buffer fill up and never be
   drained? Can the read buffer permanently lose entries in a way that
   causes incorrect eviction policy?

6. STARVATION: Can one thread's cache operations be starved indefinitely
   by other threads? Can the eviction lock be held for unbounded time?

7. NOTIFICATION PROGRESS: Can a removal/eviction listener notification
   be permanently lost (not delayed, but never delivered)?

For each property:
- Identify the mechanism that ensures progress (timeout, CAS retry
  bound, lock fairness, etc.)
- If the mechanism depends on external conditions (e.g., "eventually
  no contention"), state those conditions.
- If progress depends on the executor being live, state that assumption.

Do not analyze safety properties. Do not suggest code improvements.
