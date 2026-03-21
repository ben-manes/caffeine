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

import java.time.Duration;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import com.google.common.testing.FakeTicker;

/** Fray concurrency tests for Caffeine's expiration subsystem. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
final class ExpirationFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiredEntry_replace_schedulesCleanup() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.asMap().replace(1, 999));
    var threadB = new Thread(() -> cache.getIfPresent(1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiredEntry_computeIfAbsent_handlesExpired() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.asMap().computeIfAbsent(1, k -> 999));
    var threadB = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    if (value != null) {
      assertThat(value).isEqualTo(999);
    }
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiredEntry_compute_twoThreads() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.asMap().compute(1, (k, v) -> (v == null ? 0 : v) + 1));
    var threadB = new Thread(() -> cache.asMap().compute(1, (k, v) -> (v == null ? 0 : v) + 1));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    var value = cache.getIfPresent(1);
    assertThat(value).isNotNull();
    assertThat(value).isEqualTo(2);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiredEntry_put_fastPath_vs_slowPath() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.put(1, 200));
    var threadB = new Thread(() -> cache.put(1, 300));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    assertThat(value).isNotNull();
    assertWithMessage("Key 1 should be 200 or 300, but was %s", value)
        .that(value).isAnyOf(200, 300);
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expireAfterAccess_readExtends_vs_eviction() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(3)
        .build();
    cache.put(1, 1);
    cache.put(2, 2);
    cache.put(3, 3);

    var threadA = new Thread(() -> cache.getIfPresent(1));
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
  void variableExpiry_updateDuration_concurrentCleanup() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfter(new Expiry<Integer, Integer>() {
          @Override public long expireAfterCreate(Integer key, Integer value, long currentTime) {
            return Duration.ofMinutes(10).toNanos();
          }
          @Override public long expireAfterUpdate(Integer key, Integer value,
              long currentTime, long currentDuration) {
            return 1L;
          }
          @Override public long expireAfterRead(Integer key, Integer value,
              long currentTime, long currentDuration) {
            return currentDuration;
          }
        })
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);

    var threadA = new Thread(() -> cache.put(1, 200));
    var threadB = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    ticker.advance(Duration.ofSeconds(1));
    cache.cleanUp();

    assertThat(cache.getIfPresent(1)).isNull();
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void variableExpiry_zeroDuration_immediateExpiry() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((Integer key, Integer value) ->
            Duration.ofNanos((key == 1) ? 0L : (Long.MAX_VALUE / 2))))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();

    var threadA = new Thread(() -> cache.put(1, 100));
    var threadB = new Thread(() -> cache.put(2, 200));
    var threadC = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadC.start();
    threadA.join();
    threadB.join();
    threadC.join();
    cache.cleanUp();

    assertThat(cache.getIfPresent(1)).isNull();
    assertThat(cache.getIfPresent(2)).isNotNull();
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /* --------------- Variable Expiry Variants --------------- */

  /** Variable expiry variant of expiredEntry_computeIfAbsent_handlesExpired. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiredEntry_computeIfAbsent_variableExpiry() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.writing((key, value) -> Duration.ofMinutes(1)))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.asMap().computeIfAbsent(1, k -> 999));
    var threadB = new Thread(cache::cleanUp);

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    var value = cache.getIfPresent(1);
    if (value != null) {
      assertThat(value).isEqualTo(999);
    }
    assertThat(cache.estimatedSize()).isEqualTo(cache.asMap().size());
  }

  /** Weighted + expiration variant — tests weight accounting on expired entry compute. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void expiredEntry_compute_weighted() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .weigher((Integer k, Integer v) -> v)
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumWeight(100)
        .build();
    cache.put(1, 10);
    ticker.advance(Duration.ofMinutes(2));

    var threadA = new Thread(() -> cache.asMap().compute(1, (k, v) -> 20));
    var threadB = new Thread(cache::cleanUp);

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
