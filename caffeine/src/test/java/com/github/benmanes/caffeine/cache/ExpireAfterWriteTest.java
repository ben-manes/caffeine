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

import static com.github.benmanes.caffeine.cache.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Nullness.nullFunction;
import static com.google.common.base.Functions.identity;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.slf4j.event.Level.TRACE;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.params.ParameterizedTest;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.StartTime;
import com.github.benmanes.caffeine.cache.Policy.FixedExpiration;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;

/**
 * The test cases for caches that support the expire-after-write (time-to-live) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
final class ExpireAfterWriteTest {

  /* --------------- Cache --------------- */

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expireAfterWrite = { Expire.DISABLED, Expire.ONE_MINUTE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).isEqualTo(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.firstKey())).isNull();

    cache.cleanUp();
    assertThat(cache).isEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  void get(Cache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = key -> requireNonNull(context.original().get(key));
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey(), mappingFunction);
    assertThat(value).isEqualTo(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofSeconds(45));
    var recreated = cache.get(context.lastKey(), mappingFunction);
    assertThat(recreated).isEqualTo(context.lastKey().negate());

    cache.cleanUp();
    assertThat(cache).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  void getAllPresent(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var results = cache.getAllPresent(context.firstMiddleLastKeys());
    assertThat(results).containsExactlyEntriesIn(Maps.filterKeys(
        context.original(), context.firstMiddleLastKeys()::contains));

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys())).isExhaustivelyEmpty();

    cache.cleanUp();
    assertThat(cache).isEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  void getAll(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var result1 = cache.getAll(List.of(context.firstKey(), context.absentKey()),
        keys -> Maps.toMap(keys, identity()));
    assertThat(result1).containsExactly(
        context.firstKey(), context.firstKey().negate(), context.absentKey(), context.absentKey());

    context.ticker().advance(Duration.ofSeconds(45));
    cache.cleanUp();
    var result2 = cache.getAll(List.of(context.firstKey(), context.absentKey()),
        keys -> Maps.toMap(keys, identity()));
    assertThat(result2).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(cache).hasSize(2);

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  /* --------------- LoadingCache --------------- */

  @ParameterizedTest
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  void get_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.get(context.firstKey())).isEqualTo(context.original().get(context.firstKey()));
    assertThat(cache.get(context.absentKey())).isEqualTo(context.absentKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));

    cache.cleanUp();
    assertThat(cache).hasSize(1);
    assertThat(cache).containsEntry(context.absentKey(), context.absentKey().negate());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  void getAll_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(List.of(context.firstKey(), context.absentKey())))
        .containsExactly(context.firstKey(), context.firstKey().negate(),
            context.absentKey(), context.absentKey());

    context.ticker().advance(Duration.ofSeconds(45));
    cache.cleanUp();

    assertThat(cache.getAll(List.of(context.firstKey(), context.absentKey())))
        .containsExactly(context.firstKey(), context.firstKey(),
            context.absentKey(), context.absentKey());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
    assertThat(cache).hasSize(2);
  }

  /* --------------- AsyncCache --------------- */

  @ParameterizedTest
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  void getIfPresent(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.firstKey()))
        .succeedsWith(context.original().get(context.firstKey()));
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(cache).doesNotContainKey(context.lastKey());

    context.cleanUp();
    assertThat(cache).isEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  /* --------------- Map --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void putIfAbsent(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue())).isNotNull();

    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.putIfAbsent(context.lastKey(), context.absentValue())).isNull();

    assertThat(map).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  void getIfPresentQuietly(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var original = expireAfterWrite.ageOf(context.firstKey()).orElseThrow();
    var advancement = Duration.ofSeconds(30);
    context.ticker().advance(advancement);
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey()))
        .isEqualTo(context.original().get(context.firstKey()));
    var current = expireAfterWrite.ageOf(context.firstKey()).orElseThrow();
    assertThat(current.minus(advancement)).isEqualTo(original);
  }

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  void getExpiresAfter(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES)).isEqualTo(1);
  }

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  void getExpiresAfter_duration(
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void setExpiresAfter_negative(
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterWrite.setExpiresAfter(Duration.ofMinutes(-2)));
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void setExpiresAfter_excessive(
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(ChronoUnit.FOREVER.getDuration());
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  @ParameterizedTest
  @SuppressWarnings("PreferJavaTimeOverload")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void setExpiresAfter(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES)).isEqualTo(2);

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(context.initialSize());
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void setExpiresAfter_duration(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(Duration.ofMinutes(2));
    assertThat(expireAfterWrite.getExpiresAfter()).isEqualTo(Duration.ofMinutes(2));

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(context.initialSize());
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      expireAfterWrite = Expire.ONE_MINUTE)
  void ageOf(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(0);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(30);
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      expireAfterWrite = Expire.ONE_MINUTE)
  void ageOf_duration(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    // Truncated to seconds to ignore the LSB (nanosecond) used for refreshAfterWrite's lock
    assertThat(expireAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(0);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(expireAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(30);
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(expireAfterWrite.ageOf(context.firstKey())).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void ageOf_absent(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.absentKey())).isEmpty();
    assertThat(expireAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  void ageOf_expired(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(expireAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expireAfterWrite = Expire.ONE_MINUTE)
  void ageOf_async(AsyncCache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    assertThat(expireAfterWrite.ageOf(context.absentKey()).orElseThrow())
        .isAtLeast(Duration.ofNanos(-Async.ASYNC_EXPIRY));

    future.complete(Int.valueOf(2));
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(expireAfterWrite.ageOf(context.absentKey()).orElseThrow())
        .isIn(Range.closed(Duration.ofSeconds(30), Duration.ofSeconds(31)));
  }

  /* --------------- Policy: oldest --------------- */

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldest_unmodifiable(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var results = expireAfterWrite.oldest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldest_negative(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(IllegalArgumentException.class, () -> expireAfterWrite.oldest(-1));
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldest_zero(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.oldest(0)).isExhaustivelyEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  void oldest_partial(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    int count = context.original().size() / 2;
    assertThat(expireAfterWrite.oldest(count)).hasSize(count);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  void oldest_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet()).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldest_snapshot(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_null(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(NullPointerException.class, () -> expireAfterWrite.oldest(nullFunction()));
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_nullResult(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.oldest(stream -> null);
    assertThat(result).isNull();
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_throwsException(
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        expireAfterWrite.oldest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(ConcurrentModificationException.class, () -> {
      expireAfterWrite.oldest(stream -> {
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_closed(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var stream = expireAfterWrite.oldest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.oldest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_full(Cache<Int, Int> cache,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.oldest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var oldest = expireAfterWrite.oldest(stream -> stream.map(Map.Entry::getKey)
        .collect(toImmutableList()));
    assertThat(oldest).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, population = {Population.PARTIAL, Population.FULL})
  void oldestFunc_metadata(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var entries = expireAfterWrite.oldest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  void oldestFunc_metadata_expiresInTraversal(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(30));
    var entries = expireAfterWrite.oldest(stream -> stream.collect(toImmutableList()));
    assertThat(context.cache()).hasSize(context.initialSize());
    assertThat(entries).hasSize(1);
  }

  /* --------------- Policy: youngest --------------- */

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngest_unmodifiable(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var results = expireAfterWrite.youngest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngest_negative(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(IllegalArgumentException.class, () -> expireAfterWrite.youngest(-1));
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngest_zero(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.youngest(0)).isExhaustivelyEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  void youngest_partial(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    int count = context.original().size() / 2;
    assertThat(expireAfterWrite.youngest(count)).hasSize(count);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  void youngest_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest.keySet()).containsExactlyElementsIn(expected).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngest_snapshot(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_null(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(NullPointerException.class, () -> expireAfterWrite.youngest(nullFunction()));
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_nullResult(
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.youngest(stream -> null);
    assertThat(result).isNull();
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_throwsException(
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        expireAfterWrite.youngest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThrows(ConcurrentModificationException.class, () -> {
      expireAfterWrite.youngest(stream -> {
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_closed(@ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var stream = expireAfterWrite.youngest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.youngest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_full(Cache<Int, Int> cache,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.youngest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var youngest = expireAfterWrite.youngest(
        stream -> stream.map(Map.Entry::getKey).collect(toImmutableList()));
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest).containsExactlyElementsIn(expected).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, population = {Population.PARTIAL, Population.FULL})
  void youngestFunc_metadata(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var entries = expireAfterWrite.youngest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  void youngestFunc_metadata_expiresInTraversal(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(30));
    var entries = expireAfterWrite.youngest(stream -> stream.collect(toImmutableList()));
    assertThat(context.cache()).hasSize(context.initialSize());
    assertThat(entries).hasSize(1);
  }
}
