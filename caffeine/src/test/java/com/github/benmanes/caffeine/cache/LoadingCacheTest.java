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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
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
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
  }

  @CheckNoWriter
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void get_absent_failure(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.get(context.absentKey());
    } finally {
      assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
    }
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void get_absent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = cache.get(key);
    assertThat(value, is(-key));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(LoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.get(context.middleKey()), is(-context.middleKey()));
    assertThat(cache.get(context.lastKey()), is(-context.lastKey()));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
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
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
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
      assertThat(context, both(hasMissCount(misses)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(loadFailures)));
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
      assertThat(context, both(hasMissCount(misses)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(loadFailures)));
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
    assertThat(context, both(hasMissCount(count)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(loads)).and(hasLoadFailureCount(0)));
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
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(expect.size())));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_full(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(context.original().keySet());
    assertThat(result, is(equalTo(context.original())));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(result.size())));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
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

    assertThat(context, hasMissCount(absentKeys.size()));
    assertThat(context, hasHitCount(context.initialSize()));
    assertThat(result.keySet(), is(equalTo(ImmutableSet.copyOf(keys))));

    int loads = context.loader().isBulk() ? 1 : absentKeys.size();
    assertThat(context, both(hasLoadSuccessCount(loads)).and(hasLoadFailureCount(0)));
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
    cache.refresh(null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, compute=Compute.SYNC,
      executor = CacheExecutor.DIRECT, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_remove(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.refresh(context.firstKey());
    assertThat(cache.estimatedSize(), is(context.initialSize() - 1));
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(executor = CacheExecutor.DIRECT, loader = Loader.EXCEPTIONAL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_failure(LoadingCache<Integer, Integer> cache, CacheContext context) {
    // Shouldn't leak exception to caller and should retain stale entry
    cache.refresh(context.absentKey());
    cache.refresh(context.firstKey());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(2)));
  }

  @CheckNoWriter
  @CacheSpec(loader = Loader.NULL)
  @Test(dataProvider = "caches")
  public void refresh_absent_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.refresh(context.absentKey());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void refresh_absent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.refresh(context.absentKey());
    assertThat(cache.estimatedSize(), is(1 + context.initialSize()));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));

    // records a hit
    assertThat(cache.get(context.absentKey()), is(-context.absentKey()));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.NULL,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.refresh(key);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(count)));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.getIfPresent(key), is(nullValue()));
    }
    assertThat(cache.estimatedSize(), is(context.initialSize() - count));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_sameValue(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.refresh(key);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(count)).and(hasLoadFailureCount(0)));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.get(key), is(context.original().get(key)));
    }
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.IDENTITY,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void refresh_present_differentValue(
      LoadingCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.refresh(key);
      // records a hit
      assertThat(cache.get(key), is(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(count)));
    assertThat(context, both(hasLoadSuccessCount(count)).and(hasLoadFailureCount(0)));
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
    cache.refresh(key);
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
    cache.refresh(key);
    cache.invalidate(key);

    refresh.set(true);
    await().until(() -> cache.getIfPresent(key), is(refreshed));
    await().until(() -> cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
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
