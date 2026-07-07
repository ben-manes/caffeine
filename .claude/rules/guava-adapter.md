---
paths:
  - "guava/**"
---

# Guava Adapter Conventions

- Thin wrapper over Caffeine — delegates to Caffeine APIs with type adaptation
- `CaffeinatedGuava` uses reflection to detect if a Guava `CacheLoader` implements
  `loadAll(Iterable)` — check `getDeclaringClass()` to distinguish overrides from
  the base class default. Do not break this detection logic.
- The detection is *fooled* by delegating wrappers like `CacheLoader.asyncReloading`
  (its wrapper overrides `loadAll` even when the underlying loader doesn't), so the
  bulk-loader adapters (`InternalBulkLoader`/`ExternalBulkLoader`) catch
  `UnsupportedLoadingOperationException` and fall back to per-key `load`, matching
  native Guava's `getAll`. Catching it is unambiguous — its constructor is
  package-private, so only the base-class default `loadAll` can throw it (user code
  can't).
- `nullBulkLoad` ThreadLocal in `CaffeinatedGuavaLoadingCache` signals that
  `loadAll` returned null keys/values. Required because Guava's `getAll()` must
  throw `InvalidCacheLoadException` for nulls, but filtering happens inside the
  bulk loader, not at the call site.
- Compatibility tests are forked from Guava's own test suite
