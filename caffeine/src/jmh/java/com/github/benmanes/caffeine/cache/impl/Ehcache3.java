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

import org.ehcache.Cache;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.builders.UserManagedCacheBuilder;
import org.ehcache.config.units.EntryUnit;

import com.github.benmanes.caffeine.cache.BasicCache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Ehcache3<K, V> implements BasicCache<K, V> {
  private final Cache<K, V> cache;

  @SuppressWarnings("unchecked")
  public Ehcache3(int maximumSize) {
    var resourcesPools = ResourcePoolsBuilder.newResourcePoolsBuilder()
        .heap(maximumSize, EntryUnit.ENTRIES);
    cache = (Cache<K, V>) UserManagedCacheBuilder
        .newUserManagedCacheBuilder(Object.class, Object.class)
        .withResourcePools(resourcesPools)
        .build(true);
  }

  @Override
  public V get(K key) {
    return cache.get(key);
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
