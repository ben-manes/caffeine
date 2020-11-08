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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Expiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Advance;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoWriter;
import com.github.benmanes.caffeine.cache.testing.RefreshAfterWrite;
import com.github.benmanes.caffeine.cache.testing.RemovalNotification;
import com.github.benmanes.caffeine.cache.testing.TrackingExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * The test cases for caches that support the refresh after write policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("PreferJavaTimeOverload")
@Test(dataProviderClass = CacheProvider.class)
public final class RefreshAfterWriteTest {

  /* --------------- getIfPresent --------------- */

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(-context.middleKey()));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(-context.middleKey()));

    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(futureOf(-context.middleKey())));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey()), is(futureOf(-context.middleKey())));

    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  /* --------------- getAllPresent --------------- */

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    int count = context.firstMiddleLastKeys().size();
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(count));

    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* --------------- getFunc --------------- */

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getFunc(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // refreshed

    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getFunc(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // refreshed

    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  /* --------------- get --------------- */

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);

    assertThat(cache.getIfPresent(context.firstKey()), is(-context.firstKey()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);

    assertThat(cache.getIfPresent(context.firstKey()), is(futureOf(-context.firstKey())));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED,
      compute = Compute.ASYNC, values = ReferenceType.STRONG)
  public void get_sameFuture(CacheContext context) {
    AtomicBoolean done = new AtomicBoolean();
    AsyncLoadingCache<Integer, Integer> cache = context.buildAsync(key -> {
      await().untilTrue(done);
      return -key;
    });

    Integer key = 1;
    cache.synchronous().put(key, key);
    CompletableFuture<Integer> original = cache.get(key);
    for (int i = 0; i < 10; i++) {
      context.ticker().advance(1, TimeUnit.MINUTES);
      CompletableFuture<Integer> next = cache.get(key);
      assertThat(next, is(sameInstance(original)));
    }
    done.set(true);
    await().until(() -> cache.synchronous().getIfPresent(key), is(-key));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  public void get_slowRefresh(CacheContext context) {
    Integer key = context.absentKey();
    Integer originalValue = context.absentValue();
    AtomicBoolean reloaded = new AtomicBoolean();
    AtomicInteger reloading = new AtomicInteger();
    ThreadPoolExecutor executor = (ThreadPoolExecutor)
        ((TrackingExecutor) context.executor()).delegate();
    LoadingCache<Integer, Integer> cache = context.build(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new AssertionError();
      }
      @Override public Integer reload(Integer key, Integer oldValue) {
        int count = reloading.incrementAndGet();
        await().untilTrue(reloaded);
        return count;
      }
    });

    cache.put(key, originalValue);

    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.get(key), is(originalValue));

    await().untilAtomic(reloading, is(1));
    assertThat(cache.getIfPresent(key), is(originalValue));

    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.get(key), is(originalValue));

    reloaded.set(true);
    await().until(() -> cache.get(key), is(not(originalValue)));
    await().until(executor::getQueue, is(empty()));
    assertThat(reloading.get(), is(1));
    assertThat(cache.get(key), is(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NULL)
  public void get_null(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = 1;
    cache.synchronous().put(key, key);
    context.ticker().advance(2, TimeUnit.MINUTES);
    await().until(() -> cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  /* --------------- getAll --------------- */

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll(LoadingCache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = ImmutableList.of(context.firstKey(), context.absentKey());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(keys), is(ImmutableMap.of(context.firstKey(), -context.firstKey(),
        context.absentKey(), context.absentKey())));

    // Trigger a refresh, may return old values
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.getAll(keys);

    // Ensure new values are present
    assertThat(cache.getAll(keys), is(ImmutableMap.of(context.firstKey(), context.firstKey(),
        context.absentKey(), context.absentKey())));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getAll(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = ImmutableList.of(context.firstKey(), context.absentKey());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(keys), is(futureOf(ImmutableMap.of(context.firstKey(),
        -context.firstKey(), context.absentKey(), context.absentKey()))));

    // Trigger a refresh, may return old values
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.getAll(keys);

    // Ensure new values are present
    assertThat(cache.getAll(keys), is(futureOf(ImmutableMap.of(context.firstKey(),
        context.firstKey(), context.absentKey(), context.absentKey()))));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  /* --------------- put --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void put(CacheContext context) {
    AtomicBoolean refresh = new AtomicBoolean();
    Integer key = context.absentKey();
    Integer original = 1;
    Integer updated = 2;
    Integer refreshed = 3;
    LoadingCache<Integer, Integer> cache = context.build(k -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(key), is(original));

    assertThat(cache.asMap().put(key, updated), is(original));
    refresh.set(true);

    await().until(() -> context.consumedNotifications().size(), is(2));
    List<Integer> removed = context.consumedNotifications().stream()
        .map(RemovalNotification::getValue).collect(toList());

    assertThat(cache.getIfPresent(key), is(updated));
    assertThat(removed, containsInAnyOrder(original, refreshed));
    assertThat(cache, hasRemovalNotifications(context, 2, RemovalCause.REPLACED));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  /* --------------- invalidate --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void invalidate(CacheContext context) {
    AtomicBoolean refresh = new AtomicBoolean();
    Integer key = context.absentKey();
    Integer original = 1;
    Integer refreshed = 2;
    LoadingCache<Integer, Integer> cache = context.build(k -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(key), is(original));

    cache.invalidate(key);
    refresh.set(true);

    await().until(() -> cache.getIfPresent(key), is(refreshed));
    await().until(() -> cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  /* --------------- Policy --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    assertThat(refreshAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    refreshAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(refreshAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(2L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(
        context.firstKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }

  /* --------------- Policy: oldest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    refreshAfterWrite.oldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    refreshAfterWrite.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void oldest_zero(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    assertThat(refreshAfterWrite.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, refreshAfterWrite = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(refreshAfterWrite.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, advanceOnPopulation = Advance.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    Map<Integer, Integer> oldest = refreshAfterWrite.oldest(Integer.MAX_VALUE);
    assertThat(oldest.keySet(), contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    Map<Integer, Integer> oldest = refreshAfterWrite.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* --------------- Policy: youngest --------------- */

  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    refreshAfterWrite.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    refreshAfterWrite.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void youngest_zero(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    assertThat(refreshAfterWrite.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, refreshAfterWrite = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(refreshAfterWrite.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, advanceOnPopulation = Advance.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    Map<Integer, Integer> youngest = refreshAfterWrite.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(keys, contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @RefreshAfterWrite Expiration<Integer, Integer> refreshAfterWrite) {
    Map<Integer, Integer> youngest = refreshAfterWrite.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
