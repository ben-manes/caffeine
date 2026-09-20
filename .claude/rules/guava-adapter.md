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
- `InternalBulkLoader` copies the returned map once into an `IdentityHashMap`, dropping null keys
  and values in the same pass, so a lazy map is evaluated once. Identity keeps distinct extras that
  are equal, which Guava stores as separate entries under `weakKeys()` and as a replacement
  otherwise (`bulkLoad_equalExtras`). It relies on core matching loaded keys to requested ones by
  equality: a core that probed `loaded.get(requestedKey)` would miss fresh but equal result keys
  and fail with `InvalidCacheLoadException` (`bulkLoad_freshKeys`).
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
  1/2/1/1. `getAllPresent` deduplicates before looking up: `[1,1,1,2,2]` with only key 1 cached
  reports 1 hit/1 miss instead of Guava's 3 hits/2 misses, because a lookup per repeated key
  adds hit/miss noise to the statistics and the eviction policy. `getAll` records one outcome
  per bulk load: an empty `loadAll` map counts as a failure and a map with null keys or values
  as a success, the reverse of Guava, and the per-key fallback records one success where Guava
  records one per key. Returned values, stored entries, and exceptions agree, except for the
  weak-key case below.
- **Bulk reads keep the first of distinct but equal weak keys.** Core deduplicates a bulk
  request by `equals` before its identity lookups, as Guava's `getAll` does, but Guava's
  `getAllPresent` looks up every input key and keeps the last. With two such keys the facade's
  `getAllPresent` returns nothing for `[absent, cached]` where Guava returns the cached key, and
  the first key's value where Guava returns the second's when both are cached; `getAll` returns
  the first key's load in both. Matching Guava would bring back the per-key lookup noise, for
  keys that override `equals` yet rely on identity. See the weak-key bulk entry in [standing
  rulings](../docs/ruled-out.md#core).
- **Absent-key refresh uses the configured executor.** Guava calls `load` inline when no old
  value exists; the facade queues it. A queuing executor therefore leaves the facade's load
  pending when Guava has already installed the result. `Runnable::run` gives Guava's timing.
  Present-key refresh matches: the adapter must call `reload` on the caller to obtain its
  future, even when that reload performs asynchronous work.
  Slow reloads serialize colliding refresh registrations under the accepted CHM-bin rule.
  A reload waiting for another thread's cache operation is still a prohibited cache dependency,
  even when its own body makes no cache call; Guava's different lock granularity does not
  establish an independent-progress guarantee for the facade.
- **Failed synchronous loads are retried by waiters.** Guava shares one
  `LoadingValueReference` failure. Core `computeIfAbsent` serializes waiters at the bin lock,
  each retrying the failed load: three waiting callers can make three calls and receive
  different failures, with the k-th paying k times the latency. This is native Caffeine
  semantics; changing the facade would require rebuilding Guava's loading-reference machinery.
  `AsyncLoadingCache` shares failures because it stores the future.
- **A stale entry's `setValue` returns the value it captured.** The facade's entry set yields
  core's write-through entries, which follow `ConcurrentHashMap`: after another write to the key,
  `setValue` returns the entry's captured value where Guava returns the value its `put` replaced.
  Both install the new value.
