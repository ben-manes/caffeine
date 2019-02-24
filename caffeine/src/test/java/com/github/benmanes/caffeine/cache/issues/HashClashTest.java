/*
 * Copyright 2016 Branimir Lambov. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.issues;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import java.util.Set;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;

/**
 * CASSANDRA-11452: Hash collisions cause the cache to not admit new entries.
 *
 * @author Branimir Lambov (github.com/blambov)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class HashClashTest {
  private static final int STEP = 5;
  private static final Long LONG_1 = 1L;
  private static final long ITERS = 200_000;
  private static final boolean debug = false;

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.ONE_FIFTY, stats = Stats.ENABLED)
  public void testCache(Cache<Long, Long> cache, CacheContext context) {
    for (long j = 0; j < 300; ++j) {
      cache.get(1L, Function.identity());
      cache.get(j, Function.identity());
    }
    printStats(cache);
    printKeys(cache);

    // add a hashcode clash for 1
    Long CLASH = (ITERS << 32) ^ ITERS ^ 1;
    assertThat(CLASH.hashCode(), is(LONG_1.hashCode()));
    cache.get(CLASH, Function.identity());
    printKeys(cache);

    // repeat some entries to let CLASH flow to the probation head
    for (long j = 0; j < 300; ++j) {
      cache.get(1L, Function.identity());
      cache.get(j, Function.identity());
    }
    printKeys(cache);

    // Now run a repeating sequence which has a longer length than window space size.
    for (long i = 0; i < ITERS; i += STEP) {
      cache.get(1L, Function.identity());
      for (long j = 0; j < STEP; ++j) {
        cache.get(-j, Function.identity());
      }
    }
    printKeys(cache);
    printStats(cache);

    assertThat(cache.stats().hitRate(), is(greaterThan(0.99d)));
  }

  static void printStats(Cache<Long, Long> cache) {
    if (debug) {
      System.out.printf("size %,d requests %,d hit ratio %f%n",
          cache.estimatedSize(), cache.stats().requestCount(), cache.stats().hitRate());
    }
  }

  static void printKeys(Cache<Long, Long> cache) {
    if (debug) {
      Set<Long> keys = cache.policy().eviction()
          .map(policy -> policy.hottest(Integer.MAX_VALUE).keySet())
          .orElseGet(cache.asMap()::keySet);
      System.out.println(keys);
    }
  }
}
