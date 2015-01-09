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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheStatsTest {

  @Test(dataProvider = "badArgs", expectedExceptions = IllegalArgumentException.class)
  public void invalid(long hitCount, long missCount, long loadSuccessCount, long loadFailureCount,
      long totalLoadTime, long evictionCount) {
    new CacheStats(hitCount, missCount, loadSuccessCount,
        loadFailureCount, totalLoadTime, evictionCount);
  }

  @Test
  public void empty() {
    CacheStats stats = new CacheStats(0, 0, 0, 0, 0, 0);
    checkStats(stats, 0, 0, 1.0, 0, 0.0, 0, 0, 0.0, 0, 0, 0.0, 0);
    assertThat(stats, is(equalTo(new CacheStats(0, 0, 0, 0, 0, 0))));
    assertThat(stats.hashCode(), is(new CacheStats(0, 0, 0, 0, 0, 0).hashCode()));
    assertThat(stats, hasToString(new CacheStats(0, 0, 0, 0, 0, 0).toString()));
  }

  @Test
  public void populated() {
    CacheStats stats = new CacheStats(11, 13, 17, 19, 23, 27);
    checkStats(stats, 24, 11, 11.0/24, 13, 13.0/24,
        17, 19, 19.0/36, 17 + 19, 23, 23.0/(17 + 19), 27);

    assertThat(stats, is(not(equalTo(new CacheStats(0, 0, 0, 0, 0, 0)))));
    assertThat(stats.hashCode(), is(not(new CacheStats(0, 0, 0, 0, 0, 0).hashCode())));
    assertThat(stats, hasToString(not(new CacheStats(0, 0, 0, 0, 0, 0).toString())));

    assertThat(stats, is(equalTo(new CacheStats(11, 13, 17, 19, 23, 27))));
    assertThat(stats.hashCode(), is(new CacheStats(11, 13, 17, 19, 23, 27).hashCode()));
    assertThat(stats, hasToString(new CacheStats(11, 13, 17, 19, 23, 27).toString()));
  }

  @Test
  public void minus() {
    CacheStats one = new CacheStats(11, 13, 17, 19, 23, 27);
    CacheStats two = new CacheStats(53, 47, 43, 41, 37, 31);

    CacheStats diff = two.minus(one);
    checkStats(diff, 76, 42, 42.0 / 76, 34, 34.0 / 76,
        26, 22, 22.0 / 48, 26 + 22, 14, 14.0 / (26 + 22), 4);
    assertThat(one.minus(two), is(new CacheStats(0, 0, 0, 0, 0, 0)));
  }

  public void plus() {
    CacheStats one = new CacheStats(11, 13, 15, 13, 11, 9);
    CacheStats two = new CacheStats(53, 47, 41, 39, 37, 35);

    CacheStats sum = two.plus(one);
    checkStats(sum, 124, 64, 64.0 / 124, 60, 60.0 / 124,
        56, 52, 52.0 / 108, 56 + 52, 48, 48.0 / (56 + 52), 44);

    assertThat(sum, is(one.plus(two)));
  }

  private static void checkStats(CacheStats stats, long requestCount, long hitCount,
      double hitRate, long missCount, double missRate, long loadSuccessCount,
      long loadFailureCount, double loadExceptionRate, long loadCount, long totalLoadTime,
      double averageLoadPenalty, long evictionCount) {
    assertThat(stats.requestCount(), is(requestCount));
    assertThat(stats.hitCount(), is(hitCount));
    assertThat(stats.hitRate(), is(hitRate));
    assertThat(stats.missCount(), is(missCount));
    assertThat(stats.missRate(), is(missRate));
    assertThat(stats.loadSuccessCount(), is(loadSuccessCount));
    assertThat(stats.loadFailureCount(), is(loadFailureCount));
    assertThat(stats.loadFailureRate(), is(loadExceptionRate));
    assertThat(stats.totalLoadTime(), is(totalLoadTime));
    assertThat(stats.averageLoadPenalty(), is(averageLoadPenalty));
    assertThat(stats.evictionCount(), is(evictionCount));
  }

  @DataProvider(name = "badArgs")
  public Object[][] providesBadArgs() {
    return new Object[][] {
        { -1,  0,  0,  0,  0,  0, },
        {  0, -1,  0,  0,  0,  0, },
        {  0,  0, -1,  0,  0,  0, },
        {  0,  0,  0, -1,  0,  0, },
        {  0,  0,  0,  0, -1,  0, },
        {  0,  0,  0,  0,  0, -1, },
    };
  }
}
