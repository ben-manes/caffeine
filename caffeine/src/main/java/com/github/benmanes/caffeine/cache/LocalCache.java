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
package com.github.benmanes.caffeine.cache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * An in-memory cache providing thread safety and atomicity guarantees. This interface provides an
 * extension to {@link ConcurrentMap} for use with skeletal implementations.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalCache<K, V> extends ConcurrentMap<K, V> {

  /** Returns whether this cache has statistics enabled. */
  boolean isRecordingStats();

  /** Returns the {@link StatsCounter} used by this cache. */
  StatsCounter statsCounter();

  /** Asynchronously sends a removal notification to the listener. */
  void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause);

  /** Returns the {@link Executor} used by this cache. */
  Executor executor();

  /** Returns the map of in-flight refresh operations. */
  ConcurrentMap<Object, CompletableFuture<?>> refreshes();

  /** Returns whether the cache captures the write time of the entry. */
  boolean hasWriteTime();

  /** Returns the {@link Expiry} used by this cache. */
  @Nullable Expiry<K, V> expiry();

  /** Returns the {@link Ticker} used by this cache for expiration. */
  Ticker expirationTicker();

  /** Returns the {@link Ticker} used by this cache for statistics. */
  Ticker statsTicker();

  /** See {@link Cache#estimatedSize()}. */
  long estimatedSize();

  /** Returns the reference key. */
  Object referenceKey(K key);

  /**
   * See {@link Cache#getIfPresent(K)}. This method differs by accepting a parameter of whether
   * to record the hit and miss statistics based on the success of this operation.
   */
  @Nullable
  V getIfPresent(K key, boolean recordStats);

  /**
   * See {@link Cache#getIfPresent(K)}. This method differs by not recording the access with
   * the statistics nor the eviction policy, and populates the write time if known.
   */
  @Nullable
  V getIfPresentQuietly(K key, long[/* 1 */] writeTime);

  /** See {@link Cache#getAllPresent}. */
  Map<K, V> getAllPresent(Iterable<? extends K> keys);

  @Override
  default @Nullable V compute(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    return compute(key, remappingFunction, expiry(),
        /* recordMiss */ false, /* recordLoad */ true, /* recordLoadFailure */ true);
  }

  /**
   * See {@link ConcurrentMap#compute}. This method differs by accepting parameters indicating
   * whether to record miss and load statistics based on the success of this operation.
   */
  @Nullable V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      @Nullable Expiry<K, V> expiry, boolean recordMiss,
      boolean recordLoad, boolean recordLoadFailure);

  @Override
  default @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    return computeIfAbsent(key, mappingFunction, /* recordStats */ true, /* recordLoad */ true);
  }

  /**
   * See {@link ConcurrentMap#computeIfAbsent}. This method differs by accepting parameters
   * indicating how to record statistics.
   */
  @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean recordStats, boolean recordLoad);

  /** See {@link Cache#invalidateAll(Iterable)}. */
  default void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      remove(key);
    }
  }

  /** See {@link Cache#cleanUp}. */
  void cleanUp();

  /** Decorates the remapping function to record statistics if enabled. */
  default <T, R> Function<? super T, ? extends R> statsAware(
      Function<? super T, ? extends R> mappingFunction, boolean recordLoad) {
    if (!isRecordingStats()) {
      return mappingFunction;
    }
    return key -> {
      R value;
      statsCounter().recordMisses(1);
      long startTime = statsTicker().read();
      try {
        value = mappingFunction.apply(key);
      } catch (RuntimeException | Error e) {
        statsCounter().recordLoadFailure(statsTicker().read() - startTime);
        throw e;
      }
      long loadTime = statsTicker().read() - startTime;
      if (recordLoad) {
        if (value == null) {
          statsCounter().recordLoadFailure(loadTime);
        } else {
          statsCounter().recordLoadSuccess(loadTime);
        }
      }
      return value;
    };
  }

  /** Decorates the remapping function to record statistics if enabled. */
  default <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction) {
    return statsAware(remappingFunction, /* recordMiss */ true,
        /* recordLoad */ true, /* recordLoadFailure */ true);
  }

  /** Decorates the remapping function to record statistics if enabled. */
  default <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction,
      boolean recordMiss, boolean recordLoad, boolean recordLoadFailure) {
    if (!isRecordingStats()) {
      return remappingFunction;
    }
    return (t, u) -> {
      R result;
      if ((u == null) && recordMiss) {
        statsCounter().recordMisses(1);
      }
      long startTime = statsTicker().read();
      try {
        result = remappingFunction.apply(t, u);
      } catch (RuntimeException | Error e) {
        if (recordLoadFailure) {
          statsCounter().recordLoadFailure(statsTicker().read() - startTime);
        }
        throw e;
      }
      long loadTime = statsTicker().read() - startTime;
      if (recordLoad) {
        if (result == null) {
          statsCounter().recordLoadFailure(loadTime);
        } else {
          statsCounter().recordLoadSuccess(loadTime);
        }
      }
      return result;
    };
  }
}
