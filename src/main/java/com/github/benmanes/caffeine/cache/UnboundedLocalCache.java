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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.CacheLoader;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class UnboundedLocalCache<K, V> implements LocalCache<K, V> {
  final ConcurrentMap<K, V> cache;

  UnboundedLocalCache(Caffeine<? super K, ? super V> builder) {
    this.cache = new ConcurrentHashMap<K, V>();
  }

  // TODO(ben): Make sure to override default methods to delegate to optimized versions

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
    return null;
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {}

  @Override
  public V remove(Object key) {
    return null;
  }

  @Override
  public boolean remove(Object key, Object value) {
    return false;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    return false;
  }

  @Override
  public V replace(K key, V value) {
    return null;
  }

  @Override
  public Set<K> keySet() {
    return null;
  }

  @Override
  public Collection<V> values() {
    return null;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    return null;
  }



  // TODO

  @Override
  public V getIfPresent(Object key) {
    return null;
  }

  @Override
  public V get(K key, CacheLoader<? super K, V> loader) throws ExecutionException {
    return null;
  }
}
