/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
import static com.github.benmanes.caffeine.cache.RemovalCause.SIZE;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.function.Function.identity;
import static org.junit.Assert.assertThrows;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.base.Functions;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link LoadingCache} interface that simulate the most generic usages.
 * These tests do not validate eviction management, concurrency behavior, or the
 * {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class LoadingCacheTest {

  /* --------------- get --------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  @CheckNoEvictions @CheckNoStats
  public void get_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(null));
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void get_absent_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey())).isNull();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  public void get_absent_throwsException(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.get(context.absentKey()));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.CHECKED_EXCEPTIONAL)
  public void get_absent_throwsCheckedException(
      LoadingCache<Int, Int> cache, CacheContext context) {
    var exception = assertThrows(CompletionException.class, () -> cache.get(context.absentKey()));
    assertThat(exception).hasCauseThat().isInstanceOf(ExecutionException.class);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, loader = Loader.INTERRUPTED)
  public void get_absent_interrupted(LoadingCache<Int, Int> cache, CacheContext context) {
    var exception = assertThrows(CompletionException.class, () -> cache.get(context.absentKey()));
    assertThat(Thread.interrupted()).isTrue();
    assertThat(exception).hasCauseThat().isInstanceOf(InterruptedException.class);
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CacheSpec
  @CheckNoEvictions
  @Test(dataProvider = "caches")
  public void get_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    assertThat(cache.get(key)).isEqualTo(key.negate());
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey())).isEqualTo(context.firstKey().negate());
    assertThat(cache.get(context.middleKey())).isEqualTo(context.middleKey().negate());
    assertThat(cache.get(context.lastKey())).isEqualTo(context.lastKey().negate());
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getAll --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.getAll(null));
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_nullKey(LoadingCache<Int, Int> cache, CacheContext context) {
    List<Int> keys = Collections.singletonList(null);
    assertThrows(NullPointerException.class, () -> cache.getAll(keys));
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_empty(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(List.of())).isExhaustivelyEmpty();
    assertThat(context).stats().hits(0).misses(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_MODIFY_KEYS)
  public void getAll_immutable_keys(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () -> cache.getAll(context.absentKeys()));
  }

  @CacheSpec
  @CheckNoEvictions
  @Test(dataProvider = "caches")
  public void getAll_immutable_result(LoadingCache<Int, Int> cache, CacheContext context) {
    var results = cache.getAll(context.firstMiddleLastKeys());
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_nullLookup(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys());
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void getAll_absent_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys())).isExhaustivelyEmpty();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NULL)
  public void getAll_absent_bulkNull(LoadingCache<Int, Int> cache, CacheContext context) {
    var exception = assertThrows(Exception.class, () -> cache.getAll(context.absentKeys()));
    assertThat(exception).isInstanceOf(
        context.isGuava() ? InvalidCacheLoadException.class : NullPointerException.class);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  public void getAll_absent_throwsException(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.getAll(context.absentKeys()));
    int misses = context.absentKeys().size();
    int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.CHECKED_EXCEPTIONAL, Loader.BULK_CHECKED_EXCEPTIONAL })
  public void getAll_absent_throwsCheckedException(
      LoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.getAll(context.absentKeys()));
    assertThat(e).hasCauseThat().isInstanceOf(ExecutionException.class);

    int misses = context.absentKeys().size();
    int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  public void getAll_absent_throwsException_iterable(
      LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () ->
        cache.getAll(() -> context.absentKeys().iterator()));
    int misses = context.absentKeys().size();
    int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.INTERRUPTED, Loader.BULK_INTERRUPTED })
  public void getAll_absent_interrupted(LoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.getAll(context.absentKeys()));
    if (context.isSync()) {
      assertThat(Thread.interrupted()).isTrue();
    }
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);

    int misses = context.absentKeys().size();
    int loadFailures = (context.loader().isBulk() || context.isSync()) ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(loadFailures);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var expect = Maps.toMap(context.absentKeys(), Int::negate);
    var result = cache.getAll(expect.keySet());
    assertThat(result).isEqualTo(expect);

    int misses = expect.size();
    int loads = context.loader().isBulk() ? 1 : misses;
    assertThat(context).stats().hits(0).misses(misses).success(loads).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_absent_partial(LoadingCache<Int, Int> cache, CacheContext context) {
    var expect = new ImmutableMap.Builder<Int, Int>()
        .putAll(Maps.toMap(context.firstMiddleLastKeys(), Int::negate))
        .putAll(Maps.toMap(context.absentKeys(), Int::negate))
        .build();
    var result = cache.getAll(expect.keySet());
    assertThat(result).isEqualTo(expect);

    int misses = context.absentKeys().size();
    int hits = context.firstMiddleLastKeys().size();
    int loads = context.loader().isBulk() ? 1 : misses;
    assertThat(context).stats().hits(hits).misses(misses).success(loads).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_partial(LoadingCache<Int, Int> cache, CacheContext context) {
    var expect = Maps.toMap(context.firstMiddleLastKeys(), Int::negate);
    var result = cache.getAll(expect.keySet());

    assertThat(result).containsExactlyEntriesIn(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_full(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.original().keySet());
    assertThat(result).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(result.size()).misses(0).success(0).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_exceeds(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys());

    assertThat(result.keySet()).containsExactlyElementsIn(context.absentKeys());
    assertThat(cache).hasSizeGreaterThan(context.initialSize() + context.absentKeys().size());
    assertThat(context).stats().hits(0).misses(result.size()).success(1).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.BULK_DIFFERENT,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_different(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys());

    assertThat(result).isEmpty();
    assertThat(cache.asMap()).containsAtLeastEntriesIn(result);
    assertThat(context).stats().hits(0).misses(context.absent().size()).success(1).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_duplicates(LoadingCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys);
    assertThat(result).containsExactlyKeys(keys);

    int loads = context.loader().isBulk() ? 1 : absentKeys.size();
    assertThat(context).stats().hits(context.initialSize())
        .misses(absentKeys.size()).success(loads).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<Int>(context.absentKeys());
    Collections.shuffle(keys);

    assertThat(cache.getAll(keys).keySet()).containsExactlyElementsIn(keys).inOrder();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_partial(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    assertThat(cache.getAll(keys).keySet()).containsExactlyElementsIn(keys).inOrder();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    assertThat(cache.getAll(keys).keySet()).containsExactlyElementsIn(keys).inOrder();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_exceeds(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = new ArrayList<>(cache.getAll(keys).keySet());
    assertThat(result.subList(0, keys.size())).containsExactlyElementsIn(keys).inOrder();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void getAll_jdk8186171(CacheContext context) {
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }
    LoadingCache<Object, Int> cache = context.build(key -> null);

    var keys = intern(new ArrayList<Key>());
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(new Key());
    }
    Key key = Iterables.getLast(keys);
    Int value = context.absentValue();
    cache.put(key, value);

    var result = cache.getAll(keys);
    assertThat(result).containsExactly(key, value);
    assertThat(result.values()).doesNotContain(null);
  }

  /* --------------- refresh --------------- */

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refresh_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.refresh(null));
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE)
  public void refresh_dedupe(LoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    var future2 = cache.refresh(key);
    assertThat(future1).isSameInstanceAs(future2);

    future1.complete(context.absentValue());
    assertThat(cache).containsEntry(key, context.absentValue());
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_remove(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refresh(context.firstKey());
    assertThat(future).succeedsWithNull();
    assertThat(cache).hasSize(context.initialSize() - 1);
    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      loader = Loader.NULL, population = Population.EMPTY)
  public void refresh_ignored(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refresh(context.absentKey());
    assertThat(future).succeedsWithNull();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      loader = Loader.EXCEPTIONAL, removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refresh_failure(LoadingCache<Int, Int> cache, CacheContext context) {
    // Shouldn't leak exception to caller nor retain the future; should retain the stale entry
    var future1 = cache.refresh(context.absentKey());
    var future2 = cache.refresh(context.firstKey());
    var future3 = cache.refresh(context.lastKey());
    assertThat(future2).isNotSameInstanceAs(future3);
    assertThat(future1).hasCompletedExceptionally();
    assertThat(future2).hasCompletedExceptionally();
    assertThat(future3).hasCompletedExceptionally();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).stats().success(0).failures(3);
  }

  @CheckNoEvictions @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.REFRESH_EXCEPTIONAL)
  public void refresh_throwsException(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.refresh(context.absentKey()));
  }

  @CheckNoEvictions @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.REFRESH_CHECKED_EXCEPTIONAL)
  public void refresh_throwsCheckedException(LoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.refresh(context.absentKey()));
    assertThat(e).hasCauseThat().isInstanceOf(ExecutionException.class);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.REFRESH_INTERRUPTED)
  public void refresh_interrupted(LoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.refresh(context.absentKey()));
    assertThat(Thread.interrupted()).isTrue();
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);

    int failures = context.isGuava() ? 1 : 0;
    assertThat(context).stats().hits(0).misses(0).success(0).failures(failures);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refresh_cancel(LoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    assertThat(future1).isNotDone();
    future1.cancel(true);

    var future2 = cache.refresh(key);
    assertThat(future1).isNotSameInstanceAs(future2);

    future2.cancel(false);
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void refresh_absent_null(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refresh(context.absentKey());
    assertThat(future).succeedsWithNull();
    assertThat(cache).hasSize(context.initialSize());
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.SINGLETON, maximumSize = Maximum.UNREACHABLE,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refresh_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    var future = cache.refresh(key);
    assertThat(future).succeedsWith(key.negate());
    assertThat(cache).hasSize(1 + context.initialSize());
    assertThat(cache).containsEntry(context.absentKey(), key.negate());
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_null(LoadingCache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future).succeedsWithNull();
      removed.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(0).failures(count);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).doesNotContainKey(key);
    }
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_sameValue(LoadingCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future).succeedsWith(context.original().get(key));
      replaced.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, loader = Loader.IDENTITY)
  public void refresh_present_sameInstance(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentKey());
    var future = cache.refresh(context.absentKey());

    assertThat(cache).hasSize(1);
    assertThat(future).succeedsWith(context.absentKey());
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
    assertThat(cache).containsEntry(context.absentKey(), context.absentKey());

    if (context.isGuava()) {
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.absentKey(), context.absentKey())
          .exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.IDENTITY,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_differentValue(LoadingCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future).succeedsWith(key);
      assertThat(cache).containsEntry(key, key);
      replaced.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void refresh_conflict(CacheContext context) {
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int updated = Int.valueOf(2);
    Int refreshed = Int.valueOf(3);
    LoadingCache<Int, Int> cache = context.build(k -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);
    assertThat(cache.asMap().put(key, updated)).isEqualTo(original);

    refresh.set(true);
    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }
    await().untilAsserted(() -> assertThat(context).removalNotifications().hasSize(2));

    assertThat(cache).containsEntry(key, updated);
    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Map.entry(key, original), Map.entry(key, refreshed))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void refresh_put(CacheContext context) {
    var started = new AtomicBoolean();
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    Int updated = Int.valueOf(3);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    assertThat(started.get()).isFalse();

    var future = cache.refresh(key);
    await().untilTrue(started);
    cache.put(key, updated);
    refresh.set(true);

    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    await().untilAsserted(() -> {
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, refreshed);
    });

    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Map.entry(key, original), Map.entry(key, refreshed))
        .exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void refresh_invalidate(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().untilTrue(done);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);
    await().untilTrue(started);

    cache.invalidate(key);
    done.set(true);

    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    if (context.isGuava()) {
      await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshed));
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(key, original).exclusively();
    } else {
      // linearizable
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key));
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(Map.entry(key, original), Map.entry(key, refreshed))
          .exclusively();
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expiryTime = Expire.ONE_MINUTE, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, removalListener = Listener.CONSUMING)
  public void refresh_expired(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().untilTrue(done);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);

    await().untilTrue(started);
    context.ticker().advance(Duration.ofMinutes(10));
    assertThat(cache).doesNotContainKey(key);

    done.set(true);
    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    if (context.isGuava()) {
      await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshed));
      assertThat(context).removalNotifications().withCause(EXPIRED)
          .contains(key, original).exclusively();
    } else {
      // linearizable
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key));
      assertThat(context).removalNotifications().withCause(EXPIRED).contains(key, original);
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(key, refreshed);
      assertThat(context).removalNotifications().hasSize(2);
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.ASYNC_INCOMPLETE,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expiryTime = Expire.ONE_MINUTE, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE}, removalListener = Listener.CONSUMING)
  public void refresh_expired_inFlight(LoadingCache<Int, Int> cache, CacheContext context) {
    var future1 = cache.refresh(context.firstKey());
    context.ticker().advance(Duration.ofMinutes(10));
    var future2 = cache.refresh(context.firstKey());

    future1.complete(null);
    future2.complete(null);
    assertThat(future2).isNotSameInstanceAs(future1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      maximumSize = Maximum.ONE, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void refresh_evicted(CacheContext context) {
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    Int key1 = context.absentKey();
    Int key2 = key1.add(1);
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    LoadingCache<Int, Int> cache = context.build(k -> {
      started.set(true);
      await().forever().untilTrue(done);
      return refreshed;
    });

    cache.put(key1, original);
    var future = cache.refresh(key1);

    await().untilTrue(started);
    cache.put(key2, original);
    cache.cleanUp();
    assertThat(cache).doesNotContainKey(key1);

    done.set(true);
    if (context.isGuava()) {
      future.join();
    } else {
      assertThat(future).succeedsWith(refreshed);
    }

    if (context.isGuava()) {
      await().untilAsserted(() -> assertThat(cache).containsEntry(key1, refreshed));
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key2));
      assertThat(context).removalNotifications().withCause(SIZE)
          .contains(Map.of(key1, original, key2, original)).exclusively();
    } else {
      // linearizable
      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(key1));
      await().untilAsserted(() -> assertThat(cache).containsEntry(key2, original));
      assertThat(context).removalNotifications().withCause(SIZE).contains(key1, original);
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(key1, refreshed);
      assertThat(context).removalNotifications().hasSize(2);
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CheckNoEvictions @CheckMaxLogLevel(TRACE)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_cancel_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.cancel(false);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.refresh(context.absentKey());
    assertThat(TestLoggerFactory.getLoggingEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CheckNoEvictions @CheckMaxLogLevel(TRACE)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_timeout_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.orTimeout(0, TimeUnit.SECONDS);
        await().until(future::isDone);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.refresh(context.absentKey());
    assertThat(TestLoggerFactory.getLoggingEvents()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CheckNoEvictions @CheckMaxLogLevel(WARN)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_error_log(CacheContext context) {
    var expected = new RuntimeException();
    CacheLoader<Int, Int> cacheLoader = key -> { throw expected; };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.refresh(context.absentKey());
    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).hasCauseThat().isSameInstanceAs(expected);
    assertThat(event.getLevel()).isEqualTo(WARN);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_nullFuture_load(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        return null;
      }
    });
    assertThrows(NullPointerException.class, () -> cache.refresh(context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refresh_nullFuture_reload(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        return null;
      }
    });
    cache.put(context.absentKey(), context.absentValue());
    assertThrows(NullPointerException.class, () -> cache.refresh(context.absentKey()));
  }

  /* --------------- refreshAll --------------- */

  @Test(dataProvider = "caches")
  @CheckNoEvictions @CheckNoStats
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refreshAll_null(LoadingCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.refreshAll(null));
  }

  @Test(dataProvider = "caches")
  @CheckNoEvictions @CheckNoStats
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refreshAll_nullKey(LoadingCache<Int, Int> cache, CacheContext context) {
    List<Int> keys = Collections.singletonList(null);
    assertThrows(NullPointerException.class, () -> cache.refreshAll(keys));
  }

  @CacheSpec
  @CheckNoEvictions
  @Test(dataProvider = "caches")
  public void refreshAll_nullLookup(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.refreshAll(context.firstMiddleLastKeys()).join();
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @CacheSpec
  @CheckNoEvictions
  @Test(dataProvider = "caches")
  public void refreshAll_immutable(LoadingCache<Int, Int> cache, CacheContext context) {
    var results = cache.refreshAll(context.firstMiddleLastKeys()).join();
    assertThrows(UnsupportedOperationException.class, results::clear);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refreshAll_absent(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.refreshAll(context.absentKeys()).join();
    int count = context.absentKeys().size();
    assertThat(result).hasSize(count);
    assertThat(cache).hasSize(context.initialSize() + count);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refreshAll_present(LoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.refreshAll(context.original().keySet()).join();
    int count = context.original().size();
    assertThat(result).hasSize(count);

    var expected = Maps.toMap(context.original().keySet(), Functions.identity());
    assertThat(cache).containsExactlyEntriesIn(expected);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      loader = Loader.EXCEPTIONAL, removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refreshAll_failure(LoadingCache<Int, Int> cache, CacheContext context) {
    var future = cache.refreshAll(List.of(
        context.absentKey(), context.firstKey(), context.lastKey()));
    assertThat(future).hasCompletedExceptionally();
    assertThat(cache).hasSize(context.initialSize());
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.REFRESH_INTERRUPTED)
  public void refreshAll_interrupted(LoadingCache<Int, Int> cache, CacheContext context) {
    var e = assertThrows(CompletionException.class, () -> cache.refreshAll(context.absentKeys()));
    assertThat(Thread.interrupted()).isTrue();
    assertThat(e).hasCauseThat().isInstanceOf(InterruptedException.class);

    int failures = context.isGuava() ? 1 : 0;
    assertThat(context).stats().hits(0).misses(0).success(0).failures(failures);
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void refreshAll_cancel(LoadingCache<Int, Int> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    var future2 = cache.refreshAll(List.of(key));

    assertThat(future1).isNotDone();
    future1.cancel(true);

    assertThat(future2).hasCompletedExceptionally();
    assertThat(cache).containsExactlyEntriesIn(context.original());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refreshAll_nullFuture_load(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        return null;
      }
    });
    assertThrows(NullPointerException.class, () -> cache.refreshAll(context.absent().keySet()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void refreshAll_nullFuture_reload(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        return null;
      }
    });
    cache.put(context.absentKey(), context.absentValue());
    assertThrows(NullPointerException.class, () -> cache.refreshAll(context.absent().keySet()));
  }

  /* --------------- CacheLoader --------------- */

  @Test
  public void loadAll() throws Exception {
    CacheLoader<Object, ?> loader = key -> key;
    assertThrows(UnsupportedOperationException.class, () -> loader.loadAll(Set.of()));
  }

  @Test
  public void reload() throws Exception {
    CacheLoader<Int, Int> loader = key -> key;
    assertThat(loader.reload(Int.valueOf(1), Int.valueOf(1))).isEqualTo(1);
  }

  @Test
  public void asyncLoad_exception() throws Exception {
    var e = new Exception();
    CacheLoader<Int, Int> loader = key -> { throw e; };
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run))
        .failsWith(CompletionException.class).hasCauseThat().isSameInstanceAs(e);
  }

  @Test
  public void asyncLoad() throws Exception {
    CacheLoader<Int, Int> loader = key -> key;
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run)).succeedsWith(1);
  }

  @Test
  public void asyncLoadAll_exception() throws Exception {
    var e = new Exception();
    var loader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) throws Exception {
        throw e;
      }
    };
    assertThat(loader.asyncLoadAll(Int.setOf(1), Runnable::run))
        .failsWith(CompletionException.class).hasCauseThat().isSameInstanceAs(e);
  }

  @Test
  public void asyncLoadAll() throws Throwable {
    CacheLoader<Object, ?> loader = key -> key;
    assertThat(loader.asyncLoadAll(Set.of(), Runnable::run))
        .failsWith(CompletionException.class)
        .hasCauseThat().isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void asyncReload_exception() throws Exception {
    for (var e : List.of(new Exception(), new RuntimeException())) {
      CacheLoader<Int, Int> loader = key -> { throw e; };
      assertThat(loader.asyncReload(Int.valueOf(1), Int.valueOf(1), Runnable::run))
          .failsWith(CompletionException.class).hasCauseThat().isSameInstanceAs(e);
    }
  }

  @Test
  public void asyncReload() throws Exception {
    CacheLoader<Int, Int> loader = Int::negate;
    var future = loader.asyncReload(Int.valueOf(1), Int.valueOf(2), Runnable::run);
    assertThat(future).succeedsWith(-1);
  }

  @Test
  public void bulk_null() {
    assertThrows(NullPointerException.class, () -> CacheLoader.bulk(null));
  }

  @Test
  public void bulk_absent() throws Exception {
    CacheLoader<Int, Int> loader = CacheLoader.bulk(keys -> Map.of());
    assertThat(loader.loadAll(Int.setOf(1))).isEmpty();
    assertThat(loader.load(Int.valueOf(1))).isNull();
  }

  @Test
  public void bulk_present() throws Exception {
    CacheLoader<Int, Int> loader = CacheLoader.bulk(keys -> {
      return keys.stream().collect(toImmutableMap(identity(), identity()));
    });
    assertThat(loader.loadAll(Int.setOf(1, 2))).containsExactlyEntriesIn(Int.mapOf(1, 1, 2, 2));
    assertThat(loader.load(Int.valueOf(1))).isEqualTo(1);
  }

  /* --------------- Policy: refreshes --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE)
  public void refreshes(LoadingCache<Int, Int> cache, CacheContext context) {
    var key1 = Iterables.get(context.absentKeys(), 0);
    var key2 = context.original().isEmpty()
        ? Iterables.get(context.absentKeys(), 1)
        : context.firstKey();
    var future1 = cache.refresh(key1);
    var future2 = cache.refresh(key2);
    assertThat(cache.policy().refreshes()).containsExactly(key1, future1, key2, future2);

    future1.complete(Int.valueOf(1));
    future2.cancel(true);
    assertThat(cache.policy().refreshes()).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE)
  public void refreshes_nullLookup(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.refreshAll(context.absentKeys());
    assertThat(cache.policy().refreshes().get(null)).isNull();
    assertThat(cache.policy().refreshes().containsKey(null)).isFalse();
    assertThat(cache.policy().refreshes().containsValue(null)).isFalse();

    for (var future : cache.policy().refreshes().values()) {
      future.cancel(true);
    }
  }
}
