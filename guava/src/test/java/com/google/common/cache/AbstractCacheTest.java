/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.common.cache;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

import junit.framework.TestCase;

/**
 * Unit test for {@link AbstractCache}.
 *
 * @author Charles Fry
 */
public class AbstractCacheTest extends TestCase {

  public void testEmptySimpleStats() {
    StatsCounter counter = new ConcurrentStatsCounter();
    CacheStats stats = counter.snapshot();
    assertEquals(0, stats.requestCount());
    assertEquals(0, stats.hitCount());
    assertEquals(1.0, stats.hitRate(), 0.0);
    assertEquals(0, stats.missCount());
    assertEquals(0.0, stats.missRate(), 0.0);
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadFailureCount());
    assertEquals(0, stats.loadCount());
    assertEquals(0, stats.totalLoadTime());
    assertEquals(0.0, stats.averageLoadPenalty(), 0.0);
    assertEquals(0, stats.evictionCount());
  }

  public void testSingleSimpleStats() {
    StatsCounter counter = new ConcurrentStatsCounter();
    for (int i = 0; i < 11; i++) {
      counter.recordHits(1);
    }
    for (int i = 0; i < 13; i++) {
      counter.recordLoadSuccess(i);
    }
    for (int i = 0; i < 17; i++) {
      counter.recordLoadFailure(i);
    }
    for (int i = 0; i < 23; i++) {
      counter.recordMisses(1);
    }
    for (int i = 0; i < 27; i++) {
      counter.recordEviction(1, RemovalCause.SIZE);
    }
    CacheStats stats = counter.snapshot();
    int requestCount = 11 + 23;
    assertEquals(requestCount, stats.requestCount());
    assertEquals(11, stats.hitCount());
    assertEquals(11.0 / requestCount, stats.hitRate(), 0.0);
    int missCount = 23;
    assertEquals(missCount, stats.missCount());
    assertEquals(((double) missCount) / requestCount, stats.missRate(), 0.0);
    assertEquals(13, stats.loadSuccessCount());
    assertEquals(17, stats.loadFailureCount());
    assertEquals(13 + 17, stats.loadCount());
    assertEquals(214, stats.totalLoadTime());
    assertEquals(214.0 / (13 + 17), stats.averageLoadPenalty(), 0.0);
    assertEquals(27, stats.evictionCount());
  }

  public void testSimpleStatsOverflow() {
    StatsCounter counter = new ConcurrentStatsCounter();
    counter.recordLoadSuccess(Long.MAX_VALUE);
    counter.recordLoadSuccess(1);
    CacheStats stats = counter.snapshot();
    assertEquals(Long.MAX_VALUE, stats.totalLoadTime());
  }

  public void testSimpleStatsIncrementBy() {
    long totalLoadTime = 0;

    ConcurrentStatsCounter counter1 = new ConcurrentStatsCounter();
    for (int i = 0; i < 11; i++) {
      counter1.recordHits(1);
    }
    for (int i = 0; i < 13; i++) {
      counter1.recordLoadSuccess(i);
      totalLoadTime += i;
    }
    for (int i = 0; i < 17; i++) {
      counter1.recordLoadFailure(i);
      totalLoadTime += i;
    }
    for (int i = 0; i < 19; i++) {
      counter1.recordMisses(1);
    }
    for (int i = 0; i < 23; i++) {
      counter1.recordEviction(1, RemovalCause.SIZE);
    }

    ConcurrentStatsCounter counter2 = new ConcurrentStatsCounter();
    for (int i = 0; i < 27; i++) {
      counter2.recordHits(1);
    }
    for (int i = 0; i < 31; i++) {
      counter2.recordLoadSuccess(i);
      totalLoadTime += i;
    }
    for (int i = 0; i < 37; i++) {
      counter2.recordLoadFailure(i);
      totalLoadTime += i;
    }
    for (int i = 0; i < 41; i++) {
      counter2.recordMisses(1);
    }
    for (int i = 0; i < 43; i++) {
      counter1.recordEviction(1, RemovalCause.SIZE);
    }

    counter1.incrementBy(counter2);
    assertEquals(CacheStats.of(38, 60, 44, 54, totalLoadTime, 66, 66), counter1.snapshot());
  }
}
