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

import java.util.Objects;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.errorprone.annotations.Immutable;

/**
 * Statistics about the performance of a {@link Cache}.
 * <p>
 * Cache statistics are incremented according to the following rules:
 * <ul>
 *   <li>When a cache lookup encounters an existing cache entry {@code hitCount} is incremented.
 *   <li>When a cache lookup first encounters a missing cache entry, a new entry is loaded.
 *   <ul>
 *     <li>After successfully loading an entry {@code missCount} and {@code loadSuccessCount} are
 *         incremented, and the total loading time, in nanoseconds, is added to
 *         {@code totalLoadTime}.
 *     <li>When an exception is thrown while loading an entry or if the loaded value is {code null},
 *         {@code missCount} and {@code loadFailureCount} are incremented, and the total loading
 *         time, in nanoseconds, is added to {@code totalLoadTime}.
 *     <li>Cache lookups that encounter a missing cache entry that is still loading will wait
 *         for loading to complete (whether successful or not) and then increment {@code missCount}.
 *   </ul>
 *   <li>When an entry is computed through the {@linkplain Cache#asMap asMap} the
 *       {@code loadSuccessCount} or {@code loadFailureCount} is incremented.
 *   <li>When an entry is evicted from the cache, {@code evictionCount} is incremented and the
 *       weight added to {@code evictionWeight}.
 *   <li>No stats are modified when a cache entry is invalidated or manually removed.
 *   <li>No stats are modified by non-computing operations invoked on the
 *       {@linkplain Cache#asMap asMap} view of the cache.
 * </ul>
 * <p>
 * A lookup is specifically defined as an invocation of one of the methods
 * {@link LoadingCache#get(Object)}, {@link Cache#get(Object, java.util.function.Function)}, or
 * {@link LoadingCache#getAll(Iterable)}.
 * <p>
 * This is a <em>value-based</em> class; use of identity-sensitive operations (including reference
 * equality ({@code ==}), identity hash code, or synchronization) on instances of {@code CacheStats}
 * may have unpredictable results and should be avoided.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Immutable
public final class CacheStats {
  private static final CacheStats EMPTY_STATS = CacheStats.of(0L, 0L, 0L, 0L, 0L, 0L, 0L);

  private final long hitCount;
  private final long missCount;
  private final long loadSuccessCount;
  private final long loadFailureCount;
  private final long totalLoadTime;
  private final long evictionCount;
  private final long evictionWeight;

  private CacheStats(@NonNegative long hitCount, @NonNegative long missCount,
      @NonNegative long loadSuccessCount, @NonNegative long loadFailureCount,
      @NonNegative long totalLoadTime, @NonNegative long evictionCount,
      @NonNegative long evictionWeight) {
    if ((hitCount < 0) || (missCount < 0) || (loadSuccessCount < 0) || (loadFailureCount < 0)
        || (totalLoadTime < 0) || (evictionCount < 0) || (evictionWeight < 0)) {
      throw new IllegalArgumentException();
    }
    this.hitCount = hitCount;
    this.missCount = missCount;
    this.loadSuccessCount = loadSuccessCount;
    this.loadFailureCount = loadFailureCount;
    this.totalLoadTime = totalLoadTime;
    this.evictionCount = evictionCount;
    this.evictionWeight = evictionWeight;
  }

  /**
   * Returns a {@code CacheStats} representing the specified statistics.
   *
   * @param hitCount the number of cache hits
   * @param missCount the number of cache misses
   * @param loadSuccessCount the number of successful cache loads
   * @param loadFailureCount the number of failed cache loads
   * @param totalLoadTime the total load time (success and failure)
   * @param evictionCount the number of entries evicted from the cache
   * @param evictionWeight the sum of weights of entries evicted from the cache
   * @return a {@code CacheStats} representing the specified statistics
   */
  public static CacheStats of(@NonNegative long hitCount, @NonNegative long missCount,
      @NonNegative long loadSuccessCount, @NonNegative long loadFailureCount,
      @NonNegative long totalLoadTime, @NonNegative long evictionCount,
      @NonNegative long evictionWeight) {
    // Many parameters of the same type in a row is a bad thing, but this class is not constructed
    // by end users and is too fine-grained for a builder.
    return new CacheStats(hitCount, missCount, loadSuccessCount,
        loadFailureCount, totalLoadTime, evictionCount, evictionWeight);
  }

  /**
   * Returns a statistics instance where no cache events have been recorded.
   *
   * @return an empty statistics instance
   */
  public static CacheStats empty() {
    return EMPTY_STATS;
  }

  /**
   * Returns the number of times {@link Cache} lookup methods have returned either a cached or
   * uncached value. This is defined as {@code hitCount + missCount}.
   * <p>
   * <b>Note:</b> the values of the metrics are undefined in case of overflow (though it is
   * guaranteed not to throw an exception). If you require specific handling, we recommend
   * implementing your own stats collector.
   *
   * @return the {@code hitCount + missCount}
   */
  public @NonNegative long requestCount() {
    return saturatedAdd(hitCount, missCount);
  }

  /**
   * Returns the number of times {@link Cache} lookup methods have returned a cached value.
   *
   * @return the number of times {@link Cache} lookup methods have returned a cached value
   */
  public @NonNegative long hitCount() {
    return hitCount;
  }

  /**
   * Returns the ratio of cache requests which were hits. This is defined as
   * {@code hitCount / requestCount}, or {@code 1.0} when {@code requestCount == 0}. Note that
   * {@code hitRate + missRate =~ 1.0}.
   *
   * @return the ratio of cache requests which were hits
   */
  public @NonNegative double hitRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 1.0 : (double) hitCount / requestCount;
  }

  /**
   * Returns the number of times {@link Cache} lookup methods have returned an uncached (newly
   * loaded) value, or null. Multiple concurrent calls to {@link Cache} lookup methods on an absent
   * value can result in multiple misses, all returning the results of a single cache load
   * operation.
   *
   * @return the number of times {@link Cache} lookup methods have returned an uncached (newly
   *         loaded) value, or null
   */
  public @NonNegative long missCount() {
    return missCount;
  }

  /**
   * Returns the ratio of cache requests which were misses. This is defined as
   * {@code missCount / requestCount}, or {@code 0.0} when {@code requestCount == 0}.
   * Note that {@code hitRate + missRate =~ 1.0}. Cache misses include all requests which
   * weren't cache hits, including requests which resulted in either successful or failed loading
   * attempts, and requests which waited for other threads to finish loading. It is thus the case
   * that {@code missCount >= loadSuccessCount + loadFailureCount}. Multiple
   * concurrent misses for the same key will result in a single load operation.
   *
   * @return the ratio of cache requests which were misses
   */
  public @NonNegative double missRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : (double) missCount / requestCount;
  }

  /**
   * Returns the total number of times that {@link Cache} lookup methods attempted to load new
   * values. This includes both successful load operations, as well as those that threw exceptions.
   * This is defined as {@code loadSuccessCount + loadFailureCount}.
   * <p>
   * <b>Note:</b> the values of the metrics are undefined in case of overflow (though it is
   * guaranteed not to throw an exception). If you require specific handling, we recommend
   * implementing your own stats collector.
   *
   * @return the {@code loadSuccessCount + loadFailureCount}
   */
  public @NonNegative long loadCount() {
    return saturatedAdd(loadSuccessCount, loadFailureCount);
  }

  /**
   * Returns the number of times {@link Cache} lookup methods have successfully loaded a new value.
   * This is always incremented in conjunction with {@link #missCount}, though {@code missCount}
   * is also incremented when an exception is encountered during cache loading (see
   * {@link #loadFailureCount}). Multiple concurrent misses for the same key will result in a
   * single load operation.
   *
   * @return the number of times {@link Cache} lookup methods have successfully loaded a new value
   */
  public @NonNegative long loadSuccessCount() {
    return loadSuccessCount;
  }

  /**
   * Returns the number of times {@link Cache} lookup methods failed to load a new value, either
   * because no value was found or an exception was thrown while loading. This is always incremented
   * in conjunction with {@code missCount}, though {@code missCount} is also incremented when cache
   * loading completes successfully (see {@link #loadSuccessCount}). Multiple concurrent misses for
   * the same key will result in a single load operation.
   *
   * @return the number of times {@link Cache} lookup methods failed to load a new value
   */
  public @NonNegative long loadFailureCount() {
    return loadFailureCount;
  }

  /**
   * Returns the ratio of cache loading attempts which threw exceptions. This is defined as
   * {@code loadFailureCount / (loadSuccessCount + loadFailureCount)}, or {@code 0.0} when
   * {@code loadSuccessCount + loadFailureCount == 0}.
   * <p>
   * <b>Note:</b> the values of the metrics are undefined in case of overflow (though it is
   * guaranteed not to throw an exception). If you require specific handling, we recommend
   * implementing your own stats collector.
   *
   * @return the ratio of cache loading attempts which threw exceptions
   */
  public @NonNegative double loadFailureRate() {
    long totalLoadCount = saturatedAdd(loadSuccessCount, loadFailureCount);
    return (totalLoadCount == 0) ? 0.0 : (double) loadFailureCount / totalLoadCount;
  }

  /**
   * Returns the total number of nanoseconds the cache has spent loading new values. This can be
   * used to calculate the miss penalty. This value is increased every time {@code loadSuccessCount}
   * or {@code loadFailureCount} is incremented.
   *
   * @return the total number of nanoseconds the cache has spent loading new values
   */
  public @NonNegative long totalLoadTime() {
    return totalLoadTime;
  }

  /**
   * Returns the average number of nanoseconds spent loading new values. This is defined as
   * {@code totalLoadTime / (loadSuccessCount + loadFailureCount)}.
   * <p>
   * <b>Note:</b> the values of the metrics are undefined in case of overflow (though it is
   * guaranteed not to throw an exception). If you require specific handling, we recommend
   * implementing your own stats collector.
   *
   * @return the average number of nanoseconds spent loading new values
   */
  public @NonNegative double averageLoadPenalty() {
    long totalLoadCount = saturatedAdd(loadSuccessCount, loadFailureCount);
    return (totalLoadCount == 0) ? 0.0 : (double) totalLoadTime / totalLoadCount;
  }

  /**
   * Returns the number of times an entry has been evicted. This count does not include manual
   * {@linkplain Cache#invalidate invalidations}.
   *
   * @return the number of times an entry has been evicted
   */
  public @NonNegative long evictionCount() {
    return evictionCount;
  }

  /**
   * Returns the sum of weights of evicted entries. This total does not include manual
   * {@linkplain Cache#invalidate invalidations}.
   *
   * @return the sum of weights of evicted entities
   */
  public @NonNegative long evictionWeight() {
    return evictionWeight;
  }

  /**
   * Returns a new {@code CacheStats} representing the difference between this {@code CacheStats}
   * and {@code other}. Negative values, which aren't supported by {@code CacheStats} will be
   * rounded up to zero.
   *
   * @param other the statistics to subtract with
   * @return the difference between this instance and {@code other}
   */
  public CacheStats minus(CacheStats other) {
    return CacheStats.of(
        Math.max(0L, saturatedSubtract(hitCount, other.hitCount)),
        Math.max(0L, saturatedSubtract(missCount, other.missCount)),
        Math.max(0L, saturatedSubtract(loadSuccessCount, other.loadSuccessCount)),
        Math.max(0L, saturatedSubtract(loadFailureCount, other.loadFailureCount)),
        Math.max(0L, saturatedSubtract(totalLoadTime, other.totalLoadTime)),
        Math.max(0L, saturatedSubtract(evictionCount, other.evictionCount)),
        Math.max(0L, saturatedSubtract(evictionWeight, other.evictionWeight)));
  }

  /**
   * Returns a new {@code CacheStats} representing the sum of this {@code CacheStats} and
   * {@code other}.
   * <p>
   * <b>Note:</b> the values of the metrics are undefined in case of overflow (though it is
   * guaranteed not to throw an exception). If you require specific handling, we recommend
   * implementing your own stats collector.
   *
   * @param other the statistics to add with
   * @return the sum of the statistics
   */
  public CacheStats plus(CacheStats other) {
    return CacheStats.of(
        saturatedAdd(hitCount, other.hitCount),
        saturatedAdd(missCount, other.missCount),
        saturatedAdd(loadSuccessCount, other.loadSuccessCount),
        saturatedAdd(loadFailureCount, other.loadFailureCount),
        saturatedAdd(totalLoadTime, other.totalLoadTime),
        saturatedAdd(evictionCount, other.evictionCount),
        saturatedAdd(evictionWeight, other.evictionWeight));
  }

  /**
   * Returns the difference of {@code a} and {@code b} unless it would overflow or underflow in
   * which case {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
   */
  @SuppressWarnings("ShortCircuitBoolean")
  private static long saturatedSubtract(long a, long b) {
    long naiveDifference = a - b;
    if ((a ^ b) >= 0 | (a ^ naiveDifference) >= 0) {
      // If a and b have the same signs or a has the same sign as the result then there was no
      // overflow, return.
      return naiveDifference;
    }
    // we did over/under flow
    return Long.MAX_VALUE + ((naiveDifference >>> (Long.SIZE - 1)) ^ 1);
  }

  /**
   * Returns the sum of {@code a} and {@code b} unless it would overflow or underflow in which case
   * {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
   */
  @SuppressWarnings("ShortCircuitBoolean")
  private static long saturatedAdd(long a, long b) {
    long naiveSum = a + b;
    if ((a ^ b) < 0 | (a ^ naiveSum) >= 0) {
      // If a and b have different signs or a has the same sign as the result then there was no
      // overflow, return.
      return naiveSum;
    }
    // we did over/under flow, if the sign is negative we should return MAX otherwise MIN
    return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
  }

  @Override
  public int hashCode() {
    return Objects.hash(hitCount, missCount, loadSuccessCount,
        loadFailureCount, totalLoadTime, evictionCount, evictionWeight);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof CacheStats)) {
      return false;
    }
    CacheStats other = (CacheStats) o;
    return hitCount == other.hitCount
        && missCount == other.missCount
        && loadSuccessCount == other.loadSuccessCount
        && loadFailureCount == other.loadFailureCount
        && totalLoadTime == other.totalLoadTime
        && evictionCount == other.evictionCount
        && evictionWeight == other.evictionWeight;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + '{'
        + "hitCount=" + hitCount + ", "
        + "missCount=" + missCount + ", "
        + "loadSuccessCount=" + loadSuccessCount + ", "
        + "loadFailureCount=" + loadFailureCount + ", "
        + "totalLoadTime=" + totalLoadTime + ", "
        + "evictionCount=" + evictionCount + ", "
        + "evictionWeight=" + evictionWeight
        + '}';
  }
}
