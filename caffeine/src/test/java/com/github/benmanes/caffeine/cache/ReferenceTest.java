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

import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.google.common.base.Preconditions.checkState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasValue;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.DeleteException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.testing.GcFinalization;

/**
 * The test cases for caches that support a reference eviction policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class ReferenceTest {
  // These tests focus on weak reference collection, since that can be reliably triggered through
  // a garbage collection. Soft references cannot be deterministically evicted, so we must infer
  // correct usage from the weak tests. A possible workaround is to mimic collection by null'ing
  // out the entry's reference and using a custom ReferenceQueue that we can append to.

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG, population = Population.FULL)
  public void cleanup_keys(Cache<Integer, Integer> cache, CacheContext context) {
    runCleanupTest(cache, context);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK, population = Population.FULL)
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

    long count = context.initialSize() - cache.estimatedSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.EMPTY)
  public void evict_weakKeys(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(Integer.MIN_VALUE + ThreadLocalRandom.current().nextInt(), 0);
    cleanUpUntilEmpty(cache, context);
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = ReferenceType.WEAK, population = Population.FULL)
  public void evict_weakValues(Cache<Integer, Integer> cache, CacheContext context) {
    context.original().clear();
    cleanUpUntilEmpty(cache, context);

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.SINGLETON)
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
  @CacheSpec(values = ReferenceType.WEAK, population = Population.SINGLETON)
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

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.FULL)
  public void identity_keys(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = new Integer(context.firstKey());
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = {ReferenceType.WEAK, ReferenceType.SOFT}, population = Population.FULL)
  public void identity_values(Cache<Integer, Integer> cache, CacheContext context) {
    Integer value = new Integer(context.original().get(context.firstKey()));
    assertThat(cache.asMap(), hasValue(value));
  }

  /* ---------------- Cache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = MaximumSize.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = MaximumSize.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(key, k -> context.absentValue()), is(context.absentValue()));

    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = MaximumSize.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void get_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.get(key, Function.identity());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().containsKey(key), is(true));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = MaximumSize.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAllPresent(keys), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = MaximumSize.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, context.absentValue());

    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = MaximumSize.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void put_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.put(key, context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().containsKey(key), is(true));
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = MaximumSize.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putAll(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> entries = ImmutableMap.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue());
    context.clear();
    GcFinalization.awaitFullGc();
    cache.putAll(entries);

    long count = context.initialSize() - cache.estimatedSize() + 3;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = MaximumSize.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void putAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.putAll(ImmutableMap.of(key, context.absentValue()));
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().containsKey(key), is(true));
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = MaximumSize.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidate(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.invalidate(key);

    long count = context.initialSize() - cache.estimatedSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

}
