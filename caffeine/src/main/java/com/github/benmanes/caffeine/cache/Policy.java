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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.Caffeine.toNanosSaturated;

import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * An access point for inspecting and performing low-level operations based on the cache's runtime
 * characteristics. These operations are optional and dependent on how the cache was constructed
 * and what abilities the implementation exposes.
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NullMarked
public interface Policy<K, V> {

  /**
   * Returns whether the cache statistics are being accumulated.
   *
   * @return if cache statistics are being recorded
   */
  boolean isRecordingStats();

  /**
   * Returns the value associated with the {@code key} in this cache, or {@code null} if there is no
   * cached value for the {@code key}. Unlike {@link Cache#getIfPresent(Object)}, this method does
   * not produce any side effects such as updating statistics, the eviction policy, resetting the
   * expiration time, or triggering a refresh.
   *
   * @param key the key whose associated value is to be returned
   * @return the value to which the specified key is mapped, or {@code null} if this cache contains
   *         no mapping for the key
   * @throws NullPointerException if the specified key is null
   */
  @Nullable
  V getIfPresentQuietly(K key);

  /**
   * Returns the cache entry associated with the {@code key} in this cache, or {@code null} if there
   * is no cached value for the {@code key}. Unlike {@link Cache#getIfPresent(Object)}, this method
   * does not produce any side effects such as updating statistics, the eviction policy, resetting
   * the expiration time, or triggering a refresh.
   *
   * @param key the key whose associated value is to be returned
   * @return the entry mapping for the specified key, or {@code null} if this cache contains no
   *         mapping for the key
   * @throws NullPointerException if the specified key is null
   */
  default @Nullable CacheEntry<K, V> getEntryIfPresentQuietly(K key) {
    // This method was added & implemented in version 3.0.6
    throw new UnsupportedOperationException();
  }

  /**
   * Returns an unmodifiable snapshot {@link Map} view of the in-flight refresh operations.
   *
   * @return a snapshot view of the in-flight refresh operations
   */
  Map<K, CompletableFuture<V>> refreshes();

  /**
   * Returns access to perform operations based on the maximum size or maximum weight eviction
   * policy. If the cache was not constructed with a size-based bound or the implementation does
   * not support these operations, an empty {@link Optional} is returned.
   *
   * @return access to low-level operations for this cache if an eviction policy is used
   */
  Optional<Eviction<K, V>> eviction();

  /**
   * Returns access to perform operations based on the time-to-idle expiration policy. This policy
   * determines that an entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, the most recent replacement of its value, or its last
   * access. Access time is reset by all cache read and write operations (including
   * {@code Cache.asMap().get(Object)} and {@code Cache.asMap().put(K, V)}), but not by operations
   * on the collection-views of {@link Cache#asMap}.
   * <p>
   * If the cache was not constructed with access-based expiration or the implementation does not
   * support these operations, an empty {@link Optional} is returned.
   *
   * @return access to low-level operations for this cache if a time-to-idle expiration policy is
   *         used
   */
  Optional<FixedExpiration<K, V>> expireAfterAccess();

  /**
   * Returns access to perform operations based on the time-to-live expiration policy. This policy
   * determines that an entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, or the most recent replacement of its value.
   * <p>
   * If the cache was not constructed with write-based expiration or the implementation does not
   * support these operations, an empty {@link Optional} is returned.
   *
   * @return access to low-level operations for this cache if a time-to-live expiration policy is
   *         used
   */
  Optional<FixedExpiration<K, V>> expireAfterWrite();

  /**
   * Returns access to perform operations based on the variable expiration policy. This policy
   * determines that an entry should be automatically removed from the cache once a per-entry
   * duration has elapsed.
   * <p>
   * If the cache was not constructed with variable expiration or the implementation does not
   * support these operations, an empty {@link Optional} is returned.
   *
   * @return access to low-level operations for this cache if a variable expiration policy is used
   */
  Optional<VarExpiration<K, V>> expireVariably();

