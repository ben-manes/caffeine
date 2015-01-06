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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Random;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.GarbageCollector;
import com.google.common.testing.GcFinalization;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class ReferenceTest {
  // These are slow tests due to requiring a garbage collection cycle

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.EMPTY)
  public void evict_weakKeys(Cache<Integer, Integer> cache, CacheContext context) {
    context.original().clear();
    cache.put(new Random().nextInt(), 0);
    GcFinalization.awaitFullGc();
    cleanUp(cache, context, 0);
    assertThat(cache.estimatedSize(), is(0L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = ReferenceType.WEAK, population = Population.FULL)
  public void evict_weakValues(Cache<Integer, Integer> cache, CacheContext context) {
    context.original().clear();
    GcFinalization.awaitFullGc();
    cleanUp(cache, context, 0);
    assertThat(cache.estimatedSize(), is(0L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = ReferenceType.SOFT, population = Population.FULL)
  public void evict_softValues(Cache<Integer, Integer> cache, CacheContext context) {
    context.original().clear();
    GarbageCollector.awaitSoftRefGc();
    cleanUp(cache, context, 0);
    assertThat(cache.estimatedSize(), is(0L));
  }

  static void cleanUp(Cache<Integer, Integer> cache, CacheContext context, long finalSize) {
    // As clean-up is amortized, pretend that the increment count may be as low as per entry
    for (int i = 0; i < context.population().size(); i++) {
      cache.cleanUp();
      if (cache.estimatedSize() == finalSize) {
        return; // passed
      }
    }
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));
  }
}
