/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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

import org.rapidoid.cache.Cache;
import org.rapidoid.cache.Caching;

import com.github.benmanes.caffeine.cache.BasicCache;

/**
 * @author nikolce.mihajlovski@gmail.com (Nikolche Mihajlovski)
 */
public final class RapidoidCache<K, V> implements BasicCache<K, V> {
  private final Cache<K, V> cache;

  public RapidoidCache(int maximumSize) {
    cache = Caching.of((K key) -> (V) null)
        .capacity(maximumSize)
        .build();
  }

  @Override
  public V get(K key) {
    return cache.getIfExists(key);
  }

  @Override
  public void put(K key, V value) {
    cache.set(key, value);
  }

  @Override
  public void clear() {
    cache.clear();
  }
}
