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
package com.github.benmanes.caffeine;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;

/**
 * An adapter to expose a Caffeine cache through the Guava interfaces.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinatedGuava {

  private CaffeinatedGuava() {}

  /** Returns a Caffeine cache wrapped in a Guava {@link Cache} facade. */
  public <K, V> Cache<K, V> build(Caffeine<K, V> builder) {
    return new CaffeinatedGuavaCache<K, V>(builder.build());
  }

  /** Returns a Caffeine cache wrapped in a Guava {@link LoadingCache} facade. */
  public <K, V> LoadingCache<K, V> build(Caffeine<K, V> builder, CacheLoader<K, V> cacheLoader) {
    return new CaffeinatedGuavaLoadingCache<K, V>(builder.build(cacheLoader));
  }
}
