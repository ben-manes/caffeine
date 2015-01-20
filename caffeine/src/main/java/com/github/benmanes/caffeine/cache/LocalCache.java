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

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * An in-memory cache providing thread safety and atomicity guarantees. This interface provides
 * extensions to the {@link ConcurrentMap} for use with shared skeletal implementations such as
 * {@link LocalManualCache}, {@link LocalLoadingCache}, and {@link AsyncLocalLoadingCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalCache<K, V> extends ConcurrentMap<K, V> {

  RemovalListener<K, V> removalListener();
  StatsCounter statsCounter();
  boolean isRecordingStats();
  Executor executor();
  Ticker ticker();

  /**
   * Returns the approximate number of entries in this cache. The value returned is an estimate; the
   * actual count may differ if there are concurrent insertions or removals, or if some entries are
   * pending removal due to expiration or soft/weak reference collection.
   *
   * @return the estimated number of mappings
   */
  long estimatedSize();

  @Nullable V getIfPresent(Object key, boolean recordStats);

  Map<K, V> getAllPresent(Iterable<?> keys);

  @Override
  default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    return compute(key, remappingFunction, false, false);
  }

  V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss, boolean isAsync);

  @Override
  default V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    return computeIfAbsent(key, mappingFunction, false);
  }

  V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean isAsync);

  void cleanUp();

  default void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      remove(key);
    }
  }
}
