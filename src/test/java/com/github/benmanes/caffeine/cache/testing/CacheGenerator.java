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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.google.common.collect.ImmutableList;

/**
 * Generates test case scenarios based on the {@link CacheSpec}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheGenerator {
  private final CacheSpec cacheSpec;
  private List<CacheContext> contexts;
  private Map<CacheContext, Cache<Integer, Integer>> scenarios;

  public CacheGenerator(CacheSpec cacheSpec) {
    this.cacheSpec = cacheSpec;
  }

  public Map<CacheContext, Cache<Integer, Integer>> generate(boolean requiresLoadingCache) {
    initialize();
    makeInitialCapacities();
    makeCacheStats();
    // Disabled until supported to avoid duplicated tests
    // makeMaximumSizes();
    // makeKeyReferences();
    // makeValueReferences();
    makeExecutors();
    makeRemovalListeners();
    makeCachesAndPopulate(requiresLoadingCache);
    return scenarios;
  }

  private void initialize() {
    scenarios = new LinkedHashMap<>();
    contexts = ImmutableList.of(new CacheContext());
  }

  /** Generates a new set of contexts with the initial capacity combinations. */
  private void makeInitialCapacities() {
    assertThat(cacheSpec.initialCapacity().length, is(greaterThan(0)));

    List<CacheContext> combinations = new ArrayList<>();
    for (CacheContext context : contexts) {
      for (InitialCapacity initialCapacity : cacheSpec.initialCapacity()) {
        CacheContext copy = context.copy();
        copy.initialCapacity = initialCapacity;
        combinations.add(copy);
      }
    }
    contexts = combinations;
  }

  /** Generates a new set of contexts with the cache statistic combinations. */
  private void makeCacheStats() {
    List<CacheContext> combinations = new ArrayList<>();
    for (CacheContext context : contexts) {
      for (Stats stats : cacheSpec.stats()) {
        CacheContext copy = context.copy();
        copy.stats = stats;
        combinations.add(copy);
      }
    }
    contexts = combinations;
  }

  /** Generates a new set of contexts with the maximum size combinations. */
  private void makeMaximumSizes() {
    // TODO(ben): Support eviction
  }

  /** Generates a new set of contexts with the key reference combinations. */
  private void makeKeyReferences() {
    // TODO(ben): Support soft keys
  }

  /** Generates a new set of contexts with the value reference combinations. */
  private void makeValueReferences() {
    // TODO(ben): Support soft and weak values
  }

  /** Generates a new set of contexts with the executor combinations. */
  private void makeExecutors() {
    List<CacheContext> combinations = new ArrayList<>();
    for (CacheContext context : contexts) {
      for (CacheExecutor executor : cacheSpec.executor()) {
        CacheContext copy = context.copy();
        copy.executor = executor.get();
        combinations.add(copy);
      }
    }
    contexts = combinations;
  }

  /** Generates a new set of builders with the removal listener combinations. */
  private void makeRemovalListeners() {
    List<CacheContext> combinations = new ArrayList<>();
    for (CacheContext context : contexts) {
      for (Listener removalListenerType : cacheSpec.removalListener()) {
        CacheContext copy = context.copy();
        copy.removalListenerType = removalListenerType;
        copy.removalListener = removalListenerType.create();
        combinations.add(copy);
      }
    }
    contexts = combinations;
  }

  /** Constructs caches with the populated combinations. */
  private void makeCachesAndPopulate(boolean requiresLoadingCache) {
    assertThat(cacheSpec.population().length, is(greaterThan(0)));

    for (CacheContext context : contexts) {
      for (Population population : cacheSpec.population()) {
        context.population = population;
        if (!requiresLoadingCache) {
          makeCache(context, false);
        }
        makeCache(context, true);
      }
    }
  }

  private void makeCache(CacheContext context, boolean loading) {
    CacheContext copy = context.copy();
    Cache<Integer, Integer> cache = newCache(copy, loading);
    context.population.populate(copy, cache);
    scenarios.put(copy, cache);
  }

  private Cache<Integer, Integer> newCache(CacheContext context, boolean loading) {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != null) {
      throw new UnsupportedOperationException();
    }
    if (context.executor != null) {
      builder.executor(context.executor);
    }
    if (context.removalListener != null) {
      builder.removalListener(context.removalListener);
    }
    if (loading) {
      context.cache = builder.build(cacheSpec.loader());
    } else {
      context.cache = builder.build();
    }
    return context.cache;
  }
}
