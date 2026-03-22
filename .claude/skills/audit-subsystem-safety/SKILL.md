---
name: audit-subsystem-safety
description: Audit one cache subsystem for concurrency correctness defects
argument-hint: "[subsystem: eviction|refresh|write-buffer|compute|expiration|references|read]"
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the following subsystem for correctness defects: $ARGUMENTS

Subsystem scopes (if no argument given, audit ALL subsystems one at a time):
- Eviction path: evictFromMain, evictFromWindow, evictEntry, makeDead, resurrection
- Refresh path: refreshIfNeeded, tryRefresh, doRefreshIfNeeded, afterRefreshCompletion
- Write buffer: afterWrite, AddTask, UpdateTask, RemovalTask, drainWriteBuffer
- Compute path: doComputeIfAbsent, remap, and their interaction with CHM compute lambdas
- Expiration path: expireEntries, hasExpired, timer wheel scheduling, Pacer
- Reference collection: referenceKey, referenceValue, cleanUpReferences
- Read path: getIfPresent, afterRead, read buffer, frequency sketch updates

DO NOT analyze code outside your scope unless it is directly called by your scoped methods.

Key cross-cutting invariants your subsystem must preserve:
- Node lifecycle is unidirectional: alive -> retired -> dead (never reversed)
- Weight accounting: weightedSize must converge to the sum of weights of
  live entries, regardless of task ordering in the write buffer
- Lock ordering: evictionLock -> CHM bin lock -> synchronized(node)
- The value field on a node uses acquire/release semantics; the key
  reference is immutable after construction (plain read is safe)

For each method in your scope:

1. List every shared mutable field it reads or writes, and the access mode
   (plain, opaque, acquire, release, volatile, synchronized, CAS).
2. List every lock held at each access point.
3. For every pair of (write in method A, read in method B) within your scope,
   state whether a happens-before edge exists. If it depends on a condition,
   state the condition.
4. Attempt to construct a 2-thread or 3-thread interleaving that violates
   a postcondition of any method in your scope. Pay particular attention
   to interleavings where a node transitions between lifecycle states
   (e.g., retirement during an in-progress update).

For each candidate defect:
- Provide the concrete interleaving (thread actions interleaved step-by-step)
- State the invariant violated
- State the observable incorrect behavior
- Verify your interleaving is legal under the JMM (not just sequentially consistent)

If no defects are found, output the invariants that are preserved and WHY
(which synchronization mechanism protects each one).

Do not provide praise, style suggestions, or performance observations.
