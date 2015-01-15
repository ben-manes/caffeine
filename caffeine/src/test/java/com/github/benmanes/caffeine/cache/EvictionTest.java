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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasEvictionCount;
import static com.github.benmanes.caffeine.matchers.IsEmptyMap.emptyMap;
import static com.jayway.awaitility.Awaitility.await;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.simulator.generator.IntegerGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.ScrambledZipfianGenerator;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.RejectingRemovalListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * The test cases for caches with a page replacement algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class EvictionTest {
  // FIXME: Add async weighted size support

  /* ---------------- RemovalListener -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL,
      removalListener = Listener.REJECTING)
  public void removalListener_fails(Cache<Integer, Integer> cache, CacheContext context) {
    RejectingRemovalListener<Integer, Integer> removalListener = context.removalListener();
    // Guava-style caches reject before the max size is reached & are unpredictable
    removalListener.rejected = 0;
    long size = cache.estimatedSize();
    for (Integer key : context.absentKeys()) {
      cache.put(key, -key);
      if (cache.estimatedSize() != ++size) {
        break;
      }
    }
    assertThat(removalListener.rejected, is(1));
  }

  @Test
  public void removalNotification() {
    RemovalNotification<Integer, Integer> sizeOne =
        new RemovalNotification<>(1, -1, RemovalCause.SIZE);
    RemovalNotification<Integer, Integer> explicitOne =
        new RemovalNotification<>(1, -1, RemovalCause.EXPLICIT);
    RemovalNotification<Integer, Integer> sizeTwo =
        new RemovalNotification<>(2, -2, RemovalCause.SIZE);
    assertThat(sizeOne, is(equalTo(explicitOne)));
    assertThat(sizeTwo, is(not(equalTo(explicitOne))));

    try {
      sizeOne.setValue(-2);
      Assert.fail();
    } catch (UnsupportedOperationException e) {}
  }

  /* ---------------- Evict (size/weight) -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      maximumSize = { MaximumSize.ZERO, MaximumSize.ONE, MaximumSize.FULL })
  public void evict(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    cache.putAll(context.absent());
    if (eviction.isWeighted()) {
      assertThat(eviction.weightedSize().get(), is(context.maximumWeight()));
    } else {
      assertThat(cache.estimatedSize(), is(context.maximumSize()));
    }
    int count = context.absentKeys().size();
    assertThat(context, hasEvictionCount(count));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.SIZE));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = { MaximumSize.ZERO, MaximumSize.ONE, MaximumSize.FULL })
  public void evict_lru(Cache<Integer, Integer> cache, CacheContext context) {
    int[] evicted = new int[1];
    Map<Integer, Integer> lru = new LinkedHashMap<Integer, Integer>(1, 0.75f, true) {
      private static final long serialVersionUID = 1L;
      @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
        if (size() > context.maximumSize()) {
          evicted[0]++;
          return true;
        }
        return false;
      }
    };
    Map<Integer, Integer> all = new HashMap<>();
    IntegerGenerator generator = new ScrambledZipfianGenerator(10 * context.maximumSize());
    for (int i = 0; i < (10 * context.maximumSize()); i++) {
      Integer next = generator.nextInt();
      all.putIfAbsent(next, next);
      Integer key = all.getOrDefault(next, next);

      Integer lruValue = lru.putIfAbsent(key, key);
      Integer cacheValue = cache.asMap().putIfAbsent(key, key);
      assertThat(cacheValue, is(lruValue));
    }
    assertThat(cache.asMap(), is(equalTo(lru)));
    assertThat(context, hasEvictionCount(evicted[0]));
    assertThat(cache, hasRemovalNotifications(context, evicted[0], RemovalCause.SIZE));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.TEN,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_weighted(Cache<Integer, List<Integer>> cache, Eviction<?, ?> eviction) {
    // Never evicted
    cache.put(0, asList());

    cache.put(1, asList(1, 2));
    cache.put(2, asList(3, 4, 5, 6, 7));
    cache.put(3, asList(8, 9, 10));
    assertThat(cache.estimatedSize(), is(4L));
    assertThat(eviction.weightedSize().get(), is(10L));

    // evict (1)
    cache.put(4, asList(11));
    assertThat(cache.asMap().containsKey(1), is(false));
    assertThat(cache.estimatedSize(), is(4L));
    assertThat(eviction.weightedSize().get(), is(9L));

    // evict (2, 3)
    cache.put(5, asList(12, 13, 14, 15, 16, 17, 18, 19, 20));
    assertThat(cache.estimatedSize(), is(3L));
    assertThat(eviction.weightedSize().get(), is(10L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.TEN,
      weigher = CacheWeigher.VALUE, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_weighted_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<Integer> valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return 6;
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(5, CompletableFuture.completedFuture(5));
    cache.put(4, CompletableFuture.completedFuture(4));
    cache.put(6, valueFuture);
    assertThat(eviction.weightedSize().get(), is(9L));
    assertThat(cache.synchronous().estimatedSize(), is(3L));

    ready.set(true);
    await().untilTrue(done);
    assertThat(eviction.weightedSize().get(), is(10L));
    assertThat(cache.synchronous().estimatedSize(), is(2L));
    assertThat(context, hasRemovalNotifications(context, 1, RemovalCause.SIZE));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.ZERO,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_zero_async(AsyncLoadingCache<Integer, List<Integer>> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<List<Integer>> valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return ImmutableList.of(1, 2, 3, 4, 5);
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(eviction.weightedSize().get(), is(0L));
    assertThat(cache.synchronous().estimatedSize(), is(1L));

    ready.set(true);
    await().untilTrue(done);
    assertThat(eviction.weightedSize().get(), is(0L));
    assertThat(cache.synchronous().estimatedSize(), is(0L));
  }

  /* ---------------- Weighted -------------- */

  @CacheSpec(maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.NEGATIVE, population = Population.EMPTY)
  @Test(dataProvider = "caches",
      expectedExceptions = { IllegalArgumentException.class, IllegalStateException.class })
  public void put_negativeWeight(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @CacheSpec(maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.ZERO, population = Population.EMPTY)
  @Test(dataProvider = "caches")
  public void put_zeroWeight(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.put("a", asList(1, 2, 3));
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(eviction.weightedSize().get(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_sameWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.put("a", asList(-1, -2, -3));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_changeWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.put("a", asList(-1, -2, -3, -4));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(5L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_asyncWeight(AsyncLoadingCache<Integer, List<Integer>> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<List<Integer>> valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return ImmutableList.of(1, 2, 3, 4, 5);
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(eviction.weightedSize().get(), is(0L));
    assertThat(cache.synchronous().estimatedSize(), is(1L));

    ready.set(true);
    await().untilTrue(done);
    assertThat(eviction.weightedSize().get(), is(5L));
    assertThat(cache.synchronous().estimatedSize(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replace_sameWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.asMap().replace("a", asList(-1, -2, -3));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replace_changeWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.asMap().replace("a", asList(-1, -2, -3, -4));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(5L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_sameWeight(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().replace("a", asList(1, 2, 3), asList(4, 5, 6)), is(true));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_changeWeight(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.asMap().replace("a", asList(1, 2, 3), asList(-1, -2, -3, -4));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(5L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_fails(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().replace("a", asList(1), asList(4, 5)), is(false));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void remove(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().remove("a"), is(asList(1, 2, 3)));
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(eviction.weightedSize().get(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void removeConditionally(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().remove("a", asList(1, 2, 3)), is(true));
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(eviction.weightedSize().get(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void removeConditionally_fails(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().remove("a", asList(-1, -2, -3)), is(false));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().get(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void invalidateAll(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.invalidateAll();
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(eviction.weightedSize().get(), is(0L));
  }

  /* ---------------- Policy: IsWeighted -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, population = Population.EMPTY)
  public void isWeighted(CacheContext context, Eviction<Integer, Integer> eviction) {
    assertThat(eviction.isWeighted(), is(context.isWeighted()));
  }

  /* ---------------- Policy: WeightedSize -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.TEN)
  public void weightedSize(Cache<Integer, Integer> cache, Eviction<Integer, Integer> eviction) {
    assertThat(eviction.weightedSize().get(), is(10 * cache.estimatedSize()));
  }

  /* ---------------- Policy: MaximumSize -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    long newSize = context.maximumWeightOrSize() / 2;
    eviction.setMaximumSize(newSize);
    assertThat(eviction.getMaximumSize(), is(newSize));
    if (context.initialSize() > newSize) {
      assertThat(cache.estimatedSize(), is(newSize));
      assertThat(cache, hasRemovalNotifications(context, newSize, RemovalCause.SIZE));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease_min(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximumSize(0);
    assertThat(eviction.getMaximumSize(), is(0L));
    if (context.initialSize() > 0) {
      assertThat(cache.estimatedSize(), is(0L));
    }
    assertThat(cache, hasRemovalNotifications(context, context.initialSize(), RemovalCause.SIZE));
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void maximumSize_decrease_negative(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    try {
      eviction.setMaximumSize(-1);
    } finally {
      assertThat(eviction.getMaximumSize(), is(context.maximumWeightOrSize()));
      assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.SIZE));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void maximumSize_increase(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximumSize(2 * context.maximumWeightOrSize());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(eviction.getMaximumSize(), is(2 * context.maximumWeightOrSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, removalListener = Listener.REJECTING)
  public void maximumSize_increase_max(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximumSize(Long.MAX_VALUE);
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(eviction.getMaximumSize(), is(Long.MAX_VALUE - Integer.MAX_VALUE)); // impl detail
  }

  /* ---------------- Policy: Coldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void coldest_unmodifiable(Eviction<Integer, Integer> eviction) {
    eviction.coldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void coldest_negative(Eviction<Integer, Integer> eviction) {
    eviction.coldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void coldest_zero(Eviction<Integer, Integer> eviction) {
    assertThat(eviction.coldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void coldest_partial(CacheContext context, Eviction<Integer, Integer> eviction) {
    int count = (int) context.initialSize() / 2;
    assertThat(eviction.coldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void coldest_order(CacheContext context, Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> coldest = eviction.coldest(Integer.MAX_VALUE);
    assertThat(Iterables.elementsEqual(coldest.keySet(), context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void coldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> coldest = eviction.coldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest, is(equalTo(context.original())));
  }

  /* ---------------- Policy: Hottest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void hottest_unmodifiable(Eviction<Integer, Integer> eviction) {
    eviction.hottest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void hottest_negative(Eviction<Integer, Integer> eviction) {
    eviction.hottest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void hottest_zero(Eviction<Integer, Integer> eviction) {
    assertThat(eviction.hottest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void hottest_partial(CacheContext context, Eviction<Integer, Integer> eviction) {
    int count = (int) context.initialSize() / 2;
    assertThat(eviction.hottest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hottest_order(CacheContext context, Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> hottest = eviction.hottest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(hottest.keySet()).reverse());
    assertThat(Iterables.elementsEqual(keys, context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void hottest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> hottest = eviction.hottest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest, is(equalTo(context.original())));
  }
}
