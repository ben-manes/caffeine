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

import static com.github.benmanes.caffeine.cache.BoundedLocalCache.MAXIMUM_EXPIRY;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.util.function.Function.identity;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;

/**
 * The test cases for caches that support the variable expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterVarTest {

  @Test(dataProvider = "caches")
  @CacheSpec(expiryTime = Expire.FOREVER,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  public void expiry_bounds(Cache<Int, Int> cache, CacheContext context) {
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).isNotNull();

    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void get(LoadingCache<Int, Int> cache, CacheContext context) {
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void getAll_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var results = cache.getAll(context.firstMiddleLastKeys());
    assertThat(results).isNotEmpty();

    verify(context.expiry(), times(3)).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  public void getAll_absent(LoadingCache<Int, Int> cache, CacheContext context) {
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

  /* --------------- Create --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void put_replace(Cache<Int, Int> cache, CacheContext context) {
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void put_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = context.absentValue().asFuture();
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void put_replace(Map<Int, Int> map, CacheContext context) {
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void putAll_replace(Cache<Int, Int> cache, CacheContext context) {
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void replace_updated(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.replace(context.firstKey(), context.absentValue())).isNotNull();
    context.ticker().advance(Duration.ofSeconds(30));

    context.cleanUp();
    assertThat(map).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void replace_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.replace(context.firstKey(), context.absentValue()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiryTime = Expire.ONE_MINUTE, expiry = CacheExpiry.CREATE)
  public void replaceConditionally_updated(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.replace(key, context.original().get(key), context.absentValue())).isTrue();
    context.ticker().advance(Duration.ofSeconds(30));

    context.cleanUp();
    assertThat(map).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void replaceConditionally_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    Int oldValue = context.original().get(context.firstKey());
    assertThrows(ExpirationException.class, () ->
        map.replace(context.firstKey(), oldValue, context.absentValue()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  /* --------------- Exceptional --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void getIfPresent_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> cache.getIfPresent(context.firstKey()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void get_expiryFails_create(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> cache.get(context.absentKey(), identity()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void get_expiryFails_read(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> cache.get(context.firstKey(), identity()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void getAllPresent_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.getAllPresent(context.firstMiddleLastKeys()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void put_insert_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofHours(1));
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        cache.put(context.absentKey(), context.absentValue()));
    context.ticker().advance(Duration.ofHours(-1));
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void put_insert_replaceExpired_expiryFails(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void put_update_expiryFails(Cache<Int, Int> cache,
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

  /* --------------- Compute --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfAbsent_absent(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.absentKey(), identity());
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfAbsent_nullValue(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.absentKey(), key -> null);
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfAbsent_present(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.firstKey(), identity());
    verify(context.expiry()).expireAfterRead(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfAbsent_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.computeIfAbsent(context.absentKey(), identity()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfPresent_absent(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.absentKey(), (key, value) -> value);
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfPresent_nullValue(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.firstKey(), (key, value) -> null);
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfPresent_present_differentValue(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.firstKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfPresent_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(context.firstKey(), (key, value) -> value);
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void computeIfPresent_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.computeIfPresent(context.firstKey(), (k, v) -> v.negate()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void compute_absent(Map<Int, Int> map, CacheContext context) {
    map.compute(context.absentKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void compute_nullValue(Map<Int, Int> map, CacheContext context) {
    map.compute(context.absentKey(), (key, value) -> null);
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void compute_present_differentValue(Map<Int, Int> map, CacheContext context) {
    map.compute(context.firstKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void compute_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    map.compute(context.firstKey(), (key, value) -> value);
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void merge_absent(Map<Int, Int> map, CacheContext context) {
    map.merge(context.absentKey(), context.absentValue(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void merge_nullValue(Map<Int, Int> map, CacheContext context) {
    map.merge(context.firstKey(), context.absentValue(), (key, value) -> null);
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void merge_present_differentValue(Map<Int, Int> map, CacheContext context) {
    map.merge(context.firstKey(), context.absentKey(), (key, value) -> context.absentValue());
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void merge_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    map.merge(context.firstKey(), context.absentKey(), (key, value) -> value);
    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO)
  public void refresh_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refresh(context.absentKey()).join();

    verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
    if (context.isAsync()) {
      verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    }
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void refresh_present(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refresh(context.firstKey()).join();

    verify(context.expiry()).expireAfterUpdate(any(), any(), anyLong(), anyLong());
    verifyNoMoreInteractions(context.expiry());
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  public void getIfPresentQuietly(Cache<Int, Int> cache, CacheContext context) {
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.DISABLED)
  public void expireVariably_notEnabled(Cache<Int, Int> cache) {
    assertThat(cache.policy().expireVariably()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES)).isEmpty();
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES)).hasValue(1);

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(TimeUnit.HOURS.toNanos(1));
    cache.put(context.firstKey(), context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES)).hasValue(60);
    assertThat(expireAfterVar.getExpiresAfter(context.lastKey(), TimeUnit.MINUTES)).hasValue(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter_duration(Cache<Int, Int> cache,
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
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter_absent(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Mockito.reset(context.expiry());
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
    verifyNoInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      refreshAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter_expired(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void setExpiresAfter_negative(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var duration = Duration.ofMinutes(-2);
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.setExpiresAfter(context.absentKey(), duration));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void setExpiresAfter_excessive(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), ChronoUnit.FOREVER.getDuration());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), 2, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES)).hasValue(2);

    expireAfterVar.setExpiresAfter(context.absentKey(), 4, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES)).isEmpty();

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter_duration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), Duration.ofMinutes(2));
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey())).hasValue(Duration.ofMinutes(2));

    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(4));
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey())).isEmpty();

    context.ticker().advance(Duration.ofSeconds(90));
    cache.cleanUp();
    assertThat(cache).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter_absent(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(1));
    context.ticker().advance(Duration.ofSeconds(30));
    cache.cleanUp();
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
          refreshAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_expired(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    expireAfterVar.setExpiresAfter(context.absentKey(), Duration.ofMinutes(1));
    cache.cleanUp();
    assertThat(cache).isEmpty();
  }

  /* --------------- Policy: putIfAbsent --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullKey(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(null, Int.valueOf(2), 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), null, 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullTimeUnit(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), 3, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_negativeDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), -10, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_nullDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.putIfAbsent(Int.valueOf(1), Int.valueOf(2), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_excessiveDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldValue = expireAfterVar.putIfAbsent(context.absentKey(),
        context.absentValue(), ChronoUnit.FOREVER.getDuration());
    assertThat(oldValue).isNull();
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_insert(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_present(Cache<Int, Int> cache,
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

  /* --------------- Policy: put --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullKey(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(null, Int.valueOf(2), 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(Int.valueOf(1), null, 3, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullTimeUnit(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), 3, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_negativeDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), -10, TimeUnit.SECONDS));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_excessiveDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldValue = expireAfterVar.put(context.absentKey(),
        context.absentValue(), ChronoUnit.FOREVER.getDuration());
    assertThat(oldValue).isNull();
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_nullDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.put(Int.valueOf(1), Int.valueOf(2), null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_insert(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void put_replace(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS, removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void compute_nullKey(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.compute(null, (key, value) -> key.negate(), Duration.ZERO));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS, removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void compute_nullMappingFunction(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () ->
        expireAfterVar.compute(Int.valueOf(1), null, Duration.ZERO));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS, removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void compute_negativeDuration(
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var duration = Duration.ofMinutes(-1);
    assertThrows(IllegalArgumentException.class, () ->
        expireAfterVar.compute(Int.valueOf(1), (key, value) -> key.negate(), duration));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void compute_excessiveDuration(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var value = expireAfterVar.compute(context.absentKey(),
        (k, v) -> context.absentValue(), ChronoUnit.FOREVER.getDuration());
    assertThat(value).isNotNull();
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.NANOSECONDS))
        .hasValue(MAXIMUM_EXPIRY);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_remove(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @SuppressWarnings("CheckReturnValue")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void compute_recursive(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      @Override public Int apply(Int key, Int value) {
        return expireAfterVar.compute(key, this, Duration.ofDays(1));
      }
    };
    try {
      expireAfterVar.compute(context.absentKey(), mappingFunction, Duration.ofDays(1));
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @SuppressWarnings("CheckReturnValue")
  @CacheSpec(expiry = CacheExpiry.ACCESS, population = Population.EMPTY)
  public void compute_pingpong(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var key1 = Int.valueOf(1);
    var key2 = Int.valueOf(2);
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      @Override public Int apply(Int key, Int value) {
        return expireAfterVar.compute(key.equals(key1) ? key2 : key1, this, Duration.ofDays(1));
      }
    };
    try {
      expireAfterVar.compute(key1, mappingFunction, Duration.ofDays(1));
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO)
  public void compute_error(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void compute_absent_nullValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int result = expireAfterVar.compute(context.absentKey(),
        (key, value) -> null, Duration.ofDays(1));
    verifyNoInteractions(context.expiry());

    assertThat(result).isNull();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void compute_absent(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO)
  public void compute_absent_expires(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO)
  public void compute_absent_expiresImmediately(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void compute_absent_expiresLater(Cache<Int, Int> cache,
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
  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameValue(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = Listener.REJECTING)
  public void compute_sameInstance(Cache<Int, Int> cache,
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
  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_differentValue(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var replaced = new HashMap<Int, Int>();
    var duration = context.expiryTime().duration().dividedBy(2);
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_present_expires(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_present_expiresImmediately(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_present_expiresLater(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.MOCKITO, population = Population.EMPTY,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void compute_async_null(AsyncCache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void compute_writeTime(Cache<Int, Int> cache,
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void compute_absent_error(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThrows(IllegalStateException.class, () -> {
      expireAfterVar.compute(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); }, Duration.ofDays(1));
    });
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey())).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, compute = Compute.SYNC,
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void compute_present_error(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().advance(Duration.ofSeconds(30));
    var variable = expireAfterVar.getExpiresAfter(context.firstKey());
    assertThrows(IllegalStateException.class, () -> {
      expireAfterVar.compute(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); }, Duration.ofDays(1));
    });
    assertThat(variable).isEqualTo(expireAfterVar.getExpiresAfter(context.firstKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void compute_absent_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () -> map.compute(context.absentKey(), (k, v) -> k));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  public void compute_present_expiryFails(Map<Int, Int> map, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpirationException.class);
    assertThrows(ExpirationException.class, () ->
        map.compute(context.firstKey(), (k, v) -> v.negate()));
    assertThat(map).containsExactlyEntriesIn(context.original());
  }

  /* --------------- Policy: oldest --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldest_unmodifiable(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var results = expireAfterVar.oldest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldest_negative(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () -> expireAfterVar.oldest(-1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldest_zero(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.oldest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void oldest_partial(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    int count = context.original().size() / 2;
    assertThat(expireAfterVar.oldest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void oldest_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet()).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldest_snapshot(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_null(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () -> expireAfterVar.oldest(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_nullResult(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.oldest(stream -> null);
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_throwsException(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        expireAfterVar.oldest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(ConcurrentModificationException.class, () -> {
      expireAfterVar.oldest(stream -> {
        context.ticker().advance(Duration.ofNanos(1));
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_closed(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var stream = expireAfterVar.oldest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.oldest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void oldestFunc_full(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.oldest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      expiry = CacheExpiry.ACCESS)
  public void oldestFunc_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var oldest = expireAfterVar.oldest(stream -> stream.map(Map.Entry::getKey)
        .collect(toImmutableList()));
    assertThat(oldest).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = VARIABLE, population = {Population.PARTIAL, Population.FULL})
  public void oldestFunc_metadata(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var entries = expireAfterVar.oldest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.ACCESS, expiryTime = Expire.ONE_MINUTE)
  public void oldestFunc_metadata_expiresInTraversal(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(30));
    var entries = expireAfterVar.oldest(stream -> stream.collect(toImmutableList()));
    assertThat(context.cache()).hasSize(context.initialSize());
    assertThat(entries).hasSize(1);
  }

  /* --------------- Policy: youngest --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngest_unmodifiable(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var results = expireAfterVar.youngest(Integer.MAX_VALUE);
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngest_negative(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(IllegalArgumentException.class, () -> expireAfterVar.youngest(-1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngest_zero(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThat(expireAfterVar.youngest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void youngest_partial(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    int count = context.original().size() / 2;
    assertThat(expireAfterVar.youngest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void youngest_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest.keySet()).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngest_snapshot(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_null(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(NullPointerException.class, () -> expireAfterVar.youngest(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_nullResult(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.youngest(stream -> null);
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_throwsException(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        expireAfterVar.youngest(stream -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    assertThrows(ConcurrentModificationException.class, () -> {
      expireAfterVar.youngest(stream -> {
        context.ticker().advance(Duration.ofNanos(1));
        cache.put(context.absentKey(), context.absentValue());
        throw assertThrows(ConcurrentModificationException.class, stream::count);
      });
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_closed(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var stream = expireAfterVar.youngest(identity());
    var exception = assertThrows(IllegalStateException.class, () -> stream.forEach(e -> {}));
    assertThat(exception).hasMessageThat().isEqualTo("source already consumed or closed");
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.youngest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expiry = CacheExpiry.ACCESS)
  public void youngestFunc_full(Cache<Int, Int> cache,
      CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var result = expireAfterVar.youngest(stream -> stream
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DISABLED, Listener.REJECTING }, expiry = CacheExpiry.ACCESS)
  public void youngestFunc_order(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var youngest = expireAfterVar.youngest(
        stream -> stream.map(Map.Entry::getKey).collect(toImmutableList()));
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = VARIABLE, population = Population.FULL)
  public void youngestFunc_metadata(CacheContext context, VarExpiration<Int, Int> expireAfterVar) {
    var entries = expireAfterVar.youngest(stream -> stream.collect(toImmutableList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.ACCESS, expiryTime = Expire.ONE_MINUTE)
  public void youngestFunc_metadata_expiresInTraversal(CacheContext context,
      VarExpiration<Int, Int> expireAfterVar) {
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(30));
    var entries = expireAfterVar.youngest(stream -> stream.collect(toImmutableList()));
    assertThat(context.cache()).hasSize(context.initialSize());
    assertThat(entries).hasSize(1);
  }

  static final class ExpirationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
