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
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
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
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.LocalAsyncCache.AsyncBulkCompleter.NullMapCompletionException;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link AsyncLoadingCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckNoEvictions @CheckMaxLogLevel(TRACE)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsyncLoadingCacheTest {

  /* --------------- get --------------- */

  @CacheSpec
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  public void get_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(null));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey())).succeedsWith(context.absentValue());
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  public void get_absent_failure(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey())).hasCompletedExceptionally();
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
  @CacheSpec(loader = Loader.EXCEPTIONAL, executor = CacheExecutor.THREADED,
      executorFailure = ExecutorFailure.IGNORED)
  public void get_absent_failure_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var done = new AtomicBoolean();
    var valueFuture = cache.get(key).whenComplete((r, e) -> done.set(true));

    await().untilTrue(done);
    await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key));
    await().untilAsserted(() -> assertThat(cache).hasSize(context.initialSize()));

    assertThat(valueFuture).hasCompletedExceptionally();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_EXCEPTIONAL)
  public void get_absent_throwsException(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.get(context.absentKey()));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_CHECKED_EXCEPTIONAL)
  public void get_absent_throwsCheckedException(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.get(context.absentKey()));

    assertThat(e).hasCauseThat().isInstanceOf(ExecutionException.class);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INTERRUPTED)
  public void get_absent_interrupted(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.get(context.absentKey()));

    assertThat(Thread.interrupted()).isTrue();
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey())).succeedsWith(context.firstKey().negate());
    assertThat(cache.get(context.middleKey())).succeedsWith(context.middleKey().negate());
    assertThat(cache.get(context.lastKey())).succeedsWith(context.lastKey().negate());
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getAll --------------- */

  @CheckNoStats
  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.getAll(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_nullKey(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    List<Int> keys = Collections.singletonList(null);
    assertThrows(NullPointerException.class, () -> cache.getAll(keys));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_empty(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(List.of()).join()).isExhaustivelyEmpty();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_MODIFY_KEYS)
  public void getAll_immutable_keys_loader(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.getAll(context.absentKeys());
    assertThat(future).failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_BULK_MODIFY_KEYS)
  public void getAll_immutable_keys_asyncLoader(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () -> cache.getAll(context.absentKeys()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_immutable_result(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var results = cache.getAll(context.firstMiddleLastKeys()).join();
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_nullLookup(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys()).join();
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NULL)
  public void getAll_absent_bulkNull(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys())).failsWith(NullMapCompletionException.class);
    assertThat(context).stats().hits(0).misses(context.absentKeys().size()).success(0).failures(1);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  public void getAll_absent_failure(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys())).failsWith(CompletionException.class);
    int misses = context.absentKeys().size();
    int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
    assertThat(logEvents()
        .withMessage("Exception thrown during asynchronous load")
        .withUnderlyingCause(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(loadFailures);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.ASYNC_EXCEPTIONAL, Loader.ASYNC_BULK_EXCEPTIONAL })
  public void getAll_absent_throwsException(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.getAll(context.absentKeys()));
    int misses = context.loader().isBulk() ? context.absentKeys().size() : 1;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.ASYNC_CHECKED_EXCEPTIONAL, Loader.ASYNC_BULK_CHECKED_EXCEPTIONAL })
  public void getAll_absent_throwsCheckedException(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.getAll(context.absentKeys()));

    assertThat(e).hasCauseThat().isInstanceOf(ExecutionException.class);
    int misses = context.loader().isBulk() ? context.absentKeys().size() : 1;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.ASYNC_INTERRUPTED, Loader.ASYNC_BULK_INTERRUPTED })
  public void getAll_absent_interrupted(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.getAll(context.absentKeys()));

    assertThat(Thread.interrupted()).isTrue();
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);
    int misses = context.loader().isBulk() ? context.absentKeys().size() : 1;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_absent(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var expect = Maps.toMap(context.absentKeys(), Int::negate);
    var result = cache.getAll(expect.keySet()).join();
    assertThat(result).isEqualTo(expect);

    int misses = expect.size();
    int loads = context.loader().isBulk() ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(loads).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_absent_partial(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var expect = new ImmutableMap.Builder<Int, Int>()
        .putAll(Maps.toMap(context.firstMiddleLastKeys(), Int::negate))
        .putAll(Maps.toMap(context.absentKeys(), Int::negate))
        .buildOrThrow();
    var result = cache.getAll(expect.keySet()).join();
    assertThat(result).isEqualTo(expect);

    int misses = context.absentKeys().size();
    int hits = context.firstMiddleLastKeys().size();
    int loads = context.loader().isBulk() ? 1 : misses;
    assertThat(context).stats().hits(hits).misses(misses).success(loads).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_partial(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet()).join();

    assertThat(result).containsExactlyEntriesIn(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = { CacheExecutor.DIRECT, CacheExecutor.THREADED })
  public void getAll_exceeds(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys()).join();

    assertThat(result.keySet()).containsExactlyElementsIn(context.absentKeys());
    assertThat(cache).hasSizeGreaterThan(context.initialSize() + context.absentKeys().size());
    assertThat(context).stats().hits(0).misses(result.size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_DIFFERENT,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_different(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys()).join();

    assertThat(result).isEmpty();
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(context).stats().hits(0).misses(context.absent().size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_duplicates(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys).join();
    assertThat(result).containsExactlyKeys(keys);

    int loads = context.loader().isBulk() ? 1 : absentKeys.size();
    assertThat(context).stats().hits(context.initialSize())
        .misses(absentKeys.size()).success(loads).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_absent(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_partial(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_NEGATIVE_EXCEEDS },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_present(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = cache.getAll(keys).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_exceeds(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys).join();
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_BULK_EXCEPTIONAL)
  public void getAll_present_inserted(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var started = new AtomicBoolean();
    var computing = new AtomicBoolean();
    var writer = new AtomicReference<Thread>();

    var future = CompletableFuture.supplyAsync(() -> {
      writer.set(Thread.currentThread());
      started.set(true);
      await().untilTrue(computing);
      return cache.getAll(ImmutableSet.of(context.absentKey())).join();
    }, ConcurrentTestHarness.executor);

    cache.asMap().computeIfAbsent(context.absentKey(), key -> {
      await().untilTrue(started);
      computing.set(true);

      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> {
        var thread = writer.get();
        return (thread != null) && threadState.contains(thread.getState());
      });

      return CompletableFuture.completedFuture(context.absentValue());
    });

    assertThat(future).succeedsWith(ImmutableMap.of(context.absentKey(), context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.ASYNC, removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = CacheExecutor.THREADED)
  public void getAll_canceled_individual(CacheContext context) {
    var ready = new AtomicBoolean();
    var loader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public ImmutableMap<Int, Int> loadAll(Set<? extends Int> keys) {
        await().untilTrue(ready);
        return keys.stream().collect(toImmutableMap(identity(), Int::negate));
      }
    };

    var cache = context.buildAsync(loader);
    var bulk = cache.getAll(context.absentKeys());
    for (var key : context.absentKeys()) {
      var future = requireNonNull(cache.getIfPresent(key));
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
  @CacheSpec(compute = Compute.ASYNC, removalListener = { Listener.DISABLED, Listener.REJECTING },
      executor = CacheExecutor.THREADED)
  public void getAll_canceled_bulk(CacheContext context) {
    var ready = new AtomicBoolean();
    var loader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public ImmutableMap<Int, Int> loadAll(Set<? extends Int> keys) {
        await().untilTrue(ready);
        return keys.stream().collect(toImmutableMap(identity(), Int::negate));
      }
    };

    var cache = context.buildAsync(loader);
    var bulk = cache.getAll(context.absentKeys());
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
  @CacheSpec(compute = Compute.ASYNC, removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_badLoader(CacheContext context) {
    var loader = new AsyncCacheLoader<Int, Int>() {
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Map<Int, Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) {
        throw new LoadAllException();
      }
    };
    var cache = context.buildAsync(loader);

    assertThrows(LoadAllException.class, () -> cache.getAll(context.absentKeys()));
    assertThat(cache).isEmpty();
  }

  /* --------------- put --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    var value = context.absentValue().toFuture();
    for (Int key : context.firstMiddleLastKeys()) {
      cache.put(key, value);
      assertThat(cache.get(key)).succeedsWith(context.absentValue());
      replaced.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* --------------- refresh --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      compute = Compute.ASYNC, executor = CacheExecutor.THREADED)
  public void refresh(CacheContext context) {
    var done = new AtomicBoolean();
    var cache = context.buildAsync((Int key) -> {
      await().untilTrue(done);
      return key.negate();
    });

    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    var original = cache.get(key);
    for (int i = 0; i < 10; i++) {
      context.ticker().advance(Duration.ofSeconds(1));
      cache.synchronous().refresh(key);

      var next = cache.get(key);
      assertThat(next).isSameInstanceAs(original);
    }
    done.set(true);
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, key.negate()));
  }

  @SuppressWarnings("NullAway")
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, compute = Compute.ASYNC)
  public void refresh_nullFuture_load(CacheContext context) {
    var cache = context.buildAsync((Int key, Executor executor) -> null);
    assertThrows(NullPointerException.class, () ->
        cache.synchronous().refresh(context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, compute = Compute.ASYNC)
  public void refresh_nullFuture_reload(CacheContext context) {
    var cache = context.buildAsync(new AsyncCacheLoader<Int, Int>() {
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        throw new IllegalStateException();
      }
      @SuppressWarnings("NullAway")
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        return null;
      }
    });
    cache.synchronous().put(context.absentKey(), context.absentValue());
    assertThrows(NullPointerException.class, () ->
        cache.synchronous().refresh(context.absentKey()));
  }

  @Test(dataProvider = "caches", timeOut = 5_000) // Issue #69
  @CacheSpec(population = Population.EMPTY,
      compute = Compute.ASYNC, executor = CacheExecutor.THREADED)
  public void refresh_deadlock(CacheContext context) {
    var future = new CompletableFuture<Int>();
    var cache = context.buildAsync((Int k, Executor e) -> future);

    cache.synchronous().refresh(context.absentKey());
    var get = cache.get(context.absentKey());

    future.complete(context.absentValue());
    assertThat(get).succeedsWith(context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.REFRESH_EXCEPTIONAL)
  public void refresh_throwsException(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    assertThrows(IllegalStateException.class, () -> cache.synchronous().refresh(key));

    int failures = context.isGuava() ? 1 : 0;
    assertThat(context).stats().hits(0).misses(0).success(0).failures(failures);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.REFRESH_CHECKED_EXCEPTIONAL)
  public void refresh_throwsCheckedException(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var e = assertThrows(CompletionException.class, () -> cache.synchronous().refresh(key));
    assertThat(e).hasCauseThat().isInstanceOf(ExecutionException.class);

    int failures = context.isGuava() ? 1 : 0;
    assertThat(context).stats().hits(0).misses(0).success(0).failures(failures);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.REFRESH_INTERRUPTED)
  public void refresh_interrupted(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var e = assertThrows(CompletionException.class, () -> cache.synchronous().refresh(key));
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);
    assertThat(Thread.interrupted()).isTrue();

    int failures = context.isGuava() ? 1 : 0;
    assertThat(context).stats().hits(0).misses(0).success(0).failures(failures);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void refresh_current_inFlight(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    cache.synchronous().refresh(context.absentKey());
    assertThat(cache).containsEntry(context.absentKey(), future);
    assertThat(cache.synchronous().policy().refreshes()).isEmpty();
    future.complete(context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.ASYNC, removalListener = Listener.CONSUMING)
  public void refresh_current_sameInstance(CacheContext context) {
    var future = context.absentValue().toFuture();
    var cache = context.buildAsync((key, executor) -> future);

    cache.put(context.absentKey(), future);
    cache.synchronous().refresh(context.absentKey());
    assertThat(context).notifications().isEmpty();
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void refresh_current_failed(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var future = context.absentValue().toFuture();
    cache.put(context.absentKey(), future);

    future.obtrudeException(new Exception());
    assertThat(cache.asMap()).containsKey(context.absentKey());

    cache.synchronous().refresh(context.absentKey());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.ASYNC,
      removalListener = Listener.CONSUMING, executor = CacheExecutor.THREADED)
  public void refresh_current_removed(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var cache = context.buildAsync((Int key) -> {
      started.set(true);
      await().untilTrue(done);
      return key;
    });

    cache.put(context.absentKey(), context.absentValue().toFuture());
    cache.synchronous().refresh(context.absentKey());
    await().untilTrue(started);

    cache.synchronous().invalidate(context.absentKey());
    done.set(true);

    await().untilAsserted(() -> {
      assertThat(context).removalNotifications().containsExactlyValues(
          context.absentKey(), context.absentValue());
    });
  }

  /* --------------- AsyncCacheLoader --------------- */

  @Test
  public void asyncLoadAll() {
    AsyncCacheLoader<Int, Int> loader = (key, executor) -> key.negate().toFuture();
    assertThrows(UnsupportedOperationException.class, () ->
        loader.asyncLoadAll(Set.of(), Runnable::run));
  }

  @Test
  public void asyncReload() throws Exception {
    AsyncCacheLoader<Int, Int> loader = (key, executor) -> key.negate().toFuture();
    var future = loader.asyncReload(Int.valueOf(1), Int.valueOf(2), Runnable::run);
    assertThat(future).succeedsWith(-1);
  }

  @Test
  @SuppressWarnings("NullAway")
  public void bulk_function_null() {
    Function<Set<? extends Int>, Map<Int, Int>> f = null;
    assertThrows(NullPointerException.class, () -> AsyncCacheLoader.bulk(f));
  }

  @Test
  public void bulk_function_absent() throws Exception {
    AsyncCacheLoader<Int, Int> loader = AsyncCacheLoader.bulk(keys -> Map.of());
    assertThat(loader.asyncLoadAll(Set.of(), Runnable::run)).succeedsWith(Map.of());
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run)).succeedsWithNull();
  }

  @Test
  public void bulk_function_present() throws Exception {
    AsyncCacheLoader<Int, Int> loader = AsyncCacheLoader.bulk(keys -> {
      return keys.stream().collect(toImmutableMap(identity(), identity()));
    });
    assertThat(loader.asyncLoadAll(Int.setOf(1, 2), Runnable::run))
        .succeedsWith(Int.mapOf(1, 1, 2, 2));
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run)).succeedsWith(1);
  }

  @Test
  @SuppressWarnings("NullAway")
  public void bulk_bifunction_null() {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f = null;
    assertThrows(NullPointerException.class, () -> AsyncCacheLoader.bulk(f));
  }

  @Test
  public void bulk_absent() throws Exception {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f =
        (keys, executor) -> CompletableFuture.completedFuture(Map.of());
    var loader = AsyncCacheLoader.bulk(f);
    assertThat(loader.asyncLoadAll(Set.of(), Runnable::run)).succeedsWith(Map.of());
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run)).succeedsWithNull();
  }

  @Test
  public void bulk_present() throws Exception {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f =
        (keys, executor) -> {
          ImmutableMap<Int, Int> results = keys.stream()
              .collect(toImmutableMap(identity(), identity()));
          return CompletableFuture.completedFuture(results);
        };
    var loader = AsyncCacheLoader.bulk(f);
    assertThat(loader.asyncLoadAll(Int.setOf(1, 2), Runnable::run))
        .succeedsWith(Int.mapOf(1, 1, 2, 2));
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run)).succeedsWith(1);
  }

  private static final class LoadAllException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    LoadAllException() {
      super(/* message= */ null, /* cause= */ null,
          /* enableSuppression= */ false, /* writableStackTrace= */ false);
    }
  }
}
