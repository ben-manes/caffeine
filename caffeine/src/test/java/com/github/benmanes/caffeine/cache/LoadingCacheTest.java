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

import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoWriter;
import com.github.benmanes.caffeine.cache.testing.RemovalNotification;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link LoadingCache} interface that simulate the most generic usages.
 * These tests do not validate eviction management, concurrency behavior, or the
 * {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class LoadingCacheTest {

  /* --------------- get --------------- */

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.get(null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void get_absent_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey()), is(nullValue()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

  }

  @CheckNoWriter
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void get_absent_failure(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.get(context.absentKey());
    } finally {
      verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));
    }
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void get_absent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = cache.get(key);
    assertThat(value, is(-key));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(1).failures(0));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(LoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.get(context.middleKey()), is(-context.middleKey()));
    assertThat(cache.get(context.lastKey()), is(-context.lastKey()));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- getAll --------------- */

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_iterable_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(null);
  }

  @CheckNoWriter
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_iterable_nullKey(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(Collections.singletonList(null));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_empty(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(ImmutableList.of());
    assertThat(result.size(), is(0));
    verifyStats(context, verifier -> verifier.hits(0).misses(0));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAll_immutable(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(context.absentKeys()).clear();
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.NULL)
  public void getAll_absent_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getAll(context.absentKeys()), is(ImmutableMap.of()));
  }

  @CheckNoWriter
  @CacheSpec(loader = Loader.BULK_NULL)
  @Test(dataProvider = "caches", expectedExceptions = Exception.class)
  public void getAll_absent_bulkNull(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(context.absentKeys());
  }

  @CheckNoWriter
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getAll_absent_failure(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys());
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = context.loader().isBulk()
          ? 1
          : (context.isAsync() ? misses : 1);
      verifyStats(context, verifier ->
          verifier.hits(0).misses(misses).success(0).failures(loadFailures));
    }
  }

  @CheckNoWriter
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getAll_absent_failure_iterable(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.getAll(() -> context.absentKeys().iterator());
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = context.loader().isBulk()
          ? 1
          : (context.isAsync() ? misses : 1);
      verifyStats(context, verifier ->
          verifier.hits(0).misses(misses).success(0).failures(loadFailures));
    }
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_absent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(context.absentKeys());

    int count = context.absentKeys().size();
    int loads = context.loader().isBulk() ? 1 : count;
    assertThat(result.size(), is(count));
    verifyStats(context, verifier -> verifier.hits(0).misses(count).success(loads).failures(0));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_partial(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>();
    expect.put(context.firstKey(), -context.firstKey());
    expect.put(context.middleKey(), -context.middleKey());
    expect.put(context.lastKey(), -context.lastKey());
    Map<Integer, Integer> result = cache.getAll(expect.keySet());

    assertThat(result, is(equalTo(expect)));
    verifyStats(context, verifier -> verifier.hits(expect.size()).misses(0).success(0).failures(0));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_full(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(context.original().keySet());
    assertThat(result, is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(result.size()).misses(0).success(0).failures(0));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_duplicates(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    Iterable<Integer> keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    Map<Integer, Integer> result = cache.getAll(keys);
    assertThat(result.keySet(), is(equalTo(ImmutableSet.copyOf(keys))));

    int loads = context.loader().isBulk() ? 1 : absentKeys.size();
    verifyStats(context, verifier ->
        verifier.hits(context.initialSize()).misses(absentKeys.size()).success(loads).failures(0));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_absent(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_partial(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_present(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_exceeds(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys).keySet());
    assertThat(result.subList(0, keys.size()), is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      keys = ReferenceType.STRONG, writer = Writer.DISABLED)
  public void getAll_jdk8186171(CacheContext context) {
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }
    LoadingCache<Object, Integer> cache = context.build(key -> null);

    List<Key> keys = new ArrayList<>();
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(new Key());
    }
    Key key = Iterables.getLast(keys);
    Integer value = context.absentValue();
    cache.put(key, value);

    Map<Object, Integer> result = cache.getAll(keys);
    assertThat(result.values(), not(hasItem(nullValue())));
    assertThat(result, is(equalTo(ImmutableMap.of(key, value))));
  }

  /* --------------- refresh --------------- */

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void refresh_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.refresh(null).join();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine)
  public void refresh_dedupe(LoadingCache<Integer, Integer> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    var future2 = cache.refresh(key);
    assertThat(future1, is(sameInstance(future2)));

    future1.complete(-key);
    assertThat(cache.getIfPresent(key), is(-key));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      executor = CacheExecutor.DIRECT, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_remove(LoadingCache<Integer, Integer> cache, CacheContext context) {
    var future = cache.refresh(context.firstKey());
    assertThat(future.join(), is(nullValue()));
    assertThat(cache.estimatedSize(), is(context.initialSize() - 1));
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.EXPLICIT));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DIRECT, loader = Loader.EXCEPTIONAL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_failure(LoadingCache<Integer, Integer> cache, CacheContext context) {
    // Shouldn't leak exception to caller nor retain the future; should retain the stale entry
    var future1 = cache.refresh(context.absentKey());
    var future2 = cache.refresh(context.firstKey());
    var future3 = cache.refresh(context.firstKey());
    assertThat(future2, is(not(sameInstance(future3))));
    assertThat(future1.isCompletedExceptionally(), is(true));
    assertThat(future2.isCompletedExceptionally(), is(true));
    assertThat(future3.isCompletedExceptionally(), is(true));
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    verifyStats(context, verifier -> verifier.success(0).failures(3));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE, implementation = Implementation.Caffeine,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refresh_cancel(LoadingCache<Integer, Integer> cache, CacheContext context) {
    var key = context.original().isEmpty() ? context.absentKey() : context.firstKey();
    var future1 = cache.refresh(key);
    assertThat(future1.isDone(), is(false));
    future1.cancel(true);

    var future2 = cache.refresh(key);
    assertThat(future1, is(not(sameInstance(future2))));

    future2.cancel(false);
    assertThat(cache.asMap(), is(equalTo(context.original())));
  }

  @CheckNoWriter
  @CacheSpec(loader = Loader.NULL)
  @Test(dataProvider = "caches")
  public void refresh_absent_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    var future = cache.refresh(context.absentKey());
    assertThat(future.join(), is(nullValue()));
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(
      maximumSize = Maximum.UNREACHABLE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING }, population = Population.SINGLETON)
  public void refresh_absent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    var future = cache.refresh(key);
    assertThat(future.join(), is(not(nullValue())));
    assertThat(cache.estimatedSize(), is(1 + context.initialSize()));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(1).failures(0));

    // records a hit
    assertThat(cache.get(context.absentKey()), is(-context.absentKey()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future.join(), is(nullValue()));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(count));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }
    assertThat(cache.estimatedSize(), is(context.initialSize() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_sameValue(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future.join(), is(context.original().get(key)));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.get(key), is(context.original().get(key)));
    }
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.IDENTITY,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_differentValue(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      var future = cache.refresh(key);
      assertThat(future.join(), is(key));

      // records a hit
      assertThat(cache.get(key), is(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
    verifyStats(context, verifier -> verifier.hits(count).misses(0).success(count).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  public void refresh_conflict(CacheContext context) {
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
    var future = cache.refresh(key);
    assertThat(cache.asMap().put(key, updated), is(original));

    refresh.set(true);
    future.join();

    await().until(() -> context.removalNotifications().size(), is(2));
    List<Integer> removed = context.removalNotifications().stream()
        .map(RemovalNotification::getValue).collect(toList());

    assertThat(cache.getIfPresent(key), is(updated));
    assertThat(removed, containsInAnyOrder(original, refreshed));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(2, RemovalCause.REPLACED));
    verifyStats(context, verifier -> verifier.success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      removalListener = Listener.CONSUMING)
  public void refresh_invalidate(CacheContext context) {
    AtomicBoolean refresh = new AtomicBoolean();
    Integer key = context.absentKey();
    Integer original = 1;
    Integer refreshed = 2;
    LoadingCache<Integer, Integer> cache = context.build(k -> {
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    var future = cache.refresh(key);
    cache.invalidate(key);

    refresh.set(true);
    future.join();

    assertThat(cache.getIfPresent(key), is(nullValue()));
    await().until(() -> context.removalNotifications(), hasSize(1));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.EXPLICIT));
    verifyStats(context, verifier -> verifier.success(1).failures(0));
  }

  /* --------------- CacheLoader --------------- */

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void loadAll() throws Exception {
    CacheLoader<Object, ?> loader = key -> key;
    loader.loadAll(Collections.emptyList());
  }

  @Test
  public void reload() throws Exception {
    CacheLoader<Integer, Integer> loader = key -> key;
    assertThat(loader.reload(1, 1), is(1));
  }

  @Test
  public void asyncLoad_exception() throws Exception {
    Exception e = new Exception();
    CacheLoader<Integer, Integer> loader = key -> { throw e; };
    try {
      loader.asyncLoad(1, Runnable::run).join();
    } catch (CompletionException ex) {
      assertThat(ex.getCause(), is(sameInstance(e)));
    }
  }

  @Test
  public void asyncLoad() throws Exception {
    CacheLoader<Integer, ?> loader = key -> key;
    CompletableFuture<?> future = loader.asyncLoad(1, Runnable::run);
    assertThat(future.get(), is(1));
  }

  @Test
  public void asyncLoadAll_exception() throws Exception {
    Exception e = new Exception();
    CacheLoader<Integer, Integer> loader = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) throws Exception {
        throw new AssertionError();
      }
      @Override public Map<Integer, Integer> loadAll(
          Iterable<? extends Integer> keys) throws Exception {
        throw e;
      }
    };
    try {
      loader.asyncLoadAll(Arrays.asList(1), Runnable::run).join();
    } catch (CompletionException ex) {
      assertThat(ex.getCause(), is(sameInstance(e)));
    }
  }

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void asyncLoadAll() throws Throwable {
    CacheLoader<Object, ?> loader = key -> key;
    try {
      loader.asyncLoadAll(Collections.emptyList(), Runnable::run).get();
    } catch (ExecutionException e) {
      throw e.getCause();
    }
  }

  @Test
  public void asyncReload_exception() throws Exception {
    for (Exception e : Arrays.asList(new Exception(), new RuntimeException())) {
      CacheLoader<Integer, Integer> loader = key -> { throw e; };
      try {
        loader.asyncReload(1, 1, Runnable::run).join();
        Assert.fail();
      } catch (CompletionException ex) {
        assertThat(ex.getCause(), is(sameInstance(e)));
      }
    }
  }

  @Test
  public void asyncReload() throws Exception {
    CacheLoader<Integer, Integer> loader = key -> -key;
    CompletableFuture<?> future = loader.asyncReload(1, 2, Runnable::run);
    assertThat(future.get(), is(-1));
  }
}
