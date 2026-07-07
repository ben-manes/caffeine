---
name: audit-map-contract
description: Audit ConcurrentMap and Map contract compliance for asMap() view
context: fork
agent: auditor
disable-model-invocation: true
---

Audit compliance with `java.util.concurrent.ConcurrentMap` and `java.util.Map` contracts,
and Caffeine's alignment with the Java Collections Framework.

## Cross-reference the latest OpenJDK source — do NOT reason from JavaDoc memory

The JavaDoc is silent or ambiguous on the behaviors that actually bite (null inside a bulk
collection arg, `containsAll(self)`, `equals` across map types, the *optional* NPE points,
default-method bodies). **WebFetch the real source and read the method body** before
asserting a contract. Track `master` (latest) — we stay pragmatically current, not pinned
to a JDK version. Fetch the raw form (`raw.githubusercontent.com/openjdk/jdk/master/src/
java.base/share/classes/…`), not the `blob` page. These files are large (CHM ~6500 lines)
and WebFetch answers a *prompt* over the content with a small model, so a generic "dump the
file" truncates — **prompt for the specific method** ("quote the exact body of
`CollectionView.containsAll`"), one method per fetch:

- **ConcurrentHashMap** — the primary reference; `asMap()` is a `ConcurrentMap` and closest
  to CHM:
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/concurrent/ConcurrentHashMap.java`
- **ConcurrentSkipListMap** — the *other* JDK `ConcurrentMap`; the tie-breaker on whether a
  divergence from CHM is legal (e.g. it also throws UOE on `entrySet().add`, where CHM's
  put-through is a nonstandard v8-rewrite addition):
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/concurrent/ConcurrentSkipListMap.java`
- **ConcurrentMap** — interface + default methods (`getOrDefault`, `compute*`, `merge`,
  `replaceAll`):
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/concurrent/ConcurrentMap.java`
- **Map** — base contract + interface default methods:
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/Map.java`
- **AbstractMap** — the default-method bodies the views inherit (`AbstractMap.equals`,
  and via `AbstractCollection`/`AbstractSet` the `containsAll`/`removeAll`/`retainAll` loops):
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/AbstractMap.java`
- **HashMap** — the most common `equals`/`hashCode` comparison target:
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/HashMap.java`
- **IdentityHashMap** — identity-semantics edge, relevant to weak-key identity and the
  async future-keyed raw view:
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/IdentityHashMap.java`
- **WeakHashMap** — relevant to weak keys with automatic removal:
  `https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/java/util/WeakHashMap.java`
- **Guava `LocalCache`** — the reference for Caffeine's Guava-compat behavior (the
  `CaffeinatedGuava` facade + the uniform null-leniency the philosophy below cites; its
  `asMap()` views, removal causes, loader/exception translation):
  `https://raw.githubusercontent.com/google/guava/master/guava/src/com/google/common/cache/LocalCache.java`

## Alignment philosophy — close to CHM, diverge when better

**Be pragmatically close to CHM, but Caffeine may diverge where its behavior is better** —
and when it does, the divergence must be *coherent* and recorded (design-decisions.md /
the cross-model ledger). Do not treat "differs from CHM" as a bug by itself:

- **CHM is internally inconsistent.** e.g. `removeAll([null])` throws NPE while
  `containsAll([null])` returns false — an artifact of which methods happened to get an
  explicit null-guard, not a contract. **Don't chase CHM's inconsistencies.**
- **Caffeine's own coherent principles are the target**, not any one JDK map's quirks:
  null-hostile on *direct* single-element ops (`put`/`get`/`containsKey`/`contains(null)` →
  NPE — nulls are rejected), but null-tolerant of a null *element* in a *bulk* collection
  arg (`containsAll`/`removeAll` skip it — a null is trivially absent); weakly-consistent
  views; `size()` is an estimate; expired/collected entries filtered from queries and
  iteration. The `CaffeinatedGuava` facade is the deliberate exception — uniformly
  null-lenient (Guava-compat), overriding native null-hostility only where Guava diverges.
- A contract-**optional** behavior (permitted NPE, ordering, `equals` across types) has a
  legal **spread** — check several impls (CHM / CSLM / HashMap / Guava), not one, and note
  that the collections testlibs usually **tolerate both** legal choices (e.g. guava-testlib
  `testContainsAll_nullNotAllowed` does `assertFalse(...)` with `catch (NPE tolerated)`).
  Flag a divergence only when Caffeine is the *incoherent* one, or diverges from *both* CHM
  and Guava with no better rationale.

## Settle divergences empirically, not from memory

For any "does Caffeine match CHM/Guava?" question, compile a tiny harness and run Caffeine
**side-by-side with real CHM / CSLM / Guava** — don't guess (this session, "we mirror CHM"
on `containsAll([null])` was empirically wrong — both CHM *and* Guava return false, Caffeine
NPE'd; and the CHMv8 `entrySet().add` history was non-obvious):

- Classpath: the **built jar** `caffeine/build/libs/caffeine-*.jar` (it has the generated
  node classes like `SSMS`; `build/classes/java/main` does NOT → factory reflection throws),
  plus real guava from `~/.gradle/caches/**/guava-*.jar`.
- Run under **JDK 26** (`~/.gradle/jdks/*26*/**/bin/java`) — the classes are that bytecode level.
- Gotcha: a `timeout … | grep` pipeline reports *grep's* exit code, not gradle's — read the
  `BUILD` line.
- For a native-view fix, `AsMapTest`'s `map` param is polymorphic: `compute=ASYNC` yields the
  async **sync-view** (`LocalAsyncCache.AsMapView`), not BLC/ULC — a view change must cover it.
- Cross-check the collections testlibs (`:caffeine:googleTest`/`apacheTest`/`eclipseTest`,
  `:guava:test`) — they encode the contract's tolerated spread.

## Audit dimensions

1. **Map contract**: equals/hashCode consistency (and across map types — HashMap, TreeMap,
   IdentityHashMap), putAll atomicity (if weigher throws mid-batch), replaceAll per-entry
   atomicity, containsValue consistency.

2. **ConcurrentMap contract**: compute/computeIfAbsent/merge atomicity ("mapping function
   applied at most once"), getOrDefault on expired entries, forEach with concurrent
   mutations, compute returning null (should remove entry), the interface default methods.

3. **Null handling**: NPE at the correct points for null keys/values on *direct* ops;
   null *elements* in bulk args (`containsAll`/`removeAll`/`retainAll`) per the philosophy
   above; mapping functions returning null (compute→remove, merge→remove); putIfAbsent(key,
   null).

4. **Entry/EntrySet contracts**: Map.Entry.setValue() write-through, entrySet
   remove/contains checking both key AND value, snapshot vs live entries, entrySet().add UOE.

5. **Collection view contracts**: keySet()/values()/entrySet() backed by the cache
   (bidirectional), `contains`/`containsAll`/`remove`/`removeAll`/`retainAll`/`removeIf`,
   `containsAll(self)` short-circuit, view equals/hashCode over the logical (filtered) set.

6. **Cache semantics interaction**: expired-but-present entries visible via asMap()?
   Collected weak keys visible? asMap() operations triggering listeners? asMap().put() vs
   cache.put() differences (access time, stats, refresh)? Async raw view vs sync view.

For each finding: **quote the requirement from the fetched OpenJDK source** (the method +
actual body, not remembered JavaDoc), show Caffeine's behavior (ideally via the harness),
name the reference spread (CHM / CSLM / HashMap / Guava) and whether Caffeine is coherent,
and provide a test case. If Caffeine's divergence is *intentional and better*, the output is
a design-decisions.md / ledger note, not a bug.
