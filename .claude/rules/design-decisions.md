---
paths:
  - "caffeine/src/main/java/**"
---

# Design Decisions (Quick Reference)

Before reporting a bug or suggesting a "fix," check this list. These are intentional.

- **Weight=0** is a pinning feature (skipped during eviction), not a bug. Instead verify weight convergence via the telescoping sum.
- **EXPIRE_TOLERANCE (1s)** is intentional inexactness — entries may expire up to 1s earlier than the configured duration (never later). Applies to both `writeTime` reorder decisions in remap and `accessTime` updates on the read path (skipped if the last update is within tolerance, to avoid hot-entry cache-line contention). Tolerance is bypassed when the configured duration is `<= tolerance`.
- **Transient negative weightedSize** is acceptable eventual consistency. Instead verify convergence after maintenance completes.
- **accessTime uses opaque write (not CAS)** to avoid contention storms. Instead verify that stale reads cause only benign early expiration.
- **notifyEviction before user code** preserves linearizability. Instead verify catch-commit-rethrow handles exceptions after irrevocable notification.
- **Catch-commit-rethrow** in doComputeIfAbsent/remap makes phantom evictions real on exception. Instead verify the committed state is consistent. This is the most commonly misunderstood pattern — read the doc before flagging exception handling in compute paths.
- **Two weight fields** (weight + policyWeight) is intentional for the telescoping sum. Instead verify both fields converge. The convergence proof depends on task ordering — read the doc before flagging negative transient sizes.
- **Non-volatile keyReference in WeakValueReference** is safe (published via setRelease + storeStoreFence). Instead verify the fence is present in setValue.
- **`refreshIfNeeded` is lock-free and `discardRefresh` is over-aggressive** — both intentional. The completion-path ABA guards discard spurious reloads, and any refresh racing a mutation must be killed for linearizability. Don't add `synchronized(node)` to refresh or try to narrow the discard.
- **Async sync-view mutations are physical while queries are logical** — `AsyncCache.synchronous().asMap()` treats in-flight entries as absent for queries but `KeySet.remove`/`removeAll`/`removeIf`/`retainAll` and iterator removal operate on the raw delegate. Blocking everywhere invites deadlock; the split is the inherent sync-over-async tradeoff.
- **`TimerWheel.advance` delta=0 on sub-tick advances** is correct, including `nanos = -1 → 0`. Entries in the "last bucket" are visited on the next full wheel cycle; read-path `hasExpired` evicts on access sooner.
- **`Pacer.calculateSchedule` bumps a would-be 0L result to 1L** — `nextFireTime = 0L` is the unscheduled sentinel. Don't remove the guard.
- **`LoadingCache.getAll` partial-commits valid entries when `loadAll` returns nulls** — the Javadoc's "the mapping is left unestablished" is singular; only invalid (null) mappings are dropped, valid ones are retained.
- **`Caffeine.from(CaffeineSpec)` disables strict parsing** — mirrors Guava's `CacheBuilderSpec`; permits programmatic overrides like adding a weigher after `maximumSize`. The footgun of disabled eviction is accepted.
- **`BoundedLocalCache.equals` uses size + iterate-this + count==expectedSize**, not CHM-style two-sided iteration. AbstractMap-style is symmetric with the most common comparison target (HashMap), `BLC.size()` is reliable enough that the prescreen earns its keep, and O(n) beats O(n+m). The `count == expectedSize` postcondition catches the f6071dd race shape: maintenance trimming dead entries between the size prescreen and iteration would otherwise yield a silent false-true on the surviving subset. Don't propose CHM's no-size two-sided iteration here. The same pattern is mirrored in `LocalAsyncCache.AsMapView.equals` (the future-typed view).

For full rationale, see `.claude/docs/design-decisions.md`
