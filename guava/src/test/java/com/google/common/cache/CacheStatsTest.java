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

import com.github.benmanes.caffeine.cache.stats.CacheStats;

import junit.framework.TestCase;

/**
 * Unit test for {@link CacheStats}.
 *
 * @author Charles Fry
 */
@SuppressWarnings("JUnit3FloatingPointComparisonWithoutDelta")
public class CacheStatsTest extends TestCase {

  public void testEmpty() {
    CacheStats stats = CacheStats.of(0, 0, 0, 0, 0, 0, 0);
    assertEquals(0, stats.requestCount());
    assertEquals(0, stats.hitCount());
    assertEquals(1.0, stats.hitRate());
    assertEquals(0, stats.missCount());
    assertEquals(0.0, stats.missRate());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadFailureCount());
    assertEquals(0.0, stats.loadFailureRate());
    assertEquals(0, stats.loadCount());
    assertEquals(0, stats.totalLoadTime());
    assertEquals(0.0, stats.averageLoadPenalty());
    assertEquals(0, stats.evictionCount());
  }

  public void testSingle() {
    CacheStats stats = CacheStats.of(11, 13, 17, 19, 23, 27, 54);
    assertEquals(24, stats.requestCount());
    assertEquals(11, stats.hitCount());
    assertEquals(11.0/24, stats.hitRate());
    assertEquals(13, stats.missCount());
    assertEquals(13.0/24, stats.missRate());
    assertEquals(17, stats.loadSuccessCount());
    assertEquals(19, stats.loadFailureCount());
    assertEquals(19.0/36, stats.loadFailureRate());
    assertEquals(17 + 19, stats.loadCount());
    assertEquals(23, stats.totalLoadTime());
    assertEquals(23.0/(17 + 19), stats.averageLoadPenalty());
    assertEquals(27, stats.evictionCount());
    assertEquals(54, stats.evictionWeight());
  }

  public void testMinus() {
    CacheStats one = CacheStats.of(11, 13, 17, 19, 23, 27, 54);
    CacheStats two = CacheStats.of(53, 47, 43, 41, 37, 31, 62);

    CacheStats diff = two.minus(one);
    assertEquals(76, diff.requestCount());
    assertEquals(42, diff.hitCount());
    assertEquals(42.0/76, diff.hitRate());
    assertEquals(34, diff.missCount());
    assertEquals(34.0/76, diff.missRate());
    assertEquals(26, diff.loadSuccessCount());
    assertEquals(22, diff.loadFailureCount());
    assertEquals(22.0/48, diff.loadFailureRate());
    assertEquals(26 + 22, diff.loadCount());
    assertEquals(14, diff.totalLoadTime());
    assertEquals(14.0/(26 + 22), diff.averageLoadPenalty());
    assertEquals(4, diff.evictionCount());
    assertEquals(8, diff.evictionWeight());

    assertEquals(CacheStats.of(0, 0, 0, 0, 0, 0, 0), one.minus(two));
  }

  public void testPlus() {
    CacheStats one = CacheStats.of(11, 13, 15, 13, 11, 9, 18);
    CacheStats two = CacheStats.of(53, 47, 41, 39, 37, 35, 70);

    CacheStats sum = two.plus(one);
    assertEquals(124, sum.requestCount());
    assertEquals(64, sum.hitCount());
    assertEquals(64.0/124, sum.hitRate());
    assertEquals(60, sum.missCount());
    assertEquals(60.0/124, sum.missRate());
    assertEquals(56, sum.loadSuccessCount());
    assertEquals(52, sum.loadFailureCount());
    assertEquals(52.0/108, sum.loadFailureRate());
    assertEquals(56 + 52, sum.loadCount());
    assertEquals(48, sum.totalLoadTime());
    assertEquals(48.0/(56 + 52), sum.averageLoadPenalty());
    assertEquals(44, sum.evictionCount());
    assertEquals(88, sum.evictionWeight());

    assertEquals(sum, one.plus(two));
  }

  public void testPlusLarge() {
    CacheStats maxCacheStats =
        CacheStats.of(
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE);
    CacheStats smallCacheStats = CacheStats.of(1, 1, 1, 1, 1, 1, 1);

    CacheStats sum = smallCacheStats.plus(maxCacheStats);
    assertEquals(Long.MAX_VALUE, sum.requestCount());
    assertEquals(Long.MAX_VALUE, sum.hitCount());
    assertEquals(1.0, sum.hitRate());
    assertEquals(Long.MAX_VALUE, sum.missCount());
    assertEquals(1.0, sum.missRate());
    assertEquals(Long.MAX_VALUE, sum.loadSuccessCount());
    assertEquals(Long.MAX_VALUE, sum.loadFailureCount());
    assertEquals(1.0, sum.loadFailureRate());
    assertEquals(Long.MAX_VALUE, sum.loadCount());
    assertEquals(Long.MAX_VALUE, sum.totalLoadTime());
    assertEquals(1.0, sum.averageLoadPenalty());
    assertEquals(Long.MAX_VALUE, sum.evictionCount());
    assertEquals(Long.MAX_VALUE, sum.evictionWeight());

    assertEquals(sum, maxCacheStats.plus(smallCacheStats));
  }
}
