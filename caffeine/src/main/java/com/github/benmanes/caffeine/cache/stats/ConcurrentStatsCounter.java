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
import com.github.benmanes.caffeine.cache.RemovalCause;

/**
 * A thread-safe {@link StatsCounter} implementation for use by {@link Cache} implementors.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ConcurrentStatsCounter implements StatsCounter {
  private final LongAdder hitCount;
  private final LongAdder missCount;
  private final LongAdder loadSuccessCount;
  private final LongAdder loadFailureCount;
  private final LongAdder totalLoadTime;
  private final LongAdder evictionCount;
  private final LongAdder evictionWeight;

  /**
   * Constructs an instance with all counts initialized to zero.
   */
  public ConcurrentStatsCounter() {
    hitCount = new LongAdder();
    missCount = new LongAdder();
    loadSuccessCount = new LongAdder();
    loadFailureCount = new LongAdder();
    totalLoadTime = new LongAdder();
    evictionCount = new LongAdder();
    evictionWeight = new LongAdder();
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
  public void recordLoadFailure(long loadTime) {
    loadFailureCount.increment();
    totalLoadTime.add(loadTime);
  }

  @Override
  public void recordEviction(int weight, RemovalCause cause) {
    evictionCount.increment();
    evictionWeight.add(weight);
  }

  @Override
  public CacheStats snapshot() {
    return CacheStats.of(
        negativeToMaxValue(hitCount.sum()),
        negativeToMaxValue(missCount.sum()),
        negativeToMaxValue(loadSuccessCount.sum()),
        negativeToMaxValue(loadFailureCount.sum()),
        negativeToMaxValue(totalLoadTime.sum()),
        negativeToMaxValue(evictionCount.sum()),
        negativeToMaxValue(evictionWeight.sum()));
  }

  /** Returns {@code value}, if non-negative. Otherwise, returns {@link Long#MAX_VALUE}. */
  private static long negativeToMaxValue(long value) {
    return (value >= 0) ? value : Long.MAX_VALUE;
  }

  /**
   * Increments all counters by the values in {@code other}.
   *
   * @param other the counter to increment from
   */
  public void incrementBy(StatsCounter other) {
    CacheStats otherStats = other.snapshot();
    hitCount.add(otherStats.hitCount());
    missCount.add(otherStats.missCount());
    loadSuccessCount.add(otherStats.loadSuccessCount());
    loadFailureCount.add(otherStats.loadFailureCount());
    totalLoadTime.add(otherStats.totalLoadTime());
    evictionCount.add(otherStats.evictionCount());
    evictionWeight.add(otherStats.evictionWeight());
  }

  @Override
  public String toString() {
    return snapshot().toString();
  }
}
