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

import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyListeners;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
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
import com.github.benmanes.caffeine.cache.testing.ExpireAfterWrite;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * The test cases for caches that support the expire after write (time-to-live) policy.
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
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // recreated

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(0));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache.getIfPresent(context.absentKey()), is(-context.absentKey()));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  public void getAll(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(ImmutableList.of(context.firstKey(), context.absentKey())),
        is(ImmutableMap.of(context.firstKey(), -context.firstKey(),
            context.absentKey(), context.absentKey())));

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.getAll(ImmutableList.of(context.firstKey(), context.absentKey())),
        is(ImmutableMap.of(context.firstKey(), context.firstKey(),
            context.absentKey(), context.absentKey())));
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- AsyncLoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getIfPresent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.lastKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(0L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE }, expireAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE }, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue()), is(not(nullValue())));

    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.lastKey(), context.absentValue()), is(nullValue()));

    long count = context.initialSize();
    assertThat(map.size(), is(1));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void getIfPresentQuietly(Cache<Integer, Integer> cache, CacheContext context) {
    Duration original = cache.policy().expireAfterWrite().get().ageOf(context.firstKey()).get();
    Duration advancement = Duration.ofSeconds(30);
    context.ticker().advance(advancement);
    cache.policy().getIfPresentQuietly(context.firstKey());
    Duration current = cache.policy().expireAfterWrite().get().ageOf(context.firstKey()).get();
    assertThat(current.minus(advancement), is(original));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter_duration(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(), is(Duration.ofMinutes(1L)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(2L));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      mustExpireWithAnyOf = AFTER_WRITE, expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_duration(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(Duration.ofMinutes(2));
    assertThat(expireAfterWrite.getExpiresAfter(), is(Duration.ofMinutes(2L)));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_duration(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey()), is(Optional.of(Duration.ZERO)));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey()),
        is(Optional.of(Duration.ofSeconds(30L))));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey()).isPresent(), is(false));
  }

  /* --------------- Policy: oldest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.oldest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_zero(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterWrite.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expireAfterWrite = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet(), contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* --------------- Policy: youngest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_zero(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterWrite.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expireAfterWrite = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(keys, contains(Iterables.toArray(keys, Integer.class)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite FixedExpiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
