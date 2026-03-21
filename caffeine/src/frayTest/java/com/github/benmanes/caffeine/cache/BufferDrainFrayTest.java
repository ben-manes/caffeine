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

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

/** Fray concurrency tests for Caffeine's write buffer and drain status state machine. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings("FutureReturnValueIgnored")
final class BufferDrainFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void writeBuffer_contention_threeWriters() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(100)
        .build();

    var threadA = new Thread(() -> {
      for (int i = 0; i < 10; i++) {
        cache.put(i, i);
      }
    });
    var threadB = new Thread(() -> {
      for (int i = 10; i < 20; i++) {
        cache.put(i, i);
      }
    });
    var threadC = new Thread(() -> {
      for (int i = 20; i < 30; i++) {
        cache.put(i, i);
      }
    });

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();
    cache.cleanUp();

    for (int i = 0; i < 30; i++) {
      assertWithMessage("Key %s should be present", i).that(cache.getIfPresent(i)).isNotNull();
    }
    assertThat(cache.estimatedSize()).isEqualTo(30);
    assertThat(cache.asMap().size()).isEqualTo(cache.estimatedSize());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void drainStatus_processingToRequired_transition() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .build();
    for (int i = 0; i < 5; i++) {
      cache.put(i, i);
    }

    var threadA = new Thread(() -> cache.put(5, 5));
    var threadB = new Thread(() -> cache.put(6, 6));
    var threadC = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();
    cache.cleanUp();

    assertThat(cache.getIfPresent(5)).isNotNull();
    assertThat(cache.getIfPresent(6)).isNotNull();
    assertThat(cache.asMap().size()).isEqualTo(cache.estimatedSize());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void afterWrite_inlineFallback() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(5)
        .build();

    var threadA = new Thread(() -> {
      for (int i = 0; i < 20; i++) {
        cache.put(i, i);
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

    assertThat(cache.estimatedSize()).isAtMost(5);
    assertThat(cache.asMap().size()).isEqualTo(cache.estimatedSize());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void addTask_removeTask_outOfOrder() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumSize(10)
        .build();

    var threadA = new Thread(() -> cache.put(1, 100));
    var threadB = new Thread(() -> cache.invalidate(1));
    var threadC = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();
    cache.cleanUp();

    // Key 1 may be present (if invalidate ran before put) or absent (if invalidate ran after put)
    assertThat(cache.asMap().size()).isEqualTo(cache.estimatedSize());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void updateTask_weightDelta_convergence() throws InterruptedException {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .maximumWeight(100)
        .weigher((Integer k, Integer v) -> v)
        .build();
    cache.put(1, 10);

    var threadA = new Thread(() -> cache.put(1, 20));
    var threadB = new Thread(() -> cache.put(1, 5));
    var threadC = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();
    cache.cleanUp();

    assertThat(cache.getIfPresent(1)).isNotNull();
    int actualValue = cache.getIfPresent(1);
    assertWithMessage("Key 1 value should be 20 or 5, but was %s", actualValue)
        .that(actualValue).isAnyOf(20, 5);
    long weightedSize = cache.policy().eviction().orElseThrow()
        .weightedSize().orElseThrow();
    int actualWeight = cache.asMap().values().stream().mapToInt(Integer::intValue).sum();
    assertThat(weightedSize).isEqualTo(actualWeight);
  }
}
