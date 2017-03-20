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

import com.github.benmanes.caffeine.cache.BasicCache;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Ehcache2<K, V> implements BasicCache<K, V> {
  private final Ehcache cache;

  public Ehcache2(int maximumSize, MemoryStoreEvictionPolicy evictionPolicy) {
    CacheConfiguration config = new CacheConfiguration("benchmark", maximumSize);
    config.setMemoryStoreEvictionPolicyFromObject(evictionPolicy);
    cache = new Cache(config);

    CacheManager cacheManager = new CacheManager();
    cacheManager.addCache(cache);
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get(K key) {
    Element element = cache.get(key);
    return (element == null) ? null : (V) element.getObjectValue();
  }

  @Override
  public void put(K key, V value) {
    cache.put(new Element(key, value));
  }

  @Override
  public void clear() {
    cache.removeAll();
  }
}
