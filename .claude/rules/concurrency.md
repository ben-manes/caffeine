---
paths:
  - "caffeine/src/main/java/**"
  - "caffeine/src/test/java/**"
  - "caffeine/src/frayTest/java/**"
  - "caffeine/src/lincheckTest/java/**"
  - "caffeine/src/jcstress/java/**"
---

# Concurrency Conventions

- Most node field access uses VarHandle access modes (key, value, accessTime, writeTime) — check acquire/release/opaque carefully. weight, policyWeight, and queueType are plain non-volatile fields, read and written directly.
- synchronized(node) is used for node-level mutations; evictionLock for policy state
- Lock ordering must be: evictionLock → CHM bin lock → synchronized(node)
- Read buffer drops are benign (affects eviction quality, not correctness)
- Write buffer tasks are never lost — inline fallback on offer failure, and a drain that passes
  over an unpublished slot (`relaxedPoll`) is re-armed by that producer's `scheduleAfterWrite`
- The drain status state machine (IDLE → REQUIRED → PROCESSING_TO_IDLE/PROCESSING_TO_REQUIRED) ensures single-threaded maintenance
- Node state encoding: alive (has value), retired (marked for removal), dead (fully unlinked)
