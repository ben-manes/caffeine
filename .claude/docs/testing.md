# Test Infrastructure Guide

## Parameterized Test System

Tests use a custom JUnit Jupiter parameterization framework built on `@CacheSpec`.

### Writing a test

```java
@ParameterizedTest
@CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
void myTest(Cache<Int, Int> cache, CacheContext context) {
  // cache is pre-populated per @CacheSpec
  // context holds configuration + test utilities
  assertThat(cache).hasSize(context.original().size());
}
```

### How it works

1. **`@CacheSpec`** declares test dimensions as enum values. Each dimension
   (implementation, compute, keys, values, maximum, expiry, loader, etc.)
   has a set of enum variants.

2. **`CacheGenerator`** computes the Cartesian product of all declared enum
   values, filtered by compatibility rules.

3. **`CacheProvider`** (implements `ArgumentsProvider`) feeds combinations to
   JUnit. It inspects the test method's parameter types to filter further —
   a method taking `AsyncCache` won't receive sync-only configurations.

4. **`CaffeineCacheFromContext`** constructs a `Caffeine` builder from each
   `CacheContext` configuration, populates it, and passes it to the test.

5. **`CacheValidationInterceptor`** runs post-test validation on every test
   via JUnit's `InvocationInterceptor`:
   - Validates internal cache structure (`CacheSubject.isValid()`)
   - Checks executor completion
   - Verifies statistics consistency
   - Validates removal/eviction notifications

### Key classes

| Class | Location | Purpose |
|-------|----------|---------|
| `CacheSpec` | testFixtures | Annotation declaring test dimensions |
| `CacheProvider` | testFixtures | JUnit ArgumentsProvider |
| `CacheGenerator` | testFixtures | Cartesian product + filtering |
| `CacheContext` | testFixtures | Configuration holder + utilities |
| `CaffeineCacheFromContext` | testFixtures | Context → Caffeine builder factory |
| `CacheValidationInterceptor` | testFixtures | Post-test validation |
| `Options` | testFixtures | `-P` system property filters |

### CacheContext utilities

- `context.ticker()` — FakeTicker for time control (`ticker.advance(Duration)`)
- `context.original()` — pre-populated entries (insertion order)
- `context.absentKey()` / `absentValue()` — guaranteed-absent values
- `context.firstKey()`, `middleKey()`, `lastKey()` — keys from population
- `context.intern(T)` — thread-local interning for weak/soft reference tests
- `context.evictionListener()` — cast to `ConsumingRemovalListener` for assertions

### Population sizes

- `EMPTY` = 0, `SINGLETON` = 1, `PARTIAL` = 25, `FULL` = 50
- Pre-populated caches never exceed max size (no evictions during setup)

### Filtering with -P flags

```bash
-Pimplementation=caffeine  # caffeine or guava
-Pkeys=strong              # strong or weak
-Pvalues=strong            # strong, weak, or soft
-Pcompute=sync             # sync or async
-Pstats=enabled            # enabled or disabled
```

Processed by `Options.java`. Case-insensitive enum matching.

### Sharding

```bash
-PshardCount=40 -PshardIndex=0
```

`ShardedTestFilter` partitions at method granularity (FarmHash) for balanced CI.

## Assertion Patterns

### Truth subjects

```java
import static com.github.benmanes.caffeine.testing.CacheSubject.assertThat;

assertThat(cache).isEmpty();
assertThat(cache).hasSize(5);
assertThat(cache).containsKey(key);
assertThat(cache).containsExactlyKeys(list);
```

Custom subjects: `CacheSubject`, `AsyncCacheSubject`, `CacheContextSubject`,
`MapSubject`, `CollectionSubject`, `FutureSubject`.

### Removal listener verification

```java
var listener = (ConsumingRemovalListener<Int, Int>) context.evictionListener();
// ... cache operation ...
assertThat(listener.removed()).containsExactly(
    RemovalNotification.create(key, value, RemovalCause.EXPIRED));
```

### Async coordination

```java
import static com.github.benmanes.caffeine.testing.Awaits.await;

await().until(() -> cache.estimatedSize() == expected);
await().until(() -> executor.submitted() == executor.completed());
```

