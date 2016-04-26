/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import static org.hamcrest.Matchers.is;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.testing.DescriptionBuilder;

/**
 * A matcher that evaluates if the {@link CacheStats} recorded all of the statistical events.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class HasStats extends TypeSafeDiagnosingMatcher<CacheContext> {
  private enum StatsType {
    HIT, MISS, EVICTION_COUNT, EVICTION_WEIGHT, LOAD_SUCCESS, LOAD_FAILURE, TOTAL_LOAD_TIME
  }

  final long count;
  final StatsType type;
  DescriptionBuilder desc;

  private HasStats(StatsType type, long count) {
    this.count = count;
    this.type = type;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("stats: " + type.name() + "=" + count);
    if ((desc != null) && (description != desc.getDescription())) {
      description.appendText(desc.getDescription().toString());
    }
  }

  @Override
  protected boolean matchesSafely(CacheContext context, Description description) {
    if (!context.isRecordingStats()) {
      return true;
    }

    CacheStats stats = context.stats();
    desc = new DescriptionBuilder(description);
    ForkJoinPool.commonPool().awaitQuiescence(10, TimeUnit.SECONDS);
    switch (type) {
      case HIT:
        return desc.expectThat(type.name(), stats.hitCount(), is(count)).matches();
      case MISS:
        return desc.expectThat(type.name(), stats.missCount(), is(count)).matches();
      case EVICTION_COUNT:
        return desc.expectThat(type.name(), stats.evictionCount(), is(count)).matches();
      case EVICTION_WEIGHT:
        return desc.expectThat(type.name(), stats.evictionWeight(), is(count)).matches();
      case LOAD_SUCCESS:
        return desc.expectThat(type.name(), stats.loadSuccessCount(), is(count)).matches();
      case LOAD_FAILURE:
        return desc.expectThat(type.name(), stats.loadFailureCount(), is(count)).matches();
      default:
        throw new AssertionError("Unknown stats type");
    }
  }

  public static HasStats hasHitCount(long count) {
    return new HasStats(StatsType.HIT, count);
  }

  public static HasStats hasMissCount(long count) {
    return new HasStats(StatsType.MISS, count);
  }

  public static HasStats hasEvictionCount(long count) {
    return new HasStats(StatsType.EVICTION_COUNT, count);
  }

  public static HasStats hasEvictionWeight(long count) {
    return new HasStats(StatsType.EVICTION_WEIGHT, count);
  }

  public static HasStats hasLoadSuccessCount(long count) {
    return new HasStats(StatsType.LOAD_SUCCESS, count);
  }

  public static HasStats hasLoadFailureCount(long count) {
    return new HasStats(StatsType.LOAD_FAILURE, count);
  }
}
