[![Build Status](https://github.com/ben-manes/caffeine/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/ben-manes/caffeine/actions?query=workflow%3Abuild+branch%3Amaster)
[![Test Count](https://gist.githubusercontent.com/ben-manes/c20eb418f0e0bd6dfe1c25beb35faae4/raw/badge.svg)](https://github.com/ben-manes/caffeine/actions?query=workflow%3Abuild+branch%3Amaster)
[![Coverage Status](https://coveralls.io/repos/github/ben-manes/caffeine/badge.svg?branch=master)](https://coveralls.io/github/ben-manes/caffeine?branch=master)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.ben-manes.caffeine/caffeine?color=31c653&label=maven%20central)](https://central.sonatype.com/artifact/com.github.ben-manes.caffeine/caffeine)
[![JavaDoc](https://www.javadoc.io/badge/com.github.ben-manes.caffeine/caffeine.svg?color=31c653)](https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine)
[![License](https://img.shields.io/:license-apache-31c653.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://caffeine.develocity.cloud/scans)
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

* [Spring Cache][spring]: Simple, modern, productive Java
* [Play Framework][play]: High velocity web framework
* [Micronaut][micronaut]: A modern, full-stack framework
* [Coroutines][caffeine-coroutines]: Kotlin Coroutines extension
* [Bootique][bootique]: A fast, simple Java platform
* [Quarkus][quarkus]: Supersonic Subatomic Java
* [Camel][camel]: Routing and mediation engine
* [Scaffeine][scaffeine]: Scala wrapper for Caffeine
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
    ([slides][modern-cache-slides]) at [HighScalability][]
  * [The Adaptive Window, From the Ground Up][adaptive-window] on optimizing shifting workloads
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
implementation("com.github.ben-manes.caffeine:caffeine:3.2.4")

// Optional extensions
implementation("com.github.ben-manes.caffeine:guava:3.2.4")
implementation("com.github.ben-manes.caffeine:jcache:3.2.4")
```

For Java 11 or above, use `3.x` otherwise use `2.x`.

See the [release notes][releases] for details of the changes.

Snapshots of the development version are available in
[Sonatype's snapshots repository][snapshots].

[benchmarks]: https://github.com/ben-manes/caffeine/wiki/Benchmarks
[users-guide]: https://github.com/ben-manes/caffeine/wiki
[javadoc]: https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine
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
[maven]: https://central.sonatype.com/artifact/com.github.ben-manes.caffeine/caffeine
[releases]: https://github.com/ben-manes/caffeine/releases
[snapshots]: https://central.sonatype.org/publish/publish-portal-snapshots/#consuming-snapshot-releases-for-your-project
[efficiency]: https://github.com/ben-manes/caffeine/wiki/Efficiency
[tinylfu]: https://dl.acm.org/doi/10.1145/3149371?cid=99659224047
[adaptive-tinylfu]: https://dl.acm.org/doi/10.1145/3274808.3274816?cid=99659224047
[size-tinylfu]: https://dl.acm.org/doi/10.1145/3507920?cid=99659224047
[modern-cache-1]: https://highscalability.com/blog/2016/1/25/design-of-a-modern-cache.html
[modern-cache-2]: https://highscalability.com/blog/2019/2/25/design-of-a-modern-cachepart-deux.html
[modern-cache-slides]: https://docs.google.com/presentation/d/1NlDxyXsUG1qlVHMl4vsUUBQfAJ2c2NsFPNPr2qymIBs
[HighScalability]: https://highscalability.com
[spring]: https://docs.spring.io/spring-framework/reference/integration/cache/store-configuration.html#cache-store-configuration-caffeine
[scaffeine]: https://github.com/blemale/scaffeine
[kafka]: https://kafka.apache.org
[hbase]: https://hbase.apache.org
[cassandra]: https://cassandra.apache.org
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
[bootique]: https://bootique.io/
[caffeine-coroutines]: https://github.com/be-hase/caffeine-coroutines
[adaptive-window]: https://htmlpreview.github.io/?https://github.com/ben-manes/caffeine/blob/master/wiki/adaptive-window.html


## 🌐 Web Resources & Interactive Index
- [TANK WARS IAW](https://quizverses.github.io/tank-wars-iaw.html)
- [CATEGORY MERGE224](https://studyplaying.github.io/category-merge224.html)
- [CANNON MERGE](https://studyquests.github.io/cannon-merge.html)
- [PANDA LU TREEHOUSE](https://thelearnquesters.pages.dev/panda-lu-treehouse.html)
- [BFFS SPRING BREAK FASHIONISTA](https://studyquests.github.io/bffs-spring-break-fashionista.html)
- [CAPYBARA MUKBANG ASMR](https://studyquests.github.io/capybara-mukbang-asmr.html)
- [Z STICK DUEL FIGHTING](https://studyquests.pages.dev/z-stick-duel-fighting.html)
- [PURRFECT BAKERY](https://studyquests.github.io/purrfect-bakery.html)
- [CATEGORY SIMULATION 3](https://quizverses.pages.dev/category-simulation-3.html)
- [KNOCK AND RUN 100 DOORS ESCAPE](https://studyquests.github.io/knock-and-run-100-doors-escape.html)
- [JIGSORT PUZZLES](https://studyplayings.pages.dev/jigsort-puzzles.html)
- [AIR BLOCK](https://studyquesthub.web.app/air-block.html)
- [PET TILE MASTER](https://studyplayings.pages.dev/pet-tile-master.html)
- [PUSH IT 3D](https://studyquests.pages.dev/push-it-3d.html)
- [COLOR SAND PUZZLE](https://studyquests.github.io/color-sand-puzzle.html)
- [MOSQUITO BITE 3D](https://quizverses.github.io/mosquito-bite-3d.html)
- [BOMBARDINO CROCODILO TERROR JUMPSCARE](https://studyplayings.pages.dev/bombardino-crocodilo-terror-jumpscare.html)
- [ARCHERS RANDOM](https://quizverses-9d2f2.web.app/archers-random.html)
- [CATEGORY IDLE](https://studyquests.pages.dev/category-idle.html)
- [MIRRORS PUZZLE](https://studyquests.pages.dev/mirrors-puzzle.html)
- [INDEX40](https://thelearnquesters.pages.dev/index40.html)
- [MOBILE LEGENDS SLIME 3V3](https://studyquests.pages.dev/mobile-legends-slime-3v3.html)
- [CATEGORY BRAIN](https://learnquester.pages.dev/category-brain.html)
- [STICKMAN THE FLASH](https://studyquesthub.web.app/stickman-the-flash.html)
- [FAR ORION NEW WORLDS](https://learnquester.pages.dev/far-orion-new-worlds.html)
- [TRANSFORMERS BATTLE FOR THE CITY](https://studyquesthub.web.app/transformers-battle-for-the-city.html)
- [EXTREME REAL CAR DRIVING 2025](https://learnquester.pages.dev/extreme-real-car-driving-2025.html)
- [CLINIC CLEANUP CREW](https://studyquests.github.io/clinic-cleanup-crew.html)
- [MERGE BRAINROT](https://studyquests.github.io/merge-brainrot.html)
- [BLOCK DROPPING MERGE](https://studyplayings.pages.dev/block-dropping-merge.html)
- [COLOR SCREW RESCUE PUZZLE](https://quizverses.github.io/color-screw-rescue-puzzle.html)
- [CATEGORY SANDBOX](https://studyplayings.web.app/category-sandbox.html)
- [RELAX MINI GAMES COLLECTION](https://studyquesthub.web.app/relax-mini-games-collection.html)
- [SQUID GAME PLAYGROUND SHOOTER](https://studyplayings.pages.dev/squid-game-playground-shooter.html)
- [CATEGORY BATTLE524](https://studyquests.pages.dev/category-battle524.html)
- [CYBER ROLLING GOING BALL 3D](https://studyquests.pages.dev/cyber-rolling-going-ball-3d.html)
- [RUNIC BLOCK COLLAPSE](https://studyquests.github.io/runic-block-collapse.html)
- [EAT DONUTS](https://studyplaying.github.io/eat-donuts.html)
- [STELLAR GUARDIAN](https://quizverses.github.io/stellar-guardian.html)
- [MOTO TRAFFIC RIDER](https://studyquesthub.web.app/moto-traffic-rider.html)
- [OIL DIGGING](https://studyquests.pages.dev/oil-digging.html)
- [CATEGORY CAR376](https://learnquesters.pages.dev/category-car376.html)
- [SUPERHERO TRANSFORM CHANGE RACE](https://studyquests.github.io/superhero-transform-change-race.html)
- [PLANTS VS ZOMBIES WAR](https://studyplaying.github.io/plants-vs-zombies-war.html)
- [CATEGORY MINECRAFT 2](https://thelearnquesters.pages.dev/category-minecraft-2.html)
- [DREAM RESTAURANT 3D](https://studyquests.pages.dev/dream-restaurant-3d.html)
- [OFFROAD CLIMB 4X4](https://studyplayings.pages.dev/offroad-climb-4x4.html)
- [DISASSEMBLE THE PICTURE PUZZLE](https://studyplaying.github.io/disassemble-the-picture-puzzle.html)
- [DRAW TO SMASH ZOMBIE](https://studyplaying.github.io/draw-to-smash-zombie.html)
- [MEN VS GORILLAS](https://learnquesters.pages.dev/men-vs-gorillas.html)
- [HOLE EAT GROW ATTACK](https://studyplaying.github.io/hole-eat-grow-attack.html)
- [SLITHERCRAFT IO](https://studyplayings.web.app/slithercraft-io.html)
- [AFRICAN PRINCESSES STYLE ISLAND](https://learnquester.pages.dev/african-princesses-style-island.html)
- [CAR PARKING MASTER 3D REAL DRIVING SIMULATOR](https://quizverses-9d2f2.web.app/car-parking-master-3d-real-driving-simulator.html)
- [CATEGORY CARTOON76](https://studyplayings.web.app/category-cartoon76.html)
- [TRAFFIC RUN PUZZLE](https://thelearnquester.web.app/traffic-run-puzzle.html)
- [CHICKEN BANANA RUN](https://studyquesthub.web.app/chicken-banana-run.html)
- [BATTLE OF TANK STEEL](https://studyquests.pages.dev/battle-of-tank-steel.html)
- [CATEGORY MAHJONG](https://studyplayings.web.app/category-mahjong.html)
- [ARCHERS RANDOM](https://studyplaying.github.io/archers-random.html)
- [MAD TRUCK](https://studyquests.github.io/mad-truck.html)
- [CUTE CATS ADVENTURES](https://quizverses.github.io/cute-cats-adventures.html)
- [PHONE CASE DIY 5](https://studyplayings.pages.dev/phone-case-diy-5.html)
- [MEATRIDER](https://learnquesters.pages.dev/meatrider.html)
- [PHANTOM THIEF CAT RUNNING](https://studyplaying.github.io/phantom-thief-cat-running.html)
- [3D ACRYLIC NAIL NAIL ART GAME](https://quizverses.github.io/3d-acrylic-nail-nail-art-game.html)
- [CATEGORY CASUAL 3](https://studyquests.pages.dev/category-casual-3.html)
- [CROWD BATTLE GUN RUSH](https://thelearnquesters.pages.dev/crowd-battle-gun-rush.html)
- [CATEGORY CASUAL971](https://studyplayings.web.app/category-casual971.html)
- [THE SORTING MART](https://thelearnquester.web.app/the-sorting-mart.html)
- [BOXTERIA](https://studyplayings.web.app/boxteria.html)
- [CATEGORY ARENA255](https://studyquests.pages.dev/category-arena255.html)
- [CATEGORY MAGIC46](https://studyquesthub.web.app/category-magic46.html)
- [AMAZING AIRPLANE RACER](https://thelearnquester.web.app/amazing-airplane-racer.html)
- [SUPER BITCOIN BOY](https://studyquests.github.io/super-bitcoin-boy.html)
- [EXIT PUZZLE](https://studyplaying.github.io/exit-puzzle.html)
- [WORLDGUESSR](https://studyquesthub.web.app/worldguessr.html)
- [GOLD MINER TOWER DEFENSE](https://thelearnquester.web.app/gold-miner-tower-defense.html)
- [JIGSOLITAIRE](https://learnquester.pages.dev/jigsolitaire.html)
- [CATEGORY CASUAL 8](https://learnquester.github.io/category-casual-8.html)
- [BOOM STICK BAZOOKA](https://studyquesthub.web.app/boom-stick-bazooka.html)
- [GLOVES GROW RUSH](https://quizverses-9d2f2.web.app/gloves-grow-rush.html)
- [WORMS](https://learnquester.pages.dev/worms.html)
- [TRANSFORM CAR BATTLE](https://quizverses-9d2f2.web.app/transform-car-battle.html)
- [CATEGORY SKILL256](https://quizverses-9d2f2.web.app/category-skill256.html)
- [ITALIAN BRAINROT DRAG MERGE PUZZLE](https://studyquests.github.io/italian-brainrot-drag-merge-puzzle.html)
- [QUIZMANIA TRIVIA GAME](https://studyquests.github.io/quizmania-trivia-game.html)
- [SORT MASTER](https://studyquests.pages.dev/sort-master.html)
- [PIECE OF CAKE MERGE AND BAKE](https://studyplayings.pages.dev/piece-of-cake-merge-and-bake.html)
- [CATEGORY FASHION105](https://studyplayings.web.app/category-fashion105.html)
- [SAVE THE BEAUTY](https://quizverses-9d2f2.web.app/save-the-beauty.html)
- [UNLOCK THE BOLTS](https://learnquester.pages.dev/unlock-the-bolts.html)
- [RED LIGHT GREEN LIGHT](https://studyquests.github.io/red-light-green-light.html)
- [IDLE SUPERMARKET TYCOON](https://quizverses.github.io/idle-supermarket-tycoon.html)
- [HOOK PIN JAM](https://learnquester.pages.dev/hook-pin-jam.html)
- [BITGOBLINS RPG SIMULATOR](https://studyplaying.github.io/bitgoblins-rpg-simulator.html)
- [LAMBO TRAFFIC RACER](https://learnquesters.pages.dev/lambo-traffic-racer.html)
- [FISH RAIN 2](https://studyplaying.github.io/fish-rain-2.html)
- [CATEGORY FASHION](https://learnquester.github.io/category-fashion.html)
- [CATEGORY THINKY](https://thelearnquesters.pages.dev/category-thinky.html)
- [OFFICE KNIGHT 3D CASTLE DEFENSE](https://quizverses.github.io/office-knight-3d-castle-defense.html)
- [PANDA SHOP SIMULATOR](https://learnquester.pages.dev/panda-shop-simulator.html)
- [CATEGORY QUIZ](https://quizverses-9d2f2.web.app/category-quiz.html)
- [BUBBLE PLOPPER](https://quizverses-9d2f2.web.app/bubble-plopper.html)
- [GEOMETRY LITE](https://quizverses.github.io/geometry-lite.html)
- [SUGAR HEROES](https://learnquester.pages.dev/sugar-heroes.html)
- [ICE FISHING 3D](https://thelearnquester.web.app/ice-fishing-3d.html)
- [THE COUNTERFEIT BANK](https://studyquests.pages.dev/the-counterfeit-bank.html)
- [CIRCLE SHOOTER MASTER](https://quizverses.github.io/circle-shooter-master.html)
- [CLASSIC LABYRINTH 3D MAZE](https://thelearnquesters.pages.dev/classic-labyrinth-3d-maze.html)
- [WONDERS OF EGYPT MATCH 2](https://studyquests.pages.dev/wonders-of-egypt-match-2.html)
- [CATEGORY UNBLOCKED WEBSITES](https://studyplaying.github.io/category-unblocked-websites.html)
- [CATEGORY COOKING](https://learnquester.github.io/category-cooking.html)
- [CATEGORY MEME BLOXY24](https://learnquester.pages.dev/category-meme-bloxy24.html)
- [UNCLE BULLET 007](https://quizverses-9d2f2.web.app/uncle-bullet-007.html)
- [CATEGORY 3D1 383](https://thelearnquesters.pages.dev/category-3d1-383.html)
- [CATEGORY IO](https://learnquester.github.io/category-io.html)
- [CATEGORY HERO72](https://studyquesthub.web.app/category-hero72.html)
- [CATEGORY UNBLOCKED](https://quizverses-9d2f2.web.app/category-unblocked.html)
- [MONSTER MERGE LEGENDS ALIVE](https://learnquester.pages.dev/monster-merge-legends-alive.html)
- [CATEGORY SPACE](https://quizverses-9d2f2.web.app/category-space.html)
- [HELP ME TRICKY BRAIN PUZZLES](https://learnquester.pages.dev/help-me-tricky-brain-puzzles.html)
- [HEXANAUT IO](https://studyplayings.pages.dev/hexanaut-io.html)
- [CAR JAM ESCAPE](https://learnquester.pages.dev/car-jam-escape.html)
- [DOODLE DINO RUN](https://studyquests.pages.dev/doodle-dino-run.html)
- [PAWS OFF MY CLUES](https://learnquester.pages.dev/paws-off-my-clues.html)
- [FIRE TRUCK DRIVING SIMULATOR](https://learnquester.pages.dev/fire-truck-driving-simulator.html)
- [MERGE TOWN](https://quizverses-9d2f2.web.app/merge-town.html)
- [COSMO VOID](https://quizverses.github.io/cosmo-void.html)
- [STEAL BRAINROT DUEL](https://quizverses-9d2f2.web.app/steal-brainrot-duel.html)
