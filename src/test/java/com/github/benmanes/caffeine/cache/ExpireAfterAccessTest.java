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
import static com.github.benmanes.caffeine.matchers.IsEmptyMap.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Advanced.Expiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.ExpireAfterAccess;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

/**
 * The test cases for caches that support the expire after read (time-to-idle) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterAccessTest {

  /* ---------------- Cache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.getIfPresent(context.lastKey()), is(nullValue()));

    cache.cleanUp();
    assertThat(cache.size(), is(1L));
    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = key -> -key;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    cache.get(context.lastKey(), mappingFunction); // recreated

    cache.cleanUp();
    assertThat(cache.size(), is(2L));
    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(3));

    cache.cleanUp();
    assertThat(cache.size(), is(3L));
    long count = context.initialSize() - 3;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.put(context.firstKey(), -context.firstKey());
    cache.put(context.absentKey(), -context.absentKey());

    // Ignore replacement notification
    context.consumedNotifications().clear();

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.getIfPresent(context.absentKey()), is(-context.absentKey()));

    cache.cleanUp();
    assertThat(cache.size(), is(2L));
    long count = Math.max(1, context.initialSize() - 1);
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void putAll(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.putAll(ImmutableMap.of(
        context.firstKey(), -context.firstKey(),
        context.absentKey(), -context.absentKey()));

    // Ignore replacement notification
    context.consumedNotifications().clear();

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.getIfPresent(context.absentKey()), is(-context.absentKey()));

    cache.cleanUp();
    assertThat(cache.size(), is(2L));
    long count = Math.max(1, context.initialSize() - 1);
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void size(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(90, TimeUnit.SECONDS);
    assertThat(cache.size(), is(context.initialSize()));
    cache.cleanUp();
    assertThat(cache.size(), is(0L));
  }

  /* ---------------- LoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.get(context.lastKey()), is(context.lastKey()));
    cache.cleanUp();
    assertThat(cache.size(), is(2L));

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.size(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
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
    assertThat(cache.size(), is(3L));
  }

  /* ---------------- Advanced -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void getExpiresAfter_access(
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void setExpiresAfter_access(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterAccess.getExpiresAfter(TimeUnit.MINUTES), is(2L));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.size(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_access(CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.ageOf(context.firstKey(), TimeUnit.SECONDS).get(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterAccess.ageOf(context.firstKey(), TimeUnit.SECONDS).get(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    Assert.assertFalse(expireAfterAccess.ageOf(context.firstKey(), TimeUnit.SECONDS).isPresent());
  }

  /* ---------------- Advanced: oldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.oldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(@ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void oldest_zero(@ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterAccess = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterAccess.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> oldest = expireAfterAccess.oldest(Integer.MAX_VALUE);
    assertThat(Iterables.elementsEqual(oldest.keySet(), context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> oldest = expireAfterAccess.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* ---------------- Advanced: youngest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(@ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    expireAfterAccess.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void youngest_zero(@ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    assertThat(expireAfterAccess.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterAccess = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterAccess.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterAccess = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> youngest = expireAfterAccess.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(Iterables.elementsEqual(keys, context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterAccess = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterAccess Expiration<Integer, Integer> expireAfterAccess) {
    Map<Integer, Integer> youngest = expireAfterAccess.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
