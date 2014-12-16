[![Build Status](https://travis-ci.org/ben-manes/caffeine.svg)](https://travis-ci.org/ben-manes/caffeine)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![License](http://img.shields.io/:license-apache-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

# Caffeine

Concurrent data-structures for Java 8.

This project is in early development and released under the
[Apache License](http://www.apache.org/licenses/LICENSE-2.0).

## Collections

#### [EliminationStack](https://github.com/ben-manes/caffeine/blob/master/src/main/java/com/github/benmanes/caffeine/EliminationStack.java)
A lock-free stack that employs an elimination backoff arena to cancel operations with reverse
semantics.

#### [SingleConsumerQueue](https://github.com/ben-manes/caffeine/blob/master/src/main/java/com/github/benmanes/caffeine/SingleConsumerQueue.java)
A lock-free queue that supports concurrent producers and is restricted to a single consumer. This
implementation employs a combining backoff arena, the inverse of elimination, to reduce contention
caused by multiple producers.

## Caching

#### In-Memory (aka on-heap)
A high-performance cache that is API compatible with Guava. This implementation draws on the
author's experience designing [ConcurrentLinkedHashMap](https://code.google.com/p/concurrentlinkedhashmap/)
and co-authoring [Guava's Cache](https://code.google.com/p/guava-libraries/wiki/CachesExplained).
The API is extended to include an asynchronous `CompletableFuture` interface and to expose low-level
options like changing the maximum size, expiration timeouts, and traversing in retention order. 

#### Tracing and Simulator
A lightweight cache tracing api can be enabled to capture information on how well an application's
utilizes its caches. Typically caches are either too small due to statistics not being monitored, or
too large due to over sizing to increase the hit rate. Running the simulator on traced data enables
adjusting the cache size based on both the hit rate and active-content-ratio.

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
