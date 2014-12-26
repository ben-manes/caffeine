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

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.github.benmanes.caffeine.UnsafeAccess;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class LocalLoadingCache<K, V> extends LocalManualCache<K, V> implements LoadingCache<K, V> {
  private final CacheLoader<? super K, V> loader;

  LocalLoadingCache(LocalCache<K, V> localCache, CacheLoader<? super K, V> loader) {
    super(localCache);
    this.loader = loader;
  }

  @Override
  public V get(K key) {
    return localCache.computeIfAbsent(key, loader::load);
  }

  @Override
  public Map<K, V> getAll(Iterable<? extends K> keys) {
    Map<K, V> result = new HashMap<K, V>();
    for (K key : keys) {
      requireNonNull(key);
      V value = localCache.computeIfAbsent(key, loader::load);
      if (value != null) {
        result.put(key, value);
      }
    }
    return Collections.unmodifiableMap(result);
  }

  @Override
  public void refresh(K key) {
    localCache.computeIfPresent(key, (k, v) -> loader.load(key));
  }

  /* ---------------- Experiments -------------- */

  private static boolean canBulkLoad(CacheLoader<?, ?> loader) {
    try {
      return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
    } catch (NoSuchMethodException | SecurityException e) {
      return false;
    }
  }

  static final class BulkLoadingCache<K, V> {
    LocalCache<K, Promise<V>> localCache;
    CacheLoader<K, V> loader;

    public Map<K, V> getAll(Iterable<? extends K> keys) {
      Map<K, Promise<V>> missing = new HashMap<>();
      Map<K, Promise<V>> promises = new HashMap<>();

      try {
        for (K key : keys) {
          requireNonNull(keys);
          Promise<V> promise = new Promise<V>();
          UnsafeAccess.UNSAFE.monitorEnter(promise);
          Promise<V> value = localCache.computeIfAbsent(key, k -> promise);
          if (value == promise) {
            missing.put(key, promise);
            promises.put(key, promise);
          } else {
            promises.put(key, value);
          }
        }

        Map<K, V> loaded = missing.isEmpty()
            ? Collections.emptyMap()
            : loader.loadAll(Collections.unmodifiableCollection(missing.keySet()));
        for (Iterator<Entry<K, Promise<V>>> iter = missing.entrySet().iterator(); iter.hasNext();) {
          Entry<K, Promise<V>> entry = iter.next();
          K key = entry.getKey();
          V value = loaded.get(key);
          Promise<V> promise = entry.getValue();
          if (value == null) {
            localCache.remove(key, promise);
          } else {
            // TODO(ben): lazily set to piggyback visibility on monitorExit
            promise.value = value;
          }
          UnsafeAccess.UNSAFE.monitorExit(promise);
          iter.remove();
        }
      } catch (RuntimeException | Error e) {
        for (Promise<V> promise : missing.values()) {
          UnsafeAccess.UNSAFE.monitorExit(promise);
        }
      }

      Map<K, V> result = new HashMap<>(promises.size());
      for (Entry<K, Promise<V>> entry : promises.entrySet()) {
        Promise<V> promise = entry.getValue();
        if (promise.value != null) {
          result.put(entry.getKey(), promise.value);
          continue;
        }
        synchronized(promise) {
          if (promise.value != null) {
            result.put(entry.getKey(), promise.value);
          }
        }
      }
      return Collections.unmodifiableMap(result);
    }

    static final class Promise<V> {
      volatile V value;
    }
  }
}
