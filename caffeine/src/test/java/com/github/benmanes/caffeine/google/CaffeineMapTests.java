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
package com.github.benmanes.caffeine.google;

import java.util.Comparator;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheGenerator;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Guava testlib map tests for the {@link Cache#asMap()} and {@link AsyncCache#asMap()} views.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineMapTests extends TestCase {

  public static Test suite() {
    var suite = new TestSuite();
    new CacheGenerator(cacheSpec()).generate().parallel()
        .flatMap(MapTestFactory::makeTests)
        .sorted(Comparator.comparing(TestSuite::getName))
        .forEach(suite::addTest);
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
