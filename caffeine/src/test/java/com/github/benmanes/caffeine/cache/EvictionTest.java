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

import static com.github.benmanes.caffeine.cache.RemovalCause.SIZE;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.RejectingRemovalListener;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;

/**
 * The test cases for caches with a page replacement algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class EvictionTest {

  /* --------------- RemovalListener --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = Maximum.FULL,
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN}, removalListener = Listener.REJECTING)
  public void removalListener_fails(Cache<Int, Int> cache, CacheContext context) {
    var removalListener = (RejectingRemovalListener<Int, Int>) context.removalListener();
    // Guava-style caches reject before the max size is reached & are unpredictable
    removalListener.rejected = 0;
    long size = cache.estimatedSize();
    for (Int key : context.absentKeys()) {
      cache.put(key, key.negate());
      if (cache.estimatedSize() != ++size) {
        break;
      }
    }
    assertThat(removalListener.rejected).isEqualTo(1);
  }

  /* --------------- Evict (size/weight) --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      maximumSize = { Maximum.ZERO, Maximum.ONE, Maximum.FULL },
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN})
  public void evict(Cache<Int, Int> cache, CacheContext context, Eviction<Int, Int> eviction) {
    cache.putAll(context.absent());
    if (eviction.isWeighted()) {
      assertThat(cache).hasWeightedSize(context.maximumWeight());
    } else {
      assertThat(cache).hasSize(context.maximumSize());
    }
    int count = context.absentKeys().size();
    assertThat(context).stats().evictions(count);
    assertThat(context).notifications().withCause(SIZE).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.COLLECTION, keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    // Enforce full initialization of internal structures
    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), List.of());
    }
    cache.invalidateAll();

    var value1 = Int.listOf(8, 9, 10);
    var value2 = Int.listOf(3, 4, 5, 6, 7);
    var value3 = Int.listOf(1, 2);
    var value4 = Int.listOf(11);
    var value5 = Int.listOf(12, 13, 14, 15, 16, 17, 18, 19, 20);

    // Never evicted
    cache.put(Int.valueOf(0), List.of());

    cache.put(Int.valueOf(1), value1);
    cache.put(Int.valueOf(2), value2);
    cache.put(Int.valueOf(3), value3);
    assertThat(cache).hasSize(4);
    assertThat(cache).hasWeightedSize(10);

    // [0 | 1, 2, 3] remains (4 exceeds window and has the same usage history, so evicted)
    cache.put(Int.valueOf(4), value4);
    assertThat(cache).hasSize(4);
    assertThat(cache).hasWeightedSize(10);
    assertThat(cache).doesNotContainKey(Int.valueOf(4));

    // [0 | 1, 2, 3] -> [0, 4 | 2, 3]
    cache.put(Int.valueOf(4), value4);
    assertThat(cache).hasSize(4);
    assertThat(cache).hasWeightedSize(8);
    assertThat(cache).doesNotContainKey(Int.valueOf(1));

    // [0, 4 | 2, 3] remains (5 exceeds window and has the same usage history, so evicted)
    cache.put(Int.valueOf(5), value5);
    assertThat(cache).hasSize(4);
    assertThat(cache).hasWeightedSize(8);
    assertThat(context).stats().evictions(3).evictionWeight(13);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.COLLECTION, keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_weighted_reorder(Cache<Int, List<Int>> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    eviction.setMaximum(3);
    for (int i = 1; i <= 3; i++) {
      cache.put(Int.valueOf(i), Int.listOf(1));
    }
    cache.asMap().computeIfPresent(Int.valueOf(1), (k, v) -> Int.listOf(1, 2));
    assertThat(cache).containsEntry(Int.valueOf(1), Int.listOf(1, 2));
    assertThat(cache).hasWeightedSize(3);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.VALUE)
  public void evict_weighted_entryTooBig(Cache<Int, Int> cache, CacheContext context) {
    cache.put(Int.valueOf(9), Int.valueOf(9));
    cache.put(Int.valueOf(1), Int.valueOf(1));
    assertThat(cache).hasSize(2);
    cache.policy().eviction().ifPresent(eviction -> {
      assertThat(cache).hasWeightedSize(10);
    });

    cache.put(Int.valueOf(20), Int.valueOf(20));
    assertThat(cache).hasSize(2);
    cache.policy().eviction().ifPresent(eviction -> {
      assertThat(cache).hasWeightedSize(10);
    });
    assertThat(context).removalNotifications().withCause(SIZE)
        .contains(Int.valueOf(20), Int.valueOf(20)).exclusively();
    if (context.isCaffeine()) {
      assertThat(context).stats().evictionWeight(20);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.VALUE, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG,
      removalListener = Listener.CONSUMING)
  @SuppressWarnings("FutureReturnValueIgnored")
  public void evict_weighted_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return Int.valueOf(6);
    }, executor);
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(Int.valueOf(5), Int.futureOf(5));
    cache.put(Int.valueOf(4), Int.futureOf(4));
    cache.put(Int.valueOf(6), valueFuture);
    assertThat(cache).hasWeightedSize(9);
    assertThat(cache).hasSize(3);

    ready.set(true);
    await().untilTrue(done);
    await().untilAsserted(() -> assertThat(cache).hasSize(2));
    await().untilAsserted(() -> assertThat(cache).hasWeightedSize(10));

    assertThat(context).stats().evictionWeight(5);
    assertThat(context).notifications().withCause(SIZE).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.ZERO,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  @SuppressWarnings("FutureReturnValueIgnored")
  public void evict_zero_async(AsyncCache<Int, List<Int>> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return Int.listOf(1, 2, 3, 4, 5);
    }, executor);
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(cache).hasWeightedSize(0);
    assertThat(cache).hasSize(1);

    ready.set(true);
    await().untilTrue(done);
    await().untilAsserted(() -> assertThat(cache).isEmpty());
    assertThat(context).notifications().withCause(SIZE).hasSize(1).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, maximumSize = Maximum.FULL, weigher = CacheWeigher.DEFAULT,
      evictionListener = Listener.MOCKITO, removalListener = Listener.REJECTING)
  public void evict_evictionListenerFails(Cache<Int, Int> cache, CacheContext context) {
    doThrow(RuntimeException.class)
        .when(context.evictionListener()).onRemoval(any(), any(), any());
    cache.policy().eviction().ifPresent(policy -> policy.setMaximum(0));
    verify(context.evictionListener(), times((int) context.initialSize()))
        .onRemoval(any(), any(), any());
  }

  /* --------------- Weighted --------------- */

  @CacheSpec(maximumSize = Maximum.FULL,
      weigher = CacheWeigher.NEGATIVE, population = Population.EMPTY)
  @Test(dataProvider = "caches",
      expectedExceptions = { IllegalArgumentException.class, IllegalStateException.class })
  public void put_negativeWeight(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @CacheSpec(maximumSize = Maximum.FULL,
      weigher = CacheWeigher.ZERO, population = Population.EMPTY)
  @Test(dataProvider = "caches")
  public void put_zeroWeight(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put(Cache<String, List<Int>> cache, CacheContext context) {
    cache.put("a", Int.listOf(1, 2, 3));
    assertThat(cache).hasWeightedSize(3);
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_sameWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    cache.put("a", Int.listOf(-1, -2, -3));
    assertThat(cache).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_changeWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    cache.put("a", Int.listOf(-1, -2, -3, -4));
    assertThat(cache).hasWeightedSize(5);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  @SuppressWarnings("FutureReturnValueIgnored")
  public void put_asyncWeight(AsyncCache<Int, List<Int>> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return Int.listOf(1, 2, 3, 4, 5);
    }, executor);
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(cache).hasWeightedSize(0);
    assertThat(cache).hasSize(1);

    ready.set(true);
    await().untilTrue(done);
    await().untilAsserted(() -> assertThat(cache).hasSize(1));
    await().untilAsserted(() -> assertThat(cache).hasWeightedSize(5));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replace_sameWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    cache.asMap().replace("a", Int.listOf(-1, -2, -3));
    assertThat(cache).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replace_changeWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    cache.asMap().replace("a", Int.listOf(-1, -2, -3, -4));
    assertThat(cache).hasWeightedSize(5);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_sameWeight(
      Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    assertThat(cache.asMap().replace("a", Int.listOf(1, 2, 3), Int.listOf(4, 5, 6))).isTrue();
    assertThat(cache).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_changeWeight(
      Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    cache.asMap().replace("a", Int.listOf(1, 2, 3), Int.listOf(-1, -2, -3, -4));
    assertThat(cache).hasWeightedSize(5);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_fails(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    assertThat(cache.asMap().replace("a", Int.listOf(1), Int.listOf(4, 5))).isFalse();
    assertThat(cache).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void remove(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    assertThat(cache.asMap().remove("a")).containsExactlyElementsIn(Int.listOf(1, 2, 3)).inOrder();
    assertThat(cache).hasWeightedSize(1);
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void removeConditionally(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    assertThat(cache.asMap().remove("a", Int.listOf(1, 2, 3))).isTrue();
    assertThat(cache).hasWeightedSize(1);
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void removeConditionally_fails(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    assertThat(cache.asMap().remove("a", Int.listOf(-1, -2, -3))).isFalse();
    assertThat(cache).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void invalidateAll(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1)));
    cache.invalidateAll();
    assertThat(cache).isEmpty();
  }

  /* --------------- Policy --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.UNREACHABLE)
  public void getIfPresentQuietly(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var expected = eviction.hottest(Integer.MAX_VALUE).keySet();
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey())).isNotNull();
    assertThat(cache.policy().getIfPresentQuietly(context.middleKey())).isNotNull();
    assertThat(cache.policy().getIfPresentQuietly(context.lastKey())).isNotNull();
    var actual = eviction.hottest(Integer.MAX_VALUE).keySet();
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  /* --------------- Policy: IsWeighted --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = Maximum.FULL, population = Population.EMPTY)
  public void isWeighted(CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.isWeighted()).isEqualTo(context.isWeighted());
  }

  /* --------------- Policy: WeightOf --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void weightOf(Cache<Int, Int> cache, CacheContext context, Eviction<Int, Int> eviction) {
    Int key = Int.valueOf(1);
    cache.put(key, Int.valueOf(1));
    assertThat(eviction.weightOf(key)).hasValue(1);

    cache.put(key, Int.valueOf(2));
    assertThat(eviction.weightOf(key)).hasValue(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  public void weightOf_absent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.weightOf(context.absentKey())).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.VALUE,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void weightOf_expired(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    cache.put(context.absentKey(), Int.valueOf(1));
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(eviction.weightOf(context.absentKey())).isEmpty();
  }

  /* --------------- Policy: WeightedSize --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DEFAULT)
  public void weightedSize_absent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.weightOf(context.firstKey())).isEmpty();
    assertThat(eviction.weightOf(context.absentKey())).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.TEN)
  public void weightedSize(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = 0;
    for (Int key : cache.asMap().keySet()) {
      weightedSize += eviction.weightOf(key).getAsInt();
    }
    assertThat(eviction.weightedSize()).hasValue(weightedSize);
    assertThat(weightedSize).isEqualTo(10 * cache.estimatedSize());
  }

  /* --------------- Policy: MaximumSize --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long newSize = context.maximumWeightOrSize() / 2;
    eviction.setMaximum(newSize);
    assertThat(eviction.getMaximum()).isEqualTo(newSize);
    if (context.initialSize() > newSize) {
      if (context.isZeroWeighted()) {
        assertThat(cache).hasSize(context.initialSize());
        assertThat(context).notifications().isEmpty();
      } else {
        assertThat(cache).hasSize(newSize);
        assertThat(context).notifications().withCause(SIZE).hasSize(newSize).exclusively();
      }
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = Maximum.FULL, weigher = { CacheWeigher.DEFAULT, CacheWeigher.TEN })
  public void maximumSize_decrease_min(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    eviction.setMaximum(0);
    assertThat(eviction.getMaximum()).isEqualTo(0);
    if (context.initialSize() > 0) {
      long expectedSize = context.isZeroWeighted() ? context.initialSize() : 0;
      assertThat(cache).hasSize(expectedSize);
    }
    assertThat(context).notifications().withCause(SIZE)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void maximumSize_decrease_negative(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    try {
      eviction.setMaximum(-1);
    } finally {
      assertThat(eviction.getMaximum()).isEqualTo(context.maximumWeightOrSize());
      assertThat(context).notifications().isEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void maximumSize_increase(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    eviction.setMaximum(2 * context.maximumWeightOrSize());
    assertThat(cache).hasSize(context.initialSize());
    assertThat(eviction.getMaximum()).isEqualTo(2 * context.maximumWeightOrSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = Maximum.FULL, removalListener = Listener.REJECTING)
  public void maximumSize_increase_max(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    eviction.setMaximum(Long.MAX_VALUE);
    assertThat(cache).hasSize(context.initialSize());
    assertThat(eviction.getMaximum()).isEqualTo(Long.MAX_VALUE - Integer.MAX_VALUE); // impl detail
  }

  /* --------------- Policy: Coldest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void coldest_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.coldest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void coldest_negative(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.coldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  public void coldest_zero(CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.coldest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldest_partial(CacheContext context, Eviction<Int, Int> eviction) {
    int count = context.original().size() / 2;
    assertThat(eviction.coldest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL,
      weigher = { CacheWeigher.DEFAULT, CacheWeigher.TEN },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void coldest_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var coldest = new LinkedHashSet<>(eviction.coldest(Integer.MAX_VALUE).keySet());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL)
  public void coldest_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var coldest = eviction.coldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest).containsExactlyEntriesIn(context.original());
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void coldestWeight_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.coldestWeighted(Long.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void coldestWeighted_negative(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.coldestWeighted(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  public void coldestWeighted_zero(CacheContext context, Eviction<Int, Int> eviction) {
    if (context.weigher() == CacheWeigher.ZERO) {
      assertThat(eviction.coldestWeighted(0)).isEqualTo(context.original());
    } else {
      assertThat(eviction.coldestWeighted(0)).isExhaustivelyEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestWeighted_partial(CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = context.original().entrySet().stream()
        .mapToLong(entry -> context.weigher().weigh(entry.getKey(), entry.getValue()))
        .limit(context.original().size() / 2)
        .sum();
    var coldest = eviction.coldestWeighted(weightedSize);
    var actualWeighedSize = coldest.entrySet().stream()
        .mapToLong(entry -> context.weigher().weigh(entry.getKey(), entry.getValue()))
        .sum();
    if (context.isWeighted()) {
      assertThat(coldest).hasSizeIn(Range.closed(0, context.original().size()));
    } else {
      assertThat(coldest).hasSize(context.original().size() / 2);
    }
    assertThat(actualWeighedSize).isIn(Range.closed(0L, weightedSize));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL,
      weigher = { CacheWeigher.DEFAULT, CacheWeigher.TEN },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void coldestWeighted_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var coldest = new LinkedHashSet<>(eviction.coldestWeighted(Long.MAX_VALUE).keySet());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL)
  public void coldestWeighted_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var coldest = eviction.coldestWeighted(Long.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest).containsExactlyEntriesIn(context.original());
  }

  /* --------------- Policy: Hottest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void hottest_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.hottest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void hottest_negative(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.hottest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  public void hottest_zero(CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.hottest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottest_partial(CacheContext context, Eviction<Int, Int> eviction) {
    int count = context.original().size() / 2;
    assertThat(eviction.hottest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hottest_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var hottest = eviction.hottest(Integer.MAX_VALUE).keySet();
    var coldest = new LinkedHashSet<>(ImmutableList.copyOf(hottest).reverse());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL)
  public void hottestWeighted_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var hottest = eviction.hottestWeighted(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest).containsExactlyEntriesIn(context.original());
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void hottestWeighted_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.hottestWeighted(Long.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void hottestWeighted_negative(CacheContext context, Eviction<Int, Int> eviction) {
    eviction.hottestWeighted(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = Maximum.FULL)
  public void hottestWeighted_zero(CacheContext context, Eviction<Int, Int> eviction) {
    if (context.weigher() == CacheWeigher.ZERO) {
      assertThat(eviction.hottestWeighted(0)).isEqualTo(context.original());
    } else {
      assertThat(eviction.hottestWeighted(0)).isExhaustivelyEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestWeighted_partial(CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = context.original().entrySet().stream()
        .mapToLong(entry -> context.weigher().weigh(entry.getKey(), entry.getValue()))
        .limit(context.original().size() / 2)
        .sum();
    var hottest = eviction.hottestWeighted(weightedSize);
    var actualWeighedSize = hottest.entrySet().stream()
        .mapToLong(entry -> context.weigher().weigh(entry.getKey(), entry.getValue()))
        .sum();
    if (context.isWeighted()) {
      assertThat(hottest).hasSizeIn(Range.closed(0, context.original().size()));
    } else {
      assertThat(hottest).hasSize(context.original().size() / 2);
    }
    assertThat(actualWeighedSize).isIn(Range.closed(0L, weightedSize));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hottestWeighted_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var hottest = eviction.hottestWeighted(Long.MAX_VALUE).keySet();
    var coldest = new LinkedHashSet<>(ImmutableList.copyOf(hottest).reverse());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL)
  public void hottest_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var hottest = eviction.hottest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest).containsExactlyEntriesIn(context.original());
  }
}
