---
paths:
  - "caffeine/src/main/java/**"
---

# Design Decisions (Quick Reference)

Before reporting a bug or suggesting a "fix," check this list. These are intentional.

- **Weight=0** is a pinning feature (skipped during eviction), not a bug
- **EXPIRE_WRITE_TOLERANCE (1s)** is intentional inexactness, not a missed optimization
- **Transient negative weightedSize** is acceptable eventual consistency
- **accessTime uses opaque write (not CAS)** to avoid contention storms on hot entries
- **notifyEviction before user code** preserves linearizability for resource-based listeners
- **Catch-commit-rethrow** in doComputeIfAbsent/remap makes phantom evictions real on exception
- **Two weight fields** (weight + policyWeight) is intentional for the telescoping sum
- **Non-volatile keyReference in WeakValueReference** is safe (published via setRelease + storeStoreFence)

For full rationale, see `.claude/docs/design-decisions.md`
