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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.DataProvider;

import com.github.benmanes.caffeine.cache.CacheSpec.Population;

/**
 * A data provider that generates caches based on the {@link CacheSpec} configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheProvider {
  public static final int POPULATED_SIZE = 100;

  @DataProvider(name = "caches")
  public static Iterator<Object[]> providesCaches(Method testMethod) {
    CacheSpec cacheSpec = testMethod.getAnnotation(CacheSpec.class);
    requireNonNull(cacheSpec, "@CacheSpec not found");

    Set<Caffeine<Object, Object>> builders = start();
    Set<Cache<Integer, Integer>> caches = makePopulations(cacheSpec, builders);

    return toTestCases(caches);
  }

  static Set<Caffeine<Object, Object>> start() {
    Set<Caffeine<Object, Object>> builders = new LinkedHashSet<>();
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    builders.add(builder);
    return builders;
  }

  static Iterator<Object[]> toTestCases(Set<Cache<Integer, Integer>> caches) {
    List<Object[]> result = new ArrayList<>();
    for (Cache<Integer, Integer> cache : caches) {
      result.add(new Object[] { cache });
    }
    return result.iterator();
  }

  static Set<Cache<Integer, Integer>> makePopulations(
      CacheSpec cacheSpec, Set<Caffeine<Object, Object>> builders) {
    assertThat(cacheSpec.population().length, is(greaterThan(0)));

    int initialCapacity = cacheSpec.population().length * builders.size();
    Set<Cache<Integer, Integer>> populated = new LinkedHashSet<>(initialCapacity);
    for (Caffeine<Object, Object> builder : builders) {
      for (Population population : cacheSpec.population()) {
        Cache<Integer, Integer> cache = builder.copy().build();
        population.populate(cache, POPULATED_SIZE);
        populated.add(cache);
      }
    }
    return populated;
  }
}
