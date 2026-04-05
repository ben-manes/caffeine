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

## PIT Mutation Testing

- `./gradlew :caffeine:pitest` runs mutation testing. Segmented by scope:
  - `-Ppit=dataStructures` → ~1h: TimerWheel, FrequencySketch, Pacer, BoundedBuffer, StripedBuffer, MpscGrowableArrayQueue, AbstractLinkedDeque, Interner, Async, Scheduler, Caffeine (builder), CaffeineSpec (parser)
  - `-Ppit=cache` → overnight: BoundedLocalCache + UnboundedLocalCache, tested via the full `@CacheSpec` suite (AsMapTest, CacheTest, Eviction/Expire/Refresh tests, etc.)
  - no flag → everything (slowest, overnight)
- `jvmArgs` filter `@CacheSpec` combinations to `implementation=caffeine, keys=strong, values=strong, stats=enabled`. `compute` is intentionally unfiltered to keep async tests
- `skipFailingTests = true` handles tests that explicitly require the opposite on a filtered axis (e.g. `@CacheSpec(stats = DISABLED)`)
- Don't add the inverse of a filter value (e.g. `stats=disabled`) — most tests use the common defaults
- Run overnight, not on CI. Segments produce separate reports in `build/reports/pitest/`; re-run with a different scope to overwrite
- Concurrency bugs aren't caught by mutation testing — rely on Fray/LinCheck/JCStress for that
