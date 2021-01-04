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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("deprecation")
public final class StatsCounterTest {

  @Test
  public void disabled() {
    StatsCounter counter = DisabledStatsCounter.INSTANCE;
    counter.recordHits(1);
    counter.recordMisses(1);
    counter.recordEviction(1, RemovalCause.SIZE);
    counter.recordLoadSuccess(1);
    counter.recordLoadFailure(1);
    assertThat(counter.snapshot(), is(CacheStats.of(0, 0, 0, 0, 0, 0, 0)));
    assertThat(counter.toString(), is(CacheStats.of(0, 0, 0, 0, 0, 0, 0).toString()));

    for (DisabledStatsCounter type : DisabledStatsCounter.values()) {
      assertThat(DisabledStatsCounter.valueOf(type.name()), is(counter));
    }
  }

  @Test
  public void enabled() {
    ConcurrentStatsCounter counter = new ConcurrentStatsCounter();
    counter.recordHits(1);
    counter.recordMisses(1);
    counter.recordEviction(10, RemovalCause.SIZE);
    counter.recordLoadSuccess(1);
    counter.recordLoadFailure(1);
    CacheStats expected = CacheStats.of(1, 1, 1, 1, 2, 1, 10);
    assertThat(counter.snapshot(), is(expected));
    assertThat(counter.toString(), is(expected.toString()));
    assertThat(counter.snapshot().toString(), is(expected.toString()));

    counter.incrementBy(counter);
    assertThat(counter.snapshot(), is(CacheStats.of(2, 2, 2, 2, 4, 2, 20)));
  }

  @Test
  public void concurrent() {
    StatsCounter counter = new ConcurrentStatsCounter();
    ConcurrentTestHarness.timeTasks(5, () -> {
      counter.recordHits(1);
      counter.recordMisses(1);
      counter.recordEviction(10, RemovalCause.SIZE);
      counter.recordLoadSuccess(1);
      counter.recordLoadFailure(1);
    });
    assertThat(counter.snapshot(), is(CacheStats.of(5, 5, 5, 5, 10, 5, 50)));
  }

  @Test
  public void guarded() {
    StatsCounter counter = StatsCounter.guardedStatsCounter(new ConcurrentStatsCounter());
    counter.recordHits(1);
    counter.recordMisses(1);
    counter.recordEviction(10, RemovalCause.SIZE);
    counter.recordLoadSuccess(1);
    counter.recordLoadFailure(1);
    CacheStats expected = CacheStats.of(1, 1, 1, 1, 2, 1, 10);
    assertThat(counter.snapshot(), is(expected));
    assertThat(counter.toString(), is(expected.toString()));
    assertThat(counter.snapshot().toString(), is(expected.toString()));
  }

  @Test
  public void guarded_exception() {
    StatsCounter statsCounter = Mockito.mock(StatsCounter.class);
    when(statsCounter.snapshot()).thenThrow(new NullPointerException());
    doThrow(NullPointerException.class).when(statsCounter).recordHits(anyInt());
    doThrow(NullPointerException.class).when(statsCounter).recordMisses(anyInt());
    doThrow(NullPointerException.class).when(statsCounter).recordEviction(anyInt(), any());
    doThrow(NullPointerException.class).when(statsCounter).recordLoadSuccess(anyLong());
    doThrow(NullPointerException.class).when(statsCounter).recordLoadFailure(anyLong());

    StatsCounter guarded = StatsCounter.guardedStatsCounter(statsCounter);
    guarded.recordHits(1);
    guarded.recordMisses(1);
    guarded.recordEviction(10, RemovalCause.SIZE);
    guarded.recordLoadSuccess(1);
    guarded.recordLoadFailure(1);
    assertThat(guarded.snapshot(), is(CacheStats.empty()));

    verify(statsCounter).recordHits(1);
    verify(statsCounter).recordMisses(1);
    verify(statsCounter).recordEviction(10, RemovalCause.SIZE);
    verify(statsCounter).recordLoadSuccess(1);
    verify(statsCounter).recordLoadFailure(1);
  }

  @Test
  public void overflow_loadSuccess() {
    StatsCounter counter = new ConcurrentStatsCounter();
    counter.recordLoadSuccess(Long.MAX_VALUE);
    counter.recordLoadSuccess(1);
    CacheStats stats = counter.snapshot();
    assertThat(stats.totalLoadTime(), is(Long.MAX_VALUE));
  }

  @Test
  public void overflow_loadFailure() {
    StatsCounter counter = new ConcurrentStatsCounter();
    counter.recordLoadFailure(Long.MAX_VALUE);
    counter.recordLoadFailure(1);
    CacheStats stats = counter.snapshot();
    assertThat(stats.totalLoadTime(), is(Long.MAX_VALUE));
  }
}
