# Testing Conventions

- Run a single test method with `--tests 'Class.method'` — fine even if it sweeps the full `@CacheSpec` matrix. Don't run a whole `@CacheSpec` class or the full suite locally; avoid them, or narrow with `-P` flags when you must (CI runs the full matrix, sharded across 40 workers)
- Tests use JUnit Jupiter with Truth assertions and Awaitility for async
- Test classes are parameterized via `@CacheSpec` + `CacheProvider` — use `-P` flags to filter
- New tests should follow the `@CacheSpec` parameterization pattern, not create caches manually
- Use `CacheContext` for test utilities: `context.ticker()`, `context.absentKey()`, etc.
- GC-dependent tests are inherently flaky — use `GcFinalization` and `Awaits.awaitFullGc()`
- Notification assertions: cast to `ConsumingRemovalListener`, check `listener.removed()`
- Fray tests use direct thread creation with `@FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)`, no parameterization
- Tests: `caffeine/src/test/java/`, fixtures: `testFixtures/`
- `examples/` hold the same quality bar as the library: their tests must cover
  unhappy paths (duplicate keys, a failing write/load, empty batches) —
  happy-path-only tests are where example bugs have hidden
- For full test infrastructure details, see `.claude/docs/testing.md`

## Test Discovery Guide

When a change touches an area, these are the test classes to run first. This is a
living guide at the class level — update it when reorganizations happen, don't treat
it as a static contract.

### Public API surfaces
- `Cache` — `CacheTest`
- `LoadingCache` — `LoadingCacheTest`
- `AsyncCache` — `AsyncCacheTest`
- `AsyncLoadingCache` — `AsyncLoadingCacheTest`
- `asMap()` view — `AsMapTest`, `AsyncAsMapTest`
- Builder / spec — `CaffeineTest`, `CaffeineSpecTest`

### Feature areas
- Eviction (size/weight, admission, hill climber) — `EvictionTest`, plus `BoundedLocalCacheTest` for white-box paths
- Expiration — `ExpirationTest` for cross-cutting, plus `ExpireAfterAccessTest` / `ExpireAfterWriteTest` / `ExpireAfterVarTest` for the specific policy
- Refresh — `RefreshAfterWriteTest`
- Weak/soft references — `ReferenceTest`
- Stats — tests under `stats/`
- Scheduler — `SchedulerTest`
- Async helpers — `AsyncTest`

### Internal / white-box
- `BoundedLocalCache` internals — `BoundedLocalCacheTest`
- `UnboundedLocalCache` internals — `UnboundedLocalCacheTest`
- Data structures — `FrequencySketchTest`, `TimerWheelTest`, `BoundedBufferTest`,
  `StripedBufferTest`, `MpscGrowableArrayQueueTest`, `LinkedDequeTest`, `PacerTest`,
  `InternerTest`, `WindowClimberTest`
- Climber behavioral pins — `WindowClimberGateTest`: a four-cell deterministic subset of the
  `/climber-gate` battery (whisper escape, position-jam control, demoflood adjudication, moat
  valley crossing) run as plain JUnit in the standard suite (~7s, seeded synthetic streams,
  generous bars). Each cell's bar is calibrated against a measured *broken* value, not against
  drift: the moat cell reads 58.2 healthy and 46.2 with the audit layer ablated, and is barred at
  53. A cell whose healthy-to-broken separation is under ~5pp belongs in the manual battery
  instead, where it is adjudicated on an N=8 mean (`whisper_mod_a12` is the worked example: 2pp
  of separation and already below LRU, so any CI bar there is loose or flaky). It exists so
  audit-clock liveness, trap escape, and probe-adjudication regressions fail CI; the full
  battery, its sentinels, and the real corpus remain `/climber-gate` (manual). Kept separate
  from `WindowClimberTest` on purpose: pitest's `targetTests` allowlist names that class, so
  folding the workload cells in would run them against every WindowClimber mutant (tens of
  minutes) and distort the calibrated ~88% kill baseline (396/451 over the nested-class scope,
  re-baselined 2026-08-07). The survivor population is five documented equivalence classes
  (min/max/abs boundary mutants; unreachable float-threshold edges; veto band comparisons
  shadowed by the on-band resync; ±1 divide-vs-multiply; removed layer `reset()`s that are
  no-ops on an already-reset machine) plus three honest soft spots queued for a pinning pass:
  mid-flight state across `resized`, the starvation confirm's release, and the reactive
  tier's period read. `WindowClimberFuzzer` scenarios: random samples, teleporting positions,
  fuzzed region geometry, partial adjustment application, cross-tier resizes
- Behavioral regret (does the climber close the gap on a workload) is not the unit suite's job:
  `/climber-gate` holds the known traps and `/audit-regret` searches for new ones, both in the
  simulator against `product.Caffeine`
