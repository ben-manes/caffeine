# JSR-107 (JCache) Conformance Reference

Resource map and methodology for auditing the `jcache/` adapter against the
JSR-107 1.1.1 specification, the reference implementation (RI), and the
ecosystem. Used by `/audit-jcache-conformance` and by `/audit-sibling-divergence`
Group G2.

**Core principle:** a green TCK is *necessary but not sufficient*. The TCK
frequently asserts only the observable end-state, not the event stream or the
statistics — so two implementations with different `UPDATED`-vs-`EXPIRED` events
and different put counts both pass. The spec text is the contract; the RI (written
by the spec authors) resolves what the spec leaves ambiguous; the ecosystem
majority is the tiebreaker when the RI looks like an outlier.

## Canonical spec source — fetch live, never bundle

The 1.1.1 spec is a **Google Doc**. Fetch the spec as plain text on demand:

```bash
DOC=1ijduF_tmHvBaUS7VBBU2ZN8_eEBiFaXXg9OI0_ZxCrA
curl -fsSL "https://docs.google.com/document/d/$DOC/export?format=txt" -o /tmp/jsr107_spec.txt
```

- JSR landing page: <https://jcp.org/en/jsr/detail?id=107>
- Spec doc: <https://docs.google.com/document/d/1ijduF_tmHvBaUS7VBBU2ZN8_eEBiFaXXg9OI0_ZxCrA/edit>

Reference the spec by **section / javadoc name**, not line or page number (the
export reflows). The 1.1.1 top-level sections (verified heading text) are:
*Store-By-Value and Store-By-Reference*, *Configuration*, *Expiry Policies*,
*Integration*, *Cache Entry Listeners*, *Entry Processors*, *Caching Providers*,
*Annotations* (CDI/Spring — not implemented by the adapter, out of scope), and
*Statistics Effects of Cache Operations*. The `/audit-jcache-conformance` matrix
(domains A–N) maps each adapter behavior to one of these.

The **DEEP** sections (TCK asserts end-state, not the event/stat — this is where
bugs hide):

- **"Expiry Policies"** — the `ExpiryPolicy` javadoc (`getExpiryForCreation` /
  `getExpiryForUpdate` / `getExpiryForAccess`) and the table *"how each of the
  cache methods interact with a configured ExpiryPolicy"* (which expiry method
  each operation calls).
