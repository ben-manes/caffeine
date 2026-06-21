/*
 * Copyright 2026 Julian Rubin. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.issues;

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Issue #1970: Refresh completion window allowed refreshAfterWrite to schedule a reload before the
 * write to the cache became visible.
 *
 * @author rubin94@gmail.com (Julian Rubin)
 */
final class Issue1970Test {
  private static final Duration DURATION = Duration.ofSeconds(5);
  private static final int NUM_THREADS = 8;

  @Test
  @SuppressFBWarnings("UTAO_JUNIT_ASSERTION_ODDITIES_ASSERT_USED")
  void stressTestRefreshRaceWindow() {
    var loadCount = new AtomicInteger();
    var error = new AtomicReference<AssertionError>();
    var cacheReference = new AtomicReference<LoadingCache<Object, Integer>>();
    LoadingCache<Object, Integer> cache = Caffeine.newBuilder()
        .executor(ConcurrentTestHarness.executor)
        .refreshAfterWrite(Duration.ofMillis(10))
        .build(key -> {
          int n = loadCount.incrementAndGet();
          var currentValue = cacheReference.get().get(key);
          if (currentValue + 1 != n && currentValue != -1) {
            error.set(new AssertionError("Unexpected " + n + " " + currentValue));
          }
          return n;
        });

    cacheReference.set(cache);
    var key = new Object();
    cache.put(key, -1);

    long deadline = System.currentTimeMillis() + DURATION.toMillis();
    ConcurrentTestHarness.timeTasks(NUM_THREADS, () -> {
      while (System.currentTimeMillis() < deadline) {
        assertThat(cache.get(key)).isNotNull();
      }
    });
    assertThat(loadCount.get()).isGreaterThan(1);
    await().until(() -> cache.policy().refreshes().isEmpty());
    if (error.get() != null) {
      throw error.get();
    }
  }
}
