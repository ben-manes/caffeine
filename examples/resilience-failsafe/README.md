[Failsafe's][failsafe] retry, timeout, and fallback strategies can be used to make the cache
operations resiliant to intermittent failures.

### Retry
A [retry policy][retry] will retry failed executions a certain number of times, with an optional
delay between attempts.

```java
var retryPolicy = RetryPolicy.builder()
    .withDelay(Duration.ofSeconds(1))
    .withMaxAttempts(3)
    .build();
var failsafe = Failsafe.with(retryPolicy);

// Retry outside of the cache loader for synchronous calls
Cache<K, V> cache = Caffeine.newBuilder().build();
failsafe.get(() -> cache.get(key, key -> /* intermittent failures */ ));

// Optionally, retry inside the cache load for asynchronous calls
AsyncCache<K, V> asyncCache = Caffeine.newBuilder().buildAsync();
asyncCache.get(key, (key, executor) -> failsafe.getAsync(() -> /* intermittent failure */ ));
```

### Timeout
A [timeout policy][timeout] will cancel the execution if it takes too long to complete.

```java
var timeout = Timeout.builder(Duration.ofSeconds(10)).withInterrupt().build();
var failsafe = Failsafe.with(timeout, retryPolicy);

Cache<K, V> cache = Caffeine.newBuilder().build();
failsafe.get(() -> cache.get(key, key -> /* timeout */ ));
```

### Fallback
A [fallback policy][fallback] will provide an alternative result for a failed execution.

```java
var fallback = Fallback.of(/* fallback */);
var failsafe = Failsafe.with(fallback, timeout, retryPolicy);

Cache<K, V> cache = Caffeine.newBuilder().build();
failsafe.get(() -> cache.get(key, key -> /* failure */ ));
```

[failsafe]: https://failsafe.dev
[retry]: https://failsafe.dev/retry
[timeout]: https://failsafe.dev/timeout
[fallback]: https://failsafe.dev/fallback
