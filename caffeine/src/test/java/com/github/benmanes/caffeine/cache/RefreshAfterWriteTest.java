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

import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
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
import static java.util.Map.entry;
import static org.hamcrest.Matchers.is;

import java.time.Duration;
import java.util.List;
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
        return oldValue.add(1).asFuture();
      }
    });
    cache.put(key, original);
    context.ticker().advance(duration);
    ConcurrentTestHarness.execute(() -> cache.get(key));
    await().untilAtomic(reloads, is(1));

    assertThat(cache.get(key)).isEqualTo(original);
    refresh.set(true);
    cache.get(key);

    await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(1));
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refresh1));
    await().untilAsserted(() -> assertThat(cache.policy().refreshes()).isEmpty());

    context.ticker().advance(duration);
    assertThat(cache.get(key)).isEqualTo(refresh1);

    await().untilAtomic(reloads, is(2));
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refresh2));
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

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING)
  public void refreshIfNeeded_replace(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentKey());
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.get(context.absentKey());

    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentKey()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      removalListener = Listener.CONSUMING, loader = Loader.NULL)
  public void refreshIfNeeded_remove(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.get(context.absentKey());

    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.absentKey(), context.absentValue()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.EMPTY,
      removalListener = Listener.CONSUMING)
  public void refreshIfNeeded_noChange(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public Int reload(Int key, Int oldValue) {
        return oldValue;
      }
    });
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.get(context.absentKey());

    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.IDENTITY, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_discard(LoadingCache<Int, Int> cache, CacheContext context) {
    var executor = (TrackingExecutor) context.executor();
    executor.pause();
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.get(context.firstKey());

    assertThat(cache.policy().refreshes()).isNotEmpty();
    cache.put(context.firstKey(), context.absentValue());
    executor.resume();

    await().until(() -> executor.submitted() == executor.completed());
    assertThat(context).removalNotifications().withCause(REPLACED).contains(
        entry(context.firstKey(), context.original().get(context.firstKey())),
        entry(context.firstKey(), context.firstKey())).exclusively();
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.IDENTITY, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_absent_newValue(LoadingCache<Int, Int> cache, CacheContext context) {
    var executor = (TrackingExecutor) context.executor();
    executor.pause();
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.get(context.firstKey());

    assertThat(cache.policy().refreshes()).isNotEmpty();
    cache.invalidate(context.firstKey());
    executor.resume();

    await().until(() -> executor.submitted() == executor.completed());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.firstKey());
    assertThat(cache).doesNotContainKey(context.firstKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.NULL, executor = CacheExecutor.THREADED)
  public void refreshIfNeeded_absent_nullValue(LoadingCache<Int, Int> cache, CacheContext context) {
    var executor = (TrackingExecutor) context.executor();
    executor.pause();
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.get(context.firstKey());

    assertThat(cache.policy().refreshes()).isNotEmpty();
    cache.invalidate(context.firstKey());
    executor.resume();

    await().until(() -> executor.submitted() == executor.completed());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()));
    assertThat(cache).doesNotContainKey(context.firstKey());
  }

  /* --------------- getIfPresent --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey())).succeedsWith(context.middleKey().negate());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.middleKey())).succeedsWith(context.middleKey().negate());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
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
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys())).hasSize(count);

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
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

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getFunc_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // refreshed

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
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

    assertThat(cache).containsEntry(context.firstKey(), context.firstKey().negate());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void get_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);

    var oldValue = cache.getIfPresent(context.firstKey());
    assertThat(oldValue).succeedsWith(context.firstKey().negate());
    assertThat(cache).containsEntry(context.firstKey(), context.firstKey());

    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
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
      assertThat(next).isSameInstanceAs(original);
    }
    done.set(true);
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, key.negate()));
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
    assertThat(cache.get(key)).isEqualTo(originalValue);

    await().untilTrue(started);
    assertThat(cache).containsEntry(key, originalValue);

    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.get(key)).isEqualTo(originalValue);

    done.set(true);
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshedValue));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NULL)
  public void get_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    context.ticker().advance(2, TimeUnit.MINUTES);
    assertThat(cache.get(key)).succeedsWith(key);
    assertThat(cache.get(key)).succeedsWithNull();
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- getAll --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(keys)).containsExactly(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey());

    // Trigger a refresh, may return old values
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.getAll(keys);

    // Ensure new values are present
    assertThat(cache.getAll(keys)).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  @SuppressWarnings("FutureReturnValueIgnored")
  public void getAll_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(keys).join()).containsExactly(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey());

    // Trigger a refresh, may return old values
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.getAll(keys);

    // Ensure new values are present
    assertThat(cache.getAll(keys).join()).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  /* --------------- put --------------- */

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
    context.ticker().advance(2, TimeUnit.MINUTES);

    assertThat(started.get()).isFalse();
    assertThat(cache.getIfPresent(key)).isEqualTo(original);
    await().untilTrue(started);

    assertThat(cache.asMap().put(key, updated)).isEqualTo(original);
    refresh.set(true);

    await().untilAsserted(() -> assertThat(context).removalNotifications().hasSize(2));

    assertThat(cache).containsEntry(key, updated);
    assertThat(context).removalNotifications().containsExactlyValues(original, refreshed);

    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(2).exclusively();
  }

  /* --------------- invalidate --------------- */

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
    context.ticker().advance(2, TimeUnit.MINUTES);

    assertThat(started.get()).isFalse();
    assertThat(cache.getIfPresent(key)).isEqualTo(original);
    await().untilTrue(started);

    cache.invalidate(key);
    refresh.set(true);

    var executor = (TrackingExecutor) context.executor();
    await().until(() -> executor.submitted() == executor.completed());

    if (context.isGuava()) {
      // Guava does not protect against ABA when the entry was removed by allowing a possibly
      // stale value from being inserted.
      assertThat(cache.getIfPresent(key)).isEqualTo(refreshed);
    } else {
      // Maintain linearizability by discarding the refresh if completing after an explicit removal
      assertThat(cache.getIfPresent(key)).isNull();
    }

    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(1);
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
    assertThat(executor.submitted()).isEqualTo(submitted + 1);

    // return in-flight future
    var future1 = cache.refresh(context.absentKey());
    assertThat(executor.submitted()).isEqualTo(submitted + 1);
    future1.complete(context.absentValue().negate());

    // trigger a new automatic refresh
    submitted = executor.submitted();
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.getIfPresent(context.absentKey());
    assertThat(executor.submitted()).isEqualTo(submitted + 1);

    var future2 = cache.refresh(context.absentKey());
    assertThat(future2).isNotSameInstanceAs(future1);
    future2.cancel(true);
  }

  /* --------------- Policy: refreshes --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void refreshes(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.getIfPresent(context.firstKey());
    assertThat(cache.policy().refreshes()).hasSize(1);

    var future = cache.policy().refreshes().get(context.firstKey());
    assertThat(future).isNotNull();

    future.complete(Int.valueOf(Integer.MAX_VALUE));
    assertThat(cache.policy().refreshes()).isExhaustivelyEmpty();
    assertThat(cache).containsEntry(context.firstKey(), Int.valueOf(Integer.MAX_VALUE));
  }

  /* --------------- Policy: refreshAfterWrite --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void getRefreshesAfter(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(1);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshesAfter(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(2, TimeUnit.MINUTES);
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(2);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void setRefreshesAfter_duration(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(2);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(2);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(0);
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(30);
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(75);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_duration(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    // Truncated to seconds to ignore the LSB (nanosecond) used for refreshAfterWrite's lock
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).get().toSeconds()).isEqualTo(0);
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).get().toSeconds()).isEqualTo(30);
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).get().toSeconds()).isEqualTo(75);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE)
  public void ageOf_absent(CacheContext context,
      @RefreshAfterWrite FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
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
    assertThat(refreshAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }
}