### GC testing

```java
var ref = new SoftReference<>(target);
GcFinalization.awaitClear(ref);  // Forces GC + reference processing
```

Use `Awaits.awaitFullGc()` for full GC with cleaner registration.
Inherently flaky — use `GcFinalization` utilities, not `System.gc()`.

## Race Testing Patterns (BoundedLocalCacheTest)

These patterns force specific interleavings without Fray. Used for white-box
testing of internal paths.

### Forcing the slow path (synchronized(node) block)

Hold the node monitor on the main thread, start a writer on another thread,
wait for it to block, then mutate state while it's blocked:

```java
synchronized (node) {
    var future = CompletableFuture.runAsync(() -> {
        cache.asMap().putIfAbsent(key, value);
    });
    await().until(() -> writerThread.getState() == BLOCKED);
    // node state is now accessible while writer is blocked
    node.setAccessTime(now);
}
// writer proceeds after releasing the monitor
```

### Observing retired node state

Hold `evictionLock` to prevent maintenance, run a removal on another thread,
observe the intermediate retired state:

```java
cache.evictionLock.lock();
try {
    CompletableFuture.supplyAsync(() -> cache.remove(key)).join();
    assertThat(node.isRetired()).isTrue();
    cache.cleanUp();
    assertThat(node.isDead()).isTrue();
} finally {
    cache.evictionLock.unlock();
}
```

### Thread state polling for race coordination

Use Awaitility + `Thread.getState()` instead of latches:

```java
await().until(() -> {
    var thread = writer.get();
    return thread != null && EnumSet.of(BLOCKED, WAITING).contains(thread.getState());
});
```

### State reset after intentional corruption

When tests corrupt internal state (retiring nodes, clearing references),
always reset to prevent `CacheValidationInterceptor` failures:

```java
// after intentionally corrupting state
requireNonNull(context.build(key -> key));
```

### Accessing internals

Cast to `BoundedLocalCache` for white-box testing:

```java
var localCache = asBoundedLocalCache(cache);  // private helper
```

## Issue Regression Test Patterns

Tests in `issues/` use different interleaving techniques:

- **Issue568Test** (value visibility): 10 threads x 1000 iterations with random
  50% put/get probability. Detects stale null reads via brute-force contention.
- **Issue412Test** (incorrect cause): Zipfian key distribution + nanos-level TTL
  to maximize expiration-vs-invalidation race window.
- **Issue859Test** (liveness): 100K iterations with real `Scheduler` (wall-clock,
  not FakeTicker) to test that maintenance scheduling is not lost.
- **Solr10141Test** (eviction+update race): 64 threads x 156K reads each with
  `AtomicBoolean` per-value liveness flag to detect double-removal.

## Choosing the Dynamic Tool for an Escalated Finding

Static analysis escalates interleavings it cannot adjudicate. Pick the tool by
what the hypothesis needs, then port the skeleton into the source tree and run
it — an unported skeleton is a TODO, not a resolution.

| Hypothesis | Tool | Why |
|---|---|---|
| Interleaving of operations at synchronization points | Fray (`caffeine/src/frayTest/`) | Systematic scheduler exploration; cannot see plain-field data races (sync-point granularity) |
| Plain/opaque-field data race, SC-reachable | LinCheck model checking (`caffeine/src/lincheckTest/`) | Field-access granularity catches races Fray cannot |
| Weak-memory publication/reordering (store-buffer etc.) | jcstress (`caffeine/src/jcstress/`) | The only tool that exercises the hardware memory model |

