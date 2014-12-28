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
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.DisabledStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Caffeine<K, V> {
  static final Supplier<StatsCounter> DISABLED_STATS_COUNTER_SUPPLIER =
      () -> DisabledStatsCounter.INSTANCE;
  static final Supplier<StatsCounter> ENABLED_STATS_COUNTER_SUPPLIER =
      () -> new ConcurrentStatsCounter();
  static final int DEFAULT_CONCURRENCY_LEVEL = 16;
  static final int DEFAULT_INITIAL_CAPACITY = 16;
  static final int UNSET_INT = -1;

  long maximumSize = UNSET_INT;
  long maximumWeight = UNSET_INT;
  Weigher<? super K, ? super V> weigher;

  RemovalListener<? super K, ? super V> removalListener;
  Supplier<StatsCounter> statsCounterSupplier;
  int initialCapacity;
  Executor executor;

  public Caffeine() {
    weigher = SingletonWeigher.INSTANCE;
    executor = ForkJoinPool.commonPool();
    statsCounterSupplier = DISABLED_STATS_COUNTER_SUPPLIER;
  }

  /** Ensures that the argument expression is true. */
  static void checkArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /** Ensures that the state expression is true. */
  static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  @SuppressWarnings("unchecked")
  @Nullable <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener() {
    return (RemovalListener<K1, V1>) removalListener;
  }

  public static Caffeine<Object, Object> newBuilder() {
    return new Caffeine<Object, Object>();
  }

  public void initialCapacity(int initialCapacity) {
    // TODO(ben): Validate
    this.initialCapacity = initialCapacity;
  }

  public Caffeine<K, V> executor(Executor executor) {
    this.executor = requireNonNull(executor);
    return this;
  }

  public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
      RemovalListener<? super K1, ? super V1> removalListener) {
    checkState(this.removalListener == null);

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

  boolean isRecordingStats() {
    return statsCounterSupplier == ENABLED_STATS_COUNTER_SUPPLIER;
  }

  Ticker ticker() {
    return Ticker.disabledTicker();
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

  enum NullRemovalListener implements RemovalListener<Object, Object> {
    INSTANCE;

    @Override
    public void onRemoval(RemovalNotification<Object, Object> notification) {}
  }

  enum SingletonWeigher implements Weigher<Object, Object> {
    INSTANCE;

    @Override
    public int weigh(Object key, Object value) {
      return 1;
    }
  }

}
