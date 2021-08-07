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
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.Functions.identity;
import static com.google.common.truth.Truth.assertThat;
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
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
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
  public void expire_zero(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    if (context.isZeroWeighted() && context.isGuava()) {
      // Guava translates to maximumSize=0, which won't evict
      assertThat(cache).hasSize(1);
      assertThat(context).notifications().isEmpty();
    } else {
      runVariableExpiration(context);
      assertThat(cache).isEmpty();
      assertThat(context).notifications().withCause(EXPIRED).hasSize(1).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, compute = Compute.SYNC,
      scheduler = CacheScheduler.MOCKITO)
  public void schedule(Cache<Int, Int> cache, CacheContext context) {
    var delay = ArgumentCaptor.forClass(long.class);
    var task = ArgumentCaptor.forClass(Runnable.class);
    doReturn(DisabledFuture.INSTANCE).when(context.scheduler()).schedule(
        eq(context.executor()), task.capture(), delay.capture(), eq(TimeUnit.NANOSECONDS));

    cache.put(context.absentKey(), context.absentValue());

    long minError = TimeUnit.MINUTES.toNanos(1) - TOLERANCE;
    long maxError = TimeUnit.MINUTES.toNanos(1) + TOLERANCE;
    assertThat(delay.getValue()).isIn(Range.closed(minError, maxError));

    context.ticker().advance(delay.getValue());
    task.getValue().run();

    if (context.expiresVariably()) {
      // scheduled a timerWheel cascade, run next schedule
      assertThat(delay.getAllValues()).hasSize(2);
      context.ticker().advance(delay.getValue());
      task.getValue().run();
    }

    assertThat(cache).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, compute = Compute.SYNC,
      scheduler = CacheScheduler.MOCKITO)
  public void schedule_immediate(Cache<Int, Int> cache, CacheContext context) {
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
  public void schedule_delay(Cache<Int, Duration> cache, CacheContext context) {
    var actualExpirationPeriods = new HashMap<Int, Duration>();
    var delay = ArgumentCaptor.forClass(long.class);
    var task = ArgumentCaptor.forClass(Runnable.class);
    Answer<Void> onRemoval = invocation -> {
      var key = invocation.getArgument(0, Int.class);
      var value = invocation.getArgument(1, Duration.class);
      actualExpirationPeriods.put(key, Duration.ofNanos(context.ticker().read()).minus(value));
      return null;
    };
    doAnswer(onRemoval).when(context.removalListener()).onRemoval(any(), any(), any());
    when(context.scheduler().schedule(any(), task.capture(), delay.capture(), any()))
        .thenReturn(Futures.immediateFuture(null));
    var original = new HashMap<Int, Duration>();

    Int key1 = Int.valueOf(1);
    var value1 = Duration.ofNanos(context.ticker().read());
    original.put(key1, value1);
    cache.put(key1, value1);

    var insertDelay = Duration.ofMillis(10);
    context.ticker().advance(insertDelay);

    Int key2 = Int.valueOf(2);
    var value2 = Duration.ofNanos(context.ticker().read());
    original.put(key2, value2);
    cache.put(key2, value2);

    var expireKey1 = Duration.ofNanos(1 + delay.getValue()).minus(insertDelay);
    context.ticker().advance(expireKey1);
    task.getValue().run();

    var expireKey2 = Duration.ofNanos(1 + delay.getValue());
    context.ticker().advance(expireKey2);
    task.getValue().run();

    if (context.expiresVariably()) {
      context.ticker().advance(Pacer.TOLERANCE);
      task.getValue().run();
    }

    var maxExpirationPeriod = Duration.ofNanos(context.expiryTime().timeNanos() + Pacer.TOLERANCE);
    assertThat(actualExpirationPeriods.get(key1)).isAtMost(maxExpirationPeriod);
    assertThat(actualExpirationPeriods.get(key2)).isAtMost(maxExpirationPeriod);
    assertThat(actualExpirationPeriods).hasSize(original.size());
  }

  /* --------------- Cache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void get_writeTime(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int value = context.absentValue();

    cache.get(key, k -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    assertThat(cache).hasSize(1);
    assertThat(cache).containsEntry(key, value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), context.absentValue());

    runVariableExpiration(context);
    long count = context.initialSize();
    assertThat(cache).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(cache).doesNotContainKey(context.middleKey());

    cache.cleanUp();
    assertThat(cache).hasSize(2);
    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putAll_insert(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.putAll(Map.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue()));

    long count = context.initialSize();
    assertThat(cache).hasSize(3);
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putAll_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.putAll(Map.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(cache).doesNotContainKey(context.middleKey());

    cache.cleanUp();
    assertThat(cache).hasSize(2);
    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidate(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidate(context.firstKey());

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidateAll(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll(context.firstMiddleLastKeys());

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidateAll_full(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE)
  public void estimatedSize(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE)
  public void cleanUp(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.cleanUp();

    assertThat(cache).isEmpty();
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    Int key = context.firstKey();
    assertThat(cache.refresh(key)).succeedsWith(key);

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- AsyncCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = Listener.CONSUMING, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(2, TimeUnit.MINUTES);

    cache.get(context.firstKey(), k -> k);
    cache.get(context.middleKey(), k -> context.absentValue());
    cache.get(context.lastKey(), (k, executor) -> context.absentValue().asFuture());

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get_writeTime(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int value = context.absentValue();

    cache.get(key, k -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    assertThat(cache).hasSize(1);
    assertThat(cache).containsEntry(key, value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.get(context.absentKey(), (k, e) -> future);
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.synchronous().cleanUp();

    assertThat(context).removalNotifications().isEmpty();
    future.complete(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache).containsEntry(context.absentKey(), future);

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache).doesNotContainKey(context.absentKey());

    cache.synchronous().cleanUp();
    assertThat(context).removalNotifications().withCause(EXPIRED).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.SINGLETON, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getAll(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.getAll(context.firstMiddleLastKeys(), keysToLoad -> Maps.asMap(keysToLoad, identity()));
    var expectedMap = Maps.asMap(keys, identity());
    assertThat(cache.getAll(keys, keysToLoad -> Maps.asMap(keysToLoad, identity())).join())
        .containsExactlyEntriesIn(expectedMap).inOrder();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), CacheContext.intern(context.absentValue().asFuture()));

    runVariableExpiration(context);
    assertThat(cache).hasSize(1);
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  @SuppressWarnings("FutureReturnValueIgnored")
  public void put_insert_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.synchronous().cleanUp();

    assertThat(context).removalNotifications().isEmpty();
    future.complete(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache).containsEntry(context.absentKey(), future);

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache).doesNotContainKey(context.absentKey());

    cache.synchronous().cleanUp();
    assertThat(context).removalNotifications().withCause(EXPIRED).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = context.absentValue().asFuture();
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(cache).doesNotContainKey(context.middleKey());

    cache.synchronous().cleanUp();
    assertThat(cache).hasSize(2);
    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void isEmpty(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.isEmpty()).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void size(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.size()).isEqualTo(context.original().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsKey(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsKey(context.firstKey())).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsValue(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsValue(context.original().get(context.firstKey()))).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void clear(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    map.clear();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.put(context.firstKey(), context.absentValue())).isNull();

    assertThat(map).hasSize(1);
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    assertThat(map.put(context.firstKey(), context.absentValue())).isNotNull();
    assertThat(map.put(context.absentKey(), context.absentValue())).isNull();
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(map).containsEntry(context.firstKey(), context.absentValue());
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).doesNotContainKey(context.middleKey());

    context.cleanUp();
    assertThat(map).hasSize(2);
    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue())).isNull();

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map).isExhaustivelyEmpty();
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace_updated(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue())).isNotNull();
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue())).isFalse();

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map).isExhaustivelyEmpty();
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally_updated(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue())).isTrue();
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void remove(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(context.firstKey())).isNull();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void removeConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(key, context.original().get(key))).isFalse();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    var result = map.computeIfAbsent(key, k -> context.absentValue());
    assertThat(result).isEqualTo(context.absentValue());

    assertThat(map).hasSize(1);
    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_writeTime(Map<Int, Int> map, CacheContext context) {
    Int key = context.absentKey();
    Int value = context.absentValue();

    map.computeIfAbsent(key, k -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfPresent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfPresent(key, (k, v) -> value)).isNull();

    assertThat(map).isExhaustivelyEmpty();
    if (context.isGuava()) {
      context.cleanUp();
    }

    long count = context.initialSize();
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfPresent_writeTime(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();

    map.computeIfPresent(key, (k, v) -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    context.cleanUp();
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v).isNull();
      return value;
    })).isEqualTo(value);

    long count = context.initialSize() - map.size() + 1;
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_writeTime(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();

    map.compute(key, (k, v) -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    context.cleanUp();
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.merge(key, value, (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    })).isEqualTo(value);

    long count = context.initialSize() - map.size() + 1;
    assertThat(context).notifications().withCause(EXPIRED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge_writeTime(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();

    map.merge(key, value, (oldValue, v) -> {
      context.ticker().advance(5, TimeUnit.MINUTES);
      return value;
    });
    context.cleanUp();
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void iterators(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.keySet().iterator().hasNext()).isFalse();
    assertThat(map.values().iterator().hasNext()).isFalse();
    assertThat(map.entrySet().iterator().hasNext()).isFalse();
  }

  /* --------------- Weights --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putIfAbsent_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = CacheContext.intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    context.ticker().advance(1, TimeUnit.MINUTES);

    var newValue = CacheContext.intern(List.copyOf(context.absent().values()));
    cache.asMap().putIfAbsent(context.absentKey(), newValue);
    assertThat(cache).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = CacheContext.intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    assertThat(cache).hasWeightedSize(1);

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache).hasWeightedSize(1);

    var newValue = CacheContext.intern(List.copyOf(context.absent().values()));
    cache.put(context.absentKey(), newValue);
    assertThat(cache).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = CacheContext.intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    context.ticker().advance(1, TimeUnit.MINUTES);

    var newValue = CacheContext.intern(List.copyOf(context.absent().values()));
    cache.asMap().computeIfAbsent(context.absentKey(), k -> newValue);
    assertThat(cache).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = CacheContext.intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    context.ticker().advance(1, TimeUnit.MINUTES);

    var newValue = CacheContext.intern(List.copyOf(context.absent().values()));
    cache.asMap().compute(context.absentKey(), (k, v) -> newValue);
    assertThat(cache).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = CacheContext.intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    context.ticker().advance(1, TimeUnit.MINUTES);

    var newValue = CacheContext.intern(List.copyOf(context.absent().values()));
    cache.asMap().merge(context.absentKey(), newValue,
        (oldValue, v) -> { throw new AssertionError("Should never be called"); });
    assertThat(cache).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  public void keySetToArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(2 * context.expiryTime().timeNanos(), TimeUnit.NANOSECONDS);
    assertThat(map.keySet().toArray(new Int[0])).isEmpty();
    assertThat(map.keySet().toArray(Int[]::new)).isEmpty();
    assertThat(map.keySet().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      implementation = Implementation.Caffeine)
  public void keySet_iterator(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(map.keySet().iterator().hasNext()).isFalse();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPIRED)
        .hasSize(context.initialSize()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  public void valuesToArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(2 * context.expiryTime().timeNanos(), TimeUnit.NANOSECONDS);
    assertThat(map.values().toArray(new Int[0])).isEmpty();
    assertThat(map.values().toArray(Int[]::new)).isEmpty();
    assertThat(map.values().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      implementation = Implementation.Caffeine)
  public void values_iterator(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(map.values().iterator().hasNext()).isFalse();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPIRED)
        .hasSize(context.initialSize()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE)
  public void entrySetToArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(2 * context.expiryTime().timeNanos(), TimeUnit.NANOSECONDS);
    assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0])).isEmpty();
    assertThat(map.entrySet().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    assertThat(map.entrySet().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      implementation = Implementation.Caffeine)
  public void entrySet_iterator(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(map.keySet().iterator().hasNext()).isFalse();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPIRED)
        .hasSize(context.initialSize()).exclusively();
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
  public void getIfPresentQuietly_expired(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey())).isNotNull();
    context.ticker().advance(10, TimeUnit.MINUTES);
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey())).isNull();
  }
}
