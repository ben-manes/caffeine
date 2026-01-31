/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.MAXIMUM_EXPIRY;
import static com.github.benmanes.caffeine.cache.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Nullness.nullBiFunction;
import static com.github.benmanes.caffeine.testing.Nullness.nullDuration;
import static com.github.benmanes.caffeine.testing.Nullness.nullFunction;
import static com.github.benmanes.caffeine.testing.Nullness.nullKey;
import static com.github.benmanes.caffeine.testing.Nullness.nullRef;
import static com.github.benmanes.caffeine.testing.Nullness.nullValue;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Thread.State.BLOCKED;
import static java.lang.Thread.State.WAITING;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ConcurrentModificationException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.StartTime;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.testing.SerializableTester;

/**
 * The test cases for caches that support the variable expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
final class ExpireAfterVarTest {

  @ParameterizedTest
  @CacheSpec(expiryTime = Expire.FOREVER,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  void expiry_bounds(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofNanos(System.nanoTime()));
    var running = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key = context.absentKey();
    cache.put(key, key);

    try {
      ConcurrentTestHarness.execute(() -> {
        while (!done.get()) {
          context.ticker().advance(Duration.ofMinutes(1));
          var value = cache.get(key, Int::new);
          assertThat(value).isSameInstanceAs(key);
          running.set(true);
        }
      });
      await().untilTrue(running);
      cache.cleanUp();

      assertThat(cache.get(key, Int::new)).isSameInstanceAs(key);
    } finally {
      done.set(true);
    }
  }

  /* --------------- Get --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).isNotNull();

    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void getIfPresent_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    assertThat(cache.getIfPresent(context.absentKey())).isEqualTo(context.absentValue());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void get_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    var value = cache.get(context.absentKey());
    assertThat(value).isNotNull();

    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void get_absent_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    assertThat(cache.get(context.absentKey())).isEqualTo(context.absentKey().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();

    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void get_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void get_present_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    assertThat(cache.get(context.absentKey())).isEqualTo(context.absentValue());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getAll_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var results = cache.getAll(context.firstMiddleLastKeys());
    assertThat(results).isNotEmpty();

    verify(context.expiry(), times(3)).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE})
  void getAll_present_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    assertThat(cache.getAll(context.firstMiddleLastKeys()))
        .containsExactlyEntriesIn(Maps.toMap(context.firstMiddleLastKeys(),
            key -> requireNonNull(context.original().get(key))));

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(Maps.toMap(context.firstMiddleLastKeys(), Int::negate))
        .exclusively();
    assertThat(cache).containsExactlyEntriesIn(Maps.filterKeys(
        context.original(), not(in(context.firstMiddleLastKeys()))));

    verify(context.expiry(), times(context.firstMiddleLastKeys().size()))
        .expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  void getAll_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var results = cache.getAll(context.absentKeys());
    assertThat(results).isNotEmpty();

    verify(context.expiry(), times(context.absent().size()))
        .expireAfterCreate(any(), any(), anyLong());
    if (context.isAsync() && !context.loader().isBulk()) {
      verify(context.expiry(), times(context.absent().size()))
          .expireAfterUpdate(any(), any(), anyLong(), anyLong());
    }
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE})
  void getAll_absent_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    assertThat(cache.getAll(context.absentKeys())).containsExactlyEntriesIn(context.absent());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absent())
        .exclusively();
    assertThat(cache).isEmpty();

    verify(context.expiry(), times(context.absent().size()))
        .expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  /* --------------- Create --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void put_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(cache).doesNotContainKey(context.middleKey());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());

    context.cleanUp();
    assertThat(cache).hasSize(1);

    var expected = new HashMap<>(context.original());
    expected.put(context.firstKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void put_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = context.absentValue().toFuture();
    context.ticker().advance(Duration.ofSeconds(30));

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(cache).doesNotContainKey(context.middleKey());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());

    context.cleanUp();
    assertThat(cache).hasSize(1);

    var expected = new HashMap<>(context.original());
    expected.put(context.firstKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void put_insert_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.put(context.absentKey(), context.absentValue());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void put_replace(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));

    assertThat(map.put(context.firstKey(), context.absentValue())).isNotNull();
    assertThat(map.put(context.absentKey(), context.absentValue())).isNull();
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(map).doesNotContainKey(context.firstKey());
    assertThat(map).doesNotContainKey(context.middleKey());
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());

    context.cleanUp();
    assertThat(map).hasSize(1);

    var expected = new HashMap<>(context.original());
    expected.put(context.firstKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
  void put_replace_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.put(context.absentKey(), context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void putAll_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));

    cache.putAll(Map.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(cache).doesNotContainKey(context.middleKey());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());

    context.cleanUp();
    assertThat(cache).hasSize(1);

    var expected = new HashMap<>(context.original());
    expected.put(context.firstKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void replace_updated(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.replace(context.firstKey(), context.absentValue())).isNotNull();
    context.ticker().advance(Duration.ofSeconds(45));

    context.cleanUp();
    assertThat(map).isExhaustivelyEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
  void replace_updated_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().replace(context.absentKey(), context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void replace_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.replace(context.firstKey(), context.absentValue()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void replace_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.asMap().replace(context.firstKey(), future);
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void replaceConditionally_updated(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.replace(key, context.original().get(key), context.absentValue())).isTrue();
    context.ticker().advance(Duration.ofSeconds(45));

    context.cleanUp();
    assertThat(map).isExhaustivelyEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
  void replaceConditionally_updated_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().replace(context.absentKey(),
        context.absentValue(), context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void replaceConditionally_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    Int oldValue = context.original().get(context.firstKey());
    assertThrows(ExpirationException.class, () ->
        map.replace(context.firstKey(), oldValue, context.absentValue()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void replaceConditionally_expiryFails_async(
      AsyncCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var prior = requireNonNull(cache.getIfPresent(context.firstKey()));
    var future = new CompletableFuture<Int>();
    cache.asMap().replace(context.firstKey(), prior, future);
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  /* --------------- Exceptional --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getIfPresent_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> cache.getIfPresent(context.firstKey()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void get_expiryFails_create(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> cache.get(context.absentKey(), identity()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void get_expiryFails_create_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = cache.get(context.absentKey(), (key, executor) -> new CompletableFuture<>());
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void get_expiryFails_read(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> cache.get(context.firstKey(), identity()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getAll_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.getAll(context.absentKeys(), keys -> Maps.toMap(keys, Int::negate)));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    if (context.isCaffeine()) {
      assertThat(logEvents()
          .withMessage("Exception thrown during asynchronous load")
          .withThrowable(ExpirationException.class)
          .withLevel(WARN)
          .exclusively())
          .hasSize(context.isAsync() ? context.absentKeys().size() : 0);
    }
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getAll_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Map<Int, Int>>();
    var result = cache.getAll(context.absentKeys(), (keys, executor) -> future);
    future.complete(Maps.toMap(context.absentKeys(), Int::negate));
    assertThat(result).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(ExpirationException.class);
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(context.absentKeys().size());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getAll_expiryFails_newEntries(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.getAll(context.absentKeys(), keys -> HashBiMap.create(context.original()).inverse()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    if (context.isCaffeine()) {
      assertThat(logEvents()
          .withMessage("Exception thrown during asynchronous load")
          .withThrowable(ExpirationException.class)
          .withLevel(WARN)
          .exclusively())
          .hasSize(context.isAsync() ? context.original().size() : 0);
    }
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getAll_expiryFails_newEntries_async(
      AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Map<Int, Int>>();
    var result = cache.getAll(context.absentKeys(), (keys, executor) -> future);
    future.complete(HashBiMap.create(context.original()).inverse());
    assertThat(result).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(ExpirationException.class);
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(context.original().size());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void getAllPresent_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.getAllPresent(context.firstMiddleLastKeys()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void put_insert_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.put(context.absentKey(), context.absentValue()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void put_insert_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    future.complete(context.absentKey());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void put_insert_replaceExpired_expiryFails(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireVariably) {
    var expectedDuration = expireVariably.getExpiresAfter(context.firstKey(), TimeUnit.NANOSECONDS);
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.put(context.firstKey(), context.absentValue()));

    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    var currentDuration = expireVariably.getExpiresAfter(
        context.firstKey(), TimeUnit.NANOSECONDS);
    assertThat(currentDuration).isEqualTo(expectedDuration);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void put_update_expiryFails(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireVariably) {
    var expectedDuration = expireVariably.getExpiresAfter(context.firstKey(), TimeUnit.NANOSECONDS);
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.put(context.firstKey(), context.absentValue()));

    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
    var currentDuration = expireVariably.getExpiresAfter(
        context.firstKey(), TimeUnit.NANOSECONDS);
    assertThat(currentDuration).isEqualTo(expectedDuration);
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void put_update_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.put(context.firstKey(), future);
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  /* --------------- Compute --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfAbsent_absent(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.absentKey(), identity());
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfAbsent_nullValue(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.absentKey(), key -> null);
    verifyNoInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfAbsent_present(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.firstKey(), identity());
    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void computeIfAbsent_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().computeIfAbsent(context.absentKey(), key -> context.absentValue());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfAbsent_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.computeIfAbsent(context.absentKey(), identity()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfAbsent_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.asMap().computeIfAbsent(context.absentKey(), key -> future);
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_absent(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.absentKey(), (key, value) -> value);
    verifyNoInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_nullValue(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.firstKey(), (key, value) -> null);
    verifyNoInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_present_differentValue(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.firstKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.firstKey(), (key, value) -> value);
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().computeIfPresent(context.absentKey(), (k, v) -> context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.computeIfPresent(context.firstKey(), (k, v) -> v.negate()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void computeIfPresent_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.asMap().computeIfPresent(context.firstKey(), (k, v) -> future);
    future.complete(context.firstKey());
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_absent(Map<Int, Int> map, CacheContext context) {
    map.compute(context.absentKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_nullValue(Map<Int, Int> map, CacheContext context) {
    map.compute(context.absentKey(), (key, value) -> null);
    verifyNoInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void compute_absent_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().compute(context.absentKey(), (k, v) -> context.absentValue());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_present_differentValue(Map<Int, Int> map, CacheContext context) {
    map.compute(context.firstKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    map.compute(context.firstKey(), (key, value) -> value);
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void compute_present_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().compute(context.absentKey(), (k, v) -> context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void merge_absent(Map<Int, Int> map, CacheContext context) {
    map.merge(context.absentKey(), context.absentValue(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void merge_nullValue(Map<Int, Int> map, CacheContext context) {
    map.merge(context.firstKey(), context.absentValue(), (key, value) -> null);
    verifyNoInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void merge_absent_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().merge(context.absentKey(), context.absentValue(),
        (k, v) -> context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void merge_present_differentValue(Map<Int, Int> map, CacheContext context) {
    map.merge(context.firstKey(), context.absentKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void merge_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    map.merge(context.firstKey(), context.absentKey(), (key, value) -> value);
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void merge_present_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().merge(context.absentKey(), context.absentValue(),
        (k, v) -> context.absentValue().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
  void refresh_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refresh(context.absentKey()).join();

    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    if (context.isAsync()) {
      verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    }
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void refresh_absent_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.refresh(context.absentKey());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentKey().negate())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_expiryFails_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = cache.refresh(context.absentKey());
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(context.isAsync() ? 1 : 0);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void refresh_present(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refresh(context.firstKey()).join();

    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void refresh_present_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.refresh(context.absentKey());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentKey().negate());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_expiryFails_present(LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = cache.refresh(context.firstKey());
    future.complete(context.absentValue());
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(context.isAsync() ? 1 : 0);
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  void getIfPresentQuietly(Cache<Int, Int> cache, CacheContext context) {
    var original = cache.policy().expireVariably().orElseThrow()
        .getExpiresAfter(context.firstKey()).orElseThrow();
    var advancement = Duration.ofSeconds(30);
    context.ticker().advance(advancement);
    var value = cache.policy().getIfPresentQuietly(context.firstKey());
    assertThat(value).isNotNull();
    var current = cache.policy().expireVariably().orElseThrow()
        .getExpiresAfter(context.firstKey()).orElseThrow();
    assertThat(current.plus(advancement)).isEqualTo(original);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.DISABLED)
  void expireVariably_notEnabled(Cache<Int, Int> cache) {
    assertThat(cache.policy().expireVariably()).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void getExpiresAfter(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES)).isEmpty();
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES)).hasValue(1);

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(TimeUnit.HOURS.toNanos(1));
    cache.put(context.firstKey(), context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES)).hasValue(60);
    assertThat(expireAfterVar.getExpiresAfter(context.lastKey(), TimeUnit.MINUTES)).hasValue(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void getExpiresAfter_duration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey())).isEmpty();
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey())).hasValue(Duration.ofMinutes(1));

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(TimeUnit.HOURS.toNanos(1));
    cache.put(context.firstKey(), context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey())).hasValue(Duration.ofHours(1));
    assertThat(expireAfterVar.getExpiresAfter(context.lastKey())).hasValue(Duration.ofMinutes(1));
  }

  @SuppressWarnings("unchecked")
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void getExpiresAfter_absent(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Mockito.reset(context.expiry());
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
    verifyNoInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      refreshAfterWrite = Expire.ONE_MINUTE)
  void getExpiresAfter_expired(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
          refreshAfterWrite = Expire.ONE_MINUTE)
  void getExpiresAfter_async(AsyncCache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()).orElseThrow())
        .isEqualTo(Duration.ofNanos(Async.ASYNC_EXPIRY));

    future.complete(context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()).orElseThrow())
        .isEqualTo(context.expiryTime().duration());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void setExpiresAfter_negative(
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var duration = Duration.ofMinutes(-2);
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.setExpiresAfter(context.absentKey(), duration));
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.ACCESS)
  void setExpiresAfter_excessive(
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), ChronoUnit.FOREVER.getDuration());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void setExpiresAfter(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), 2, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES)).hasValue(2);

    expireAfterVar.setExpiresAfter(context.absentKey(), 4, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES)).isEmpty();

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void setExpiresAfter_duration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), Duration.ofMinutes(2));
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey())).hasValue(Duration.ofMinutes(2));

    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(4));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey())).isEmpty();

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void setExpiresAfter_absent(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(1));
    context.ticker().advance(Duration.ofSeconds(30));
    cache.cleanUp();
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
          refreshAfterWrite = Expire.ONE_MINUTE)
  void setExpiresAfter_expired(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(1));
    cache.cleanUp();
    assertThat(cache).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
          refreshAfterWrite = Expire.ONE_MINUTE)
  void setExpiresAfter_async(AsyncCache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()).orElseThrow())
        .isEqualTo(Duration.ofNanos(Async.ASYNC_EXPIRY));

    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(1));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()).orElseThrow())
        .isEqualTo(Duration.ofNanos(Async.ASYNC_EXPIRY));

    future.complete(context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()).orElseThrow())
        .isEqualTo(context.expiryTime().duration());

    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(2));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey()).orElseThrow())
        .isEqualTo(Duration.ofMinutes(2));
  }

  /* --------------- Policy: putIfAbsent --------------- */

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void putIfAbsent_nullKey(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(nullKey(), Int.valueOf(2), 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void putIfAbsent_nullValue(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), nullValue(), 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void putIfAbsent_nullTimeUnit(VarExpiration<Int, Int> expireAfterVar) {
    TimeUnit unit = nullRef();
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), 3, unit));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void putIfAbsent_negativeDuration(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), -10, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void putIfAbsent_nullDuration(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), nullDuration()));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void putIfAbsent_excessiveDuration(
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldValue = expireAfterVar.putIfAbsent(context.absentKey(),
        context.absentValue(), ChronoUnit.FOREVER.getDuration());
    assertThat(oldValue).isNull();
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void putIfAbsent_insert(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.absentKey();
    Int value = context.absentValue();
    Int result = expireAfterVar.putIfAbsent(key, value, Duration.ofMinutes(2));
    assertThat(result).isNull();

    assertThat(cache).containsEntry(key, value);
    assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(Duration.ofMinutes(2));

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void putIfAbsent_insert_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().putIfAbsent(context.absentKey(), context.absentValue());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void putIfAbsent_present(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    Int result = expireAfterVar.putIfAbsent(key, value, Duration.ofMinutes(2));
    assertThat(result).isEqualTo(context.original().get(key));

    assertThat(cache).containsEntry(key, context.original().get(key));
    assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(Duration.ofMinutes(1));

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.MOCKITO)
  void putIfAbsent_present_expired(Cache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenReturn(Expire.ONE_MINUTE.timeNanos());
    cache.put(context.absentKey(), context.absentValue());

    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenReturn(Long.MIN_VALUE);
    cache.asMap().putIfAbsent(context.absentKey(), context.absentKey().negate());

    context.ticker().advance(Duration.ofSeconds(2));
    context.cleanUp();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
    assertThat(cache).isEmpty();
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
  void putIfAbsent_incomplete(AsyncCache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var done = new AtomicBoolean();
    var started = new AtomicBoolean();
    var future = new CompletableFuture<@Nullable Int>();
    var writer = new AtomicReference<@Nullable Thread>();

    cache.put(context.absentKey(), future);
    ConcurrentTestHarness.execute(() -> {
      writer.set(Thread.currentThread());
      started.set(true);
      var result = expireAfterVar.putIfAbsent(context.absentKey(),
          context.absentValue(), Duration.ofDays(1));
      assertThat(result).isNull();
      done.set(true);
    });
    await().untilTrue(started);
    var threadState = EnumSet.of(BLOCKED, WAITING);
    await().until(() -> {
      var thread = writer.get();
      return (thread != null) && threadState.contains(thread.getState());
    });
    future.complete(null);
    await().untilTrue(done);
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @Nested @Isolated
  final class IsolatedTest {

    @ParameterizedTest
    @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
    void putIfAbsent_incomplete_null(AsyncCache<Int, @Nullable Int> cache,
        CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
      var computeThread = new AtomicReference<@Nullable Thread>();
      var threadState = EnumSet.of(BLOCKED, WAITING);
      var future1 = new CompletableFuture<@Nullable Int>();
      var future2 = new CompletableFuture<@Nullable Int>();
      cache.put(context.absentKey(), future1);
      ConcurrentTestHarness.execute(() -> {
        computeThread.set(Thread.currentThread());
        var result = cache.asMap().computeIfPresent(context.absentKey(), (k, f) -> {
          f.join();
          return future2;
        });
        assertThat(result).isNotNull();
      });
      await().until(() -> {
        var thread = computeThread.get();
        return (thread != null) && threadState.contains(thread.getState());
      });

      var putIfAbsentThread = new AtomicReference<@Nullable Thread>();
      var endPutIfAbsent = new AtomicBoolean();
      ConcurrentTestHarness.execute(() -> {
        putIfAbsentThread.set(Thread.currentThread());
        var result = expireAfterVar.putIfAbsent(context.absentKey(),
            context.absentValue(), Duration.ofDays(1));
        assertThat(result).isNull();
        endPutIfAbsent.set(true);
      });
      await().until(() -> {
        var thread = putIfAbsentThread.get();
        return (thread != null) && threadState.contains(thread.getState());
      });
      future1.complete(null);

      // LocalAsyncCache, LocalCache, and RemovalListener
      await().until(() -> future2.getNumberOfDependents() >= 2);

      future2.complete(null);
      await().untilTrue(endPutIfAbsent);
      assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    }
  }

  /* --------------- Policy: put --------------- */

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void put_nullKey(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(nullKey(), Int.valueOf(2), 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void put_nullValue(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(Int.valueOf(1), nullValue(), 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void put_nullTimeUnit(VarExpiration<Int, Int> expireAfterVar) {
    TimeUnit unit = nullRef();
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), 3, unit));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void put_negativeDuration(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), -10, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void put_excessiveDuration(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldValue = expireAfterVar.put(context.absentKey(),
        context.absentValue(), ChronoUnit.FOREVER.getDuration());
    assertThat(oldValue).isNull();
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void put_nullDuration(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), nullDuration()));
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void put_insert(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.absentKey();
    Int value = context.absentValue();
    Int oldValue = expireAfterVar.put(key, value, Duration.ofMinutes(2));
    assertThat(oldValue).isNull();

    assertThat(cache).containsEntry(key, value);
    assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(Duration.ofMinutes(2));

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  void put_replace(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    Int oldValue = expireAfterVar.put(key, value, Duration.ofMinutes(2));
    assertThat(oldValue).isEqualTo(context.original().get(key));

    assertThat(cache).containsEntry(key, value);
    assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(Duration.ofMinutes(2));

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  /* --------------- Policy: compute --------------- */

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS, removalListener = {Listener.DISABLED, Listener.REJECTING})
  void compute_nullKey(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.compute(nullKey(), (key, value) -> key.negate(), Duration.ZERO));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS, removalListener = {Listener.DISABLED, Listener.REJECTING})
  void compute_nullMappingFunction(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.compute(Int.valueOf(1), nullBiFunction(), Duration.ZERO));
  }

  @CheckNoStats
  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS, removalListener = {Listener.DISABLED, Listener.REJECTING})
  void compute_negativeDuration(VarExpiration<Int, Int> expireAfterVar) {
    var duration = Duration.ofMinutes(-1);
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.compute(Int.valueOf(1), (key, value) -> key.negate(), duration));
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void compute_excessiveDuration(
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.absentKey(),
        (k, v) -> context.absentValue(), ChronoUnit.FOREVER.getDuration());
    assertThat(value).isNotNull();
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void compute_remove(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(expireAfterVar.compute(key, (k, v) -> {
        assertThat(v).isNotNull();
        return null;
      }, Duration.ofDays(1))).isNull();
      removed.put(key, context.original().get(key));
    }

    verifyNoInteractions(context.expiry());
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).stats().hits(0).misses(0).success(0).failures(count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @ParameterizedTest
  @SuppressWarnings("CheckReturnValue")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void compute_recursive(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var mappingFunction = new BiFunction<Int, Int, @Nullable Int>() {
      @Override public @Nullable Int apply(Int key, Int value) {
        return expireAfterVar.compute(key, this, Duration.ofDays(1));
      }
    };
    var error = assertThrows(Throwable.class, () ->
        expireAfterVar.compute(context.absentKey(), mappingFunction, Duration.ofDays(1)));
    assertThat(error.getClass()).isAnyOf(StackOverflowError.class, IllegalStateException.class);
  }

  @ParameterizedTest
  @SuppressWarnings("CheckReturnValue")
  @CacheSpec(expiry = CacheExpiry.ACCESS, population = Population.EMPTY)
  void compute_pingpong(VarExpiration<Int, Int> expireAfterVar) {
    var key1 = Int.valueOf(1);
    var key2 = Int.valueOf(2);
    var mappingFunction = new BiFunction<Int, Int, @Nullable Int>() {
      @Override public @Nullable Int apply(Int key, Int value) {
        return expireAfterVar.compute(key.equals(key1) ? key2 : key1, this, Duration.ofDays(1));
      }
    };
    var error = assertThrows(Throwable.class, () ->
        expireAfterVar.compute(key1, mappingFunction, Duration.ofDays(1)));
    assertThat(error.getClass()).isAnyOf(StackOverflowError.class, IllegalStateException.class);
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO)
  void compute_error(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalStateException.class, () -> {
      expireAfterVar.compute(context.absentKey(),
          (key, value) -> { throw new IllegalStateException(); }, Duration.ofDays(1));
    });

    verifyNoInteractions(context.expiry());
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);

    Int result = expireAfterVar.compute(context.absentKey(),
        (k, v) -> k.negate(), Duration.ofDays(1));
    assertThat(result).isEqualTo(context.absentKey().negate());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void compute_absent_nullValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int result = expireAfterVar.compute(context.absentKey(),
        (key, value) -> null, Duration.ofDays(1));
    verifyNoInteractions(context.expiry());

    assertThat(result).isNull();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void compute_absent(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var duration = context.expiryTime().duration().dividedBy(2);

    Int result = expireAfterVar.compute(context.absentKey(),
        (key, value) -> context.absentValue(), duration);
    verifyNoInteractions(context.expiry());
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey())).hasValue(duration);

    assertThat(result).isEqualTo(context.absentValue());
    assertThat(cache).hasSize(1 + context.initialSize());
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO)
  void compute_absent_expires(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.absentKey(),
        (k, v) -> context.absentValue(), Duration.ofMinutes(1));
    assertThat(value).isNotNull();
    context.ticker().advance(Duration.ofMinutes(5));
    context.cleanUp();

    verifyNoInteractions(context.expiry());
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO)
  void compute_absent_expiresImmediately(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.absentKey(),
        (k, v) -> context.absentValue(), Duration.ZERO);
    assertThat(value).isNotNull();
    assertThat(cache).doesNotContainKey(context.absentKey());
    context.ticker().advance(Duration.ofMinutes(1));
    context.cleanUp();

    verifyNoInteractions(context.expiry());
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  void compute_absent_expiresLater(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.absentKey(),
        (k, v) -> context.absentValue(), Duration.ofMinutes(5));
    assertThat(value).isNotNull();
    context.ticker().advance(Duration.ofMinutes(3));
    context.cleanUp();

    assertThat(cache).hasSize(1);
    verifyNoInteractions(context.expiry());
    assertThat(cache).containsKey(context.absentKey());
    assertThat(context).notifications().withCause(EXPIRED)
          .contains(context.original()).exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void compute_sameValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var duration = context.expiryTime().duration().dividedBy(2);
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = intern(new Int(context.original().get(key)));
      Int result = expireAfterVar.compute(key, (k, v) -> value, duration);

      replaced.put(key, value);
      assertThat(result).isSameInstanceAs(value);
      assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(duration);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    verifyNoInteractions(context.expiry());
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache.asMap()).containsAtLeastEntriesIn(replaced);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = Listener.REJECTING)
  void compute_sameInstance(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var duration = context.expiryTime().duration().dividedBy(2);
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      Int result = expireAfterVar.compute(key, (k, v) -> value, duration);

      assertThat(result).isSameInstanceAs(value);
      assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(duration);
    }
    verifyNoInteractions(context.expiry());
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).notifications().isEmpty();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void compute_differentValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var replaced = new HashMap<Int, Int>();
    var duration = context.expiryTime().duration().dividedBy(2);
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = requireNonNull(context.original().get(key));
      Int result = expireAfterVar.compute(key, (k, v) -> value.negate(), duration);

      replaced.put(key, value);
      assertThat(result).isEqualTo(key);
      assertThat(expireAfterVar.getExpiresAfter(key)).hasValue(duration);
    }
    verifyNoInteractions(context.expiry());
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, key);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void compute_present_expires(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.firstKey(), (k, v) -> {
      assertThat(v).isNotNull();
      return context.absentValue();
    }, Duration.ofMinutes(1));
    assertThat(value).isNotNull();
    context.ticker().advance(Duration.ofMinutes(5));
    context.cleanUp();

    verifyNoInteractions(context.expiry());
    assertThat(cache).hasSize(context.initialSize() - 1);
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()));
    assertThat(context).removalNotifications().withCause(EXPIRED)
        .contains(context.firstKey(), context.absentValue());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(context).evictionNotifications().withCause(EXPIRED)
        .contains(context.firstKey(), context.absentValue()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void compute_present_expiresImmediately(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.firstKey(), (k, v) -> {
      assertThat(v).isNotNull();
      return context.absentValue();
    }, Duration.ZERO);
    assertThat(value).isNotNull();
    assertThat(cache).doesNotContainKey(context.firstKey());
    context.ticker().advance(Duration.ofMinutes(1));
    context.cleanUp();

    verifyNoInteractions(context.expiry());
    assertThat(cache).hasSize(context.initialSize() - 1);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()));
    assertThat(context).removalNotifications().withCause(EXPIRED)
        .contains(context.firstKey(), context.absentValue());
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(context).evictionNotifications().withCause(EXPIRED)
        .contains(context.firstKey(), context.absentValue()).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void compute_present_expiresLater(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.firstKey(), (k, v) -> {
      assertThat(v).isNotNull();
      return context.absentValue();
    }, Duration.ofMinutes(5));
    assertThat(value).isNotNull();
    context.ticker().advance(Duration.ofMinutes(3));
    context.cleanUp();

    assertThat(cache).hasSize(1);
    verifyNoInteractions(context.expiry());
    assertThat(cache).containsKey(context.firstKey());

    var expired = new HashMap<>(context.original());
    expired.remove(context.firstKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()));
    assertThat(context).removalNotifications().withCause(EXPIRED).contains(expired);
    assertThat(context).removalNotifications().hasSize(context.initialSize());
    assertThat(context).evictionNotifications().withCause(EXPIRED).contains(expired).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.MOCKITO, population = Population.EMPTY,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void compute_async_null(AsyncCache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<@Nullable Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = expireAfterVar.compute(key, (k, oldValue) -> newValue, Duration.ofDays(1));
      assertThat(result).isEqualTo(newValue);
      done.set(true);
    });
    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    verifyNoInteractions(context.expiry());
    assertThat(cache).containsEntry(key, newValue);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void compute_writeTime(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.firstKey();
    Int value = context.absentValue();

    var computed = expireAfterVar.compute(key, (k, v) -> {
      context.ticker().advance(Duration.ofMinutes(5));
      return value;
    }, context.expiryTime().duration());
    assertThat(computed).isNotNull();

    context.cleanUp();
    assertThat(cache).hasSize(1);
    assertThat(cache).containsKey(key);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void compute_absent_error(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThrows(IllegalStateException.class, () -> {
      expireAfterVar.compute(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); }, Duration.ofDays(1));
    });
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey())).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, compute = Compute.SYNC,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  void compute_present_error(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().advance(Duration.ofSeconds(30));
    var variable = expireAfterVar.getExpiresAfter(context.firstKey());
    assertThrows(IllegalStateException.class, () -> {
      expireAfterVar.compute(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); }, Duration.ofDays(1));
    });
    assertThat(variable).isEqualTo(expireAfterVar.getExpiresAfter(context.firstKey()));
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_absent_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> map.compute(context.absentKey(), (k, v) -> k));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_absent_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.asMap().compute(context.absentKey(), (k, v) -> future);
    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_present_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.compute(context.firstKey(), (k, v) -> requireNonNull(v).negate()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_present_expiryFails_async(AsyncCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    var future = new CompletableFuture<Int>();
    cache.asMap().compute(context.firstKey(), (k, v) -> future);
    future.complete(context.firstKey());
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpirationException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  void compute_incomplete(AsyncCache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var done = new AtomicBoolean();
    var started = new AtomicBoolean();
    var writer = new AtomicReference<@Nullable Thread>();
    var future = new CompletableFuture<Int>() {
      @Override public boolean isDone() {
        return done.get() && super.isDone();
      }
    };
    cache.asMap().compute(context.firstKey(), (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        writer.set(Thread.currentThread());
        started.set(true);
        var result = expireAfterVar.compute(context.firstKey(), (k1, v1) ->
            context.absentValue(), Duration.ofDays(1));
        assertThat(result).isEqualTo(context.absentValue());
        done.set(true);
      });
      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> {
        var thread = writer.get();
        return (thread != null) && threadState.contains(thread.getState());
      });
      return future;
    });
    future.completeExceptionally(new Exception());
    await().untilTrue(done);
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
  }

  /* --------------- Policy: oldest --------------- */

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldest_unmodifiable(VarExpiration<Int, Int> expireAfterVar) {
    var results = expireAfterVar.oldest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldest_negative(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () -> expireAfterVar.oldest(-1));
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldest_zero(VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.oldest(0)).isExhaustivelyEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.ACCESS)
  void oldest_partial(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    int count = context.original().size() / 2;
    assertThat(expireAfterVar.oldest(count)).hasSize(count);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  void oldest_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet()).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldest_snapshot(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_null(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () -> expireAfterVar.oldest(nullFunction()));
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_nullResult(VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.oldest(stream -> null);
    assertThat(result).isNull();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_throwsException(VarExpiration<Int, Int> expireAfterVar) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        expireAfterVar.oldest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(ConcurrentModificationException.class, () -> {
      expireAfterVar.oldest(stream -> {
        context.ticker().advance(Duration.ofNanos(1));
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_closed(VarExpiration<Int, Int> expireAfterVar) {
    var stream = expireAfterVar.oldest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.oldest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void oldestFunc_full(Cache<Int, Int> cache, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.oldest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      expiry = CacheExpiry.ACCESS)
  void oldestFunc_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(stream -> stream.map(Map.Entry::getKey)
        .collect(toImmutableList()));
    assertThat(oldest).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = VARIABLE, population = {Population.PARTIAL, Population.FULL})
  void oldestFunc_metadata(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var entries = expireAfterVar.oldest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.ACCESS, expiryTime = Expire.ONE_MINUTE)
  void oldestFunc_metadata_expiresInTraversal(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(30));
    var entries = expireAfterVar.oldest(stream -> stream.collect(toImmutableList()));
    assertThat(context.cache()).hasSize(context.initialSize());
    assertThat(entries).hasSize(1);
  }

  /* --------------- Policy: youngest --------------- */

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngest_unmodifiable(VarExpiration<Int, Int> expireAfterVar) {
    var results = expireAfterVar.youngest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngest_negative(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () -> expireAfterVar.youngest(-1));
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngest_zero(VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.youngest(0)).isExhaustivelyEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.ACCESS)
  void youngest_partial(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    int count = context.original().size() / 2;
    assertThat(expireAfterVar.youngest(count)).hasSize(count);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  void youngest_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest.keySet()).containsExactlyElementsIn(expected).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngest_snapshot(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_null(VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () -> expireAfterVar.youngest(nullFunction()));
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_nullResult(VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.youngest(stream -> null);
    assertThat(result).isNull();
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_throwsException(VarExpiration<Int, Int> expireAfterVar) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        expireAfterVar.youngest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(ConcurrentModificationException.class, () -> {
      expireAfterVar.youngest(stream -> {
        context.ticker().advance(Duration.ofNanos(1));
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_closed(VarExpiration<Int, Int> expireAfterVar) {
    var stream = expireAfterVar.youngest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.youngest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @ParameterizedTest
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  void youngestFunc_full(Cache<Int, Int> cache, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.youngest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @ParameterizedTest
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING }, expiry = CacheExpiry.ACCESS)
  void youngestFunc_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(
        stream -> stream.map(Map.Entry::getKey).collect(toImmutableList()));
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest).containsExactlyElementsIn(expected).inOrder();
  }

  @ParameterizedTest
  @CacheSpec(mustExpireWithAnyOf = VARIABLE, population = Population.FULL)
  void youngestFunc_metadata(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var entries = expireAfterVar.youngest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.ACCESS, expiryTime = Expire.ONE_MINUTE)
  void youngestFunc_metadata_expiresInTraversal(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(30));
    var entries = expireAfterVar.youngest(stream -> stream.collect(toImmutableList()));
    assertThat(context.cache()).hasSize(context.initialSize());
    assertThat(entries).hasSize(1);
  }

  /* --------------- Expiry --------------- */

  @Test
  void expiry_creating_null() {
    var expiry = Expiry.creating((key, value) -> nullDuration());
    assertThrows(NullPointerException.class, () -> expiry.expireAfterCreate(1, 2, 3));
    assertThat(expiry.expireAfterUpdate(1, 2, 3, 99)).isEqualTo(99);
    assertThat(expiry.expireAfterRead(1, 2, 3, 99)).isEqualTo(99);
  }

  @Test
  void expiry_creating() {
    var expiry = Expiry.creating((Integer key, Integer value) -> Duration.ofSeconds(key + value));
    assertThat(expiry.expireAfterCreate(1, 2, 3)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(expiry.expireAfterUpdate(1, 2, 3, 99)).isEqualTo(99);
    assertThat(expiry.expireAfterRead(1, 2, 3, 99)).isEqualTo(99);
  }

  @Test
  void expiry_creating_saturating() {
    var expiry = Expiry.creating((Long key, Long value) -> Duration.ofNanos(key));
    assertThat(expiry.expireAfterCreate(Long.MIN_VALUE, 2L, 3)).isEqualTo(Long.MIN_VALUE);
    assertThat(expiry.expireAfterCreate(Long.MAX_VALUE, 2L, 3)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void expiry_creating_serialize() {
    SerializableBiFunction creator = (key, value) -> Duration.ofNanos(key);

    var expiry = Expiry.creating(creator);
    var reserialized = SerializableTester.reserialize(expiry);
    assertThat(reserialized.expireAfterCreate(1, 2, 3)).isEqualTo(1L);
    assertThat(expiry.expireAfterUpdate(1, 2, 3, 99)).isEqualTo(99);
    assertThat(expiry.expireAfterRead(1, 2, 3, 99)).isEqualTo(99);
  }

  @Test
  void expiry_writing_null() {
    var expiry = Expiry.writing((Integer key, Integer value) -> nullDuration());
    assertThrows(NullPointerException.class, () -> expiry.expireAfterCreate(1, 2, 3));
    assertThrows(NullPointerException.class, () -> expiry.expireAfterUpdate(1, 2, 3, 99));
    assertThat(expiry.expireAfterRead(1, 2, 3, 99)).isEqualTo(99);
  }

  @Test
  void expiry_writing() {
    var expiry = Expiry.writing((Integer key, Integer value) -> Duration.ofSeconds(key + value));
    assertThat(expiry.expireAfterCreate(1, 2, 3)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(expiry.expireAfterUpdate(1, 2, 3, 99)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(expiry.expireAfterRead(1, 2, 3, 99)).isEqualTo(99);
  }

  @Test
  void expiry_writing_saturating() {
    var expiry = Expiry.writing((Long key, Long value) -> Duration.ofNanos(key));
    assertThat(expiry.expireAfterCreate(Long.MIN_VALUE, 2L, 3)).isEqualTo(Long.MIN_VALUE);
    assertThat(expiry.expireAfterCreate(Long.MAX_VALUE, 2L, 3)).isEqualTo(Long.MAX_VALUE);
    assertThat(expiry.expireAfterUpdate(Long.MIN_VALUE, 2L, 3, 4)).isEqualTo(Long.MIN_VALUE);
    assertThat(expiry.expireAfterUpdate(Long.MAX_VALUE, 2L, 3, 4)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void expiry_writing_serialize() {
    SerializableBiFunction writer = (key, value) -> Duration.ofSeconds(key + value);

    var expiry = Expiry.writing(writer);
    var reserialized = SerializableTester.reserialize(expiry);
    assertThat(reserialized.expireAfterCreate(1, 2, 3)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(reserialized.expireAfterUpdate(1, 2, 3, 99))
        .isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(reserialized.expireAfterRead(1, 2, 3, 99)).isEqualTo(99);
  }

  @Test
  void expiry_accessing_null() {
    var expiry = Expiry.accessing((Integer key, Integer value) -> nullDuration());
    assertThrows(NullPointerException.class, () -> expiry.expireAfterCreate(1, 2, 3));
    assertThrows(NullPointerException.class, () -> expiry.expireAfterUpdate(1, 2, 3, 99));
    assertThrows(NullPointerException.class, () -> expiry.expireAfterRead(1, 2, 3, 99));
  }

  @Test
  void expiry_accessing() {
    var expiry = Expiry.accessing((Integer key, Integer value) -> Duration.ofSeconds(key + value));
    assertThat(expiry.expireAfterCreate(1, 2, 3)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(expiry.expireAfterUpdate(1, 2, 3, 99)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(expiry.expireAfterRead(1, 2, 3, 99)).isEqualTo(Duration.ofSeconds(3).toNanos());
  }

  @Test
  void expiry_accessing_saturating() {
    var expiry = Expiry.accessing((Long key, Long value) -> Duration.ofNanos(key));
    assertThat(expiry.expireAfterCreate(Long.MIN_VALUE, 2L, 3)).isEqualTo(Long.MIN_VALUE);
    assertThat(expiry.expireAfterCreate(Long.MAX_VALUE, 2L, 3)).isEqualTo(Long.MAX_VALUE);
    assertThat(expiry.expireAfterUpdate(Long.MIN_VALUE, 2L, 3, 4)).isEqualTo(Long.MIN_VALUE);
    assertThat(expiry.expireAfterUpdate(Long.MAX_VALUE, 2L, 3, 4)).isEqualTo(Long.MAX_VALUE);
    assertThat(expiry.expireAfterRead(Long.MIN_VALUE, 2L, 3, 4)).isEqualTo(Long.MIN_VALUE);
    assertThat(expiry.expireAfterRead(Long.MAX_VALUE, 2L, 3, 4)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void expiry_accessing_serialize() {
    SerializableBiFunction accessor = (key, value) -> Duration.ofSeconds(key + value);

    var expiry = Expiry.accessing(accessor);
    var reserialized = SerializableTester.reserialize(expiry);
    assertThat(reserialized.expireAfterCreate(1, 2, 3)).isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(reserialized.expireAfterUpdate(1, 2, 3, 99))
        .isEqualTo(Duration.ofSeconds(3).toNanos());
    assertThat(reserialized.expireAfterRead(1, 2, 3, 99))
        .isEqualTo(Duration.ofSeconds(3).toNanos());
  }

  @FunctionalInterface
  private interface SerializableBiFunction
    extends BiFunction<Integer, Integer, Duration>, Serializable {}

  private static final class ExpirationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("unused")
    ExpirationException() {
      super(/* message= */ null, /* cause= */ null,
          /* enableSuppression= */ false, /* writableStackTrace= */ false);
    }
  }
}
