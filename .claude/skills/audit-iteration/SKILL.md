---
name: audit-iteration
description: Analyze concurrent iteration and view consistency guarantees
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the concurrent iteration and view behavior of the cache.

Assume at least one view operation can observe inconsistent state under
concurrent modification. If your analysis yields zero findings, re-examine
expired-but-present entries and dead/retired node visibility — explain
specifically why no user-observable inconsistency is possible.

View operations to analyze:
- asMap().entrySet/keySet/values() iteration
- asMap().forEach()
- asMap().size()
- asMap().containsValue()
- Snapshot operations (policy().eviction().hottest/coldest)

For each:
1. What consistency guarantee? (Snapshot? Weakly consistent? Sequentially consistent?)
2. Can iteration observe:
   - A retired or dead node?
   - An entry whose key/value has been garbage collected?
   - An expired but not-yet-cleaned-up entry?
   - A node with inconsistent key/value (key from one version, value from another)?
3. Can concurrent put/remove/compute cause the iterator to:
   - Skip an entry that existed for the entire iteration?
   - Return the same entry twice?
   - Throw ConcurrentModificationException?
4. Does wrapping ConcurrentHashMap iterators introduce issues CHM wouldn't have?

For each issue: construct a concrete interleaving and state whether it violates
the documented contract or is expected weakly-consistent behavior.
