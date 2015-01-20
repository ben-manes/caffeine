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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalLoadingCache<C extends LocalCache<K, V>, K, V>
    extends LocalManualCache<C, K, V>, LoadingCache<K, V> {
  static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

  default boolean hasLoadAll(CacheLoader<? super K, V> loader) {
    try {
      return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
    } catch (NoSuchMethodException | SecurityException e) {
      logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
      return false;
    }
  }

  CacheLoader<? super K, V> loader();
  boolean hasBulkLoader();

  @Override
  default V get(K key) {
    return cache().computeIfAbsent(key, loader()::load);
  }

  @Override
  default Map<K, V> getAll(Iterable<? extends K> keys) {
    List<K> keysToLoad = new ArrayList<>();
    Map<K, V> result = new HashMap<>();
    for (K key : keys) {
      V value = cache().getIfPresent(key, false);
      if (value == null) {
        keysToLoad.add(key);
      } else {
        result.put(key, value);
      }
    }
    cache().statsCounter().recordHits(result.size());
    if (keysToLoad.isEmpty()) {
      return result;
    }
    bulkLoad(keysToLoad, result);
    return Collections.unmodifiableMap(result);
  }

  default void bulkLoad(List<K> keysToLoad, Map<K, V> result) {
    cache().statsCounter().recordMisses(keysToLoad.size());

    if (!hasBulkLoader()) {
      for (K key : keysToLoad) {
        V value = cache().compute(key, (k, v) -> loader().load(key), false, false);
        if (value != null) {
          result.put(key, value);
        }
      }
      return;
    }

    boolean success = false;
    long startTime = cache().ticker().read();
    try {
      @SuppressWarnings("unchecked")
      Map<K, V> loaded = (Map<K, V>) loader().loadAll(keysToLoad);
      cache().putAll(loaded);
      for (K key : keysToLoad) {
        V value = loaded.get(key);
        if (value != null) {
          result.put(key, value);
        }
      }
      success = !loaded.isEmpty();
    } finally {
      long loadTime = cache().ticker().read() - startTime;
      if (success) {
        cache().statsCounter().recordLoadSuccess(loadTime);
      } else {
        cache().statsCounter().recordLoadFailure(loadTime);
      }
    }
  }

  @Override
  default void refresh(K key) {
    requireNonNull(key);
    cache().executor().execute(() -> {
      try {
        BiFunction<? super K, ? super V, ? extends V> refreshFunction = (k, oldValue) ->
            (oldValue == null)  ? loader().load(key) : loader().reload(key, oldValue);
        cache().compute(key, refreshFunction, false, false);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown during refresh", t);
      }
    });
  }
}
