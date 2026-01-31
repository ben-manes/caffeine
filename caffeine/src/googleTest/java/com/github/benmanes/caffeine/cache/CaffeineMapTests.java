/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import java.util.Comparator;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.Stats;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Guava testlib map tests for the {@link Cache#asMap()} and {@link AsyncCache#asMap()} views.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"JUnitClassModifiers", "JUnitMethodDeclaration",
    "PMD.JUnit4SuitesShouldUseSuiteAnnotation"})
public final class CaffeineMapTests extends TestCase {

  public static Test suite() {
    var suite = new TestSuite();
    CacheGenerator.forCacheSpec(cacheSpec()).generate().parallel()
        .flatMap(MapTestFactory::makeTests)
        .sorted(Comparator.comparing(TestSuite::getName))
        .forEachOrdered(suite::addTest);
    return suite;
  }

  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      weigher = CacheWeigher.DISABLED, removalListener = Listener.DISABLED,
      evictionListener = Listener.DISABLED, stats = Stats.ENABLED)
  private static CacheSpec cacheSpec() {
    try {
      return CaffeineMapTests.class.getDeclaredMethod("cacheSpec")
          .getAnnotation(CacheSpec.class);
    } catch (NoSuchMethodException | SecurityException e) {
      throw new AssertionError(e);
    }
  }
}
