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
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.util.function.Function.identity;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.FixedRefresh;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * The test cases for caches that support the refresh after write policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class RefreshAfterWriteTest {

  /* --------------- refreshIfNeeded --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_nonblocking(CacheContext context) {
    Int key = context.absentKey();
    Int original = intern(Int.valueOf(1));
    Int refresh1 = intern(original.add(1));
    Int refresh2 = intern(refresh1.add(1));
    var duration = Duration.ofMinutes(2);

    var refresh = new AtomicBoolean();
    var reloads = new AtomicInteger();
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override
      public CompletableFuture<Int> asyncReload(Int key, Int oldValue, Executor executor) {
        reloads.incrementAndGet();
        await().untilTrue(refresh);
        return oldValue.add(1).asFuture();
      }
    });
    cache.put(key, original);
    context.ticker().advance(duration);
    ConcurrentTestHarness.execute(() -> assertThat(cache.get(key)).isAnyOf(original, refresh1));
    await().untilAtomic(reloads, is(1));

    assertThat(cache.get(key)).isEqualTo(original);
    refresh.set(true);

    Int refreshed = cache.get(key);
    assertThat(refreshed).isAnyOf(original, refresh1);

    await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(1));
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refresh1));
    await().untilAsserted(() -> assertThat(cache.policy().refreshes()).isEmpty());

    context.ticker().advance(duration);
    assertThat(cache.get(key)).isEqualTo(refresh2);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_failure(CacheContext context) {
    Int key = context.absentKey();
    var reloads = new AtomicInteger();
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override
      public CompletableFuture<Int> asyncReload(Int key, Int oldValue, Executor executor) {
        reloads.incrementAndGet();
        throw new IllegalStateException();
      }
    });
    cache.put(key, key);

    for (int i = 0; i < 5; i++) {
      context.ticker().advance(Duration.ofMinutes(2));
      Int value = cache.get(key);
      assertThat(value).isEqualTo(key);
      await().untilAtomic(reloads, is(i + 1));
    }
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      loader = Loader.REFRESH_INTERRUPTED, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.DIRECT)
  public void refreshIfNeeded_interrupted(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());

    assertThat(value).isNotNull();
    assertThat(Thread.interrupted()).isTrue();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING)
  public void refreshIfNeeded_replace(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentKey());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentKey())
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      removalListener = Listener.CONSUMING, loader = Loader.NULL)
  public void refreshIfNeeded_remove(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.EMPTY,
      removalListener = Listener.CONSUMING)
  public void refreshIfNeeded_noChange(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @CanIgnoreReturnValue
      @Override public Int reload(Int key, Int oldValue) {
        return oldValue;
      }
    });
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  public void refreshIfNeeded_replaced(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = cache.policy().refreshes().get(context.firstKey());

    cache.put(context.firstKey(), context.absentValue());
    future.complete(context.absentKey().negate());

    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(
            Map.entry(context.firstKey(), context.original().get(context.firstKey())),
            Map.entry(context.firstKey(), context.absentKey().negate()))
        .exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MILLISECOND, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE, mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expiryTime = Expire.ONE_MINUTE, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void refreshIfNeeded_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(10));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = cache.policy().refreshes().get(context.firstKey());

    context.ticker().advance(Duration.ofMinutes(1));
    future.complete(context.absentValue());
    assertThat(cache).isEmpty();

    assertThat(context).removalNotifications().withCause(EXPIRED)
        .contains(context.original());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Map.entry(context.firstKey(), context.absentValue()));
    assertThat(context).removalNotifications().hasSize(context.original().size() + 1);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  public void refreshIfNeeded_absent_newValue(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = cache.policy().refreshes().get(context.firstKey());

    cache.invalidate(context.firstKey());
    assertThat(cache).doesNotContainKey(context.firstKey());

    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.firstKey());

    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(
            Map.entry(context.firstKey(), context.original().get(context.firstKey())),
            Map.entry(context.firstKey(), context.absentValue()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_absent_nullValue(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = cache.policy().refreshes().get(context.firstKey());

    cache.invalidate(context.firstKey());
    future.complete(null);

    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  public void refreshIfNeeded_cancel_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.cancel(false);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(TestLoggerFactory.getLoggingEvents()).isEmpty();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  public void refreshIfNeeded_timeout_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.orTimeout(0, TimeUnit.SECONDS);
        await().until(future::isDone);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(TestLoggerFactory.getLoggingEvents()).isEmpty();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  public void refreshIfNeeded_error_log(CacheContext context) {
    var expected = new RuntimeException();
    CacheLoader<Int, Int> cacheLoader = key -> { throw expected; };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).hasCauseThat().isSameInstanceAs(expected);
    assertThat(event.getLevel()).isEqualTo(WARN);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  public void refreshIfNeeded_nullFuture(CacheContext context) {
    var refreshed = new AtomicBoolean();
    CacheLoader<Int, Int> loader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        refreshed.set(true);
        return null;
      }
    };

    var cache = context.isAsync()
        ? context.buildAsync(loader).synchronous()
        : context.build(loader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isNotNull();
    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).isInstanceOf(NullPointerException.class);
    assertThat(event.getLevel()).isEqualTo(WARN);

    assertThat(refreshed.get()).isTrue();
    assertThat(cache.policy().refreshes()).isEmpty();
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  /* --------------- getIfPresent --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.middleKey(), context.original().get(context.middleKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();

    if (context.isCaffeine()) {
      cache.policy().refreshes().get(context.middleKey()).complete(context.middleKey());
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.middleKey(), context.original().get(context.middleKey()))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.middleKey())).succeedsWith(context.middleKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.middleKey())).succeedsWith(context.middleKey().negate());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.middleKey(), context.original().get(context.middleKey()))
        .exclusively();
  }

  /* --------------- getAllPresent --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,  loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var results = cache.getAllPresent(context.firstMiddleLastKeys());

    assertThat(results).isNotEmpty();
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()))
        .containsExactlyEntriesIn(Maps.asMap(context.firstMiddleLastKeys(), key -> key));

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Maps.filterKeys(context.original(), context.firstMiddleLastKeys()::contains))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getAllPresent_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var expected = cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()))
        .containsExactlyEntriesIn(expected);

    if (context.isCaffeine()) {
      var replaced = new HashMap<Int, Int>();
      for (var key : context.firstMiddleLastKeys()) {
        cache.policy().refreshes().get(key).complete(key);
        replaced.put(key, context.original().get(key));
      }
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(replaced).exclusively();
    }
  }

  /* --------------- getFunc --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getFunc_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey(), identity());
    assertThat(value).isEqualTo(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.lastKey(), identity())).isEqualTo(context.lastKey());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.lastKey(), context.original().get(context.lastKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.PARTIAL, Population.FULL })
  public void getFunc_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = context.original()::get;
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey(), mappingFunction);
    assertThat(value).isNotNull();

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.lastKey(), mappingFunction)).isEqualTo(context.lastKey().negate());

    if (context.isCaffeine()) {
      cache.policy().refreshes().get(context.lastKey()).complete(context.lastKey());
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.lastKey(), context.original().get(context.lastKey()))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getFunc_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = context.original()::get;
    context.ticker().advance(Duration.ofSeconds(30));
    cache.get(context.firstKey(), mappingFunction).join();
    context.ticker().advance(Duration.ofSeconds(45));
    cache.get(context.lastKey(), mappingFunction).join(); // refreshed

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.lastKey(), context.original().get(context.lastKey()))
        .exclusively();
  }

  /* --------------- get --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void get_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.firstKey())).isEqualTo(context.firstKey());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.PARTIAL, Population.FULL })
  public void get_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.firstKey())).isEqualTo(context.firstKey().negate());

    if (context.isCaffeine()) {
      cache.policy().refreshes().get(context.firstKey()).complete(context.firstKey());
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.firstKey(), context.original().get(context.firstKey()))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void get_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    cache.get(context.firstKey()).join();
    cache.get(context.absentKey()).join();
    context.ticker().advance(Duration.ofSeconds(45));

    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).succeedsWith(context.firstKey());
    assertThat(cache).containsEntry(context.firstKey(), context.firstKey());

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED,
      compute = Compute.ASYNC)
  public void get_sameFuture(CacheContext context) {
    var done = new AtomicBoolean();
    var cache = context.buildAsync((Int key) -> {
      await().untilTrue(done);
      return intern(key.negate());
    });

    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    var original = cache.get(key);
    for (int i = 0; i < 10; i++) {
      context.ticker().advance(Duration.ofMinutes(1));
      var next = cache.get(key);
      assertThat(next).isSameInstanceAs(original);
    }
    done.set(true);
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, key.negate()));
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  public void get_slowRefresh(CacheContext context) {
    Int key = context.absentKey();
    Int originalValue = context.absentValue();
    Int refreshedValue = intern(originalValue.add(1));
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(done);
      return refreshedValue;
    });

    cache.put(key, originalValue);

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(originalValue);

    await().untilTrue(started);
    assertThat(cache).containsEntry(key, originalValue);

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(originalValue);

    done.set(true);
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshedValue));
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NULL)
  public void get_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).succeedsWith(key);
    assertThat(cache.get(key)).succeedsWithNull();
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- getAll --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(keys)).containsExactly(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey());

    // Trigger a refresh, ensure new values are present
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAll(keys)).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    var expected = Maps.toMap(context.firstMiddleLastKeys(), Int::negate);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(expected);

    // Trigger a refresh, returns old values
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(expected);

    if (context.isCaffeine()) {
      for (var key : keys) {
        cache.policy().refreshes().get(key).complete(key);
      }
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(Maps.filterKeys(context.original(), context.firstMiddleLastKeys()::contains))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(keys).join()).containsExactly(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey());

    // Trigger a refresh, may return old values
    context.ticker().advance(Duration.ofSeconds(45));
    cache.getAll(keys).join();

    // Ensure new values are present
    assertThat(cache.getAll(keys).join()).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  /* --------------- put --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void put(CacheContext context) {
    var started = new AtomicBoolean();
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int updated = Int.valueOf(2);
    Int refreshed = Int.valueOf(3);
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(Duration.ofMinutes(2));

    assertThat(started.get()).isFalse();
    assertThat(cache.getIfPresent(key)).isEqualTo(original);
    await().untilTrue(started);

    assertThat(cache.asMap().put(key, updated)).isEqualTo(original);
    refresh.set(true);

    await().untilAsserted(() -> assertThat(context).removalNotifications().hasSize(2));

    assertThat(cache).containsEntry(key, updated);
    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Map.entry(key, original), Map.entry(key, refreshed))
        .exclusively();
  }

  /* --------------- invalidate --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void invalidate(CacheContext context) {
    var started = new AtomicBoolean();
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(Duration.ofMinutes(2));

    assertThat(started.get()).isFalse();
    assertThat(cache.getIfPresent(key)).isEqualTo(original);
    await().untilTrue(started);

    cache.invalidate(key);
    refresh.set(true);

    await().until(() -> context.executor().submitted() == context.executor().completed());

    if (context.isGuava()) {
      // Guava does not protect against ABA when the entry was removed by allowing a possibly
      // stale value from being inserted.
      assertThat(cache.getIfPresent(key)).isEqualTo(refreshed);
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(key, original).exclusively();
    } else {
      // Maintain linearizability by discarding the refresh if completing after an explicit removal
      assertThat(cache.getIfPresent(key)).isNull();
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(Map.entry(key, original), Map.entry(key, refreshed))
          .exclusively();
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      loader = Loader.ASYNC_INCOMPLETE, refreshAfterWrite = Expire.ONE_MINUTE)
  public void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    int submitted;

    // trigger an automatic refresh
    submitted = context.executor().submitted();
    context.ticker().advance(Duration.ofMinutes(2));
    var value1 = cache.getIfPresent(context.absentKey());
    assertThat(value1).isEqualTo(context.absentValue());
    assertThat(context.executor().submitted()).isEqualTo(submitted + 1);
    var automatic1 = cache.policy().refreshes().get(context.absentKey());

    // return in-flight future
    var future1 = cache.refresh(context.absentKey());
    assertThat(future1).isSameInstanceAs(automatic1);
    assertThat(context.executor().submitted()).isEqualTo(submitted + 1);
    future1.complete(intern(context.absentValue().negate()));

    // trigger a new automatic refresh
    submitted = context.executor().submitted();
    context.ticker().advance(Duration.ofMinutes(2));
    var value2 = cache.getIfPresent(context.absentKey());
    assertThat(value2).isEqualTo(context.absentValue().negate());
    assertThat(context.executor().submitted()).isEqualTo(submitted + 1);
    var automatic2 = cache.policy().refreshes().get(context.absentKey());

    var future2 = cache.refresh(context.absentKey());
    assertThat(future2).isNotSameInstanceAs(future1);
    assertThat(future2).isSameInstanceAs(automatic2);
    future2.cancel(true);
  }

  /* --------------- Policy: refreshes --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void refreshes(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.getIfPresent(context.firstKey());
    assertThat(cache.policy().refreshes()).hasSize(1);
    assertThat(value).isNotNull();

    var future = cache.policy().refreshes().get(context.firstKey());
    assertThat(future).isNotNull();

    future.complete(Int.MAX_VALUE);
    assertThat(cache.policy().refreshes()).isExhaustivelyEmpty();
    assertThat(cache).containsEntry(context.firstKey(), Int.MAX_VALUE);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void refreshes_nullLookup(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).isNotNull();

    var future = cache.policy().refreshes().get(context.firstKey());
    assertThat(cache.policy().refreshes().get(null)).isNull();
    assertThat(cache.policy().refreshes().containsKey(null)).isFalse();
    assertThat(cache.policy().refreshes().containsValue(null)).isFalse();

    future.cancel(true);
  }

  /* --------------- Policy: refreshAfterWrite --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  public void getRefreshesAfter(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(1);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshAfter_negative(Cache<Int, Int> cache,
      CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    var duration = Duration.ofMinutes(-2);
    assertThrows(IllegalArgumentException.class, () ->
        refreshAfterWrite.setRefreshesAfter(duration));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshAfter_excessive(Cache<Int, Int> cache,
      CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(ChronoUnit.FOREVER.getDuration());
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshesAfter(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(2);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshesAfter_duration(CacheContext context,
      FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(2);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      refreshAfterWrite = Expire.ONE_MINUTE)
  public void ageOf(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(0);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(30);
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(75);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      refreshAfterWrite = Expire.ONE_MINUTE)
  public void ageOf_duration(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    // Truncated to seconds to ignore the LSB (nanosecond) used for refreshAfterWrite's lock
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(0);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(30);
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(75);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  public void ageOf_absent(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.absentKey())).isEmpty();
    assertThat(refreshAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void ageOf_expired(Cache<Int, Int> cache,
      CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }
}
