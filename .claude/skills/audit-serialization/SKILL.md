---
name: audit-serialization
description: Audit serialization proxy correctness and round-trip safety
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the serialization/deserialization behavior of the cache.

1. **Proxy completeness**: Does the proxy capture ALL configuration?
   Check: max size/weight, expiration, key/value strength, weigher, loader,
   removal/eviction listener, ticker, scheduler, executor, initial capacity.

2. **State transfer**: Are entries serialized or only configuration?
   If entries: are expired entries filtered? async values handled?
   weak/soft refs dereferenced? If not: is this documented?

3. **Deserialized consistency**: Correct initial state for frequency sketch,
   timer wheel, drain status, deques, weight counters? Correct node types?

4. **Cross-version compatibility**: serialVersionUID present? Can older
   proxies deserialize in newer versions? Default values correct for missing fields?

5. **Security**: Can crafted serialized form create inconsistent state?
   Are inputs validated? Is the proxy pattern correctly implemented?

6. **Edge cases**:
   - Serializing during in-flight operations
   - Serializing with pending removal notifications
   - LoadingCache with non-serializable CacheLoader
   - AsyncCache with incomplete futures

For each defect: state the field/behavior affected, before/after across
round-trip, severity (data loss, incorrect behavior, security risk).
