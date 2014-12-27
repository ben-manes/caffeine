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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class UnboundedLocalCache<K, V> extends AbstractLocalCache<K, V> {
  final ConcurrentHashMap<K, V> cache;

  UnboundedLocalCache(Caffeine<? super K, ? super V> builder) {
    super(builder);
    this.cache = new ConcurrentHashMap<K, V>(builder.initialCapacity);
  }

  /* ---------------- Cache -------------- */

  @Override
  public V getIfPresent(Object key) {
    V value = cache.get(key);

    if (value == null) {
      statsCounter().recordMisses(1);
    } else {
      statsCounter().recordHits(1);
    }
    return value;
  }

  @Override
  public long mappingCount() {
    return cache.mappingCount();
  }

  @Override
  public V get(K key, Function<? super K, ? extends V> mappingFunction) {
    return computeIfAbsent(key, mappingFunction);
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int misses = 0;
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      V value = cache.get(key);
      if (value == null) {
        misses++;
      } else {
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        result.put(castKey, value);
      }
    }
    statsCounter().recordMisses(misses);
    statsCounter().recordHits(result.size());
    return Collections.unmodifiableMap(result);
  }

  @Override
  public void invalidateAll() {
    clear();
  }

  @Override
  public void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      remove(key);
    }
  }

  @Override
  public void cleanUp() {}

  @Override
  public Iterator<K> keyIterator() {
    return cache.keySet().iterator();
  }

  @Override
  public Spliterator<K> keySpliterator() {
    return cache.keySet().spliterator();
  }

  @Override
  public Iterator<V> valueIterator() {
    return cache.values().iterator();
  }

  @Override
  public Spliterator<V> valueSpliterator() {
    return cache.values().spliterator();
  }

  @Override
  public Iterator<Entry<K, V>> entryIterator() {
    return cache.entrySet().iterator();
  }

  @Override
  public Spliterator<Entry<K, V>> entrySpliterator() {
    return cache.entrySet().spliterator();
  }


  /* ---------------- JDK8+ Map extensions -------------- */

  @Override
  public V getOrDefault(Object key, V defaultValue) {
    V value = getIfPresent(key);
    return (value == null) ? defaultValue : value;
  }

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    cache.forEach(action);
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    if (!hasRemovalListener()) {
      cache.replaceAll(function);
      return;
    }

    // ensures that the removal notification is processed after the removal has completed
    requireNonNull(function);
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    cache.replaceAll((key, value) -> {
      if (notification[0] != null) {
        notifyRemoval(notification[0]);
        notification[0] = null;
      }
      V newValue = requireNonNull(function.apply(key, value));
      notification[0] = new RemovalNotification<K, V>(key, value, RemovalCause.REPLACED);
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    // optimistic fast path due to computeIfAbsent always locking
    V value = cache.get(key);
    if (value != null) {
      statsCounter().recordHits(1);
      return value;
    }

    if (!isReordingStats()) {
      return cache.computeIfAbsent(key, mappingFunction);
    }
    boolean[] missed = new boolean[1];
    value = cache.computeIfAbsent(key, k -> {
      missed[0] = true;
      long startTime = ticker.read();
      try {
        V result = mappingFunction.apply(key);
        statsCounter().recordLoadSuccess(ticker.read() - startTime);
        return result;
      } catch (RuntimeException | Error e) {
        statsCounter().recordLoadException(ticker.read() - startTime);
        statsCounter().recordMisses(1);
        throw e;
      }
    });
    if (missed[0]) {
      statsCounter().recordMisses(1);
    } else {
      statsCounter().recordHits(1);
    }
    return value;
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    if (!cache.containsKey(key)) {
      return null;
    }
    if (!hasRemovalListener()) {
      return cache.computeIfPresent(key, makeStatsAware(remappingFunction));
    }
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = cache.computeIfPresent(key, (K k, V oldValue) -> {
      V newValue = makeStatsAware(remappingFunction).apply(k, oldValue);
      notification[0] = (newValue == null)
          ? new RemovalNotification<K, V>(key, oldValue, RemovalCause.EXPLICIT)
          : new RemovalNotification<K, V>(key, oldValue, RemovalCause.REPLACED);
          return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    if (!hasRemovalListener()) {
      return cache.compute(key, makeStatsAware(remappingFunction));
    }

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = cache.compute(key, (K k, V oldValue) -> {
      V newValue = makeStatsAware(remappingFunction).apply(k, oldValue);
      if (oldValue != null) {
        notification[0] = (newValue == null)
            ? new RemovalNotification<K, V>(key, oldValue, RemovalCause.EXPLICIT)
            : new RemovalNotification<K, V>(key, oldValue, RemovalCause.REPLACED);
      }
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);
    if (!hasRemovalListener()) {
      return cache.merge(key, value, makeStatsAware(remappingFunction));
    }

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = cache.merge(key, value, (V oldValue, V val) -> {
      V newValue = makeStatsAware(remappingFunction).apply(oldValue, val);
      notification[0] = (newValue == null)
          ? new RemovalNotification<K, V>(key, oldValue, RemovalCause.EXPLICIT)
          : new RemovalNotification<K, V>(key, oldValue, RemovalCause.REPLACED);
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  /** Decorates the remapping function to record statistics if enabled. */
  <A, B, C> BiFunction<? super A, ? super B, ? extends C> makeStatsAware(
      BiFunction<? super A, ? super B, ? extends C> remappingFunction) {
    if (!isReordingStats()) {
      return remappingFunction;
    }
    return (k, oldValue) -> {
      long startTime = ticker.read();
      try {
        C newValue = remappingFunction.apply(k, oldValue);
        statsCounter().recordLoadSuccess(ticker.read() - startTime);
        return newValue;
      } catch (RuntimeException | Error e) {
        statsCounter().recordLoadException(ticker.read() - startTime);
        throw e;
      }
    };
  }

  /* ---------------- Concurrent Map -------------- */

  @Override
  public boolean isEmpty() {
    return cache.isEmpty();
  }

  @Override
  public void clear() {
    if (!hasRemovalListener()) {
      cache.clear();
      return;
    }
    for (K key : cache.keySet()) {
      V value = cache.remove(key);
      notifyRemoval(key, value, RemovalCause.EXPLICIT);
    }
  }

  @Override
  public int size() {
    return cache.size();
  }

  @Override
  public boolean containsKey(Object key) {
    return cache.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return cache.containsValue(value);
  }

  @Override
  public V get(Object key) {
    return cache.get(key);
  }

  @Override
  public V put(K key, V value) {
    V oldValue = cache.put(key, value);
    if (hasRemovalListener() && (oldValue != null)) {
      notifyRemoval(key, oldValue, RemovalCause.REPLACED);
    }
    return oldValue;
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return cache.putIfAbsent(key, value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    if (!hasRemovalListener()) {
      cache.putAll(map);
      return;
    }
    for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
      V oldValue = cache.put(entry.getKey(), entry.getValue());
      if (oldValue != null) {
        notifyRemoval(entry.getKey(), oldValue, RemovalCause.REPLACED);
      }
    }
  }

  @Override
  public V remove(Object key) {
    V value = cache.remove(key);
    if (hasRemovalListener() && (value != null)) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      notifyRemoval(castKey, value, RemovalCause.EXPLICIT);
    }
    return value;
  }

  @Override
  public boolean remove(Object key, Object value) {
    boolean removed = cache.remove(key, value);
    if (hasRemovalListener() && removed) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      @SuppressWarnings("unchecked")
      V castValue = (V) value;
      notifyRemoval(castKey, castValue, RemovalCause.EXPLICIT);
    }
    return removed;
  }

  @Override
  public V replace(K key, V value) {
    V prev = cache.replace(key, value);
    if ((hasRemovalListener()) && prev != null) {
      notifyRemoval(key, value, RemovalCause.REPLACED);
    }
    return prev;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    boolean replaced = cache.replace(key, oldValue, newValue);
    if (hasRemovalListener() && replaced) {
      notifyRemoval(key, oldValue, RemovalCause.REPLACED);
    }
    return replaced;
  }

  @Override
  public boolean equals(Object o) {
    return cache.equals(o);
  }

  @Override
  public int hashCode() {
    return cache.hashCode();
  }

  @Override
  public String toString() {
    return cache.toString();
  }

  /* ---------------- Manual Cache -------------- */

  static class LocalManualCache<K, V> implements Cache<K, V> {
    final UnboundedLocalCache<K, V> localCache;

    LocalManualCache(Caffeine<K, V> builder) {
      this.localCache = new UnboundedLocalCache<>(builder);
    }

    @Override
    public long size() {
      return localCache.mappingCount();
    }

    @Override
    public void cleanUp() {
      localCache.cleanUp();
    }

    @Override
    public @Nullable V getIfPresent(Object key) {
      return localCache.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return localCache.get(key, mappingFunction);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      return localCache.getAllPresent(keys);
    }

    @Override
    public void put(K key, V value) {
      localCache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      localCache.putAll(map);
    }

    @Override
    public void invalidate(Object key) {
      requireNonNull(key);
      localCache.remove(key);
    }

    @Override
    public void invalidateAll() {
      localCache.invalidateAll();
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      localCache.invalidateAll(keys);
    }

    @Override
    public CacheStats stats() {
      return localCache.statsCounter().snapshot();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return localCache;
    }
  }

  /* ---------------- Loading Cache -------------- */

  static final class LocalLoadingCache<K, V> extends LocalManualCache<K, V>
      implements LoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    final CacheLoader<? super K, V> loader;
    final Executor executor;

    LocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder);
      this.loader = loader;
      this.executor = builder.executor;
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
      requireNonNull(key);
      executor.execute(() -> {
        try {
          localCache.compute(key, loader::refresh);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during refresh", t);
        }
      });
    }

    /* ---------------- Experiments -------------- */

//    private static boolean canBulkLoad(CacheLoader<?, ?> loader) {
//      try {
//        return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
//      } catch (NoSuchMethodException | SecurityException e) {
//        return false;
//      }
//    }
//
//    static final class BulkLoadingCache<K, V> {
//      LocalCache<K, Promise<V>> localCache;
//      CacheLoader<K, V> loader;
//
//      public Map<K, V> getAll(Iterable<? extends K> keys) {
//        Map<K, Promise<V>> missing = new HashMap<>();
//        Map<K, Promise<V>> promises = new HashMap<>();
//
//        try {
//          for (K key : keys) {
//            requireNonNull(keys);
//            Promise<V> promise = new Promise<V>();
//            UnsafeAccess.UNSAFE.monitorEnter(promise);
//            Promise<V> value = localCache.computeIfAbsent(key, k -> promise);
//            if (value == promise) {
//              missing.put(key, promise);
//              promises.put(key, promise);
//            } else {
//              promises.put(key, value);
//            }
//          }
//
//          Map<K, V> loaded = missing.isEmpty()
//              ? Collections.emptyMap()
//              : loader.loadAll(Collections.unmodifiableCollection(missing.keySet()));
//          for (Iterator<Entry<K, Promise<V>>> iter = missing.entrySet().iterator(); iter.hasNext();) {
//            Entry<K, Promise<V>> entry = iter.next();
//            K key = entry.getKey();
//            V value = loaded.get(key);
//            Promise<V> promise = entry.getValue();
//            if (value == null) {
//              localCache.remove(key, promise);
//            } else {
//              // TODO(ben): lazily set to piggyback visibility on monitorExit
//              promise.value = value;
//            }
//            UnsafeAccess.UNSAFE.monitorExit(promise);
//            iter.remove();
//          }
//        } catch (RuntimeException | Error e) {
//          for (Promise<V> promise : missing.values()) {
//            UnsafeAccess.UNSAFE.monitorExit(promise);
//          }
//        }
//
//        Map<K, V> result = new HashMap<>(promises.size());
//        for (Entry<K, Promise<V>> entry : promises.entrySet()) {
//          Promise<V> promise = entry.getValue();
//          if (promise.value != null) {
//            result.put(entry.getKey(), promise.value);
//            continue;
//          }
//          synchronized(promise) {
//            if (promise.value != null) {
//              result.put(entry.getKey(), promise.value);
//            }
//          }
//        }
//        return Collections.unmodifiableMap(result);
//      }
//
//      static final class Promise<V> {
//        volatile V value;
//      }
//    }
  }
}
