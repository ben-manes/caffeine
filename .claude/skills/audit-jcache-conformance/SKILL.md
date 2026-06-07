---
name: audit-jcache-conformance
description: JSR-107 (JCache) spec-conformance audit
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Agent, Write
---

# Audit: JSR-107 Conformance

This audit verifies the `jcache/` adapter against the **full JSR-107 1.1.1
specification** — every normative domain, not just the corners the last bug
touched. It walks the spec surface and allocates depth by where the TCK is blind.

**Why the TCK is the floor, not the verification.** The TCK frequently asserts
only the observable **end-state** (`containsKey`/`get`), not the **event stream**
(`CREATED`/`UPDATED`/`EXPIRED`/`REMOVED`) or the **statistics** (`CachePuts`,
hits/misses, removals, evictions). Two implementations with different events and
different counts both pass. Every real bug found in this adapter recently lived in
exactly that TCK-blind gap (zero-creation-expiry phantom `CREATED`;
zero-update-expiry `put`-family suppressing `UPDATED`). So the audit goes **DEEP**
(full spec→RI→ecosystem→internal-parity differential) on the event/statistic/
expiry/write-through surface, and runs a **COVERAGE** pass (spec-text correctness +
confirm a test pins it) over the rest of the spec — escalating any COVERAGE clause
that turns out to hide a TCK-invisible event or statistic to the DEEP differential.

This is a focused, resource-heavy specialization of `/audit-sibling-divergence`
Group G2. Read **`.claude/docs/jsr107-conformance.md` first** — it is the resource
map (live-fetch recipes, RI/ecosystem class locations for every domain below), the
differential methodology, the parity-test pattern, and the catalogue of
already-resolved divergences (do not re-flag those).

## When to run

- After any change to `jcache/src/main` touching `CacheProxy`, `LoadingCacheProxy`,
  `EntryProcessorEntry`/`postProcess`, `EventDispatcher`, expiry, statistics,
  the write-through (`CacheWriter`) path, or store-by-value copying.
- Before a release, as a comprehensive 1.1.1 re-verification.
- Once per quarter as a conformance baseline.
- When a `/audit-sibling-divergence` run raises a Group G2 finding — use this to
  resolve the *direction* (the static audit cannot, and has guessed wrong before).

Heavyweight (fetches the spec, clones/fetches the ecosystem, may spawn
sub-auditors). Not for routine pre-commit review.

## Step 0: Load the spec and the resource map

Read `.claude/docs/jsr107-conformance.md`. Fetch the live spec:

```bash
DOC=1ijduF_tmHvBaUS7VBBU2ZN8_eEBiFaXXg9OI0_ZxCrA
curl -fsSL "https://docs.google.com/document/d/$DOC/export?format=txt" -o /tmp/jsr107_spec.txt
```

Confirm it fetched the real spec (grep for `getExpiryForUpdate`), not an auth page.
The export reflows; reference the spec by **section name** (the 1.1.1 section
headings are listed per-domain in the matrix below). Locate the TCK sources
(`find ~/.gradle/caches -name 'cache-tests-1.1.1-test-sources.jar'` and
`jcache/build/tck/`).

## Step 1: Build the spec-surface matrix

Walk the **whole** 1.1.1 spec. Map each normative domain to its spec section, its
adapter code, and the **depth** it warrants. Do not stop at the write-path/expiry
family — that is one DEEP domain among several, and the recent bugs there created a
recency bias this matrix exists to correct.

**Depth = DEEP** — the TCK does not pin the event stream / statistic / expiry
timing, so it can mask a real divergence. Run the full differential (Step 2) and
add a parity-matrix test.
**Depth = COVERAGE** — the spec text is unambiguous and the TCK's end-state
assertion is sufficient. Confirm (a) the adapter matches the spec text and (b) a
test pins it; cite the test. Escalate to DEEP the moment a hidden event/stat
appears.

