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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheStatsTest {

  @Test(dataProvider = "badArgs")
  public void invalid(int hitCount, int missCount, int loadSuccessCount, int loadFailureCount,
      int totalLoadTime, int evictionCount, int evictionWeight) {
    assertThrows(IllegalArgumentException.class, () -> {
      CacheStats.of(hitCount, missCount, loadSuccessCount,
          loadFailureCount, totalLoadTime, evictionCount, evictionWeight);
    });
  }

  @Test
  public void empty() {
    var stats = CacheStats.of(0, 0, 0, 0, 0, 0, 0);
    checkStats(stats, 0, 0, 1.0, 0, 0.0, 0, 0, 0.0, 0, 0, 0.0, 0, 0);

    assertThat(stats).isEqualTo(CacheStats.empty());
    assertThat(stats.equals(null)).isFalse();
    assertThat(stats).isNotEqualTo(new Object());
    assertThat(stats).isEqualTo(CacheStats.empty());
    assertThat(stats.hashCode()).isEqualTo(CacheStats.empty().hashCode());
    assertThat(stats.toString()).isEqualTo(CacheStats.empty().toString());
  }

  @Test
  public void populated() {
    var stats = CacheStats.of(11, 13, 17, 19, 23, 27, 54);
    checkStats(stats, 24, 11, 11.0/24, 13, 13.0/24,
        17, 19, 19.0/36, 17 + 19, 23, 23.0/(17 + 19), 27, 54);

    assertThat(stats.equals(stats)).isTrue();
    assertThat(stats).isNotEqualTo(CacheStats.empty());
    assertThat(stats.hashCode()).isNotEqualTo(CacheStats.empty().hashCode());
    assertThat(stats.toString()).isNotEqualTo(CacheStats.empty().toString());

    var expected = CacheStats.of(11, 13, 17, 19, 23, 27, 54);
    assertThat(stats.equals(expected)).isTrue();
    assertThat(stats.hashCode()).isEqualTo(expected.hashCode());
    assertThat(stats.toString()).isEqualTo(expected.toString());
  }

  @Test
  public void minus() {
    var one = CacheStats.of(11, 13, 17, 19, 23, 27, 54);
    var two = CacheStats.of(53, 47, 43, 41, 37, 31, 62);

    var diff = two.minus(one);
    checkStats(diff, 76, 42, 42.0 / 76, 34, 34.0 / 76,
        26, 22, 22.0 / 48, 26 + 22, 14, 14.0 / (26 + 22), 4, 8);
    assertThat(one.minus(two)).isEqualTo(CacheStats.empty());
  }

  @Test
  public void plus() {
    var one = CacheStats.of(11, 13, 15, 13, 11, 9, 18);
    var two = CacheStats.of(53, 47, 41, 39, 37, 35, 70);

    var sum = two.plus(one);
    checkStats(sum, 124, 64, 64.0 / 124, 60, 60.0 / 124,
        56, 52, 52.0 / 108, 56 + 52, 48, 48.0 / (56 + 52), 44, 88);

    assertThat(sum).isEqualTo(one.plus(two));
  }

  @Test
  public void overflow() {
    var max = CacheStats.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    checkStats(max.plus(max), Long.MAX_VALUE, Long.MAX_VALUE, 1.0, Long.MAX_VALUE, 1.0,
        Long.MAX_VALUE, Long.MAX_VALUE, 1.0, Long.MAX_VALUE, Long.MAX_VALUE, 1.0,
        Long.MAX_VALUE, Long.MAX_VALUE);
  }

  @Test
  public void underflow() {
    var max = CacheStats.of(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    assertThat(CacheStats.empty().minus(max)).isEqualTo(CacheStats.empty());
  }

  private static void checkStats(CacheStats stats, long requestCount, long hitCount,
      double hitRate, long missCount, double missRate, long loadSuccessCount,
      long loadFailureCount, double loadFailureRate, long loadCount, long totalLoadTime,
      double averageLoadPenalty, long evictionCount, long evictionWeight) {
    assertThat(stats.requestCount()).isEqualTo(requestCount);
    assertThat(stats.hitCount()).isEqualTo(hitCount);
    assertThat(stats.hitRate()).isEqualTo(hitRate);
    assertThat(stats.missCount()).isEqualTo(missCount);
    assertThat(stats.missRate()).isEqualTo(missRate);
    assertThat(stats.loadSuccessCount()).isEqualTo(loadSuccessCount);
    assertThat(stats.loadFailureCount()).isEqualTo(loadFailureCount);
    assertThat(stats.loadFailureRate()).isEqualTo(loadFailureRate);
    assertThat(stats.loadCount()).isEqualTo(loadCount);
    assertThat(stats.totalLoadTime()).isEqualTo(totalLoadTime);
    assertThat(stats.averageLoadPenalty()).isEqualTo(averageLoadPenalty);
    assertThat(stats.evictionCount()).isEqualTo(evictionCount);
    assertThat(stats.evictionWeight()).isEqualTo(evictionWeight);
  }

  @DataProvider(name = "badArgs")
  public Object[][] providesBadArgs() {
    return new Object[][] {
        { -1,  0,  0,  0,  0,  0,  0, },
        {  0, -1,  0,  0,  0,  0,  0, },
        {  0,  0, -1,  0,  0,  0,  0, },
        {  0,  0,  0, -1,  0,  0,  0, },
        {  0,  0,  0,  0, -1,  0,  0, },
        {  0,  0,  0,  0,  0, -1,  0, },
        {  0,  0,  0,  0,  0,  0, -1, },
    };
  }
}
