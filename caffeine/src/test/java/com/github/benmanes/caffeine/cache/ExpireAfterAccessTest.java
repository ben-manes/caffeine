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

import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyListeners;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.ExpireAfterAccess;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * The test cases for caches that support the expire after read (time-to-idle) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("PreferJavaTimeOverload")
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterAccessTest {

  /* --------------- Cache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = Expire.ONE_MINUTE, population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.getIfPresent(context.lastKey()), is(nullValue()));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = Expire.ONE_MINUTE, population = { Population.PARTIAL, Population.FULL })
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    cache.get(context.lastKey(), mappingFunction); // recreated

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = Expire.ONE_MINUTE, population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(3));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(3L));

    long count = context.initialSize() - 3;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expireAfterAccess = Expire.ONE_MINUTE,
      loader = Loader.IDENTITY, population = { Population.PARTIAL, Population.FULL })
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.get(context.lastKey()), is(context.lastKey()));
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(2L));

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = Expire.ONE_MINUTE, loader = {Loader.IDENTITY, Loader.BULK_IDENTITY},
      population = { Population.PARTIAL, Population.FULL })
  public void getAll(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(ImmutableList.of(context.firstKey(), context.middleKey())),
        is(ImmutableMap.of(context.firstKey(), -context.firstKey(),
            context.middleKey(), -context.middleKey())));

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.getAll(ImmutableList.of(context.firstKey(), context.absentKey())),
        is(ImmutableMap.of(context.firstKey(), -context.firstKey(),
            context.absentKey(), context.absentKey())));

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.getAll(ImmutableList.of(context.middleKey(), context.absentKey())),
        is(ImmutableMap.of(context.middleKey(), context.middleKey(),
            context.absentKey(), context.absentKey())));
    assertThat(cache.estimatedSize(), is(3L));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- AsyncLoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = Expire.ONE_MINUTE, population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getIfPresent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(futureOf(-context.firstKey())));
    assertThat(cache.getIfPresent(context.lastKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(1L));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = Expire.ONE_MINUTE, population = Population.FULL)
  public void putIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue()), is(not(nullValue())));

    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.lastKey(), context.absentValue()), is(nullValue()));

    long count = context.initialSize() - 1;
    assertThat(map.size(), is(2));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterAccess = Expire.ONE_MINUTE)
  public void getIfPresentQuietly(Cache<Integer, Integer> cache, CacheContext context) {
    Duration original = cache.policy().expireAfterAccess().get().ageOf(context.firstKey()).get();
    Duration advancement = Duration.ofSeconds(30);
    context.ticker().advance(advancement);
    cache.policy().getIfPresentQuietly(context.firstKey());
    Duration current = cache.policy().expireAfterAccess().get().ageOf(context.firstKey()).get();
    assertThat(current.minus(advancement), is(original));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void getExpiresAfter(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void getExpiresAfter_duration(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.getExpiresAfter(), is(Duration.ofMinutes(1L)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterAccess.getExpiresAfter(TimeUnit.MINUTES), is(2L));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void setExpiresAfter_duration(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.setExpiresAfter(Duration.ofMinutes(2L));
    assertThat(expireAfterAccess.getExpiresAfter(), is(Duration.ofMinutes(2L)));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterAccess.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    Assert.assertFalse(expireAfterAccess.ageOf(context.firstKey(), TimeUnit.SECONDS).isPresent());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_duration(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.ageOf(context.firstKey()), is(Optional.of(Duration.ZERO)));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterAccess.ageOf(context.firstKey()),
        is(Optional.of(Duration.ofSeconds(30L))));
    context.ticker().advance(45, TimeUnit.SECONDS);
    Assert.assertFalse(expireAfterAccess.ageOf(context.firstKey()).isPresent());
  }

  /* --------------- Policy: oldest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.oldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void oldest_zero(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterAccess = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterAccess.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expireAfterAccess = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> oldest = expireAfterAccess.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet(), contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> oldest = expireAfterAccess.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* --------------- Policy: youngest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void youngest_zero(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterAccess = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterAccess.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expireAfterAccess = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> youngest = expireAfterAccess.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(keys, contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess FixedExpiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> youngest = expireAfterAccess.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
