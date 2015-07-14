[![Build Status](https://travis-ci.org/ben-manes/caffeine.svg)](https://travis-ci.org/ben-manes/caffeine)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine)
[![JavaDoc](https://img.shields.io/badge/javadoc-1.3.1-brightgreen.svg)](http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine)
[![License](http://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

Caffeine is a [high performance][benchmarks] caching library based on Java 8. For more
details, see our [users' guide][users-guide] and browse the [API docs][javadoc] for
the latest release.

### Cache

Caffeine provides an in-memory cache using a Google Guava inspired API. The
[improvements][benchmarks] draw on our experience designing [Guava's cache][guava-cache]
and [ConcurrentLinkedHashMap][clhm].

```java
LoadingCache<Key, Graph> graphs = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterAccess(5, TimeUnit.MINUTES)
    .refreshAfterWrite(1, TimeUnit.MINUTES)
    .build(key -> createExpensiveGraph(key));
```

#### Features at a Glance

Caffeine provide flexible construction to create a cache with a combination of the following features:

 * [automatic loading of entries][population] into the cache, optionally asynchronously
 * [least-recently-used eviction][size] when a maximum size is exceeded
 * [time-based expiration][time] of entries, measured since last access or last write
 * keys automatically wrapped in [weak references][reference]
 * values automatically wrapped in [weak or soft references][reference]
 * [notification][listener] of evicted (or otherwise removed) entries
 * [writes propagated][writer] to an external resource
 * accumulation of cache access [statistics][statistics]

In addition, Caffeine offers the following extensions:
 * [tracing][tracing]
 * [simulation][simulator]
 * [Guava adapters][guava-adapter]
 * [JSR-107 JCache][jsr107]

### Download

Download from [Maven Central][maven] or depend via Gradle:

```gradle
compile 'com.github.ben-manes.caffeine:caffeine:1.3.1'

// Optional extensions
compile 'com.github.ben-manes.caffeine:guava:1.3.1'
compile 'com.github.ben-manes.caffeine:jcache:1.3.1'
compile 'com.github.ben-manes.caffeine:tracing-async:1.3.1'
```

Snapshots of the development version are available in
[Sonatype's snapshots repository](https://oss.sonatype.org/content/repositories/snapshots).

[benchmarks]: https://github.com/ben-manes/caffeine/wiki/Benchmarks
[users-guide]: https://github.com/ben-manes/caffeine/wiki
[javadoc]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine
[guava-cache]: https://code.google.com/p/guava-libraries/wiki/CachesExplained
[clhm]: https://code.google.com/p/concurrentlinkedhashmap
[population]: https://github.com/ben-manes/caffeine/wiki/Population
[size]: https://github.com/ben-manes/caffeine/wiki/Eviction#size-based
[time]: https://github.com/ben-manes/caffeine/wiki/Eviction#time-based
[reference]: https://github.com/ben-manes/caffeine/wiki/Eviction#reference-based
[listener]: https://github.com/ben-manes/caffeine/wiki/Removal
[writer]: https://github.com/ben-manes/caffeine/wiki/Writer
[statistics]: https://github.com/ben-manes/caffeine/wiki/Statistics
[tracing]: https://github.com/ben-manes/caffeine/wiki/Tracing
[simulator]: https://github.com/ben-manes/caffeine/wiki/Simulator
[guava-adapter]: https://github.com/ben-manes/caffeine/wiki/Guava
[jsr107]: https://github.com/ben-manes/caffeine/wiki/JCache
[maven]: https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine
