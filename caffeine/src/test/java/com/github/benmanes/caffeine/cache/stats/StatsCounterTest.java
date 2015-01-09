/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.stats;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.ConcurrentTestHarness;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class StatsCounterTest {

  @Test
  public void disabled() {
    StatsCounter counter = DisabledStatsCounter.INSTANCE;
    counter.recordHits(1);
    counter.recordMisses(1);
    counter.recordLoadSuccess(1);
    counter.recordLoadFailure(1);
    assertThat(counter.snapshot(), is(new CacheStats(0, 0, 0, 0, 0, 0)));

    for (DisabledStatsCounter type : DisabledStatsCounter.values()) {
      assertThat(DisabledStatsCounter.valueOf(type.toString()), is(counter));
    }
  }

  @Test
  public void enabled() {
    ConcurrentStatsCounter counter = new ConcurrentStatsCounter();
    counter.recordHits(1);
    counter.recordMisses(1);
    counter.recordEviction();
    counter.recordLoadSuccess(1);
    counter.recordLoadFailure(1);
    assertThat(counter.snapshot(), is(new CacheStats(1, 1, 1, 1, 2, 1)));

    counter.incrementBy(counter);
    assertThat(counter.snapshot(), is(new CacheStats(2, 2, 2, 2, 4, 2)));
  }

  @Test
  public void concurrent() {
    StatsCounter counter = new ConcurrentStatsCounter();
    ConcurrentTestHarness.timeTasks(5, () -> {
      counter.recordHits(1);
      counter.recordMisses(1);
      counter.recordEviction();
      counter.recordLoadSuccess(1);
      counter.recordLoadFailure(1);
    });
    assertThat(counter.snapshot(), is(new CacheStats(5, 5, 5, 5, 10, 5)));
  }
}