- `WindowClimber` state or schedule changes must ALSO run
  `./gradlew :caffeine:fuzzTest --tests 'WindowClimberFuzzer'` — its oracle (mirrored by
  `LocalCacheSubject.checkHillClimber`) pins the state-machine invariants and CI runs it; a
  stale bound there shipped as a red CI job in 2026-07 because class-scoped `test` runs never
  touch the `fuzzTest` source set

### Regressions and stress
- Issue-specific regression tests live under `issues/` (e.g., `Issue568Test`) — search
  there when touching code named in a historical GitHub issue
- Cross-feature stress — `MultiThreadedTest`
- Concurrency interleavings — specialized suites (`frayTest`, `lincheckTest`, `jcstress`)

### Narrowing a class or suite run
A single method runs as-is (`--tests 'Class.method'`) — sweeping its matrix is fine.
It's whole classes and the full suite that must be avoided, or narrowed with `-P`
flags (e.g., `-Pcompute=sync -Pkeys=strong -Pvalues=strong -Pstats=disabled`) when
you can't. Over-pinning can empty a method's matrix (JUnit `initializationError`) —
e.g., `ReferenceTest` needs `values=weak/soft`.

## Fuzz Testing (Jazzer)

- Jazzer cannot run 2+ fuzz tests in the same JVM process
  ([jazzer#599](https://github.com/CodeIntelligenceTesting/jazzer/issues/599))
- `forkEvery = 1` is set in `build.gradle.kts` so each test class gets its own fork
- When adding multiple `@FuzzTest` methods to one file, wrap each in a `@Nested`
  inner class so forking isolates them:
  ```java
  final class MyFuzzer {
    @Nested class FuzzA {
      @FuzzTest(maxDuration = "5m")
      void fuzz(FuzzedDataProvider data) { ... }
    }
    @Nested class FuzzB {
      @FuzzTest(maxDuration = "5m")
      void fuzz(FuzzedDataProvider data) { ... }
    }
  }
  ```
- Alternatively, keep one `@FuzzTest` per file (the current convention)
- Fuzz tests require `JAZZER_FUZZ=1` environment variable (set by the Gradle task)

## PIT Mutation Testing

- `./gradlew :caffeine:pitest` runs mutation testing on self-contained data structures:
  TimerWheel, FrequencySketch, Pacer, BoundedBuffer, StripedBuffer, MpscGrowableArrayQueue,
  AbstractLinkedDeque, Interner, Async, Scheduler, Caffeine (builder), CaffeineSpec (parser),
  WindowClimber
- `BoundedLocalCache` and `UnboundedLocalCache` are NOT in scope — the `@CacheSpec`
  parameterized test suite makes PIT's main process OOM during coverage collection,
  regardless of heap size (`mainProcessJvmArgs` doesn't effectively bump the forked JVM).
  Line coverage on those classes is already 100% via JaCoCo, and concurrency bugs aren't
  caught by mutation testing anyway
- `./gradlew :guava:pitest` runs it on the whole adapter package, which is at 120/120 killed
  (100% test strength) — a confirmation that the translation logic is asserted rather than
  merely executed, so re-running it can only show a regression
- **`testSourceSets` must name every suite that covers the target.** PIT runs the `test` task
  only, so a forked or spec suite registered as its own `JvmTestSuite` is invisible to it and
  everything that suite covers reports as `NO_COVERAGE` — a report full of phantoms rather than
  gaps. Scoped to `test` alone the guava adapter scored 85% with 17 "uncovered" cache methods;
  adding `compatibilityTest` gave 100%. The `pitest` block must also sit **after**
  `testing.suites` in the build file, or the source set does not exist yet
- **jcache is deliberately not wired up.** Its TCK sets `testClassesDirs` to a jar unzipped into
  `build/tck` rather than to a source set's output, so `testSourceSets` has nothing to point at
  and the TCK's ~493 tests cannot run under PIT: a run without them scores a misleading 77% (no
  kill is attributed to `org.jsr107.tck.*`), and roughly a quarter of the survivors are the
  statistics and JMX calls that are best-effort by policy. It also needs `skipFailingTests`,
  since a few tests reach unknown-enum branches through Mockito's static mocking, which fights
  PIT's agent. If jcache signal is ever wanted, scope `targetClasses` to the classes the unit
  suite owns outright (`CaffeineConfiguration`, `Expirable`, `EntryProcessorEntry`,
  `TypesafeConfigurator`), where the absent TCK does not distort the result
- Runtime: ~30-60 minutes. Use for ad-hoc runs, not CI
- Concurrency bugs aren't caught by mutation testing — rely on Fray/LinCheck/JCStress for that
