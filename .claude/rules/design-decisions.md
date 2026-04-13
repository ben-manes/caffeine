---
paths:
  - "caffeine/src/main/java/**"
---

# Design Decisions (Quick Reference)

Before reporting a bug or suggesting a "fix," check this list. These are intentional.

- **Weight=0** is a pinning feature (skipped during eviction), not a bug. Instead verify weight convergence via the telescoping sum.
- **EXPIRE_WRITE_TOLERANCE (1s)** is intentional inexactness. Instead verify entries expire within the tolerance window.
- **Transient negative weightedSize** is acceptable eventual consistency. Instead verify convergence after maintenance completes.
- **accessTime uses opaque write (not CAS)** to avoid contention storms. Instead verify that stale reads cause only benign early expiration.
- **notifyEviction before user code** preserves linearizability. Instead verify catch-commit-rethrow handles exceptions after irrevocable notification.
- **Catch-commit-rethrow** in doComputeIfAbsent/remap makes phantom evictions real on exception. Instead verify the committed state is consistent. This is the most commonly misunderstood pattern — read the doc before flagging exception handling in compute paths.
- **Two weight fields** (weight + policyWeight) is intentional for the telescoping sum. Instead verify both fields converge. The convergence proof depends on task ordering — read the doc before flagging negative transient sizes.
- **Non-volatile keyReference in WeakValueReference** is safe (published via setRelease + storeStoreFence). Instead verify the fence is present in setValue.

For full rationale, see `.claude/docs/design-decisions.md`
