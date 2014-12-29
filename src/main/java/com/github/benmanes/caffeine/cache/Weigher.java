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

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Calculates the weights of cache entries. The total weight threshold is used to determine when an
 * eviction is required.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ThreadSafe
@FunctionalInterface
public interface Weigher<K, V> {

  /**
   * Returns the weight of a cache entry. There is no unit for entry weights; rather they are simply
   * relative to each other.
   *
   * @param key the key to weigh
   * @param value the value to weigh
   * @return the weight of the entry; must be non-negative
   */
  @Nonnegative
  int weigh(@Nonnull K key, @Nonnull V value);

  /** Returns a weigher where an entry has a weight of <tt>1</tt>. */
  public static <K, V> Weigher<K, V> singleton() {
    @SuppressWarnings("unchecked")
    Weigher<K, V> self = (Weigher<K, V>) SingletonWeigher.INSTANCE;
    return self;
  }
}

enum SingletonWeigher implements Weigher<Object, Object> {
  INSTANCE;

  @Override public int weigh(Object key, Object value) {
    return 1;
  }
}
