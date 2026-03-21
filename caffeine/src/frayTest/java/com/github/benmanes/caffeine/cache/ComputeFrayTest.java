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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import com.google.common.testing.FakeTicker;

/** Fray concurrency tests for Caffeine's compute/merge exception handling paths. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored", "PMD.ExceptionAsFlowControl"})
final class ComputeFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void doComputeIfAbsent_mappingException_onExpiredEntry() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> {
      try {
        cache.asMap().computeIfAbsent(1, k -> {
          throw new IllegalStateException("test");
        });
      } catch (IllegalStateException expected) { /* ignored */ }
    });
    var threadB = new Thread(() -> cache.getIfPresent(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void remap_exception_onExpiredEntry_wasEvictedFlag() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> {
      try {
        cache.asMap().compute(1, (k, v) -> {
          throw new IllegalStateException("test");
        });
      } catch (IllegalStateException expected) { /* ignored */ }
    });
    var threadB = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void remap_weigherException_onExpiredWeightedEntry() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .maximumWeight(100)
        .weigher((Integer k, Integer v) -> {
          checkState(v != -1, "weigher failure");
          return v;
        })
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .build();
    cache.put(1, 10);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> {
      try {
        cache.asMap().compute(1, (k, v) -> -1);
      } catch (IllegalStateException expected) {
        // expected
      }
    });
    var threadB = new Thread(() -> cache.getIfPresent(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void doComputeIfAbsent_expiryException_onNewEntry() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((Integer key, Integer value) -> {
          checkState(key != 99, "expiry failure");
          return Duration.ofMinutes(5);
        }))
        .executor(Runnable::run)
        .maximumSize(10)
        .build();

    var threadA = new Thread(() -> {
      try {
        cache.asMap().computeIfAbsent(99, k -> 100);
      } catch (IllegalStateException expected) {
        // expected
      }
    });
    var threadB = new Thread(() -> cache.getIfPresent(99));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.getIfPresent(99)).isNull();
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void compute_sameInstance_setValue_skip() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .build();
    cache.put(1, 100);

    var threadA = new Thread(() -> cache.asMap().compute(1, (k, v) -> v));
    var threadB = new Thread(() -> cache.getIfPresent(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    assertThat(cache.getIfPresent(1)).isEqualTo(100);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void compute_nullReturn_concurrentPut() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .build();
    cache.put(1, 100);

    var threadA = new Thread(() -> cache.asMap().compute(1, (k, v) -> null));
    var threadB = new Thread(() -> {
      cache.put(1, 200);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    if (value != null) {
      assertWithMessage("Key 1 should be absent or have value 200, but was %s", value)
          .that(value).isEqualTo(200);
    }
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void merge_concurrentRemove() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .build();
    cache.put(1, 100);

    var threadA = new Thread(() -> cache.asMap().merge(1, 50, Integer::sum));
    var threadB = new Thread(() -> cache.invalidate(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    if (value != null) {
      assertWithMessage("Key 1 should be absent, 50, or 150, but was %s", value)
          .that(value).isAnyOf(50, 150);
    }
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void computeIfAbsent_fastPath_evictionRace() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> cache.asMap().computeIfAbsent(1, k -> 999));
    var threadB = new Thread(() -> {
      cache.put(4, 4);
      cache.put(5, 5);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isAtMost(3);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
    var value = cache.getIfPresent(1);
    if (value != null) {
      assertWithMessage("Key 1 should be 1 or 999, but was %s", value).that(value).isAnyOf(1, 999);
    }
  }

  /* --------------- Configuration Variants --------------- */

  /** Weighted variant of doComputeIfAbsent_mappingException_onExpiredEntry. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void doComputeIfAbsent_mappingException_onExpiredEntry_weighted()
      throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumWeight(100)
        .weigher((Integer k, Integer v) -> v)
        .build();
    cache.put(1, 10);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> {
      try {
        cache.asMap().computeIfAbsent(1, k -> {
          throw new IllegalStateException("test");
        });
      } catch (IllegalStateException expected) { /* ignored */ }
    });
    var threadB = new Thread(() -> cache.getIfPresent(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /** Eviction listener variant verifying notification consistency on exception. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void remap_exception_onExpiredEntry_withListener() throws InterruptedException {
    var ticker = new FakeTicker();
    var evictions = new AtomicInteger();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .evictionListener((key, value, cause) -> evictions.incrementAndGet())
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> {
      try {
        cache.asMap().compute(1, (k, v) -> {
          throw new IllegalStateException("test");
        });
      } catch (IllegalStateException expected) { /* ignored */ }
    });
    var threadB = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
    // The expired entry should produce at most one eviction notification
    assertWithMessage("Expected at most 1 eviction, but was %s", evictions.get())
        .that(evictions.get()).isAtMost(1);
  }

  /**
   * putIfAbsent fast path reads {@code !prior.isAlive()} without the node lock, enters the
   * spin-wait loop, then falls back to computeIfPresent. A concurrent put for the same key
   * races through the three-phase path.
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void putIfAbsent_spinWait_concurrentEviction() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> cache.asMap().putIfAbsent(1, 100));
    var threadB = new Thread(() -> {
      // Trigger eviction that may target key 1, causing its node to retire
      cache.put(4, 4);
      cache.put(5, 5);
    });
    var threadC = new Thread(() -> cache.put(1, 200));

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isAtMost(3);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /**
   * remap's removal path through {@code compute(k, (k,v) -> null)} on a weighted entry has
   * different weight accounting than explicit invalidate. A concurrent put for the same key
   * races with the RemovalTask/AddTask ordering in the write buffer.
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void compute_nullReturn_weightedEntry_concurrentPut() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumWeight(50)
        .weigher((Integer k, Integer v) -> v)
        .build();
    cache.put(1, 10);
    cache.put(2, 10);

    var threadA = new Thread(() -> cache.asMap().compute(1, (k, v) -> null));
    var threadB = new Thread(() -> cache.put(1, 15));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }
}
