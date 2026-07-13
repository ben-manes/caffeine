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
- **Operations racing `close()` are conformant — accepted (audit-lifecycle M2/M3,
  2026-07-06 with Ben; spec-verified against 1.1.1 § *Closing a Cache* + § *Consistency*).**
  The spec governs only *future* use ("Once closed any attempt to **use** an operational
  method ... will throw `IllegalStateException`") and is **silent on operations in
  progress**; § *Consistency* punts concurrent behavior to "implementation dependent." So a
  `put`/load that passed `requireNotClosed()` before the flag flipped and commits after
  `close()`'s `invalidateAll` (**M2**) is not just unspecified but **explicitly permitted**:
  "Closing a Cache does not necessarily destroy the contents ... the contents of a closed
  Cache may still be available." Local in-memory → the lingering entry is GC-reclaimed, no
  resource leak. **M3**: `EventDispatcher.publish`'s *first-event* `runAsync` throws
  `RejectedExecutionException` synchronously to the racing caller once the executor is shut
  down (subsequent events' `thenRunAsync` captures it — a real internal asymmetry; trigger
  is a rejecting executor = the close-shutdown or a user's `AbortPolicy` bounded pool). The
  **event loss itself is spec-mandated** — close() "must prevent events being delivered to
  configured `CacheEntryListener`s" — which Caffeine enforces **two ways** (the shut-down
  executor rejects the dispatch task, *and* `EventTypeAwareListener.dispatch` early-returns
  on `event.getSource().isClosed()`), so the drop is correct on both paths; only the
  raw-REE-to-caller is the unspecified quirk. **Executor shutdown is spec-SILENT, not required** (correcting a first-draft
  overclaim): § *Closing a Cache*'s enumerated `Closeable` list is `CacheLoader`/
  `CacheWriter`/`CacheEntryListener`s/`ExpiryPolicy` — the executor is **not** in it;
  closing it rests on the general "release all resources coordinated on behalf of the
  Cache" phrase (defensible, cache-owned — the sibling catalogue entry above). In-flight
  user callbacks (`CacheWriter` I/O) are the user's lifecycle responsibility; common-pool/
  virtual-thread defaults don't hit the shutdown-race. Don't add op-vs-close locking or a
  synchronous REE guard; don't re-raise M2 (spec explicitly permits retained contents).
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
- **`JCacheLoaderAdapter.expireTimeMillis` applies the same ±1 sentinel-collision
  adjustment** as `CacheProxy.getWriteExpireTimeMillis`/`setAccessExpireTime` when
  a finite adjusted time lands exactly on `0` or `Long.MAX_VALUE` (treated as
  already-expired / eternal). Resolved by 9905bf070 and pinned by
  `CacheLoaderTest.load_adjustedTimeSentinelZero`/`...Max`; earlier revisions of
  this list recorded the adjustment as missing — do not re-derive that gap.
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
- **Access-expiry touch runs lock-free; a concurrent replace races it loosely —
  adjudicated benign (2026-07-12 with Ben; J3 ≡ adversarial F3/F4).** `get`,
  `getAll` (`getAndFilterExpiredEntries`), `EntryIterator.hasNext`, and
  `LoadingCacheProxy.getOrLoad` read via `getIfPresent`, then `setAccessExpireTime`
  applies `policy.setExpiresAfter(key, …)` — which re-looks-up the node by key, so
  if the entry was replaced in between it moves the *new* entry's native timer. Not
  a defect: `getExpiryForAccess()` is value-independent (nullary), so the applied
  duration is identical to what a real get of the new entry would set — the raced
  outcome is exactly the legal "get linearized after the put" serialization. The
  authoritative field (`Expirable.expireTimeMillis`, which every read gates on) is
  only ever written on the *held* instance, never the replacement, so reads stay
  correct; the stale `setExpiresAfter` moves only the native mirror, and the
  effective expiry is the earlier of native/wrapper — either end is a valid
  serialization (access bound ⇒ "get after put"; write bound ⇒ "get before put").
  Worst case is a background `EXPIRED` firing at the access bound instead of the
  write bound, indistinguishable from "a get happened." Enforcing wrapper/native
  serializability would cost a `computeIfPresent` bin-lock on *every* access-expiry
  read to close a race that only ever yields a legal outcome — wrong trade against
  best-effort expiry (same reasoning as core `accessTime`'s opaque-write-not-CAS).
  The F4 corollary — `access=ZERO` + eternal + a concurrent `invoke` whose
  processor READs — lets the unlocked `setExpireTimeMillis(0L)` flip the shared
  `Expirable` under `invoke`'s `compute`, surfacing an `EntryProcessorException`-
  wrapped TOCTOU (**not** a raw NPE; `postProcess` runs inside the processor-
  exception wrapper). Same family, caught/wrapped, pathological config. Don't add
  per-access locking or an identity guard; don't re-raise J3/F3/F4.
- **A non-serializable store-by-value value throws `CacheException`** (fixed 2026-07-12).
  `JavaSerializationCopier.serialize` wrapped the failure in `UncheckedIOException`
  while `deserialize` (and `Cache.put`'s `@throws CacheException`) used `CacheException`
  — so `put`/`putIfAbsent` of a non-serializable value escaped as a non-`CacheException`.
  Now aligned with `deserialize`. Ecosystem is split (cache2k `CacheException`, RI
  `IllegalArgumentException` — itself serialize/deserialize-asymmetric, Hazelcast own
  `RuntimeException`); consistency + cache2k parity won, no TCK coverage. Pinned by
  `JavaSerializationCopierTest.serializable_fail` and
  `CacheWriterTest.putIfAbsent_nonSerializableValue_doesNotWrite`. Don't revert toward
  the RI's `IllegalArgumentException`.
- **Config `key-type`/`value-type` resolve via the context classloader** (fixed 2026-07-12).
  `TypesafeConfigurator.addKeyValueTypes` used bare `Class.forName(name)` — the adapter's own
  module loader, which is neither the TCCL nor the manager loader. Now
  `Class.forName(name, true, tccl)` (adapter loader when the TCCL is null; `true` preserves the
  original single-arg initialization). The TCCL is the spec's own resolution idiom —
  `Caching.getDefaultClassLoader()` **is** the TCCL, and the customization classes
  (`CacheLoader`/`CacheWriter`/`ExpiryPolicy`/listeners) resolve through `FactoryBuilder`, which
  also uses the TCCL — so types now resolve the same way as customizations, and
  `CacheManagerImpl`'s OSGi swap makes the TCCL == the manager loader where the TCCL is
  unreliable. **Threading the manager loader directly (types via `managerCL.loadClass`,
  customizations via a CL-aware `FactoryCreator`) was built and rejected**: the spec's own
  `FactoryBuilder` uses the TCCL, so out-correcting it for types alone splits a cache's loaders
  for the one case it would help (an explicit-CL manager outside OSGi), which `FactoryBuilder`
  doesn't help either. Match the TCCL idiom; don't add a manager-CL parameter to `FactoryCreator`.
  Pinned by `TypesafeConfigurationTest.resolvesTypesViaContextClassLoader`.
- **`TypesafeConfigurator.from` swallows only `ConfigException.BadPath`; other
  `ConfigException`s bubble up raw — intentional** (adjudicated 2026-07-12 with Ben).
  `BadPath` is the mandatory no-op for a JCache cache name that isn't representable as a
  Typesafe config path (Typesafe's path grammar is stricter than JCache names), returning
  `Optional.empty()`. Every other `ConfigException` (Missing/WrongType) is a real
  misconfiguration and *should* surface — don't wrap creation failures in `CacheException`
  here. (`addKeyValueTypes` CNFE → `IllegalStateException` likewise surfaces as a real
  config error; the `FactoryBuilder` bare-`RuntimeException` on a bad factory class is the
  spec's own code, `javax.cache.configuration.FactoryBuilder`, not Caffeine's to rewrap.)

- **`invokeAll` isolates a per-key failure instead of aborting the batch** (fixed 2026-07-12).
  The per-key catch was `EntryProcessorException`-only, so a non-EPE failure — e.g. a
  non-serializable key's `copyOf(key)` `CacheException`, evaluated *outside* the EPE-wrapping
  remap — escaped and aborted the whole batch (committing side effects for already-iterated
  keys, then discarding all results). A second `catch (RuntimeException e)` now captures it as
  that key's result: an existing EPE passes through as-is, a non-EPE is wrapped once in
  `EntryProcessorException` (matching Ehcache3; the RI/Hazelcast double-wrap `EPE(EPE)`). All
  four impls (RI/Ehcache3/cache2k/Hazelcast) isolate per-key with a broad catch + EPE-wrap;
  Caffeine was the lone outlier. **Single-key `invoke` is intentionally unchanged** — it throws
  the raw `CacheException` for a bad key (consistent with `put`, and the `invoke` javadoc's
  "EPE only if the EntryProcessor throws"); the EPE-wrap is only the `invokeAll` *result*
  surface, whose `EntryProcessorResult.get()` is spec-declared `@throws EntryProcessorException`.
  Don't wrap the failure inside `invoke` itself. Pinned by
  `CacheProxyTest.invokeAll_perKeyFailure_isolatedNotAborted`.

- **Refresh `reload` honors the update null/throw → "unchanged" rule** (fixed 2026-07-12).
  `JCacheLoaderAdapter.expireTimeMillis` (shared by `load`/`loadAll` creation and `reload`
  update) called `duration.isZero()` without a null guard, so a `getExpiryForUpdate()`
  returning `null` — the `CreatedExpiryPolicy`/`AccessedExpiryPolicy`/default-`EternalExpiryPolicy`
  behavior — NPE'd into the `catch (RuntimeException)` and returned `Long.MAX_VALUE` (eternal)
  plus a WARNING per refresh, silently making a finite-expiry entry eternal on
  `refreshAfterWrite`. The helper now takes a `boolean created` (mirroring
  `CacheProxy.getWriteExpireTimeMillis`): a null-or-throwing **update** returns the
  `Long.MIN_VALUE` "unchanged" sentinel, which `reload` remaps to `oldValue.getExpireTimeMillis()`;
  **creation** keeps null/throw → eternal so the loaded entry is not lost. `reload` is an update
  (it receives `oldValue`), so this matches the put/replace update paths — both null and a
  throwing `getExpiryForUpdate` leave the expiration unchanged; only the *insert* path is
  eternal-on-throw. Pinned by `CacheLoaderTest.reload_nullUpdateExpiry_keepsExpiration` /
  `reload_updateExpiryFailure_keepsExpiration`.

- **`putAll` awaits committed entries' synchronous listeners even when a copier throws mid-loop**
  (fixed 2026-07-12). Under store-by-value, `putNoCopyOrAwait`'s `copyOf(value)`/`copyOf(key)` run
  before the atomic compute, so a non-copyable entry threw out of the store loop and skipped the
  single post-loop `awaitSynchronous` — the already-committed entries' `CREATED`/`UPDATED` sync
  futures (added to the `pending` ThreadLocal by `EventDispatcher.publish`) leaked to the thread's
  next operation (over-wait only; no correctness loss). The loop is now wrapped in try/finally with
  `awaitSynchronous` in the finally, matching `getAll`. Correct because every committed `putAll`
  publish corresponds to a real mutation, and the up-front `writeAll`-failure path publishes nothing.
  `removeAll(Set)` shares the loop shape but its `removeNoCopyOrAwait` uses the raw key (no `copyOf`),
  so it has no mid-loop throw vector and is unaffected. Pinned by
  `EventDispatcherTest.putAll_copierFailsMidway_doesNotLeakPending`.

- **`CacheProxy` surfaces a store-by-value copier failure as `CacheException` across the whole API**
  (fixed 2026-07-12). `get`/`getAll` propagated a copier's non-`CacheException` `RuntimeException`
  raw, while `LoadingCacheProxy.get`/`getAll` wrapped it — a `get`-behaves-differently-by-read-through
  divergence (sibling-divergence G-F7). Per spec each op `@throws CacheException if there is a
  problem fetching/doing …`, and the RI's `fromInternal` read-copy throws `CacheException`. Fixed at
  the copier boundary: the `copyOf` primitive (exception-wrapping folded in; the old `copyValue`
  removed, its callers now `copyOf(expirable.get())`) rethrows `NPE`/`ISE`/`CCE`/`CacheException` raw
  and wraps any other `RuntimeException` in `CacheException`, so every method
  (`get`/`getAll`/`getAnd*`/`put*`/`replace*`/`iterator`) is consistent — the copier is the only
  user-pluggable non-`CacheException` vector (a writer failure is already `CacheWriterException`).
  `copyOf` is also **strict** (non-null parameter) so NullAway enforces the assumed non-null at every
  call site; the only genuinely-nullable callers are the 3 `getAnd*` prior-value returns, which guard
  with `(x == null) ? null : copyOf(x)`. The `JCacheLoaderAdapter`'s own `copyOf` is deliberately left
  surfacing copier failures as `CacheLoaderException` (a `CacheException` subtype, contextually correct
  for the read-through/refresh/`loadAll` flow; converging it would need a `catch (CacheException)`
  passthrough that risks the TCK's mandatory loader→`CacheLoaderException` wrapping). Pinned by
  `CacheProxyTest.copierFailure_wrappedInCacheException`.

When auditing JCache, read this list first to avoid re-deriving known
false-positives, then run the differential on anything new.
