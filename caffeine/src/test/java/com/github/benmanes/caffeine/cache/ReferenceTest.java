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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.google.common.base.Preconditions.checkState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.Iterators;
import com.google.common.testing.GcFinalization;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class ReferenceTest {

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      population = Population.FULL, writer = Writer.DISABLED)
  public void cleanup_keys(Cache<Integer, Integer> cache, CacheContext context) {
    runCleanupTest(cache, context);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      population = Population.FULL, writer = Writer.DISABLED)
  public void cleanup_values(Cache<Integer, Integer> cache, CacheContext context) {
    runCleanupTest(cache, context);
  }

  // FIXME(ben): We can't test soft values since they are not predictably GC'd
  static void runCleanupTest(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(Iterators.size(cache.asMap().keySet().iterator()), is(1));
    for (int i = 0; i < 1000; i++) {
      // Trigger enough reads to perform a cleanup
      checkState(cache.getIfPresent(key) == value);
    }
    assertThat(cache.estimatedSize(), lessThan(Population.FULL.size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.EMPTY, writer = Writer.DISABLED)
  public void evict_weakKeys(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(Integer.MIN_VALUE + ThreadLocalRandom.current().nextInt(), 0);
    cleanUpUntilEmpty(cache, context);
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = ReferenceType.WEAK, population = Population.FULL, writer = Writer.DISABLED)
  public void evict_weakValues(Cache<Integer, Integer> cache, CacheContext context) {
    context.original().clear();
    cleanUpUntilEmpty(cache, context);
    assertThat(cache, hasRemovalNotifications(
        context, context.initialSize(), RemovalCause.COLLECTED));
  }

  @Test(enabled = false, dataProvider = "caches")
  @CacheSpec(values = ReferenceType.SOFT, population = Population.FULL, writer = Writer.DISABLED)
  public void evict_softValues(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    awaitSoftRefGc();
    cleanUpUntilEmpty(cache, context);
    assertThat(cache, hasRemovalNotifications(
        context, context.initialSize(), RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.SINGLETON, writer = Writer.DISABLED)
  public void iterator_weakKeys(Cache<Integer, Integer> cache, CacheContext context) {
    WeakReference<Integer> weakKey = new WeakReference<>(context.firstKey());
    context.clear();
    GcFinalization.awaitClear(weakKey);

    for (Integer key : cache.asMap().keySet()) {
      assertThat(key, is(not(nullValue())));
    }
    for (Entry<Integer, Integer> entry : cache.asMap().entrySet()) {
      assertThat(entry.getKey(), is(not(nullValue())));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = ReferenceType.WEAK,
      population = Population.SINGLETON, writer = Writer.DISABLED)
  public void iterator_weakValues(Cache<Integer, Integer> cache, CacheContext context) {
    WeakReference<Integer> weakValue = new WeakReference<>(
        context.original().get(context.firstKey()));
    context.clear();
    GcFinalization.awaitClear(weakValue);

    for (Integer value : cache.asMap().values()) {
      assertThat(value, is(not(nullValue())));
    }
    for (Entry<Integer, Integer> entry : cache.asMap().entrySet()) {
      assertThat(entry.getValue(), is(not(nullValue())));
    }
  }

  @Test(enabled = false, dataProvider = "caches")
  @CacheSpec(values = ReferenceType.SOFT,
      population = Population.SINGLETON, writer = Writer.DISABLED)
  public void iterator_softValues(Cache<Integer, Integer> cache, CacheContext context) {
    awaitSoftRefGc();

    for (Integer value : cache.asMap().values()) {
      assertThat(value, is(not(nullValue())));
    }
    for (Entry<Integer, Integer> entry : cache.asMap().entrySet()) {
      assertThat(entry.getValue(), is(not(nullValue())));
    }
  }

  static void cleanUpUntilEmpty(Cache<Integer, Integer> cache, CacheContext context) {
    // As clean-up is amortized, pretend that the increment count may be as low as per entry
    int i = 0;
    do {
      GcFinalization.awaitFullGc();
      cache.cleanUp();
      if (cache.asMap().isEmpty()) {
        return; // passed
      }
    } while (i++ < context.population().size());
    assertThat(cache.estimatedSize(), is(0L));
  }

  /**
   * Tries to coerce a major GC cycle that evicts soft references, assuming that they are held
   * globally in LRU order.
   */
  static void awaitSoftRefGc() {
    byte[] garbage = new byte[1024];
    SoftReference<Object> flag = new SoftReference<>(new Object());
    List<Object> softRefs = new ArrayList<>();
    while (flag.get() != null) {
      int free = Math.abs((int) Runtime.getRuntime().freeMemory());
      int nextLength = Math.max(garbage.length, garbage.length << 2);
      garbage = new byte[Math.min(free >> 2, nextLength)];
      softRefs.add(new SoftReference<>(garbage));
    }
    softRefs.clear();
  }
}
