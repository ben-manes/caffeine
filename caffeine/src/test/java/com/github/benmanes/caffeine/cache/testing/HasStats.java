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

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static org.hamcrest.Matchers.is;

import java.util.concurrent.Callable;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
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
    if ((context.cacheExecutor != CacheExecutor.DIRECT)
        && context.executor() instanceof TrackingExecutor) {
      TrackingExecutor executor = (TrackingExecutor) context.executor();
      if (executor.submitted() != executor.completed()) {
        await().pollInSameThread().until(() -> executor.submitted() == executor.completed());
      }
    }

    CacheStats stats = context.stats();
    desc = new DescriptionBuilder(description);
    switch (type) {
      case HIT:
        return awaitStatistic(context, stats::hitCount);
      case MISS:
        return awaitStatistic(context, stats::missCount);
      case EVICTION_COUNT:
        return awaitStatistic(context, stats::evictionCount);
      case EVICTION_WEIGHT:
        return awaitStatistic(context, stats::evictionWeight);
      case LOAD_SUCCESS:
        return awaitStatistic(context, stats::loadSuccessCount);
      case LOAD_FAILURE:
        return awaitStatistic(context, stats::loadFailureCount);
      default:
        throw new AssertionError("Unknown stats type");
    }
  }

  private boolean awaitStatistic(CacheContext context, Callable<Long> statistic) {
    try {
      if (statistic.call().equals(count)) {
        return true;
      } else if (context.cacheExecutor != CacheExecutor.DIRECT)  {
        await().pollInSameThread().until(statistic, is(count));
        return true;
      }
      return false;
    } catch (Exception e) {
      return desc.expectThat(type.name(), statistic, is(count)).matches();
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
