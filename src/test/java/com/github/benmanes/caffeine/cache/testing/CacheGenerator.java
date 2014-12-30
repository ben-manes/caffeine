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
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;

/**
 * Generates test case scenarios based on the {@link CacheSpec}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheGenerator {
  private final CacheSpec cacheSpec;
  private final boolean isLoadingOnly;

  private List<CacheContext> contexts;
  private List<CacheContext> combinations;

  public CacheGenerator(CacheSpec cacheSpec, boolean isLoadingOnly) {
    this.isLoadingOnly = isLoadingOnly;
    this.cacheSpec = cacheSpec;
  }

  /** Returns an iterator so that creating a test case is lazy and GC-able after use. */
  public Iterator<Entry<CacheContext, Cache<Integer, Integer>>> generate() {
    initialize();
    makeInitialCapacities();
    makeCacheStats();
    makeMaximumSizes();
    makeKeyReferences();
    makeValueReferences();
    makeExecutors();
    makeRemovalListeners();
    makePopulations();
    makeManualAndLoading();

    return Iterators.transform(Iterators.consumingIterator(contexts.iterator()),
        context -> makeCache(context));
  }

  private void initialize() {
    contexts = new ArrayList<>(64);
    contexts.add(new CacheContext());
    combinations = new ArrayList<>(64);
  }

  private void swap() {
    List<CacheContext> temp = contexts;
    contexts = combinations;
    combinations = temp;
    temp.clear();
  }

  /** Generates a new set of contexts with the initial capacity combinations. */
  private void makeInitialCapacities() {
    assertThat(cacheSpec.initialCapacity().length, is(greaterThan(0)));

    for (CacheContext context : contexts) {
      for (InitialCapacity initialCapacity : cacheSpec.initialCapacity()) {
        CacheContext copy = context.copy();
        copy.initialCapacity = initialCapacity;
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Generates a new set of contexts with the cache statistic combinations. */
  private void makeCacheStats() {
    for (CacheContext context : contexts) {
      for (Stats stats : cacheSpec.stats()) {
        CacheContext copy = context.copy();
        copy.stats = stats;
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Generates a new set of contexts with the maximum size combinations. */
  private void makeMaximumSizes() {
    for (CacheContext context : contexts) {
      for (MaximumSize maximumSize : cacheSpec.maximumSize()) {
        CacheContext copy = context.copy();
        copy.maximumSize = maximumSize;
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Generates a new set of contexts with the key reference combinations. */
  private void makeKeyReferences() {
    for (CacheContext context : contexts) {
      context.keyStrength = ReferenceType.STRONG;
    }
  }

  /** Generates a new set of contexts with the value reference combinations. */
  private void makeValueReferences() {
    for (CacheContext context : contexts) {
      context.valueStrength = ReferenceType.STRONG;
    }
  }

  /** Generates a new set of contexts with the executor combinations. */
  private void makeExecutors() {
    for (CacheContext context : contexts) {
      for (CacheExecutor executor : cacheSpec.executor()) {
        CacheContext copy = context.copy();
        copy.executor = executor.get();
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Generates a new set of builders with the removal listener combinations. */
  private void makeRemovalListeners() {
    for (CacheContext context : contexts) {
      for (Listener removalListenerType : cacheSpec.removalListener()) {
        CacheContext copy = context.copy();
        copy.removalListenerType = removalListenerType;
        copy.removalListener = removalListenerType.create();
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Generates a new set of builders with the population combinations. */
  private void makePopulations() {
    for (CacheContext context : contexts) {
      for (Population population : cacheSpec.population()) {
        CacheContext copy = context.copy();
        copy.population = population;
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Generates a new set of builders with the population combinations. */
  private void makeManualAndLoading() {
    for (CacheContext context : contexts) {
      if (!isLoadingOnly) {
        combinations.add(context);
      }
      for (Loader loader : cacheSpec.loader()) {
        CacheContext copy = context.copy();
        copy.loader = loader;
        combinations.add(copy);
      }
    }
    swap();
  }

  /** Constructs cache and populates. */
  private Entry<CacheContext, Cache<Integer, Integer>> makeCache(
      CacheContext context) {
    CacheContext copy = context.copy();
    Cache<Integer, Integer> cache = newCache(copy);
    context.population.populate(cache, copy);
    return Maps.immutableEntry(copy, cache);
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
}
