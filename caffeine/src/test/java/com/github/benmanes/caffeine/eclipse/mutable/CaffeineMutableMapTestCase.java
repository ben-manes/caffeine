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
package com.github.benmanes.caffeine.eclipse.mutable;

import java.util.stream.Stream;

import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.junit.jupiter.params.Parameter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheGenerator;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.eclipse.ConcurrentMapAdapter;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class CaffeineMutableMapTestCase {
  @Parameter CacheContext template;

  protected <K, V> ConcurrentMutableMap<K, V> newMap() {
    var context = new CacheContext(template);
    CacheGenerator.initialize(context);
    @SuppressWarnings("unchecked")
    var cache = (Cache<K, V>) context.cache();
    return new ConcurrentMapAdapter<>(cache.asMap());
  }

  protected <K, V> ConcurrentMutableMap<K, V> newMapWithKeyValue(K key, V value) {
    ConcurrentMutableMap<K, V> map = newMap();
    map.put(key, value);
    return map;
  }

  protected <K, V> ConcurrentMutableMap<K, V> newMapWithKeysValues(
      K key1, V value1, K key2, V value2) {
    ConcurrentMutableMap<K, V> map = newMap();
    map.put(key1, value1);
    map.put(key2, value2);
    return map;
  }

  protected <K, V> ConcurrentMutableMap<K, V> newMapWithKeysValues(
      K key1, V value1, K key2, V value2, K key3, V value3) {
    ConcurrentMutableMap<K, V> map = newMap();
    map.put(key1, value1);
    map.put(key2, value2);
    map.put(key3, value3);
    return map;
  }

  protected <K, V> ConcurrentMutableMap<K, V> newMapWithKeysValues(
      K key1, V value1, K key2, V value2, K key3, V value3, K key4, V value4) {
    ConcurrentMutableMap<K, V> map = newMap();
    map.put(key1, value1);
    map.put(key2, value2);
    map.put(key3, value3);
    map.put(key4, value4);
    return map;
  }

  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      weigher = CacheWeigher.DISABLED, removalListener = Listener.DISABLED,
      evictionListener = Listener.DISABLED, stats = Stats.ENABLED,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  protected static Stream<CacheContext> caches() throws NoSuchMethodException {
    var cacheSpec = CaffeineMutableMapTestCase.class.getDeclaredMethod("caches")
        .getAnnotation(CacheSpec.class);
    return new CacheGenerator(cacheSpec).generate();
  }
}
