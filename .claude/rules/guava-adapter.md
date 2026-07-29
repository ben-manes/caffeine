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
- **The two `build` overloads bridge two different loader contracts.** With a *Guava* loader the
  adapter installs an `Internal*Loader` wrapper, which converts a null return to
  `InvalidCacheLoadException` and a checked exception to the `CacheLoaderException` marker that the
  facade's catch chain maps to `ExecutionException`. With a **native Caffeine** loader nothing is
  wrapped (`builder.build(loader)`), and Caffeine's `CacheLoader` deliberately allows null to mean
  "no value" — so the two contracts collide at the Guava-typed surface:
  - **null is rejected at the facade.** `get`/`getUnchecked` route through `requireLoaded`, which
    throws `InvalidCacheLoadException("null value")`. Returning null from a method Guava declares
    never-null just moves the failure to an unrelated NPE in the caller, and `getAll` on the same
    cache already rejected it (its post-check throws for a missing key) — so passthrough was not
    self-consistent. Note it takes a loader returning null from a non-null-typed `V` to get here;
    JSpecify enforcement is opt-in, so that is reachable without a linter complaining.
  - **A checked exception surfaces as `UncheckedExecutionException(CompletionException(E))`** rather
    than `ExecutionException(E)` — a known, accepted seam. It cannot be fixed in the facade's catch
    chain: core's `newMappingFunction` rethrows `RuntimeException` as-is and wraps checked in a
    `CompletionException`, so a user loader's *own* `CompletionException` (which Guava taxonomy says
    must become `UncheckedExecutionException`) is byte-identical to a core-wrapped checked exception,
    and unwrapping would mistranslate it. A correct fix must wrap the native loader at construction
    (before core sees it), which needs two wrapper shapes to mirror `loadAll` presence — core's
    `hasLoadAll` is reflective, so an unconditional override would falsely advertise bulk support.
    Not built; don't attempt the catch-chain version.
- Compatibility tests are forked from Guava's own test suite
