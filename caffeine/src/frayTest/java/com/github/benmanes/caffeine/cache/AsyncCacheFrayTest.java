/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.function.Function.identity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import com.google.common.testing.FakeTicker;

/** Fray concurrency tests for Caffeine's async cache subsystem. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
final class AsyncCacheFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void completion_triggersReplace() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .buildAsync();
    var future = new CompletableFuture<Integer>();
    cache.put(1, future);

    var threadA = new Thread(() -> future.complete(42));
    var threadB = new Thread(() -> cache.synchronous().getIfPresent(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    assertThat(cache.synchronous().getIfPresent(1)).isEqualTo(42);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void exceptionalCompletion_cleanup() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .buildAsync();
    var future = new CompletableFuture<Integer>();
    cache.put(1, future);

    var threadA = new Thread(() -> future.completeExceptionally(new IllegalStateException("fail")));
    var threadB = new Thread(() -> cache.get(1, k -> 99));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    var value = cache.synchronous().getIfPresent(1);
    if (value != null) {
      assertWithMessage("Key 1 should be absent or have value 99, but was %s", value)
          .that(value).isEqualTo(99);
    }
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void completion_duringEviction() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .buildAsync();
    cache.put(1, CompletableFuture.completedFuture(1));
    cache.put(2, CompletableFuture.completedFuture(2));
    var future = new CompletableFuture<Integer>();
    cache.put(3, future);

    var threadA = new Thread(() -> future.complete(42));
    var threadB = new Thread(() -> {
      cache.put(4, CompletableFuture.completedFuture(4));
      cache.put(5, CompletableFuture.completedFuture(5));
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    assertThat(cache.synchronous().estimatedSize()).isAtMost(3);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void completion_concurrentPut() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .buildAsync();
    var future = new CompletableFuture<Integer>();
    cache.put(1, future);

    var threadA = new Thread(() -> future.complete(42));
    var threadB = new Thread(() -> cache.put(1, CompletableFuture.completedFuture(99)));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    var value = cache.synchronous().getIfPresent(1);
    assertThat(value).isNotNull();
    assertWithMessage("Key 1 should be 42 or 99, but was %s", value)
        .that(value).isAnyOf(42, 99);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void bulkLoad_concurrentSingleGet() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .buildAsync();

    var threadA = new Thread(() -> {
      cache.getAll(List.of(1, 2, 3), keys ->
          keys.stream().collect(toImmutableMap(identity(), key -> key * 10)));
    });
    var threadB = new Thread(() -> cache.get(2, k -> 20));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    assertThat(cache.synchronous().getIfPresent(1)).isNotNull();
    assertThat(cache.synchronous().getIfPresent(2)).isNotNull();
    assertThat(cache.synchronous().getIfPresent(3)).isNotNull();
    assertThat(cache.synchronous().getIfPresent(2)).isEqualTo(20);
    assertThat(cache.synchronous().getIfPresent(1)).isEqualTo(10);
    assertThat(cache.synchronous().getIfPresent(3)).isEqualTo(30);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void bulkLoad_threeWayOverlap() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(20)
        .buildAsync();

    var threadA = new Thread(() -> {
      cache.getAll(List.of(1, 2, 3), keys ->
          keys.stream().collect(toImmutableMap(identity(), key -> key * 10)));
    });
    var threadB = new Thread(() -> {
      cache.getAll(List.of(2, 3, 4), keys ->
          keys.stream().collect(toImmutableMap(identity(), key -> key * 10)));
    });
    var threadC = new Thread(() -> {
      cache.getAll(List.of(3, 4, 5), keys ->
          keys.stream().collect(toImmutableMap(identity(), key -> key * 10)));
    });

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();

    for (int k = 1; k <= 5; k++) {
      assertThat(cache.synchronous().getIfPresent(k)).isEqualTo(k * 10);
    }
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void weight_transition_zeroToReal() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .weigher((Integer k, Integer v) -> v)
        .executor(Runnable::run)
        .maximumWeight(50)
        .buildAsync();

    var threadA = new Thread(() -> cache.get(1, k -> 5));
    var threadB = new Thread(() -> cache.synchronous().cleanUp());

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    assertThat(cache.synchronous().getIfPresent(1)).isEqualTo(5);
    long reportedWeight = cache.synchronous().policy().eviction()
        .orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.synchronous().asMap().values().stream()
        .mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiry_delegation() throws InterruptedException {
    var ticker = new FakeTicker();
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((key, value) -> Duration.ofMinutes(10)))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .buildAsync();
    var future = new CompletableFuture<Integer>();
    cache.put(1, future);

    var threadA = new Thread(() -> future.complete(42));
    var threadB = new Thread(() -> cache.synchronous().cleanUp());

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    assertThat(cache.synchronous().getIfPresent(1)).isNotNull();

    ticker.advance(Duration.ofMinutes(15));
    cache.synchronous().cleanUp();

    assertThat(cache.synchronous().getIfPresent(1)).isNull();
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void cancellation_cleanup() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .buildAsync();
    var future = new CompletableFuture<Integer>();
    cache.put(1, future);

    var threadA = new Thread(() -> future.cancel(true));
    var threadB = new Thread(() -> cache.get(1, k -> 99));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    var value = cache.synchronous().getIfPresent(1);
    if (value != null) {
      assertWithMessage("Key 1 should be absent or have value 99, but was %s", value)
          .that(value).isEqualTo(99);
    }
  }

  /* --------------- Async Eviction Variants --------------- */

  /** Async variant of eviction_resurrection_computeIfAbsent. In-flight futures use ASYNC_EXPIRY. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void eviction_resurrection_computeIfAbsent() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .buildAsync();
    cache.put(1, CompletableFuture.completedFuture(1));
    cache.put(2, CompletableFuture.completedFuture(2));
    cache.put(3, CompletableFuture.completedFuture(3));

    var threadA = new Thread(() -> {
      cache.put(4, CompletableFuture.completedFuture(4));
      cache.put(5, CompletableFuture.completedFuture(5));
    });
    var threadB = new Thread(() -> cache.get(1, k -> 10));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    assertThat(cache.synchronous().estimatedSize()).isEqualTo(cache.synchronous().asMap().size());
  }

  /** Async weighted variant — tests AsyncWeigher weight=0 for in-flight combined with eviction. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void weighted_eviction_convergence() throws InterruptedException {
    AsyncCache<Integer, Integer> cache = Caffeine.newBuilder()
        .weigher((Integer k, Integer v) -> v)
        .executor(Runnable::run)
        .maximumWeight(20)
        .buildAsync();
    cache.put(1, CompletableFuture.completedFuture(5));
    cache.put(2, CompletableFuture.completedFuture(5));
    cache.put(3, CompletableFuture.completedFuture(5));

    var threadA = new Thread(() -> cache.get(4, k -> 8));
    var threadB = new Thread(() -> cache.put(1, CompletableFuture.completedFuture(1)));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.synchronous().cleanUp();

    long reportedWeight = cache.synchronous().policy().eviction()
        .orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.synchronous().asMap().values().stream()
        .mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(reportedWeight).isAtMost(20);
  }
}
