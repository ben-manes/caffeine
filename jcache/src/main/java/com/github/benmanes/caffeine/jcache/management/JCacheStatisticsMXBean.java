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
package com.github.benmanes.caffeine.jcache.management;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nonnegative;
import javax.cache.management.CacheStatisticsMXBean;

/**
 * Caffeine JCache statistics.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheStatisticsMXBean implements CacheStatisticsMXBean {
  private final LongAdder removals = new LongAdder();
  private final LongAdder expiries = new LongAdder();
  private final LongAdder puts = new LongAdder();
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder evictions = new LongAdder();
  private final LongAdder putTimeNanos = new LongAdder();
  private final LongAdder getTimeNanos = new LongAdder();
  private final LongAdder removeTimeNanos = new LongAdder();

  private volatile boolean enabled;

  /** @return if statistic collection is enabled. */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Sets whether the statistic collection is enabled.
   *
   * @param enabled whether to collect statistics
   */
  public void enable(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void clear() {
    puts.reset();
    misses.reset();
    removals.reset();
    expiries.reset();
    hits.reset();
    evictions.reset();
    getTimeNanos.reset();
    putTimeNanos.reset();
    removeTimeNanos.reset();
  }

  @Override
  public long getCacheHits() {
    return hits.sum();
  }

  @Override
  public float getCacheHitPercentage() {
    long requestCount = getCacheGets();
    return (requestCount == 0) ? 0f : 100 * ((float) getCacheHits() / requestCount);
  }

  /**
   * Records cache hits. This should be called when a cache request returns a cached value.
   *
   * @param count the number of hits to record
   */
  public void recordHits(@Nonnegative long count) {
    if (enabled) {
      hits.add(count);
    }
  }

  @Override
  public long getCacheMisses() {
    return misses.sum();
  }

  @Override
  public float getCacheMissPercentage() {
    long requestCount = getCacheGets();
    return (requestCount == 0) ? 0f : 100 * ((float) getCacheMisses() / requestCount);
  }

  /**
   * Records cache misses. This should be called when a cache request returns a value that was not
   * found in the cache.
   *
   * @param count the number of misses to record
   */
  public void recordMisses(@Nonnegative long count) {
    if (enabled) {
      misses.add(count);
    }
  }

  @Override
  public long getCacheGets() {
    return getCacheHits() + getCacheMisses();
  }

  @Override
  public long getCachePuts() {
    return puts.sum();
  }

  /**
   * Records cache insertion and updates.
   *
   * @param count the number of writes to record
   */
  public void recordPuts(@Nonnegative long count) {
    if (enabled) {
      puts.add(count);
    }
  }

  @Override
  public long getCacheRemovals() {
    return removals.sum();
  }

  /**
   * Records cache removals.
   *
   * @param count the number of removals to record
   */
  public void recordRemovals(@Nonnegative long count) {
    if (enabled) {
      removals.add(count);
    }
  }

  @Override
  public long getCacheEvictions() {
    return evictions.sum();
  }

  @Override
  public float getAverageGetTime() {
    return average(getCacheGets(), getTimeNanos.sum());
  }

  @Override
  public float getAveragePutTime() {
    return average(getCachePuts(), putTimeNanos.sum());
  }

  @Override
  public float getAverageRemoveTime() {
    return average(getCacheRemovals(), removeTimeNanos.sum());
  }

  private static float average(long requestCount, long opsTimeNanos) {
    if ((requestCount == 0) || (opsTimeNanos == 0)) {
      return 0;
    }
    long opsTimeMillis = TimeUnit.MILLISECONDS.toNanos(opsTimeNanos);
    return (float) opsTimeMillis / requestCount;
  }
}
