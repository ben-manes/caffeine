---
name: audit-map-contract
description: Audit ConcurrentMap and Map contract compliance for asMap() view
context: fork
agent: auditor
disable-model-invocation: true
---

Audit compliance with java.util.concurrent.ConcurrentMap and java.util.Map contracts.

1. **Map contract**: equals/hashCode consistency, putAll atomicity (if weigher
   throws mid-batch), replaceAll per-entry atomicity, containsValue consistency.

2. **ConcurrentMap contract**: compute/computeIfAbsent/merge atomicity ("mapping
   function applied at most once"), getOrDefault on expired entries, forEach with
   concurrent mutations, compute returning null (should remove entry).

3. **Null handling**: NullPointerException at correct points for null keys/values.
   Mapping functions returning null (compute→remove, merge→remove).
   putIfAbsent(key, null) behavior.

4. **Entry/EntrySet contracts**: Map.Entry.setValue() write-through, entrySet
   remove/contains checking both key AND value, snapshot vs live entries.

5. **Collection view contracts**: keySet().remove() removing from cache,
   values().remove() semantics, views backed by cache (bidirectional changes).

6. **Cache semantics interaction**: expired-but-present entries visible via asMap()?
   Collected weak keys visible? asMap() operations triggering listeners?
   asMap().put() vs cache.put() differences (access time, stats, refresh)?

For each violation: quote the JDK contract requirement, show actual behavior,
provide a test case.
