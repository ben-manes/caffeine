/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.Pacer.TOLERANCE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyListeners;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;

/**
 * The test cases for caches that support an expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("PreferJavaTimeOverload")
@Test(dataProviderClass = CacheProvider.class)
public final class ExpirationTest {

  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.IMMEDIATELY},
      expireAfterWrite = {Expire.DISABLED, Expire.IMMEDIATELY}, implementation = Implementation.Guava,
      expiryTime = Expire.IMMEDIATELY, population = Population.EMPTY,
      evictionListener = { Listener.CONSUMING, Listener.DEFAULT, Listener.REJECTING })
  public void expire_zero(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    if (context.isZeroWeighted() && context.isGuava()) {
      // Guava translates to maximumSize=0, which won't evict
      assertThat(cache.estimatedSize(), is(1L));
      verifyListeners(context, verifier -> verifier.noInteractions());
    } else {
      runVariableExpiration(context);
      assertThat(cache.estimatedSize(), is(0L));
      verifyListeners(context, verifier -> verifier.hasOnly(1, RemovalCause.EXPIRED));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, compute = Compute.SYNC,
      scheduler = CacheScheduler.MOCKITO)
  public void schedule(Cache<Integer, Integer> cache, CacheContext context) {
    ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(long.class);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    doReturn(DisabledFuture.INSTANCE).when(context.scheduler()).schedule(
        eq(context.executor()), task.capture(), delay.capture(), eq(TimeUnit.NANOSECONDS));

    cache.put(context.absentKey(), context.absentValue());

    long minError = TimeUnit.MINUTES.toNanos(1) - TOLERANCE;
    long maxError = TimeUnit.MINUTES.toNanos(1) + TOLERANCE;
    assertThat(delay.getValue(), is(both(greaterThan(minError)).and(lessThan(maxError))));

    context.ticker().advance(delay.getValue());
    task.getValue().run();

    if (context.expiresVariably()) {
      // scheduled a timerWheel cascade, run next schedule
      assertThat(delay.getAllValues(), hasSize(2));
      context.ticker().advance(delay.getValue());
      task.getValue().run();
    }

    assertThat(cache.asMap(), is(anEmptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, compute = Compute.SYNC,
      scheduler = CacheScheduler.MOCKITO)
  public void schedule_immediate(Cache<Integer, Integer> cache, CacheContext context) {
    doAnswer(invocation -> {
      invocation.getArgument(1, Runnable.class).run();
      return DisabledFuture.INSTANCE;
    }).when(context.scheduler()).schedule(any(), any(), anyLong(), any());

    cache.put(context.absentKey(), context.absentValue());
    verify(context.scheduler(), atMostOnce()).schedule(any(), any(), anyLong(), any());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      scheduler = CacheScheduler.MOCKITO, removalListener = Listener.MOCKITO)
  public void schedule_delay(Cache<Integer, Duration> cache, CacheContext context) {
    Map<Integer, Duration> actualExpirationPeriods = new HashMap<>();
    ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(long.class);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    Answer<Void> onRemoval = invocation -> {
      Integer key = invocation.getArgument(0, Integer.class);
      Duration value = invocation.getArgument(1, Duration.class);
      actualExpirationPeriods.put(key, Duration.ofNanos(context.ticker().read()).minus(value));
      return null;
    };
    doAnswer(onRemoval).when(context.removalListener()).onRemoval(any(), any(), any());
    when(context.scheduler().schedule(any(), task.capture(), delay.capture(), any()))
        .thenReturn(Futures.immediateFuture(null));
    Map<Integer, Duration> original = new HashMap<>();

    Integer key1 = 1;
    Duration value1 = Duration.ofNanos(context.ticker().read());
    original.put(key1, value1);
    cache.put(key1, value1);

    Duration insertDelay = Duration.ofMillis(10);
    context.ticker().advance(insertDelay);

    Integer key2 = 2;
    Duration value2 = Duration.ofNanos(context.ticker().read());
    original.put(key2, value2);
    cache.put(key2, value2);

    Duration expireKey1 = Duration.ofNanos(1 + delay.getValue()).minus(insertDelay);
    context.ticker().advance(expireKey1);
    task.getValue().run();

    Duration expireKey2 = Duration.ofNanos(1 + delay.getValue());
    context.ticker().advance(expireKey2);
    task.getValue().run();

    if (context.expiresVariably()) {
      context.ticker().advance(Pacer.TOLERANCE);
      task.getValue().run();
    }

    Duration maxExpirationPeriod = Duration.ofNanos(
        context.expiryTime().timeNanos() + Pacer.TOLERANCE);
    assertThat(actualExpirationPeriods.get(key1), is(lessThanOrEqualTo(maxExpirationPeriod)));
    assertThat(actualExpirationPeriods.get(key2), is(lessThanOrEqualTo(maxExpirationPeriod)));
    assertThat(actualExpirationPeriods, is(aMapWithSize(original.size())));
  }

  /* --------------- Cache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void get_writeTime(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = context.absentValue();

    cache.get(key, k -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache.getIfPresent(key), is(value));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), context.absentValue());

    runVariableExpiration(context);
    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(1L));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.estimatedSize(), is(2L));

    if (context.isGuava()) {
      cache.cleanUp();
    }

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putAll_insert(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.putAll(ImmutableMap.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(3L));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putAll_replace(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.putAll(ImmutableMap.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.estimatedSize(), is(2L));

    if (context.isGuava()) {
      cache.cleanUp();
    }

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidate(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidate(context.firstKey());

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll(context.firstMiddleLastKeys());

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll();

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE)
  public void estimatedSize(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE)
  public void cleanUp(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.cleanUp();

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(0L));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void refresh(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    Integer key = context.firstKey();
    cache.refresh(key).join();

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- AsyncLoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY,
      removalListener = Listener.CONSUMING, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(2, TimeUnit.MINUTES);

    cache.get(context.firstKey());
    cache.get(context.middleKey(), k -> context.absentValue());
    cache.get(context.lastKey(), (k, executor) ->
        CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get_writeTime(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = context.absentValue();

    cache.get(key, k -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    assertThat(cache.synchronous().estimatedSize(), is(1L));
    assertThat(cache.getIfPresent(key), futureOf(value));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get_async(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    cache.get(context.absentKey(), (k, e) -> future);
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.synchronous().cleanUp();

    verifyRemovalListener(context, verifier -> verifier.noInteractions());
    future.complete(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.absentKey()), is(future));

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));

    cache.synchronous().cleanUp();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.SINGLETON, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE, loader = {Loader.BULK_IDENTITY})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getAll(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.getAll(context.firstMiddleLastKeys());
    assertThat(cache.getAll(keys).join(), is(Maps.uniqueIndex(keys, Functions.identity())));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), CompletableFuture.completedFuture(context.absentValue()));

    runVariableExpiration(context);
    long count = context.initialSize();
    assertThat(cache.synchronous().estimatedSize(), is(1L));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void put_insert_async(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    cache.put(context.absentKey(), future);
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.synchronous().cleanUp();

    verifyRemovalListener(context, verifier -> verifier.noInteractions());
    future.complete(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.absentKey()), is(future));

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));

    cache.synchronous().cleanUp();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = CompletableFuture.completedFuture(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(futureOf(context.absentValue())));
    assertThat(cache.getIfPresent(context.absentKey()), is(futureOf(context.absentValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void isEmpty(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.isEmpty(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void size(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsKey(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsKey(context.firstKey()), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsValue(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsValue(context.original().get(context.firstKey())), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    map.clear();

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.put(context.firstKey(), context.absentValue()), is(nullValue()));

    long count = context.initialSize();
    assertThat(map.size(), is(1));
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    assertThat(map.put(context.firstKey(), context.absentValue()), is(not(nullValue())));
    assertThat(map.put(context.absentKey(), context.absentValue()), is(nullValue()));
    context.removalNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(map.get(context.firstKey()), is(context.absentValue()));
    assertThat(map.get(context.absentKey()), is(context.absentValue()));
    assertThat(map.get(context.middleKey()), is(nullValue()));
    assertThat(map.size(), is(2));

    if (context.isGuava()) {
      context.cleanUp();
    }

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(nullValue()));

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map.size(), is(0));
    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace_updated(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(not(nullValue())));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(false));

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map.size(), is(0));
    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally_updated(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(true));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void remove(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(context.firstKey()), is(nullValue()));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void removeConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(key, context.original().get(key)), is(false));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfAbsent(key, k -> context.absentValue()), is(context.absentValue()));

    assertThat(map.size(), is(1));
    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_writeTime(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = context.absentValue();

    map.computeIfAbsent(key, k -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    assertThat(map.size(), is(1));
    assertThat(map.containsKey(key), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfPresent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfPresent(key, (k, v) -> value), is(nullValue()));

    assertThat(map.size(), is(0));
    if (context.isGuava()) {
      context.cleanUp();
    }

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfPresent_writeTime(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();

    map.computeIfPresent(key, (k, v) -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    context.cleanUp();
    assertThat(map.size(), is(1));
    assertThat(map.containsKey(key), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v, is(nullValue()));
      return value;
    }), is(value));

    long count = context.initialSize() - map.size() + 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_writeTime(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();

    map.compute(key, (k, v) -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    context.cleanUp();
    assertThat(map.size(), is(1));
    assertThat(map.containsKey(key), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.merge(key, value, (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    }), is(value));

    long count = context.initialSize() - map.size() + 1;
    verifyListeners(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge_writeTime(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();

    map.merge(key, value, (oldValue, v) -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    context.cleanUp();
    assertThat(map.size(), is(1));
    assertThat(map.containsKey(key), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void iterators(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(Iterators.size(map.keySet().iterator()), is(0));
    assertThat(Iterators.size(map.values().iterator()), is(0));
    assertThat(Iterators.size(map.entrySet().iterator()), is(0));
  }

  /* --------------- Weights --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().putIfAbsent(1, ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(1, ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().computeIfAbsent(1, k -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().compute(1, (k, v) -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().merge(1, ImmutableList.of(1, 2, 3), (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    });

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  public void keySetToArray(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(2 * context.expiryTime().timeNanos(), TimeUnit.NANOSECONDS);
    assertThat(map.keySet().toArray(new Integer[0]), arrayWithSize(0));
    assertThat(map.keySet().toArray(), arrayWithSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      implementation = Implementation.Caffeine)
  public void keySet_iterator(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(map.keySet().iterator().hasNext(), is(false));
    assertThat(map, is(emptyMap()));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(context.initialSize(), RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  public void valuesToArray(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(2 * context.expiryTime().timeNanos(), TimeUnit.NANOSECONDS);
    assertThat(map.values().toArray(new Integer[0]), arrayWithSize(0));
    assertThat(map.values().toArray(), arrayWithSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      implementation = Implementation.Caffeine)
  public void values_iterator(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(map.values().iterator().hasNext(), is(false));
    assertThat(map, is(emptyMap()));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(context.initialSize(), RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  public void entrySetToArray(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(2 * context.expiryTime().timeNanos(), TimeUnit.NANOSECONDS);
    assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0]), arrayWithSize(0));
    assertThat(map.entrySet().toArray(), arrayWithSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      implementation = Implementation.Caffeine)
  public void entrySet_iterator(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(map.keySet().iterator().hasNext(), is(false));
    assertThat(map, is(emptyMap()));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(context.initialSize(), RemovalCause.EXPIRED));
  }

  /**
   * Ensures that variable expiration is run, as it may not have due to expiring in coarse batches.
   */
  private static void runVariableExpiration(CacheContext context) {
    if (context.expiresVariably()) {
      // Variable expires in coarse buckets at a time
      context.ticker().advance(2, TimeUnit.SECONDS);
      context.cleanUp();
    }
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
    population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
    expiryTime = Expire.ONE_MINUTE, mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
    expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
    expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
    expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void getIfPresentQuietly_expired(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey()), is(not(nullValue())));
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey()), is(nullValue()));
  }
}
