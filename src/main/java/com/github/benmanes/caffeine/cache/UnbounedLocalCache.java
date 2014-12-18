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

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableMap;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class UnbounedLocalCache<K, V> {
  final ConcurrentMap<K, V> data;

  UnbounedLocalCache(Caffeine<? super K, ? super V> builder) {
    this.data = new ConcurrentHashMap<K, V>();
  }

  static final class UnbounedLocalCacheManual<K, V> implements Cache<K, V> {
    final UnbounedLocalCache<K, V> localCache;

    UnbounedLocalCacheManual(Caffeine<? super K, ? super V> builder) {
      localCache = new UnbounedLocalCache<K, V>(builder);
    }

    @Override
    public V getIfPresent(Object key) {
      return null;
    }

    @Override
    public V get(K key, Callable<? extends V> valueLoader) throws ExecutionException {
      return null;
    }

    @Override
    public ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
      return null;
    }

    @Override
    public void put(K key, V value) {}

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {}

    @Override
    public void invalidate(Object key) {}

    @Override
    public void invalidateAll(Iterable<?> keys) {}

    @Override
    public void invalidateAll() {}

    @Override
    public long size() {
      return 0;
    }

    @Override
    public CacheStats stats() {
      return null;
    }

    @Override
    public void cleanUp() {}

    @Override
    public ConcurrentMap<K, V> asMap() {
      return null;
    }
  }
}
