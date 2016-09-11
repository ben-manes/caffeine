/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.stats.metrics;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * A {@link StatsCounter} instrumented with Dropwizard Metrics.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MetricsStatsCounter implements StatsCounter {
  private final Meter hitCount;
  private final Meter missCount;
  private final Meter loadSuccessCount;
  private final Meter loadFailureCount;
  private final Timer totalLoadTime;
  private final Meter evictionCount;
  private final Meter evictionWeight;

  /**
   * Constructs an instance for use by a single cache.
   *
   * @param registry the registry of metric instances
   * @param metricsPrefix the prefix name for the metrics
   */
  public MetricsStatsCounter(MetricRegistry registry, String metricsPrefix) {
    requireNonNull(metricsPrefix);
    hitCount = registry.meter(metricsPrefix + ".hits");
    missCount = registry.meter(metricsPrefix + ".misses");
    totalLoadTime = registry.timer(metricsPrefix + ".loads");
    loadSuccessCount = registry.meter(metricsPrefix + ".loads-success");
    loadFailureCount = registry.meter(metricsPrefix + ".loads-failure");
    evictionCount = registry.meter(metricsPrefix + ".evictions");
    evictionWeight = registry.meter(metricsPrefix + ".evictions-weight");
  }

  @Override
  public void recordHits(int count) {
    hitCount.mark(count);
  }

  @Override
  public void recordMisses(int count) {
    missCount.mark(count);
  }

  @Override
  public void recordLoadSuccess(long loadTime) {
    loadSuccessCount.mark();
    totalLoadTime.update(loadTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordLoadFailure(long loadTime) {
    loadFailureCount.mark();
    totalLoadTime.update(loadTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordEviction() {
    // This method is scheduled for removal in version 3.0 in favor of recordEviction(weight)
    recordEviction(1);
  }

  @Override
  public void recordEviction(int weight) {
    evictionCount.mark();
    evictionWeight.mark(weight);
  }

  @Override
  public CacheStats snapshot() {
    return new CacheStats(
        hitCount.getCount(),
        missCount.getCount(),
        loadSuccessCount.getCount(),
        loadFailureCount.getCount(),
        totalLoadTime.getCount(),
        evictionCount.getCount(),
        evictionWeight.getCount());
  }

  @Override
  public String toString() {
    return snapshot().toString();
  }
}
