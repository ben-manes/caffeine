---
paths:
  - "caffeine/src/main/java/**"
  - "caffeine/src/test/java/**"
  - "caffeine/src/frayTest/java/**"
  - "caffeine/src/lincheckTest/java/**"
  - "caffeine/src/jcstress/java/**"
---

# Concurrency Conventions

- Most node field access uses VarHandle access modes (key, value, accessTime, writeTime) — check acquire/release/opaque carefully. weight, policyWeight, and metadata are plain non-volatile fields, read and written directly.
  `metadata` is a bag of bits the eviction policy owns: the low `QUEUE_BITS` are the queue type
  and a weighted node spends the rest on the high half of its 64-bit policy weight, so
  `getPolicyWeight`/`setQueueType` both touch it and neither may be called without the lock.
- synchronized(node) is used for node-level mutations; evictionLock for policy state
- Lock ordering must be: evictionLock → CHM bin lock → synchronized(node)
- Read buffer drops are benign (affects eviction quality, not correctness)
- Write buffer tasks are never lost: an offer failure falls back to inline maintenance, and a drain
  that passes over an unpublished slot (`relaxedPoll`) is re-armed by that producer's
  `scheduleAfterWrite`, unless a weak-memory interleaving defeats the re-arm, when the task waits
  for the next write, `cleanUp`, or a read that fills a stripe
- The drain status state machine (IDLE → REQUIRED → PROCESSING_TO_IDLE/PROCESSING_TO_REQUIRED) ensures single-threaded maintenance
- Node state encoding: alive (in the map and policy), retired (removed from the map, awaiting policy
  removal), dead (removed from both). Reference collection is orthogonal: an alive node can read a
  null weak key or weak/soft value, which readers treat as collected
