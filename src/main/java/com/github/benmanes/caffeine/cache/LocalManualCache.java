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

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class LocalManualCache<K, V> implements Cache<K, V> {
  final LocalCache<K, V> localCache;

  LocalManualCache(LocalCache<K, V> localCache) {
    this.localCache = requireNonNull(localCache);
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
  public V get(K key, Callable<? extends V> valueLoader) throws ExecutionException {
    requireNonNull(valueLoader);
    return localCache.get(key, (Object k) -> valueLoader.call());
  }

  @Override
  public /* Immutable */ Map<K, V> getAllPresent(Iterable<?> keys) {
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
  public ConcurrentMap<K, V> asMap() {
    return localCache;
  }
}
