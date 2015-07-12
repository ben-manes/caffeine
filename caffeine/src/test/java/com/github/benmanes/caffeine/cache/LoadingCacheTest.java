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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

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

  /* ---------------- get -------------- */

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

  /* ---------------- getAll -------------- */

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

  /* ---------------- refresh -------------- */

  @CheckNoWriter
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void refresh_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.refresh(null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
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
  @CacheSpec(executor = CacheExecutor.DIRECT,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
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
  @CacheSpec(executor = CacheExecutor.DIRECT,
  population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
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
  @CacheSpec(executor = CacheExecutor.DIRECT, loader = Loader.IDENTITY,
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

  /* ---------------- CacheLoader -------------- */

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void loadAll() {
    CacheLoader<Object, ?> loader = key -> key;
    loader.loadAll(Collections.emptyList());
  }

  @Test
  public void reload() {
    CacheLoader<Integer, Integer> loader = key -> key;
    assertThat(loader.reload(1, 1), is(1));
  }
}
