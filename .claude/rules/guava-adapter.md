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
  `UnsupportedLoadingOperationException` and fall back to per-key `load`. Catching it
  is unambiguous — its constructor is package-private, so only the base-class default
  `loadAll` can throw it (user code can't).
- **That fallback loads per key but does not commit per key, and the difference from native
  Guava is accepted.** The adapters accumulate the loads into a map that core installs only
  once every key has succeeded, so a failure part way through discards the values already
  loaded. `LocalCache.getAll` instead loops `get(key, defaultLoader)`, committing each value
  before attempting the next. Witnessed with an `asyncReloading` loader that fails on the
  second of two keys: Guava retains the first and records a load success, the facade retains
  nothing. Declined 2026-08-15 (Ben): `getAll` specifies no prefix retention, reaching it takes
  both an undetectable non-bulk loader and a mid-batch failure, and what is lost is a
  warm-cache difference the caller's retry reloads; anyone depending on it would use Caffeine's
  own API. The facade could be repaired by catching the marker in
  `CaffeinatedGuavaLoadingCache.getAll` and looping `cache.get(key)`, at the cost of
  double-counting the fallback's hits and misses (the aborted bulk attempt counts them first,
  and there is no counter API to compensate as Guava's `misses--` does). `caffeinate()`'s
  external loader cannot be repaired at all, since a `CacheLoader` has no handle to install
  with.
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
