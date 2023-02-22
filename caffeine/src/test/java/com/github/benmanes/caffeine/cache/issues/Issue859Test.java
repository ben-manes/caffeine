/*
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

import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * Issue #859: Removal listener not called (due to no activity and pending work in write buffer)
 * <p>
 * While a maintenance cycle is running it disables scheduling of a clean up by concurrent writers.
 * When complete the drain status is restored to required and previously this may have had to wait
 * for other activity to trigger the clean up. That could be an excessive delay if the write buffer
 * contains inserted entries, they expire, and a prompt removal notification is expected due to a
 * configured scheduler. To avoid this delay, the maintenance cycle should be scheduled.
 *
 * @author mario-schwede-hivemq (Mario Schwede)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(groups = "isolated")
public final class Issue859Test {
  private static final int NUMBER_OF_RUNS = 100_000;
  private static final int NUMBER_OF_KEYS = 10;

  @Test
  public void scheduleIfPendingWrites() {
    var runs = new ArrayList<TestRun>();
    for (int i = 1; i <= NUMBER_OF_RUNS; i++) {
      runs.add(runTest());
    }
    for (var run : runs) {
      boolean finished = Uninterruptibles.awaitUninterruptibly(run.latch, Duration.ofSeconds(5));
      assertThat(finished).isTrue();
    }
  }

  private TestRun runTest() {
    var latch = new CountDownLatch(NUMBER_OF_KEYS);
    Cache<Integer, Boolean> cache = Caffeine.newBuilder()
        .removalListener((key, value, cause) -> latch.countDown())
        .expireAfterWrite(Duration.ofMillis(5))
        .scheduler(Scheduler.systemScheduler())
        .executor(executor)
        .build();
    for (int i = 0; i < NUMBER_OF_KEYS; i++) {
      var key = i;
      executor.execute(() -> cache.put(key, Boolean.TRUE));
    }

    // The scheduler maintains a weak reference to the cache, so it must be held strongly until the
    // test completes.
    return new TestRun(cache, latch);
  }

  static final class TestRun {
    final Cache<Integer, Boolean> cache;
    final CountDownLatch latch;

    TestRun(Cache<Integer, Boolean> cache, CountDownLatch latch) {
      this.cache = cache;
      this.latch = latch;
    }
  }
}
