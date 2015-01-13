[![Build Status](https://travis-ci.org/ben-manes/caffeine.svg)](https://travis-ci.org/ben-manes/caffeine)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Stories in Ready](https://badge.waffle.io/ben-manes/caffeine.png?label=ready&title=Ready)](https://waffle.io/ben-manes/caffeine)
[![License](http://img.shields.io/:license-apache-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

# Caffeine

Concurrent data-structures for Java 8.

This project is in **early development** and released under the
[Apache License](http://www.apache.org/licenses/LICENSE-2.0).

## Collections

#### [EliminationStack](caffeine/src/main/java/com/github/benmanes/caffeine/EliminationStack.java)
A lock-free stack that employs an elimination backoff arena to cancel operations with reverse
semantics.

#### [SingleConsumerQueue](caffeine/src/main/java/com/github/benmanes/caffeine/SingleConsumerQueue.java)
A lock-free queue that supports concurrent producers and is restricted to a single consumer. This
implementation employs a combining backoff arena, the inverse of elimination, to reduce contention
caused by multiple producers.

## Caching

#### In-Memory
A high-performance cache with a Guava inspired API. This implementation draws on the author's 
experience designing [ConcurrentLinkedHashMap](https://code.google.com/p/concurrentlinkedhashmap/)
and co-authoring [Guava's Cache](https://code.google.com/p/guava-libraries/wiki/CachesExplained).
The API is extended to include an asynchronous cache and to expose low-level options like changing
the maximum size, expiration timeouts, and traversing in retention order. To simplify integration,
a Guava compatible adapter is available (see [migration guide](MIGRATING.md)).

| Interface | Description |
| --------- | ----------- |
| [Caffeine](caffeine/src/main/java/com/github/benmanes/caffeine/cache/Caffeine.java) | Builds cache instances based on a wide range of features |
| [Cache](caffeine/src/main/java/com/github/benmanes/caffeine/cache/Cache.java) | Synchronous cache that allows entries to be manually added |
| [LoadingCache](caffeine/src/main/java/com/github/benmanes/caffeine/cache/LoadingCache.java) | Synchronous cache that automatically loads entries when not present |
| [AsyncLoadingCache](caffeine/src/main/java/com/github/benmanes/caffeine/cache/AsyncLoadingCache.java) |Asynchronous cache that automatically loads entries when not present and returns a [CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html) |
| [CacheLoader](caffeine/src/main/java/com/github/benmanes/caffeine/cache/CacheLoader.java) | Computes or retrieves values, based on a key, for use in populating a cache |
| [Weigher](caffeine/src/main/java/com/github/benmanes/caffeine/cache/Weigher.java) | Calculates the size of entries relative to each other; used if a maximum weight is specified |
| [RemovalListener](caffeine/src/main/java/com/github/benmanes/caffeine/cache/RemovalListener.java) | Listens for notifications when an entry is removed from a cache |
| [CacheStats](caffeine/src/main/java/com/github/benmanes/caffeine/cache/stats/CacheStats.java) | Statistics about the performance of a cache |
| [Policy](caffeine/src/main/java/com/github/benmanes/caffeine/cache/Policy.java) | Access to inspect and perform low-level cache operations based on its constructed configuration |
| [CaffeinatedGuava](guava/src/main/java/com/github/benmanes/caffeine/guava/CaffeinatedGuava.java) | An adapter that exposes a Caffeine cache through Guava facades |

#### Tracing and Simulator
A lightweight cache tracing API can be enabled to capture information on how well an application
utilizes its caches. Typically caches are either too small due to statistics not being monitored, or
too large due to oversizing to increase the hit rate. Running the simulator on traced data enables
adjusting the cache size based on both the hit rate and active content ratio.

The simulator includes a family of eviction policies and distribution generators. As each policy is
a decision of trade-offs, the simulator allows developers to determine which policies are best for
their usage scenarios. A general purpose cache, like the one provided by this project, should
evaluate policies that improve upon LRU. Specialized application-specific caches, such as off-heap,
can utilize this infrastructure as well.

## Development Notes
To get started, [sign the Contributor License Agreement](https://www.clahub.com/agreements/ben-manes/caffeine).

#### Java Microbenchmark Harness
[JMH](https://github.com/melix/jmh-gradle-plugin) benchmarks can be run using

```gradle
gradlew jmh -PincludePattern=[class-name pattern]
```

#### Java Object Layout
[JOL](http://openjdk.java.net/projects/code-tools/jol) inspectors can be run using

```gradle
gradlew [object-layout task] -PclassName=[class-name]
```

For convenience, the project's package is prepended to the supplied class name.

#### Static analysis
Static code analysis tasks are not enabled by default and can be run using

```gradle
gradlew clean build -Dcheckstyle -Dfindbugs -Dpmd
```

#### Parameterized testing

Cache unit tests can opt into being run against all cache configurations that meet a specification
constraint. A test method annotated with a configured `@CacheSpec` and using the `CacheProvider`
will be executed with all possible combinations. The test case can inspect the execution
configuration by accepting the `CacheContext` as a parameter.

Parameterized tests can take advantage of automatic validation of the cache's internal data
structures to detect corruption. The `CacheValidationListener` is run after a successful test case
and if an error is detected then the test is set with the failure information.

```java
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CacheTest {

  @CacheSpec(
    keys = { ReferenceType.STRONG, ReferenceType.WEAK },
    values = { ReferenceType.STRONG, ReferenceType.WEAK, ReferenceType.SOFT },
    maximumSize = { MaximumSize.DISABLED, MaximumSize.FULL, MaximumSize.UNREACHABLE })
  @Test(dataProvider = "caches")
  public void getIfPresent_notFound(
      Cache<Integer, Integer> cache, CacheContext context) {
    // This test is run against 72 different cache configurations
    // (2 key types) * (3 value types) * (3 max sizes) * (4 population modes)
    assertThat(cache.getIfPresent(context.getAbsentKey()), is(nullValue());
    assertThat(cache.stats(), both(hasMissCount(1)).and(hasHitCount(0)));
  }
}
```

#### A special thanks to...
![YourKit](http://www.yourkit.com/images/yklogo.png)

[YourKit](http://www.yourkit.com) supports open source projects with its full-featured Java Profiler.
