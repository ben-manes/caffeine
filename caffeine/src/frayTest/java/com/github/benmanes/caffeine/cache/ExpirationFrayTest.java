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

import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;

import com.google.common.testing.FakeTicker;

/** Fray concurrency tests for Caffeine's expiration subsystem. */
@ExtendWith(FrayTestExtension.class)
@SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
final class ExpirationFrayTest {

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void replace_schedulesCleanup() throws InterruptedException {
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

    assertThat(cache).isValid();
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void computeIfAbsent_handlesExpired() throws InterruptedException {
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
    assertThat(cache).isValid();
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void compute_twoThreads() throws InterruptedException {
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
    assertThat(cache).isValid();
  }

  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void put_fastPath_vs_slowPath() throws InterruptedException {
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
    assertThat(cache).isValid();
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
    assertThat(cache).isValid();
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
    assertThat(cache).isValid();
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
    assertThat(cache).isValid();
  }

  /* --------------- Variable Expiry Variants --------------- */

  /** Variable expiry variant of expiredEntry_computeIfAbsent_handlesExpired. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void computeIfAbsent_variableExpiry() throws InterruptedException {
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
    assertThat(cache).isValid();
  }

  /**
   * computeIfAbsent must never return an entry that is already expired. Every value is born expired
   * (expireAfterCreate = 0), so the only correct result is the freshly computed 999 — returning the
   * 100 inserted by the other thread means the existing-entry expiry check used a stale clock that
   * predated the insertion (see doComputeIfAbsent, which must re-read now like remap does).
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void computeIfAbsent_concurrentInsert_staleExpiryClock() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((k, v) -> Duration.ZERO))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();

    var result = new AtomicReference<Integer>();
    var threadA = new Thread(() -> result.set(cache.asMap().computeIfAbsent(1, k -> 999)));
    var threadB = new Thread(() -> {
      ticker.advance(Duration.ofMinutes(1));
      cache.put(1, 100);
    });

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();

    assertWithMessage("computeIfAbsent returned an expired value")
        .that(result.get()).isEqualTo(999);
    assertThat(cache).isValid();
  }

  /** Weighted + expiration variant — tests weight accounting on expired entry compute. */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void compute_weighted() throws InterruptedException {
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
    assertThat(cache).isValid();
  }

  /**
   * A read must never return a value whose expiration a concurrent rewrite already announced. The
   * entry is expired before the threads start, so the read may only miss or observe the rewrite;
   * returning the original value means the reader loaded it before the rewrite and then judged
   * expiry by the rewrite's fresh write time (see hasExpired, which the reader must consult before
   * loading the value). Failed on the first iteration before the reads were reordered.
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void getIfPresent_expiringRewrite_neverReturnsExpiredValue() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var result = new AtomicReference<@Nullable Integer>();
    var threadA = new Thread(() -> result.set(cache.getIfPresent(1)));
    var threadB = new Thread(() -> cache.put(1, 200));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertWithMessage("getIfPresent returned an expired value")
        .that(result.get()).isAnyOf(null, 200);
    assertThat(cache).isValid();
  }

  /**
   * putIfAbsent's optimistic fast path must never treat an expired entry as present. The entry is
   * expired before the threads start, so the call may only install its own value or observe the
   * rewrite; returning the original value means the fast path judged the stale value by the
   * rewrite's fresh timestamps. Exercises the negated presence test that the getIfPresent probe
   * does not reach.
   */
  @FrayTest(iterations = 10_000, resetClassLoaderPerIteration = false)
  void putIfAbsent_expiringRewrite_neverReturnsExpiredValue() throws InterruptedException {
    var ticker = new FakeTicker();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .maximumSize(10)
        .build();
    cache.put(1, 100);
    ticker.advance(Duration.ofMinutes(2));

    var result = new AtomicReference<@Nullable Integer>();
    var threadA = new Thread(() -> result.set(cache.asMap().putIfAbsent(1, 999)));
    var threadB = new Thread(() -> cache.put(1, 200));

    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
    cache.cleanUp();

    assertWithMessage("putIfAbsent returned an expired value")
        .that(result.get()).isAnyOf(null, 200);
    assertThat(cache).isValid();
  }
}
