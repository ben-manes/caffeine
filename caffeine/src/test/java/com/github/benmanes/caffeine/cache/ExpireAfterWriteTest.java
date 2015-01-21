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

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Expiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
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
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterWriteTest {

  /* ---------------- Cache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(cache, hasRemovalNotifications(context, context.initialSize(), RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = key -> context.original().get(key);
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // recreated

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(0));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));
    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put(Cache<Integer, Integer> cache, CacheContext context) {
    Integer firstValue = context.original().get(context.firstKey());

    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.put(context.firstKey(), firstValue);
    cache.put(context.absentKey(), context.absentValue());

    // Ignore replacement notification
    context.consumedNotifications().clear();

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(firstValue));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(2L));
    long count = Math.max(1, context.initialSize() - 1);
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }


  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void putAll(Cache<Integer, Integer> cache, CacheContext context) {
    Integer firstValue = context.original().get(context.firstKey());

    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.putAll(ImmutableMap.of(
        context.firstKey(), firstValue,
        context.absentKey(), context.absentValue()));

    // Ignore replacement notification
    context.consumedNotifications().clear();

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(firstValue));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(2L));
    long count = Math.max(1, context.initialSize() - 1);
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void size(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(90, TimeUnit.SECONDS);
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));
  }

  /* ---------------- LoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache.getIfPresent(context.absentKey()), is(-context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
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
  }

  /* ---------------- Policy -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter_write(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_write(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(2L));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_write(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }


  /* ---------------- Policy: oldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.oldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_zero(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterWrite.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    assertThat(Iterables.elementsEqual(oldest.keySet(), context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* ---------------- Policy: youngest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_zero(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterWrite.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(Iterables.elementsEqual(keys, context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
