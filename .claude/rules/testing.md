# Testing Conventions

- Run a single test method with `--tests 'Class.method'` — fine even if it sweeps the full `@CacheSpec` matrix. Don't run a whole `@CacheSpec` class or the full suite locally; avoid them, or narrow with `-P` flags when you must (CI runs the full matrix, sharded across 40 workers)
- Tests use JUnit Jupiter with Truth assertions and Awaitility for async
- Test classes are parameterized via `@CacheSpec` + `CacheProvider` — use `-P` flags to filter
- New tests should follow the `@CacheSpec` parameterization pattern, not create caches manually
- Use `CacheContext` for test utilities: `context.ticker()`, `context.absentKey()`, etc.
- GC-dependent tests are inherently flaky — use `GcFinalization` and `Awaits.awaitFullGc()`
- A value the test itself creates (a loader returning `key.negate()`, a compute remapping) must
  be wrapped in `CacheContext.intern(...)` under `@CheckNoEvictions`, or the weak/soft cells
  can collect it between the test body and the teardown check and report it as a `COLLECTED`
  eviction. Values from `context.absentValue()` and `context.original()` are already pinned
- Notification assertions: cast to `ConsumingRemovalListener`, check `listener.removed()`
- Fray tests use direct thread creation with `@FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)`, no parameterization
- Tests: `caffeine/src/test/java/`, fixtures: `testFixtures/`
- `examples/` hold the same quality bar as the library: their tests must cover
  unhappy paths (duplicate keys, a failing write/load, empty batches) —
  happy-path-only tests are where example bugs have hidden
- **Pin a user-visible contract at the API level, in the test class that owns it.** A
  `Policy`/`Cache`/`asMap` behaviour belongs in `ExpireAfterVarTest`, `CacheTest`, `EvictionTest`
  and so on, written as the user would hit it. A white-box pin in the data structure's own test
  (`TimerWheelTest`, `BoundedBufferTest`) asserts the mechanism but shows nobody what the change
  buys, and it is the wrong artifact to hand to a reviewer. Add the internal pin only for a case
  the API cannot reach, and say which case that is.
- **Match the conventions of the file you are editing.** Reuse its `@MethodSource` shape, its
  fixtures, and its naming rather than importing a different style (a static direction table, a
  `named(...)` `Consumer` source) that the file does not already use. A reviewer who has to
  reverse-engineer a test, or ablate production code to find out what it pins, has been handed
  work rather than evidence.
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
- Climber behavioral pins — `WindowClimberGateTest` (a deterministic four-cell subset of the
  `/climber-gate` battery, run in the standard suite) and `WindowClimberFuzzer`. Bars, the
  pitest separation, and the fuzzer's oracle contract are in `.claude/docs/testing.md`
  §*Window Climber Test Policy*. Read it before touching any of them.
- `WindowClimber` state or schedule changes must ALSO run the `WindowClimberFuzzer` fuzz
  target (`:caffeine:fuzzTest`); a class-scoped run never touches the `fuzzTest` source set.
  New climber state must arrive with its invariants in `ClimberInvariants`, or the fuzz run
  proves nothing.
- Behavioral regret (does the climber close the gap on a workload) is not the unit suite's job:
  `/climber-gate` holds the known traps and `/audit-regret` searches for new ones, both in the
  simulator against `product.Caffeine`

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

## Fuzz Testing and PIT

Jazzer's one-`@FuzzTest`-per-JVM constraint, each fuzzer's selector pattern, and PIT's
target and `testSourceSets` scoping are in `.claude/docs/testing.md` §*Fuzz Testing (Jazzer)*
and §*PIT Mutation Testing*. Two traps worth carrying here:

- **Take a fuzzer's selector pattern from `.github/workflows/build.yml`, don't guess it.**
  Guessing a bare name for a nested holder class selects **zero tests and still reports BUILD
  SUCCESSFUL**; confirm the result XML's `tests=` count either way.
- PIT's `testSourceSets` must name every suite that covers the target, or everything that
  suite covers reports as `NO_COVERAGE`, which is a report full of phantoms rather than gaps.

## Full Details

For test infrastructure (CacheSpec internals, Truth subjects, race-testing patterns, Fray,
coverage) see `.claude/docs/testing.md`.
