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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Advance;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Generates test case scenarios based on the {@link CacheSpec}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PreferJavaTimeOverload")
final class CacheGenerator {
  // Integer caches the object identity semantics of autoboxing for values between
  // -128 and 127 (inclusive) as required by JLS (assuming default setting)
  private static final List<Map.Entry<Integer, Integer>> INTS = makeInts();
  private static final int BASE = 1_000;

  private final Options options;
  private final CacheSpec cacheSpec;
  private final boolean isAsyncOnly;
  private final boolean isLoadingOnly;

  public CacheGenerator(CacheSpec cacheSpec, Options options,
      boolean isLoadingOnly, boolean isAsyncOnly) {
    this.isLoadingOnly = isLoadingOnly;
    this.isAsyncOnly = isAsyncOnly;
    this.cacheSpec = cacheSpec;
    this.options = options;
  }

  /** Returns a lazy stream so that the test case is GC-able after use. */
  public Stream<Map.Entry<CacheContext, Cache<Integer, Integer>>> generate() {
    return combinations().stream()
        .map(this::newCacheContext)
        .filter(this::isCompatible)
        .map(context -> {
          Cache<Integer, Integer> cache = newCache(context);
          populate(context, cache);
          return Maps.immutableEntry(context, cache);
        });
  }

  /** Returns the Cartesian set of the possible cache configurations. */
  @SuppressWarnings("unchecked")
  private Set<List<Object>> combinations() {
    Set<Boolean> asyncLoading = ImmutableSet.of(true, false);
    Set<Stats> statistics = filterTypes(options.stats(), cacheSpec.stats());
    Set<ReferenceType> keys = filterTypes(options.keys(), cacheSpec.keys());
    Set<ReferenceType> values = filterTypes(options.values(), cacheSpec.values());
    Set<Compute> computations = filterTypes(options.compute(), cacheSpec.compute());
    Set<Implementation> implementations = filterTypes(
        options.implementation(), cacheSpec.implementation());

    if (isAsyncOnly) {
      values = values.contains(ReferenceType.STRONG)
          ? ImmutableSet.of(ReferenceType.STRONG)
          : ImmutableSet.of();
      computations = Sets.filter(computations, Compute.ASYNC::equals);
    }
    if (isAsyncOnly || computations.equals(ImmutableSet.of(Compute.ASYNC))) {
      implementations = implementations.contains(Implementation.Caffeine)
          ? ImmutableSet.of(Implementation.Caffeine)
          : ImmutableSet.of();
    }
    if (computations.equals(ImmutableSet.of(Compute.SYNC))) {
      asyncLoading = ImmutableSet.of(false);
    }

    if (computations.isEmpty() || implementations.isEmpty()
        || keys.isEmpty() || values.isEmpty()) {
      return ImmutableSet.of();
    }
    return Sets.cartesianProduct(
        ImmutableSet.copyOf(cacheSpec.initialCapacity()),
        ImmutableSet.copyOf(statistics),
        ImmutableSet.copyOf(cacheSpec.weigher()),
        ImmutableSet.copyOf(cacheSpec.maximumSize()),
        ImmutableSet.copyOf(cacheSpec.expiry()),
        ImmutableSet.copyOf(cacheSpec.expireAfterAccess()),
        ImmutableSet.copyOf(cacheSpec.expireAfterWrite()),
        ImmutableSet.copyOf(cacheSpec.refreshAfterWrite()),
        ImmutableSet.copyOf(cacheSpec.advanceOnPopulation()),
        ImmutableSet.copyOf(keys),
        ImmutableSet.copyOf(values),
        ImmutableSet.copyOf(cacheSpec.executor()),
        ImmutableSet.copyOf(cacheSpec.scheduler()),
        ImmutableSet.copyOf(cacheSpec.removalListener()),
        ImmutableSet.copyOf(cacheSpec.evictionListener()),
        ImmutableSet.copyOf(cacheSpec.population()),
        ImmutableSet.of(true, isLoadingOnly),
        ImmutableSet.copyOf(asyncLoading),
        ImmutableSet.copyOf(computations),
        ImmutableSet.copyOf(cacheSpec.loader()),
        ImmutableSet.copyOf(implementations));
  }

  /** Returns the set of options filtered if a specific type is specified. */
  private static <T> Set<T> filterTypes(Optional<T> type, T[] options) {
    if (type.isPresent()) {
      return type.filter(Arrays.asList(options)::contains).isPresent()
          ? ImmutableSet.of(type.get())
          : ImmutableSet.of();
    }
    return ImmutableSet.copyOf(options);
  }

