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
package com.github.benmanes.caffeine.cache.testing;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Generates test case scenarios based on the {@link CacheSpec}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheGenerator {
  private final CacheSpec cacheSpec;
  private final boolean isLoadingOnly;

  public CacheGenerator(CacheSpec cacheSpec, boolean isLoadingOnly) {
    this.isLoadingOnly = isLoadingOnly;
    this.cacheSpec = cacheSpec;
  }

  /** Returns an iterator so that creating a test case is lazy and GC-able after use. */
  public Iterator<Entry<CacheContext, Cache<Integer, Integer>>> generate() {
    return combinations().stream()
        .map(this::newCacheContext)
        .map(context -> {
          Cache<Integer, Integer> cache = newCache(context);
          populate(context, cache);
          return Maps.immutableEntry(context, cache);
        }).iterator();
  }

  @SuppressWarnings("unchecked")
  private Set<List<Object>> combinations() {
    return Sets.cartesianProduct(
        ImmutableSet.copyOf(cacheSpec.initialCapacity()),
        ImmutableSet.copyOf(cacheSpec.stats()),
        ImmutableSet.copyOf(cacheSpec.maximumSize()),
        ImmutableSet.copyOf(cacheSpec.expireAfterAccess()),
        ImmutableSet.copyOf(cacheSpec.expireAfterWrite()),
        ImmutableSet.copyOf(cacheSpec.keys()),
        ImmutableSet.copyOf(cacheSpec.values()),
        ImmutableSet.copyOf(cacheSpec.executor()),
        ImmutableSet.copyOf(cacheSpec.removalListener()),
        ImmutableSet.copyOf(cacheSpec.population()),
        ImmutableSet.of(true, isLoadingOnly),
        ImmutableSet.copyOf(cacheSpec.loader()));
  }

  private CacheContext newCacheContext(List<Object> combination) {
    return new CacheContext(
        (InitialCapacity) combination.get(0),
        (Stats) combination.get(1),
        (MaximumSize) combination.get(2),
        (Expire) combination.get(3),
        (Expire) combination.get(4),
        (ReferenceType) combination.get(5),
        (ReferenceType) combination.get(6),
        (CacheExecutor) combination.get(7),
        (Listener) combination.get(8),
        (Population) combination.get(9),
        (Boolean) combination.get(10),
        (Loader) combination.get(11));
  }

  private Cache<Integer, Integer> newCache(CacheContext context) {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != MaximumSize.DISABLED) {
      builder.maximumSize(context.maximumSize.max());
    }
    if (context.afterAccess != Expire.DISABLED) {
      builder.expireAfterAccess(context.afterAccess.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.afterWrite != Expire.DISABLED) {
      builder.expireAfterWrite(context.afterWrite.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.expires()) {
      builder.ticker(context.ticker());
    }
    if (context.keyStrength == ReferenceType.WEAK) {
      builder.weakKeys();
    } else if (context.keyStrength == ReferenceType.SOFT) {
      throw new IllegalStateException();
    }
    if (context.valueStrength == ReferenceType.WEAK) {
      builder.weakValues();
    } else if (context.valueStrength == ReferenceType.SOFT) {
      builder.softValues();
    }
    if (context.executor != null) {
      builder.executor(context.executor);
    }
    if (context.removalListenerType != Listener.DEFAULT) {
      builder.removalListener(context.removalListener);
    }
    if (context.loader == null) {
      context.cache = builder.build();
    } else {
      context.cache = builder.build(context.loader);
    }
    return context.cache;
  }

  private void populate(CacheContext context, Cache<Integer, Integer> cache) {
    int maximum = (int) Math.min(context.maximumSize(), context.population.size());
    context.firstKey = (int) Math.min(1, context.population.size());
    context.lastKey = maximum;
    context.middleKey = Math.max(context.firstKey, ((context.lastKey - context.firstKey) / 2));
    for (int i = 1; i <= maximum; i++) {
      context.original.put(i, -i);
      cache.put(i, -i);
    }
  }
}
