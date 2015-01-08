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
package com.github.benmanes.caffeine.guava;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.CaffeinatedGuavaCacheLoader;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * An adapter to expose a Caffeine cache through the Guava interfaces.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinatedGuava {

  private CaffeinatedGuava() {}

  /** Returns a Caffeine cache wrapped in a Guava {@link Cache} facade. */
  public static <K1 extends K, K, V1 extends V, V>Cache<K1, V1> build(Caffeine<K, V> builder) {
    return new CaffeinatedGuavaCache<K1, V1>(builder.build());
  }

  /** Returns a Caffeine cache wrapped in a Guava {@link LoadingCache} facade. */
  public static <K1 extends K, K, V1 extends V, V> LoadingCache<K1, V1> build(
      Caffeine<K, V> builder, CacheLoader<? super K1, V1> cacheLoader) {
    @SuppressWarnings("unchecked")
    CacheLoader<K1, V1> loader = (CacheLoader<K1, V1>) cacheLoader;
    return build(builder, new CaffeinatedGuavaCacheLoader<>(loader));
  }

  /** Returns a Caffeine cache wrapped in a Guava {@link LoadingCache} facade. */
  public static <K1 extends K, K, V1 extends V, V> LoadingCache<K1, V1> build(
      Caffeine<K, V> builder,
      com.github.benmanes.caffeine.cache.CacheLoader<? super K1, V1> cacheLoader) {
    return new CaffeinatedGuavaLoadingCache<K1, V1>(builder.build(cacheLoader));
  }
}