  /** Returns a new cache context based on the combination. */
  private CacheContext newCacheContext(List<Object> combination) {
    int index = 0;
    return new CacheContext(
        (InitialCapacity) combination.get(index++),
        (Stats) combination.get(index++),
        (CacheWeigher) combination.get(index++),
        (Maximum) combination.get(index++),
        (CacheExpiry) combination.get(index++),
        (Expire) combination.get(index++),
        (Expire) combination.get(index++),
        (Expire) combination.get(index++),
        (Advance) combination.get(index++),
        (ReferenceType) combination.get(index++),
        (ReferenceType) combination.get(index++),
        (CacheExecutor) combination.get(index++),
        (CacheScheduler) combination.get(index++),
        (Listener) combination.get(index++),
        (Listener) combination.get(index++),
        (Population) combination.get(index++),
        (Boolean) combination.get(index++),
        (Boolean) combination.get(index++),
        (Compute) combination.get(index++),
        (Loader) combination.get(index++),
        (Implementation) combination.get(index++),
        cacheSpec);
  }

  /** Returns if the context is a viable configuration. */
  private boolean isCompatible(CacheContext context) {
    boolean asyncIncompatible = context.isAsync()
        && ((context.implementation() != Implementation.Caffeine) || !context.isStrongValues());
    boolean asyncLoaderIncompatible = context.isAsyncLoading()
        && (!context.isAsync() || !context.isLoading());
    boolean refreshIncompatible = context.refreshes() && !context.isLoading();
    boolean weigherIncompatible = context.isUnbounded() && context.isWeighted();
    boolean referenceIncompatible = cacheSpec.requiresWeakOrSoft()
        && context.isStrongKeys() && context.isStrongValues();
    boolean expiryIncompatible = (context.expiryType() != CacheExpiry.DISABLED)
        && ((context.implementation() != Implementation.Caffeine)
            || (context.expireAfterAccess() != Expire.DISABLED)
            || (context.expireAfterWrite() != Expire.DISABLED));
    boolean expirationIncompatible = (cacheSpec.mustExpireWithAnyOf().length > 0)
        && !Arrays.stream(cacheSpec.mustExpireWithAnyOf()).anyMatch(context::expires);
    boolean schedulerIgnored = (context.cacheScheduler != CacheScheduler.DEFAULT)
        && !context.expires();
    boolean evictionListenerIncompatible = (context.evictionListenerType() != Listener.DEFAULT)
        && ((context.implementation() != Implementation.Caffeine)
            || (context.isAsync() && context.isWeakKeys()));

    boolean skip = asyncIncompatible || asyncLoaderIncompatible || evictionListenerIncompatible
        || refreshIncompatible || weigherIncompatible || expiryIncompatible
        || expirationIncompatible || referenceIncompatible || schedulerIgnored;
    return !skip;
  }

  /** Creates a new cache based on the context's configuration. */
  public static <K, V> Cache<K, V> newCache(CacheContext context) {
    switch (context.implementation()) {
      case Caffeine:
        return CaffeineCacheFromContext.newCaffeineCache(context);
      case Guava:
        return GuavaCacheFromContext.newGuavaCache(context);
    }
    throw new IllegalStateException();
  }

  /** Fills the cache up to the population size. */
  @SuppressWarnings({"deprecation", "unchecked", "BoxedPrimitiveConstructor"})
  private void populate(CacheContext context, Cache<Integer, Integer> cache) {
    if (context.population.size() == 0) {
      return;
    }

    int maximum = (int) Math.min(context.maximumSize(), context.population.size());
    int first = BASE + (int) Math.min(0, context.population.size());
    int last = BASE + maximum - 1;
    int middle = Math.max(first, BASE + ((last - first) / 2));

    for (int i = 0; i < maximum; i++) {
      Map.Entry<Integer, Integer> entry = INTS.get(i);

      // Reference caching (weak, soft) require unique instances for identity comparison
      Integer key = context.isStrongKeys() ? entry.getKey() : new Integer(BASE + i);
      Integer value = context.isStrongValues() ? entry.getValue() : new Integer(-key);

      if (key == first) {
        context.firstKey = key;
      }
      if (key == middle) {
        context.middleKey = key;
      }
      if (key == last) {
        context.lastKey = key;
      }
      cache.put(key, value);
      context.original.put(key, value);
      context.ticker().advance(context.advance.timeNanos(), TimeUnit.NANOSECONDS);
    }
  }

  /** Returns a cache of integers and their negation. */
  @SuppressWarnings({"BoxedPrimitiveConstructor", "deprecation"})
  private static List<Map.Entry<Integer, Integer>> makeInts() {
    int size = Stream.of(CacheSpec.Population.values())
        .mapToInt(population -> Math.toIntExact(population.size()))
        .max().getAsInt();
    ImmutableList.Builder<Map.Entry<Integer, Integer>> builder = ImmutableList.builder();
    for (int i = 0; i < size; i++) {
      int value = BASE + i;
      builder.add(Maps.immutableEntry(new Integer(value), new Integer(-value)));
    }
    return builder.build();
  }
}
