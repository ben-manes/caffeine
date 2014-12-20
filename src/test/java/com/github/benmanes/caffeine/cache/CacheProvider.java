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
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.DataProvider;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.google.common.collect.ImmutableSet;

/**
 * A data provider that generates caches based on the {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider {
  public static final int UNSET_INT = -1;
  public static final int POPULATED_SIZE = 100;

  @DataProvider(name = "caches")
  public static Iterator<Object[]> providesCaches(Method testMethod) throws Exception {
    CacheSpec cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    requireNonNull(cacheSpec, "@CacheSpec not found");

    Set<Caffeine<Object, Object>> builders = ImmutableSet.of(Caffeine.newBuilder());
    builders = makeInitialCapacities(cacheSpec, builders);
    builders = makeRemovalListeners(cacheSpec, builders);
    builders = makeExecutors(cacheSpec, builders);

    Set<Cache<Integer, Integer>> caches = makePopulations(cacheSpec, builders);
    return toTestCases(caches);
  }

  /** Generates a new set of builders with the executor combinations. */
  static Set<Caffeine<Object, Object>> makeExecutors(
      CacheSpec cacheSpec, Set<Caffeine<Object, Object>> builders) throws Exception {
    if (cacheSpec.executor().length == 0) {
      return builders;
    }
    int combinationCount = cacheSpec.executor().length * builders.size();
    Set<Caffeine<Object, Object>> combinations = new LinkedHashSet<>(combinationCount);
    for (Caffeine<Object, Object> builder : builders) {
      for (CacheExecutor executor : cacheSpec.executor()) {
        Caffeine<Object, Object> copy = builder.copy();
        if (executor != CacheExecutor.UNSET) {
          copy.executor(executor.get());
        }
        combinations.add(copy);
      }
    }
    return Collections.unmodifiableSet(combinations);
  }

  /** Generates a new set of builders with the removal listener combinations. */
  static Set<Caffeine<Object, Object>> makeRemovalListeners(
      CacheSpec cacheSpec, Set<Caffeine<Object, Object>> builders) throws Exception {
    if (cacheSpec.removalListener().length == 0) {
      return builders;
    }
    int combinationCount = cacheSpec.removalListener().length * builders.size();
    Set<Caffeine<Object, Object>> combinations = new LinkedHashSet<>(combinationCount);
    for (Caffeine<Object, Object> builder : builders) {
      for (Class<RemovalListener<?, ?>> removalListenerClass : cacheSpec.removalListener()) {
        Caffeine<Object, Object> copy = builder.copy();
        copy.removalListener(removalListenerClass.newInstance());
        combinations.add(copy);
      }
    }
    return Collections.unmodifiableSet(combinations);
  }

  /** Generates a new set of builders with the initial capacity combinations. */
  static Set<Caffeine<Object, Object>> makeInitialCapacities(
      CacheSpec cacheSpec, Set<Caffeine<Object, Object>> builders) {
    if (cacheSpec.initialCapacity().length == 0) {
      return builders;
    }
    int combinationCount = cacheSpec.initialCapacity().length * builders.size();
    Set<Caffeine<Object, Object>> combinations = new LinkedHashSet<>(combinationCount);
    for (Caffeine<Object, Object> builder : builders) {
      for (int initialCapacity : cacheSpec.initialCapacity()) {
        Caffeine<Object, Object> copy = builder.copy();
        if (initialCapacity != UNSET_INT) {
          copy.initialCapacity(initialCapacity);
        }
        combinations.add(copy);
      }
    }
    return Collections.unmodifiableSet(combinations);
  }

  /** Constructs caches with the populated combinations. */
  static Set<Cache<Integer, Integer>> makePopulations(
      CacheSpec cacheSpec, Set<Caffeine<Object, Object>> builders) {
    assertThat(cacheSpec.population().length, is(greaterThan(0)));

    int combinationCount = cacheSpec.population().length * builders.size();
    Set<Cache<Integer, Integer>> combinations = new LinkedHashSet<>(combinationCount);
    for (Caffeine<Object, Object> builder : builders) {
      for (Population population : cacheSpec.population()) {
        Cache<Integer, Integer> cache = builder.copy().build();
        population.populate(cache, POPULATED_SIZE);
        combinations.add(cache);
      }
    }
    return Collections.unmodifiableSet(combinations);
  }

  /** Returns the test case scenarios from the given caches. */
  static Iterator<Object[]> toTestCases(Set<Cache<Integer, Integer>> caches) {
    List<Object[]> result = new ArrayList<>(caches.size());
    for (Cache<Integer, Integer> cache : caches) {
      result.add(new Object[] { cache });
    }
    return result.iterator();
  }
}
