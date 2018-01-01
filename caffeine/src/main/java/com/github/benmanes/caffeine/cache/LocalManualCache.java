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
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * This class provides a skeletal implementation of the {@link Cache} interface to minimize the
 * effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalManualCache<C extends LocalCache<K, V>, K, V> extends Cache<K, V> {

  /** Returns the backing {@link LocalCache} data store. */
  C cache();

  @Override
  default long estimatedSize() {
    return cache().estimatedSize();
  }

  @Override
  default void cleanUp() {
    cache().cleanUp();
  }

  @Override
  default @Nullable V getIfPresent(Object key) {
    return cache().getIfPresent(key, /* recordStats */ true);
  }

  @Override
  default @Nullable V get(K key, Function<? super K, ? extends V> mappingFunction) {
    return cache().computeIfAbsent(key, mappingFunction);
  }

  @Override
  default Map<K, V> getAllPresent(Iterable<?> keys) {
    return cache().getAllPresent(keys);
  }

  @Override
  default void put(K key, V value) {
    cache().put(key, value);
  }

  @Override
  default void putAll(Map<? extends K, ? extends V> map) {
    cache().putAll(map);
  }

  @Override
  default void invalidate(Object key) {
    cache().remove(key);
  }

  @Override
  default void invalidateAll(Iterable<?> keys) {
    cache().invalidateAll(keys);
  }

  @Override
  default void invalidateAll() {
    cache().clear();
  }

  @Override
  default CacheStats stats() {
    return cache().statsCounter().snapshot();
  }

  @Override
  default ConcurrentMap<K, V> asMap() {
    return cache();
  }
}
