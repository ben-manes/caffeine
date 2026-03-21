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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

/** Fray concurrency tests for Caffeine's eviction subsystem. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
final class EvictionFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void resurrection_computeIfAbsent() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> {
      cache.put(4, 4);
      cache.put(5, 5);
    });
    var threadB = new Thread(() -> cache.asMap().computeIfAbsent(1, k -> k * 10));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void resurrection_put() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> {
      cache.put(4, 4);
      cache.put(5, 5);
    });
    var threadB = new Thread(() -> {
      cache.put(1, 999);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
    var value = cache.getIfPresent(1);
    if (value != null) {
      assertThat(value).isEqualTo(999);
    }
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void concurrentRemove_sameKey() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> {
      cache.put(4, 4);
      cache.put(5, 5);
    });
    var threadB = new Thread(() -> {
      cache.invalidate(1);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.getIfPresent(1)).isNull();
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void weightChange_duringEviction() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumWeight(20)
        .weigher((Integer k, Integer v) -> v)
        .build();
    cache.put(1, 5);
    cache.put(2, 5);
    cache.put(3, 5);
    cache.put(4, 5);

    var threadA = new Thread(() -> {
      cache.put(5, 8);
    });
    var threadB = new Thread(() -> {
      cache.put(1, 1);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(reportedWeight).isAtMost(20);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void weightConvergence_rapidUpdates() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumWeight(100)
        .weigher((Integer k, Integer v) -> v)
        .build();
    cache.put(1, 5);

    var threadA = new Thread(() -> {
      for (int i = 0; i < 5; i++) {
        cache.put(1, 20);
        cache.put(1, 1);
      }
    });
    var threadB = new Thread(() -> {
      for (int i = 0; i < 5; i++) {
        cache.cleanUp();
      }
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void zeroWeight_neverEvicted() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumWeight(10)
        .weigher((Integer k, Integer v) -> v)
        .build();
    cache.put(0, 0);
    cache.put(1, 5);
    cache.put(2, 5);

    var threadA = new Thread(() -> cache.put(3, 8));
    var threadB = new Thread(() -> cache.getIfPresent(0));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertWithMessage("Zero-weight entry must not be evicted")
        .that(cache.getIfPresent(0)).isNotNull();
    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    assertThat(reportedWeight).isAtMost(10);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void singleEntry_contention() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(1)
        .build();

    var threadA = new Thread(() -> {
      cache.put(1, 1);
      cache.put(2, 2);
    });
    var threadB = new Thread(() -> {
      cache.put(3, 3);
      cache.put(4, 4);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(1);
    assertThat(cache.asMap().size()).isEqualTo(1);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void accessOrder_promotion() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(5)
        .build();
    for (int i = 1; i <= 5; i++) {
      cache.put(i, i);
    }

    var threadA = new Thread(() -> cache.getIfPresent(1));
    var threadB = new Thread(() -> {
      cache.put(6, 6);
      cache.put(7, 7);
      cache.put(8, 8);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isAtMost(5);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /* --------------- Weighted Variants --------------- */

  /** Weighted variant of eviction_resurrection_computeIfAbsent. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void resurrection_computeIfAbsent_weighted() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .weigher((Integer k, Integer v) -> v)
        .executor(Runnable::run)
        .maximumWeight(15)
        .build();
    cache.put(1, 5);
    cache.put(2, 5);
    cache.put(3, 5);

    var threadA = new Thread(() -> cache.put(4, 8));
    var threadB = new Thread(() -> cache.asMap().computeIfAbsent(1, k -> 5));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(reportedWeight).isAtMost(15);
  }

  /** Weighted variant of eviction_resurrection_put. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void resurrection_put_weighted() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .weigher((Integer k, Integer v) -> v)
        .executor(Runnable::run)
        .maximumWeight(15)
        .build();
    cache.put(1, 5);
    cache.put(2, 5);
    cache.put(3, 5);

    var threadA = new Thread(() -> cache.put(4, 8));
    var threadB = new Thread(() -> cache.put(1, 3));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(reportedWeight).isAtMost(15);
  }

  /** Eviction with removal listener verifying notification consistency. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void removalListener_consistency() throws InterruptedException {
    var notifications = new ConcurrentLinkedQueue<RemovalCause>();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .removalListener((key, value, cause) -> notifications.add(cause))
        .executor(Runnable::run)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> {
      cache.put(4, 4);
      cache.put(5, 5);
    });
    var threadB = new Thread(() -> cache.invalidate(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
    for (var cause : notifications) {
      assertWithMessage("Unexpected removal cause: %s", cause)
          .that(cause).isAnyOf(RemovalCause.SIZE, RemovalCause.EXPLICIT);
    }
  }

  /**
   * clear() releases evictionLock then removes stragglers via data.computeIfPresent outside
   * the lock. A concurrent put can re-add a key that clear is about to remove as a straggler,
   * and the write buffer task ordering (RemovalTask from clear vs AddTask from put) depends
   * on scheduling.
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void clear_concurrentPut() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .build();
    for (int i = 0; i < 5; i++) {
      cache.put(i, i);
    }

    var threadA = new Thread(cache::invalidateAll);
    var threadB = new Thread(() -> {
      cache.put(1, 100);
      cache.put(6, 600);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /**
   * Two threads putting different new keys when the cache is at capacity. Both trigger eviction
   * and compete for evictionLock. The victim selection in evictFromMain reads deque state that
   * the other thread's AddTask may have modified.
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void multiKey_concurrentEviction() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(5)
        .build();
    for (int i = 1; i <= 5; i++) {
      cache.put(i, i);
    }

    var threadA = new Thread(() -> {
      cache.put(10, 10);
      cache.put(11, 11);
    });
    var threadB = new Thread(() -> {
      cache.put(20, 20);
      cache.put(21, 21);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isAtMost(5);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /** Weighted variant of multi-key concurrent eviction. Verifies weight convergence. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void multiKey_concurrentEviction_weighted() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .weigher((Integer k, Integer v) -> v)
        .executor(Runnable::run)
        .maximumWeight(25)
        .build();
    for (int i = 1; i <= 5; i++) {
      cache.put(i, 5);
    }

    var threadA = new Thread(() -> {
      cache.put(10, 8);
      cache.put(11, 8);
    });
    var threadB = new Thread(() -> {
      cache.put(20, 7);
      cache.put(21, 7);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    long reportedWeight = cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(reportedWeight).isEqualTo(actualWeight);
    assertThat(reportedWeight).isAtMost(25);
  }
}
