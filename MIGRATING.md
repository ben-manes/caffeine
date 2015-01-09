# Migrating

## [Guava Cache](https://code.google.com/p/guava-libraries/wiki/CachesExplained)

#### API compatibility

Caffeine provides an adapter to expose its caches using Guava's interfaces. These adapters provide
the exact some API contract with the implementation caveats below. Where possible Guava like
behavior is provided and validated with a port of Guava's test suite.

When transitioning to Caffeine's interfaces, please note that while the two caches have similar
method names the behavior may be slightly different. Please consult the JavaDoc to compare usages
more thoroughly.

#### Maximum size (or weighted size)

Guava will evict prior to reaching the maximum size whereas Caffeine will evict once the threshold
has been crossed.

#### Invalidation with concurrent computations

Guava is able to remove entries while they are still being computed, deferring to the computing
thread to notify the listener once complete. In Caffeine each entry being invalidated will block
the caller until it has finished computing and will then be removed. The behavior is non-blocking
if an asynchronous cache is used instead.

#### Asynchronous notifications

Guava processes removal notifications from a queue that any calling thread may take from. Caffeine
delegates to the configured executor (default: `ForkJoinPool.commonPool()`).

#### Asynchronous refresh

Guava recomputes an entry on the thread requesting a refresh. Caffeine delegates to the configured
executor (default: `ForkJoinPool.commonPool()`).

#### Computing null values

Guava throws an exception when a null value has been computed and retains the entry if due to a
refresh. Caffeine returns the null value and, if the computation was due to a refresh, removes the
entry.

#### CacheStats

Guava's `CacheStats.loadExceptionCount()` and `CacheStats.loadExceptionRate()` are renamed to
`CacheStats.loadFailureCount()` and `CacheStats.loadFailureRate()` respectfully in Caffeine. This
change is due to null computed values being treated as load failures and not as exceptions.

## [ConcurrentLinkedHashMap](https://code.google.com/p/concurrentlinkedhashmap/)

#### Weigher

ConcurrentLinkedHashMap requires a minimum weight of 1. Like Guava, Caffeine allows a mimimum 
weight of 0.

#### Asynchronous notifications

ConcurrentLinkedHashMap processes eviction notifications from a queue that any calling thread may
take from. Caffeine delegates to the configured executor (default: `ForkJoinPool.commonPool()`).