  /**
   * Returns access to perform operations based on the time-to-live refresh policy. This policy
   * determines that an entry should be automatically reloaded once a fixed duration has elapsed
   * after the entry's creation, or the most recent replacement of its value.
   * <p>
   * If the cache was not constructed with write-based refresh or the implementation does not
   * support these operations, an empty {@link Optional} is returned.
   *
   * @return access to low-level operations for this cache if a time-to-live refresh policy is used
   */
  Optional<FixedRefresh<K, V>> refreshAfterWrite();

  /**
   * The low-level operations for a cache with a size-based eviction policy.
   *
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  interface Eviction<K, V> {

    /**
     * Returns whether the cache is bounded by a maximum size or maximum weight.
     *
     * @return if the size bounding takes into account the entry's weight
     */
    boolean isWeighted();

    /**
     * Returns the weight of the entry. If this cache does not use a weighted size bound or does not
     * support querying for the entry's weight, then the {@link OptionalInt} will be empty.
     *
     * @param key the key for the entry being queried
     * @return the weight if the entry is present in the cache
     * @throws NullPointerException if the specified key is null
     */
    OptionalInt weightOf(K key);

    /**
     * Returns the approximate accumulated weight of entries in this cache. If this cache does not
     * use a weighted size bound, then the {@link OptionalLong} will be empty.
     *
     * @return the combined weight of the values in this cache
     */
    OptionalLong weightedSize();

    /**
     * Returns the maximum total weighted or unweighted size of this cache, depending on how the
     * cache was constructed. This value can be best understood by inspecting {@link #isWeighted()}.
     *
     * @return the maximum size bounding, which may be either weighted or unweighted
     */
    long getMaximum();

