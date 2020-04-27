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
package com.github.benmanes.caffeine.cache.impl;

import static com.github.benmanes.caffeine.cache.CacheType.CONCURRENCY_LEVEL;
import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.BasicCache;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GuavaCache<K, V> implements BasicCache<K, V> {
  private final Cache<K, V> cache;

  public GuavaCache(int maximumSize) {
    cache = CacheBuilder.newBuilder()
        .concurrencyLevel(CONCURRENCY_LEVEL)
        .initialCapacity(maximumSize)
        .maximumSize(maximumSize)
        .build();
  }

  public GuavaCache(Cache<K, V> cache) {
    this.cache = requireNonNull(cache);
  }

  @Override
  public V get(K key) {
    return cache.getIfPresent(key);
  }

  @Override
  public void put(K key, V value) {
    cache.put(key, value);
  }

  @Override
  public void remove(K key) {
    cache.invalidate(key);
  }

  @Override
  public void clear() {
    cache.invalidateAll();
  }

  @Override
  public void cleanUp() {
    cache.cleanUp();
  }
}
