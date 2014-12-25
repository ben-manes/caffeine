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
package com.github.benmanes.caffeine.cache.stats;

import java.util.concurrent.atomic.LongAdder;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * A thread-safe {@link StatsCounter} implementation for use by {@link Cache} implementors.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ConcurrentStatsCounter implements StatsCounter {
  private final LongAdder hitCount;
  private final LongAdder missCount;
  private final LongAdder loadSuccessCount;
  private final LongAdder loadExceptionCount;
  private final LongAdder totalLoadTime;
  private final LongAdder evictionCount;

  /**
   * Constructs an instance with all counts initialized to zero.
   */
  public ConcurrentStatsCounter() {
    hitCount = new LongAdder();
    missCount = new LongAdder();
    loadSuccessCount = new LongAdder();
    loadExceptionCount = new LongAdder();
    totalLoadTime = new LongAdder();
    evictionCount = new LongAdder();
  }

  @Override
  public void recordHits(int count) {
    hitCount.add(count);
  }

  @Override
  public void recordMisses(int count) {
    missCount.add(count);
  }

  @Override
  public void recordLoadSuccess(long loadTime) {
    loadSuccessCount.increment();
    totalLoadTime.add(loadTime);
  }

  @Override
  public void recordLoadException(long loadTime) {
    loadExceptionCount.increment();
    totalLoadTime.add(loadTime);
  }

  @Override
  public void recordEviction() {
    evictionCount.increment();
  }

  @Override
  public CacheStats snapshot() {
    return new CacheStats(
        hitCount.sum(),
        missCount.sum(),
        loadSuccessCount.sum(),
        loadExceptionCount.sum(),
        totalLoadTime.sum(),
        evictionCount.sum());
  }

  /**
   * Increments all counters by the values in {@code other}.
   */
  public void incrementBy(StatsCounter other) {
    CacheStats otherStats = other.snapshot();
    hitCount.add(otherStats.hitCount());
    missCount.add(otherStats.missCount());
    loadSuccessCount.add(otherStats.loadSuccessCount());
    loadExceptionCount.add(otherStats.loadExceptionCount());
    totalLoadTime.add(otherStats.totalLoadTime());
    evictionCount.add(otherStats.evictionCount());
  }
}
