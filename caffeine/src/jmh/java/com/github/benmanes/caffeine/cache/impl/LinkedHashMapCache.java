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
package com.github.benmanes.caffeine.cache.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.github.benmanes.caffeine.cache.BasicCache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LinkedHashMapCache<K, V> implements BasicCache<K, V> {
  private final Map<K, V> map;
  private final ReentrantLock mapLock = new ReentrantLock();

  public LinkedHashMapCache(int maximumSize, boolean accessOrder) {
    map = new BoundedLinkedHashMap<>(maximumSize, accessOrder);
  }

  @Override
  public V get(K key) {
    mapLock.lock();
    try {
      return map.get(key);
    } finally {
      mapLock.unlock();
    }
  }

  @Override
  public void put(K key, V value) {
    mapLock.lock();
    try {
      map.put(key, value);
    } finally {
      mapLock.unlock();
    }
  }

  @Override
  public void remove(K key) {
    mapLock.lock();
    try {
      map.remove(key);
    } finally {
      mapLock.unlock();
    }
  }

  @Override
  public void clear() {
    mapLock.lock();
    try {
      map.clear();
    } finally {
      mapLock.unlock();
    }
  }

  static final class BoundedLinkedHashMap<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;
    private final int maximumSize;

    public BoundedLinkedHashMap(int maximumSize, boolean accessOrder) {
      super(maximumSize, 0.75f, accessOrder);
      this.maximumSize = maximumSize;
    }

    @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      return size() > maximumSize;
    }
  }
}
