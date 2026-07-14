---
paths:
  - "jcache/**"
---

# JCache Adapter Conventions

- Significantly more complex than the Guava adapter — has its own subsystems
- **Expirable wrapper**: All values are stored as `Expirable<V>`, not `V` directly.
  JCache requires application-level expiration via `ExpiryPolicy`, separate from
  Caffeine's native expiry. Every value access involves the wrapper.
- **EntryProcessor state machine**: `EntryProcessorEntry.Action` tracks the dominant
  operation (NONE → READ/CREATED/UPDATED/LOADED/DELETED). `getValue()` is stateful —
  first call triggers loading. Action transitions have strict rules. `remove()` from
  CREATED resets to NONE (same-call create+delete is a no-op); DELETED with a null prior
  means `remove()` (or load-through then `remove()`) hit an absent/expired key — the
  `CacheWriter.delete` still fires (write-through), but no REMOVED event or removal stat.
- **EntryProcessor lazy-expiry is reconciled before the processor**: `invoke` reconciles a
  lazily-expired prior inline *before* running the processor — it publishes EXPIRED, counts
  the eviction, and passes the entry to the processor as absent,
  mirroring `BoundedLocalCache`'s evict-before-callback ordering. If the processor or a
  write-through `CacheWriter` then throws **any `Throwable`** (else the eager EXPIRED is
  orphaned and double-fires on the next reap), the expired prior's removal is **committed**,
  its synchronous EXPIRED listener is awaited, and the failure is rethrown after `compute`
  returns via `processorFailure` — an `Error` as-is, anything else wrapped as an
  `EntryProcessorException` (per the `Cache.invoke` javadoc's "wrap any `Exception` thrown")
  — so a failed `invoke` fires exactly one EXPIRED + one eviction. The expiration is a clock
  fact, not
  contingent on the operation succeeding. Consequently `postProcess` is
  expiry-free: a null prior means "absent", so READ/UPDATED (which imply a live prior) never
  observe one and their `requireNonNull(expirable)` is a proven invariant. Don't move the
  expiry check back into `postProcess` or re-read the clock there.
- **EventDispatcher**: Per-key ordering via CompletableFuture chains. Synchronous
  listeners tracked in ThreadLocal; callers must call `awaitSynchronous()` or
  `ignoreSynchronous()`.
- **In-flight futures**: All async operations add to `inFlight` set for close() to
  await. New async operations must add futures to this set.
- **TypesafeConfigurator**: External HOCON config (`application.conf`) can intercept
  cache creation. Config-based caches take precedence over programmatic ones.
- **OSGi classloader**: `CacheManagerImpl` swaps the thread context classloader to the
  manager's loader on the *creation* paths (`createCache`/`getCache`) — the JCache
  `FactoryBuilder.ClassFactory` resolves config-named factory classes via the ambient TCCL
  there. `destroyCache`/`close` intentionally do **not** swap: teardown resolves no class
  names (the resources are already-instantiated objects), so the TCCL is irrelevant to
  Caffeine's own close logic; a user `close()` that relies on the ambient TCCL is the user's
  concern (Ehcache3 swaps the TCCL nowhere). The classloader is held via WeakReference.
- Tests include JSR-107 TCK (auto-unpacked) and isolated tests (per-JVM forking)
- **Run `:jcache:tckTest` for spec-conformance changes** — not just `:jcache:test`. The
  TCK encodes interpretations (and some pre-1.1.1 strictness) that unit tests don't
  cover, e.g. `CacheLoaderTest.shouldPropagateExceptionUsingLoadAll` still asserts
  `CacheLoaderException` wrapping even after the 1.1.1 spec relaxed that rule, and
  `CacheMBStatisticsBeanTest.testIterateAndRemove` pins the hit/removal accounting
  split for iterator.next vs iterator.remove. When TCK and spec javadoc disagree,
  TCK wins.
- **JDK-version-gated behavior**: the suite compiles and runs on the **minimum** JDK
  (11) by default — pass `-PjavaVersion=N` to run on a newer one. Behavior that only
  exists on a newer JDK won't reproduce on the default runner (e.g. `ExecutorService`
  became `AutoCloseable` in JDK 19, so a `close()`/`tryClose` path is dead on 11).
  Exercise such paths with an explicitly-`AutoCloseable` test double so the logic runs
  on any JDK, or run with `-PjavaVersion`.
