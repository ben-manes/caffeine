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
- check-then-CAS guards: a lock-free path that loads a CAS expectation and later validates
  another field (an identity or liveness check) before the CAS needs the expectation load ordered
  before the check. An acquire load does not order an earlier load, so without a load-load fence
  the expectation can reflect a write the check missed (`tryExpireAfterRead`'s `variableTime`
  load and value-identity check)
- third-party timestamp stores: the value-before-timestamp fence pairing covers timestamps stored
  by the value's writer. A reader that loads the value and then stores a timestamp (read-path
  `setAccessTime`) is ordered for later readers only on multi-copy-atomic hardware (ARMv8, x86),
  not on POWER

For each issue:
- State the specific reordering or visibility failure
- Construct a concrete 2-thread execution
- Verify the execution is legal under the JMM (not just TSO)

Do not report issues that only affect performance.
Do not report deliberately racy patterns with documented stale-read tolerance.

**Platform focus**: Pay specific attention to aarch64 (ARM) memory ordering.
ARM's weaker-than-TSO model exposes reordering bugs invisible on x86.
Historical bug (#1820): a weak/soft-value `setValue` published the new
`WeakValueReference` with `setRelease` and then cleared the old reference.
Without a `storeStoreFence` between them, aarch64 could make the `clear()`
visible before the publication, so a reader saw a null value. Check for
similar patterns: a release store followed by a store that a racing reader
must not observe first.
