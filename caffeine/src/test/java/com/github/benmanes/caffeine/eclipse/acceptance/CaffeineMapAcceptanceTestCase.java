/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.eclipse.acceptance;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.junit.jupiter.params.Parameter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.eclipse.ConcurrentMapAdapter;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class CaffeineMapAcceptanceTestCase {
  @Parameter Supplier<Caffeine<Object, Object>> supplier;

  protected <K, V> ConcurrentMutableMap<K, V> newMap(int initialCapacity) {
    Cache<K, V> cache = supplier.get().initialCapacity(initialCapacity).build();
    return new ConcurrentMapAdapter<>(cache.asMap());
  }

  protected <K, V> ConcurrentMutableMap<K, V> newMap() {
    Cache<K, V> cache = supplier.get().build();
    return new ConcurrentMapAdapter<>(cache.asMap());
  }

  static Stream<Supplier<Caffeine<Object, Object>>> caches() {
    return Stream.of(
        () -> Caffeine.newBuilder(),
        () -> Caffeine.newBuilder()
            .maximumSize(Long.MAX_VALUE)
            .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE))
    );
  }
}
