---
name: audit-linearizability
description: Analyze the cache for linearizability violations across all public methods
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for linearizability violations. For each public method below,
identify its LINEARIZATION POINT — the single atomic step at which the operation
appears to take effect.

Methods to analyze:

Single-key operations:
- get(key), getIfPresent(key)
- put(key, value), putIfAbsent(key, value)
- remove(key), remove(key, value)
- replace(key, value), replace(key, oldValue, newValue)
- computeIfAbsent(key, function), compute(key, function), merge(key, value, function)

Bulk / aggregate operations:
- getAll(keys) / getAllPresent(keys)
- putAll(map)
- invalidateAll(keys) / invalidateAll()
- size(), containsKey(key), containsValue(value)

Note: Bulk operations are typically NOT linearizable as a unit. State whether each
provides any atomicity beyond per-element linearizability.

For each method:
1. State the linearization point (e.g., "CAS on CHM bin at line X").
2. If conditional, enumerate all cases.
3. Construct a 2-thread scenario confirming the linearization point.

Then attempt to construct violations:
4. Can two threads observe operations in an inconsistent order?
   - put(k, v1) / put(k, v2) / get(k): Can C see v2 then v1?
   - computeIfAbsent(k, f): Can f execute twice concurrently?
   - remove(k) / get(k): Can get return a value after remove linearized?
5. Is size() linearizable or documented as an estimate? Bounds on error?
6. For async cache variants: is the linearization point the future insertion
   or completion? Can get() return an already-replaced future?

For each candidate violation:
- Provide the full interleaving
- Show the sequential history it violates
- Verify the interleaving is JMM-legal

Do not analyze internal consistency, only external observability.
