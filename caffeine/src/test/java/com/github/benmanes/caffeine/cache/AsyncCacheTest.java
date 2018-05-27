/*
 * Copyright 2018 Ben Manes. All Rights Reserved.
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
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.CompletableFuture;
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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoWriter;
import com.github.benmanes.caffeine.testing.Awaits;

/**
 * The test cases for the {@link AsyncCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
@SuppressWarnings("FutureReturnValueIgnored")
public final class AsyncCacheTest {

  /* ---------------- getIfPresent -------------- */

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    cache.getIfPresent(null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getIfPresent_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.lastKey()), is(not(nullValue())));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- getFunc -------------- */

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.get(null, key -> null);
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullLoader(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.get(context.absentKey(), (Function<Integer, Integer>) null);
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKeyAndLoader(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    cache.get(null, (Function<Integer, Integer>) null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void getFunc_absent_null(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key, k -> null);

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isDone(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL, executor = CacheExecutor.THREADED,
      executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_null_async(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    Integer key = context.absentKey();
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<Integer> valueFuture = cache.get(key, k -> {
      Awaits.await().untilTrue(ready);
      return null;
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    ready.set(true);
    Awaits.await().untilTrue(done);
    Awaits.await().until(() -> !cache.synchronous().asMap().containsKey(context.absentKey()));
    Awaits.await().until(() -> context, both(hasMissCount(1)).and(hasHitCount(0)));
    Awaits.await().until(() -> context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isDone(), is(true));
    assertThat(cache.synchronous().asMap(), not(hasKey(key)));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void getFunc_absent_failure(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> valueFuture = cache.get(context.absentKey(),
        k -> { throw new IllegalStateException(); });

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_failure_async(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<Integer> valueFuture = cache.get(context.absentKey(), k -> {
      Awaits.await().untilTrue(ready);
      throw new IllegalStateException();
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    ready.set(true);
    Awaits.await().untilTrue(done);
    Awaits.await().until(() -> !cache.synchronous().asMap().containsKey(context.absentKey()));
    Awaits.await().until(() -> context, both(hasMissCount(1)).and(hasHitCount(0)));
    Awaits.await().until(() -> context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL, executor = CacheExecutor.THREADED)
  public void getFunc_absent_cancelled(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<Integer> valueFuture = cache.get(context.absentKey(), k -> {
      Awaits.await().until(done::get);
      return null;
    });
    valueFuture.whenComplete((r, e) -> done.set(true));
    valueFuture.cancel(true);

    Awaits.await().untilTrue(done);
    await().until(() -> context, both(hasMissCount(1)).and(hasHitCount(0)));
    await().until(() -> context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isDone(), is(true));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void getFunc_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> value = cache.get(key, k -> context.absentValue());
    assertThat(value, is(futureOf(context.absentValue())));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getFunc_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
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
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.get(null, (key, executor) -> CompletableFuture.completedFuture(null));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullLoader(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    BiFunction<Integer, Executor, CompletableFuture<Integer>> mappingFunction = null;
    cache.get(context.absentKey(), mappingFunction);
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullKeyAndLoader(AsyncCache<Integer, Integer> cache
      , CacheContext context) {
    BiFunction<Integer, Executor, CompletableFuture<Integer>> mappingFunction = null;
    cache.get(null, mappingFunction);
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getBiFunc_throwsException(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    try {
      cache.get(context.absentKey(), (key, executor) -> { throw new IllegalStateException(); });
    } finally {
      assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
      assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    }
  }

  @CheckNoWriter
  @CacheSpec(loader = Loader.NULL)
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_absent_null(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    cache.get(context.absentKey(), (k, executor) -> null);
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_failure_before(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = new CompletableFuture<>();
    failedFuture.completeExceptionally(new IllegalStateException());

    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key, (k, executor) -> failedFuture);

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_failure_after(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = new CompletableFuture<>();

    Integer key = context.absentKey();
    CompletableFuture<Integer> valueFuture = cache.get(key, (k, executor) -> failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_cancelled(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> cancelledFuture = new CompletableFuture<>();
    cache.get(context.absentKey(), (k, executor) -> cancelledFuture);
    cancelledFuture.cancel(true);

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void getBiFunc_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> value = cache.get(key,
        (k, executor) -> CompletableFuture.completedFuture(context.absentValue()));
    assertThat(value, is(futureOf(context.absentValue())));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getBiFunc_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
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

  /* ---------------- put -------------- */

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    cache.put(null, value);
  }

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), null);
  }

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKeyAndValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert_failure_before(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = CompletableFuture.completedFuture(null);
    failedFuture.completeExceptionally(new IllegalStateException());

    cache.put(context.absentKey(), failedFuture);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert_failure_after(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = new CompletableFuture<>();

    cache.put(context.absentKey(), failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    cache.put(context.absentKey(), value);
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() + 1));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
    assertThat(cache.synchronous().getIfPresent(context.absentKey()), is(context.absentValue()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_replace_failure_before(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = CompletableFuture.completedFuture(null);
    failedFuture.completeExceptionally(new IllegalStateException());

    cache.put(context.middleKey(), failedFuture);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() - 1));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_replace_failure_after(AsyncCache<Integer, Integer> cache,
      CacheContext context) {
    CompletableFuture<Integer> failedFuture = CompletableFuture.completedFuture(null);

    cache.put(context.middleKey(), failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() - 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_nullValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(null);
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.put(key, value);
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() - count));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  /* ---------------- serialize -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(writer = Writer.EXCEPTIONAL)
  public void serialize(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache, is(reserializable()));
  }
}
