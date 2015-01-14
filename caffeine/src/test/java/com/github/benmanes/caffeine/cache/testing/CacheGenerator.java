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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
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
  private final boolean isAsyncLoadingOnly;

  public CacheGenerator(CacheSpec cacheSpec, boolean isLoadingOnly, boolean isAsyncLoadingOnly) {
    this.isAsyncLoadingOnly = isAsyncLoadingOnly;
    this.isLoadingOnly = isLoadingOnly;
    this.cacheSpec = cacheSpec;
  }

  /** Returns a lazy stream so that the test case (GC-able after use). */
  public Stream<Entry<CacheContext, Cache<Integer, Integer>>> generate(
      Optional<Compute> compute, Optional<Implementation> implementation,
      Optional<ReferenceType> keyType, Optional<ReferenceType> valueType) {
    return combinations(compute, implementation, keyType, valueType).stream()
        .map(this::newCacheContext)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(context -> {
          Cache<Integer, Integer> cache = CacheFromContext.newCache(context);
          populate(context, cache);
          return Maps.immutableEntry(context, cache);
        });
  }

  @SuppressWarnings("unchecked")
  private Set<List<Object>> combinations(Optional<Compute> compute,
      Optional<Implementation> implementation, Optional<ReferenceType> keyType,
      Optional<ReferenceType> valueType) {
    Set<ReferenceType> keys = filterTypes(keyType, cacheSpec.keys());
    Set<ReferenceType> values = filterTypes(valueType, cacheSpec.values());
    Set<Compute> computations = filterTypes(compute, cacheSpec.compute());
    Set<Implementation> implementations = filterTypes(implementation, cacheSpec.implementation());

    if (isAsyncLoadingOnly) {
      values = values.contains(ReferenceType.STRONG)
          ? ImmutableSet.of(ReferenceType.STRONG)
          : ImmutableSet.of();
      computations.remove(Compute.SYNC);
    }
    if (isAsyncLoadingOnly || computations.equals(ImmutableSet.of(Compute.ASYNC))) {
      implementations = implementations.contains(Implementation.Caffeine)
          ? ImmutableSet.of(Implementation.Caffeine)
          : ImmutableSet.of();
    }

    if (computations.isEmpty() || implementations.isEmpty() || keys.isEmpty() || values.isEmpty()) {
      return ImmutableSet.of();
    }
    return Sets.cartesianProduct(
        ImmutableSet.copyOf(cacheSpec.initialCapacity()),
        ImmutableSet.copyOf(cacheSpec.stats()),
        ImmutableSet.copyOf(cacheSpec.weigher()),
        ImmutableSet.copyOf(cacheSpec.maximumSize()),
        ImmutableSet.copyOf(cacheSpec.expireAfterAccess()),
        ImmutableSet.copyOf(cacheSpec.expireAfterWrite()),
        ImmutableSet.copyOf(keys),
        ImmutableSet.copyOf(values),
        ImmutableSet.copyOf(cacheSpec.executor()),
        ImmutableSet.copyOf(cacheSpec.removalListener()),
        ImmutableSet.copyOf(cacheSpec.population()),
        ImmutableSet.of(true, isLoadingOnly),
        ImmutableSet.copyOf(computations),
        ImmutableSet.copyOf(cacheSpec.loader()),
        ImmutableSet.copyOf(implementations));
  }

  private static <T> Set<T> filterTypes(Optional<T> type, T[] options) {
    if (type.isPresent()) {
      return type.filter(Arrays.asList(options)::contains).isPresent()
          ? new LinkedHashSet<>(Arrays.asList(type.get()))
          : new LinkedHashSet<>();
    }
    return new LinkedHashSet<>(Arrays.asList(options));
  }

  private Optional<CacheContext> newCacheContext(List<Object> combination) {
    int index = 0;
    CacheContext context = new CacheContext(
        (InitialCapacity) combination.get(index++),
        (Stats) combination.get(index++),
        (CacheWeigher) combination.get(index++),
        (MaximumSize) combination.get(index++),
        (Expire) combination.get(index++),
        (Expire) combination.get(index++),
        (ReferenceType) combination.get(index++),
        (ReferenceType) combination.get(index++),
        (CacheExecutor) combination.get(index++),
        (Listener) combination.get(index++),
        (Population) combination.get(index++),
        (Boolean) combination.get(index++),
        (Compute) combination.get(index++),
        (Loader) combination.get(index++),
        (Implementation) combination.get(index++));

    boolean asyncIncompatible = (context.implementation() != Implementation.Caffeine)
        || (context.valueStrength() != ReferenceType.STRONG);
    boolean skip = context.isAsync() && asyncIncompatible;

    return skip ? Optional.empty() : Optional.of(context);
  }

  private void populate(CacheContext context, Cache<Integer, Integer> cache) {
    if (context.population.size() == 0) {
      return;
    }

    // Integer caches the object identity semantics of autoboxing for values between
    // -128 and 127 (inclusive) as required by JLS
    int base = 1000;

    int maximum = (int) Math.min(context.maximumSize(), context.population.size());
    int first = base + (int) Math.min(1, context.population.size());
    int last = base + maximum;
    int middle = Math.max(first, base + ((last - first) / 2));

    // Ensure references are the same for identity comparison (soft, weak)
    for (int i = 1; i <= maximum; i++) {
      Integer key = (base + i);
      if (key == first) {
        context.firstKey = key;
      }
      if (key == middle) {
        context.middleKey = key;
      }
      if (key == last) {
        context.lastKey = key;
      }
      context.original.put(key, -key);
    }
    cache.putAll(context.original);
  }
}
