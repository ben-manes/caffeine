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
import java.util.stream.Stream;

import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.Cache;
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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.StartTime;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * Generates test case scenarios based on the {@link CacheSpec}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheGenerator {
  private static final ImmutableList<Map.Entry<Int, Int>> INTS = makeInts();
  private static final int BASE = 1_000;

  private final Options options;
  private final CacheSpec cacheSpec;
  private final boolean isAsyncOnly;
  private final boolean isLoadingOnly;
  private final boolean isGuavaCompatible;

  public CacheGenerator(CacheSpec cacheSpec) {
    this(cacheSpec, Options.fromSystemProperties(),
        /* isLoadingOnly= */ false, /* isAsyncOnly= */ false, /* isGuavaCompatible= */ true);
  }

  CacheGenerator(CacheSpec cacheSpec, Options options,
      boolean isLoadingOnly, boolean isAsyncOnly, boolean isGuavaCompatible) {
    this.isGuavaCompatible = isGuavaCompatible;
    this.isLoadingOnly = isLoadingOnly;
    this.isAsyncOnly = isAsyncOnly;
    this.cacheSpec = cacheSpec;
    this.options = options;
  }

  /** Returns a lazy stream so that the test case is GC-able after use. */
  public Stream<CacheContext> generate() {
    return combinations().stream()
        .map(this::newCacheContext)
        .filter(this::isCompatible);
  }

  /** Creates the cache and associates it with the context. */
  public static void initialize(CacheContext context) {
    populate(context, newCache(context));
  }

  /** Returns the Cartesian set of the possible cache configurations. */
  @SuppressWarnings("IdentityConversion")
  private Set<List<Object>> combinations() {
    var asyncLoader = ImmutableSet.of(true, false);
    var keys = filterTypes(options.keys(), cacheSpec.keys());
    var values = filterTypes(options.values(), cacheSpec.values());
    var statistics = filterTypes(options.stats(), cacheSpec.stats());
    var expiry = Sets.immutableEnumSet(Arrays.asList(cacheSpec.expiry()));
    var computations = filterTypes(options.compute(), cacheSpec.compute());
    var loaders = Sets.immutableEnumSet(Arrays.asList(cacheSpec.loader()));
    var weigher = Sets.immutableEnumSet(Arrays.asList(cacheSpec.weigher()));
    var executor = Sets.immutableEnumSet(Arrays.asList(cacheSpec.executor()));
    var scheduler = Sets.immutableEnumSet(Arrays.asList(cacheSpec.scheduler()));
    var startTime = Sets.immutableEnumSet(Arrays.asList(cacheSpec.startTime()));
    var population = Sets.immutableEnumSet(Arrays.asList(cacheSpec.population()));
    var maximumSize = Sets.immutableEnumSet(Arrays.asList(cacheSpec.maximumSize()));
    var implementations = filterTypes(options.implementation(), cacheSpec.implementation());
    var initialCapacity = Sets.immutableEnumSet(Arrays.asList(cacheSpec.initialCapacity()));
    var removalListener = Sets.immutableEnumSet(Arrays.asList(cacheSpec.removalListener()));
    var evictionListener = Sets.immutableEnumSet(Arrays.asList(cacheSpec.evictionListener()));
    var expireAfterWrite = Sets.immutableEnumSet(Arrays.asList(cacheSpec.expireAfterWrite()));
    var expireAfterAccess = Sets.immutableEnumSet(Arrays.asList(cacheSpec.expireAfterAccess()));
    var refreshAfterWrite = Sets.immutableEnumSet(Arrays.asList(cacheSpec.refreshAfterWrite()));

    if (isAsyncOnly) {
      values = values.contains(ReferenceType.STRONG)
          ? Sets.immutableEnumSet(ReferenceType.STRONG)
          : ImmutableSet.of();
      computations = Sets.intersection(computations,
          Sets.immutableEnumSet(Compute.ASYNC)).immutableCopy();
    }
    if (!isGuavaCompatible || isAsyncOnly
        || computations.equals(Sets.immutableEnumSet(Compute.ASYNC))) {
      implementations = Sets.difference(implementations,
          Sets.immutableEnumSet(Implementation.Guava)).immutableCopy();
    }
    if (computations.equals(Sets.immutableEnumSet(Compute.SYNC))) {
      asyncLoader = ImmutableSet.of(false);
    }

    if (isLoadingOnly) {
      loaders = Sets.difference(loaders, Sets.immutableEnumSet(Loader.DISABLED)).immutableCopy();
    }

    if (computations.isEmpty() || implementations.isEmpty() || keys.isEmpty() || values.isEmpty()) {
      return ImmutableSet.of();
    }

    return Sets.cartesianProduct(initialCapacity, statistics, weigher, maximumSize, expiry,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, keys, values, executor, scheduler,
        removalListener, evictionListener, population, asyncLoader, computations, loaders,
        implementations, startTime);
  }

  /** Returns the set of options filtered if a specific type is specified. */
  private static <T extends Enum<T>> ImmutableSet<T> filterTypes(Optional<T> type, T[] options) {
    return type.isPresent()
        ? Sets.intersection(Sets.immutableEnumSet(Arrays.asList(options)),
            Set.of(type.orElseThrow())).immutableCopy()
        : Sets.immutableEnumSet(Arrays.asList(options));
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
        (ReferenceType) combination.get(index++),
        (ReferenceType) combination.get(index++),
        (CacheExecutor) combination.get(index++),
        (CacheScheduler) combination.get(index++),
        (Listener) combination.get(index++),
        (Listener) combination.get(index++),
        (Population) combination.get(index++),
        (Boolean) combination.get(index++),
        (Compute) combination.get(index++),
        (Loader) combination.get(index++),
        (Implementation) combination.get(index++),
        (StartTime) combination.get(index),
        cacheSpec.expiryTime());
  }

  /** Returns if the context is a viable configuration. */
  private boolean isCompatible(CacheContext context) {
    boolean asyncIncompatible = context.isAsync()
        && (!context.isCaffeine() || !context.isStrongValues());
    boolean asyncLoaderIncompatible = context.isAsyncLoader()
        && (!context.isAsync() || !context.isLoading());
    boolean refreshIncompatible = context.refreshes() && !context.isLoading();
    boolean weigherIncompatible = (context.maximum() == Maximum.DISABLED) && context.isWeighted();
    boolean referenceIncompatible = cacheSpec.requiresWeakOrSoft()
        && context.isStrongKeys() && context.isStrongValues();
    boolean expiryIncompatible = (context.expiryType() != CacheExpiry.DISABLED)
        && (!context.isCaffeine()
            || (context.expireAfterAccess() != Expire.DISABLED)
            || (context.expireAfterWrite() != Expire.DISABLED));
    boolean expirationIncompatible = (cacheSpec.mustExpireWithAnyOf().length > 0)
        && Arrays.stream(cacheSpec.mustExpireWithAnyOf()).noneMatch(context::expires);
    boolean schedulerIgnored = (context.cacheScheduler != CacheScheduler.DISABLED)
        && (!context.expires() || context.isGuava());
    boolean evictionListenerIncompatible = (context.evictionListenerType() != Listener.DISABLED)
        && (!context.isCaffeine() || (context.isAsync() && context.isWeakKeys()));

    boolean skip = asyncIncompatible || asyncLoaderIncompatible || evictionListenerIncompatible
        || refreshIncompatible || weigherIncompatible || expiryIncompatible
        || expirationIncompatible || referenceIncompatible || schedulerIgnored;
    return !skip;
  }

  /** Creates a new cache based on the context's configuration. */
  private static <K, V> Cache<K, V> newCache(CacheContext context) {
    if (context.isCaffeine()) {
      return CaffeineCacheFromContext.newCaffeineCache(context);
    } else if (context.isGuava()) {
      return GuavaCacheFromContext.newGuavaCache(context);
    }
    throw new IllegalStateException();
  }

  /** Fills the cache up to the population size. */
  @SuppressWarnings("unchecked")
  private static void populate(CacheContext context, Cache<Int, Int> cache) {
    if (context.population.size() == 0) {
      // timerWheel clock initialization
      cache.cleanUp();
      return;
    }

    int maximum = Math.toIntExact(Math.min(context.maximumSize(), context.population.size()));
    int first = Math.toIntExact(BASE + Math.min(0, context.population.size()));
    int last = BASE + maximum - 1;
    int middle = Math.max(first, BASE + ((last - first) / 2));

    for (int i = 0; i < maximum; i++) {
      Map.Entry<Int, Int> entry = INTS.get(i);

      // Reference caching (weak, soft) require unique instances for identity comparison
      var key = context.isStrongKeys() ? entry.getKey() : new Int(BASE + i);
      var value = context.isStrongValues() ? entry.getValue() : new Int(-key.intValue());

      if (key.intValue() == first) {
        context.firstKey = key;
      }
      if (key.intValue() == middle) {
        context.middleKey = key;
      }
      if (key.intValue() == last) {
        context.lastKey = key;
      }
      cache.put(key, value);
      context.original.put(key, value);
    }
    if (context.executorType() != CacheExecutor.DIRECT) {
      cache.cleanUp();
    }
    if (context.expiryType() == CacheExpiry.MOCKITO) {
      Mockito.clearInvocations(context.expiry());
    }
  }

  /** Returns a cache of integers and their negation. */
  private static ImmutableList<Map.Entry<Int, Int>> makeInts() {
    int size = Arrays.stream(Population.values())
        .mapToInt(population -> Math.toIntExact(population.size()))
        .max().getAsInt();
    var builder = new ImmutableList.Builder<Map.Entry<Int, Int>>();
    for (int i = 0; i < size; i++) {
      Int value = Int.valueOf(BASE + i);
      builder.add(Map.entry(value, value.negate()));
    }
    return builder.build();
  }
}
