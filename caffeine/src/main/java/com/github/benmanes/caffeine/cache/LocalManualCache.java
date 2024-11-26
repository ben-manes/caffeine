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

import static com.github.benmanes.caffeine.cache.Caffeine.calculateHashMapCapacity;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.errorprone.annotations.Var;

/**
 * This class provides a skeletal implementation of the {@link Cache} interface to minimize the
 * effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalManualCache<K, V> extends Cache<K, V> {

  /** Returns the backing {@link LocalCache} data store. */
  LocalCache<K, V> cache();

  @Override
  default long estimatedSize() {
    return cache().estimatedSize();
  }

  @Override
  default void cleanUp() {
    cache().cleanUp();
  }

  @Override
  default @Nullable V getIfPresent(K key) {
    return cache().getIfPresent(key, /* recordStats= */ true);
  }

  @Override
  @SuppressWarnings("NullAway")
  default @Nullable V get(K key, Function<? super K, ? extends V> mappingFunction) {
    return cache().computeIfAbsent(key, mappingFunction);
  }

  @Override
  default Map<K, V> getAllPresent(Iterable<? extends K> keys) {
    return cache().getAllPresent(keys);
  }

  @Override
  default Map<K, V> getAll(Iterable<? extends K> keys,
      Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
    requireNonNull(mappingFunction);

    var found = cache().getAllPresent(keys);
    int initialCapacity = calculateHashMapCapacity(keys);
    var result = new LinkedHashMap<K, V>(initialCapacity);
    var keysToLoad = new LinkedHashSet<K>(initialCapacity);
    for (K key : keys) {
      V value = found.get(key);
      if (value == null) {
        keysToLoad.add(key);
      }
      result.put(key, value);
    }
    if (keysToLoad.isEmpty()) {
      return found;
    }

    bulkLoad(keysToLoad, result, mappingFunction);
    return Collections.unmodifiableMap(result);
  }

  /**
   * Performs a non-blocking bulk load of the missing keys. Any missing entry that materializes
   * during the load are replaced when the loaded entries are inserted into the cache.
   */
  default void bulkLoad(Set<K> keysToLoad, Map<K, V> result,
      Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
    long startTime = cache().statsTicker().read();
    @Var boolean success = false;
    try {
      var loaded = mappingFunction.apply(Collections.unmodifiableSet(keysToLoad));
      loaded.forEach(cache()::put);
      for (K key : keysToLoad) {
        V value = loaded.get(key);
        if (value == null) {
          result.remove(key);
        } else {
          result.put(key, value);
        }
      }
      success = !loaded.isEmpty();
    } finally {
      long loadTime = cache().statsTicker().read() - startTime;
      if (success) {
        cache().statsCounter().recordLoadSuccess(loadTime);
      } else {
        cache().statsCounter().recordLoadFailure(loadTime);
      }
    }
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
  default void invalidate(K key) {
    cache().remove(key);
  }

  @Override
  default void invalidateAll(Iterable<? extends K> keys) {
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
