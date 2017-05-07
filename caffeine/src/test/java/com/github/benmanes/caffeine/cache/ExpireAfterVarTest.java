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

import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.function.Function.identity;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * The test cases for caches that support the variable expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterVarTest {

  /* ---------------- Exceptional -------------- */

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void getIfPresent_expiryFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.getIfPresent(context.firstKey());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, writer = Writer.MOCKITO, removalListener = Listener.REJECTING)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void get_expiryFails_create(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.get(context.absentKey(), identity());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
      verify(context.cacheWriter(), never()).write(anyInt(), anyInt());
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void get_expiryFails_read(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.get(context.firstKey(), identity());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void getAllPresent_expiryFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterRead(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.getAllPresent(context.firstMiddleLastKeys());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void put_insert_expiryFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.put(context.absentKey(), context.absentValue());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine, expiryTime = Expire.ONE_MINUTE,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void put_insert_replaceExpred_expiryFails(Cache<Integer, Integer> cache,
      CacheContext context, VarExpiration<Integer, Integer> expireVariably) {
    OptionalLong duration = expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS);
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.put(context.firstKey(), context.absentValue());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
      assertThat(expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS), is(duration));
    }
  }

  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.MOCKITO)
  @Test(dataProvider = "caches", expectedExceptions = ExpirationException.class)
  public void put_update_expiryFails(Cache<Integer, Integer> cache, CacheContext context,
      VarExpiration<Integer, Integer> expireVariably) {
    OptionalLong duration = expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS);
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
          .thenThrow(ExpirationException.class);
      cache.put(context.firstKey(), context.absentValue());
    } finally {
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
      assertThat(expireVariably.getExpiresAfter(context.firstKey(), NANOSECONDS), is(duration));
    }
  }

  static final class ExpirationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  /* ---------------- Policy -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, expiry = CacheExpiry.DISABLED)
  public void expireVariably_notEnabled(Cache<Integer, Integer> cache) {
    assertThat(cache.policy().expireVariably(), is(Optional.empty()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void getExpiresAfter(Cache<Integer, Integer> cache, CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES),
        is(OptionalLong.empty()));
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(1)));

    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(TimeUnit.HOURS.toNanos(1));
    cache.put(context.firstKey(), context.absentValue());
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(60)));

    assertThat(expireAfterVar.getExpiresAfter(context.lastKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(1)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Integer, Integer> cache, CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    expireAfterVar.setExpiresAfter(context.firstKey(), 2, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.firstKey(), TimeUnit.MINUTES),
        is(OptionalLong.of(2)));

    expireAfterVar.setExpiresAfter(context.lastKey(), -2, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.lastKey(), TimeUnit.MINUTES),
        is(OptionalLong.empty()));

    expireAfterVar.setExpiresAfter(context.absentKey(), 4, TimeUnit.MINUTES);
    assertThat(expireAfterVar.getExpiresAfter(context.absentKey(), TimeUnit.MINUTES),
        is(OptionalLong.empty()));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
  }

  /* ---------------- Policy: oldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    expireAfterVar.oldest(Integer.MAX_VALUE).clear();
  }

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    expireAfterVar.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void oldest_zero(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    assertThat(expireAfterVar.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void oldest_partial(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterVar.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    Map<Integer, Integer> oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet(), contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    Map<Integer, Integer> oldest = expireAfterVar.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* ---------------- Policy: youngest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    expireAfterVar.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    expireAfterVar.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void youngest_zero(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    assertThat(expireAfterVar.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expiry = CacheExpiry.ACCESS)
  public void youngest_partial(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterVar.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = {Population.PARTIAL, Population.FULL}, expiry = CacheExpiry.ACCESS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    Map<Integer, Integer> youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(keys, contains(Iterables.toArray(keys, Integer.class)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiry = CacheExpiry.ACCESS)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      VarExpiration<Integer, Integer> expireAfterVar) {
    Map<Integer, Integer> youngest = expireAfterVar.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
