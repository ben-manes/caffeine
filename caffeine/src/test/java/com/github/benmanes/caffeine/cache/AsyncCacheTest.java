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
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link AsyncCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
@SuppressWarnings("FutureReturnValueIgnored")
public final class AsyncCacheTest {

  /* --------------- getIfPresent --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getIfPresent(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getIfPresent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.lastKey()), is(not(nullValue())));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- getFunc --------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.get(null, key -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.get(context.absentKey(), (Function<Int, Int>) null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKeyAndLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.get(null, (Function<Int, Int>) null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var valueFuture = cache.get(key, k -> null);
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isDone(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_null_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = cache.get(key, k -> {
      await().untilTrue(ready);
      return null;
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    ready.set(true);
    await().untilTrue(done);
    await().until(() -> !cache.synchronous().asMap().containsKey(context.absentKey()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isDone(), is(true));
    assertThat(cache.synchronous().asMap(), not(hasKey(key)));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    var valueFuture = cache.get(context.absentKey(), k -> { throw new IllegalStateException(); });
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_failure_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    var valueFuture = cache.get(context.absentKey(), k -> {
      await().untilTrue(ready);
      throw new IllegalStateException();
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    ready.set(true);
    await().untilTrue(done);
    await().until(() -> !cache.synchronous().asMap().containsKey(context.absentKey()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_cancelled(AsyncCache<Int, Int> cache, CacheContext context) {
    var done = new AtomicBoolean();
    var valueFuture = cache.get(context.absentKey(), k -> {
      await().until(done::get);
      return null;
    });
    valueFuture.whenComplete((r, e) -> done.set(true));
    valueFuture.cancel(true);

    await().untilTrue(done);
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isDone(), is(true));
    await().until(() -> cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var value = cache.get(key, k -> context.absentValue());
    assertThat(value, is(futureOf(context.absentValue())));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getFunc_present(AsyncCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader),
        is(futureOf(context.original().get(context.firstKey()))));
    assertThat(cache.get(context.middleKey(), loader),
        is(futureOf(context.original().get(context.middleKey()))));
    assertThat(cache.get(context.lastKey(), loader),
        is(futureOf(context.original().get(context.lastKey()))));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- getBiFunc --------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.get(null, (key, executor) -> CompletableFuture.completedFuture(null));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Int, Executor, CompletableFuture<Int>> mappingFunction = null;
    cache.get(context.absentKey(), mappingFunction);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_nullKeyAndLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Int, Executor, CompletableFuture<Int>> mappingFunction = null;
    cache.get(null, mappingFunction);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getBiFunc_throwsException(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.get(context.absentKey(), (key, executor) -> { throw new IllegalStateException(); });
    } finally {
      verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));
      assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    }
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getBiFunc_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.get(context.absentKey(), (k, executor) -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_failure_before(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = new CompletableFuture<Int>();
    failedFuture.completeExceptionally(new IllegalStateException());

    Int key = context.absentKey();
    var valueFuture = cache.get(key, (k, executor) -> failedFuture);
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_failure_after(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = new CompletableFuture<Int>();

    Int key = context.absentKey();
    var valueFuture = cache.get(key, (k, executor) -> failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_cancelled(AsyncCache<Int, Int> cache, CacheContext context) {
    var cancelledFuture = new CompletableFuture<Int>();
    cache.get(context.absentKey(), (k, executor) -> cancelledFuture);
    cancelledFuture.cancel(true);

    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var value = cache.get(key, (k, executor) -> context.absentValue().asFuture());
    assertThat(value, is(futureOf(context.absentValue())));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getBiFunc_present(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Int, Executor, CompletableFuture<Int>> loader =
        (key, executor) -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader),
        is(futureOf(context.original().get(context.firstKey()))));
    assertThat(cache.get(context.middleKey(), loader),
        is(futureOf(context.original().get(context.middleKey()))));
    assertThat(cache.get(context.lastKey(), loader),
        is(futureOf(context.original().get(context.lastKey()))));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- getAllFunc --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_nullKeys(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(null, keys -> { throw new AssertionError(); });
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_nullKeys_nullFunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(null, (Function<Set<? extends Int>, Map<Int, Int>>) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_nullFunction(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.original().keySet(), (Function<Set<? extends Int>, Map<Int, Int>>) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(Collections.singletonList(null), keys -> { throw new AssertionError(); });
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = CompletionException.class)
  public void getAllFunction_absent_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys(), keys -> { throw new IllegalStateException(); }).join();
    } finally {
      int misses = context.absentKeys().size();
      verifyStats(context, verifier -> verifier.hits(0).misses(misses).success(0).failures(1));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), keys -> context.absent()).join();

    int count = context.absentKeys().size();
    assertThat(result.size(), is(count));
    verifyStats(context, verifier -> verifier.hits(0).misses(count).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_present_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet(), keys -> {
      assertThat(Iterables.size(keys), is(lessThan(expect.keySet().size())));
      return keys.stream().collect(toMap(identity(), Int::negate));
    }).join();

    assertThat(result, is(equalTo(expect)));
    verifyStats(context, verifier -> verifier.hits(expect.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_exceeds(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), keys -> {
      var moreKeys = new ArrayList<Int>(keys);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return moreKeys.stream().collect(toMap(identity(), Int::negate));
    }).join();

    assertThat(result.keySet(), equalTo(context.absentKeys()));
    assertThat(cache.synchronous().estimatedSize(),
        is(greaterThan(context.initialSize() + context.absentKeys().size())));
    verifyStats(context, verifier -> verifier.hits(0).misses(result.size()).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_duplicates(AsyncCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys, keysToLoad -> {
      assertThat(List.copyOf(keysToLoad), is(equalTo(ImmutableSet.copyOf(keysToLoad).asList())));
      return keysToLoad.stream().collect(toMap(identity(), Int::negate));
    }).join();

    verifyStats(context, verifier ->
        verifier.hits(context.initialSize()).misses(absentKeys.size()).success(1).failures(0));
    assertThat(result.keySet(), is(equalTo(ImmutableSet.copyOf(keys))));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_present_ordered_absent(
AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, keysToLoad -> {
      return keysToLoad.stream().collect(toMap(identity(), Int::negate));
    }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_present_ordered_partial(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, keysToLoad -> {
      return keysToLoad.stream().collect(toMap(identity(), Int::negate));
    }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_present_ordered_present(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, keysToLoad
        -> { throw new AssertionError(); }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_present_ordered_exceeds(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, keysToLoad -> {
      var moreKeys = new ArrayList<Int>(keysToLoad);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return moreKeys.stream().collect(toMap(identity(), Int::negate));
    }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, compute = Compute.ASYNC,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_badLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys(), keysToLoad -> { throw new LoadAllException(); }).join();
    } catch (CompletionException e) {
      assertThat(e.getCause(), is(instanceOf(LoadAllException.class)));
      assertThat(cache.asMap().size(), is(context.original().size()));
    }
  }

  /* --------------- getAllBiFunc --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_nullKeys(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(null, (keys, executor) -> { throw new AssertionError(); });
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_nullKeys_nullBifunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f = null;
    cache.getAll(null, f);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_nullBifunction(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f = null;
    cache.getAll(context.original().keySet(), f);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(Collections.singletonList(null),
        (keys, executor) -> { throw new AssertionError(); });
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllFunction_iterable_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(List.of(), keys -> { throw new AssertionError(); }).join();
    assertThat(result, is(anEmptyMap()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_iterable_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(List.of(),
        (keys, executor) -> { throw new AssertionError(); }).join();
    assertThat(result, is(anEmptyMap()));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAllFunction_immutable(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(),
        keys -> keys.stream().collect(toMap(identity(), identity()))).join();
    result.clear();
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAllBifunction_immutable(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), (keys, executor) -> {
      return CompletableFuture.completedFuture(
          keys.stream().collect(toMap(identity(), identity())));
    }).join();
    result.clear();
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = CompletionException.class)
  public void getAllFunction_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys(), keys -> null).join();
    } finally {
      int misses = context.absentKeys().size();
      verifyStats(context, verifier -> verifier.hits(0).misses(misses).success(0).failures(1));
    }
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = CompletionException.class)
  public void getAllBifunction_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.absentKeys(), (keys, executor) ->
        CompletableFuture.completedFuture(null)).join();
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAllBifunction_absent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.absentKeys(), (keys, executor) -> null).join();
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = CompletionException.class)
  public void getAllBifunction_absent_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys(), (keys, executor) -> {
        var future = new CompletableFuture<Map<Int, Int>>();
        future.completeExceptionally(new IllegalStateException());
        return future;
      }).join();
    } finally {
      int misses = context.absentKeys().size();
      verifyStats(context, verifier -> verifier.hits(0).misses(misses).success(0).failures(1));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), (keys, executor) ->
        CompletableFuture.completedFuture(context.absent())).join();
    assertThat(result, is(context.absent()));

    int count = context.absentKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(count).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_present_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet(), (keys, executor) -> {
      assertThat(Iterables.size(keys), is(lessThan(expect.keySet().size())));
      return CompletableFuture.completedFuture(
          keys.stream().collect(toMap(identity(), Int::negate)));
    }).join();

    assertThat(result, is(equalTo(expect)));
    verifyStats(context, verifier -> verifier.hits(expect.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_exceeds(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), (keys, executor) -> {
      var moreKeys = new ArrayList<Int>(keys);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return CompletableFuture.completedFuture(
          moreKeys.stream().collect(toMap(identity(), Int::negate)));
    }).join();

    assertThat(result.keySet(), equalTo(context.absentKeys()));
    assertThat(cache.synchronous().estimatedSize(),
        is(greaterThan(context.initialSize() + context.absentKeys().size())));
    verifyStats(context, verifier -> verifier.hits(0).misses(result.size()).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_duplicates(AsyncCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys, (keysToLoad, executor) -> {
      assertThat(List.copyOf(keysToLoad), is(equalTo(ImmutableSet.copyOf(keysToLoad).asList())));
      return CompletableFuture.completedFuture(
          keysToLoad.stream().collect(toMap(identity(), Int::negate)));
    }).join();

    verifyStats(context, verifier ->
        verifier.hits(context.initialSize()).misses(absentKeys.size()).success(1).failures(0));
    assertThat(result.keySet(), is(equalTo(ImmutableSet.copyOf(keys))));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_present_ordered_absent(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, (keysToLoad, executor) -> {
      assertThat(Set.copyOf(keysToLoad), is(equalTo(context.absentKeys())));
      return CompletableFuture.completedFuture(
          keysToLoad.stream().collect(toMap(identity(), Int::negate)));
    }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_present_ordered_partial(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, (keysToLoad, executor) -> {
      assertThat(Set.copyOf(keysToLoad), is(equalTo(context.absentKeys())));
      return CompletableFuture.completedFuture(
          keysToLoad.stream().collect(toMap(identity(), Int::negate)));
    }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_present_ordered_present(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, (keysToLoad, executor)
        -> { throw new AssertionError(); }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_present_ordered_exceeds(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, (keysToLoad, executor) -> {
      var moreKeys = new ArrayList<Int>(keysToLoad);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return CompletableFuture.completedFuture(
          moreKeys.stream().collect(toMap(identity(), Int::negate)));
    }).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, compute = Compute.ASYNC,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllBifunction_badLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys(), (keysToLoad, executor)
          -> { throw new LoadAllException(); }).join();
      Assert.fail();
    } catch (LoadAllException e) {
      assertThat(cache.asMap().size(), is(context.original().size()));
    }
  }

  /* --------------- put --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    cache.put(null, value);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.put(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert_failure_before(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = CompletableFuture.completedFuture((Int) null);
    failedFuture.completeExceptionally(new IllegalStateException());

    cache.put(context.absentKey(), failedFuture);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert_failure_after(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = new CompletableFuture<Int>();

    cache.put(context.absentKey(), failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    cache.put(context.absentKey(), value);
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() + 1));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(1).failures(0));
    assertThat(cache.synchronous().getIfPresent(context.absentKey()), is(context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_replace_failure_before(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = CompletableFuture.completedFuture((Int) null);
    failedFuture.completeExceptionally(new IllegalStateException());

    cache.put(context.middleKey(), failedFuture);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() - 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_replace_failure_after(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = CompletableFuture.completedFuture((Int) null);

    cache.put(context.middleKey(), failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() - 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = CompletableFuture.completedFuture((Int) null);
    for (Int key : context.firstMiddleLastKeys()) {
      cache.put(key, value);
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  /* --------------- misc --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.MOCKITO)
  public void removalListener_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    future.complete(null);

    verify(context.removalListener(), never()).onRemoval(
        any(Int.class), any(Int.class), any(RemovalCause.class));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void serialize(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache, is(reserializable()));
  }

  @SuppressWarnings("serial")
  private static final class LoadAllException extends RuntimeException {}
}
