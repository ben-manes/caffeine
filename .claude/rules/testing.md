# Testing Conventions

- Run individual tests with `--tests`, never the full suite during development
- Tests use JUnit 5 (Jupiter) with Truth assertions and Awaitility for async
- Test classes are parameterized via `@CacheSpec` + `CacheProvider` — use `-P` flags to filter
- New tests should follow the `@CacheSpec` parameterization pattern, not create caches manually
- Use `CacheContext` for test utilities: `context.ticker()`, `context.absentKey()`, etc.
- GC-dependent tests are inherently flaky — use `GcFinalization` and `Awaits.awaitFullGc()`
- Notification assertions: cast to `ConsumingRemovalListener`, check `listener.removed()`
- Fray tests use direct thread creation with `@FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)`, no parameterization
- Tests: `caffeine/src/test/java/`, fixtures: `testFixtures/`
- For full test infrastructure details, see `.claude/docs/testing.md`
