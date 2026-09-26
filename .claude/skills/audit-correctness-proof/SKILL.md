---
name: audit-correctness-proof
description: Attempt formal correctness proofs for all public cache methods
context: fork
agent: auditor
disable-model-invocation: true
---

Attempt to PROVE the correctness of the cache, not to find bugs.

For each public method (get, put, remove, compute, computeIfAbsent, merge,
replace, size, clear):

1. State the method's specification: preconditions, postconditions,
   concurrent behavior promises.
2. Identify the synchronization protocol ensuring the postcondition.
   For an expiring entry the clock is part of that state: place each
   ticker read that dates the entry or judges its expiry relative to the
   linearization point. A read taken before a step that can block (a
   hash-bin lock, a node monitor, a user callback) dates or judges
   against a stale clock, so check the paths that create an entry as
   well as the ones that test it.
3. Write a proof sketch:
   a. Assume the precondition holds
   b. Identify the critical section(s)
   c. Show the postcondition is established within the critical section
   d. Show no concurrent operation can invalidate it before the caller observes

4. If you CANNOT complete the proof at any step, stop and report:
   - Which step fails
   - What additional assumption would be needed
   - Whether the code guarantees that assumption

Gaps in the proof are more valuable than speculative bug reports — a gap
tells us exactly where to look.

Do not provide praise or style commentary.
