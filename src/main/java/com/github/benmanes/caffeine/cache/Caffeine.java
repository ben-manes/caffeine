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

import static java.util.Objects.requireNonNull;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.DisabledStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Caffeine<K, V> {
  private static final Supplier<StatsCounter> DISABLED_STATS_COUNTER_SUPPLIER =
      () -> DisabledStatsCounter.INSTANCE;
  private static final Supplier<StatsCounter> ENABLED_STATS_COUNTER_SUPPLIER =
      () -> new ConcurrentStatsCounter();
  private static final Ticker DISABLED_TICKER = () -> 0;

  private static final int DEFAULT_INITIAL_CAPACITY = 16;
  static final int UNSET_INT = -1;

  private long maximumSize = UNSET_INT;
  private long maximumWeight = UNSET_INT;
  private long expireAfterWriteNanos = UNSET_INT;
  private long expireAfterAccessNanos = UNSET_INT;
  private int initialCapacity = UNSET_INT;

  Supplier<StatsCounter> statsCounterSupplier = DISABLED_STATS_COUNTER_SUPPLIER;
  RemovalListener<? super K, ? super V> removalListener;

  private Weigher<? super K, ? super V> weigher;
  private Executor executor;

  private Caffeine() {}

  /** Ensures that the argument expression is true. */
  static void requireArgument(boolean expression, String template, Object... args) {
    if (!expression) {
      throw new IllegalArgumentException(String.format(template, args));
    }
  }

  /** Ensures that the argument expression is true. */
  static void requireArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /** Ensures that the state expression is true. */
  static void requireState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /** Ensures that the state expression is true. */
  static void requireState(boolean expression, String template, Object... args) {
    if (!expression) {
      throw new IllegalStateException(String.format(template, args));
    }
  }

  /* ---------------- Internal accessors -------------- */

  Ticker ticker() {
    return isRecordingStats() || isExpirable()
        ? Ticker.systemTicker()
        : DISABLED_TICKER;
  }

  boolean isRecordingStats() {
    return (statsCounterSupplier == ENABLED_STATS_COUNTER_SUPPLIER);
  }

  boolean isExpirable() {
    return false;
  }

  @SuppressWarnings("unchecked")
  @Nullable <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener() {
    return (RemovalListener<K1, V1>) removalListener;
  }

  /* ---------------- Public API -------------- */

  /**
   * Constructs a new {@code Caffeine} instance with default settings, including strong keys, strong
   * values, and no automatic eviction of any kind.
   */
  public static Caffeine<Object, Object> newBuilder() {
    return new Caffeine<Object, Object>();
  }

  /**
   * Sets the minimum total size for the internal hash tables. For example, if the initial capacity
   * is {@code 60}, and the concurrency level is {@code 8}, then eight segments are created, each
   * having a hash table of size eight. Providing a large enough estimate at construction time
   * avoids the need for expensive resizing operations later, but setting this value unnecessarily
   * high wastes memory.
   *
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   * @throws IllegalStateException if an initial capacity was already set
   */
  public Caffeine<K, V> initialCapacity(int initialCapacity) {
    requireState(this.initialCapacity == UNSET_INT, "initial capacity was already set to %s",
        this.initialCapacity);
    requireArgument(initialCapacity >= 0);
    this.initialCapacity = initialCapacity;
    return this;
  }

  int getInitialCapacity() {
    return (initialCapacity == UNSET_INT) ? DEFAULT_INITIAL_CAPACITY : initialCapacity;
  }

  /**
   * Specifies the executor to use when running asynchronous tasks. The executor is delegated to
   * when sending removal notifications and asynchronous computations requested through the
   * {@link AsyncLoadingCache} and {@link LoadingCache#refresh}. By default,
   * {@link ForkJoinPool#commonPool()} is used.
   * <p>
   * The primary intent of this method is to facilitate testing of caches which have been
   * configured with {@link #removalListener} or utilize asynchronous computations. A test may
   * instead prefer to configure the cache that executes directly on the same thread.
   * <p>
   * Beware that configuring a cache with an executor that throws {@link RejectedExecutionException}
   * may experience non-deterministic behavior.
   *
   * @param executor the executor to use for asynchronous execution
   * @throws NullPointerException if the specified executor is null
   */
  public Caffeine<K, V> executor(Executor executor) {
    this.executor = requireNonNull(executor);
    return this;
  }

  Executor getExecutor() {
    return (executor == null) ? ForkJoinPool.commonPool() : executor;
  }

  /**
   * Specifies the maximum number of entries the cache may contain. Note that the cache <b>may evict
   * an entry before this limit is exceeded</b>. As the cache size grows close to the maximum, the
   * cache evicts entries that are less likely to be used again. For example, the cache may evict an
   * entry because it hasn't been used recently or very often.
   * <p>
   * When {@code size} is zero, elements will be evicted immediately after being loaded into the
   * cache. This can be useful in testing, or to disable caching temporarily without a code change.
   * <p>
   * This feature cannot be used in conjunction with {@link #maximumWeight}.
   *
   * @param size the maximum size of the cache
   * @throws IllegalArgumentException if {@code size} is negative
   * @throws IllegalStateException if a maximum size or weight was already set
   */
  public Caffeine<K, V> maximumSize(long maximumSize) {
    requireState(this.maximumSize == UNSET_INT,
        "maximum size was already set to %s", this.maximumSize);
    requireState(this.maximumWeight == UNSET_INT,
        "maximum weight was already set to %s", this.maximumWeight);
    requireState(this.weigher == null, "maximum size can not be combined with weigher");
    requireArgument(maximumSize >= 0, "maximum size must not be negative");
    this.maximumSize = maximumSize;
    return this;
  }

  /**
   * Specifies the maximum weight of entries the cache may contain. Weight is determined using the
   * {@link Weigher} specified with {@link #weigher}, and use of this method requires a
   * corresponding call to {@link #weigher} prior to calling {@link #build}.
   * <p>
   * Note that the cache <b>may evict an entry before this limit is exceeded</b>. As the cache size
   * grows close to the maximum, the cache evicts entries that are less likely to be used again. For
   * example, the cache may evict an entry because it hasn't been used recently or very often.
   * <p>
   * When {@code weight} is zero, elements will be evicted immediately after being loaded into
   * cache. This can be useful in testing, or to disable caching temporarily without a code change.
   * <p>
   * Note that weight is only used to determine whether the cache is over capacity; it has no effect
   * on selecting which entry should be evicted next.
   * <p>
   * This feature cannot be used in conjunction with {@link #maximumSize}.
   *
   * @param weight the maximum total weight of entries the cache may contain
   * @throws IllegalArgumentException if {@code weight} is negative
   * @throws IllegalStateException if a maximum weight or size was already set
   */
  public Caffeine<K, V> maximumWeight(long weight) {
    requireState(this.maximumWeight == UNSET_INT,
        "maximum weight was already set to %s", this.maximumWeight);
    requireState(this.maximumSize == UNSET_INT,
        "maximum size was already set to %s", this.maximumSize);
    this.maximumWeight = weight;
    requireArgument(weight >= 0, "maximum weight must not be negative");
    return this;
  }

  /**
   * Specifies the weigher to use in determining the weight of entries. Entry weight is taken into
   * consideration by {@link #maximumWeight(long)} when determining which entries to evict, and use
   * of this method requires a corresponding call to {@link #maximumWeight(long)} prior to calling
   * {@link #build}. Weights are measured and recorded when entries are inserted into the cache, and
   * are thus effectively static during the lifetime of a cache entry.
   * <p>
   * When the weight of an entry is zero it will not be considered for size-based eviction (though
   * it still may be evicted by other means).
   * <p>
   * <b>Important note:</b> Instead of returning <em>this</em> as a {@code Caffeine} instance, this
   * method returns {@code Caffeine<K1, V1>}. From this point on, either the original reference or
   * the returned reference may be used to complete configuration and build the cache, but only the
   * "generic" one is type-safe. That is, it will properly prevent you from building caches whose
   * key or value types are incompatible with the types accepted by the weigher already provided;
   * the {@code Caffeine} type cannot do this. For best results, simply use the standard
   * method-chaining idiom, as illustrated in the documentation at top, configuring a
   * {@code Caffeine} and building your {@link Cache} all in a single statement.
   * <p>
   * <b>Warning:</b> if you ignore the above advice, and use this {@code Caffeine} to build a cache
   * whose key or value type is incompatible with the weigher, you will likely experience a
   * {@link ClassCastException} at some <i>undefined</i> point in the future.
   *
   * @param weigher the weigher to use in calculating the weight of cache entries
   * @throws IllegalArgumentException if {@code size} is negative
   * @throws IllegalStateException if a maximum size was already set
   */
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> weigher(
      Weigher<? super K1, ? super V1> weigher) {
    requireState(this.weigher == null);
    requireState(this.maximumSize == UNSET_INT,
        "weigher can not be combined with maximum size", this.maximumSize);
    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.weigher = weigher;
    return self;
  }

  long getMaximumWeight() {
    if (expireAfterWriteNanos == 0 || expireAfterAccessNanos == 0) {
      return 0;
    }
    return (weigher == null) ? maximumSize : maximumWeight;
  }

  @SuppressWarnings("unchecked")
  <K1 extends K, V1 extends V> Weigher<K1, V1> getWeigher() {
    return (Weigher<K1, V1>) ((weigher == null) ? Weigher.singleton() : weigher);
  }

  /**
   * Specifies that each key (not value) stored in the cache should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   * <p>
   * <b>Warning:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of keys.
   * <p>
   * Entries with keys that have been garbage collected may be counted in {@link Cache#size}, but
   * will never be visible to read or write operations; such entries are cleaned up as part of the
   * routine maintenance described in the class javadoc.
   *
   * @throws IllegalStateException if the key strength was already set
   */
  public Caffeine<K, V> weakKeys() {
    throw new UnsupportedOperationException();
  }

  /**
   * Specifies that each value (not key) stored in the cache should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   * <p>
   * Weak values will be garbage collected once they are weakly reachable. This makes them a poor
   * candidate for caching; consider {@link #softValues} instead.
   * <p>
   * <b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of values.
   * <p>
   * Entries with values that have been garbage collected may be counted in {@link Cache#size}, but
   * will never be visible to read or write operations; such entries are cleaned up as part of the
   * routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction with {@link #buildAsync}.
   *
   * @throws IllegalStateException if the value strength was already set
   */
  public Caffeine<K, V> weakValues() {
    throw new UnsupportedOperationException();
  }

  /**
   * Specifies that each value (not key) stored in the cache should be wrapped in a
   * {@link SoftReference} (by default, strong references are used). Softly-referenced objects will
   * be garbage-collected in a <i>globally</i> least-recently-used manner, in response to memory
   * demand.
   * <p>
   * <b>Warning:</b> in most circumstances it is better to set a per-cache
   * {@linkplain #maximumSize(long) maximum size} instead of using soft references. You should only
   * use this method if you are well familiar with the practical consequences of soft references.
   * <p>
   * <b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of values.
   * <p>
   * Entries with values that have been garbage collected may be counted in {@link Cache#size}, but
   * will never be visible to read or write operations; such entries are cleaned up as part of the
   * routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction with {@link #buildAsync}.
   *
   * @throws IllegalStateException if the value strength was already set
   */
  public Caffeine<K, V> softValues() {
    throw new UnsupportedOperationException();
  }




  public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
      RemovalListener<? super K1, ? super V1> removalListener) {
    requireState(this.removalListener == null);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.removalListener = requireNonNull(removalListener);
    return self;
  }

  public Caffeine<K, V> recordStats() {
    statsCounterSupplier = ENABLED_STATS_COUNTER_SUPPLIER;
    return this;
  }

  public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;

    return (maximumSize == UNSET_INT)
        ? new UnboundedLocalCache.LocalManualCache<K1, V1>(self)
        : new BoundedLocalCache.LocalManualCache<K1, V1>(self);
  }

  public <K1 extends K, V1 extends V> LoadingCache<K1, V1> build(
      CacheLoader<? super K1, V1> loader) {
    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return (maximumSize == UNSET_INT)
        ? new UnboundedLocalCache.LocalLoadingCache<K1, V1>(self, loader)
        : new BoundedLocalCache.LocalLoadingCache<K1, V1>(self, loader);
  }

  public <K1 extends K, V1 extends V> AsyncLoadingCache<K1, V1> buildAsync(
      CacheLoader<? super K1, V1> loader) {
    throw new UnsupportedOperationException();
  }
}