| # | Domain | 1.1.1 section | Depth | Verify |
|---|--------|---------------|-------|--------|
| A | Write ops × expiry | *Expiry Policies*; *Statistics Effects* | **DEEP** | `put`/`putAll`/`putIfAbsent`/`getAndPut`/`replace(K,V)`/`replace(K,V,V)`/`getAndReplace` — creation-vs-update `ZERO`/`ETERNAL`/`null` expiry; `CREATED`/`UPDATED` emission; `CachePuts` count. **Internal parity across all siblings.** |
| B | Read & access | *Expiry Policies*; *Integration*; *Statistics Effects* | **DEEP** | `get`/`getAll`/`containsKey`/`iterator` — `getExpiryForAccess` `ZERO`/`null`/finite; `CacheHits`/`CacheMisses` (containsKey must not count); read-through load as `get`-miss-not-put |
| C | Remove ops | *Cache Entry Listeners*; *Integration*; *Statistics Effects* | **DEEP** | `remove(K)`/`remove(K,V)`/`getAndRemove`/`removeAll(keys)`/`removeAll()`/`clear()` — `REMOVED` event + `oldValue`; **`removeAll()` counts removals, `clear()` does not**; removal-stat gating on an already-expired entry; `CacheWriter.delete` |
| D | Entry processors | *Entry Processors*; *Statistics Effects* | **DEEP** | `invoke`/`invokeAll` — `EntryProcessorEntry.Action` machine (NONE→READ/CREATED/UPDATED/LOADED/DELETED) vs RI `MutableEntryOperation`; per-op events + stats; read-through `getValue()` counting as a put (catalogued — confirm, don't re-flag) |
| E | Events | *Cache Entry Listeners* | **DEEP** | `CREATED`/`UPDATED`/`REMOVED`/`EXPIRED` payload (`isOldValueAvailable`/`getOldValue`); synchronous vs asynchronous dispatch; `CacheEntryEventFilter`; per-key ordering (`EventDispatcher` CompletableFuture chains); runtime register/deregister |
| F | Statistics | *Statistics Effects of Cache Operations* | **DEEP** | The **full** `CacheStatisticsMXBean` matrix: `CacheHits`/`Misses`/`Gets`/`Puts`/`Removals`/`Evictions` × every op. Failed `putIfAbsent`/`replace` accounting; **`CacheEvictions` (the Caffeine-native-eviction→JCache-stat bridge — no sibling resolves this identically)**; `clear()` resets; averages (RI leaves them 0) |
| G | Integration — writer | *Integration* | **DEEP** | Write-through: `CacheWriter.write`/`delete` ordering vs store and vs event/stat; **a writer exception must suppress the event and the `CachePuts` increment**; `writeAll`/`deleteAll` partial-failure collection mutation; `CacheWriterException` wrapping |
| H | Integration — loader | *Integration* | **DEEP** | Read-through `get`/`getAll`/`invoke`; `loadAll` `replaceExistingValues` + completion listener; `CacheLoaderException` wrapping (**relaxed in 1.1.1 — check the revision history, not the 1.0 PDF; but TCK still asserts wrapping for `loadAll`**) |
| I | Store-by-value | *Store-By-Value and Store-By-Reference* | COVERAGE | Copy points (`put`/`get`/`iterator`/event payloads); caller-mutation isolation; `RISerializingInternalConverter` vs `RIReferenceInternalConverter`. Escalate if a copy is skipped on an event/stat path |
| J | Types | *Configuration* | COVERAGE | `getKeyType`/`getValueType` enforcement; `ClassCastException` on wrong-typed `put`/`get`; `getCache(name, K, V)` type check |
| K | Configuration | *Configuration* | COVERAGE | `MutableConfiguration` snapshot-on-create; `Factory<CacheLoader/CacheWriter/ExpiryPolicy>`; read-through/write-through/store-by-value/stats/mgmt flags |
| L | Lifecycle | *Caching Providers* | COVERAGE | `close`/`isClosed` → `IllegalStateException` on every op; `CacheManager` create(dup→`CacheException`)/get/destroy; `getCacheNames` immutable iterator; provider URI/classloader/properties |
| M | Management | *Caching Providers* (MXBeans) | COVERAGE | `CacheMXBean` attributes; JMX `ObjectName` sanitize (catalogued); enable/disable statistics & management at runtime |
| N | Null / adversarial inputs | method javadocs | COVERAGE | `NullPointerException` contract on null key/value/map/filter/processor args across the full API |

(*Annotations* — CDI/Spring `@CacheResult` etc. — is a separate spec section the
adapter does not implement; out of scope.)

## Step 2: Run the differential

For each **DEEP** domain corner, in order, until the reference behavior is
unambiguous:

1. **Spec text** — read the governing javadoc/table in `/tmp/jsr107_spec.txt`. Note
   deliberately-different wording between sibling operations (create-vs-update is
   the canonical trap).
2. **TCK** — find the test and read *what it asserts*. End-state only → it cannot
   settle the event/stat question; proceed. (When TCK and spec javadoc disagree,
   **TCK wins** — see `.claude/rules/jcache-adapter.md`.)
3. **RI** — read the oracle class for that domain (the doc's resource map names one
   per domain: `RICache` write/remove paths, `RICacheStatisticsMXBean`,
   `RICacheEventDispatcher`, `RICache.writeCacheEntry`/`deleteCacheEntry`, etc.).
4. **≥3 ecosystem impls** — fetch the adapter files named in the doc. Classify each;
   record any split.
5. **Caffeine internal parity** — verify every sibling path agrees with the
   reference *and with each other*. Internal disagreement = the highest-signal
   finding (one side is provably wrong).

For each **COVERAGE** domain: confirm the adapter's behavior matches the spec text,
and that a TCK or unit test pins it — cite the test by name. If the only test
asserts end-state and the clause hides an event/stat (e.g. does `clear()` fire
`REMOVED`? does a store-by-value copy happen on the event payload?), **escalate that
corner to DEEP** and run the ladder above. A COVERAGE domain with a correct-but-
untested clause is a coverage finding, not a pass.

Spawn one sub-auditor per domain-group — **write/read/remove (A–C)**, **entry
processors (D)**, **events (E)**, **statistics (F)**, **integration loader+writer
(G–H)**, **store-by-value/types/config/lifecycle/management (I–N)** — each with the
doc + the fetched spec as context, each required to return a concrete witness
(operation sequence → divergent event/stat/end-state, or the test that pins the
clause). Sub-agents cannot read session memory — **paste the doc's divergence
catalogue inline** so they do not re-derive known false-positives.

## Step 3: Resolve and pin

For each confirmed real divergence:

- State the spec text, the RI behavior, the ecosystem tally, and the Caffeine
  internal-parity result. The fix direction follows the spec + RI; if the static
  finding assumed the opposite direction, **say so** (this has happened — the
  zero-update-expiry fix direction was inverted from what the static audit guessed).
- Implement the fix as a minimal, surgical change, then add a **parity-matrix
  test** (`writeOp_*`/`readOp_*`/`removeOp_*` over every sibling op) asserting they
  all produce the same event count, statistic delta, and end-state. The TCK is not
  the regression guard — the parity test is.
- Run `:jcache:test` **and** `:jcache:tckTest` (per `.claude/rules/jcache-adapter.md`;
  the TCK encodes interpretations unit tests miss).
- Update `.claude/docs/jsr107-conformance.md`'s catalogue and ecosystem matrix so
  the next run does not re-litigate it.

For a COVERAGE-tier clause that is correct but **untested**, record it as a coverage
gap (which test to add), not a bug.

## Output

Write `.claude/reports/audit-jcache-conformance.md` using the standard
`.claude/docs/finding-taxonomy.md` schema (severity / category / confidence /
classification).

Lead with the **spec-surface coverage matrix**: every domain A–N → depth → verdict
(conformant / divergence / coverage-gap) → the witness or the pinning-test citation.
A clean run must show *every domain was reached*, so "clean" means the full 1.1.1
surface was walked — not just the recent-bug corners.

Then, for each finding: the spec citation (by section name), the RI behavior, the
ecosystem tally, the Caffeine internal-parity result, the witness (operation →
divergent observable), and the resolution direction with its justification. A
finding with no spec/RI/ecosystem backing for its *direction* is not ready — record
it as escalated, not as a fix.
