Using [RxJava][rxjava] observable sequences, you can asynchronously write to an external resource
after updating the cache. This approach offers a potential performance boost by batching write
operations, albeit with a trade-off of potential data inconsistency.

A [buffer][] operator gathers changes within a specified time window. This window may encompass
multiple updates to the same key so when merging these changes into a single write any outdated
modifications can be discarded. Subsequently, the subscriber is notified to execute the batch
operation.

```java
var subject = PublishSubject.<Entry<K, V>>create().toSerialized();
subject.buffer(1, TimeUnit.SECONDS)
    .map(entries -> entries.stream().collect(
        toMap(Entry::getKey, Entry::getValue, (v1, v2) -> v2)))
    .subscribeOn(Schedulers.io())
    .subscribe(System.out::println);

Cache<K, V> cache = Caffeine.newBuilder().build();
cache.asMap().compute(key, (k, v) -> {
  var value = /* mutations */
  subject.onNext(Map.entry(key, value));
  return value;
});
```

[rxjava]: https://github.com/ReactiveX/RxJava
[buffer]: http://reactivex.io/RxJava/3.x/javadoc/io/reactivex/rxjava3/core/Observable.html#buffer-long-java.util.concurrent.TimeUnit-
