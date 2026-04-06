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
