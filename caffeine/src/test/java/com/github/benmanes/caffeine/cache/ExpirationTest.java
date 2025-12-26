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

import static com.github.benmanes.caffeine.cache.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.Pacer.TOLERANCE;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.Functions.identity;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.StartTime;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Range;

/**
 * The test cases for caches that support an expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpirationTest {

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.IMMEDIATELY},
      expireAfterWrite = {Expire.DISABLED, Expire.IMMEDIATELY},
      expiryTime = Expire.IMMEDIATELY, population = Population.EMPTY,
      evictionListener = { Listener.CONSUMING, Listener.DISABLED, Listener.REJECTING })
  public void expire_zero(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    if (context.isZeroWeighted() && context.isGuava()) {
      // Guava translates to maximumSize=0, which won't evict
      assertThat(cache).hasSize(1);
      assertThat(context).notifications().isEmpty();
    } else {
      runVariableExpiration(context);
      assertThat(cache).isEmpty();
      assertThat(context).notifications().withCause(EXPIRED)
          .contains(context.absentKey(), context.absentValue())
          .exclusively();
    }
  }

  @CheckNoStats
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.IMMEDIATELY},
      expireAfterWrite = {Expire.DISABLED, Expire.IMMEDIATELY},
      expiryTime = Expire.IMMEDIATELY, population = Population.EMPTY,
      evictionListener = Listener.REJECTING, weigher = CacheWeigher.DISABLED)
  public void expire_evictionListener_failure(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    runVariableExpiration(context);
    assertThat(cache).isEmpty();

    assertThat(context).evictionNotifications().hasSize(1);
    assertThat(logEvents()
        .withMessage("Exception thrown by eviction listener")
        .withThrowable(RejectedExecutionException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void schedule(Cache<Int, Int> cache, CacheContext context) {
    when(context.scheduler().schedule(eq(context.executor()), any(), anyLong(),
        eq(TimeUnit.NANOSECONDS))).then(invocation -> DisabledFuture.instance());

    cache.put(context.absentKey(), context.absentValue());

    long minError = TimeUnit.MINUTES.toNanos(1) - TOLERANCE;
    long maxError = TimeUnit.MINUTES.toNanos(1) + TOLERANCE;
    var task1 = ArgumentCaptor.forClass(Runnable.class);
    var delay1 = ArgumentCaptor.forClass(long.class);

    verify(context.scheduler()).schedule(any(), task1.capture(), delay1.capture(), any());
    assertThat(delay1.getValue()).isIn(Range.closed(minError, maxError));
    var duration = Duration.ofNanos(delay1.getValue());

    context.ticker().advance(duration);
    task1.getValue().run();

    if (context.expiresVariably()) {
      var task2 = ArgumentCaptor.forClass(Runnable.class);
      var delay2 = ArgumentCaptor.forClass(long.class);
      verify(context.scheduler(), times(2))
          .schedule(any(), task2.capture(), delay2.capture(), any());

      // scheduled a timerWheel cascade, run next schedule
      assertThat(delay2.getAllValues()).hasSize(2);
      context.ticker().advance(duration);
      task2.getValue().run();
    }

    assertThat(cache).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void schedule_immediate(Cache<Int, Int> cache, CacheContext context) {
    when(context.scheduler().schedule(any(), any(), anyLong(), any())).then(invocation -> {
      invocation.<Runnable>getArgument(1).run();
      return new CompletableFuture<>();
    });

    cache.put(context.absentKey(), context.absentValue());
    verify(context.scheduler()).schedule(any(), any(), anyLong(), any());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      removalListener = Listener.MOCKITO)
  public void schedule_delay(Cache<Int, Duration> cache, CacheContext context) {
    var actualExpirationPeriods = new HashMap<Int, Duration>();
    Answer<@Nullable Void> onRemoval = invocation -> {
      Int key = invocation.getArgument(0);
      Duration value = invocation.getArgument(1);
      actualExpirationPeriods.put(key, Duration.ofNanos(context.ticker().read()).minus(value));
      return null;
    };
    var future1 = new CompletableFuture<@Nullable Void>();
    var future2 = new CompletableFuture<@Nullable Void>();
    var future3 = new CompletableFuture<@Nullable Void>();
    var original = new HashMap<Int, Duration>();
    doAnswer(onRemoval).when(context.removalListener()).onRemoval(any(), any(), any());
    when(context.scheduler().schedule(any(), any(), anyLong(), any()))
        .then(invocation -> future1)
        .then(invocation -> future2)
        .then(invocation -> future3);

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

    var delay1 = ArgumentCaptor.forClass(long.class);
    var task1 = ArgumentCaptor.forClass(Runnable.class);
    verify(context.scheduler()).schedule(any(), task1.capture(), delay1.capture(), any());
    var expireKey1 = Duration.ofNanos(1 + delay1.getValue()).minus(insertDelay);
    context.ticker().advance(expireKey1);
    task1.getValue().run();
    future1.complete(null);

    var delay2 = ArgumentCaptor.forClass(long.class);
    var task2 = ArgumentCaptor.forClass(Runnable.class);
    verify(context.scheduler(), times(2)).schedule(any(), task2.capture(), delay2.capture(), any());
    var expireKey2 = Duration.ofNanos(1 + delay2.getValue());
    context.ticker().advance(expireKey2);
    task2.getValue().run();
    future2.complete(null);

    if (context.expiresVariably()) {
      var task3 = ArgumentCaptor.forClass(Runnable.class);
      context.ticker().advance(Duration.ofNanos(TOLERANCE));
      verify(context.scheduler(), atLeast(2)).schedule(any(), task3.capture(), anyLong(), any());
      if (task3.getAllValues().size() > 2) {
        task3.getValue().run();
        future3.complete(null);
      }
    }

    var maxExpirationPeriod = context.expiryTime().duration().plusNanos(TOLERANCE);
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
    var value = cache.get(context.absentKey(), k -> {
      context.ticker().advance(Duration.ofMinutes(5));
      return context.absentValue();
    });
    assertThat(cache).hasSize(1);
    assertThat(value).isEqualTo(context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_insert(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    cache.put(context.firstKey(), context.absentValue());

    runVariableExpiration(context);
    assertThat(cache).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
      verifyNoMoreInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.clearRemovalNotifications(); // Ignore replacement notification
    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verify(context.expiry()).expireAfterUpdate(
          eq(context.firstKey()), any(), anyLong(), anyLong());
      verify(context.expiry()).expireAfterCreate(eq(context.absentKey()), any(), anyLong());
      verifyNoMoreInteractions(context.expiry());
    }

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(cache).doesNotContainKey(context.middleKey());

    cache.cleanUp();
    assertThat(cache).hasSize(2);

    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    assertThat(context).hasWeightedSize(1);

    context.ticker().advance(Duration.ofMinutes(1));
    assertThat(context).hasWeightedSize(1);

    var newValue = intern(List.copyOf(context.absent().values()));
    cache.put(context.absentKey(), newValue);
    assertThat(context).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void putAll_insert(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    cache.putAll(Map.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue()));

    assertThat(cache).hasSize(3);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void putAll_replace(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));

    cache.putAll(Map.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(cache).doesNotContainKey(context.middleKey());

    cache.cleanUp();
    assertThat(cache).hasSize(2);

    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(context).notifications().withCause(EXPIRED).contains(expected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void invalidate(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    cache.invalidate(context.firstKey());

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void invalidateAll_iterable(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    cache.invalidateAll(context.firstMiddleLastKeys());

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void invalidateAll_full(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(1));
    cache.invalidateAll();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE, startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void estimatedSize(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiryTime = Expire.ONE_MINUTE, startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void cleanUp(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    cache.cleanUp();

    assertThat(cache).isEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    Int key = context.firstKey();

    assertThat(cache.refresh(key)).succeedsWith(key);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  /* --------------- AsyncCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void getIfPresent_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.getIfPresent(context.absentKey())).isSameInstanceAs(future);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.getIfPresent(context.absentKey())).isSameInstanceAs(future);
    future.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = Listener.CONSUMING, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void get(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));

    cache.get(context.firstKey(), k -> k).join();
    cache.get(context.middleKey(), k -> context.absentValue()).join();
    cache.get(context.lastKey(), (k, executor) -> context.absentValue().toFuture()).join();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE },
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void get_writeTime(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int value = context.absentValue();

    cache.get(key, k -> {
      context.ticker().advance(Duration.ofMinutes(5));
      return value;
    }).join();
    assertThat(cache).hasSize(1);
    assertThat(cache).containsEntry(key, value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void get_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.get(context.absentKey(), (k, e) -> new CompletableFuture<>());
    context.ticker().advance(Duration.ofMinutes(2));
    cache.synchronous().cleanUp();

    assertThat(context).notifications().isEmpty();
    future.complete(context.absentValue());
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache).containsEntry(context.absentKey(), future);

    context.ticker().advance(Duration.ofMinutes(1));
    assertThat(cache).doesNotContainKey(context.absentKey());

    cache.synchronous().cleanUp();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void get_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.get(context.absentKey(), k -> k)).isSameInstanceAs(future);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.get(context.absentKey(), k -> k)).isSameInstanceAs(future);
    future.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.SINGLETON, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
          startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void getAll(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.ticker().advance(Duration.ofMinutes(2));
    cache.getAll(context.firstMiddleLastKeys(),
        keysToLoad -> Maps.toMap(keysToLoad, identity())).join();

    var expected = Maps.toMap(keys, identity());
    assertThat(cache.getAll(keys, keysToLoad -> Maps.toMap(keysToLoad, identity())).join())
        .containsExactlyEntriesIn(expected).inOrder();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    cache.put(context.firstKey(), intern(context.absentValue().toFuture()));

    runVariableExpiration(context);
    assertThat(cache).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_insert_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    context.ticker().advance(Duration.ofMinutes(2));
    cache.synchronous().cleanUp();

    assertThat(context).notifications().isEmpty();
    future.complete(context.absentValue());
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache).containsEntry(context.absentKey(), future);

    context.ticker().advance(Duration.ofMinutes(1));
    assertThat(cache).doesNotContainKey(context.absentKey());

    cache.synchronous().cleanUp();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.absentKey(), context.absentValue()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = context.absentValue().toFuture();
    context.ticker().advance(Duration.ofSeconds(30));

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.clearRemovalNotifications(); // Ignore replacement notification

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(cache).doesNotContainKey(context.middleKey());

    cache.synchronous().cleanUp();
    assertThat(cache).hasSize(2);

    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void isEmpty(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.isEmpty()).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void size(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.size()).isEqualTo(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void containsKey(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.containsKey(context.firstKey())).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsKey_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.asMap().containsKey(context.absentKey())).isTrue();
    assertThat(cache.synchronous().asMap().containsKey(context.absentKey())).isFalse();
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().containsKey(context.absentKey())).isTrue();
    assertThat(cache.synchronous().asMap().containsKey(context.absentKey())).isFalse();
    future.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void containsValue(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.containsValue(context.original().get(context.firstKey()))).isFalse();
  }

  @Test(dataProvider = "caches")
  @SuppressWarnings("CollectionUndefinedEquality")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsValue_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.asMap().containsValue(future)).isTrue();
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().containsValue(future)).isTrue();
    future.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void clear(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    map.clear();

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putIfAbsent(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue()))
        .isEqualTo(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue())).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putIfAbsent_weighted(Map<Int, List<Int>> map, CacheContext context) {
    var value = intern(List.of(context.absentValue()));
    map.put(context.absentKey(), value);
    context.ticker().advance(Duration.ofMinutes(1));

    var newValue = intern(List.copyOf(context.absent().values()));
    map.putIfAbsent(context.absentKey(), newValue);
    assertThat(context).hasWeightedSize(context.absent().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_insert(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.put(context.firstKey(), context.absentValue())).isNull();

    assertThat(map).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
      verifyNoMoreInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void put_replace(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));

    assertThat(map.put(context.firstKey(), context.absentValue())).isNotNull();
    assertThat(map.put(context.absentKey(), context.absentValue())).isNull();
    context.clearRemovalNotifications(); // Ignore replacement notification
    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verify(context.expiry()).expireAfterUpdate(
          eq(context.firstKey()), any(), anyLong(), anyLong());
      verify(context.expiry()).expireAfterCreate(eq(context.absentKey()), any(), anyLong());
      verifyNoMoreInteractions(context.expiry());
    }

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(map).containsEntry(context.firstKey(), context.absentValue());
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).doesNotContainKey(context.middleKey());

    context.cleanUp();
    assertThat(map).hasSize(2);

    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(expected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<@Nullable Int>();
    var f2 = new CompletableFuture<@Nullable Int>();
    var f3 = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), f1);
    assertThat(cache.asMap().put(context.absentKey(), f2)).isSameInstanceAs(f1);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().put(context.absentKey(), f3)).isSameInstanceAs(f2);
    f3.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void replace(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.replace(context.firstKey(), context.absentValue())).isNull();

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void replace_updated(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.replace(context.firstKey(), context.absentValue())).isNotNull();
    context.ticker().advance(Duration.ofSeconds(45));

    context.cleanUp();
    assertThat(map).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<@Nullable Int>();
    var f2 = new CompletableFuture<@Nullable Int>();
    var f3 = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), f1);
    assertThat(cache.asMap().replace(context.absentKey(), f2)).isSameInstanceAs(f1);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().replace(context.absentKey(), f3)).isSameInstanceAs(f2);
    f3.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void replaceConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.replace(key, context.original().get(key), context.absentValue())).isFalse();

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void replaceConditionally_updated(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(map.replace(key, context.original().get(key), context.absentValue())).isTrue();
    context.ticker().advance(Duration.ofSeconds(45));

    context.cleanUp();
    assertThat(map).hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<@Nullable Int>();
    var f2 = new CompletableFuture<@Nullable Int>();
    var f3 = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), f1);
    assertThat(cache.asMap().replace(context.absentKey(), f1, f2)).isTrue();
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().replace(context.absentKey(), f2, f3)).isTrue();
    f3.complete(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void remove(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.remove(context.firstKey())).isNull();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void remove_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<Int>();
    cache.put(context.absentKey(), f1);
    assertThat(cache.asMap().remove(context.absentKey())).isSameInstanceAs(f1);

    var f2 = new CompletableFuture<Int>();
    cache.put(context.absentKey(), f2);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().remove(context.absentKey())).isSameInstanceAs(f2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void removeConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(Duration.ofMinutes(2));

    assertThat(map.remove(key, context.original().get(key))).isFalse();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void removeConditionally_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<Int>();
    cache.put(context.absentKey(), f1);
    assertThat(cache.asMap().remove(context.absentKey(), f1)).isTrue();

    var f2 = new CompletableFuture<Int>();
    cache.put(context.absentKey(), f2);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().remove(context.absentKey(), f2)).isTrue();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void computeIfAbsent_prescreen(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.ticker().advance(Duration.ofMinutes(2));
    var result = map.computeIfAbsent(key, k -> context.absentValue());
    assertThat(result).isEqualTo(context.absentValue());

    assertThat(map).hasSize(1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
      verifyNoMoreInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = Maximum.FULL,
      expiryTime = Expire.ONE_MINUTE, mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void computeIfAbsent_expiresInCompute(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));

    assertThat(map.computeIfAbsent(context.firstKey(), k -> null)).isNull();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_weighted(Map<Int, List<Int>> map, CacheContext context) {
    var value = intern(List.of(context.absentValue()));
    map.put(context.absentKey(), value);
    context.ticker().advance(Duration.ofMinutes(1));

    var newValue = intern(List.copyOf(context.absent().values()));
    map.computeIfAbsent(context.absentKey(), k -> newValue);
    assertThat(context).hasWeightedSize(context.absent().size());
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
      context.ticker().advance(Duration.ofMinutes(5));
      return value;
    });
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS, CacheExpiry.WRITE },
      expireAfterAccess = { Expire.DISABLED, Expire.ONE_MINUTE },
      expireAfterWrite = { Expire.DISABLED, Expire.ONE_MINUTE },
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void computeIfAbsent_error(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().computeIfAbsent(context.firstKey(),
          key -> { throw new IllegalStateException(); });
    });
    assertThat(cache.policy().expireAfterAccess()
        .flatMap(policy -> policy.ageOf(context.firstKey()))).isEmpty();
    assertThat(cache.policy().expireAfterWrite()
        .flatMap(policy -> policy.ageOf(context.firstKey()))).isEmpty();
    assertThat(cache.policy().expireVariably()
        .flatMap(policy -> policy.getExpiresAfter(context.firstKey()))).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), f1);
    assertThat(cache.asMap().computeIfAbsent(
        context.absentKey(), key -> null)).isSameInstanceAs(f1);
    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().computeIfAbsent(
        context.absentKey(), key -> null)).isSameInstanceAs(f1);
    f1.complete(null);

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verifyNoInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void computeIfPresent_prescreen(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.computeIfPresent(context.firstKey(), (k, v) -> {
      throw new AssertionError("Should never be called");
    })).isNull();

    assertThat(map).isExhaustivelyEmpty();
    if (context.isGuava()) {
      context.cleanUp();
    }

    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verifyNoInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = Maximum.FULL,
      expiryTime = Expire.ONE_MINUTE, mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void computeIfPresent_expiresInCompute(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(59));
    context.ticker().setAutoIncrementStep(Duration.ofSeconds(1));

    assertThat(map.computeIfPresent(context.firstKey(), (k, v) -> {
      throw new AssertionError("Should never be called");
    })).isNull();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
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
      context.ticker().advance(Duration.ofMinutes(5));
      return value;
    });
    context.cleanUp();
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, compute = Compute.SYNC,
      expiryTime = Expire.ONE_MINUTE, mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS, CacheExpiry.WRITE })
  public void computeIfPresent_error(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var access = cache.policy().expireAfterAccess()
        .flatMap(policy -> policy.ageOf(context.firstKey()));
    var write = cache.policy().expireAfterWrite()
        .flatMap(policy -> policy.ageOf(context.firstKey()));
    var variable = cache.policy().expireVariably()
        .flatMap(policy -> policy.getExpiresAfter(context.firstKey()));
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().computeIfPresent(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); });
    });

    assertThat(access).isEqualTo(cache.policy().expireAfterAccess()
        .flatMap(policy -> policy.ageOf(context.firstKey())));
    assertThat(write).isEqualTo(cache.policy().expireAfterWrite()
        .flatMap(policy -> policy.ageOf(context.firstKey())));
    assertThat(variable).isEqualTo(cache.policy().expireVariably()
        .flatMap(policy -> policy.getExpiresAfter(context.firstKey())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfPresent_inFlight(
      AsyncCache<Int, @Nullable Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<@Nullable Int>();
    var f2 = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), f1);
    cache.asMap().computeIfPresent(context.absentKey(), (k, f) -> {
      assertThat(f).isSameInstanceAs(f1);
      return f2;
    });

    var f3 = new CompletableFuture<@Nullable Int>();
    context.ticker().advance(Duration.ofMinutes(5));
    cache.asMap().computeIfPresent(context.absentKey(), (k, f) -> {
      assertThat(f).isSameInstanceAs(f2);
      return f3;
    });
    f3.complete(null);

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verifyNoInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void compute(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v).isNull();
      return value;
    })).isEqualTo(value);

    var evicted = ArrayListMultimap.<Int, Int>create();
    var difference = Maps.difference(context.original(), map);
    evicted.putAll(Multimaps.forMap(difference.entriesOnlyOnRight()));
    evicted.putAll(Multimaps.forMap(difference.entriesOnlyOnLeft()));
    evicted.put(key, requireNonNull(context.original().get(key)));

    assertThat(evicted).hasSize(context.original().size() - map.size() + 1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(evicted.entries())
        .exclusively();

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verify(context.expiry()).expireAfterCreate(any(), any(), anyLong());
      verifyNoMoreInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_weighted(Map<Int, List<Int>> map, CacheContext context) {
    var value = intern(List.of(context.absentValue()));
    map.put(context.absentKey(), value);
    context.ticker().advance(Duration.ofMinutes(1));

    var newValue = intern(List.copyOf(context.absent().values()));
    map.compute(context.absentKey(), (k, v) -> newValue);
    assertThat(context).hasWeightedSize(context.absent().size());
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
      context.ticker().advance(Duration.ofMinutes(5));
      return value;
    });
    context.cleanUp();
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS, CacheExpiry.WRITE },
      expireAfterAccess = { Expire.DISABLED, Expire.ONE_MINUTE },
      expireAfterWrite = { Expire.DISABLED, Expire.ONE_MINUTE },
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void compute_absent_error(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().compute(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); });
    });

    assertThat(cache.policy().expireAfterAccess()
        .flatMap(policy -> policy.ageOf(context.firstKey()))).isEmpty();
    assertThat(cache.policy().expireAfterWrite()
        .flatMap(policy -> policy.ageOf(context.firstKey()))).isEmpty();
    assertThat(cache.policy().expireVariably()
        .flatMap(policy -> policy.getExpiresAfter(context.firstKey()))).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, compute = Compute.SYNC,
      expiryTime = Expire.ONE_MINUTE, mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.ACCESS, CacheExpiry.WRITE },
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void compute_present_error(Cache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var access = cache.policy().expireAfterAccess()
        .flatMap(policy -> policy.ageOf(context.firstKey()));
    var write = cache.policy().expireAfterWrite()
        .flatMap(policy -> policy.ageOf(context.firstKey()));
    var variable = cache.policy().expireVariably()
        .flatMap(policy -> policy.getExpiresAfter(context.firstKey()));
    assertThrows(IllegalStateException.class, () -> {
      cache.asMap().compute(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); });
    });

    assertThat(access).isEqualTo(cache.policy().expireAfterAccess()
        .flatMap(policy -> policy.ageOf(context.firstKey())));
    assertThat(write).isEqualTo(cache.policy().expireAfterWrite()
        .flatMap(policy -> policy.ageOf(context.firstKey())));
    assertThat(variable).isEqualTo(cache.policy().expireVariably()
        .flatMap(policy -> policy.getExpiresAfter(context.firstKey())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.MOCKITO },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_inFlight(AsyncCache<Int, @Nullable Int> cache, CacheContext context) {
    var f1 = new CompletableFuture<@Nullable Int>();
    var f2 = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), f1);
    cache.asMap().compute(context.absentKey(), (k, f) -> {
      assertThat(f).isSameInstanceAs(f1);
      return f2;
    });

    var f3 = new CompletableFuture<@Nullable Int>();
    context.ticker().advance(Duration.ofMinutes(5));
    cache.asMap().compute(context.absentKey(), (k, f) -> {
      assertThat(f).isSameInstanceAs(f2);
      return f3;
    });
    f3.complete(null);

    if (context.expiryType() == CacheExpiry.MOCKITO) {
      verifyNoInteractions(context.expiry());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void merge(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.absentValue();
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(map.merge(key, value, (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    })).isEqualTo(value);

    var evicted = ArrayListMultimap.<Int, Int>create();
    var difference = Maps.difference(context.original(), map);
    evicted.putAll(Multimaps.forMap(difference.entriesOnlyOnRight()));
    evicted.putAll(Multimaps.forMap(difference.entriesOnlyOnLeft()));
    evicted.put(key, requireNonNull(context.original().get(key)));

    assertThat(evicted).hasSize(context.original().size() - map.size() + 1);
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(evicted.entries())
        .exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.FULL,
      weigher = CacheWeigher.COLLECTION, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    var value = intern(List.of(context.absentValue()));
    cache.put(context.absentKey(), value);
    context.ticker().advance(Duration.ofMinutes(1));

    var newValue = intern(List.copyOf(context.absent().values()));
    cache.asMap().merge(context.absentKey(), newValue,
        (oldValue, v) -> { throw new AssertionError("Should never be called"); });
    assertThat(context).hasWeightedSize(context.absent().size());
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
      context.ticker().advance(Duration.ofMinutes(5));
      return value;
    });
    context.cleanUp();
    assertThat(map).hasSize(1);
    assertThat(map).containsKey(key);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void keySet_toArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.keySet().toArray(new Int[0])).isEmpty();
    assertThat(map.keySet().toArray(Int[]::new)).isEmpty();
    assertThat(map.keySet().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void keySet_iterator(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(map.keySet().iterator().hasNext()).isFalse();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void keySet_iterator_traversal(Map<Int, Int> map, CacheContext context) {
    var iterator = map.keySet().iterator();
    assertThat(iterator.next()).isNotNull();
    assertThat(iterator.hasNext()).isTrue();

    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isNotNull();
    assertThat(iterator.hasNext()).isFalse();

    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void keySet_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.asMap().keySet().contains(context.absentKey())).isTrue();

    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().keySet().contains(context.absentKey())).isTrue();
    future.complete(null);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void keySpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    map.keySet().spliterator().forEachRemaining(key -> Assert.fail());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void keySpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.keySet().spliterator().tryAdvance(key -> Assert.fail())).isFalse();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX},
      implementation = Implementation.Caffeine)
  public void keyStream_toArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.keySet().stream().toArray(Int[]::new)).isEmpty();
    assertThat(map.keySet().stream().toArray()).isEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void keyStream_toArray_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(cache.asMap().keySet().stream().toArray(Int[]::new)).isEmpty();
    assertThat(cache.asMap().keySet().stream().toArray()).isEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void values_toArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.values().toArray(new Int[0])).isEmpty();
    assertThat(map.values().toArray(Int[]::new)).isEmpty();
    assertThat(map.values().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expiryTime = Expire.ONE_MINUTE,
      population = {Population.SINGLETON, Population.PARTIAL, Population.FULL},
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void values_iterator(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(map.values().iterator().hasNext()).isFalse();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void values_iterator_traversal(Map<Int, Int> map, CacheContext context) {
    var iterator = map.values().iterator();
    assertThat(iterator.next()).isNotNull();
    assertThat(iterator.hasNext()).isTrue();

    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isNotNull();
    assertThat(iterator.hasNext()).isFalse();

    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @SuppressWarnings("CollectionUndefinedEquality")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void values_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.asMap().values().contains(future)).isTrue();

    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().values().contains(future)).isTrue();
    future.complete(null);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void valueSpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    map.values().spliterator().forEachRemaining(value -> Assert.fail());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void valueSpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.values().spliterator().tryAdvance(value -> Assert.fail())).isFalse();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX},
      implementation = Implementation.Caffeine)
  public void valueStream_toArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.values().stream().toArray(Int[]::new)).isEmpty();
    assertThat(map.values().stream().toArray()).isEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void valueStream_toArray_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(cache.asMap().values().stream().toArray(CompletableFuture<?>[]::new)).isEmpty();
    assertThat(cache.asMap().values().stream().toArray()).isEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySet_toArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0])).isEmpty();
    assertThat(map.entrySet().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    assertThat(map.entrySet().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySet_iterator(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(map.entrySet().iterator().hasNext()).isFalse();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySet_iterator_traversal(Map<Int, Int> map, CacheContext context) {
    var iterator = map.entrySet().iterator();
    assertThat(iterator.next()).isNotNull();
    assertThat(iterator.hasNext()).isTrue();

    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isNotNull();
    assertThat(iterator.hasNext()).isFalse();

    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void entrySet_inFlight(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<@Nullable Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache.asMap().entrySet().contains(entry(context.absentKey(), future))).isTrue();

    context.ticker().advance(Duration.ofMinutes(5));
    assertThat(cache.asMap().entrySet().contains(entry(context.absentKey(), future))).isTrue();
    future.complete(null);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    map.entrySet().spliterator().forEachRemaining(entry -> Assert.fail());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.entrySet().spliterator().tryAdvance(entry -> Assert.fail())).isFalse();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX},
      implementation = Implementation.Caffeine)
  public void entryStream_toArray(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(map.entrySet().stream().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    assertThat(map.entrySet().stream().toArray()).isEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, expiryTime = Expire.ONE_MINUTE,
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entryStream_toArray_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(context.expiryTime().duration().multipliedBy(2));
    assertThat(cache.asMap().entrySet().stream().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    assertThat(cache.asMap().entrySet().stream().toArray()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySet_equals(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    map.putAll(context.absent());

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(map.entrySet().equals(context.absent().entrySet())).isFalse();
    assertThat(context.absent().entrySet().equals(map.entrySet())).isFalse();

    context.cleanUp();
    assertThat(map.entrySet().equals(context.absent().entrySet())).isTrue();
    assertThat(context.absent().entrySet().equals(map.entrySet())).isTrue();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void entrySet_hashCode(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    map.putAll(context.absent());

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(map.entrySet().hashCode()).isEqualTo(context.absent().entrySet().hashCode());

    context.cleanUp();
    assertThat(map.entrySet().hashCode()).isEqualTo(context.absent().entrySet().hashCode());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void equals(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    map.putAll(context.absent());

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(map.equals(context.absent())).isFalse();
    assertThat(context.absent().equals(map)).isFalse();

    context.cleanUp();
    assertThat(map.equals(context.absent())).isTrue();
    assertThat(context.absent().equals(map)).isTrue();

    // AbstractMap may be non-symmetric if the size is equal but the iterator is empty
    context.ticker().advance(Duration.ofMinutes(1));
    assertThat(map.equals(context.absent()) && context.absent().equals(map)).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void hashCode(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    map.putAll(context.absent());

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(map.hashCode()).isEqualTo(context.absent().hashCode());

    context.cleanUp();
    assertThat(map.hashCode()).isEqualTo(context.absent().hashCode());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void toString(Map<Int, Int> map, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    map.putAll(context.absent());

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(parseToString(map)).containsExactlyEntriesIn(parseToString(context.absent()));

    context.cleanUp();
    assertThat(parseToString(map)).containsExactlyEntriesIn(parseToString(context.absent()));
  }

  private static Map<String, String> parseToString(Map<Int, Int> map) {
    return Splitter.on(',').trimResults().omitEmptyStrings().withKeyValueSeparator("=")
        .split(map.toString().replaceAll("[{}]", ""));
  }

  /* --------------- Policy --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
    expiryTime = Expire.ONE_MINUTE, mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
    expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
    expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
    expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
    startTime = {StartTime.RANDOM, StartTime.ONE_MINUTE_FROM_MAX})
  public void getIfPresentQuietly_expired(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey())).isNotNull();
    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey())).isNull();
  }

  /**
   * Ensures that variable expiration is run, as it may not have due to expiring in coarse batches.
   */
  private static void runVariableExpiration(CacheContext context) {
    if (context.expiresVariably()) {
      // Variable expires in coarse buckets at a time
      context.ticker().advance(Duration.ofSeconds(2));
      context.cleanUp();
    }
  }
}
