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
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
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

  public Map<CacheContext, Cache<Integer, Integer>> generate() {
    initialize();
    makeInitialCapacities();
    makeKeyReferences();
    makeValueReferences();
    makeExecutors();
    makeRemovalListeners();
    makeCachesAndPopulate();
    return scenarios;
  }

  void initialize() {
    scenarios = new LinkedHashMap<>();
    contexts = ImmutableList.of(new CacheContext());
  }

  /** Generates a new set of contexts with the initial capacity combinations. */
  void makeInitialCapacities() {
    assertThat(cacheSpec.initialCapacity().length, is(greaterThan(0)));

    List<CacheContext> combinations = new ArrayList<>();
    for (CacheContext context : contexts) {
      for (int initialCapacity : cacheSpec.initialCapacity()) {
        CacheContext copy = context.copy();
        if (initialCapacity != CacheSpec.DEFAULT_INITIAL_CAPACITY) {
          copy.initialCapacity = initialCapacity;
        }
        combinations.add(copy);
      }
    }
    contexts = combinations;
  }

  /** Generates a new set of contexts with the key reference combinations. */
  void makeKeyReferences() {
    // TODO(ben): Support soft keys
    assertThat(cacheSpec.keys().length, is(greaterThan(0)));
    assertThat(cacheSpec.keys(), is(arrayContaining(ReferenceType.STRONG)));
  }

  /** Generates a new set of contexts with the value reference combinations. */
  void makeValueReferences() {
    // TODO(ben): Support soft and weak values
    assertThat(cacheSpec.values().length, is(greaterThan(0)));
    assertThat(cacheSpec.values(), is(arrayContaining(ReferenceType.STRONG)));
  }

  /** Generates a new set of contexts with the executor combinations. */
  void makeExecutors() {
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

  /** Generates a new set of contexts with the maximum size combinations. */
  static List<CacheContext> makeMaximumSizes(
      CacheSpec cacheSpec, List<CacheContext> contexts) throws Exception {
    assertThat(cacheSpec.maximumSize().length, is(greaterThan(0)));

    // TODO(ben): Support eviction
    assertThat(cacheSpec.maximumSize().length, is(1));
    assertThat(cacheSpec.maximumSize()[0], is(CacheSpec.UNBOUNDED));
    return contexts;
  }

  /** Generates a new set of builders with the removal listener combinations. */
  void makeRemovalListeners() {
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
  void makeCachesAndPopulate() {
    assertThat(cacheSpec.population().length, is(greaterThan(0)));

    for (CacheContext context : contexts) {
      for (Population population : cacheSpec.population()) {
        CacheContext copy = context.copy();
        copy.population = population;

        Cache<Integer, Integer> cache = newCache(copy);
        population.populate(copy, cache);
        scenarios.put(copy, cache);
      }
    }
  }

  static Cache<Integer, Integer> newCache(CacheContext context) {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (context.initialCapacity != null) {
      builder.initialCapacity(context.initialCapacity);
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
    return builder.build();
  }
}
