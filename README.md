[![Build Status](https://travis-ci.org/ben-manes/caffeine.svg)](https://travis-ci.org/ben-manes/caffeine)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine)
[![JavaDoc](https://img.shields.io/badge/javadoc-1.2.0-brightgreen.svg)](http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine)
[![License](http://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

Caffeine is a [high performance][1] caching library based on Java 8. For more details, see our
[users' guide][2] and browse the [API docs][3] for the the latest release.

### Cache

Caffeine provides an in-memory cache using a Google Guava inspired API. The [improvements][1] draw
on our experience designing [Guava's cache][4] and [ConcurrentLinkedHashMap][5].

```java
LoadingCache<Key, Graph> graphs = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterAccess(5, TimeUnit.MINUTES)
    .refreshAfterWrite(1, TimeUnit.MINUTES)
    .build(key -> createExpensiveGraph(key));
```

#### Features at a Glance

Caffeine provide flexible construction to create a cache with any combination of the following
features:

 * [automatic loading of entries][6] into the cache, optionally asynchronously
 * [least-recently-used eviction][7] when a maximum size is exceeded
 * [time-based expiration][8] of entries, measured since last access or last write
 * keys automatically wrapped in [weak references][9]
 * values automatically wrapped in [weak or soft references][9]
 * [notification][10] of evicted (or otherwise removed) entries
 * accumulation of cache access [statistics][11]

In addition, Caffeine offers the following extensions:
 * [tracing][12]
 * [simulation][13]
 * [Guava adapters][14]
 * [JSR-107 JCache][15]

### Download

Download [the latest .jar][16] from [Maven Central][17] or depend via Gradle:

```gradle
compile 'com.github.ben-manes.caffeine:caffeine:1.2.0'

// Optional extensions
compile 'com.github.ben-manes.caffeine:guava:1.2.0'
compile 'com.github.ben-manes.caffeine:jcache:1.2.0'
compile 'com.github.ben-manes.caffeine:tracing-async:1.2.0'
```

Snapshots of the development version are available in 
[Sonatype's snapshots repository](https://oss.sonatype.org/content/repositories/snapshots).

[1]: https://github.com/ben-manes/caffeine/wiki/Benchmarks
[2]: https://github.com/ben-manes/caffeine/wiki
[3]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine
[4]: https://code.google.com/p/guava-libraries/wiki/CachesExplained
[5]: https://code.google.com/p/concurrentlinkedhashmap
[6]: https://github.com/ben-manes/caffeine/wiki/Population
[7]: https://github.com/ben-manes/caffeine/wiki/Eviction#size-based
[8]: https://github.com/ben-manes/caffeine/wiki/Eviction#time-based
[9]: https://github.com/ben-manes/caffeine/wiki/Eviction#reference-based
[10]: https://github.com/ben-manes/caffeine/wiki/Removal
[11]: https://github.com/ben-manes/caffeine/wiki/Statistics
[12]: https://github.com/ben-manes/caffeine/wiki/Tracing
[13]: https://github.com/ben-manes/caffeine/wiki/Simulator
[14]: https://github.com/ben-manes/caffeine/wiki/Guava
[15]: https://github.com/ben-manes/caffeine/wiki/JCache
[16]: https://search.maven.org/remote_content?g=com.github.ben-manes.caffeine&a=caffeine&v=LATEST
[17]: https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine
