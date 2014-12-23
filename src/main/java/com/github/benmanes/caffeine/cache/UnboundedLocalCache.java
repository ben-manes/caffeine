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

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.github.benmanes.caffeine.UnsafeAccess;

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
    return cache.get(key);
  }

  @Override
  public long mappingCount() {
    return cache.mappingCount();
  }

  @Override
  public V get(K key, CacheLoader<? super K, V> loader) throws ExecutionException {
    return computeIfAbsent(key, (K k) -> {
      try {
        return loader.load(key);
      } catch (Exception e) {
        UnsafeAccess.UNSAFE.throwException(e);
        return null; // unreachable
      }
    });
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      V value = cache.get(key);
      if (value != null) {
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        result.put(castKey, value);
      }
    }
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
    return cache.getOrDefault(key, defaultValue);
  }

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    cache.forEach(action);
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    cache.replaceAll(function);
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    // optimistic fast path due to computeIfAbsent always locking
    V value = cache.get(key);
    return (value == null)
        ? cache.computeIfAbsent(key, mappingFunction)
        : value;
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    // optimistic fast path due to computeIfAbsent always locking
    if (!cache.containsKey(key)) {
      return null;
    }
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = cache.computeIfPresent(key, (K k, V oldValue) -> {
      V newValue = remappingFunction.apply(k, oldValue);
      notification[0] = (newValue == null)
          ? new RemovalNotification<K, V>(RemovalCause.EXPLICIT, key, oldValue)
          : new RemovalNotification<K, V>(RemovalCause.REPLACED, key, oldValue);
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  @Override
  public V compute(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = cache.compute(key, (K k, V oldValue) -> {
      V newValue = remappingFunction.apply(k, oldValue);
      if (oldValue != null) {
        notification[0] = (newValue == null)
            ? new RemovalNotification<K, V>(RemovalCause.EXPLICIT, key, oldValue)
            : new RemovalNotification<K, V>(RemovalCause.REPLACED, key, oldValue);
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
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = cache.merge(key, value, (V oldValue, V val) -> {
      V newValue = remappingFunction.apply(oldValue, val);
      notification[0] = (newValue == null)
          ? new RemovalNotification<K, V>(RemovalCause.EXPLICIT, key, oldValue)
          : new RemovalNotification<K, V>(RemovalCause.REPLACED, key, oldValue);
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  /* ---------------- Concurrent Map -------------- */

  @Override
  public boolean isEmpty() {
    return cache.isEmpty();
  }

  @Override
  public void clear() {
    for (K key : cache.keySet()) {
      V value = cache.remove(key);
      notifyRemoval(RemovalCause.EXPLICIT, key, value);
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
    return cache.put(key, value);
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return cache.putIfAbsent(key, value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    cache.putAll(m);
  }

  @Override
  public V remove(Object key) {
    V value = cache.remove(key);
    if (value != null) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      notifyRemoval(RemovalCause.EXPLICIT, castKey, value);
    }
    return value;
  }

  @Override
  public boolean remove(Object key, Object value) {
    boolean removed = cache.remove(key, value);
    if (removed) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      @SuppressWarnings("unchecked")
      V castValue = (V) value;
      notifyRemoval(RemovalCause.EXPLICIT, castKey, castValue);
    }
    return removed;
  }

  @Override
  public V replace(K key, V value) {
    V prev = cache.replace(key, value);
    if (prev != null) {
      notifyRemoval(RemovalCause.REPLACED, key, value);
    }
    return prev;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    boolean replaced = cache.replace(key, oldValue, newValue);
    if (replaced) {
      notifyRemoval(RemovalCause.EXPLICIT, key, oldValue);
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
}
