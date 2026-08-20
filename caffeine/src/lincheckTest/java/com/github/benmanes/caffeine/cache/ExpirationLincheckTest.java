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

import static com.github.benmanes.caffeine.cache.LincheckOptions.modelChecking;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.lincheck.Lincheck;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Model-checking mirror of {@code ExpirationFrayTest}'s expiring-rewrite probe. A lock-free read
 * judges expiry by the entry's timestamps before loading the value, so a rewrite that announced
 * the old value's expiration cannot lend its fresh timestamps to that stale value. The model
 * checker interleaves at field-access granularity, so this guard outlives the scheduler-based
 * sibling if a node access mode is ever weakened below a synchronization point.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ExpirationLincheckTest {

  @Test
  void getIfPresent_expiringRewrite_neverReturnsExpiredValue() {
    Lincheck.runConcurrentTest(modelChecking().invocationsPerIteration, () -> {
      var nanos = new AtomicLong();
      Cache<Integer, Integer> cache = Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofMinutes(1))
          .executor(Runnable::run)
          .ticker(nanos::get)
          .maximumSize(10)
          .build();
      cache.put(1, 100);
      nanos.set(Duration.ofMinutes(2).toNanos());

      var result = new AtomicReference<@Nullable Integer>();
      var threadA = new Thread(() -> result.set(cache.getIfPresent(1)));
      var threadB = new Thread(() -> cache.put(1, 200));
      try {
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }

      Integer value = result.get();
      if ((value != null) && (value.intValue() != 200)) {
        fail("getIfPresent returned an expired value: " + value);
      }
    });
  }

  /**
   * The loading get's optimistic fast path, whose expiring-rewrite window holds no synchronization
   * point, so only field-granularity interleaving can drive a reader between the value load and
   * the timestamp reads there.
   */
  @Test
  void computeIfAbsent_expiringRewrite_neverReturnsExpiredValue() {
    Lincheck.runConcurrentTest(modelChecking().invocationsPerIteration, () -> {
      var nanos = new AtomicLong();
      Cache<Integer, Integer> cache = Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofMinutes(1))
          .executor(Runnable::run)
          .ticker(nanos::get)
          .maximumSize(10)
          .build();
      cache.put(1, 100);
      nanos.set(Duration.ofMinutes(2).toNanos());

      var result = new AtomicReference<@Nullable Integer>();
      var threadA = new Thread(() -> result.set(cache.asMap().computeIfAbsent(1, k -> 999)));
      var threadB = new Thread(() -> cache.put(1, 200));
      try {
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }

      Integer value = result.get();
      if ((value == null) || ((value.intValue() != 999) && (value.intValue() != 200))) {
        fail("computeIfAbsent returned an expired value: " + value);
      }
    });
  }
}
