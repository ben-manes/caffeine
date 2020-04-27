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

import systems.comodal.collision.cache.CollisionCache;

/**
 * Requires JDK9.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Collision<K, V> implements BasicCache<K, V> {
  private final CollisionCache<K, V> cache;

  public Collision(int maximumSize) {
    cache = CollisionCache.<V>withCapacity(maximumSize)
        .setStrictCapacity(true)
        .buildSparse();
  }

  @Override
  public V get(K key) {
    return cache.getIfPresent(key);
  }

  @Override
  public void put(K key, V value) {
    cache.putReplace(key, value);
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