jcstress caveat: model the real structure, not an isolated primitive — isolated
shapes over-state reachability (a single-slot repro can be unreachable once the
real ring buffer's wrap gap is modeled). A reproduced result on a simplified
shape justifies a fix only if the real structure also reproduces or the fix is
free. For suspected JIT-dependent interleavings a single run is not evidence of
absence — vary seeds across many short launches instead of one long run.

## Fray Concurrency Tests

Location: `caffeine/src/frayTest/`

```java
@ExtendWith(FrayTestExtension.class)
final class EvictionFrayTest {
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void concurrentPutAndEvict() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .maximumSize(1).executor(Runnable::run).build();
    var t1 = new Thread(() -> cache.put(1, 1));
    var t2 = new Thread(() -> cache.put(2, 2));
    t1.start(); t2.start();
    t1.join(); t2.join();
    cache.cleanUp();
    assertThat(cache).isValid(); // CacheSubject / AsyncCacheSubject deep oracle
  }
}
```

- 10,000 iterations with systematic interleaving exploration
- Direct thread creation (no parameterization framework)
- `executor(Runnable::run)` for inline execution
- The `frayTest` source set has no `CacheValidationInterceptor`, so each test must
  call `assertThat(cache).isValid()` itself (the suite depends on `testFixtures`;
  use `AsyncCacheSubject.assertThat` for async caches). This is the deep oracle —
  it drains to quiescence and checks deque↔data consistency, the telescoping weight
  sum, and node lifecycle.
- Do NOT use `estimatedSize() == asMap().size()` as the consistency oracle: both
  read the same `ConcurrentHashMap` (`data.mappingCount()` vs `data.size()`), so the
  assertion is a tautology that cannot detect a node stranded or duplicated in a
  deque. Keep specific assertions (exact counts, weight bounds, values) alongside
  `isValid()`.

## Test Utilities

- **`TrackingExecutor`** — wraps executor with `pause()`/`resume()` gate
  and `submitted()`/`completed()`/`failed()` counters
- **`ConcurrentTestHarness.timeTasks(nThreads, task)`** — synchronized start
  via CountDownLatch
- **`FakeTicker`** (Guava) — `advance(Duration)` for time control
- **Validation annotations**: `@CheckNoEvictions`, `@CheckMaxLogLevel(WARN)`,
  `@CheckNoStats`

## Coverage (JaCoCo)

The aggregate `jacocoFullReport` merges every `**/*<module>*/**/jacoco/*.exec` across
`:caffeine`, `:guava`, `:jcache` (main source sets only — not simulator/examples). Target is
100% line and branch, with one sanctioned exception: `StripedBuffer.expandOrRetry`'s spin-lock
retry, which no test can deterministically drive.

To re-measure a specific class without the full CI matrix, run the covering test class(es) then
that module's report, e.g. `:jcache:test --tests 'CacheLoaderTest' :jcache:jacocoTestReport`,
and read `<module>/build/reports/jacoco/test/jacocoTestReport.xml` (per-line `mi`/`ci`/`mb`/`cb`
counters). The merged CI HTML can show *internally inconsistent* line vs. branch markers when
shards drift — trust a fresh local run over a stale merged report.

### `throw alwaysThrows()` is uncoverable — a helper that never returns

A call site like `throw processorFailure(e)`, where the helper *always* throws (every path ends
in `throw`), reads as **fully uncovered** even when a test drives it. JaCoCo infers a line's
coverage from a probe placed *after* it; the `invokestatic` unwinds before that probe fires, so
the call site's instructions never count — and paradoxically the always-throwing helper itself
shows covered while its only callers show `mi>0, ci=0`. That impossible combination (a covered
callee with uncovered call sites) is the tell.

Fix by making the helper **return** the exception for the caller to throw (rethrowing only the
one case it must, e.g. `Error`), so `throw helper(e)` becomes a direct throw JaCoCo can probe:

```java
private static RuntimeException processorFailure(Throwable e) {
  if (e instanceof Error) {
    throw (Error) e;                        // must rethrow: can't return an Error as RuntimeException
  } else if (e instanceof EntryProcessorException) {
    return (EntryProcessorException) e;     // return, don't throw
  }
  return new EntryProcessorException(e);
}
```

Watch for this whenever an audit fix replaces direct `throw`s with a throw-helper — the behavior
is identical but the coverage silently drops. (A separate way audit fixes shed coverage: an added
upstream short-circuit makes a downstream guard dead — e.g. `remap` skipping a vanished key before
the `replaceAll` lambda runs left that lambda's `oldValue == null` branch unreachable. There the
fix is to delete the now-dead branch, not to test it.)
