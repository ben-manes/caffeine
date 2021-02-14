/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;

/**
 * A utility for verifying that the {@link CacheStats} has the expected metrics.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class StatsVerifier {
  private final CacheContext context;

  private StatsVerifier(CacheContext context) {
    this.context = requireNonNull(context);
  }

  public StatsVerifier hits(long count) {
    return awaitStatistic("hitCount", count, context.stats()::hitCount);
  }

  public StatsVerifier misses(long count) {
    return awaitStatistic("missCount", count, context.stats()::missCount);
  }

  public StatsVerifier evictions(long count) {
    return awaitStatistic("evictionCount", count, context.stats()::evictionCount);
  }

  public StatsVerifier evictionWeight(long count) {
    return awaitStatistic("evictionWeight", count, context.stats()::evictionWeight);
  }

  public StatsVerifier success(long count) {
    return awaitStatistic("loadSuccessCount", count, context.stats()::loadSuccessCount);
  }

  public StatsVerifier failures(long count) {
    return awaitStatistic("loadFailureCount", count, context.stats()::loadFailureCount);
  }

  private StatsVerifier awaitStatistic(String label, long count, LongSupplier statistic) {
    if (context.cacheExecutor == CacheExecutor.DIRECT)  {
      assertThat(label, statistic.getAsLong(), is(count));
    } else if (count != statistic.getAsLong()) {
      await().pollInSameThread().until(statistic::getAsLong, is(count));
    }
    return this;
  }

  private void awaitCompletion() {
    if ((context.cacheExecutor != CacheExecutor.DIRECT)
        && context.executor() instanceof TrackingExecutor) {
      TrackingExecutor executor = (TrackingExecutor) context.executor();
      if (executor.submitted() != executor.completed()) {
        await().pollInSameThread().until(() -> executor.submitted() == executor.completed());
      }
    }
  }

  /** Runs the verification block if the consuming removal listener is enabled. */
  public static void verifyStats(CacheContext context, Consumer<StatsVerifier> consumer) {
    if (context.isRecordingStats()) {
      StatsVerifier verifier = new StatsVerifier(context);
      verifier.awaitCompletion();
      consumer.accept(verifier);
    }
  }
}
