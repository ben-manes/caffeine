---
name: audit-sibling-divergence
description: Differential audit comparing matched code paths that should behave identically. Spawns one auditor per sibling pair (sync/async, bounded/unbounded, view consistency, bulk vs single, generated node variants, read fast vs slow, adapter conformance) and requires a concrete witness scenario where the two paths diverge observably.
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Agent, Write
---

# Audit: Sibling Divergence

The existing snapshot audits look at one code path and ask "is it correct?"
This audit looks at TWO paths that should produce the same observable result
and asks "do they actually agree?" Divergence between matched paths is a
confirmed historical bug pattern in Caffeine (refresh+expiration sync/async
asymmetry, weak/strong publication differences, fast-path/slow-path
disagreement under contention).

## When to run

- After significant changes to BoundedLocalCache, LocalAsyncCache, or any
  generator under `caffeine/src/javaPoet/java/`.
- After changes to the user-facing adapters (`jcache/src/main`, `guava/src/main`):
  their write/expiry/exception-translation paths are sibling families with each
  other AND with the external spec/reference they claim to match (Group G). This is
  where the audit's highest-yield gap was — adapters are user-facing yet were never
  in any audit's scope.
- Before a major release as a defense against silent sync/async drift.
- Once per quarter as a baseline audit.

Heavyweight (4-6h of agent time). Token-intensive. Do not run for routine
pre-commit review (use `/review-change` for that).

## Step 1: Inventory matched pairs

The priority matched pairs are listed below. Before launching, glance at the
codebase to confirm each pair is still present and add any new pairs (e.g.,
a new view, a new feature with sync+async variants):

**Group A — sync vs async cache** (highest historical bug yield)
- A1: `Cache.get(k, Function)` vs `AsyncCache.get(k, BiFunction)` — load semantics, listener delivery, refresh hand-off
- A2: `LoadingCache.refresh(k)` vs `AsyncLoadingCache.refresh(k)` — completion, exception, stale-detection
- A3: `Cache.asMap().compute*` vs `AsyncCache.asMap().compute*` — atomicity, listener cause, weight delta
- A4: `Cache.invalidate*` vs `AsyncCache.synchronous().invalidate*` — listener cause, in-flight value handling

**Group B — storage variants**
- B1: BoundedLocalCache vs UnboundedLocalCache for shared map operations
  (most ops are inherited; check overridden ones and the inherited ones that
  reference eviction-only fields)
- B2: Generated Node variants (`PS`, `FS`, `PW`, `FW`, `PSAW`, `PSWMS`, etc.)
  — for each method declared in `Node.java` that has multiple subclass
  implementations, verify all subclasses use consistent access modes,
  lifecycle transitions, and weight discipline

**Group C — view consistency**
- C1: `keySet().contains(k)` vs `containsKey(k)` vs `asMap().get(k) != null`
- C2: `values().contains(v)` vs `containsValue(v)`
- C3: `entrySet()` iteration vs `forEach()` vs `keySet()` + `get(k)` per key
- C4: `size()` vs `entrySet().size()` vs counting via iterator

**Group D — bulk vs single-key**
- D1: `getAllPresent(keys)` vs N×`getIfPresent(k)`
- D2: `getAll(keys, loader)` vs N×`get(k, loader)`
- D3: `putAll(m)` vs N×`put(k, v)`
- D4: `invalidateAll(keys)` vs N×`invalidate(k)`
- D5: `invalidateAll()` vs `invalidateAll(allKeys())`

**Group E — equivalent-by-construction**
- E1: `LoadingCache.get(k)` vs `Cache.get(k, cacheLoader::load)`
- E2: `getOrDefault(k, d)` vs `(get(k) == null ? d : get(k))` (ignoring atomicity)
- E3: `putIfAbsent(k, v)` vs `compute(k, (key, val) -> val == null ? v : val)`
- E4: `AsyncCache.synchronous()` view vs an equivalent sync `Cache` built with
  the same configuration

**Group F — internal paths to the same outcome**
- F1: Read fast path (`getIfPresent` optimistic) vs slow path (under
  `synchronized(node)`) — same key, same logical time, must agree on
  present-ness and value identity
- F2: Eviction listener (sync) vs Removal listener (async) for an evicted
  entry — both should fire, with consistent key/value/cause and the same
  set of entries
- F3: Maintenance task variants (`AddTask`, `UpdateTask`, `RemovalTask`,
  `RemovedTask`) — weight delta sign convention, telescoping sum
  preservation across task orderings

**Group G — adapter conformance** (user-facing: `jcache/`, `guava/`)
- G1: JCache write-path family — every path that builds an `Expirable<V>` and gates on
  `ExpiryPolicy` must agree on the create/update guard, the put statistic, and the
  CREATED/UPDATED/EXPIRED events: `put`/`putAll` (`putNoCopyOrAwait`), `putIfAbsent`
  (`putIfAbsentNoAwait`), `getAndPut`, `replace`, and the `EntryProcessor` `postProcess`
  CREATED/UPDATED/LOADED cases. (A zero-creation-expiry guard present in the put helpers
  but missing from `postProcess` was a real bug — a phantom CREATED event + put stat.)