    /**
     * Specifies the maximum total size of this cache. This value may be interpreted as the weighted
     * or unweighted threshold size based on how this cache was constructed. If the cache currently
     * exceeds the new maximum size this operation eagerly evict entries until the cache shrinks to
     * the appropriate size.
     * <p>
     * Note that some implementations may have an internal inherent bound on the maximum total size.
     * If the value specified exceeds that bound, then the value is set to the internal maximum.
     *
     * @param maximum the maximum, interpreted as weighted or unweighted size depending on how this
     *        cache was constructed
     * @throws IllegalArgumentException if the maximum size specified is negative
     */
    void setMaximum(long maximum);

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries least likely to be retained (coldest) to the entries
     * most likely to be retained (hottest). This order is determined by the eviction policy's best
     * guess at the time of creating this snapshot view.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries within the eviction policy's exclusive lock.
     *
     * @param limit the maximum size of the returned map (use {@link Integer#MAX_VALUE} to disregard
     *        the limit)
     * @return a snapshot view of the cache from the coldest entry to the hottest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    Map<K, V> coldest(int limit);

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries least likely to be retained (coldest) to the entries
     * most likely to be retained (hottest). This order is determined by the eviction policy's best
     * guess at the time of creating this snapshot view. If the cache is bounded by a maximum size
     * rather than a maximum weight, then this method is equivalent to {@link #coldest(int)}.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries within the eviction policy's exclusive lock.
     *
     * @param weightLimit the maximum weight of the returned map (use {@link Long#MAX_VALUE} to
     *        disregard the limit)
     * @return a snapshot view of the cache from the coldest entry to the hottest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    default Map<K, V> coldestWeighted(long weightLimit) {
      // This method was added & implemented in version 3.0.4
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the computed result from the ordered traversal of the cache entries. The order of
     * iteration is from the entries least likely to be retained (coldest) to the entries most
     * likely to be retained (hottest). This order is determined by the eviction policy's best guess
     * at the time of creating this computation.
     * <p>
     * Usage example:
     * <pre>{@code
     *   List<K> tenColdestKeys = cache.policy().eviction().orElseThrow()
     *       .coldest(stream -> stream.map(Map.Entry::getKey).limit(10).toList());
     * }</pre>
     * <p>
     * Beware that this computation is performed within the eviction policy's exclusive lock, so the
     * computation should be short and simple. While the computation is in progress further eviction
     * maintenance will be halted.
     *
     * @param <T> the type of the result of the mappingFunction
     * @param mappingFunction the mapping function to compute a value
     * @return the computed value
     * @throws NullPointerException if the mappingFunction is null
     * @throws RuntimeException or Error if the mappingFunction does so
     * @throws ConcurrentModificationException if the computation detectably reads or writes an
     *         entry in this cache
     */
    default <T> T coldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries most likely to be retained (hottest) to the entries
     * least likely to be retained (coldest). This order is determined by the eviction policy's best
     * guess at the time of creating this snapshot view.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries within the eviction policy's exclusive lock.
     *
     * @param limit the maximum size of the returned map (use {@link Integer#MAX_VALUE} to disregard
     *        the limit)
     * @return a snapshot view of the cache from the hottest entry to the coldest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    Map<K, V> hottest(int limit);

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries most likely to be retained (hottest) to the entries
     * least likely to be retained (coldest). This order is determined by the eviction policy's best
     * guess at the time of creating this snapshot view. If the cache is bounded by a maximum size
     * rather than a maximum weight, then this method is equivalent to {@link #hottest(int)}.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries within the eviction policy's exclusive lock.
     *
     * @param weightLimit the maximum weight of the returned map (use {@link Long#MAX_VALUE} to
     *        disregard the limit)
     * @return a snapshot view of the cache from the hottest entry to the coldest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    default Map<K, V> hottestWeighted(long weightLimit) {
      // This method was added & implemented in version 3.0.4
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the computed result from the ordered traversal of the cache entries. The order of
     * iteration is from the entries most likely to be retained (hottest) to the entries least
     * likely to be retained (coldest). This order is determined by the eviction policy's best guess
     * at the time of creating this computation.
     * <p>
     * Usage example:
     * <pre>{@code
     *   List<K> tenHottestKeys = cache.policy().eviction().orElseThrow()
     *       .hottest(stream -> stream.map(Map.Entry::getKey).limit(10).toList());
     * }</pre>
     * <p>
     * Beware that this computation is performed within the eviction policy's exclusive lock, so the
     * computation should be short and simple. While the computation is in progress further eviction
     * maintenance will be halted.
     *
     * @param <T> the type of the result of the mappingFunction
     * @param mappingFunction the mapping function to compute a value
     * @return the computed value
     * @throws NullPointerException if the mappingFunction is null
     * @throws RuntimeException or Error if the mappingFunction does so
     * @throws ConcurrentModificationException if the computation detectably reads or writes an
     *         entry in this cache
     */
    default <T> T hottest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }
  }

  /**
   * The low-level operations for a cache with a fixed expiration policy.
   *
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  interface FixedExpiration<K, V> {

    /**
     * Returns the age of the entry based on the expiration policy. The entry's age is the cache's
     * estimate of the amount of time since the entry's expiration was last reset.
     * <p>
     * An expiration policy uses the age to determine if an entry is fresh or stale by comparing it
     * to the freshness lifetime. This is calculated as {@code fresh = freshnessLifetime > age}
     * where {@code freshnessLifetime = expires - currentTime}.
     *
     * @param key the key for the entry being queried
     * @param unit the unit that {@code age} is expressed in
     * @return the age if the entry is present in the cache
     * @throws NullPointerException if the specified key is null
     */
    OptionalLong ageOf(K key, TimeUnit unit);

    /**
     * Returns the age of the entry based on the expiration policy. The entry's age is the cache's
     * estimate of the amount of time since the entry's expiration was last reset.
     * <p>
     * An expiration policy uses the age to determine if an entry is fresh or stale by comparing it
     * to the freshness lifetime. This is calculated as {@code fresh = freshnessLifetime > age}
     * where {@code freshnessLifetime = expires - currentTime}.
     *
     * @param key the key for the entry being queried
     * @return the age if the entry is present in the cache
     * @throws NullPointerException if the specified key is null
     */
    default Optional<Duration> ageOf(K key) {
      OptionalLong duration = ageOf(key, TimeUnit.NANOSECONDS);
      return duration.isPresent()
          ? Optional.of(Duration.ofNanos(duration.getAsLong()))
          : Optional.empty();
    }

    /**
     * Returns the fixed duration used to determine if an entry should be automatically removed due
     * to elapsing this time bound. An entry is considered fresh if its age is less than this
     * duration, and stale otherwise. The expiration policy determines when the entry's age is
     * reset.
     *
     * @param unit the unit that duration is expressed in
     * @return the length of time after which an entry should be automatically removed
     * @throws NullPointerException if the unit is null
     */
    long getExpiresAfter(TimeUnit unit);

    /**
     * Returns the fixed duration used to determine if an entry should be automatically removed due
     * to elapsing this time bound. An entry is considered fresh if its age is less than this
     * duration, and stale otherwise. The expiration policy determines when the entry's age is
     * reset.
     *
     * @return the length of time after which an entry should be automatically removed
     */
    default Duration getExpiresAfter() {
      return Duration.ofNanos(getExpiresAfter(TimeUnit.NANOSECONDS));
    }

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed
     * duration has elapsed. The expiration policy determines when the entry's age is reset.
     *
     * @param duration the length of time after which an entry should be automatically removed
     * @param unit the unit that {@code duration} is expressed in
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the unit is null
     */
    void setExpiresAfter(long duration, TimeUnit unit);

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed
     * duration has elapsed. The expiration policy determines when the entry's age is reset.
     *
     * @param duration the length of time after which an entry should be automatically removed
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the duration is null
     */
    default void setExpiresAfter(Duration duration) {
      setExpiresAfter(toNanosSaturated(duration), TimeUnit.NANOSECONDS);
    }

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries most likely to expire (oldest) to the entries least
     * likely to expire (youngest). This order is determined by the expiration policy's best guess
     * at the time of creating this snapshot view.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries.
     *
     * @param limit the maximum size of the returned map (use {@link Integer#MAX_VALUE} to disregard
     *        the limit)
     * @return a snapshot view of the cache from the oldest entry to the youngest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    Map<K, V> oldest(int limit);

    /**
     * Returns the computed result from the ordered traversal of the cache entries. The oorder of
     * iteration is from the entries most likely to expire (oldest) to the entries least likely to
     * expire (youngest). This order is determined by the expiration policy's best guess at the time
     * of creating this computation.
     * <p>
     * Usage example:
     * <pre>{@code
     *   List<K> tenOldestKeys = cache.policy().expireAfterWrite().orElseThrow()
     *       .oldest(stream -> stream.map(Map.Entry::getKey).limit(10).toList());
     * }</pre>
     * <p>
     * Beware that this computation is performed within the eviction policy's exclusive lock, so the
     * computation should be short and simple. While the computation is in progress further eviction
     * maintenance will be halted.
     *
     * @param <T> the type of the result of the mappingFunction
     * @param mappingFunction the mapping function to compute a value
     * @return the computed value
     * @throws NullPointerException if the mappingFunction is null
     * @throws RuntimeException or Error if the mappingFunction does so
     */
    default <T> T oldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries least likely to expire (youngest) to the entries most
     * likely to expire (oldest). This order is determined by the expiration policy's best guess at
     * the time of creating this snapshot view.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries.
     *
     * @param limit the maximum size of the returned map (use {@link Integer#MAX_VALUE} to disregard
     *        the limit)
     * @return a snapshot view of the cache from the youngest entry to the oldest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    Map<K, V> youngest(int limit);

    /**
     * Returns the computed result from the ordered traversal of the cache entries. The order of
     * iteration is from the entries least likely to expire (youngest) to the entries most likely to
     * expire (oldest). This order is determined by the expiration policy's best guess at the time
     * of creating this computation.
     * <p>
     * Usage example:
     * <pre>{@code
     *   List<K> tenYoungestKeys = cache.policy().expireAfterWrite().orElseThrow()
     *       .youngest(stream -> stream.map(Map.Entry::getKey).limit(10).toList());
     * }</pre>
     * <p>
     * Beware that this computation is performed within the eviction policy's exclusive lock, so the
     * computation should be short and simple. While the computation is in progress further eviction
     * maintenance will be halted.
     *
     * @param <T> the type of the result of the mappingFunction
     * @param mappingFunction the mapping function to compute a value
     * @return the computed value
     * @throws NullPointerException if the mappingFunction is null
     * @throws RuntimeException or Error if the mappingFunction does so
     */
    default <T> T youngest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }
  }

  /**
   * The low-level operations for a cache with a variable expiration policy.
   *
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  interface VarExpiration<K, V> {

    /**
     * Returns the duration until the entry should be automatically removed. The expiration policy
     * determines when the entry's duration is reset.
     *
     * @param key the key for the entry being queried
     * @param unit the unit that {@code age} is expressed in
     * @return the duration if the entry is present in the cache
     * @throws NullPointerException if the specified key or unit is null
     */
    OptionalLong getExpiresAfter(K key, TimeUnit unit);

    /**
     * Returns the duration until the entry should be automatically removed. The expiration policy
     * determines when the entry's duration is reset.
     *
     * @param key the key for the entry being queried
     * @return the duration if the entry is present in the cache
     * @throws NullPointerException if the specified key is null
     */
    default Optional<Duration> getExpiresAfter(K key) {
      OptionalLong duration = getExpiresAfter(key, TimeUnit.NANOSECONDS);
      return duration.isPresent()
          ? Optional.of(Duration.ofNanos(duration.getAsLong()))
          : Optional.empty();
    }

    /**
     * Specifies that the entry should be automatically removed from the cache once the duration has
     * elapsed. The expiration policy determines when the entry's age is reset.
     *
     * @param key the key for the entry being set
     * @param duration the length of time from now when the entry should be automatically removed
     * @param unit the unit that {@code duration} is expressed in
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key or unit is null
     */
    void setExpiresAfter(K key, long duration, TimeUnit unit);

    /**
     * Specifies that the entry should be automatically removed from the cache once the duration has
     * elapsed. The expiration policy determines when the entry's age is reset.
     *
     * @param key the key for the entry being set
     * @param duration the length of time from now when the entry should be automatically removed
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key or duration is null
     */
    default void setExpiresAfter(K key, Duration duration) {
      setExpiresAfter(key, toNanosSaturated(duration), TimeUnit.NANOSECONDS);
    }

    /**
     * Associates the {@code value} with the {@code key} in this cache if the specified key is not
     * already associated with a value. This method differs from {@link Map#putIfAbsent} by
     * substituting the configured {@link Expiry} with the specified duration, has no effect on the
     * duration if the entry was present, and returns the success rather than a value.
     *
     * @param key the key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param duration the length of time from now when the entry should be automatically removed
     * @param unit the unit that {@code duration} is expressed in
     * @return the previous value associated with the specified key, or {@code null} if there was no
     *         mapping for the key.
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key, value, or unit is null
     */
    @Nullable V putIfAbsent(K key, V value, long duration, TimeUnit unit);

    /**
     * Associates the {@code value} with the {@code key} in this cache if the specified key is not
     * already associated with a value. This method differs from {@link Map#putIfAbsent} by
     * substituting the configured {@link Expiry} with the specified duration, has no effect on the
     * duration if the entry was present, and returns the success rather than a value.
     *
     * @param key the key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param duration the length of time from now when the entry should be automatically removed
     * @return the previous value associated with the specified key, or {@code null} if there was no
     *         mapping for the key.
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key, value, or duration is null
     */
    default @Nullable V putIfAbsent(K key, V value, Duration duration) {
      return putIfAbsent(key, value, toNanosSaturated(duration), TimeUnit.NANOSECONDS);
    }

    /**
     * Associates the {@code value} with the {@code key} in this cache. If the cache previously
     * contained a value associated with the {@code key}, the old value is replaced by the new
     * {@code value}. This method differs from {@link Cache#put} by substituting the configured
     * {@link Expiry} with the specified duration.
     *
     * @param key the key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param duration the length of time from now when the entry should be automatically removed
     * @param unit the unit that {@code duration} is expressed in
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     *         mapping for {@code key}.
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key, value, or unit is null
     */
    @Nullable V put(K key, V value, long duration, TimeUnit unit);

    /**
     * Associates the {@code value} with the {@code key} in this cache. If the cache previously
     * contained a value associated with the {@code key}, the old value is replaced by the new
     * {@code value}. This method differs from {@link Cache#put} by substituting the
     * configured {@link Expiry} with the specified duration.
     *
     * @param key the key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @param duration the length of time from now when the entry should be automatically removed
     * @return the previous value associated with {@code key}, or {@code null} if there was no
     *         mapping for {@code key}.
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key, value, or duration is null
     */
    default @Nullable V put(K key, V value, Duration duration) {
      return put(key, value, toNanosSaturated(duration), TimeUnit.NANOSECONDS);
    }

    /**
     * Attempts to compute a mapping for the specified key and its current mapped value (or
     * {@code null} if there is no current mapping). The entire method invocation is performed
     * atomically. The supplied function is invoked exactly once per invocation of this method. Some
     * attempted update operations on this cache by other threads may be blocked while the
     * computation is in progress, so the computation should be short and simple. This method
     * differs from {@code cache.asMap().compute(key, remappingFunction)} by substituting the
     * configured {@link Expiry} with the specified duration.
     * <p>
     * <b>Warning:</b> the {@code remappingFunction} <b>must not</b> attempt to update any other
     * mappings of this cache.
     *
     * @param key key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     * @param duration the length of time from now when the entry should be automatically removed
     * @return the new value associated with the specified key, or null if none
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the specified key, remappingFunction, or duration is null
     * @throws IllegalStateException if the computation detectably attempts a recursive update to
     *         this cache that would otherwise never complete
     * @throws RuntimeException or Error if the remappingFunction does so, in which case the mapping
     *         is unchanged
     */
    default @Nullable V compute(K key,
        BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction,
        Duration duration) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries most likely to expire (oldest) to the entries least
     * likely to expire (youngest). This order is determined by the expiration policy's best guess
     * at the time of creating this snapshot view.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries.
     *
     * @param limit the maximum size of the returned map (use {@link Integer#MAX_VALUE} to disregard
     *        the limit)
     * @return a snapshot view of the cache from the oldest entry to the youngest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    Map<K, V> oldest(int limit);

    /**
     * Returns the computed result from the ordered traversal of the cache entries. The oorder of
     * iteration is from the entries most likely to expire (oldest) to the entries least likely to
     * expire (youngest). This order is determined by the expiration policy's best guess at the time
     * of creating this computation.
     * <p>
     * Usage example:
     * <pre>{@code
     *   List<K> tenOldestKeys = cache.policy().expireAfterVariably().orElseThrow()
     *       .oldest(stream -> stream.map(Map.Entry::getKey).limit(10).toList());
     * }</pre>
     * <p>
     * Beware that this computation is performed within the eviction policy's exclusive lock, so the
     * computation should be short and simple. While the computation is in progress further eviction
     * maintenance will be halted.
     *
     * @param <T> the type of the result of the mappingFunction
     * @param mappingFunction the mapping function to compute a value
     * @return the computed value
     * @throws NullPointerException if the mappingFunction is null
     * @throws RuntimeException or Error if the mappingFunction does so
     */
    default <T> T oldest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }

    /**
     * Returns an unmodifiable snapshot {@link Map} view of the cache with ordered traversal. The
     * order of iteration is from the entries least likely to expire (youngest) to the entries most
     * likely to expire (oldest). This order is determined by the expiration policy's best guess at
     * the time of creating this snapshot view.
     * <p>
     * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of the page replacement policy, determining the retention ordering
     * requires a traversal of the entries.
     *
     * @param limit the maximum size of the returned map (use {@link Integer#MAX_VALUE} to disregard
     *        the limit)
     * @return a snapshot view of the cache from the youngest entry to the oldest
     * @throws IllegalArgumentException if the limit specified is negative
     */
    Map<K, V> youngest(int limit);

    /**
     * Returns the computed result from the ordered traversal of the cache entries. The order of
     * iteration is from the entries least likely to expire (youngest) to the entries most likely to
     * expire (oldest). This order is determined by the expiration policy's best guess at the time
     * of creating this computation.
     * <p>
     * Usage example:
     * <pre>{@code
     *   List<K> tenYoungestKeys = cache.policy().expireAfterVariably().orElseThrow()
     *       .youngest(stream -> stream.map(Map.Entry::getKey).limit(10).toList());
     * }</pre>
     * <p>
     * Beware that this computation is performed within the eviction policy's exclusive lock, so the
     * computation should be short and simple. While the computation is in progress further eviction
     * maintenance will be halted.
     *
     * @param <T> the type of the result of the mappingFunction
     * @param mappingFunction the mapping function to compute a value
     * @return the computed value
     * @throws NullPointerException if the mappingFunction is null
     * @throws RuntimeException or Error if the mappingFunction does so
     */
    default <T> T youngest(Function<Stream<CacheEntry<K, V>>, T> mappingFunction) {
      // This method was added & implemented in version 3.0.6
      throw new UnsupportedOperationException();
    }
  }

  /**
   * The low-level operations for a cache with a fixed refresh policy.
   *
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  interface FixedRefresh<K, V> {

    /**
     * Returns the age of the entry based on the refresh policy. The entry's age is the cache's
     * estimate of the amount of time since the entry's refresh period was last reset.
     * <p>
     * A refresh policy uses the age to determine if an entry is fresh or stale by comparing it
     * to the freshness lifetime. This is calculated as {@code fresh = freshnessLifetime > age}
     * where {@code freshnessLifetime = expires - currentTime}.
     *
     * @param key the key for the entry being queried
     * @param unit the unit that {@code age} is expressed in
     * @return the age if the entry is present in the cache
     * @throws NullPointerException if the specified key or unit is null
     */
    OptionalLong ageOf(K key, TimeUnit unit);

    /**
     * Returns the age of the entry based on the refresh policy. The entry's age is the cache's
     * estimate of the amount of time since the entry's refresh period was last reset.
     * <p>
     * A refresh policy uses the age to determine if an entry is fresh or stale by comparing it
     * to the freshness lifetime. This is calculated as {@code fresh = freshnessLifetime > age}
     * where {@code freshnessLifetime = expires - currentTime}.
     *
     * @param key the key for the entry being queried
     * @return the age if the entry is present in the cache
     * @throws NullPointerException if the specified key is null
     */
    default Optional<Duration> ageOf(K key) {
      OptionalLong duration = ageOf(key, TimeUnit.NANOSECONDS);
      return duration.isPresent()
          ? Optional.of(Duration.ofNanos(duration.getAsLong()))
          : Optional.empty();
    }

    /**
     * Returns the fixed duration used to determine if an entry should be eligible for reloading due
     * to elapsing this time bound. An entry is considered fresh if its age is less than this
     * duration, and stale otherwise. The refresh policy determines when the entry's age is
     * reset.
     *
     * @param unit the unit that duration is expressed in
     * @return the length of time after which an entry is eligible to be reloaded
     * @throws NullPointerException if the unit is null
     */
    long getRefreshesAfter(TimeUnit unit);

    /**
     * Returns the fixed duration used to determine if an entry should be eligible for reloading due
     * to elapsing this time bound. An entry is considered fresh if its age is less than this
     * duration, and stale otherwise. The refresh policy determines when the entry's age is
     * reset.
     *
     * @return the length of time after which an entry is eligible to be reloaded
     */
    default Duration getRefreshesAfter() {
      return Duration.ofNanos(getRefreshesAfter(TimeUnit.NANOSECONDS));
    }

    /**
     * Specifies that each entry should be eligible for reloading once a fixed duration has elapsed.
     * The refresh policy determines when the entry's age is reset.
     *
     * @param duration the length of time after which an entry is eligible to be reloaded
     * @param unit the unit that {@code duration} is expressed in
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the unit is null
     */
    void setRefreshesAfter(long duration, TimeUnit unit);

    /**
     * Specifies that each entry should be eligible for reloading once a fixed duration has elapsed.
     * The refresh policy determines when the entry's age is reset.
     *
     * @param duration the length of time after which an entry is eligible to be reloaded
     * @throws IllegalArgumentException if {@code duration} is negative
     * @throws NullPointerException if the duration is null
     */
    default void setRefreshesAfter(Duration duration) {
      setRefreshesAfter(toNanosSaturated(duration), TimeUnit.NANOSECONDS);
    }
  }

  /**
   * A key-value pair that may include policy metadata for the cached entry. Unless otherwise
   * specified, this is a value-based class, it can be assumed that the implementation is an
   * immutable snapshot of the cached data at the time of this entry's creation, and it will not
   * reflect changes afterward.
   *
   * @param <K> the type of keys
   * @param <V> the type of values
   */
  @NullMarked
  interface CacheEntry<K, V> extends Map.Entry<K, V> {

    /**
     * Returns the entry's weight. If the cache was not configured with a maximum weight then this
     * value is always {@code 1}.
     *
     * @return the weight if the entry
     */
    int weight();

    /**
     * Returns the {@link Ticker#read()} ticks for when this entry expires. If the cache was not
     * configured with an expiration policy then this value is roughly {@link Long#MAX_VALUE}
     * ticks away from the {@link #snapshotAt()} reading.
     *
     * @return the ticker reading for when the entry expires
     */
    long expiresAt();

    /**
     * Returns the duration between {@link #expiresAt()} and {@link #snapshotAt()}.
     *
     * @return the length of time after which the entry will be automatically removed
     */
    default Duration expiresAfter() {
      return Duration.ofNanos(expiresAt() - snapshotAt());
    }

    /**
     * Returns the {@link Ticker#read()} ticks for when this entry becomes refreshable. If the cache
     * was not configured with a refresh policy then this value is roughly {@link Long#MAX_VALUE}
     * ticks away from the {@link #snapshotAt()} reading.
     *
     * @return the ticker reading for when the entry may be refreshed
     */
    long refreshableAt();

    /**
     * Returns the duration between {@link #refreshableAt()} and {@link #snapshotAt()}.
     *
     * @return the length of time after which an entry is eligible to be reloaded
     */
    default Duration refreshableAfter() {
      return Duration.ofNanos(refreshableAt() - snapshotAt());
    }

    /**
     * Returns the {@link Ticker#read()} ticks for when this snapshot of the entry was taken. This
     * reading may be a constant if no time-based policy is configured.
     *
     * @return the ticker reading for when this snapshot of the entry was taken.
     */
    long snapshotAt();
  }
}