- **"Cache Entry Listeners"** (the listener table, *"summarises the listeners that
  are invoked by each cache operation"*, prefaced with **"Expiry is always 'No'.
  The exact timing of expiry is caching implementation specific."**) — also governs
  `EXPIRED`/`REMOVED` and the `isOldValueAvailable`/`getOldValue` payload, synchronous
  vs asynchronous dispatch, and `CacheEntryEventFilter`.
- **"Statistics Effects of Cache Operations"** — the per-operation table plus the
  `CacheStatisticsMXBean` definitions; note the spec is internally inconsistent here
  (the broad "total number of puts" definition vs. the per-operation tables; `get`
  read-through is explicitly *not* a put). Covers the **full** counter matrix —
  `CacheHits`/`Misses`/`Gets`/`Puts`/`Removals`/`Evictions` — not just puts;
  `clear()` does **not** count removals while `removeAll()` does; `CacheEvictions`
  is the Caffeine-native-eviction→JCache-stat bridge (no sibling resolves it
  identically — the ecosystem differential is genuinely informative there).
- **"Integration"** — read-through (`CacheLoader`, `loadAll`,
  `CacheLoaderException` wrapping — *relaxed in 1.1.1*) **and** write-through
  (`CacheWriter.write`/`delete` ordering vs the store and vs the event/stat; a
  writer exception must suppress the event and the `CachePuts` increment;
  `writeAll`/`deleteAll` mutate the passed collection to reflect partial failure).
- **"Entry Processors"** — the `MutableEntry` action machine
  (NONE→READ/CREATED/UPDATED/LOADED/DELETED) and which event/stat each terminal
  action emits; `invoke` **and** `invokeAll`.

The **COVERAGE** sections (spec text unambiguous, TCK end-state sufficient — confirm
correctness + a pinning test, escalate only if an event/stat surfaces):

- **"Store-By-Value and Store-By-Reference"** — copy on store and on return so
  caller mutation cannot leak into the cache; the copy points (`put`/`get`/
  `iterator`/event payloads) and the `RISerializingInternalConverter` vs
  `RIReferenceInternalConverter` split.
- **"Configuration"** — `MutableConfiguration` snapshot-on-create, the
  `Factory<…>` wiring, key/value **type** enforcement (`ClassCastException`).
- **"Caching Providers"** — lifecycle (`close`/`isClosed`→`IllegalStateException`,
  `CacheManager` create/get/destroy, `getCacheNames` immutability) and the
  `CacheMXBean`/JMX management surface.

## The differential methodology

For each spec-ambiguous behavior (anything the TCK does not pin to a specific
event stream / statistic):

1. **Spec text.** Read the relevant `ExpiryPolicy` / listener / statistics
   javadoc and tables. Watch for *deliberately different wording* between sibling
   operations (the create-vs-update distinction below is the canonical example).
2. **TCK.** Find the test that exercises it and read **what it actually asserts**
   — usually only `containsKey`/`get` end-state, which several different behaviors
   satisfy. A passing TCK on the relevant test is the floor, not the answer.
3. **RI (the oracle).** The RI was written by the spec/TCK authors; its behavior
   is the canonical interpretation of ambiguous text.
4. **≥3 ecosystem impls.** Confirm the RI is not an outlier. If the ecosystem
   splits, the spec text + RI win; record the split so a future audit does not
   re-litigate it.
5. **Caffeine internal parity.** All Caffeine write paths that build an
   `Expirable<V>` and gate on `ExpiryPolicy` must agree with each other. Internal
   disagreement is the highest-signal finding — one side is wrong.

## Resource map (fetch / clone on demand)

### Reference implementation (the oracle)

```bash
curl -fsSL -o /tmp/cache-ri-impl-sources.jar \
  https://repo1.maven.org/maven2/org/jsr107/ri/cache-ri-impl/1.1.1/cache-ri-impl-1.1.1-sources.jar
mkdir -p /tmp/ri-src && (cd /tmp/ri-src && unzip -oq /tmp/cache-ri-impl-sources.jar)
```

- GitHub: <https://github.com/jsr107/RI>. The oracle class per audit domain
  (all present in the 1.1.1 sources jar above):

  | Domain | RI oracle |
  |---|---|
  | Write / read / remove ops, expiry, put/hit/miss/removal stats | `org.jsr107.ri.RICache` (every `put`/`replace`/`getAndReplace`/`get`/`remove`/`invoke` path) + `RICachedValue` |
  | Write-through | `RICache.writeCacheEntry` / `deleteCacheEntry` (both gate on `configuration.isWriteThrough()`); `cacheWriter.writeAll` / `deleteAll` for `putAll`/`removeAll` partial-failure collection mutation |
  | Statistics | `org.jsr107.ri.management.RICacheStatisticsMXBean` — five `AtomicLong` counters (`cacheHits`/`Misses`/`Puts`/`Removals`/`Evictions`); `CacheGets = hits + misses`; hit/miss percentages; **averages return 0** (the RI does not track `Average*Time`) |
  | Events | `org.jsr107.ri.event.RICacheEventDispatcher` + `RICacheEntryEvent`; filters via `RICacheEntryEventFilteringIterator` |
  | Entry processors | `org.jsr107.ri.processor.EntryProcessorEntry` + `MutableEntryOperation` (the action machine Caffeine's `EntryProcessorEntry.Action` mirrors) |
  | Store-by-value vs by-reference | `RISerializingInternalConverter` vs `RIReferenceInternalConverter` (the copy-on-store / copy-on-return points) |
  | Lifecycle / provider | `RICacheManager`, `org.jsr107.ri.spi.RICachingProvider`; management via `RICacheMXBean` |

- The decisive RI tell for expiry: its **create** path checks `isExpiredAt(now)`
  and calls `processExpiries` (suppress / not-added); its **update** path has
  **no** such check — it calls `setExpiryTime(getAdjustedTime(now))`, `putCount++`,
  and `addEvent(UPDATED)`. That asymmetry *is* the spec's create-vs-update rule.

### Ecosystem (clone shallow, or raw-fetch the one adapter file)

| Impl | Repo | JCache expiry path |
|---|---|---|
| cache2k | `cache2k/cache2k` | `cache2k-jcache/.../jcache/provider/TouchyJCacheAdapter.java` — `ExpiryPolicyAdapter.calculateExpiryTime` + `durationToTicks` (`Duration.ZERO`→`NOW`) |
| Coherence | `oracle/coherence` | `prj/coherence-jcache/.../jcache/localcache/LocalCache.java` — `put`/`replace` create branch guards `!isExpiredAt`, update branch stores + `putCount++` unconditionally; expiry via `JCacheEntryMetaInf.modified` / `LocalCacheValue.updateInternalValue` |
| Ehcache 2 | `ehcache/ehcache-jcache` | `ehcache-jcache/.../jcache/JCache.java` — `setTimeTo` (`isZero()`→`false`→remove) |
| Ehcache 3 | `ehcache/ehcache3` | `ehcache-107/.../jsr107/Eh107Cache.java` (delegates to core; expiry via the `Eh107Expiry` bridge) |
| Hazelcast | `hazelcast/hazelcast` | `.../cache/impl/AbstractCacheRecordStore.java` — `updateRecord` (`isExpiredAt`→skips `updateRecordValue`/`onUpdateRecord`/`createCacheUpdatedEvent`) |
| Infinispan | `infinispan/infinispan` | `jcache/commons/.../jcache/AbstractJCache.java` — `put`/`replace` (`Duration.ZERO`→explicit `cache.remove`) |

The table maps each impl's **expiry** path specifically. For the other DEEP
domains, the relevant class lives in the **same JCache module** already named — the
statistics MXBean, the event-dispatch/listener path, the `CacheWriter` write-through
call sites, and the store-by-value converter. Locate them per run (the impls evolve;
don't hard-code a path that may have moved) and difference against the RI oracle for
that domain from the table above.

Hazelcast, Infinispan, and Coherence are large; prefer raw-fetching the single
adapter file (`raw.githubusercontent.com/<repo>/<default-branch>/<path>`) over a
full clone. cache2k and the Ehcache 107 modules are small enough to shallow-clone.
Coherence's UPDATED event is delivered via a backing-map listener (not an explicit
`addEvent` on the write path), so confirm event behavior through its listener model,
not the `put` method alone.

### TCK (already a build dependency — read it locally)

```bash
# test sources are cached by the :jcache:tckTest task
find ~/.gradle/caches -name 'cache-tests-1.1.1-test-sources.jar'
# the run also unpacks the TCK here:
ls jcache/build/tck/org/jsr107/tck/
```

Key tests: `org.jsr107.tck.expiry.CacheExpiryTest`
(`expire_whenCreated_*`, `expire_whenModified`) and
`org.jsr107.tck.management.CacheMBStatisticsBeanTest`. When a TCK test and the
spec javadoc disagree, **the TCK wins** (see `.claude/rules/jcache-adapter.md`).

## The parity-matrix test pattern

The executable form of "all write paths must agree." A single parameterized test
runs every sibling operation through the same spec-ambiguous scenario and asserts
they produce the *same* observable outcome (event count, statistic delta,
end-state). This out-yields per-method tests because it makes a *disagreement*
fail rather than locking in one path's behavior.

Examples in the suite:
`JCacheCreationExpiryTest.writeOp_absent_zeroCreationExpiry` (put / putAll /
putIfAbsent / getAndPut / invoke) and
`JCacheUpdateExpiryTest.writeOp_present_zeroUpdateExpiry` (put / putAll /
getAndPut / replace / replaceConditionally / getAndReplace / invoke). When a new
ambiguous corner is resolved, add a parity test; do not rely on the TCK.

## Worked example: zero-CREATION vs zero-UPDATE expiry

The spec deliberately uses **different wording** for the two, and the difference
is observable but TCK-invisible:

- **`getExpiryForCreation()` → `Duration.ZERO`**: the entry *"is considered to be
  already expired and **will not be added to the Cache**."* → suppress everything:
  no store, no `CREATED`, no put recorded.
- **`getExpiryForUpdate()` → `Duration.ZERO`**: *"a Cache.Entry is **considered
  immediately expired**."* → the entry **is** updated, then expires. The
  "Invocation of Listeners" table fires `UPDATE` for `put`/`replace`/`invoke` on
  an existing key regardless of duration, and the operation's `Expired` column is
  `No` (expiry is a separate, impl-timed event). → publish `UPDATED`, record the
  put, store the entry already-expired; `EXPIRED` fires on the next access.

The TCK's only update-expiry test (`CacheExpiryTest.expire_whenModified`) asserts
only `containsKey == false` / `get == null` — satisfied whether you remove the
entry or store it immediately-expired. So it cannot catch the event/stat
divergence.

**Ecosystem split on the update side effects** (the rare non-unanimous corner —
zero-creation is unanimous "not added" across all impls):

| Behavior on zero `getExpiryForUpdate()` | UPDATED? | Put counted? | Impls |
|---|---|---|---|
| Store immediately-expired (spec-faithful) | yes | yes | **RI**, Coherence, cache2k, Ehcache 3 |
| Remove / suppress | no | no | Infinispan, Ehcache 2, Hazelcast |

The two spec-author-aligned implementations — the **RI** and Oracle's
**Coherence** — both implement the literal "updated, then immediately expired"
(store + count + `UPDATED`); the remove-shortcut camp passes only because the TCK
checks end-state. The split is 4–3 *toward* the spec-faithful reading, and the
spec text + RI settle it regardless of the head-count.

The spec text + RI put Caffeine in the first camp; `replace`/`getAndReplace`/
`invoke` were already there, and `put`/`putAll`/`getAndPut` were fixed to match
(2026-06-07) by gating the zero-expiry suppression on creation only
(`expirable == null`). The `/audit-sibling-divergence` report that surfaced this
proposed aligning the *siblings to `put`* — **backwards**; only the live spec +
RI + ecosystem dive caught the inverted direction. (Same trap as the
`getAll`/`get` access-expiry precedent, where the auditor's assumed direction was
also inverted.)

## Divergence catalogue (intentional or resolved — do not re-flag)

Verified against the ecosystem; see `.claude/rules/jcache-adapter.md` and the
session memory for the full rationale.

- **ZERO-expiry `EXPIRED` event carries the just-put value, not the prior value.**
  Suppressing it or passing `null` would strand resources tied to the discarded
  value. Intentional.
- **`invoke` read-through `getValue()` counts as a put; `get`/`getAll` do not.**
  The spec lacks a `CacheLoads` stat; counting invoke-loads surfaces some
  stampede visibility. The spec's own per-operation tables don't apply the broad
  "total puts" definition uniformly. Intentional.
- **`CacheProxy.close()` shuts down a configured `ExecutorService`.** Spec-silent;
  defensible default (cache owns the executor). Documented, not a bug.
- **JMX `ObjectName` sanitize (`[,:=\n*?]→.`)** matches the RI/ecosystem pattern;
  switching to `ObjectName.quote()` would break operator tooling. Intentional.
- **Exception in `getExpiryForCreation`** → ETERNAL (matches RI/Hazelcast/
  Infinispan); **`getExpiryForUpdate`/`Access`** → leave the duration unchanged.
- **`getCacheNames` iterator `UnsupportedOperationException`**, **`getCache(String)`
  on a typed cache not throwing**, **`CacheLoader` exceptions not wrapped**, and
  **`iterator()` silently skipping expired entries** were all **relaxed in 1.1.1**
  — the 1.0 PDF is not authoritative on these. Always cross-check the 1.1.1
  revision history before treating a 1.0 sentence as binding.
- **`CacheEvictions` includes expirations** (lazy JCache-level expiry on read/write
  paths and native `RemovalCause.EXPIRED` evictions both call `recordEvictions`).
  Ecosystem is 4–0 the other way (RI, Ehcache 3, cache2k, Hazelcast all track
  expiry separately or not at all and exclude it from `CacheEvictions`), but the
  spec's "removal initiated by the cache itself to free up space" is ambiguous, the
  MXBean has no expiry counter, and the TCK only asserts evictions in scenarios
  with no expiry. Intentional — expired entries would otherwise be invisible in
  JCache statistics. Do not "fix" toward the ecosystem; it would break dashboards.
- **An expired entry hit by `remove`/`removeAll`/`getAndRemove` counts as an
  eviction (+`EXPIRED` event), never a removal (+`REMOVED`)**. The RI's
  `removeAll` counts expired entries as removals, contradicting its own
  `remove(K)`; the spec's "one removal per entry that is removed" plus "expired
  entries are not returned from a cache" back Caffeine's gating. TCK-blind.
- **A `CacheEntryEventFilter` exception must not abort the in-flight operation**
  (fixed 2026-06-10). Filters are evaluated inside the `compute` that mutates the
  entry; previously a throwing filter aborted the store *after* `CacheWriter.write`
  had run and after earlier-iterated registrations had already enqueued the event
  (phantom events). The RI commits the mutation and only then propagates; Hazelcast
  and cache2k evaluate filters at delivery time. `EventTypeFilter.evaluate` now logs
  the filter failure and returns false so the dispatcher skips that listener,
  matching the listener-exception policy. Pinned by
  `EventDispatcherTest.publishCreated_filterThrows`.
- **`invoke`/`invokeAll` UPDATED must record `CachePuts` only after
  `CacheWriter.write` succeeds** (fixed 2026-06-10). `postProcess` case UPDATED
  recorded the put before the write-through call, so a writer exception left a
  phantom put while correctly suppressing the `UPDATED` event and the store — the
  only path out of step (put/putAll/replace×3/putIfAbsent/invoke-CREATED/-DELETED
  all suppress). The RI orders writer→count; the TCK never combines a failing
  writer with `invoke`. Pinned by the parity tests
  `CacheWriterTest.writeOp_failingWriter_noPutsRecorded` /
  `writeOp_writerSucceeds_recordsPut` /
  `removeOp_failingWriter_noRemovalsRecorded` (put / putAll / getAndPut /
  replace×2 / getAndReplace / invoke; remove / remove(K,V) / getAndRemove /
  removeAll / invoke-remove / iterator.remove).
- **Single-key `invoke` surfaces a writer failure as
  `EntryProcessorException(CacheWriterException)`** (`postProcess` runs inside the
  processor-exception wrapping). **Spec-mandated, not a divergence**: the *Entry
  Processors* → "Exceptions in EntryProcessors" section requires any exception
  "during the invocation of an EntryProcessor, **either by the Caching
  implementation or the EntryProcessor itself**" to be wrapped as
  `EntryProcessorException`. The RI propagates raw `CacheWriterException` (its
  writer runs after the processor try/catch) and is the **outlier**; cache2k
  wraps like Caffeine; Ehcache 3 leaks its own `CacheWritingException`. The TCK
  tolerates all of these (`catch (CacheException)`), and `invokeAll` per-key
  capture is observably RI-equivalent (`result.get()` throws
  `EntryProcessorException(CacheWriterException)` in both). Do not "fix" toward
  the RI — the explicit spec section beats the RI here. (An audit initially
  guessed the RI direction; same inverted-direction trap as zero-update expiry.)
- **Store-by-value event payloads expose the cache's stored value instance**
  (`CREATED`/`UPDATED` carry the stored copy; `EXPIRED`/`REMOVED` carry
  `expirable.get()`), so a listener that mutates `event.getValue()` mutates the
  cached value. cache2k events carry stored values identically; the RI hands
  listeners the caller's instance (CREATED/UPDATED) or a fresh deserialized copy
  (EXPIRED/REMOVED), but only as an artifact of its serialized-bytes store.
  Spec-silent (store-by-value text addresses caller mutation, not listener
  mutation); TCK-blind (`StoreByValueTest` asserts nothing about listener
  payloads); the spec's portability recommendations already discourage
  listener-side cache interaction. Intentional — a per-event copy would tax
  every listener-bearing op. Do not change without a driver.
- **`invoke` `remove()` on an absent entry records no removal and fires no
  `REMOVED` event** (only `CacheWriter.delete` is called). The RI unconditionally
  counts a removal and fires REMOVED-with-null-value, contradicting its own
  `remove(K)` gating and the listener table ("Yes, if remove() did remove an
  entry"); the stats table's looser "Yes, if remove() was called" is internally
  inconsistent with its removeAll/remove(K) rows. Caffeine's gating matches the
  listener table and its own `remove(K)`. TCK only tests remove-on-present.
- **`putIfAbsent` under zero creation expiry returns false, records a hit, fires
  `EXPIRED` with the new value, no put** — exact RI parity (RI: `result=false` →
  `increaseCacheHits(1)`, `processExpiries` with the new value). The
  hit-for-an-absent-key looks wrong but is the oracle's behavior; do not "fix".
- **`iterator().remove()` does not gate on expiry**: an entry that expires between
  `next()` and `remove()` still gets `REMOVED` + a removal count (RI parity — its
  iterator removes unconditionally, "we simply don't care"). The expired→eviction
  gating catalogued above applies to `remove`/`removeAll`/`getAndRemove` only.
- **`JCacheLoaderAdapter.expireTimeMillis` lacks the ±1 sentinel-collision
  adjustment** that `CacheProxy.getWriteExpireTimeMillis`/`setAccessExpireTime`
  apply when a finite adjusted time lands exactly on `0` or `Long.MAX_VALUE`
  (treated as already-expired / eternal). Requires an exact arithmetic collision
  with the ticker; informational internal-parity nit, not worth the branch until
  a loader path shares the helper.
- **Synchronous-listener exceptions are logged and swallowed**, not wrapped in
  `CacheEntryListenerException` and rethrown to the cache-op caller (the RI
  rethrows). The TCK's `testBrokenCacheEntryListener` tolerates both behaviors
  (catch-without-fail), and the queued-dispatch model has no synchronous frame to
  rethrow from. Intentional; documented in the `EventDispatcher` class javadoc.
- **Entry-processor `getValue()`-load followed by `remove()` calls
  `CacheWriter.delete`** (action `LOADED` → `DELETED`). The RI cancels LOAD+remove
  to a no-op. Spec-silent; Caffeine's behavior is internally consistent with
  remove-on-absent, where both implementations invoke `delete`. Intentional.
- **`getExpiryForCreation()` returning `null`** (undefined by the spec — only
  update/access document null) stores the entry with the `Long.MIN_VALUE`
  "unchanged" sentinel, which behaves as effectively eternal; `CREATED` is
  published and the put counted, consistently across all creation paths. The RI
  would NPE. Implementation-defined input; not a defect.

When auditing JCache, read this list first to avoid re-deriving known
false-positives, then run the differential on anything new.
