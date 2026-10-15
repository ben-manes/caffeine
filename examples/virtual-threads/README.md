Using Java 21 [Virtual Threads][loom], you can scale I/O-bound cache loading and
asynchronous refreshing without exhausting thread pools or incurring heavy platform-thread memory overhead.

### The Problem: Thread Pool Exhaustion

In high-throughput systems where cache misses or background reloads fetch data over the network
(e.g., microservices, databases, or third-party APIs), traditional platform thread pools (such as
the common `ForkJoinPool` or bounded `ThreadPoolExecutor`) can quickly become saturated. When all
threads block on network I/O, subsequent cache loads stall in executor queues, leading to high
tail latencies and cascading system degradation.

### The Solution: Virtual Threads

Configuring Caffeine with `Executors.newVirtualThreadPerTaskExecutor()` offloads cache loading and
maintenance tasks to lightweight virtual threads. When a virtual thread executes a blocking operation
(such as an HTTP call, JDBC query, or `Thread.sleep`), the JVM automatically unmounts it from its
underlying OS carrier thread. The carrier thread remains free to execute other work, enabling
tens of thousands of concurrent I/O operations with near-zero idle overhead.

```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder()
    .executor(executor)
    .maximumSize(10_000)
    .refreshAfterWrite(Duration.ofMinutes(5))
    .buildAsync(new AsyncCacheLoader<Key, Graph>() {
      @Override
      public CompletableFuture<Graph> asyncLoad(Key key, Executor executor) {
        return CompletableFuture.supplyAsync(() -> fetchRemoteGraph(key), executor);
      }

      @Override
      public CompletableFuture<Graph> asyncReload(Key key, Graph oldValue, Executor executor) {
        return CompletableFuture.supplyAsync(() -> refreshRemoteGraph(key, oldValue), executor);
      }
    });
```

### Key Benefits

- **Non-blocking lookups:** `cache.get(key)` immediately returns an incomplete `CompletableFuture`
  while the blocking load proceeds concurrently on a dedicated virtual thread.
- **Stale-while-revalidate:** With `refreshAfterWrite` and `asyncReload`, cache lookups immediately
  return the existing (stale) value while a virtual thread asynchronously fetches the refreshed value
  in the background without blocking concurrent readers or exhausting thread pools.
- **Scalability:** Thousands of concurrent cache misses and background refreshes can execute
  simultaneously without thread starvation.

### Running the Example

```bash
./gradlew run
```

[loom]: https://openjdk.org/jeps/444
