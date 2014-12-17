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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.cache.CacheBuilder;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum CacheType {
  ConcurrentLinkedHashMap() {
    @Override public <K, V> Map<K, V> create(int maximumSize) {
      return new ConcurrentLinkedHashMap.Builder<K, V>()
        .maximumWeightedCapacity(maximumSize)
        .build();
    }
  },
  Guava() {
    @Override public <K, V> Map<K, V> create(int maximumSize) {
      return CacheBuilder.newBuilder().maximumSize(maximumSize).<K, V>build().asMap();
    }
  },
  LinkedHashMap_Lru() {
    @Override public <K, V> Map<K, V> create(int maximumSize) {
      return Collections.synchronizedMap(new BoundedLinkedHashMap<K, V>(true, maximumSize));
    }
  },
  ConcurrentHashMap() { // unbounded
    @Override public <K, V> Map<K, V> create(int maximumSize) {
      return new ConcurrentHashMap<K, V>(maximumSize);
    }
  },
  ConcurrentHashMapV7() { // unbounded, see OpenJDK/7u40-b43
    @Override public <K, V> Map<K, V> create(int maximumSize) {
      return new ConcurrentHashMapV7<K, V>(maximumSize);
    }
  };

  public abstract <K, V> Map<K, V> create(int maximumSize);
}
