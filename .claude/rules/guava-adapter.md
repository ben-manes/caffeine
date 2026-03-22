---
paths:
  - "guava/**"
---

# Guava Adapter Conventions

- Thin wrapper over Caffeine — delegates to Caffeine APIs with type adaptation
- `CaffeinatedGuava` uses reflection to detect if a Guava `CacheLoader` implements
  `loadAll(Iterable)` — check `getDeclaringClass()` to distinguish overrides from
  the base class default. Do not break this detection logic.
- `nullBulkLoad` ThreadLocal in `CaffeinatedGuavaLoadingCache` signals that
  `loadAll` returned null keys/values. Required because Guava's `getAll()` must
  throw `InvalidCacheLoadException` for nulls, but filtering happens inside the
  bulk loader, not at the call site.
- Compatibility tests are forked from Guava's own test suite
