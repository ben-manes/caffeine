---
name: audit-feature-interaction
description: Analyze feature interaction pairs and triples for concurrent defects
context: fork
agent: auditor
disable-model-invocation: true
---

Analyze the cache for defects arising from multiple features operating
simultaneously on the same entry.

Features:
A. Eviction (size/weight limit exceeded)
B. Expiration (afterAccess, afterWrite, variable)
C. Reference collection (weak key, weak value, soft value GC)
D. Refresh (automatic reload)
E. Loading (CacheLoader / computeIfAbsent)
F. Async completion (CompletableFuture values)
G. Listener notification (removal listener, eviction listener)
H. Weight change (Weigher returning different weight for new value)

For each pair (A,B), (A,C), ..., (G,H) — 28 pairs total:

1. Can both features trigger simultaneously on the same entry?
2. If yes, construct the most adversarial interleaving.
3. Trace step-by-step: correct processing? notifications? final state? cleanup?

After all pairs, identify highest-risk TRIPLE interactions:
- Eviction + expiration + listener exception
- Refresh + async completion + GC collection
- Weight change + eviction + compute

High-risk pairwise combinations:
- Eviction during refresh (entry evicted while refreshing)
- Expiration during compute (entry expires mid-computation)
- GC during async completion (future completes after weak ref collected)
- Weight change during eviction
- Listener exception during any other feature

For each defect: state the features involved, provide the interleaving,
state the incorrect behavior.
