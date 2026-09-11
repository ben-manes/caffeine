---
paths:
  - "guava/**"
---

# Guava Adapter Conventions

The adapter delegates to Caffeine with type/exception adaptation. Compatibility tests are
forked from Guava's suite. Read [standing rulings](../docs/ruled-out.md#guava-adapter) before
raising a compatibility finding.

## Loader Adaptation

- `CaffeinatedGuava` detects `loadAll(Iterable)` overrides with `getDeclaringClass()`.
  Delegating wrappers such as `CacheLoader.asyncReloading` advertise bulk support even when
  their delegate lacks it. `InternalBulkLoader`/`ExternalBulkLoader` catch
  `UnsupportedLoadingOperationException` and fall back to per-key loading. Its package-private
  constructor makes it an unambiguous marker for the base-class default.
- `InternalBulkLoader` copies the returned map once into a `HashMap`: the map may materialize
  lazily, and core would otherwise iterate it again and probe every requested key.
  Equality-based copying is intentional; see the accepted weak-key limitation below.
- The static `nullBulkLoad` ThreadLocal signals null keys/values filtered inside the loader so
  the facade can throw `InvalidCacheLoadException`. `getAll` saves the enclosing marker and
  restores it in `finally`. Weighers, expiry, and same-thread removal listeners can perform a
  nested load on this or another facade cache during insertion. A per-instance marker would
  still fail same-cache nesting and require a back-reference in the serializable loader.

The two `build` overloads bridge different contracts:

- A Guava loader is wrapped in `Internal*Loader`: null becomes `InvalidCacheLoadException`,
  and checked exceptions become a `CacheLoaderException` marker translated by the facade to
  `ExecutionException`.
- A native Caffeine loader is installed directly and may return null. The Guava surface still
  rejects null: `get`/`getUnchecked` use `requireLoaded`, and `getAll` rejects missing keys.
  This is reachable without static-analysis enforcement of the non-null `V` type.
- A native loader's checked exception surfaces as
  `UncheckedExecutionException(CompletionException(E))`, an accepted seam. Core's wrapper is
  indistinguishable from a user-thrown `CompletionException`, which must remain unchecked.
  Do not unwrap it in the facade's catch chain. A correct repair requires construction-time
  wrappers with separate bulk/non-bulk shapes so reflective `hasLoadAll` remains accurate;
  that repair is not implemented.

## Accepted Compatibility Limits

- **Extra weak-key mappings can collapse.** The `HashMap` copy merges distinct but
  `equals`-equal keys from the loader's result; native Guava inserts each result directly.
  Lost extras never reach the cache or its removal listener. Requested keys already deduplicate
  by equality in both implementations. This uncommon extra-result case is accepted; users
  requiring different behavior can use Caffeine directly.
  Do not substitute `IdentityHashMap`: core probes `loaded.get(requestedKey)`, and fresh but
  equal result keys would fail with `InvalidCacheLoadException`. `bulkLoad_freshKeys` covers
  that shape; both full Guava suites passed the incorrect swap before this test existed.
- **Fallback bulk failure discards the successful prefix.** Per-key fallback accumulates a
  map for core to install after every load succeeds. Native Guava commits each key immediately:
  an `asyncReloading` loader failing on key two leaves key one and its load-success statistic
  in Guava, but no entry in the facade. `getAll` promises no prefix retention; retry reloads it.
  Moving fallback into the facade's `getAll` would count request hits/misses twice, with no
  counter API to compensate. The external `caffeinate()` loader has no cache handle to install
  a prefix at all.
- **Statistics are best-effort.** `asMap().computeIfAbsent` records request hits/misses where
  Guava does not, and counts a null result as a load failure. With an existing key, an absent
  key, then a null result, Guava reports hit/miss/success/failure = 0/0/1/0; the facade reports
  1/2/1/1. `getAllPresent` deduplicates before accounting: `[1,1,1,2,2]` with only key 1 cached
  reports 1 hit/1 miss instead of Guava's 3 hits/2 misses. Returned values, stored entries, and
  exceptions agree.
- **Absent-key refresh uses the configured executor.** Guava calls `load` inline when no old
  value exists; the facade queues it. A queuing executor therefore leaves the facade's load
  pending when Guava has already installed the result. `Runnable::run` gives Guava's timing.
  Present-key refresh matches: the adapter must call `reload` on the caller to obtain its
  future, even when that reload performs asynchronous work.
- **Failed synchronous loads are retried by waiters.** Guava shares one
  `LoadingValueReference` failure. Core `computeIfAbsent` serializes waiters at the bin lock,
  each retrying the failed load: three waiting callers can make three calls and receive
  different failures, with the k-th paying k times the latency. This is native Caffeine
  semantics; changing the facade would require rebuilding Guava's loading-reference machinery.
  `AsyncLoadingCache` shares failures because it stores the future.