- G2: JCache adapter vs reference — for each spec-ambiguous edge the TCK does NOT pin
  (zero/eternal/null creation & update expiry, loader-exception wrapping, read-through
  statistics), compare the adapter's observable outcome against the JSR-107 RI and ≥3
  ecosystem impls (Ehcache3, Infinispan, Hazelcast, Coherence, cache2k). The spec is the
  contract; the RI + majority resolve ambiguity. A green TCK is necessary, not sufficient
  — it is silent on exactly the paths that drift.
- G3: Guava facade vs underlying Caffeine — each `CaffeinatedGuavaCache` / facade-view
  method vs the Caffeine method it delegates to: null-query tolerance, exception
  translation (`InvalidCacheLoadException` / `ExecutionException` /
  `UncheckedExecutionException` / `ExecutionError` by checked-ness), bulk partial results.
- G4: Guava facade vs real Guava — the executable oracle for G3: run the SAME
  guava-testlib suite (matched feature flags) and operation sequences against the facade
  and a real Guava `CacheBuilder` cache; any divergence is a drop-in-compatibility bug.

If a new feature has a sync and async variant not listed above, add it as group H
before launching. When auditing the simulator, add reader-vs-sibling-reader (shared
binary/text formats, e.g. the libCacheSim family) and climber-vs-climber (shared
gradient/timestep convention) port pairs as a group — lower priority, since simulator
divergence yields misleading benchmark numbers rather than user-facing bugs.

## Step 2: Spawn parallel differential auditors

Launch one subagent per group (seven groups → seven parallel agents). Each
agent gets the prompt below, adapted to its group. Run them in a single
message so they execute in parallel.

```
You are auditing the Caffeine cache for sibling divergence: cases where two
code paths that should produce identical observable behavior do not.

YOUR GROUP: <group letter and pairs from the inventory>

The Caffeine source code is at:
- Core: caffeine/src/main/java/com/github/benmanes/caffeine/cache/
- Generated: caffeine/build/generated/sources/ (run `./gradlew :caffeine:generateNodes
  :caffeine:generateLocalCaches` if empty)
- Generators: caffeine/src/javaPoet/java/com/github/benmanes/caffeine/cache/
- Adapters (Group G): guava/src/main/java/com/github/benmanes/caffeine/guava/ and
  jcache/src/main/java/com/github/benmanes/caffeine/jcache/. For G2/G4 the reference
  contract is external — the JSR-107 1.1.1 spec/TCK and a real Guava `CacheBuilder`
  cache; construct the witness as a differential test (run both sides), not a read alone.
  To read Guava's actual behavior (no clone needed), WebFetch a specific method from
  `https://raw.githubusercontent.com/google/guava/master/guava/src/com/google/common/cache/LocalCache.java`
  — the source-level complement to G4's executable oracle; the executable side must use
  the pinned Guava version (`libs.versions.toml`), not master.

# Phase 0: Plan
For each pair in your group:
- State the contract the two paths jointly promise (what does an observer
  see when they call A vs B?).
- Predict the 2-3 most likely categories of divergence (different access
  mode, different listener cause, different exception handling, ordering of
  notifications, in-flight value visibility, weight accounting, etc.).

# Phase 1: Trace each side
For each pair:
1. Locate both implementations. Read each end to end (not just the diff).
2. Build a side-by-side table of the observable steps each path takes:
   field reads/writes (with access mode), lock acquisitions, listener
   invocations, exceptions thrown, return values.
3. Identify every step where the two paths differ. For each difference:
   - Is the difference observable to a caller?
   - Is it explained by an intentional design decision? (read
     .claude/docs/design-decisions.md and .claude/rules/design-decisions.md
     ONLY AFTER you have recorded the difference — design context causes
     premature dismissal)
   - If observable and unexplained, this is a candidate finding.

# Phase 2: Construct a witness
For each candidate finding, construct a CONCRETE WITNESS:
- Cache configuration (size, weigher, listener, expiry, ...)
- Exact sequence of method calls
- Expected observation if the paths agreed
- Actual observation given the divergence
- Strong enough that a developer could write a failing unit test from it
  without further investigation.

A finding without a concrete witness is NOT acceptable — drop it.

# Phase 3: Self-challenge
For each finding, attempt to refute it by re-reading the source. If you
can construct a path through the code that resolves the divergence (e.g.,
the second path also fires the listener through a different route you
missed), drop the finding. Be ruthless — the user wants high-precision
findings, not volume.

