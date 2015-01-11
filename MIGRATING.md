# Migrating

## [Guava Cache](https://code.google.com/p/guava-libraries/wiki/CachesExplained)

#### API compatibility

Caffeine provides an adapter to expose its caches using Guava's interfaces. These adapters provide
the same API contract with the implementation caveats below. Where possible Guava like behavior is 
emulated and validated with a port of Guava's test suite.

When transitioning to Caffeine's interfaces, please note that while the two caches have similar
method names the behavior may be slightly different. Please consult the JavaDoc to compare usages
more thoroughly.

#### Maximum size (or weighted size)

Guava will evict prior to reaching the maximum size whereas Caffeine will evict once the threshold
has been crossed.

#### Invalidation with concurrent computations

Guava will ignore entries during invalidation while they are still being computed. In Caffeine
each entry being invalidated will block the caller until it has finished computing and will then
be removed. If an asynchronous cache is used instead then invalidation is non-blocking as the
incomplete future will be removed and the removal notification delegated to the computing thread.

#### Asynchronous notifications

Guava processes removal notifications from a queue that any calling thread may take from. Caffeine
delegates to the configured executor (default: `ForkJoinPool.commonPool()`).

#### Asynchronous refresh

Guava recomputes an entry on the thread requesting a refresh. Caffeine delegates to the configured
executor (default: `ForkJoinPool.commonPool()`).

#### Computing null values

Guava throws an exception when a null value has been computed and retains the entry if due to a
refresh. Caffeine returns the null value and, if the computation was due to a refresh, removes the
entry. If the Guava adapters are used, Caffeine will behave as Guava does if built with a Guava
`CacheLoader`.

#### CacheStats

Guava's `CacheStats.loadExceptionCount()` and `CacheStats.loadExceptionRate()` are renamed to
`CacheStats.loadFailureCount()` and `CacheStats.loadFailureRate()` respectively in Caffeine. This
change is due to null computed values being treated as load failures and not as exceptions.

#### Android & GWT compatibility

Caffeine does not provide compatibility due to those platforms not supporting Java 8.

## [ConcurrentLinkedHashMap](https://code.google.com/p/concurrentlinkedhashmap/)

#### Weigher

`ConcurrentLinkedHashMap` requires a minimum weight of 1. Like Guava, Caffeine allows a mimimum
weight of 0 to indicate that the entry will not be evicted due to a size-based policy.

#### Asynchronous notifications

`ConcurrentLinkedHashMap` processes eviction notifications from a queue that any calling thread may
take from. Caffeine delegates to the configured executor (default: `ForkJoinPool.commonPool()`).

#### Serialization

`ConcurrentLinkedHashMap` retains the entries and discards the eviction order when serializing.
Caffeine, like Guava, retains only the configuration and no data.
