---
name: audit-memory-retention
description: Analyze reference retention paths that prevent GC of removed entries
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for memory retention defects — cases where logically removed
entries remain reachable through internal data structures.

This is NOT about liveness. Assume the entry IS removed from the CHM. The question
is whether internal references still pin the key, value, or node in memory.

Assume at least one retention path exists where removed entries remain
reachable longer than necessary. If your analysis yields zero findings,
re-examine the write buffer and removal listener queue — explain specifically
why retention is bounded.

Trace what happens to references after removal in each data structure:

1. **Eviction deques**: Can dead nodes remain linked? Are key/value/prev/next nulled?
2. **Timer wheel**: Are removed nodes always unlinked? Can sentinels retain references?
3. **Write buffer**: Do enqueued tasks hold strong references until drained? Upper bound?
4. **Read buffer**: Are slots nulled after consumption? Can removed nodes be pinned?
5. **Removal listener queue**: Can slow executors cause unbounded retention?
6. **Weak/soft references**: After WeakKeyReference is enqueued, is the value still reachable?
   After WeakValueReference is enqueued, is the key still reachable?
7. **Async values**: Can removed-but-incomplete futures be pinned via whenComplete?
8. **Cache views/iterators**: Can long-lived iterators pin removed entries?

For each retention path:
- State the reference chain from GC root to retained object
- State retention duration (bounded by maintenance? unbounded?)
- Assess severity: transient vs persistent

Do not report intentional strong references or retention bounded by a single
maintenance cycle (unless maintenance can be delayed indefinitely).
