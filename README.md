[![Build Status](https://travis-ci.org/ben-manes/caffeine.svg)](https://travis-ci.org/ben-manes/caffeine)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine)
[![JavaDoc](https://img.shields.io/badge/javadoc-1.2.0-brightgreen.svg)](http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine)
[![License](http://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

Caffeine is a Java 8 based concurrency library that provides specialized data structures, such as a
[high performance cache][1]. Please see our [documentation][2] for more details.

```gradle
compile 'com.github.ben-manes.caffeine:caffeine:1.2.0'

// Optional extensions
compile 'com.github.ben-manes.caffeine:guava:1.2.0'
compile 'com.github.ben-manes.caffeine:jcache:1.2.0'
compile 'com.github.ben-manes.caffeine:tracing-async:1.2.0'
```

Snapshots of the development version are available in 
[Sonatype's snapshots repository](https://oss.sonatype.org/content/repositories/snapshots).

### Cache: Features at a Glance

 * automatic loading of entries into the cache, optionally asynchronously
 * least-recently-used eviction when a maximum size is exceeded
 * time-based expiration of entries, measured since last access or last write
 * keys automatically wrapped in weak references
 * values automatically wrapped in weak or soft references
 * notification of evicted (or otherwise removed) entries
 * accumulation of cache access statistics

In addition, Caffeine offers the following extensions:
 * [tracing][4] ([api javadoc][5], [async javadoc][6])
 * [simulation][7] ([javadoc][8])
 * [JSR-107 JCache][9] ([javadoc][10])
 * [Guava][11] adapters ([javadoc][12])

[1]: https://github.com/ben-manes/caffeine/wiki/Benchmarks
[2]: https://github.com/ben-manes/caffeine/wiki
[3]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine
[4]: https://github.com/ben-manes/caffeine/wiki/Tracing
[5]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/tracing-api
[6]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/tracing-async
[7]: https://github.com/ben-manes/caffeine/wiki/Simulator
[8]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/simulator
[9]: https://github.com/ben-manes/caffeine/wiki/JCache
[10]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/jcache
[11]: https://github.com/ben-manes/caffeine/wiki/Guava
[12]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/guava
