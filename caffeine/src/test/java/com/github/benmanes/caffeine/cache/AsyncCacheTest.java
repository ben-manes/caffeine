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

import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Thread.State.BLOCKED;
import static java.lang.Thread.State.WAITING;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.LocalAsyncCache.AsyncBulkCompleter.NullMapCompletionException;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.ExpectedError;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link AsyncCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckNoEvictions @CheckMaxLogLevel(TRACE)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsyncCacheTest {

  /* --------------- getIfPresent --------------- */

  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.getIfPresent(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey())).isNull();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey())).isNotNull();
    assertThat(cache.getIfPresent(context.middleKey())).isNotNull();
    assertThat(cache.getIfPresent(context.lastKey())).isNotNull();
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getFunc --------------- */

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getFunc_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(null, key -> null));
  }

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getFunc_nullLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = null;
    assertThrows(NullPointerException.class, () -> cache.get(context.absentKey(), mappingFunction));
  }

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getFunc_nullKeyAndLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(null, (Function<Int, Int>) null));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    @SuppressWarnings("NullAway")
    var valueFuture = cache.get(key, k -> null);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);

    assertThat(valueFuture).isDone();
    assertThat(cache).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_null_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var ready = new AtomicBoolean();
    var done = new AtomicBoolean();
    @SuppressWarnings("NullAway")
    var valueFuture = cache.get(key, k -> {
      await().untilTrue(ready);
      return null;
    });
    var completed = valueFuture.whenComplete((r, e) -> done.set(true));

    ready.set(true);
    await().untilTrue(done);
    await().untilAsserted(() -> assertThat(cache).doesNotContainKey(context.absentKey()));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);

    assertThat(completed).isDone();
    assertThat(valueFuture).isDone();
    assertThat(cache).doesNotContainKey(key);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getFunc_absent_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    var valueFuture = cache.get(context.absentKey(), k -> { throw new IllegalStateException(); });
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);

    assertThat(valueFuture).hasCompletedExceptionally();
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withUnderlyingCause(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_failure_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var done = new AtomicBoolean();
    var valueFuture = cache.get(key, k -> {
      throw new IllegalStateException();
    });
    var completed = valueFuture.whenComplete((r, e) -> done.set(true));

    await().untilTrue(done);
    await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key));
    await().untilAsserted(() -> assertThat(cache).hasSize(context.initialSize()));

    assertThat(completed).isDone();
    assertThat(valueFuture).hasCompletedExceptionally();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.THREADED, executorFailure = ExecutorFailure.IGNORED)
  public void getFunc_absent_cancelled(AsyncCache<Int, Int> cache, CacheContext context) {
    var done = new AtomicBoolean();
    @SuppressWarnings("NullAway")
    var valueFuture = cache.get(context.absentKey(), k -> {
      await().until(done::get);
      return null;
    });
    var completed = valueFuture.whenComplete((r, e) -> done.set(true));
    valueFuture.cancel(true);

    await().untilTrue(done);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);

    assertThat(completed).isDone();
    assertThat(valueFuture).isDone();
    await().untilAsserted(() -> assertThat(cache).doesNotContainKey(context.absentKey()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var value = cache.get(key, k -> context.absentValue());
    assertThat(value).succeedsWith(context.absentValue());
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getFunc_present(AsyncCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader))
        .succeedsWith(context.original().get(context.firstKey()));
    assertThat(cache.get(context.middleKey(), loader))
        .succeedsWith(context.original().get(context.middleKey()));
    assertThat(cache.get(context.lastKey(), loader))
        .succeedsWith(context.original().get(context.lastKey()));
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getBiFunc --------------- */

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getBiFunc_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.get(null, (key, executor) -> CompletableFuture.completedFuture(null)));
  }

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getBiFunc_nullLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Int, Executor, CompletableFuture<Int>> mappingFunction = null;
    assertThrows(NullPointerException.class, () -> cache.get(context.absentKey(), mappingFunction));
  }

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getBiFunc_nullKeyAndLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Int, Executor, CompletableFuture<Int>> mappingFunction = null;
    assertThrows(NullPointerException.class, () -> cache.get(null, mappingFunction));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_throwsCheckedException(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(IOException.class, () -> cache.get(context.absentKey(),
        (key, executor) -> { throw uncheckedThrow(new IOException()); }));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_throwsRuntimeException(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () ->
        cache.get(context.absentKey(), (key, executor) -> { throw new IllegalStateException(); }));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_throwsError(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(ExpectedError.class, () ->
        cache.get(context.absentKey(), (key, executor) -> { throw ExpectedError.INSTANCE; }));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.get(context.absentKey(), (k, executor) -> null));
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_failure_before(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = new CompletableFuture<Int>();
    failedFuture.completeExceptionally(new IllegalStateException());

    Int key = context.absentKey();
    var valueFuture = cache.get(key, (k, executor) -> failedFuture);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);

    assertThat(valueFuture).hasCompletedExceptionally();
    assertThat(cache).doesNotContainKey(key);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_failure_after(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = new CompletableFuture<Int>();

    Int key = context.absentKey();
    var valueFuture = cache.get(key, (k, executor) -> failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);

    assertThat(valueFuture).hasCompletedExceptionally();
    assertThat(cache).doesNotContainKey(key);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent_cancelled(AsyncCache<Int, Int> cache, CacheContext context) {
    var cancelledFuture = cache.get(context.absentKey(),
        (k, executor) -> new CompletableFuture<>());
    cancelledFuture.cancel(true);

    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getBiFunc_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var value = cache.get(key, (k, executor) -> context.absentValue().toFuture());
    assertThat(value).succeedsWith(context.absentValue());
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getBiFunc_present(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Int, Executor, CompletableFuture<Int>> loader =
        (key, executor) -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader))
        .succeedsWith(context.original().get(context.firstKey()));
    assertThat(cache.get(context.middleKey(), loader))
        .succeedsWith(context.original().get(context.middleKey()));
    assertThat(cache.get(context.lastKey(), loader))
        .succeedsWith(context.original().get(context.lastKey()));
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getAllFunc --------------- */

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_nullKeys(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.getAll(null, keys -> { throw new AssertionError(); }));
  }

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_nullKeys_nullFunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    Function<Set<? extends Int>, Map<Int, Int>> mappingFunction = null;
    assertThrows(NullPointerException.class, () -> cache.getAll(null, mappingFunction));
  }

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_nullFunction(AsyncCache<Int, Int> cache, CacheContext context) {
    Function<Set<? extends Int>, Map<Int, Int>> mappingFunction = null;
    assertThrows(NullPointerException.class, () ->
        cache.getAll(context.original().keySet(), mappingFunction));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.getAll(Collections.singletonList(null), keys -> { throw new AssertionError(); }));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_iterable_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(List.of(), keys -> { throw new AssertionError(); }).join();
    assertThat(result).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_nullLookup(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys(),
        keys -> Maps.toMap(keys, Int::negate)).join();
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllFunction_immutable_keys(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(), keys -> {
      keys.clear();
      return Map.of();
    });
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAllFunction_immutable_result(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys(),
        keys -> keys.stream().collect(toImmutableMap(identity(), identity()))).join();
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void getAllFunction_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys(), keys -> null))
        .failsWith(NullMapCompletionException.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllFunction_absent_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys(), keys -> { throw new IllegalStateException(); }))
        .failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(IllegalStateException.class);
    int misses = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withUnderlyingCause(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), keys -> context.absent()).join();
    int count = context.absentKeys().size();
    assertThat(result).hasSize(count);
    assertThat(context).stats().hits(0).misses(count).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_present_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet(), keys -> {
      assertThat(keys).hasSizeLessThan(expect.size());
      return keys.stream().collect(toImmutableMap(identity(), Int::negate));
    }).join();

    assertThat(result).isEqualTo(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = { CacheExecutor.DIRECT, CacheExecutor.THREADED })
  public void getAllFunction_exceeds(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), keys -> {
      var moreKeys = new ArrayList<Int>(keys);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return intern(Maps.toMap(moreKeys, Int::negate));
    }).join();

    assertThat(result).containsExactlyKeys(context.absentKeys());
    assertThat(cache).hasSizeGreaterThan(context.initialSize() + context.absentKeys().size());
    assertThat(context).stats().hits(0).misses(result.size()).success(1).failures(0);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_different(AsyncCache<Int, Int> cache, CacheContext context) {
    var actual = Maps.uniqueIndex(context.absentKeys(), Int::negate);
    var result = cache.getAll(context.absentKeys(), keys -> actual).join();

    assertThat(result).isEmpty();
    assertThat(cache).hasSize(context.initialSize() + actual.size());
    assertThat(cache.synchronous().asMap()).containsAtLeastEntriesIn(actual);
    assertThat(context).stats().hits(0).misses(actual.size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_duplicates(AsyncCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys, keysToLoad -> {
      assertThat(keysToLoad).containsNoDuplicates();
      return keysToLoad.stream().collect(toImmutableMap(identity(), Int::negate));
    }).join();

    assertThat(context).stats().hits(context.initialSize())
        .misses(absentKeys.size()).success(1).failures(0);
    assertThat(result).containsExactlyKeys(keys);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_present_ordered_absent(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, keysToLoad -> {
      return keysToLoad.stream().collect(toImmutableMap(identity(), Int::negate));
    }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_present_ordered_partial(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, keysToLoad -> {
      return keysToLoad.stream().collect(toImmutableMap(identity(), Int::negate));
    }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_present_ordered_present(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, keysToLoad -> { throw new AssertionError(); }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_present_ordered_exceeds(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, keysToLoad -> {
      var moreKeys = new ArrayList<Int>(keysToLoad);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return intern(Maps.toMap(moreKeys, Int::negate));
    }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = CacheExecutor.THREADED)
  public void getAllFunction_canceled_individual(AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var bulk = cache.getAll(context.absentKeys(), keysToLoad -> {
      await().untilTrue(ready);
      return Maps.toMap(keysToLoad, Int::negate);
    });
    for (var key : context.absentKeys()) {
      var future = cache.getIfPresent(key);
      requireNonNull(future);
      future.cancel(true);
      assertThat(future).hasCompletedExceptionally();
    }
    ready.set(true);
    bulk.join();

    await().untilAsserted(() -> {
      for (var key : context.absentKeys()) {
        assertThat(cache).containsKey(key);
      }
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = CacheExecutor.THREADED)
  public void getAllFunction_canceled_bulk(AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var bulk = cache.getAll(context.absentKeys(), keysToLoad -> {
      await().untilTrue(ready);
      return Maps.toMap(keysToLoad, Int::negate);
    });
    var pending = context.absentKeys().stream().map(cache::getIfPresent).collect(toImmutableList());

    bulk.cancel(true);
    ready.set(true);

    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
        .orTimeout(10, TimeUnit.SECONDS)
        .join();
    for (var key : context.absentKeys()) {
      assertThat(cache).containsKey(key);
    }
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllFunction_badLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys(), keysToLoad -> { throw new LoadAllException(); }))
        .failsWith(CompletionException.class).hasCauseThat().isInstanceOf(LoadAllException.class);
    assertThat(cache).hasSize(context.initialSize());
  }

  /* --------------- getAllBiFunc --------------- */

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_nullKeys(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.getAll(null, (keys, executor) -> { throw new AssertionError(); }));
  }

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_nullKeys_nullBifunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f = null;
    assertThrows(NullPointerException.class, () -> cache.getAll(null, f));
  }

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_nullBifunction(AsyncCache<Int, Int> cache, CacheContext context) {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f = null;
    assertThrows(NullPointerException.class, () -> cache.getAll(context.original().keySet(), f));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> {
      cache.getAll(Collections.singletonList(null),
          (keys, executor) -> { throw new AssertionError(); });
    });
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_iterable_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(List.of(),
        (keys, executor) -> { throw new AssertionError(); }).join();
    assertThat(result).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBiFunction_nullLookup(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys(), (keys, executor) ->
        CompletableFuture.completedFuture(Maps.toMap(keys, Int::negate))).join();
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_immutable_keys(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () -> {
      cache.getAll(context.absentKeys(), (keys, executor) -> {
        throw assertThrows(UnsupportedOperationException.class, keys::clear);
      });
    });
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAllBifunction_immutable_result(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys(), (keys, executor) -> {
      return CompletableFuture.completedFuture(
          keys.stream().collect(toImmutableMap(identity(), identity())));
    }).join();
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_null(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = CompletableFuture.completedFuture((Map<Int, Int>) null);
    assertThat(cache.getAll(context.absentKeys(), (keys, executor) -> future))
        .failsWith(NullMapCompletionException.class);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.getAll(context.absentKeys(), (keys, executor) -> null));
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_failure_checkedException(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(),
        (keys, executor) -> CompletableFuture.failedFuture(new IOException()));
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(IOException.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(IOException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_failure_runtimeException(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(),
        (keys, executor) -> CompletableFuture.failedFuture(new IllegalStateException()));
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_failure_error(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(),
        (keys, executor) -> CompletableFuture.failedFuture(ExpectedError.INSTANCE));
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(ExpectedError.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(ExpectedError.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_failure_timeout(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(),
        (keys, executor) -> CompletableFuture.failedFuture(new TimeoutException()));
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(TimeoutException.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
    assertThat(logEvents()).isEmpty();
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_failure_canceled(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(),
        (keys, executor) -> CompletableFuture.failedFuture(new CancellationException()));
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(CancellationException.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
    assertThat(logEvents()).isEmpty();
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_throwsCheckedException(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var error = assertThrows(CompletionException.class, () -> {
      cache.getAll(context.absentKeys(),
          (keys, executor) -> { throw uncheckedThrow(new IOException()); });
    });
    assertThat(error).hasCauseThat().isInstanceOf(IOException.class);

    int misses = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_throwsRuntimeException(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> {
      cache.getAll(context.absentKeys(),
          (keys, executor) -> { throw new IllegalStateException(); });
    });

    int misses = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @CacheSpec
  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  public void getAllBifunction_absent_throwsError(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(ExpectedError.class, () ->
        cache.getAll(context.absentKeys(), (keys, executor) -> { throw ExpectedError.INSTANCE; }));
    int misses = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys(),
        (keys, executor) -> CompletableFuture.completedFuture(context.absent()));
    assertThat(future).succeedsWith(context.absent());

    int count = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(count).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_present_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet(), (keys, executor) -> {
      assertThat(keys.size()).isLessThan(expect.size());
      return CompletableFuture.completedFuture(
          keys.stream().collect(toImmutableMap(identity(), Int::negate)));
    }).join();

    assertThat(result).containsExactlyEntriesIn(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = { CacheExecutor.DIRECT, CacheExecutor.THREADED })
  public void getAllBifunction_exceeds(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), (keys, executor) -> {
      var moreKeys = new ArrayList<Int>(keys);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return CompletableFuture.completedFuture(intern(Maps.toMap(moreKeys, Int::negate)));
    }).join();

    assertThat(result).containsExactlyKeys(context.absentKeys());
    assertThat(cache).hasSizeGreaterThan(context.initialSize() + context.absentKeys().size());
    assertThat(context).stats().hits(0).misses(result.size()).success(1).failures(0);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_different(AsyncCache<Int, Int> cache, CacheContext context) {
    var actual = Maps.uniqueIndex(context.absentKeys(), Int::negate);
    var result = cache.getAll(context.absentKeys(), (keys, executor) -> {
      return CompletableFuture.completedFuture(actual);
    }).join();

    assertThat(result).isEmpty();
    assertThat(cache).hasSize(context.initialSize() + actual.size());
    assertThat(cache.synchronous().asMap()).containsAtLeastEntriesIn(actual);
    assertThat(context).stats().hits(0).misses(actual.size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_duplicates(AsyncCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys, (keysToLoad, executor) -> {
      assertThat(keysToLoad).containsNoDuplicates();
      return CompletableFuture.completedFuture(
          keysToLoad.stream().collect(toImmutableMap(identity(), Int::negate)));
    }).join();

    assertThat(result).containsExactlyKeys(keys);
    assertThat(context).stats().hits(context.initialSize())
        .misses(absentKeys.size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_present_ordered_absent(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, (keysToLoad, executor) -> {
      assertThat(keysToLoad).containsExactlyElementsIn(context.absentKeys());
      return CompletableFuture.completedFuture(
          keysToLoad.stream().collect(toImmutableMap(identity(), Int::negate)));
    }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_present_ordered_partial(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, (keysToLoad, executor) -> {
      assertThat(keysToLoad).containsExactlyElementsIn(context.absentKeys());
      return CompletableFuture.completedFuture(
          keysToLoad.stream().collect(toImmutableMap(identity(), Int::negate)));
    }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_present_ordered_present(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, (keysToLoad, executor) ->
        { throw new AssertionError(); }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = CacheExecutor.THREADED)
  public void getAllBifunction_canceled_individual(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var bulk = cache.getAll(context.absentKeys(), (keysToLoad, executor) -> {
      return CompletableFuture.supplyAsync(() -> {
        await().untilTrue(ready);
        return Maps.toMap(keysToLoad, Int::negate);
      }, executor);
    });
    for (var key : context.absentKeys()) {
      var future = cache.getIfPresent(key);
      requireNonNull(future);
      future.cancel(true);
      assertThat(future).hasCompletedExceptionally();
    }
    ready.set(true);
    bulk.join();

    await().untilAsserted(() -> {
      for (var key : context.absentKeys()) {
        assertThat(cache).containsKey(key);
      }
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = CacheExecutor.THREADED)
  public void getAllBifunction_canceled_bulk(AsyncCache<Int, Int> cache, CacheContext context) {
    var ready = new AtomicBoolean();
    var bulk = cache.getAll(context.absentKeys(), (keysToLoad, executor) -> {
      return CompletableFuture.supplyAsync(() -> {
        await().untilTrue(ready);
        return Maps.toMap(keysToLoad, Int::negate);
      }, executor);
    });
    var pending = context.absentKeys().stream().map(cache::getIfPresent).collect(toImmutableList());
    bulk.cancel(true);
    ready.set(true);

    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
        .orTimeout(10, TimeUnit.SECONDS)
        .join();
    for (var key : context.absentKeys()) {
      assertThat(cache).containsKey(key);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_present_ordered_exceeds(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, (keysToLoad, executor) -> {
      var moreKeys = new ArrayList<Int>(keysToLoad);
      for (int i = 0; i < 10; i++) {
        moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
      }
      return CompletableFuture.completedFuture(intern(Maps.toMap(moreKeys, Int::negate)));
    }).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_badLoader(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(LoadAllException.class, () ->
        cache.getAll(context.absentKeys(), (keys, executor) -> { throw new LoadAllException(); }));
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_early_success(AsyncCache<Int, Int> cache, CacheContext context) {
    var key = context.absentKeys().iterator().next();
    var value = Int.valueOf(Integer.MAX_VALUE);

    var bulk = new CompletableFuture<Map<Int, Int>>();
    var result = cache.getAll(context.absentKeys(), (keysToLoad, executor) -> bulk);
    var future = cache.asMap().get(key);

    requireNonNull(future);
    future.complete(value);
    bulk.complete(context.absent()); // obtrudes the future's value

    assertThat(future).succeedsWith(context.absent().get(key));
    assertThat(result.join()).containsExactlyEntriesIn(context.absent());
    assertThat(cache.synchronous().asMap()).containsAtLeastEntriesIn(context.absent());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllBifunction_early_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    var key = context.absentKeys().iterator().next();
    var error = new IllegalStateException();

    var bulk = new CompletableFuture<Map<Int, Int>>();
    var result = cache.getAll(context.absentKeys(), (keysToLoad, executor) -> bulk);
    var future = cache.asMap().get(key);

    requireNonNull(future);
    future.completeExceptionally(error);

    bulk.complete(context.absent());
    assertThat(future).succeedsWith(context.absent().get(key));
    assertThat(cache.synchronous().asMap()).containsAtLeastEntriesIn(context.absent());
    if (result.isCompletedExceptionally()) {
      assertThat(result).failsWith(CompletionException.class)
          .hasCauseThat().isSameInstanceAs(error);
    } else {
      assertThat(result.join()).containsExactlyEntriesIn(context.absent());
    }
    assertThat(logEvents()).isEmpty();
  }

  /* --------------- put --------------- */

  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().toFuture();
    assertThrows(NullPointerException.class, () -> cache.put(null, value));
  }

  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.put(context.absentKey(), null));
  }

  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.put(null, null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert_failure_before(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = CompletableFuture.<Int>failedFuture(new IllegalStateException());

    cache.put(context.absentKey(), failedFuture);
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()).isEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert_failure_after(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = new CompletableFuture<Int>();

    cache.put(context.absentKey(), failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(cache).hasSize(context.initialSize());
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withThrowable(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert_failure_after_noLog(AsyncCache<Int, Int> cache, CacheContext context) {
    var future1 = new CompletableFuture<Int>();
    var future2 = new CompletableFuture<Int>();
    var future3 = new CompletableFuture<Int>();
    cache.put(Iterables.get(context.absentKeys(), 0), future1);
    cache.put(Iterables.get(context.absentKeys(), 1), future2);
    cache.put(Iterables.get(context.absentKeys(), 3), future3);
    future1.complete(null);
    future2.completeExceptionally(new TimeoutException());
    future3.completeExceptionally(new CancellationException());
    assertThat(cache).doesNotContainKey(Iterables.get(context.absentKeys(), 0));
    assertThat(cache).doesNotContainKey(Iterables.get(context.absentKeys(), 1));
    assertThat(cache).doesNotContainKey(Iterables.get(context.absentKeys(), 2));
    assertThat(cache).hasSize(context.initialSize());
    assertThat(logEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert_null(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = CompletableFuture.completedFuture((Int) null);

    cache.put(context.absentKey(), future);
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    assertThat(cache).hasSize(context.initialSize() + 1);
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);
    assertThat(cache.getIfPresent(context.absentKey())).isSameInstanceAs(future);

    future.complete(context.absentValue());
    assertThat(cache).hasSize(context.initialSize() + 1);
    assertThat(context).stats().hits(1).misses(0).success(1).failures(0);
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().toFuture();
    cache.put(context.absentKey(), value);
    assertThat(cache).hasSize(context.initialSize() + 1);
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.FULL })
  public void put_replace_failure_before(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = CompletableFuture.completedFuture((Int) null);
    failedFuture.completeExceptionally(new IllegalStateException());

    cache.put(context.middleKey(), failedFuture);
    assertThat(cache).hasSize(context.initialSize() - 1);
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.FULL })
  public void put_replace_failure_after(AsyncCache<Int, Int> cache, CacheContext context) {
    var failedFuture = CompletableFuture.completedFuture((Int) null);

    cache.put(context.middleKey(), failedFuture);
    failedFuture.completeExceptionally(new IllegalStateException());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(cache).hasSize(context.initialSize() - 1);
    assertThat(logEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    var value = CompletableFuture.completedFuture((Int) null);
    for (Int key : context.firstMiddleLastKeys()) {
      cache.put(key, value);
      assertThat(cache).doesNotContainKey(key);
      removed.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var newValue = context.absentValue().toFuture();
      cache.put(key, newValue);
      assertThat(cache).containsEntry(key, newValue);
      replaced.put(key, context.original().get(key));
    }

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* --------------- misc --------------- */

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void remove_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    checkRetryWhenIncomplete(cache, context, map ->
        map.remove(context.firstKey(), context.absentValue()));
    assertThat(cache).doesNotContainKey(context.firstKey());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    checkRetryWhenIncomplete(cache, context, map ->
        map.replace(context.firstKey(), context.absentValue()));
    assertThat(cache).doesNotContainKey(context.firstKey());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    checkRetryWhenIncomplete(cache, context, map ->
        map.replace(context.firstKey(), context.absentValue(), context.absentValue()));
    assertThat(cache).doesNotContainKey(context.firstKey());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    checkRetryWhenIncomplete(cache, context, map ->
        map.computeIfPresent(context.firstKey(), (k, v) -> context.absentValue()));
    assertThat(cache).doesNotContainKey(context.firstKey());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    checkRetryWhenIncomplete(cache, context, map ->
        map.compute(context.firstKey(), (k, v) -> context.absentValue()));
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_incomplete(AsyncCache<Int, Int> cache, CacheContext context) {
    checkRetryWhenIncomplete(cache, context, map ->
        map.merge(context.firstKey(), context.absentValue(), (v1, v2) -> null));
    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
  }

  private static void checkRetryWhenIncomplete(AsyncCache<Int, Int> cache,
      CacheContext context, Consumer<Map<Int, Int>> write) {
    var done = new AtomicBoolean();
    var started = new AtomicBoolean();
    var writer = new AtomicReference<Thread>();
    var future = new CompletableFuture<Int>() {
      @Override public boolean isDone() {
        return done.get() && super.isDone();
      }
    };
    cache.asMap().compute(context.firstKey(), (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        writer.set(Thread.currentThread());
        started.set(true);
        write.accept(cache.synchronous().asMap());
        done.set(true);
      });
      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> {
        var thread = writer.get();
        return (thread != null) && threadState.contains(thread.getState());
      });
      return future;
    });
    future.completeExceptionally(new Exception());
    await().untilTrue(done);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.MOCKITO)
  public void removalListener_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    future.complete(null);

    verify(context.removalListener(), never()).onRemoval(
        any(Int.class), any(Int.class), any(RemovalCause.class));
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void handleCompletion_brokenFuture_inFlight(
      AsyncCache<Int, Int> cache, CacheContext context) {
    CompletableFuture<Int> future = Mockito.spy();
    ArgumentCaptor<BiConsumer<Int, Throwable>> captor = ArgumentCaptor.captor();
    doReturn(future).when(future).whenComplete(captor.capture());
    cache.put(context.absentKey(), future);

    assertThat(future.isDone()).isFalse();
    assertThat(future.isCompletedExceptionally()).isFalse();

    var futureToString = future.toString();
    captor.getValue().accept(context.absentValue(), /* error */ null);

    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage(msg -> msg.contains("An invalid state was detected"))
        .withMessage(msg -> msg.contains("key: " + context.absentKey()))
        .withMessage(msg -> msg.contains("key type: " + Int.class.getName()))
        .withMessage(msg -> msg.contains("value type: " + Int.class.getName()))
        .withMessage(msg -> msg.contains("future: " + futureToString))
        .withThrowable(IllegalStateException.class)
        .withLevel(ERROR)
        .exclusively())
        .hasSize(1);
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void handleCompletion_brokenFuture_cancel(
      AsyncCache<Int, Int> cache, CacheContext context) {
    CompletableFuture<Int> future = Mockito.spy();
    ArgumentCaptor<BiConsumer<Int, Throwable>> captor = ArgumentCaptor.captor();
    doReturn(future).when(future).whenComplete(captor.capture());
    cache.put(context.absentKey(), future);

    future.cancel(true);
    assertThat(future).isDone();
    assertThat(future.isCancelled()).isTrue();
    assertThat(future).hasCompletedExceptionally();

    var futureToString = future.toString();
    captor.getValue().accept(context.absentValue(), /* error */ null);

    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage(msg -> msg.contains("An invalid state was detected"))
        .withMessage(msg -> msg.contains("key: " + context.absentKey()))
        .withMessage(msg -> msg.contains("key type: " + Int.class.getName()))
        .withMessage(msg -> msg.contains("value type: " + Int.class.getName()))
        .withMessage(msg -> msg.contains("future: " + futureToString))
        .withThrowable(IllegalStateException.class)
        .withLevel(ERROR)
        .exclusively())
        .hasSize(1);
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void handleCompletion_brokenFuture_nullValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    CompletableFuture<Int> future = Mockito.spy();
    ArgumentCaptor<BiConsumer<Int, Throwable>> captor = ArgumentCaptor.captor();
    doReturn(future).when(future).whenComplete(captor.capture());
    cache.put(context.absentKey(), future);

    future.complete(null);
    assertThat(future).isDone();
    assertThat(future.isCancelled()).isFalse();
    assertThat(future.isCompletedExceptionally()).isFalse();

    var futureToString = future.toString();
    captor.getValue().accept(context.absentValue(), /* error */ null);

    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(logEvents()
        .withMessage(msg -> msg.contains("An invalid state was detected"))
        .withMessage(msg -> msg.contains("key: " + context.absentKey()))
        .withMessage(msg -> msg.contains("key type: " + Int.class.getName()))
        .withMessage(msg -> msg.contains("value type: " + Int.class.getName()))
        .withMessage(msg -> msg.contains("future: " + futureToString))
        .withThrowable(IllegalStateException.class)
        .withLevel(ERROR)
        .exclusively())
        .hasSize(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void serialize(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache).isReserialize();
  }

  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  static <E extends Throwable> E uncheckedThrow(Throwable throwable) throws E {
    throw (E) throwable;
  }

  private static final class LoadAllException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LoadAllException() {
      super(/* message= */ null, /* cause= */ null,
          /* enableSuppression= */ false, /* writableStackTrace= */ false);
    }
  }
}
