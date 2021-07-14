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

import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static com.github.benmanes.caffeine.testing.IsInt.isInt;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.time.Duration;
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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RefreshAfterWrite;
import com.github.benmanes.caffeine.cache.testing.RemovalNotification;
import com.github.benmanes.caffeine.cache.testing.TrackingExecutor;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;

/**
 * The test cases for caches that support the refresh after write policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("PreferJavaTimeOverload")
@Test(dataProviderClass = CacheProvider.class)
public final class RefreshAfterWriteTest {

  /* --------------- refreshIfNeeded --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_nonblocking(CacheContext context) {
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refresh1 = original.add(1);
    Int refresh2 = refresh1.add(1);
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
        return CompletableFuture.completedFuture(oldValue.add(1));
      }
    });
    cache.put(key, original);
    context.ticker().advance(duration);
    ConcurrentTestHarness.execute(() -> cache.get(key));
    await().untilAtomic(reloads, is(1));

    assertThat(cache.get(key), is(original));
    refresh.set(true);

    await().until(() -> cache.get(key), is(refresh1));
    assertThat(reloads.get(), is(1));

    context.ticker().advance(duration);
    assertThat(cache.get(key), is(refresh1));
    await().untilAtomic(reloads, is(2));
    await().until(() -> cache.get(key), is(refresh2));
  }

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
      context.ticker().advance(2, TimeUnit.MINUTES);
      cache.get(key);
      await().untilAtomic(reloads, is(i + 1));
    }
  }

  /* --------------- getIfPresent --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(context.middleKey().negate()));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(context.middleKey().negate()));

    assertThat(cache.estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(futureOf(context.middleKey().negate())));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(futureOf(context.middleKey().negate())));

    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  /* --------------- getAllPresent --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent(LoadingCache<Int, Int> cache, CacheContext context) {
    int count = context.firstMiddleLastKeys().size();
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(count));

    assertThat(cache.estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* --------------- getFunc --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getFunc(LoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // refreshed

    assertThat(cache.estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getFunc(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // refreshed

    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  /* --------------- get --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);

    assertThat(cache.getIfPresent(context.firstKey()), is(context.firstKey().negate()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);

    assertThat(cache.getIfPresent(context.firstKey()), is(futureOf(context.firstKey().negate())));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED,
      compute = Compute.ASYNC, values = ReferenceType.STRONG)
  public void get_sameFuture(CacheContext context) {
    var done = new AtomicBoolean();
    var cache = context.buildAsync((Int key) -> {
      await().untilTrue(done);
      return key.negate();
    });

    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    var original = cache.get(key);
    for (int i = 0; i < 10; i++) {
      context.ticker().advance(1, TimeUnit.MINUTES);
      var next = cache.get(key);
      assertThat(next, is(sameInstance(original)));
    }
    done.set(true);
    await().until(() -> cache.synchronous().getIfPresent(key), is(key.negate()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  public void get_slowRefresh(CacheContext context) {
    Int key = context.absentKey();
    Int originalValue = context.absentValue();
    Int refreshedValue = originalValue.add(1);
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(done);
      return refreshedValue;
    });

    cache.put(key, originalValue);

    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.get(key), is(originalValue));

    await().untilTrue(started);
    assertThat(cache.getIfPresent(key), is(originalValue));

    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.get(key), is(originalValue));

    done.set(true);
    await().until(() -> cache.policy().getIfPresentQuietly(key), is(refreshedValue));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NULL)
  public void get_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    context.ticker().advance(2, TimeUnit.MINUTES);
    await().until(() -> cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  /* --------------- getAll --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(keys), is(Map.of(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey())));

    // Trigger a refresh, may return old values
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.getAll(keys);

    // Ensure new values are present
    assertThat(cache.getAll(keys), is(Map.of(context.firstKey(), context.firstKey(),
        context.absentKey(), context.absentKey())));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getAll(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(keys), is(futureOf(Map.of(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey()))));

    // Trigger a refresh, may return old values
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.getAll(keys);

    // Ensure new values are present
    assertThat(cache.getAll(keys), is(futureOf(Map.of(context.firstKey(),
        context.firstKey(), context.absentKey(), context.absentKey()))));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  /* --------------- put --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void put(CacheContext context) {
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int updated = Int.valueOf(2);
    Int refreshed = Int.valueOf(3);
    var cache = context.build((Int k) -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(key), is(original));

    assertThat(cache.asMap().put(key, updated), is(original));
    refresh.set(true);

    await().until(() -> context.removalNotifications().size(), is(2));
    var removed = context.removalNotifications().stream()
        .map(RemovalNotification::getValue).collect(toList());

    assertThat(cache.getIfPresent(key), is(updated));
    assertThat(removed, containsInAnyOrder(original, refreshed));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(2, RemovalCause.REPLACED));
    verifyStats(context, verifier -> verifier.success(1).failures(0));
  }

  /* --------------- invalidate --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void invalidate(CacheContext context) {
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    var cache = context.build((Int k) -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(key), is(original));

    cache.invalidate(key);
    refresh.set(true);

    var executor = (TrackingExecutor) context.executor();
    await().until(() -> executor.submitted() == executor.completed());

    if (context.implementation() == Implementation.Guava) {
      // Guava does not protect against ABA when the entry was removed by allowing a possibly
      // stale value from being inserted.
      assertThat(cache.getIfPresent(key), is(refreshed));
    } else {
      // Maintain linearizability by discarding the refresh if completing after an explicit removal
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }

    verifyRemovalListener(context, verifier -> verifier.hasCount(1, RemovalCause.EXPLICIT));
    verifyStats(context, verifier -> verifier.success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      loader = Loader.ASYNC_INCOMPLETE, refreshAfterWrite = Expire.ONE_MINUTE)
  public void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    var executor = (TrackingExecutor) context.executor();
    int submitted;

    // trigger an automatic refresh
    submitted = executor.submitted();
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.getIfPresent(context.absentKey());
    assertThat(executor.submitted(), is(submitted + 1));

    // return in-flight future
    var future1 = cache.refresh(context.absentKey());
    assertThat(executor.submitted(), is(submitted + 1));
    future1.complete(context.absentValue().negate());

    // trigger a new automatic refresh
    submitted = executor.submitted();
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.getIfPresent(context.absentKey());
    assertThat(executor.submitted(), is(submitted + 1));

    var future2 = cache.refresh(context.absentKey());
    assertThat(future2, is(not(sameInstance(future1))));
    future2.cancel(true);
  }

  /* --------------- Policy: refreshes --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void refreshes(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.getIfPresent(context.firstKey());
    assertThat(cache.policy().refreshes(), is(aMapWithSize(1)));

    var future = cache.policy().refreshes().get(context.firstKey());
    assertThat(future, is(not(nullValue())));

    future.complete(Int.valueOf(Integer.MAX_VALUE));
    assertThat(cache.policy().refreshes(), is(anEmptyMap()));
    assertThat(cache.getIfPresent(context.firstKey()), isInt(Integer.MAX_VALUE));
  }

  /* --------------- Policy: refreshAfterWrite --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void getRefreshesAfter(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes(), is(1L));
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshesAfter(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(2, TimeUnit.MINUTES);
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes(), is(2L));
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES), is(2L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshesAfter_duration(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes(), is(2L));
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES), is(2L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(75L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_duration(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).get().toSeconds(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).get().toSeconds(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).get().toSeconds(), is(75L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void ageOf_absent(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(
        context.absentKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, population = Population.EMPTY)
  public void ageOf_expired(Cache<Int, Int> cache, CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(refreshAfterWrite.ageOf(
        context.absentKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }
}
