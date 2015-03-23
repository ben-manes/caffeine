[![Build Status](https://travis-ci.org/ben-manes/caffeine.svg)](https://travis-ci.org/ben-manes/caffeine)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Stories in Ready](https://badge.waffle.io/ben-manes/caffeine.png?label=ready&title=Ready)](https://waffle.io/ben-manes/caffeine)
[![License](http://img.shields.io/:license-apache-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

Caffeine is a Java 8 based concurrency library that provides specialized data structures, such as a
[high performance cache][1]. Please see our [documentation][2] for more details.

The core is stable, feature complete, and well tested. We expect to release early next week.

```gradle
compile 'com.github.ben-manes.caffeine:caffeine:1.0.0-SNAPSHOT'

// Optional extensions
compile 'com.github.ben-manes.caffeine:guava:1.0.0-SNAPSHOT'
compile 'com.github.ben-manes.caffeine:jcache:1.0.0-SNAPSHOT'
compile 'com.github.ben-manes.caffeine:tracing-async:1.0.0-SNAPSHOT'
```

Snapshots of the development version is available in 
[Sonatype's snapshots repository](https://oss.sonatype.org/content/repositories/snapshots/).

### Cache: Features at a Glance

 * automatic loading of entries into the cache, optionally asynchronously
 * least-recently-used eviction when a maximum size is exceeded
 * time-based expiration of entries, measured since last access or last write
 * keys automatically wrapped in weak references
 * values automatically wrapped in weak or soft references
 * notification of evicted (or otherwise removed) entries
 * accumulation of cache access statistics

In addition Caffeine offers extensions for [tracing][3], [simulation][4], [JSR-107 JCache][5],
and [Guava][6] adapters.

[1]: https://github.com/ben-manes/caffeine/wiki/Benchmarks
[2]: https://github.com/ben-manes/caffeine/wiki
[3]: https://github.com/ben-manes/caffeine/wiki/Tracing
[4]: https://github.com/ben-manes/caffeine/wiki/Simulator
[5]: https://github.com/ben-manes/caffeine/wiki/JCache
[6]: https://github.com/ben-manes/caffeine/wiki/Guava
