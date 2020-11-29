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

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;

import com.github.benmanes.caffeine.cache.BasicCache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Cache2k<K, V> implements BasicCache<K, V> {
  private final Cache<K, V> cache;

  @SuppressWarnings("unchecked")
  public Cache2k(int maximumSize) {
    cache = (Cache<K, V>) Cache2kBuilder.forUnknownTypes()
        .entryCapacity(maximumSize)
        .eternal(true)
        .build();
  }

  @Override
  public V get(K key) {
    return cache.peek(key);
  }

  @Override
  public void put(K key, V value) {
    cache.put(key, value);
  }

  @Override
  public void remove(K key) {
    cache.remove(key);
  }

  @Override
  public void clear() {
    cache.clear();
  }
}
