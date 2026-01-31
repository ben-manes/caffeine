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
package com.github.benmanes.caffeine.apache;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ConcurrentMap;

import org.apache.commons.collections4.map.AbstractMapTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedClass;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheContext;
import com.github.benmanes.caffeine.cache.CacheGenerator;
import com.github.benmanes.caffeine.cache.CacheSpec;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.Stats;

/**
 * Apache Commons Collections' map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@ParameterizedClass
@CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
    weigher = CacheWeigher.DISABLED, removalListener = Listener.DISABLED,
    evictionListener = Listener.DISABLED, stats = Stats.ENABLED)
final class ApacheMapTest<K, V> extends AbstractMapTest<ConcurrentMap<K, V>, K, V> {
  final CacheContext template;

  ApacheMapTest(CacheContext template) {
    this.template = requireNonNull(template);
  }
  @Override public boolean isAllowNullKey() {
    return false;
  }
  @Override public boolean isAllowNullValueGet() {
    return false;
  }
  @Override public boolean isAllowNullValuePut() {
    return false;
  }
  @Override public boolean isTestSerialization() {
    return false;
  }
  @Override public ConcurrentMap<K, V> makeObject() {
    var context = new CacheContext(template);
    CacheGenerator.initialize(context);
    @SuppressWarnings("unchecked")
    var cache = (Cache<K, V>) context.cache();
    return cache.asMap();
  }
  @Nested final class MapKeySetTest extends TestMapKeySet {}
  @Nested final class MapValuesTest extends TestMapValues {}
  @Nested final class MapEntrySetTest extends TestMapEntrySet {}
}
