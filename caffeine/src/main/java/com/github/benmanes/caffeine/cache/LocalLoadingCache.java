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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class provides a skeletal implementation of the {@link LoadingCache} interface to minimize
 * the effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalLoadingCache<C extends LocalCache<K, V>, K, V>
    extends LocalManualCache<C, K, V>, LoadingCache<K, V> {
  Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

  /** Returns the {@link CacheLoader} used by this cache. */
  CacheLoader<? super K, V> cacheLoader();

  Function<K, V> mappingFunction();

  /** Returns whether the cache loader supports bulk loading. */
  boolean hasBulkLoader();

  /** Returns whether the supplied cache loader has bulk load functionality. */
  default boolean hasLoadAll(CacheLoader<? super K, V> loader) {
    try {
      return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
    } catch (NoSuchMethodException | SecurityException e) {
      logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
      return false;
    }
  }

  @Override
  default V get(K key) {
    return cache().computeIfAbsent(key, mappingFunction());
  }

  @Override
  default Map<K, V> getAll(Iterable<? extends K> keys) {
    return hasBulkLoader() ? loadInBulk(keys) : loadSequentially(keys);
  }

  /** Sequentially loads each missing entry. */
  default Map<K, V> loadSequentially(Iterable<? extends K> keys) {
    int count = 0;
    Map<K, V> result = new HashMap<>();
    Iterator<? extends K> iter = keys.iterator();
    while (iter.hasNext()) {
      K key = iter.next();
      count++;
      try {
        V value = get(key);
        if (value != null) {
          result.put(key, value);
        }
      } catch (Throwable t) {
        int remaining;
        if (keys instanceof Collection<?>) {
          remaining = ((Collection<?>) keys).size() - count;
        } else {
          remaining = 0;
          while (iter.hasNext()) {
            remaining++;
            iter.next();
          }
        }
        cache().statsCounter().recordMisses(remaining);
        throw t;
      }
    }
    return Collections.unmodifiableMap(result);
  }

  /** Batch loads the missing entries. */
  default Map<K, V> loadInBulk(Iterable<? extends K> keys) {
    Map<K, V> found = cache().getAllPresent(keys);
    List<K> keysToLoad = new ArrayList<>();
    for (K key : keys) {
      if (!found.containsKey(key)) {
        keysToLoad.add(key);
      }
    }
    if (keysToLoad.isEmpty()) {
      return found;
    }

    Map<K, V> result = new HashMap<>(found);
    bulkLoad(keysToLoad, result);
    return Collections.unmodifiableMap(result);
  }

  /**
   * Performs a non-blocking bulk load of the missing keys. Any missing entry that materializes
   * during the load are replaced when the loaded entries are inserted into the cache.
   */
  default void bulkLoad(List<K> keysToLoad, Map<K, V> result) {
    boolean success = false;
    long startTime = cache().statsTicker().read();
    try {
      @SuppressWarnings("unchecked")
      Map<K, V> loaded = (Map<K, V>) cacheLoader().loadAll(keysToLoad);
      for (Entry<K, V> entry : loaded.entrySet()) {
        cache().put(entry.getKey(), entry.getValue(), false);
      }
      for (K key : keysToLoad) {
        V value = loaded.get(key);
        if (value != null) {
          result.put(key, value);
        }
      }
      success = !loaded.isEmpty();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new CompletionException(e);
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
  default void refresh(K key) {
    requireNonNull(key);
    cache().executor().execute(() -> {
      BiFunction<? super K, ? super V, ? extends V> refreshFunction = (k, oldValue) -> {
        try {
          return (oldValue == null)
              ? cacheLoader().load(key)
              : cacheLoader().reload(key, oldValue);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return LocalCache.throwUnchecked(e);
        } catch (Exception e) {
          return LocalCache.throwUnchecked(e);
        }
      };
      try {
        cache().compute(key, refreshFunction, false, false);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown during refresh", t);
      }
    });
  }
}
