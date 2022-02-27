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

import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.Functions.identity;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.FixedExpiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.ExpireAfterWrite;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

/**
 * The test cases for caches that support the expire-after-write (time-to-live) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("PreferJavaTimeOverload")
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterWriteTest {

  /* --------------- Cache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expireAfterWrite = { Expire.DISABLED, Expire.ONE_MINUTE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey())).isNull();

    cache.cleanUp();
    assertThat(cache).isEmpty();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void get(Cache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // recreated

    cache.cleanUp();
    assertThat(cache).hasSize(1);

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void getAllPresent(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys())).isExhaustivelyEmpty();

    cache.cleanUp();
    assertThat(cache).isEmpty();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void getAll(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    var result1 = cache.getAll(List.of(context.firstKey(), context.absentKey()),
        keys -> Maps.asMap(keys, identity()));
    assertThat(result1).containsExactly(
        context.firstKey(), context.firstKey().negate(), context.absentKey(), context.absentKey());

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    var result2 = cache.getAll(List.of(context.firstKey(), context.absentKey()),
        keys -> Maps.asMap(keys, identity()));
    assertThat(result2).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(cache).hasSize(2);

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void get_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache).hasSize(1);
    assertThat(cache).containsEntry(context.absentKey(), context.absentKey().negate());

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  public void getAll_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(List.of(context.firstKey(), context.absentKey())))
        .containsExactly(context.firstKey(), context.firstKey().negate(),
            context.absentKey(), context.absentKey());

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.getAll(List.of(context.firstKey(), context.absentKey())))
        .containsExactly(context.firstKey(), context.firstKey(),
            context.absentKey(), context.absentKey());
    assertThat(cache).hasSize(2);

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- AsyncCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void getIfPresent(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey()).join();
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(cache).doesNotContainKey(context.lastKey());

    context.cleanUp();
    assertThat(cache).isEmpty();
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue())).isNotNull();

    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.lastKey(), context.absentValue())).isNull();

    assertThat(map).hasSize(1);
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void getIfPresentQuietly(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var original = expireAfterWrite.ageOf(context.firstKey()).get();
    var advancement = Duration.ofSeconds(30);
    context.ticker().advance(advancement);
    cache.policy().getIfPresentQuietly(context.firstKey());
    var current = expireAfterWrite.ageOf(context.firstKey()).get();
    assertThat(current.minus(advancement)).isEqualTo(original);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES)).isEqualTo(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter_duration(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_negative(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(Duration.ofMinutes(-2));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_excessive(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(ChronoUnit.FOREVER.getDuration());
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES)).isEqualTo(2);

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_duration(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(Duration.ofMinutes(2));
    assertThat(expireAfterWrite.getExpiresAfter()).isEqualTo(Duration.ofMinutes(2));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      expireAfterWrite = Expire.ONE_MINUTE)
  public void ageOf(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(0);
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(30);
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      expireAfterWrite = Expire.ONE_MINUTE)
  public void ageOf_duration(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    // Truncated to seconds to ignore the LSB (nanosecond) used for refreshAfterWrite's lock
    assertThat(expireAfterWrite.ageOf(context.firstKey()).get().toSeconds()).isEqualTo(0);
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey()).get().toSeconds()).isEqualTo(30);
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey())).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void ageOf_absent(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS })
  public void ageOf_expired(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  /* --------------- Policy: oldest --------------- */

  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.oldest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_zero(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.oldest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    int count = context.original().size() / 2;
    assertThat(expireAfterWrite.oldest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet()).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_null(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.oldest(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_nullResult(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.oldest(stream -> null);
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_throwsException(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var expected = new IllegalStateException();
    try {
      expireAfterWrite.oldest(stream -> { throw expected; });
      Assert.fail();
    } catch (IllegalStateException e) {
      assertThat(e).isSameInstanceAs(expected);
    }
  }

  @Test(dataProvider = "caches", expectedExceptions = ConcurrentModificationException.class)
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.oldest(stream -> {
      cache.put(context.absentKey(), context.absentValue());
      return stream.count();
    });
  }

  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = "source already consumed or closed")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_closed(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.oldest(stream -> stream).forEach(e -> {});
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.oldest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_full(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.oldest(stream -> stream
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  public void oldestFunc_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var oldest = expireAfterWrite.oldest(stream -> stream.map(Map.Entry::getKey).collect(toList()));
    assertThat(oldest).containsExactlyElementsIn(context.original().keySet()).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, population = {Population.PARTIAL, Population.FULL})
  public void oldestFunc_metadata(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var entries = expireAfterWrite.oldest(stream -> stream.collect(toList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }

  /* --------------- Policy: youngest --------------- */

  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.youngest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_zero(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    assertThat(expireAfterWrite.youngest(0)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    int count = context.original().size() / 2;
    assertThat(expireAfterWrite.youngest(count)).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest.keySet()).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Int, Int> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_null(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.youngest(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_nullResult(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.youngest(stream -> null);
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_throwsException(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var expected = new IllegalStateException();
    try {
      expireAfterWrite.youngest(stream -> { throw expected; });
      Assert.fail();
    } catch (IllegalStateException e) {
      assertThat(e).isSameInstanceAs(expected);
    }
  }

  @Test(dataProvider = "caches", expectedExceptions = ConcurrentModificationException.class)
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_concurrentModification(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.youngest(stream -> {
      cache.put(context.absentKey(), context.absentValue());
      return stream.count();
    });
  }

  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = "source already consumed or closed")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_closed(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    expireAfterWrite.youngest(stream -> stream).forEach(e -> {});
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_partial(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.youngest(stream -> stream
        .limit(context.initialSize() / 2)
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_full(Cache<Int, Int> cache,
      CacheContext context, @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var result = expireAfterWrite.youngest(stream -> stream
        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)));
    assertThat(cache).containsExactlyEntriesIn(result);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = {Population.PARTIAL, Population.FULL},
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      expireAfterWrite = Expire.ONE_MINUTE)
  public void youngestFunc_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var youngest = expireAfterWrite.youngest(
        stream -> stream.map(Map.Entry::getKey).collect(toList()));
    var expected = ImmutableList.copyOf(context.original().keySet()).reverse();
    assertThat(youngest).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = AFTER_WRITE, population = {Population.PARTIAL, Population.FULL})
  public void youngestFunc_metadata(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Int, Int> expireAfterWrite) {
    var entries = expireAfterWrite.youngest(stream -> stream.collect(toList()));
    for (var entry : entries) {
      assertThat(context).containsEntry(entry);
    }
  }
}
