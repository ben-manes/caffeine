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

import java.lang.reflect.Method;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalSingleLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.InternalBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.InternalSingleLoader;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Static utility methods pertaining to adapting between Caffeine and Guava cache interfaces.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinatedGuava {

  private CaffeinatedGuava() {}

  /**
   * Returns a Caffeine cache wrapped in a Guava {@link Cache} facade.
   *
   * @param builder the configured cache builder
   * @return a cache exposed under the Guava APIs
   */
  public static <K, V, K1 extends K, V1 extends V> Cache<K1, V1> build(Caffeine<K, V> builder) {
    return new CaffeinatedGuavaCache<>(builder.build());
  }

  /**
   * Returns a Caffeine cache wrapped in a Guava {@link LoadingCache} facade.
   *
   * @param builder the configured cache builder
   * @param loader the cache loader used to obtain new values
   * @return a cache exposed under the Guava APIs
   */
  public static <K, V, K1 extends K, V1 extends V> LoadingCache<K1, V1> build(
      Caffeine<K, V> builder, CacheLoader<? super K1, V1> loader) {
    return build(builder, hasLoadAll(loader)
        ? new InternalBulkLoader<>(loader)
        : new InternalSingleLoader<>(loader));
  }

  /**
   * Returns a Caffeine cache wrapped in a Guava {@link LoadingCache} facade.
   *
   * @param builder the configured cache builder
   * @param loader the cache loader used to obtain new values
   * @return a cache exposed under the Guava APIs
   */
  public static <K, V, K1 extends K, V1 extends V> LoadingCache<K1, V1> build(
      Caffeine<K, V> builder,
      com.github.benmanes.caffeine.cache.CacheLoader<? super K1, V1> loader) {
    return new CaffeinatedGuavaLoadingCache<>(builder.build(loader));
  }

  /**
   * Returns a Caffeine cache loader that delegates to a Guava cache loader.
   *
   * @param loader the cache loader used to obtain new values
   * @return a cache loader exposed under the Caffeine APIs
   */
  public static <K, V> com.github.benmanes.caffeine.cache.CacheLoader<K, V> caffeinate(
      CacheLoader<K, V> loader) {
    return hasLoadAll(loader)
        ? new ExternalBulkLoader<>(loader)
        : new ExternalSingleLoader<>(loader);
  }

  static boolean hasLoadAll(CacheLoader<?, ?> cacheLoader) {
    return hasMethod(cacheLoader, "loadAll", Iterable.class);
  }

  static boolean hasMethod(CacheLoader<?, ?> cacheLoader, String name, Class<?>... paramTypes) {
    try {
      Method method = cacheLoader.getClass().getMethod(name, paramTypes);
      return (method.getDeclaringClass() != CacheLoader.class);
    } catch (NoSuchMethodException | SecurityException e) {
      return false;
    }
  }
}
