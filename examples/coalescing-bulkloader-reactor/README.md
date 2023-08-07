[Reactor][reactor] data streams facilitate the consolidation of independent asynchronous loads into
batches at the cost of a small buffering delay. The [bufferTimeout][] operator accumulates requests
until reaching a maximum size or time limit. Since each request consists of a key and its pending
result, when the subscriber is notified it performs the batch load and completes the key's future
with its corresponding value.

It some scenarios it may be desirable to only aggregate cache refreshes rather than imposing delays
on callers awaiting explicit loads. An automated reload initiated by `refreshAfterWrite` will occur
on the first stale request for an entry. While the key is being refreshed the previous value
continues to be returned, in contrast to eviction which forces retrievals to wait until the value
is loaded anew. In such cases, batching these optimistic reloads can minimize the impact on the
source system without adversely affecting the responsiveness of the explicit requests.

### Refresh coalescing
A [Sink][sink] collects requests, buffering them up to the configured threshold, and subsequently
delivers the batch to the subscriber. The `parallelism` setting determines the number of concurrent
bulk loads that can be executed if the size constraint results in multiple batches.

```java
public final class CoalescingBulkLoader<K, V> implements CacheLoader<K, V> {
  private final Function<Set<K>, Map<K, V>> mappingFunction;
  private final Sinks.Many<Request<K, V>> sink;

  /**
   * @param maxSize the maximum entries to collect before performing a bulk request
   * @param maxTime the maximum duration to wait before performing a bulk request
   * @param parallelism the number of parallel bulk loads that can be performed
   * @param mappingFunction the function to compute the values
   */
  public CoalescingBulkLoader(int maxSize, Duration maxTime, int parallelism,
      Function<Set<K>, Map<K, V>> mappingFunction) {
    this.sink = Sinks.many().unicast().onBackpressureBuffer();
    this.mappingFunction = requireNonNull(mappingFunction);
    sink.asFlux()
        .bufferTimeout(maxSize, maxTime)
        .map(requests -> requests.stream().collect(
            toMap(Entry::getKey, Entry::getValue)))
        .parallel(parallelism)
        .runOn(Schedulers.boundedElastic())
        .subscribe(this::handle);
  }
```

To ensure immediate responses for explicit loads these calls directly invoke the mapping function,
while the optimistic reloads are instead submitted to the sink. It's worth noting that this call is
`synchronized`, as a sink does not support concurrent submissions.

```java
  @Override public V load(K key) {
    return loadAll(Set.of(key)).get(key);
  }

  @Override public abstract Map<K, V> loadAll(Set<? extends K> key) {
    return mappingFunction.apply(keys);
  }

  @Override public synchronized CompletableFuture<V> asyncReload(K key, V oldValue, Executor e) {
    var entry = Map.entry(key, new CompletableFuture<V>());
    sink.tryEmitNext(entry).orThrow();
    return entry.getValue();
  }
```

The subscriber receives a batch of requests, each comprising of a key and a pending future result.
It performs the synchronous load and then either completes the key's future with the corresponding
value or an exception if a failure occurs.

```java
  private void handle(Map<K, CompletableFuture<V>> requests) {
    try {
      var results = mappingFunction.apply(requests.keySet());
      requests.forEach((key, result) -> result.complete(results.get(key)));
    } catch (Throwable t) {
      requests.forEach((key, result) -> result.completeExceptionally(t));
    }
  }
```

### Async coalescing
The previous logic can be streamlined if all loads should be collected into batches. This approach
is most suitable for an `AsyncLoadingCache` since it does not block any other map operations while
an entry is being loaded.

```java
public final class CoalescingBulkLoader<K, V> implements AsyncCacheLoader<K, V> {
  private final Function<Set<K>, Map<K, V>> mappingFunction;
  private final Sinks.Many<Request<K, V>> sink;

  public CoalescingBulkLoader(int maxSize, Duration maxTime, int parallelism,
      Function<Set<K>, Map<K, V>> mappingFunction) {
    this.sink = Sinks.many().unicast().onBackpressureBuffer();
    this.mappingFunction = requireNonNull(mappingFunction);
    sink.asFlux()
        .bufferTimeout(maxSize, maxTime)
        .map(requests -> requests.stream().collect(
            toMap(Entry::getKey, Entry::getValue)))
        .parallel(parallelism)
        .runOn(Schedulers.boundedElastic())
        .subscribe(this::handle);
  }

  @Override public synchronized CompletableFuture<V> asyncLoad(K key, Executor e) {
    var entry = Map.entry(key, new CompletableFuture<V>());
    sink.tryEmitNext(entry).orThrow();
    return entry.getValue();
  }

  private void handle(Map<K, CompletableFuture<V>> requests) {
    try {
      var results = mappingFunction.apply(requests.keySet());
      requests.forEach((key, result) -> result.complete(results.get(key)));
    } catch (Throwable t) {
      requests.forEach((key, result) -> result.completeExceptionally(t));
    }
  }
}
```

[reactor]: https://projectreactor.io
[bufferTimeout]: https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html#bufferTimeout-int-java.time.Duration-
[sink]: https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Sinks.html
