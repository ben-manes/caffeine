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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.UnsafeAccess;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
// TODO(ben): Make sure to override default interface methods to delegate to optimized versions
final class UnboundedLocalCache<K, V> implements LocalCache<K, V> {
  final RemovalListener<K, V> removalListener;
  final ConcurrentHashMap<K, V> cache;

  UnboundedLocalCache(Caffeine<? super K, ? super V> builder) {
    this.cache = new ConcurrentHashMap<K, V>(builder.initialCapacity);
    this.removalListener = builder.getRemovalListener();
  }

  void notifyRemoval(RemovalCause cause, K key, @Nullable V value) {
    // TODO(ben): Provide pool from builder so it is user configurable
    ForkJoinPool.commonPool().execute(
        () -> removalListener.onRemoval(new RemovalNotification<K, V>()));
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
    // optimistic fast path due to computeIfAbsent always locking
    V value = cache.get(key);
    if (value != null) {
      return value;
    }
    return cache.computeIfAbsent(key, (K k) -> {
      try {
        return loader.load(key);
      } catch (Exception e) {
        UnsafeAccess.UNSAFE.throwException(new ExecutionException(e));
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
    cache.clear();
  }

  @Override
  public void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      V value = cache.remove(key);
      if (value != null) {
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        notifyRemoval(RemovalCause.EXPLICIT, castKey, value);
      }
    }
  }

  @Override
  public void cleanUp() {}

  /* ---------------- Concurrent Map -------------- */

  @Override
  public boolean isEmpty() {
    return cache.isEmpty();
  }

  @Override
  public void clear() {
    cache.clear();
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
    return cache.remove(key);
  }

  @Override
  public boolean remove(Object key, Object value) {
    return cache.remove(key, value);
  }

  @Override
  public V replace(K key, V value) {
    return cache.replace(key, value);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    return cache.replace(key, oldValue, newValue);
  }

  @Override
  public Set<K> keySet() {
    return cache.keySet();
  }

  @Override
  public Collection<V> values() {
    return cache.values();
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return cache.entrySet();
  }
}
