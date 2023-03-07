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
package com.github.benmanes.caffeine.eclipse;

import java.time.Duration;

import org.eclipse.collections.api.map.ConcurrentMutableMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.eclipse.mutable.UnifiedMapTestCase;

/**
 * Eclipse Collections' map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BoundedUnifiedMapTest
    extends UnifiedMapTestCase implements CaffeineMutableMapTestCase {
  @Override public <K, V> Cache<K, V> newCache() {
    return Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE))
        .maximumSize(Long.MAX_VALUE)
        .build();
  }
  @Override public <K, V> ConcurrentMutableMap<K, V> newMap() {
    return CaffeineMutableMapTestCase.super.newMap();
  }
  @Override public <K, V> ConcurrentMutableMap<K, V> newMapWithKeyValue(K key, V value) {
    return CaffeineMutableMapTestCase.super.newMapWithKeyValue(key, value);
  }
  @Override public <K, V> ConcurrentMutableMap<K, V> newMapWithKeysValues(
      K key1, V value1, K key2, V value2) {
    return CaffeineMutableMapTestCase.super.newMapWithKeysValues(
        key1, value1, key2, value2);
  }
  @Override public <K, V> ConcurrentMutableMap<K, V> newMapWithKeysValues(
      K key1, V value1, K key2, V value2, K key3, V value3) {
    return CaffeineMutableMapTestCase.super.newMapWithKeysValues(
        key1, value1, key2, value2, key3, value3);
  }
  @Override public <K, V> ConcurrentMutableMap<K, V> newMapWithKeysValues(
      K key1, V value1, K key2, V value2, K key3, V value3, K key4, V value4) {
    return CaffeineMutableMapTestCase.super.newMapWithKeysValues(
        key1, value1, key2, value2, key3, value3, key4, value4);
  }
}
