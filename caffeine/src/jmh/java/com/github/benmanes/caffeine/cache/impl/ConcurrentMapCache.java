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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentMap;

import com.github.benmanes.caffeine.cache.BasicCache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ConcurrentMapCache<K, V> implements BasicCache<K, V> {
  private final ConcurrentMap<K, V> map;

  public ConcurrentMapCache(ConcurrentMap<K, V> map) {
    this.map = requireNonNull(map);
  }

  @Override
  public V get(K key) {
    return map.get(key);
  }

  @Override
  public void put(K key, V value) {
    map.put(key, value);
  }

  @Override
  public void clear() {
    map.clear();
  }
}
