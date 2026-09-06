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
- [CATEGORY MONSTER206](https://quizverses.github.io/category-monster206.html)
- [GT CHAMPIONSHIP ARCADE](https://studyquests.github.io/gt-championship-arcade.html)
- [SANTA GO](https://studyquests.pages.dev/santa-go.html)
- [LAST WAR SURVIVAL](https://studyplaying.github.io/last-war-survival.html)
- [CATEGORY MANAGEMENT](https://studyplayings.pages.dev/category-management.html)
- [SOKOBAN PUSH THE BOX](https://studyquests.github.io/sokoban-push-the-box.html)
- [GLADIATORS MERGE AND FIGHT](https://studyplayings.pages.dev/gladiators-merge-and-fight.html)
- [UNSCREW WOOD PUZZLE](https://studyquesthub.web.app/unscrew-wood-puzzle.html)
- [ZEN TILE](https://learnquester.pages.dev/zen-tile.html)
- [SQUAD ASSEMBLER](https://learnquester.github.io/squad-assembler.html)
- [MEOW MARKET](https://quizverses.github.io/meow-market.html)
- [MR BEAN JUMP](https://studyplayings.web.app/mr-bean-jump.html)
- [ARROW TAP PUZZLE](https://studyplaying.github.io/arrow-tap-puzzle.html)
- [INDEX15](https://quizverses.github.io/index15.html)
- [RED LIGHT GREEN LIGHT](https://studyquesthub.web.app/red-light-green-light.html)
- [VOLLEY BEAN](https://studyquests.github.io/volley-bean.html)
- [CATEGORY CAR376](https://learnquesters.pages.dev/category-car376.html)
- [MOLE DIG CLICKER](https://studyquesthub.web.app/mole-dig-clicker.html)
- [NUBIK IN THE MONSTER WORLD](https://quizverses.pages.dev/nubik-in-the-monster-world.html)
- [CAR RACING 3D DRIVE MAD](https://quizverses.pages.dev/car-racing-3d-drive-mad.html)
- [TUNG SAHUR BOTS CHASE ROOM](https://studyquesthub.web.app/tung-sahur-bots-chase-room.html)
- [SHADOWMAN RUNNER](https://studyplayings.pages.dev/shadowman-runner.html)
- [CATEGORY GROW](https://thelearnquesters.pages.dev/category-grow.html)
- [PUZZLE BLOCKS CLASSIC](https://studyquests.pages.dev/puzzle-blocks-classic.html)
- [STICKMAN FIGHT PRO](https://thelearnquesters.pages.dev/stickman-fight-pro.html)
- [RUNNING LATE](https://thequizzone.pages.dev/running-late.html)
- [KIRKA IO](https://thelearnquesters.pages.dev/kirka-io.html)
- [CATEGORY OBSTACLE299](https://iskillquest.pages.dev/category-obstacle299.html)
- [HOME RUSH THE FISH WAR](https://theskillquest.pages.dev/home-rush-the-fish-war.html)
- [CATEGORY CARDS](https://thequizzone.pages.dev/category-cards.html)
- [3D KID SLIDING PUZZLE](https://thequizzone.pages.dev/3d-kid-sliding-puzzle.html)
- [OBBY POGO PARKOUR](https://themindplays.pages.dev/obby-pogo-parkour.html)
- [SWAT FORCE VS TERRORISTS](https://thequizzone.pages.dev/swat-force-vs-terrorists.html)
- [GOMU GOMAN](https://thequizzone.pages.dev/gomu-goman.html)
- [GEOMETRY OPEN WORLD](https://thequizzone.pages.dev/geometry-open-world.html)
- [MASTER BLENDER](https://thequizzone.pages.dev/master-blender.html)
- [LION FAMILY SIM ONLINE](https://theskillquest.pages.dev/lion-family-sim-online.html)
- [FRUIT JAM](https://theskillquest.pages.dev/fruit-jam.html)
- [DOGES BATTLE ROYALE](https://theskillquest.pages.dev/doges-battle-royale.html)
- [BUS COLOR JAM](https://thequizzone.pages.dev/bus-color-jam.html)
- [CRAZY ZOO SWIPE MATCH 3 PUZZLE GAME](https://thequizzone.pages.dev/crazy-zoo-swipe-match-3-puzzle-game.html)
- [FILL SORT PUZZLE](https://theskillquest.pages.dev/fill-sort-puzzle.html)
- [MOTORCYCLE SIMULATOR OFFLINE](https://thequizzone.pages.dev/motorcycle-simulator-offline.html)
- [MAGIC BEAUTY MAKEUP](https://thequizzone.pages.dev/magic-beauty-makeup.html)
- [MERGE FLOWERS](https://thelearnquesters.pages.dev/merge-flowers.html)
- [CLAY CRAFT TYCOON](https://thelearnquesters.pages.dev/clay-craft-tycoon.html)
- [STICKMAN DUO ESCAPE THE TOMB](https://theskillquest.pages.dev/stickman-duo-escape-the-tomb.html)
- [PUZZLE LUB](https://thelearnquesters.pages.dev/puzzle-lub.html)
- [BARBIECORE AESTHETICS](https://theskillquest.pages.dev/barbiecore-aesthetics.html)
- [THE SHAPE](https://thelearnquesters.pages.dev/the-shape.html)
- [POPTROPICA](https://theskillquest.pages.dev/poptropica.html)
- [TRAIN DRIFT](https://thequizzone.pages.dev/train-drift.html)
- [ICONIC HALLOWEEN COSTUMES](https://theskillquest.pages.dev/iconic-halloween-costumes.html)
- [WHATS IN MY BAG](https://theskillquest.pages.dev/whats-in-my-bag.html)
- [BLUE MUSHROOM CAT RUN](https://theskillquest.pages.dev/blue-mushroom-cat-run.html)
- [SUPER DOG HERO DASH](https://thelearnquesters.pages.dev/super-dog-hero-dash.html)
- [CATEGORY PIXEL313](https://thequizzone.pages.dev/category-pixel313.html)
- [CAR JAM ESCAPE](https://theskillquest.pages.dev/car-jam-escape.html)
- [CATEGORY BATTLE 2](https://themindzone.pages.dev/category-battle-2.html)
- [BRAINSTORMING 2D](https://theskillquest.pages.dev/brainstorming-2d.html)
- [CATEGORY FPS 2](https://thequizzone.pages.dev/category-fps-2.html)
- [CATEGORY CASUAL969](https://themindzone.pages.dev/category-casual969.html)
- [BILLIARD DIAMOND CHALLENGE](https://thelearnquesters.pages.dev/billiard-diamond-challenge.html)
- [MERGE MASTER SKIBIDI BOP](https://theskillquest.pages.dev/merge-master-skibidi-bop.html)
- [TRAITOR BEAVER](https://thequizzone.pages.dev/traitor-beaver.html)
- [SOLITAIRE FARM SEASONS 3](https://theskillquest.pages.dev/solitaire-farm-seasons-3.html)
- [CATEGORY HORROR](https://themindzone.pages.dev/category-horror.html)
- [CATEGORY MAKEUP](https://iskillquest.pages.dev/category-makeup.html)
- [CATEGORY AIRPLANE](https://thequizzone.pages.dev/category-airplane.html)
- [ITALIAN BRAINROT CHALLENGE](https://thelearnquesters.pages.dev/italian-brainrot-challenge.html)
- [ESCAPE FROM TUNG TUNG SAHUR](https://theskillquest.pages.dev/escape-from-tung-tung-sahur.html)
- [2248 MUSICAL](https://theskillquest.pages.dev/2248-musical.html)
- [AHA WORLD DREAM TOWN](https://iskillquest.pages.dev/aha-world-dream-town.html)
- [BACKYARD DIG HOLE 3D SIMULATOR](https://theskillquest.pages.dev/backyard-dig-hole-3d-simulator.html)
- [CATEGORY IDLE448](https://iskillquest.pages.dev/category-idle448.html)
- [MOTO STUNT BIKER](https://thelearnquesters.pages.dev/moto-stunt-biker.html)
- [CATEGORY MAHJONG](https://themindzone.pages.dev/category-mahjong.html)
- [INDEX27](https://iskillquest.pages.dev/index27.html)
- [ONE SHOT TOWER PHYSICS DESTROYER](https://thequizzone.pages.dev/one-shot-tower-physics-destroyer.html)
- [CATEGORY DRESS UP GAMES](https://thequizzone.pages.dev/category-dress-up-games.html)
- [CATEGORY MANAGEMENT210](https://thequizzone.pages.dev/category-management210.html)
- [CATEGORY MERGE](https://thequizzone.pages.dev/category-merge.html)
- [SURVIVAL ISLAND EVO](https://theskillquest.pages.dev/survival-island-evo.html)
- [CAT FROM HELL CAT SIMULATOR](https://thequizzone.pages.dev/cat-from-hell-cat-simulator.html)
- [FRUIT MAHJONG 3D](https://theskillquest.pages.dev/fruit-mahjong-3d.html)
- [CATEGORY ROBOT49](https://iskillquest.pages.dev/category-robot49.html)
- [SORT TILES](https://thelearnquesters.pages.dev/sort-tiles.html)
- [TILES OF THE UNEXPECTED 2](https://theskillquest.pages.dev/tiles-of-the-unexpected-2.html)
- [ESCAPE FROM TUNG TUNG SAHUR](https://thequizzone.pages.dev/escape-from-tung-tung-sahur.html)
- [MEGA SHARK](https://theskillquest.pages.dev/mega-shark.html)
- [VENETIAN LOVE AFFAIR](https://thequizzone.pages.dev/venetian-love-affair.html)
- [CATEGORY FLASH 3](https://iskillquest.pages.dev/category-flash-3.html)
- [CATEGORY MISSION207](https://thequizzone.pages.dev/category-mission207.html)
- [FOOD SORT 3D](https://thelearnquesters.pages.dev/food-sort-3d.html)
- [SCHOOL ESCAPE OBBIE RUN](https://thequizzone.pages.dev/school-escape-obbie-run.html)
- [CATEGORY MEME BLOXY24](https://iskillquest.pages.dev/category-meme-bloxy24.html)
- [SPRUNKI EASTER COLORING](https://theskillquest.pages.dev/sprunki-easter-coloring.html)
- [HEADLEG DASH PARKOUR](https://learnquester.github.io/headleg-dash-parkour.html)
- [BYEPASSHUB](https://thequizzone.pages.dev/byepasshub.html)
- [FIRE TRUCK DRIVING SIMULATOR](https://learnquester.pages.dev/fire-truck-driving-simulator.html)
- [MERGE HOTEL DEV](https://thequizzone.pages.dev/merge-hotel-dev.html)
- [CATEGORY MINECRAFT](https://quizverses.github.io/category-minecraft.html)
- [ARROWTIX TRAIN YOUR BRAIN](https://thelearnquester.web.app/arrowtix-train-your-brain.html)
- [SUPERMARKET SORT N MATCH](https://thelearnquesters.pages.dev/supermarket-sort-n-match.html)
- [BRIDGE FIGHT](https://studyquests.pages.dev/bridge-fight.html)
- [CATEGORY IDLE GAMES](https://thequizzone.pages.dev/category-idle-games.html)
- [BRAWL STARS BATTLE](https://thequizzone.pages.dev/brawl-stars-battle.html)
- [FUN TOWN PARKING](https://theskillquest.pages.dev/fun-town-parking.html)
- [CATEGORY UNBLOCKEDGAMES](https://quizverses.pages.dev/category-unblockedgames.html)
- [CATEGORY GROW99](https://thequizzone.pages.dev/category-grow99.html)
- [INDEX2](https://iskillquest.pages.dev/index2.html)
- [CATEGORY PUZZLE 7](https://thequizzone.pages.dev/category-puzzle-7.html)
- [ENERGY SUPERMAN 3D](https://thelearnquester.web.app/energy-superman-3d.html)
- [VOXIOM IO](https://theskillquest.pages.dev/voxiom-io.html)
- [POPPY PLAYER PUZZLE](https://learnquester.pages.dev/poppy-player-puzzle.html)
- [SEA LORDS](https://thelearnquesters.pages.dev/sea-lords.html)
- [HEXAMATCH](https://theskillquest.pages.dev/hexamatch.html)
- [ASMR BEAUTY HOMELESS](https://thequizzone.pages.dev/asmr-beauty-homeless.html)
- [GYM MUSCLE MERGE TYCOON](https://thelearnquester.web.app/gym-muscle-merge-tycoon.html)
- [CATEGORY SOLITAIRE](https://iskillquest.pages.dev/category-solitaire.html)
- [INDEX17](https://themindzone.pages.dev/index17.html)
- [CONQUERIO](https://thelearnquesters.pages.dev/conquerio.html)
- [EYE ART PERFECT MAKEUP ARTIST](https://studyquests.github.io/eye-art-perfect-makeup-artist.html)
- [SAVE HER TOUR](https://learnquesters.pages.dev/save-her-tour.html)
- [HOTGEAR](https://studyplaying.github.io/hotgear.html)
- [PIRATE ISLAND](https://thelearnquesters.pages.dev/pirate-island.html)
- [3D BLOCK GLADIATOR SWORD DRAW](https://learnquesters.pages.dev/3d-block-gladiator-sword-draw.html)
- [KALULU TANHULU ASMR MUKBANG](https://thelearnquesters.pages.dev/kalulu-tanhulu-asmr-mukbang.html)
- [MAGIC WATER SORT COLOR PUZZLE](https://learnquester.pages.dev/magic-water-sort-color-puzzle.html)
- [CATEGORY BOARDGAMES](https://thelearnquesters.pages.dev/category-boardgames.html)
