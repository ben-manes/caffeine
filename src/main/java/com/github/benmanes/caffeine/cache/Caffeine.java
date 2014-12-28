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

  private static final int DEFAULT_CONCURRENCY_LEVEL = 16;
  private static final int DEFAULT_INITIAL_CAPACITY = 16;
  static final int UNSET_INT = -1;

  long maximumSize = UNSET_INT;
  long maximumWeight = UNSET_INT;
  private int initialCapacity = UNSET_INT;
  Weigher<? super K, ? super V> weigher = SingletonWeigher.INSTANCE;

  Supplier<StatsCounter> statsCounterSupplier = DISABLED_STATS_COUNTER_SUPPLIER;
  RemovalListener<? super K, ? super V> removalListener;
  Executor executor = ForkJoinPool.commonPool();

  private Caffeine() {}

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

  int initialCapacity() {
    return (initialCapacity == UNSET_INT) ? DEFAULT_INITIAL_CAPACITY : initialCapacity;
  }

  Ticker ticker() {
    if (isRecordingStats()) {

    }
    return Ticker.systemTicker();
  }

  boolean isRecordingStats() {
    return (statsCounterSupplier == ENABLED_STATS_COUNTER_SUPPLIER);
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




  public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
      RemovalListener<? super K1, ? super V1> removalListener) {
    requireState(this.removalListener == null);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.removalListener = requireNonNull(removalListener);
    return self;
  }

  public Caffeine<K, V> weakKeys() {
    throw new UnsupportedOperationException();
  }

  public Caffeine<K, V> weakValues() {
    throw new UnsupportedOperationException();
  }

  public Caffeine<K, V> softValues() {
    throw new UnsupportedOperationException();
  }

  public Caffeine<K, V> expireAfterWrite() {
    throw new UnsupportedOperationException();
  }

  public Caffeine<K, V> expireAfterAccess() {
    throw new UnsupportedOperationException();
  }

  public Caffeine<K, V> recordStats() {
    statsCounterSupplier = ENABLED_STATS_COUNTER_SUPPLIER;
    return this;
  }

  public Caffeine<K, V> maximumSize(long size) {
    this.maximumSize = size;
    return this;
  }

  public <K1 extends K, V1 extends V> Caffeine<K1, V1> weigher(
      Weigher<? super K1, ? super V1> weigher) {
    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.weigher = weigher;
    return self;
  }

  public Caffeine<K, V> maximumWeight(long weight) {
    this.maximumWeight = weight;
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
}
