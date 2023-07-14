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
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.util.function.Function.identity;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.RejectingRemovalListener;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;

/**
 * The test cases for caches with a page replacement algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class EvictionTest {

  /* --------------- RemovalListener --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = Maximum.FULL,
      weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN}, removalListener = Listener.REJECTING)
  public void removalListener_fails(Cache<Int, Int> cache, CacheContext context) {
    var removalListener = (RejectingRemovalListener<Int, Int>) context.removalListener();
    // Guava-style caches reject before the max size is reached & are unpredictable
    removalListener.rejected = 0;
    long size = cache.estimatedSize();
    for (Int key : context.absentKeys()) {
      cache.put(key, key);
      if (cache.estimatedSize() != ++size) {
        break;
      }
    }
    assertThat(removalListener.rejected).isEqualTo(1);
  }

  /* --------------- Evict (size/weight) --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = {Maximum.ZERO, Maximum.ONE, Maximum.FULL},
      weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN})
  public void evict(Cache<Int, Int> cache, CacheContext context) {
    cache.putAll(context.absent());
    if (context.isWeighted()) {
      assertThat(context).hasWeightedSize(context.maximumWeight());
    } else {
      assertThat(cache).hasSize(context.maximumSize());
    }

    var evicted = new HashMap<Int, Int>();
    evicted.putAll(Maps.difference(context.original(), cache.asMap()).entriesOnlyOnLeft());
    evicted.putAll(Maps.difference(context.absent(), cache.asMap()).entriesOnlyOnLeft());
    assertThat(context).stats().evictions(evicted.size());
    assertThat(evicted).hasSize(context.absentKeys().size());
    assertThat(context).notifications().withCause(SIZE)
        .contains(evicted).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.COLLECTION,
      initialCapacity = InitialCapacity.EXCESSIVE)
  public void evict_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    // Enforce full initialization of internal structures
    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), List.of());
    }
    cache.invalidateAll();

    var value1 = intern(Int.listOf(8, 9, 10));
    var value2 = intern(Int.listOf(3, 4, 5, 6, 7));
    var value3 = intern(Int.listOf(1, 2));
    var value4 = intern(Int.listOf(11));
    var value5 = intern(Int.listOf(12, 13, 14, 15, 16, 17, 18, 19, 20));

    // Never evicted
    cache.put(Int.valueOf(0), List.of());

    cache.put(Int.valueOf(1), value1);
    cache.put(Int.valueOf(2), value2);
    cache.put(Int.valueOf(3), value3);
    assertThat(cache).hasSize(4);
    assertThat(context).hasWeightedSize(10);

    // [0(0) | 1(3), 2(5), 3(2)] -> [4(1), 0(0) | 3(2), 2(5), 1(3)] -> [0(0) | 3(2), 2(5), 1(3)]
    // #1: candidate=0 vs victim=1 -> skip candidate due to zero weight
    // #2: candidate=4 vs victim=1 -> f(4) == f(1) -> evict candidate
    cache.put(Int.valueOf(4), value4);
    assertThat(cache).hasSize(4);
    assertThat(context).hasWeightedSize(10);
    assertThat(cache).doesNotContainKey(Int.valueOf(4));

    // [0(0) | 3(2), 2(5), 1(3)] -> [4(1), 0(0) | 3(2), 2(5), 1(3)] -> [4(1), 0(0) | 3(2), 2(5)]
    // #1: candidate=0 vs victim=1 => skip candidate due to zero weight
    // #2: candidate=4 vs victim=1 -> f(4) > f(1) -> evict victim
    cache.put(Int.valueOf(4), value4);
    assertThat(cache).hasSize(4);
    assertThat(context).hasWeightedSize(8);
    assertThat(cache).doesNotContainKey(Int.valueOf(1));

    // [4(1), 0(0) | 3(2), 2(5)] -> [4(1), 0(0), 5(9) | 3(2), 2(5)] -> [4(1), 0(0) | 3(2), 2(5)]
    // 5 exceeds window (9 >> 1), so it is inserted at the LRU position for immediate promotion
    // #1: candidate=5 vs victim=2 => f(5) == f(2) -> evict candidate
    cache.put(Int.valueOf(5), value5);
    assertThat(cache).hasSize(4);
    assertThat(context).hasWeightedSize(8);
    assertThat(context).stats().evictions(3).evictionWeight(13);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.COLLECTION)
  public void evict_weighted_reorder(Cache<Int, List<Int>> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    eviction.setMaximum(3);
    for (int i = 1; i <= 3; i++) {
      cache.put(Int.valueOf(i), intern(Int.listOf(1)));
    }
    cache.asMap().computeIfPresent(Int.valueOf(1), (k, v) -> intern(Int.listOf(1, 2)));
    assertThat(cache).containsEntry(Int.valueOf(1), intern(Int.listOf(1, 2)));
    assertThat(context).hasWeightedSize(3);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.VALUE)
  public void evict_weighted_entryTooBig(Cache<Int, Int> cache, CacheContext context) {
    cache.put(Int.valueOf(9), Int.valueOf(9));
    cache.put(Int.valueOf(1), Int.valueOf(1));
    assertThat(cache).hasSize(2);
    cache.policy().eviction().ifPresent(eviction -> {
      assertThat(context).hasWeightedSize(10);
    });

    cache.put(Int.valueOf(20), Int.valueOf(20));
    assertThat(cache).hasSize(2);
    cache.policy().eviction().ifPresent(eviction -> {
      assertThat(context).hasWeightedSize(10);
    });
    assertThat(context).notifications().withCause(SIZE)
        .contains(Int.valueOf(20), Int.valueOf(20)).exclusively();
    if (context.isCaffeine()) {
      assertThat(context).stats().evictionWeight(20);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.VALUE, removalListener = Listener.CONSUMING)
  public void evict_weighted_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return Int.valueOf(6);
    }, executor);
    var completed = valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(Int.valueOf(5), Int.futureOf(5));
    cache.put(Int.valueOf(4), Int.futureOf(4));
    cache.put(Int.valueOf(6), valueFuture);
    assertThat(context).hasWeightedSize(9);
    assertThat(cache).hasSize(3);

    ready.set(true);
    await().untilTrue(done);
    await().until(completed::isDone);
    await().untilAsserted(() -> assertThat(cache).hasSize(2));
    await().untilAsserted(() -> assertThat(context)
        .hasWeightedSizeLessThan(context.maximumWeight() + 1));

    int expected = 15 - cache.synchronous().asMap().values().stream().mapToInt(Int::intValue).sum();
    assertThat(context).stats().evictionWeight(expected);
    assertThat(context).notifications().withCause(SIZE)
        .contains(Int.valueOf(expected), Int.valueOf(expected)).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.ZERO, weigher = CacheWeigher.COLLECTION)
  public void evict_zero_async(AsyncCache<Int, List<Int>> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return Int.listOf(1, 2, 3, 4, 5);
    }, executor);
    var completed = valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(context).hasWeightedSize(0);
    assertThat(cache).hasSize(1);

    ready.set(true);
    await().untilTrue(done);
    await().until(completed::isDone);
    await().untilAsserted(() -> assertThat(cache).isEmpty());
    assertThat(context).notifications().withCause(SIZE)
        .contains(Map.entry(context.absentKey(), Int.listOf(1, 2, 3, 4, 5)))
        .exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.DISABLED, evictionListener = Listener.MOCKITO,
      removalListener = Listener.REJECTING)
  public void evict_evictionListenerFails(Cache<Int, Int> cache, CacheContext context) {
    doThrow(RuntimeException.class)
        .when(context.evictionListener()).onRemoval(any(), any(), any());
    cache.policy().eviction().ifPresent(policy -> policy.setMaximum(0));
    verify(context.evictionListener(), times((int) context.initialSize()))
        .onRemoval(any(), any(), any());
  }

  /* --------------- Weighted --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.NEGATIVE)
  public void put_negativeWeight(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalArgumentException.class, () ->
        cache.put(context.absentKey(), context.absentValue()));
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void put_weigherFails_insert(Cache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.put(context.absentKey(), context.absentValue()));
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void put_weigherFails_update(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.put(context.firstKey(), context.absentValue()));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL,
      weigher = CacheWeigher.ZERO, population = Population.EMPTY)
  public void put_zeroWeight(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void put(Cache<String, List<Int>> cache, CacheContext context) {
    cache.put("a", intern(Int.listOf(1, 2, 3)));
    assertThat(context).hasWeightedSize(3);
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void put_sameWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1))));
    cache.put("a", intern(Int.listOf(-1, -2, -3)));
    assertThat(context).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void put_changeWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1))));
    cache.put("a", intern(Int.listOf(-1, -2, -3, -4)));
    assertThat(context).hasWeightedSize(5);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void put_asyncWeight(AsyncCache<Int, List<Int>> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      return Int.listOf(1, 2, 3, 4, 5);
    }, executor);
    var completed = valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(context).hasWeightedSize(0);
    assertThat(cache).hasSize(1);

    ready.set(true);
    await().untilTrue(done);
    await().until(completed::isDone);
    await().untilAsserted(() -> assertThat(cache).hasSize(1));
    await().untilAsserted(() -> assertThat(context).hasWeightedSize(5));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, implementation = Implementation.Guava,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void replace_weigherFails_absent(Cache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    Runnable replace = () -> cache.asMap().replace(context.absentKey(), context.absentValue());
    if (context.isAsync() || context.isGuava()) {
      replace.run();
    } else {
      assertThrows(IllegalStateException.class, replace::run);
    }
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void replace_weigherFails_present(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.asMap().replace(context.firstKey(), context.absentValue()));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void replace_sameWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1))));
    cache.asMap().replace("a", intern(Int.listOf(-1, -2, -3)));
    assertThat(context).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void replace_changeWeight(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1))));
    cache.asMap().replace("a", intern(Int.listOf(-1, -2, -3, -4)));
    assertThat(context).hasWeightedSize(5);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void replaceConditionally_sameWeight(
      Cache<String, List<Int>> cache, CacheContext context) {
    var oldValue = intern(Int.listOf(1, 2, 3));
    var newValue = intern(Int.listOf(4, 5, 6));
    cache.putAll(intern(Map.of("a", oldValue, "b", Int.listOf(1))));
    assertThat(cache.asMap().replace("a", oldValue, newValue)).isTrue();
    assertThat(context).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void replaceConditionally_changeWeight(
      Cache<String, List<Int>> cache, CacheContext context) {
    List<Int> oldValue = intern(Int.listOf(1, 2, 3));
    List<Int> newValue = intern(Int.listOf(-1, -2, -3, -4));
    cache.putAll(intern(Map.of("a", oldValue, "b", Int.listOf(1))));
    cache.asMap().replace("a", oldValue, newValue);
    assertThat(context).hasWeightedSize(5);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void replaceConditionally_weigherFails_absent(
      Cache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    Runnable replace = () -> cache.asMap().replace(
        context.absentKey(), context.absentValue(), context.absentValue());
    if (context.isAsync() || context.isGuava()) {
      replace.run();
    } else {
      assertThrows(IllegalStateException.class, replace::run);
    }
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void replaceConditionally_weigherFails_presentKey(
      Cache<Int, Int> cache, CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.asMap().replace(context.firstKey(), context.absentValue(), context.absentValue()));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void replaceConditionally_weigherFails_presentKeyAndValue(
      Cache<Int, Int> cache, CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().replace(context.firstKey(),
          context.original().get(context.firstKey()), context.absentValue());
    });
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void replaceConditionally_fails(Cache<String, List<Int>> cache, CacheContext context) {
    List<Int> oldValue = intern(Int.listOf(1));
    List<Int> newValue = intern(Int.listOf(4, 5));
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", oldValue)));
    assertThat(cache.asMap().replace("a", oldValue, newValue)).isFalse();
    assertThat(context).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void remove(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1))));
    assertThat(cache.asMap().remove("a")).containsExactlyElementsIn(Int.listOf(1, 2, 3)).inOrder();
    assertThat(context).hasWeightedSize(1);
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void removeConditionally(Cache<String, List<Int>> cache, CacheContext context) {
    var oldValue = intern(Int.listOf(1, 2, 3));
    cache.putAll(intern(Map.of("a", oldValue, "b", Int.listOf(1))));
    assertThat(cache.asMap().remove("a", oldValue)).isTrue();
    assertThat(context).hasWeightedSize(1);
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void removeConditionally_fails(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(intern(Map.of("a", Int.listOf(1, 2, 3), "b", Int.listOf(1))));
    assertThat(cache.asMap().remove("a", Int.listOf(-1, -2, -3))).isFalse();
    assertThat(context).hasWeightedSize(4);
    assertThat(cache).hasSize(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION)
  public void invalidateAll(Cache<String, List<Int>> cache, CacheContext context) {
    cache.putAll(Map.of("a", intern(Int.listOf(1, 2, 3)), "b", intern(Int.listOf(1))));
    cache.invalidateAll();
    assertThat(cache).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void computeIfAbsent_weigherFails(Cache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.asMap().computeIfAbsent(context.absentKey(), key -> context.absentValue()));
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.TEN)
  public void computeIfAbsent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    cache.asMap().computeIfAbsent(context.absentKey(), key -> context.absentValue());
    assertThat(eviction.weightOf(context.absentKey())).hasValue(10);
    assertThat(context).hasWeightedSize(10);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void computeIfPresent_weigherFails(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.asMap().computeIfPresent(context.firstKey(), (key, value) -> context.absentValue()));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void computeIfPresent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = eviction.weightedSize().getAsLong()
        + Math.abs(context.absentValue().intValue()) - context.firstKey().intValue();
    cache.asMap().computeIfPresent(context.firstKey(), (key, value) -> context.absentValue());
    assertThat(eviction.weightOf(context.firstKey()))
        .hasValue(Math.abs(context.absentValue().intValue()));
    assertThat(context).hasWeightedSize(weightedSize);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void compute_weigherFails_absent(Cache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.asMap().compute(context.absentKey(), (key, value) -> context.absentValue()));
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void compute_weigherFails_present(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () ->
        cache.asMap().compute(context.firstKey(), (key, value) -> context.absentValue()));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void compute_insert(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = eviction.weightedSize().getAsLong()
        + Math.abs(context.absentValue().intValue());
    cache.asMap().compute(context.absentKey(), (key, value) -> context.absentValue());
    assertThat(eviction.weightOf(context.absentKey()))
        .hasValue(Math.abs(context.absentValue().intValue()));
    assertThat(context).hasWeightedSize(weightedSize);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void compute_update(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = eviction.weightedSize().getAsLong()
        + Math.abs(context.absentValue().intValue()) - context.firstKey().intValue();
    cache.asMap().compute(context.firstKey(), (key, value) -> context.absentValue());
    assertThat(eviction.weightOf(context.firstKey()))
        .hasValue(Math.abs(context.absentValue().intValue()));
    assertThat(context).hasWeightedSize(weightedSize);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void merge_weigherFails_absent(Cache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().merge(context.absentKey(),
          context.absentKey(), (key, value) -> context.absentValue());
    });
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  public void merge_weigherFails_present(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    when(context.weigher().weigh(any(), any())).thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().merge(context.firstKey(),
          context.absentValue(), (key, value) -> context.absentValue());
    });
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(eviction.weightOf(context.firstKey())).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void merge_absent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = eviction.weightedSize().getAsLong()
        + Math.abs(context.absentValue().intValue());
    cache.asMap().merge(context.absentKey(),
        context.absentKey(), (key, value) -> context.absentValue());
    assertThat(eviction.weightOf(context.absentKey()))
        .hasValue(Math.abs(context.absentValue().intValue()));
    assertThat(context).hasWeightedSize(weightedSize);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void merge_update(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    long weightedSize = eviction.weightedSize().getAsLong()
        + Math.abs(context.absentValue().intValue()) - context.firstKey().intValue();
    cache.asMap().merge(context.firstKey(),
        context.absentKey(), (key, value) -> context.absentValue());
    assertThat(eviction.weightOf(context.firstKey()))
        .hasValue(Math.abs(context.absentValue().intValue()));
    assertThat(context).hasWeightedSize(weightedSize);
  }

  /* --------------- Policy --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = Maximum.UNREACHABLE)
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
  @CacheSpec(maximumSize = Maximum.FULL, population = Population.EMPTY)
  public void isWeighted(CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.isWeighted()).isEqualTo(context.isWeighted());
  }

  /* --------------- Policy: WeightOf --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void weightOf(Cache<Int, Int> cache, CacheContext context, Eviction<Int, Int> eviction) {
    Int key = Int.valueOf(1);
    cache.put(key, Int.valueOf(1));
    assertThat(eviction.weightOf(key)).hasValue(1);

    cache.put(key, Int.valueOf(2));
    assertThat(eviction.weightOf(key)).hasValue(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void weightOf_absent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.weightOf(context.absentKey())).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.VALUE, mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void weightOf_expired(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    cache.put(context.absentKey(), Int.valueOf(1));
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(eviction.weightOf(context.absentKey())).isEmpty();
  }

  /* --------------- Policy: WeightedSize --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED)
  public void weightedSize_absent(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.weightOf(context.firstKey())).isEmpty();
    assertThat(eviction.weightOf(context.absentKey())).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, weigher = CacheWeigher.TEN)
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
  @CacheSpec(maximumSize = Maximum.FULL)
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
        assertThat(context).notifications().withCause(SIZE)
            .contains(Maps.difference(context.original(), cache.asMap()).entriesOnlyOnLeft())
            .exclusively();
      }
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, weigher = { CacheWeigher.DISABLED, CacheWeigher.TEN })
  public void maximumSize_decrease_min(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    eviction.setMaximum(0);
    assertThat(eviction.getMaximum()).isEqualTo(0);
    if (context.initialSize() > 0) {
      long expectedSize = context.isZeroWeighted() ? context.initialSize() : 0;
      assertThat(cache).hasSize(expectedSize);
    }
    assertThat(context).notifications().withCause(SIZE)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void maximumSize_decrease_negative(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(IllegalArgumentException.class, () -> eviction.setMaximum(-1));
    assertThat(eviction.getMaximum()).isEqualTo(context.maximumWeightOrSize());
    assertThat(context).notifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void maximumSize_increase(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    eviction.setMaximum(2 * context.maximumWeightOrSize());
    assertThat(cache).hasSize(context.initialSize());
    assertThat(eviction.getMaximum()).isEqualTo(2 * context.maximumWeightOrSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, removalListener = Listener.REJECTING)
  public void maximumSize_increase_max(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    eviction.setMaximum(Long.MAX_VALUE);
    assertThat(cache).hasSize(context.initialSize());
    assertThat(eviction.getMaximum()).isEqualTo(Long.MAX_VALUE - Integer.MAX_VALUE); // impl detail
  }

  /* --------------- Policy: Coldest --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void coldest_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.coldest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void coldest_negative(CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(IllegalArgumentException.class, () -> eviction.coldest(-1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void coldest_zero(CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.coldest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldest_partial(CacheContext context, Eviction<Int, Int> eviction) {
    int count = context.original().size() / 2;
    assertThat(eviction.coldest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL, weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN},
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void coldest_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var coldest = new LinkedHashSet<>(eviction.coldest(Integer.MAX_VALUE).keySet());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldest_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var coldest = eviction.coldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_null(CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(NullPointerException.class, () -> eviction.coldest(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_nullResult(CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.coldest(stream -> null);
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_throwsException(CacheContext context, Eviction<Int, Int> eviction) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        eviction.coldest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(ConcurrentModificationException.class, () -> {
      eviction.coldest(stream -> {
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_closed(CacheContext context, Eviction<Int, Int> eviction) {
    var stream = eviction.coldest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.coldest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_full(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.coldest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL, weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN},
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void coldestFunc_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var coldest = new LinkedHashSet<>(eviction.coldest(stream ->
        stream.map(Map.Entry::getKey).collect(toImmutableList())));

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestFunc_metadata(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var entries = eviction.coldest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void coldestWeight_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    var results = eviction.coldestWeighted(Long.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void coldestWeighted_negative(CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(IllegalArgumentException.class, () -> eviction.coldestWeighted(-1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void coldestWeighted_zero(CacheContext context, Eviction<Int, Int> eviction) {
    if (context.cacheWeigher() == CacheWeigher.ZERO) {
      assertThat(eviction.coldestWeighted(0)).isEqualTo(context.original());
    } else {
      assertThat(eviction.coldestWeighted(0)).isExhaustivelyEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
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
  @CacheSpec(population = Population.FULL, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL, weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN},
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void coldestWeighted_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var coldest = new LinkedHashSet<>(eviction.coldestWeighted(Long.MAX_VALUE).keySet());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void coldestWeighted_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var coldest = eviction.coldestWeighted(Long.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest).containsExactlyEntriesIn(context.original());
  }

  /* --------------- Policy: Hottest --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void hottest_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.hottest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void hottest_negative(CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(IllegalArgumentException.class, () -> eviction.hottest(-1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void hottest_zero(CacheContext context, Eviction<Int, Int> eviction) {
    assertThat(eviction.hottest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottest_partial(CacheContext context, Eviction<Int, Int> eviction) {
    int count = context.original().size() / 2;
    assertThat(eviction.hottest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL, removalListener = {Listener.DISABLED, Listener.REJECTING})
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
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottest_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var hottest = eviction.hottest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_null(CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(NullPointerException.class, () -> eviction.hottest(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_nullResult(CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.hottest(stream -> null);
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_throwsException(CacheContext context, Eviction<Int, Int> eviction) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        eviction.hottest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(ConcurrentModificationException.class, () -> {
      eviction.hottest(stream -> {
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_closed(CacheContext context, Eviction<Int, Int> eviction) {
    var stream = eviction.hottest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.hottest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_full(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.hottest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL, removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void hottestFunc_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var hottest = eviction.hottest(stream ->
        stream.map(Map.Entry::getKey).collect(toImmutableList()));
    var coldest = new LinkedHashSet<>(hottest.reverse());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestFunc_metadata(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var entries = eviction.hottest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = Maximum.FULL)
  public void hottestWeighted_snapshot(Cache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    var hottest = eviction.hottestWeighted(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void hottestWeighted_unmodifiable(CacheContext context, Eviction<Int, Int> eviction) {
    var result = eviction.hottestWeighted(Long.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void hottestWeighted_negative(CacheContext context, Eviction<Int, Int> eviction) {
    assertThrows(IllegalArgumentException.class, () -> eviction.hottestWeighted(-1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void hottestWeighted_zero(CacheContext context, Eviction<Int, Int> eviction) {
    if (context.cacheWeigher() == CacheWeigher.ZERO) {
      assertThat(eviction.hottestWeighted(0)).isEqualTo(context.original());
    } else {
      assertThat(eviction.hottestWeighted(0)).isExhaustivelyEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
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
  @CacheSpec(population = Population.FULL, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.FULL, removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void hottestWeighted_order(CacheContext context, Eviction<Int, Int> eviction) {
    var keys = new LinkedHashSet<>(context.original().keySet());
    var hottest = eviction.hottestWeighted(Long.MAX_VALUE).keySet();
    var coldest = new LinkedHashSet<>(ImmutableList.copyOf(hottest).reverse());

    // Ignore the last key; hard to predict with W-TinyLFU
    keys.remove(context.lastKey());
    coldest.remove(context.lastKey());
    assertThat(coldest).containsExactlyElementsIn(keys).inOrder();
  }
}
