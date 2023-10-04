# Pipelined Architectures for Latency Aware Caching With Delayed Hits
##### Simulator in caffeine library

[Links to libery caffeine](https://github.com/ben-manes/caffeine/tree/master)


## Abstract 
Modern computer systems use caching to speed up access to data and make better use of resources. These systems, especially in storage and cloud environments, are diverse, with different types of storage, locations, and caches, leading to inconsistent access times. Applications running on these systems also have varying access patterns, like how often they access data or if they do so in bursts. To handle this complexity, cache systems use complex algorithms that aim to balance conflicting priorities. However, these algorithms are hard to expand, debug, and integrate into software.

Our research introduces a flexible architecture that represents complex algorithms as a series of simple policies. This architecture automatically adjusts the different stages of the policy pipeline to maximize the system's performance for the current workload. This means we can add new strategies while preserving the old ones and only use them when they provide value. We've also developed a new cache algorithm called Burst Cache, which can identify and keep items that come in bursts for a long time, even if they aren't recent or frequent. This is particularly useful for handling brief bursts of data that arrive faster than the cache can process them, leading to delays and increased request times. Integrating this strategy into a cache system without harming performance for non-bursty data is a challenge. Our proposed algorithm can select the best strategy based on the workload's characteristics.

## Introduction 
Caching is a crucial technique to speed up data retrieval by taking advantage of variations in access times among different types of memory and storage. Caches store a small portion of data in a faster storage medium. For instance, DRAM can act as a cache for SSDs, and SSDs can serve as a cache for HDDs. When you access data stored in a cache, it's called a cache hit, while accessing data not in the cache is a cache miss. Cache hits are generally faster because the data is fetched from a quicker storage medium.

Traditionally, cache algorithms aimed to maximize the hit ratio, which is the percentage of accesses served faster by the cache. This works well because many workloads exhibit patterns like recency (recently accessed items are likely to be accessed again) and frequency (items accessed frequently over time are likely to be accessed again). However, in practice, it's challenging to predict which heuristic will be most beneficial. Therefore, adaptive caches often blend frequency and recency and dynamically allocate resources to each heuristic based on the workload's characteristics.
In situations where access times are not consistent and especially when the times it takes to retrieve data that's not in cache (miss times) vary, it's important to note that maximizing the cache hit ratio doesn't necessarily mean minimizing the overall access time. This is particularly relevant in various real-world applications like search engines, data storage systems, mobile apps, DNS, web access, and more. Non-uniform access times typically occur when a storage system is distributed or employs different storage mediums. Additionally, Non-uniform Memory Access (NUMA) systems can result in different access times to various parts of the DRAM for different CPU cores.

When focusing on variable access times within the context of cost-aware caching, we observe an important distinction. Accessing an item while it's being added to the cache is neither a cache hit (because the item isn't in the cache) nor precisely a cache miss (because it's being fetched to the cache). These situations are known as delayed hits, common in storage systems with bursts of requests, web queries, DNS resolutions, or file retrievals. Unlike classical cache algorithms like LRU, which assume instant item admission, our approach aims to create a software-friendly delayed hit-aware cache using a pipelined adaptive architecture and dynamic sizing based on workload characteristics, allowing the incorporation of multiple policies and algorithms for improved performance.

In the context of cost-aware caching, each item in the cache is associated with a certain access cost. These costs can represent various factors such as variations in access times, monetary expenses, power consumption, and so on. The field of cost-aware caching focuses on developing efficient algorithms tailored to this cost-based model. The problem with ad-hoc caching algorithms is that they become outdated as new heuristics and approaches are introduced, and most caching research does not consider the cost-aware model. Consequently, valuable ideas from one field have limited influence on the development of ideas in other fields.
Our contribution involves enhancing the design of latency-aware caches. We propose an adaptive cache management algorithm that incorporates three distinct factors: Recency, Frequency, and Burstiness, all while considering latency. We introduce Burstiness as a new criterion for caching policies, separate from Recency and Frequency.

Our solution operates as a pipeline of simple cache policies, where the output of one policy feeds into the next. To make it adaptive, we divide the cache into smaller segments and employ a novel ghost entries-based hill climber algorithm to move portions between cache algorithms, adjusting their emphasis on Recency, Frequency, or Burstiness. This adaptability allows us to introduce and fine-tune policies based on workload characteristics.

Through extensive simulations, we demonstrate that our algorithm surpasses various state-of-the-art alternatives, is insensitive to initial conditions, and competes favorably with static configurations, occasionally even outperforming the best static setups. This underscores the effectiveness of our adaptivity technique in identifying and leveraging subtle workload trends. Furthermore, our approach outperforms existing methods, even in scenarios where previous algorithms falter, thanks to our more robust use of ghost entries. While it requires additional metadata, the overhead for a small number of blocks is comparable to other works in the field.

## policies
* LRU (Least Recently Used) is a cache eviction algorithm that removes the least recently accessed item when the cache is full
* LFU (Least Frequently Used) is a cache eviction algorithm that removes the least frequently accessed item when the cache is full.
* First-In-First-Out (FIFO): In FIFO, the first item that was added to the cache is the first one to be removed when the cache reaches its limit. It follows a strict queue-like behavior. 
* Random Replacement: This policy selects a random item from the cache to evict when needed. It's simple but lacks any specific strategy.
* Most Recently Used (MRU): MRU evicts the most recently accessed item when the cache is full. It assumes that the most recently accessed item is the most likely to be accessed again soon.
* Adaptive Replacement Cache (ARC): ARC combines elements of both LRU and LFU to adapt to changing access patterns. It dynamically adjusts its behavior based on recent access history.
* Two-Queue Algorithm (2Q): 2Q uses two separate queues, one for recently accessed items (MRU) and the other for frequently accessed items (LFU). It aims to provide a balance between these two aspects of caching.
* Clock or Second-Chance Algorithm: This policy keeps a circular buffer and marks items as they are accessed. When an item needs to be evicted, it looks for the first unmarked (unused) item in the buffer.
* LIRS (Low Inter-reference Recency Set): LIRS is designed to improve upon LRU by distinguishing between items with high and low inter-reference recency. It aims to provide better cache hit rates.
* GDSF (Greedy-Dual Size Frequency): GDSF maintains two separate queues for items and prioritizes eviction based on both size and access frequency.

## Important 
* Caffeine is a java rewrite of Guaca's cache and will supersede the Guava support in Spring Boot 2.0 . if Caffeine is present, a __CaffeineCacheManager__ (provided by the __spring-boot-starter-cache__ 'Starter') is auto-configured. Caches can be created on setup using the __spring.cache.cache-names__ property and customized by one of the followong:
   1. A cache spec defined by __spring.cache.caffeine.spec__
   2. A com.github.benmanes.caffeine.cache.CaffeineSpec bean is defined
   3. A com.github.benmanes.caffeine.cache.Caffeine bean is defined
Below is the GitHub link to download source: https://github.com/kishanjavatrainer/SpringBootCaffeineCache

* DRAM, which stands for Dynamic Random-Access Memory, is a type of volatile computer memory that is commonly used in computers and other electronic devices. It is a form of primary or main memory in a computer system and is responsible for temporarily storing data that     the CPU (Central Processing Unit) actively uses during program execution. Regarding the statement "DRAM can be a cache for SSD," this means that DRAM memory can be used as a cache to enhance the performance of Solid-State Drives (SSDs). SSDs are a type of non-volatile storage that is faster than traditional hard disk drives (HDDs) but still not as fast as DRAM. By using DRAM as a cache for frequently accessed data from the SSD, it helps reduce the latency of data access and improves overall system performance. This caching mechanism allows frequently used data to be quickly retrieved from the high-speed DRAM memory, while less frequently accessed data is stored on the slower but higher-capacity SSD. It's a technique used to bridge the speed gap between DRAM and SSD, optimizing data access in a computer system.
* Ad-hoc algorithms refer to algorithms that are specifically designed or tailored for a particular problem or situation without following a general, standardized approach. These algorithms are created on a case-by-case basis to address a specific, often unique, problem or set of conditions. They are typically not part of a broader, established algorithmic framework and are often improvised or custom-built to solve a particular problem at hand.

## Tools <img align="right" width="15%" src="https://github.com/shahaf2284/Final_Degree_Project/assets/122786017/8c99f679-bde1-41a9-a957-51882d86a4bb" /> 
* Spring Boot - is an open-source Java-based framework used for building production-ready, stand-alone, and web-based applications quickly and with minimal configuration. It is part of the larger Spring ecosystem and is designed to simplify the development of Spring   applications by providing a set of pre-configured templates and conventions. Spring Boot makes it easier to create robust, scalable, and maintainable Java applications.
    * [Guide To Caching in Spring](https://www.baeldung.com/spring-cache-tutorial)
    * [Another explanation](https://docs.spring.io/spring-boot/docs/2.1.6.RELEASE/reference/html/boot-features-caching.html#boot-features-caching-provider-caffeine)
    * [Instructions Spring](https://www.javatpoint.com/spring-boot-caching)
      

## REFERENCES
* [Caching Online Video: Analysis and Proposed Algorithm](https://www.researchgate.net/publication/319117728_Caching_Online_Video_Analysis_and_Proposed_Algorithm)
* [Cost-aware caching: optimizing cache provisioning and object placement in ICN](https://perso.telecom-paristech.fr/drossi/paper/rossi14globecom.pdf)
* [Caching with Delayed Hits](https://dl.acm.org/doi/pdf/10.1145/3387514.3405883)
* [Lower Bounds for Caching with Delayed Hits](https://arxiv.org/pdf/2006.00376.pdf)
* [Hyperbolic Caching: Flexible Caching for Web Applications](https://www.usenix.org/system/files/conference/atc17/atc17-blankstein.pdf)
* [Improving WWW Proxies Performance with Greedy-Dual-Size-Frequency Caching Policy](https://eclass.uoa.gr/modules/document/file.php/D245/2015/HPL-98-69R1_GDS.pdf)
* [Storage-Aware Caching:Revisiting Caching for Heterogeneous Storage Systems](https://research.cs.wisc.edu/wind/Publications/storageAware-fast02.pdf)
#### REFERENCES for the policies
* [Book Data Structures & Algorithms in Java](https://everythingcomputerscience.com/books/schoolboek-data_structures_and_algorithms_in_java.pdf)
* [Book Java Concurrency in Practice](https://leon-wtf.github.io/doc/java-concurrency-in-practice.pdf)
* [High-Performance Computing and Networking](https://link-springer-com.ezproxy.bgu.ac.il/book/10.1007/3-540-48228-8)

## More reference use later mybe 
* https://java-design-patterns.com/patterns/caching/


## Questions we are trying to understand about the library
* if it is possible to obtain information about the latency
   * Caffeine library primarily focuses on caching and doesn't directly provide features for measuring or obtaining information about latency. It is designed to efficiently manage in-memory caches and provide fast access to cached data.
__However__, we can measure latency and performance indirectly by implementing your own timing and profiling mechanisms in your code. Here are a few approaches you can consider:
      * Instrumentation: add instrumentation code to the application to measure the time taken by specific operations, including cache access. For example, you can use Java's __System.nanoTime()__ to record timestamps before and after cache access and calculate the time elapsed.
      * Profiling Tools: Use Java profiling tools like __VisualVM__ or __YourKit__ to analyze the performance of your application, including cache-related operations. These tools can help you identify bottlenecks and performance issues, which indirectly relate to latency.
      * Logging: Implement custom logging to record timestamps and relevant information for cache operations. This can help you analyze the time taken by cache-related activities.
      * External Monitoring: Use external monitoring tools and services that can provide insights into your application's performance, including latency measurements. Tools like __Prometheus__ with Grafana or New __Relic__ can be integrated into your application for performance monitoring.
   Remember that measuring latency can be influenced by various factors, including the hardware, operating system, and JVM configuration. It's essential to consider the specific context and requirements of your application when implementing latency measurement solutions.

   Since software libraries like Caffeine primarily focus on their core functionality (in this case, caching), additional performance monitoring and latency measurement are typically handled    at the application level using the approaches mentioned above. Keep in mind that newer versions of libraries and frameworks may introduce additional features, so it's a good practice to       check the documentation or release notes for the latest updates and capabilities.
 


[![Build Status](https://github.com/ben-manes/caffeine/workflows/build/badge.svg)](https://github.com/ben-manes/caffeine/actions?query=workflow%3Abuild+branch%3Amaster)
[![Test Count](https://gist.githubusercontent.com/ben-manes/c20eb418f0e0bd6dfe1c25beb35faae4/raw/badge.svg)](https://github.com/ben-manes/caffeine/actions?query=workflow%3Abuild+branch%3Amaster)
[![Coverage Status](https://img.shields.io/coveralls/ben-manes/caffeine.svg)](https://coveralls.io/r/ben-manes/caffeine?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.ben-manes.caffeine/caffeine)
[![JavaDoc](https://www.javadoc.io/badge/com.github.ben-manes.caffeine/caffeine.svg)](http://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine)
[![License](https://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Stack Overflow](https://img.shields.io/:stack%20overflow-caffeine-brightgreen.svg)](http://stackoverflow.com/questions/tagged/caffeine)
[![Revved up by Gradle Enterprise](https://img.shields.io/badge/Revved%20up%20by-Gradle%20Enterprise-06A0CE?logo=Gradle&labelColor=02303A)](https://caffeine.gradle-enterprise.cloud/scans)
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
* [Kafka][kafka]: A distributed event streaming platform
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
implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

// Optional extensions
implementation("com.github.ben-manes.caffeine:guava:3.1.8")
implementation("com.github.ben-manes.caffeine:jcache:3.1.8")
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
[kafka]: https://kafka.apache.org
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
