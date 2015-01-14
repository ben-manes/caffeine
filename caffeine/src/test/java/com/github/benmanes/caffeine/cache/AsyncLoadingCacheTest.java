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

import static com.github.benmanes.caffeine.cache.IsCacheReserializable.reserializable;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static com.github.benmanes.caffeine.matchers.IsFutureValue.futureOf;
import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases for the {@link AsyncLoadingCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsyncLoadingCacheTest {

  /* ---------------- CacheLoader -------------- */

  @Test
  public void asyncLoad() throws Exception {
    CacheLoader<Integer, ?> loader = key -> key;
    CompletableFuture<?> future = loader.asyncLoad(1, MoreExecutors.directExecutor());
    assertThat(future.get(), is(1));
  }

  @Test(enabled = false, expectedExceptions = UnsupportedOperationException.class)
  public void asyncLoadAll() {
    CacheLoader<Object, ?> loader = key -> key;
    loader.asyncLoadAll(Collections.<Object>emptyList(), MoreExecutors.directExecutor());
  }

  /* ---------------- getFunc -------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKey(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null, key -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullLoader(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.get(context.absentKey(), (Function<Integer, Integer>) null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKeyAndLoader(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null, (Function<Integer, Integer>) null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DIRECT)
  public void getFunc_absent_failure(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key,
        k -> { throw new IllegalStateException(); });

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DEFAULT)
  public void getFunc_absent_failure_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    Integer key = context.absentKey();
    AtomicBoolean ready = new AtomicBoolean();
    CompletableFuture<Integer> valueFuture = cache.get(key, k -> {
      await().untilTrue(ready);
      throw new IllegalStateException();
    });
    ready.set(true);

    try {
      valueFuture.get();
    } catch (Exception ignored) {}

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> value = cache.get(key, k -> context.absentValue());
    assertThat(value, is(futureOf(context.absentValue())));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getFunc_present(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader),
        is(futureOf(context.original().get(context.firstKey()))));
    assertThat(cache.get(context.middleKey(), loader),
        is(futureOf(context.original().get(context.middleKey()))));
    assertThat(cache.get(context.lastKey(), loader),
        is(futureOf(context.original().get(context.lastKey()))));

    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- getBiFunc -------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullKey(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null, (key, executor) -> CompletableFuture.completedFuture(null));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullLoader(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    BiFunction<Integer, Executor, CompletableFuture<Integer>> mappingFunction = null;
    cache.get(context.absentKey(), mappingFunction);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullKeyAndLoader(AsyncLoadingCache<Integer, Integer> cache) {
    BiFunction<Integer, Executor, CompletableFuture<Integer>> mappingFunction = null;
    cache.get(null, mappingFunction);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getBiFunc_throwsException(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    try {
      cache.get(context.absentKey(), (key, executor) -> { throw new IllegalStateException(); });
    } finally {
      assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
      assertThat(cache.synchronous().getIfPresent(context.absentKey()), is(nullValue()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DIRECT)
  public void getBiFunc_absent_failure(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = CompletableFuture.completedFuture(null);
    failedFuture.obtrudeException(new IllegalStateException());

    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key, (k, executor) -> failedFuture);

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DEFAULT)
  public void getBiFunc_absent_failure_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    AtomicBoolean ready = new AtomicBoolean();
    CompletableFuture<Integer> failedFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      throw new IllegalStateException();
    });

    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key, (k, executor) -> failedFuture);
    ready.set(true);

    try {
      valueFuture.get();
    } catch (Exception ignored) {}
    while (failedFuture.getNumberOfDependents() != 0) {}

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> value = cache.get(key,
        (k, executor) -> CompletableFuture.completedFuture(context.absentValue()));
    assertThat(value, is(futureOf(context.absentValue())));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getBiFunc_present(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    BiFunction<Integer, Executor, CompletableFuture<Integer>> loader =
        (key, executor) -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader),
        is(futureOf(context.original().get(context.firstKey()))));
    assertThat(cache.get(context.middleKey(), loader),
        is(futureOf(context.original().get(context.middleKey()))));
    assertThat(cache.get(context.lastKey(), loader),
        is(futureOf(context.original().get(context.lastKey()))));

    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- get -------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey()), is(futureOf(context.absentValue())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  public void get_absent_failure(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = cache.get(context.absentKey());
    assertThat(future.isCompletedExceptionally(), is(true));
    assertThat(cache.synchronous().getIfPresent(context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DEFAULT, loader = Loader.EXCEPTIONAL)
  public void get_absent_failure_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    AtomicBoolean ready = new AtomicBoolean();
    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key);
    ready.set(true);

    try {
      valueFuture.get();
    } catch (Exception ignored) {}

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey()), futureOf(-context.firstKey()));
    assertThat(cache.get(context.middleKey()), futureOf(-context.middleKey()));
    assertThat(cache.get(context.lastKey()), futureOf(-context.lastKey()));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- getAll -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_null(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    cache.getAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_nullKey(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    cache.getAll(Collections.singletonList(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_empty(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) throws Exception {
    CompletableFuture<Map<Integer, Integer>> result = cache.getAll(ImmutableList.of());
    assertThat(result.get().size(), is(0));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAll_immutable(AsyncLoadingCache<Integer, Integer> cache, CacheContext context)
      throws Exception {
    cache.getAll(context.absentKeys()).get().clear();
  }

  @Test(dataProvider = "caches", expectedExceptions = ExecutionException.class)
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  public void getAll_absent_failure(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) throws Exception {
    try {
      cache.getAll(context.absentKeys()).get();
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = context.loader().isBulk()
          ? 1
          : (context.isAsync() ? misses : 1);
      assertThat(context, both(hasMissCount(misses)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(loadFailures)));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_absent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context)
      throws Exception {
    Map<Integer, Integer> result = cache.getAll(context.absentKeys()).get();

    int count = context.absentKeys().size();
    int loads = context.loader().isBulk() ? 1 : count;
    assertThat(result.size(), is(count));
    assertThat(context, both(hasMissCount(count)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(loads)).and(hasLoadFailureCount(0)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_partial(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) throws Exception {
    Map<Integer, Integer> expect = new HashMap<>();
    expect.put(context.firstKey(), -context.firstKey());
    expect.put(context.middleKey(), -context.middleKey());
    expect.put(context.lastKey(), -context.lastKey());
    Map<Integer, Integer> result = cache.getAll(expect.keySet()).get();

    assertThat(result, is(equalTo(expect)));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(expect.size())));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.BULK_NEGATIVE_EXCEEDS },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_exceeds(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) throws Exception {
    Map<Integer, Integer> result = cache.getAll(context.absentKeys()).get();

    assertThat(result.keySet(), equalTo(context.absentKeys()));
    assertThat(cache.synchronous().estimatedSize(),
        is(greaterThan(context.initialSize() + context.absentKeys().size())));

    assertThat(context, both(hasMissCount(result.size())).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- put -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, context.absentValue());
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullValue(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKeyAndValue(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert_fail(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> failedFuture = CompletableFuture.completedFuture(null);
    failedFuture.obtrudeException(new IllegalStateException());

    cache.put(context.absentKey(), failedFuture);
    assertThat(cache.synchronous().getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DEFAULT)
  public void put_insert_failure_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    AtomicBoolean ready = new AtomicBoolean();
    CompletableFuture<Integer> failedFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      throw new IllegalStateException();
    });

    Integer key = context.absentKey();
    cache.put(key, failedFuture);
    ready.set(true);

    try {
      failedFuture.get();
    } catch (Exception ignored) {}
    while (failedFuture.getNumberOfDependents() != 0) {}

    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(cache.synchronous().getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    cache.put(context.absentKey(), value);
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() + 1));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
    assertThat(cache.synchronous().getIfPresent(context.absentKey()), is(context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DEFAULT)
  public void put_replace_failure_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context) {
    AtomicBoolean ready = new AtomicBoolean();
    CompletableFuture<Integer> failedFuture = CompletableFuture.supplyAsync(() -> {
      await().untilTrue(ready);
      throw new IllegalStateException();
    });

    Integer key = context.absentKey();
    cache.put(key, failedFuture);

    CompletableFuture<Integer> successFuture =
        CompletableFuture.completedFuture(context.absentValue());

    cache.put(key, successFuture);
    ready.set(true);

    try {
      failedFuture.get();
    } catch (Exception ignored) {}
    while (failedFuture.getNumberOfDependents() != 0) {}

    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(1)));
    assertThat(cache.synchronous().getIfPresent(key), is(context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.put(key, value);
      assertThat(cache.get(key), is(futureOf(context.absentValue())));
    }
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- serialize -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void serialize(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache, is(reserializable()));
  }
}
