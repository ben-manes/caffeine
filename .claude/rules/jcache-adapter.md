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
  first call triggers loading. Action transitions have strict rules.
- **EventDispatcher**: Per-key ordering via CompletableFuture chains. Synchronous
  listeners tracked in ThreadLocal; callers must call `awaitSynchronous()` or
  `ignoreSynchronous()`.
- **In-flight futures**: All async operations add to `inFlight` set for close() to
  await. New async operations must add futures to this set.
- **TypesafeConfigurator**: External HOCON config (`application.conf`) can intercept
  cache creation. Config-based caches take precedence over programmatic ones.
- **OSGi classloader**: `CacheManagerImpl` swaps thread context classloader for OSGi
  bundle environments. The classloader is held via WeakReference.
- Tests include JSR-107 TCK (auto-unpacked) and isolated tests (per-JVM forking)