# Phase 4: Output
For each surviving finding, output:
- PAIR: <pair label, e.g., A1>
- PATH-A: file:method
- PATH-B: file:method
- DIVERGENCE: <one-line description of what differs>
- WITNESS: <concrete scenario>
- OBSERVABLE: <what the caller sees that contradicts the joint contract>
- DESIGN-MATCH: <design-decisions.md item it partially matches, or "none">
- SEVERITY: critical | high | medium | low
- CONFIDENCE: high | medium

If a group has zero findings, output a coverage summary listing every pair
inspected, every method traced, and every difference dismissed (with the
reason for dismissal). Zero findings with thorough coverage is acceptable.
Zero findings with shallow coverage is not — keep looking.

DO NOT report:
- Performance differences (covered by /audit-performance)
- Style differences
- Comment/javadoc differences unless they constitute the contract drift
- Differences explicitly documented as intentional in design-decisions.md
  (note them in the "explained" section instead)
```

## Step 3: Evaluator challenge

For each agent that returned findings OR a zero-findings coverage proof,
spawn ONE evaluator subagent (general-purpose). The evaluator sees ONLY
the reviewer's report — no source code.

```
You are challenging a differential audit report. Your job is to find what
the auditor MISSED.

For each finding:
1. Is the witness scenario actually reproducible? Identify any unstated
   precondition (specific config, timing, prior state) that the witness
   does not enumerate but requires.
2. Is the divergence actually observable to a caller? Or is it an internal
   difference that produces the same external result?
3. Is the auditor's "joint contract" the actual contract, or did they
   assume a stronger contract than the documentation promises?

For each zero-findings claim:
4. Given the auditor's stated coverage, what categories of divergence
   might they have under-weighted? (E.g., focused on synchronous control
   flow, missed exception paths; focused on happy path, missed in-flight
   transitions.)

Output: prioritized list of challenges. Be specific about which finding
and which gap.
```

Have the original reviewer address each challenge by re-reading source.
Drop findings the reviewer cannot defend with concrete evidence. Add new
findings the reviewer confirms.

## Step 4: Adjudicate against design docs

Read `.claude/docs/design-decisions.md` and
`.claude/rules/design-decisions.md`. For each surviving finding, classify:

- **confirmed-divergence** — not explained by design docs; treat as a bug
- **intentional-divergence** — documented design decision (e.g., async
  listener delivery is intentionally different from sync; weight=0 entries
  are pinned across all paths); keep in the report under "explained"
- **documentation-gap** — the divergence is intentional but not documented
  anywhere a fresh reader could find it; flag as a docs fix

## Step 5: Triage and report

Triage confirmed findings by severity per `.claude/docs/finding-taxonomy.md`.

Tag each confirmed finding with the divergence axis:
- **sync-async** — the two paths represent the sync and async variants
- **storage-variant** — bounded vs unbounded or generated node variant
- **view-consistency** — view methods disagreeing with each other
- **bulk-vs-single** — aggregate operation diverging from per-element
- **equivalent-by-construction** — two APIs that should be interchangeable
- **internal-path** — fast vs slow path or maintenance task variants
- **adapter-conformance** — an adapter path diverging from a sibling adapter path, the
  underlying Caffeine method it delegates to, or the external spec/reference it claims
  to match

Write the full report to `.claude/reports/audit-sibling-divergence.md`.

Format:

```
# Sibling Divergence Audit

[N] differential auditors compared [M] sibling pairs across [G] groups.
[K] findings survived self-challenge and evaluator challenge.

## Confirmed divergences (likely bugs)

#1 [severity] [axis] PAIR — one-line summary
- PATH-A: file:method
- PATH-B: file:method
- DIVERGENCE: ...
- WITNESS: ...
- OBSERVABLE: ...

## Intentional divergences (documented)
- [pair] — link to design doc that explains the difference

## Documentation gaps (intentional but undocumented)
- [pair] — what a fresh reader would conclude vs the actual intent

## Coverage summary
- Group A: [pairs inspected, methods traced, dismissals]
- Group B: ...
- ...

## Evaluator challenges
- [N] challenges received across [M] groups; [K] led to new findings;
  [J] confirmed the original conclusion with additional evidence.

## Residual risk
What was not inspected and why.
```

## Notes

- The auditor agent's 4-phase methodology applies here too, but per-pair
  rather than per-method. Each subagent runs Phase 0 through Phase 3 for
  its assigned pairs.
- Generator pairs (B2) are special: read both the generator and the
  generated output. A divergence might be in the generator's emit logic
  (one feature combination emits the wrong access mode) or in a missing
  generator case (a feature combination emits no method at all).
- The synchronous() view of an async cache (E4) is the highest-yield pair
  in group E because it goes through the async code path internally but
  promises sync semantics. Historical bugs here include `synchronous()`
  exposing in-flight CompletableFuture state in unexpected ways.
