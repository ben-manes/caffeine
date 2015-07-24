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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.benmanes.caffeine.cache.tracing.Tracer;

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
  @Nonnull
  StatsCounter statsCounter();

  /** Returns the {@link RemovalListener} used by this cache or <tt>null</tt> if not used. */
  @Nullable
  RemovalListener<K, V> removalListener();

  /** Returns the {@link Executor} used by this cache. */
  @Nonnull
  Executor executor();

  /** Returns the {@link Ticker} used by this cache for expiration. */
  @Nonnull
  Ticker expirationTicker();

  /** Returns the {@link Ticker} used by this cache for statistics. */
  @Nonnull
  Ticker statsTicker();

  /** Returns the {@link Tracer} used by this cache. */
  default Tracer tracer() {
    return Tracer.getDefault();
  }

  /** See {@link Cache#estimatedSize()}. */
  @Nonnegative
  long estimatedSize();

  /**
   * See {@link Cache#getIfPresent(Object)}. This method differs by accepting a parameter of whether
   * to record the hit and miss statistics based on the success of this operation.
   */
  @Nullable
  V getIfPresent(@Nonnull Object key, boolean recordStats);

  /** See {@link Cache#getAllPresent}. */
  @Nonnull
  Map<K, V> getAllPresent(@Nonnull Iterable<?> keys);

  /**
   * See {@link Cache#put(Object, Object)}. This method differs by allowing the operation to not
   * notify the writer when an entry was inserted or updated.
   */
  V put(K key, V value, boolean notifyWriter);

  @Override
  default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    return compute(key, remappingFunction, false, false);
  }

  /**
   * See {@link ConcurrentMap#compute}. This method differs by accepting parameters indicating
   * whether to record a miss statistic based on the success of this operation, and further
   * qualified by whether the operation was called by an asynchronous cache.
   */
  V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss, boolean isAsync);

  @Override
  default V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    return computeIfAbsent(key, mappingFunction, false);
  }

  /**
   * See {@link ConcurrentMap#computeIfAbsent}. This method differs by accepting parameters
   * indicating whether the operation was called by an asynchronous cache.
   */
  V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction, boolean isAsync);

  /** See {@link Cache#invalidateAll(Iterable)}. */
  default void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      remove(key);
    }
  }

  /** See {@link Cache#cleanUp}. */
  void cleanUp();

  /** Decorates the remapping function to record statistics if enabled. */
  default Function<? super K, ? extends V> statsAware(
      Function<? super K, ? extends V> mappingFunction, boolean isAsync) {
    if (!isRecordingStats()) {
      return mappingFunction;
    }
    return key -> {
      V value;
      statsCounter().recordMisses(1);
      long startTime = statsTicker().read();
      try {
        value = mappingFunction.apply(key);
      } catch (RuntimeException | Error e) {
        statsCounter().recordLoadFailure(statsTicker().read() - startTime);
        throw e;
      }
      long loadTime = statsTicker().read() - startTime;
      if (!isAsync) {
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
    return statsAware(remappingFunction, true, false);
  }

  /** Decorates the remapping function to record statistics if enabled. */
  default <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction,
      boolean recordMiss, boolean isAsync) {
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
        statsCounter().recordLoadFailure(statsTicker().read() - startTime);
        throw e;
      }
      long loadTime = statsTicker().read() - startTime;
      if (!isAsync) {
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
