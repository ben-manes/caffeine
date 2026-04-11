# Testing Conventions

- Run individual tests with `--tests`, never the full suite during development
- Tests use JUnit Jupiter with Truth assertions and Awaitility for async
- Test classes are parameterized via `@CacheSpec` + `CacheProvider` — use `-P` flags to filter
- New tests should follow the `@CacheSpec` parameterization pattern, not create caches manually
- Use `CacheContext` for test utilities: `context.ticker()`, `context.absentKey()`, etc.
- GC-dependent tests are inherently flaky — use `GcFinalization` and `Awaits.awaitFullGc()`
- Notification assertions: cast to `ConsumingRemovalListener`, check `listener.removed()`
- Fray tests use direct thread creation with `@FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)`, no parameterization
- Tests: `caffeine/src/test/java/`, fixtures: `testFixtures/`
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
  `InternerTest`

### Regressions and stress
- Issue-specific regression tests live under `issues/` (e.g., `Issue568Test`) — search
  there when touching code named in a historical GitHub issue
- Cross-feature stress — `MultiThreadedTest`
- Concurrency interleavings — specialized suites (`frayTest`, `lincheckTest`, `jcstress`)

### Narrowing parameterized runs
When running a parameterized test class, combine `--tests` with `-P` flags
(e.g., `-Pcompute=sync -Pkeys=strong -Pvalues=strong -Pstats=disabled`) to avoid
exploding the Cartesian product.

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
  AbstractLinkedDeque, Interner, Async, Scheduler, Caffeine (builder), CaffeineSpec (parser)
- `BoundedLocalCache` and `UnboundedLocalCache` are NOT in scope — the `@CacheSpec`
  parameterized test suite makes PIT's main process OOM during coverage collection,
  regardless of heap size (`mainProcessJvmArgs` doesn't effectively bump the forked JVM).
  Line coverage on those classes is already 100% via JaCoCo, and concurrency bugs aren't
  caught by mutation testing anyway
- Runtime: ~30-60 minutes. Use for ad-hoc runs, not CI
- Concurrency bugs aren't caught by mutation testing — rely on Fray/LinCheck/JCStress for that
