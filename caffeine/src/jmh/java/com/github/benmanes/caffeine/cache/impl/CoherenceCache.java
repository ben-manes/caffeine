/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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

import com.github.benmanes.caffeine.cache.BasicCache;
import com.tangosol.net.cache.LocalCache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"deprecation", "unchecked"})
public final class CoherenceCache<K, V> implements BasicCache<K, V> {
  @SuppressWarnings("PMD.LooseCoupling")
  private final LocalCache cache;

  public CoherenceCache(int maximumSize, int evictionPolicyType) {
    cache = new LocalCache(Math.max(1, maximumSize));
    cache.setEvictionType(evictionPolicyType);
  }

  @Override
  public V get(K key) {
    return (V) cache.get(key);
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
