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
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.testng.annotations.DataProvider;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;

/**
 * A data provider that generates caches based on the {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider {

  @DataProvider(name = "caches")
  public static Iterator<Object[]> providesCaches(Method testMethod) throws Exception {
    CacheSpec cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    requireNonNull(cacheSpec, "@CacheSpec not found");

    Map<CacheContext, Caffeine<Object, Object>> testCaseBuilders =
        Collections.singletonMap(new CacheContext(), Caffeine.newBuilder());
    testCaseBuilders = makeInitialCapacities(cacheSpec, testCaseBuilders);
    testCaseBuilders = makeKeyReferences(cacheSpec, testCaseBuilders);
    testCaseBuilders = makeValueReferences(cacheSpec, testCaseBuilders);
    testCaseBuilders = makeRemovalListeners(cacheSpec, testCaseBuilders);
    testCaseBuilders = makeExecutors(cacheSpec, testCaseBuilders);

    Map<CacheContext, Cache<Integer, Integer>> testCases =
        makePopulations(cacheSpec, testCaseBuilders);
    return toTestCasesData(testMethod, testCases);
  }

  /** Generates a new set of builders with the maximum size combinations. */
  static Map<CacheContext, Caffeine<Object, Object>> makeMaximumSizes(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) throws Exception {
    assertThat(cacheSpec.maximumSize().length, is(greaterThan(0)));

    // TODO(ben): Support eviction
    assertThat(cacheSpec.maximumSize().length, is(1));
    assertThat(cacheSpec.maximumSize()[0], is(CacheSpec.UNBOUNDED));
    return builders;
  }

  /** Generates a new set of builders with the key reference combinations. */
  static Map<CacheContext, Caffeine<Object, Object>> makeKeyReferences(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) throws Exception {
    assertThat(cacheSpec.keys().length, is(greaterThan(0)));

    // TODO(ben): Support soft keys
    assertThat(cacheSpec.keys(), is(arrayContaining(ReferenceType.STRONG)));
    return builders;
  }

  /** Generates a new set of builders with the value reference combinations. */
  static Map<CacheContext, Caffeine<Object, Object>> makeValueReferences(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) throws Exception {
    assertThat(cacheSpec.values().length, is(greaterThan(0)));

    // TODO(ben): Support soft and weak values
    assertThat(cacheSpec.values(), is(arrayContaining(ReferenceType.STRONG)));
    return builders;
  }

  /** Generates a new set of builders with the executor combinations. */
  static Map<CacheContext, Caffeine<Object, Object>> makeExecutors(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) throws Exception {
    boolean isDefault = Arrays.equals(
        cacheSpec.executor(), new CacheExecutor[] { CacheExecutor.DEFAULT });
    if (isDefault) {
      return builders;
    }

    int combinationCount = cacheSpec.executor().length * builders.size();
    Map<CacheContext, Caffeine<Object, Object>> combinations =
        new LinkedHashMap<>(combinationCount);
    for (Entry<CacheContext, Caffeine<Object, Object>> entry : builders.entrySet()) {
      for (CacheExecutor executor : cacheSpec.executor()) {
        CacheContext context = entry.getKey().copy();
        Caffeine<Object, Object> builder = entry.getValue().copy();
        if (executor != CacheExecutor.DEFAULT) {
          builder.executor(executor.get());
        }
        combinations.put(context, builder);
      }
    }
    return Collections.unmodifiableMap(combinations);
  }

  /** Generates a new set of builders with the removal listener combinations. */
  static Map<CacheContext, Caffeine<Object, Object>> makeRemovalListeners(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) throws Exception {
    int combinationCount = cacheSpec.removalListener().length * builders.size();
    Map<CacheContext, Caffeine<Object, Object>> combinations =
        new LinkedHashMap<>(combinationCount);
    for (Entry<CacheContext, Caffeine<Object, Object>> entry : builders.entrySet()) {
      for (Listener removalListenerType : cacheSpec.removalListener()) {
        CacheContext context = entry.getKey().copy();
        Caffeine<Object, Object> builder = entry.getValue().copy();
        if (removalListenerType != Listener.DEFAULT) {
          RemovalListener<Object, Object> removalListener = removalListenerType.get();
          context.removalListener = removalListener;
          builder.removalListener(removalListener);
        }
        context.removalListenerType = removalListenerType;
        combinations.put(context, builder);
      }
    }
    return Collections.unmodifiableMap(combinations);
  }

  /** Generates a new set of builders with the initial capacity combinations. */
  static Map<CacheContext, Caffeine<Object, Object>> makeInitialCapacities(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) {
    boolean isDefault = Arrays.equals(
        cacheSpec.initialCapacity(), new int[] { CacheSpec.DEFAULT_INITIAL_CAPACITY });
    if (isDefault) {
      return builders;
    }

    int combinationCount = cacheSpec.initialCapacity().length * builders.size();
    Map<CacheContext, Caffeine<Object, Object>> combinations =
        new LinkedHashMap<>(combinationCount);
    for (Entry<CacheContext, Caffeine<Object, Object>> entry : builders.entrySet()) {
      for (int initialCapacity : cacheSpec.initialCapacity()) {
        CacheContext context = entry.getKey().copy();
        Caffeine<Object, Object> copy = entry.getValue().copy();
        if (initialCapacity != CacheSpec.DEFAULT_INITIAL_CAPACITY) {
          copy.initialCapacity(initialCapacity);
        }
        combinations.put(context, copy);
      }
    }
    return Collections.unmodifiableMap(combinations);
  }

  /** Constructs caches with the populated combinations. */
  static Map<CacheContext, Cache<Integer, Integer>> makePopulations(
      CacheSpec cacheSpec, Map<CacheContext, Caffeine<Object, Object>> builders) {
    assertThat(cacheSpec.population().length, is(greaterThan(0)));

    int combinationCount = cacheSpec.population().length * builders.size();
    Map<CacheContext, Cache<Integer, Integer>> combinations =
        new LinkedHashMap<>(combinationCount);
    for (Entry<CacheContext, Caffeine<Object, Object>> entry : builders.entrySet()) {
      for (Population population : cacheSpec.population()) {
        CacheContext context = entry.getKey().copy();
        Cache<Integer, Integer> cache = entry.getValue().copy().build();
        population.populate(context, cache);
        combinations.put(context, cache);
      }
    }
    return Collections.unmodifiableMap(combinations);
  }

  /** Returns the test case scenarios from the given caches. */
  static Iterator<Object[]> toTestCasesData(Method testMethod,
      Map<CacheContext, Cache<Integer, Integer>> builders) {
    Class<?>[] parameterClasses = testMethod.getParameterTypes();
    List<Object[]> result = new ArrayList<>(builders.size());
    for (Entry<CacheContext, Cache<Integer, Integer>> entry : builders.entrySet()) {
      Object[] params = new Object[parameterClasses.length];
      for (int i = 0; i < params.length; i++) {
        if (parameterClasses[i].isAssignableFrom(entry.getKey().getClass())) {
          params[i] = entry.getKey();
        } else if (parameterClasses[i].isAssignableFrom(entry.getValue().getClass())) {
          params[i] = entry.getValue();
        } else {
          throw new AssertionError("Unknown parameter type: " + parameterClasses[i]);
        }
      }
      result.add(params);
    }
    return result.iterator();
  }
}
