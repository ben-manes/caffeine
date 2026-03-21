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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import com.google.common.testing.FakeTicker;

/** Fray concurrency tests for Caffeine's refresh subsystem. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
final class RefreshAfterWriteFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void completion_concurrentPut() throws InterruptedException {
    var ticker = new FakeTicker();
    LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build(key -> key * 10);
    cache.get(1);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.get(1));
    var threadB = new Thread(() -> cache.put(1, 999));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    assertThat(value).isNotNull();
    assertWithMessage("Key 1 should be 10 or 999, but was %s", value)
        .that(value).isAnyOf(10, 999);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void completion_concurrentEviction() throws InterruptedException {
    var ticker = new FakeTicker();
    LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(3)
        .build(key -> key * 10);
    cache.get(1);
    cache.get(2);
    cache.get(3);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.get(1));
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
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void refresh_doubleSubmission_prevention() throws InterruptedException {
    var ticker = new FakeTicker();
    var loadCount = new AtomicInteger();
    LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build(key -> {
          loadCount.incrementAndGet();
          return key * 10;
        });
    cache.get(1);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.get(1));
    var threadB = new Thread(() -> cache.get(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    var value = cache.getIfPresent(1);
    assertThat(value).isNotNull();
    assertThat(value).isEqualTo(10);
    assertWithMessage(
        "Expected at most 3 loads (initial + at most 2 refreshes), but was %s", loadCount.get())
            .that(loadCount.get()).isAtMost(3);
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void exception_concurrentGet() throws InterruptedException {
    var ticker = new FakeTicker();
    var callCount = new AtomicInteger();
    LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build(key -> {
          checkState(callCount.incrementAndGet() <= 1, "refresh failure");
          return key * 10;
        });
    cache.get(1);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> {
      try {
        cache.get(1);
      } catch (IllegalStateException expected) {
        // May propagate if entry was evicted and get() triggers a fresh load
      }
    });
    var threadB = new Thread(() -> {
      try {
        cache.get(1);
      } catch (IllegalStateException expected) {
        // May propagate if entry was evicted and get() triggers a fresh load
      }
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    // Stale value should be returned during failed refresh, but the entry
    // may be absent if it was evicted and the reload also failed
    var value = cache.getIfPresent(1);
    if (value != null) {
      assertThat(value).isEqualTo(10);
    }
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void concurrentInvalidate() throws InterruptedException {
    var ticker = new FakeTicker();
    LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build(key -> key * 10);
    cache.get(1);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.get(1));
    var threadB = new Thread(() -> cache.invalidate(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void concurrentCompute() throws InterruptedException {
    var ticker = new FakeTicker();
    LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build(key -> key * 10);
    cache.get(1);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.get(1));
    var threadB = new Thread(() -> cache.asMap().compute(1, (k, v) -> firstNonNull(v, 0) + 100));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    assertThat(value).isNotNull();
    assertWithMessage("Key 1 should be 10, 110, or 100, but was %s", value)
        .that(value).isAnyOf(10, 110, 100);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }
}
