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
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }
}
```

- 10,000 iterations with systematic interleaving exploration
- Direct thread creation (no parameterization framework)
- `executor(Runnable::run)` for inline execution
- Always validate `estimatedSize() == asMap().size()` for consistency

## Test Utilities

- **`TrackingExecutor`** — wraps executor with `pause()`/`resume()` gate
  and `submitted()`/`completed()`/`failed()` counters
- **`ConcurrentTestHarness.timeTasks(nThreads, task)`** — synchronized start
  via CountDownLatch
- **`FakeTicker`** (Guava) — `advance(Duration)` for time control
- **Validation annotations**: `@CheckNoEvictions`, `@CheckMaxLogLevel(WARN)`,
  `@CheckNoStats`
