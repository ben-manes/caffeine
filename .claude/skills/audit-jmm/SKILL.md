---
name: audit-jmm
description: Java Memory Model audit of all VarHandle/volatile field access modes
context: fork
agent: auditor
disable-model-invocation: true
---

Perform a Java Memory Model audit of the cache.

For every field accessed via VarHandle or Unsafe, and for every field
declared volatile or accessed under synchronization:

1. State the field name, type, and declared access mode.
2. List every read and write with: access mode, method/line, locks held.
3. For each (write, read) pair: state whether happens-before is guaranteed.
   If it depends on access mode, verify BOTH sides use compatible modes.
4. Identify any field where:
   - Plain/opaque write paired with plain/opaque read across threads
     with no intervening synchronization
   - Code relies on opaque providing ordering beyond coherence
   - Volatile read on one field but non-volatile on a correlated field

Specific areas to examine:
- writeTime: encodes both timestamp and refresh-in-progress flag
- accessTime: is opaque access sufficient for expiration?
- key/value fields: do value reads provide visibility of the object's fields?
- policyWeight vs weight: correlated but updated at different times

For each issue:
- State the specific reordering or visibility failure
- Construct a concrete 2-thread execution
- Verify the execution is legal under the JMM (not just TSO)

Do not report issues that only affect performance.
Do not report deliberately racy patterns with documented stale-read tolerance.
