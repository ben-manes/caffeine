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

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * Accumulates statistics during the operation of a {@link Cache} for presentation by
 * {@link Cache#stats}. This is solely intended for consumption by {@code Cache} implementors.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
public interface StatsCounter {

  /**
   * Records cache hits. This should be called when a cache request returns a cached value.
   *
   * @param count the number of hits to record
   */
  void recordHits(@Nonnegative int count);

  /**
   * Records cache misses. This should be called when a cache request returns a value that was not
   * found in the cache. This method should be called by the loading thread, as well as by threads
   * blocking on the load. Multiple concurrent calls to {@link Cache} lookup methods with the same
   * key on an absent value should result in a single call to either {@code recordLoadSuccess} or
   * {@code recordLoadFailure} and multiple calls to this method, despite all being served by the
   * results of a single load operation.
   *
   * @param count the number of misses to record
   */
  void recordMisses(@Nonnegative int count);

  /**
   * Records the successful load of a new entry. This should be called when a cache request causes
   * an entry to be loaded, and the loading completes successfully. In contrast to
   * {@link #recordMisses}, this method should only be called by the loading thread.
   *
   * @param loadTime the number of nanoseconds the cache spent computing or retrieving the new value
   */
  void recordLoadSuccess(@Nonnegative long loadTime);

  /**
   * Records the failed load of a new entry. This should be called when a cache request causes an
   * entry to be loaded, but either no value is found or an exception is thrown while loading the
   * entry. In contrast to {@link #recordMisses}, this method should only be called by the loading
   * thread.
   *
   * @param loadTime the number of nanoseconds the cache spent computing or retrieving the new value
   *        prior to discovering the value doesn't exist or an exception being thrown
   */
  void recordLoadFailure(@Nonnegative long loadTime);

  /**
   * Records the eviction of an entry from the cache. This should only been called when an entry is
   * evicted due to the cache's eviction strategy, and not as a result of manual
   * {@link Cache#invalidate invalidations}.
   *
   * @deprecated Use {@link StatsCounter#recordEviction(int)} instead. This method is scheduled for
   *     removal in version <tt>3.0.0</tt>.
   */
  @Deprecated
  void recordEviction();

  /**
   * Records the eviction of an entry from the cache. This should only been called when an entry is
   * evicted due to the cache's eviction strategy, and not as a result of manual
   * {@link Cache#invalidate invalidations}.
   *
   * @param weight the weight of the evicted entry
   */
  default void recordEviction(int weight) {
    // This method will be abstract in version 3.0.0
    recordEviction();
  }

  /**
   * Returns a snapshot of this counter's values. Note that this may be an inconsistent view, as it
   * may be interleaved with update operations.
   *
   * @return a snapshot of this counter's values
   */
  @Nonnull
  CacheStats snapshot();

  /**
   * Returns an accumulator that does not record any cache events.
   *
   * @return an accumulator that does not record metrics
   */
  static @Nonnull StatsCounter disabledStatsCounter() {
    return DisabledStatsCounter.INSTANCE;
  }

  /**
   * Returns an accumulator that suppresses and logs any exception thrown by the delegate
   * <tt>statsCounter</tt>.
   *
   * @param statsCounter the accumulator to delegate to
   * @return an accumulator that suppresses and logs any exception thrown by the delegate
   */
  static @Nonnull StatsCounter guardedStatsCounter(@Nonnull StatsCounter statsCounter) {
    return new GuardedStatsCounter(statsCounter);
  }
}
