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
- **The copy of the loader's map is load-bearing, and the mappings it collapses are an accepted
  gap.** `InternalBulkLoader` reads the returned map exactly once into a `HashMap`, because the
  map may be lazily materialized and handing it to the cache would iterate it a second time and
  probe it once per requested key. The copy applies equality, so two keys that are `equals`-equal
  but distinct become one, and a cache using `weakKeys()` holds one mapping where native Guava
  holds both; Guava iterates the loader's map and `put`s each entry with no copy. The collapsed
  mapping never reaches the cache, so no removal notification reports it. Declined 2026-08-17
  (Ben): it takes a loader returning more mappings than were requested, the extras are what drop,
  and Caffeine's own API serves anyone who needs the behavior. Guava dedupes by equality for the
  *requested* keys too, which core's `getAll` already mirrors.
- **Do not "fix" that by copying into an `IdentityHashMap`.** The whole `:guava:test` and
  `:guava:compatibilityTest` suites pass with the swap, but core resolves each requested key
  against the copy (`loaded.get(key)`), as Guava does against the loader's own map, so a loader
  that builds its result with fresh key instances stops matching and `getAll` throws
  `InvalidCacheLoadException`. `bulkLoad_freshKeys` pins that shape; before it was added, nothing
  in either suite used a loader returning a key instance other than the one requested.
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
- **Two statistics divergences are accepted, stats being best-effort. Declined 2026-08-17 (Ben).**
  `asMap().computeIfAbsent` records request hit/miss where Guava records none (fresh cache, an
  existing key then an absent key then a null-returning function: Guava
  `hit=0 miss=0 loadSuccess=1 loadException=0`, facade `hit=1 miss=2 loadSuccess=1
  loadException=1`), and `getAllPresent` dedupes its keys before accounting where Guava counts
  every occurrence (`[1,1,1,2,2]` with only key 1 cached: Guava `hit=3 miss=2`, facade
  `hit=1 miss=1`). The values returned, the entries stored, and the exceptions raised are identical
  in both.
- **`refresh` on an absent key is queued on the executor where Guava loads inline. Declined
  2026-08-17 (Ben): the executor is the user's choice.** Guava's javadoc says loading is
  asynchronous only if `reload` was overridden asynchronously, and `LoadingValueReference.loadFuture`
  calls `load` on the caller when there is no previous value. Measured with an executor that queues
  without running: for an absent key native Guava returns with the value installed and one load
  recorded, the facade returns with the load still queued. The facade already matches for a
  *present* key, because `asyncReload` has to call `reload` to obtain its future and so runs it on
  the caller; that is forced by the shape of Guava's API rather than chosen. `load` is synchronous,
  so which thread runs it is exactly what `Caffeine.executor` configures, and the adapter takes a
  `Caffeine` builder from the caller. Anyone wanting Guava's timing passes `Runnable::run`.
- **Waiters on a failed same-key load each retry, where Guava shares the first failure. Declined
  2026-08-17 (Ben), structural.** Guava's waiters block on one `LoadingValueReference`, so a
  failure completes that future for all of them: one loader call, one exception. The facade
  delegates to core's `computeIfAbsent`, where each waiter takes the bin lock in turn and runs its
  own load. Measured with three concurrent callers on a failing loader: Guava makes one call and
  hands every caller the same failure, the facade makes three and hands each a different one, and
  because the attempts are serialized the k-th waiter pays k times the failure latency. Native
  `Caffeine` behaves the same, so this is core semantics rather than an adapter seam, and
  repairing it in the facade means rebuilding Guava's loading value reference over the cache.
  `AsyncLoadingCache` does share failures, since the future itself is the map value. Re-found
  three times (`AA-006`, `adversarial A4`, `R4-F2`).
- `nullBulkLoad` ThreadLocal in `CaffeinatedGuavaLoadingCache` signals that
  `loadAll` returned null keys/values. Required because Guava's `getAll()` must
  throw `InvalidCacheLoadException` for nulls, but filtering happens inside the
  bulk loader, not at the call site. **`getAll` scopes it to one operation**, saving any
  enclosing load's marker on entry and restoring it in the `finally`. The field is `static`, so
  without that scoping a bulk load on *any other* facade cache clears it: a `Weigher`, an
  `Expiry`, or a same-thread removal listener runs inside the enclosing load's writes, and a
  cascade lookup from there leaves the enclosing `getAll` returning a response Guava rejects.
  A per-instance ThreadLocal would isolate that but not a nested load on the same cache, and it
  would need a back-reference threaded into the `Serializable` loader, so the marker stays static
  and the scoping does the work.
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
