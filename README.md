[![Build Status](https://github.com/ben-manes/caffeine/workflows/build/badge.svg)](https://github.com/ben-manes/caffeine/actions?query=workflow%3Abuild+branch%3Amaster)
[![Test Count](https://gist.githubusercontent.com/ben-manes/c20eb418f0e0bd6dfe1c25beb35faae4/raw/badge.svg)](https://github.com/ben-manes/caffeine/actions?query=workflow%3Abuild+branch%3Amaster)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine)
[![JavaDoc](https://www.javadoc.io/badge/com.github.ben-manes.caffeine/caffeine.svg)](http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine)
[![License](https://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Stack Overflow](https://img.shields.io/:stack%20overflow-caffeine-brightgreen.svg)](http://stackoverflow.com/questions/tagged/caffeine)
<a href="https://github.com/ben-manes/caffeine/wiki">
<img align="right" height="90px" src="https://raw.githubusercontent.com/ben-manes/caffeine/master/wiki/logo.png">
</a>

Caffeine is a [high performance][benchmarks], [near optimal][efficiency] caching library. For more
details, see our [user's guide][users-guide] and browse the [API docs][javadoc] for the latest
release.

### Cache

Caffeine provides an in-memory cache using a Google Guava inspired API. The improvements draw on our
experience designing [Guava's cache][guava-cache] and [ConcurrentLinkedHashMap][clhm].

```java
LoadingCache<Key, Graph> graphs = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .refreshAfterWrite(Duration.ofMinutes(1))
    .build(key -> createExpensiveGraph(key));
```

#### Features at a Glance

Caffeine provides flexible construction to create a cache with a combination of the following
optional features:
 * [automatic loading of entries][population] into the cache, optionally asynchronously
 * [size-based eviction][size] when a maximum is exceeded based on [frequency and recency][efficiency]
 * [time-based expiration][time] of entries, measured since last access or last write
 * [asynchronously refresh][refresh] when the first stale request for an entry occurs
 * keys automatically wrapped in [weak references][reference]
 * values automatically wrapped in [weak or soft references][reference]
 * [notification][listener] of evicted (or otherwise removed) entries
 * [writes propagated][compute] to an external resource
 * accumulation of cache access [statistics][statistics]

In addition, Caffeine offers the following extensions:
 * [JSR-107 JCache][jsr107]
 * [Guava adapters][guava-adapter]
 * [Simulation][simulator]

Use Caffeine in a community provided integration:
 * [Play Framework][play]: High velocity web framework
 * [Micronaut][micronaut]: A modern, full-stack framework
 * [Spring Cache][spring]: As of Spring 4.3 & Boot 1.4
 * [Quarkus][quarkus]: Supersonic Subatomic Java
 * [Scaffeine][scaffeine]: Scala wrapper for Caffeine
 * [ScalaCache][scala-cache]: Simple caching in Scala
 * [Camel][camel]: Routing and mediation engine
 * [JHipster][jhipster]: Generate, develop, deploy
 * [Aedile][aedile]: Kotlin wrapper for Caffeine

Powering infrastructure near you:
 * [Dropwizard][dropwizard]: Ops-friendly, high-performance, RESTful APIs
 * [Cassandra][cassandra]: Manage massive amounts of data, fast
 * [Coherence][coherence]: Mission critical in-memory data grid
 * [Accumulo][accumulo]: A sorted, distributed key/value store
 * [HBase][hbase]: A distributed, scalable, big data store
 * [Apache Solr][solr]: Blazingly fast enterprise search
 * [Infinispan][infinispan]: Distributed in-memory data grid
 * [Redisson][redisson]: Ultra-fast in-memory data grid
 * [OpenWhisk][open-whisk]: Serverless cloud platform
 * [Corfu][corfu]: A cluster consistency platform
 * [Grails][grails]: Groovy-based web framework
 * [Finagle][finagle]: Extensible RPC system
 * [Neo4j][neo4j]: Graphs for Everyone
 * [Druid][druid]: Real-time analytics

### In the News

 * An in-depth description of Caffeine's architecture.
   * [Design of a Modern Cache: part #1][modern-cache-1], [part #2][modern-cache-2]
     ([slides][modern-cache-slides]) at [HighScalability][HighScalability]
 * Caffeine is presented as part of research papers evaluating its novel eviction policy.
   * [TinyLFU: A Highly Efficient Cache Admission Policy][tinylfu]
     by Gil Einziger, Roy Friedman, Ben Manes
   * [Adaptive Software Cache Management][adaptive-tinylfu]
     by Gil Einziger, Ohad Eytan, Roy Friedman, Ben Manes
   * [Lightweight Robust Size Aware Cache Management][size-tinylfu]
     by Gil Einziger, Ohad Eytan, Roy Friedman, Ben Manes

### Download

Download from [Maven Central][maven] or depend via Gradle:

```gradle
implementation("com.github.ben-manes.caffeine:caffeine:3.1.1")

// Optional extensions
implementation("com.github.ben-manes.caffeine:guava:3.1.1")
implementation("com.github.ben-manes.caffeine:jcache:3.1.1")
```

For Java 11 or above, use `3.x` otherwise use `2.x`.

See the [release notes][releases] for details of the changes.

Snapshots of the development version are available in
[Sonatype's snapshots repository][snapshots].

[benchmarks]: https://github.com/ben-manes/caffeine/wiki/Benchmarks
[users-guide]: https://github.com/ben-manes/caffeine/wiki
[javadoc]: http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine
[guava-cache]: https://github.com/google/guava/wiki/CachesExplained
[clhm]: https://github.com/ben-manes/concurrentlinkedhashmap
[population]: https://github.com/ben-manes/caffeine/wiki/Population
[size]: https://github.com/ben-manes/caffeine/wiki/Eviction#size-based
[time]: https://github.com/ben-manes/caffeine/wiki/Eviction#time-based
[refresh]: https://github.com/ben-manes/caffeine/wiki/Refresh
[reference]: https://github.com/ben-manes/caffeine/wiki/Eviction#reference-based
[listener]: https://github.com/ben-manes/caffeine/wiki/Removal
[compute]: https://github.com/ben-manes/caffeine/wiki/Compute
[statistics]: https://github.com/ben-manes/caffeine/wiki/Statistics
[simulator]: https://github.com/ben-manes/caffeine/wiki/Simulator
[guava-adapter]: https://github.com/ben-manes/caffeine/wiki/Guava
[jsr107]: https://github.com/ben-manes/caffeine/wiki/JCache
[maven]: https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine
[releases]: https://github.com/ben-manes/caffeine/releases
[snapshots]: https://oss.sonatype.org/content/repositories/snapshots/com/github/ben-manes/caffeine/
[efficiency]: https://github.com/ben-manes/caffeine/wiki/Efficiency
[tinylfu]: https://dl.acm.org/doi/10.1145/3149371?cid=99659224047
[adaptive-tinylfu]: https://dl.acm.org/doi/10.1145/3274808.3274816?cid=99659224047
[size-tinylfu]: https://dl.acm.org/doi/10.1145/3507920?cid=99659224047
[modern-cache-1]: http://highscalability.com/blog/2016/1/25/design-of-a-modern-cache.html
[modern-cache-2]: http://highscalability.com/blog/2019/2/25/design-of-a-modern-cachepart-deux.html
[modern-cache-slides]: https://docs.google.com/presentation/d/1NlDxyXsUG1qlVHMl4vsUUBQfAJ2c2NsFPNPr2qymIBs
[highscalability]: http://highscalability.com
[spring]: https://docs.spring.io/spring/docs/current/spring-framework-reference/integration.html#cache-store-configuration-caffeine
[scala-cache]: https://github.com/cb372/scalacache
[scaffeine]: https://github.com/blemale/scaffeine
[hbase]: https://hbase.apache.org
[cassandra]: http://cassandra.apache.org
[solr]: https://solr.apache.org/
[infinispan]: https://infinispan.org
[neo4j]: https://github.com/neo4j/neo4j
[finagle]: https://github.com/twitter/finagle
[druid]: https://druid.apache.org/docs/latest/configuration/index.html#cache-configuration
[jhipster]: https://www.jhipster.tech/
[open-whisk]: https://openwhisk.apache.org/
[camel]: https://github.com/apache/camel/blob/master/components/camel-caffeine/src/main/docs/caffeine-cache-component.adoc
[coherence]: https://docs.oracle.com/en/middleware/standalone/coherence/14.1.1.2206/develop-applications/implementing-storage-and-backing-maps.html#GUID-260228C2-371A-4B91-9024-8D6514DD4B78
[corfu]: https://github.com/CorfuDB/CorfuDB
[micronaut]: https://docs.micronaut.io/latest/guide/index.html#caching
[play]: https://www.playframework.com/documentation/latest/JavaCache
[redisson]: https://github.com/redisson/redisson
[accumulo]: https://accumulo.apache.org
[dropwizard]: https://www.dropwizard.io
[grails]: https://grails.org
[quarkus]: https://quarkus.io
[aedile]: https://github.com/sksamuel/aedile
