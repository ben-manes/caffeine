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
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.ImmutableList;

/**
 * The test cases for the {@link Cache} interface that simulate the most generic usages. These
 * tests do not validate eviction management, concurrency behavior, or the {@link Cache#asMap()}
 * view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CacheTest {

  /* ---------------- size -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.size(), is(context.initialSize()));
  }

  /* ---------------- getIfPresent -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getIfPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(context, hasMissCount(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.lastKey()), is(not(nullValue())));
    assertThat(context, hasHitCount(3));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(Cache<Integer, Integer> cache) {
    cache.getIfPresent(null);
  }

  /* ---------------- get -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    Integer key = context.absentKey();
    Integer value = cache.get(key, k -> -key);
    assertThat(value, is(-key));
    assertThat(context, hasMissCount(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    Function<Integer, Integer> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader), is(-context.firstKey()));
    assertThat(cache.get(context.middleKey(), loader), is(-context.middleKey()));
    assertThat(cache.get(context.lastKey(), loader), is(-context.lastKey()));
    assertThat(context, hasHitCount(3));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKey(Cache<Integer, Integer> cache) throws Exception {
    cache.get(null, Function.identity());
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullLoader(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    cache.get(context.absentKey(), null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKeyAndLoader(Cache<Integer, Integer> cache) throws Exception {
    cache.get(null, null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void get_throwsException(Cache<Integer, Integer> cache, CacheContext context)
      throws Exception {
    cache.get(context.absentKey(), key -> { throw new IllegalStateException(); });
  }

  /* ---------------- getAllPresent -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(context.absentKeys());
    assertThat(result.size(), is(0));
    assertThat(context, hasMissCount(context.absentKeys().size()));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAllPresent_absent_immutable(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(context.absentKeys()).clear();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_partial(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>();
    expect.put(context.firstKey(), -context.firstKey());
    expect.put(context.middleKey(), -context.middleKey());
    expect.put(context.lastKey(), -context.lastKey());
    Map<Integer, Integer> result = cache.getAllPresent(expect.keySet());
    assertThat(result, is(equalTo(expect)));
    assertThat(context, hasHitCount(expect.size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_full(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(cache.asMap().keySet());
    assertThat(result, is(equalTo(cache.asMap())));
    assertThat(context, hasHitCount(result.size()));
    assertThat(context, hasMissCount(0));
  }

  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getAllPresent_present_immutable(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(cache.asMap().keySet()).clear();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_empty(Cache<Integer, Integer> cache) {
    Map<Integer, Integer> result = cache.getAllPresent(ImmutableList.of());
    assertThat(result.size(), is(0));
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_nullKey(Cache<Integer, Integer> cache) {
    cache.getAllPresent(Collections.singletonList(null));
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_null(Cache<Integer, Integer> cache) {
    cache.getAllPresent(null);
  }

  /* ---------------- put -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), -context.absentKey());
    assertThat(cache.size(), is(context.initialSize() + 1));
    assertThat(cache.getIfPresent(context.absentKey()), is(-context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameValue(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.put(key, -key);
      assertThat(cache.getIfPresent(key), is(-key));
    }
    assertThat(cache.size(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.put(key, -context.absentKey());
      assertThat(cache.getIfPresent(key), is(-context.absentKey()));
    }
    assertThat(cache.size(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, -context.absentKey());
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKeyAndValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, null);
  }

  /* ---------------- put all -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_insert(Cache<Integer, Integer> cache, CacheContext context) {
    int startKey = context.original().size() + 1;
    Map<Integer, Integer> entries = IntStream
        .range(startKey, 100 + startKey).boxed()
        .collect(Collectors.toMap(Function.identity(), key -> -key));
    cache.putAll(entries);
    assertThat(cache.size(), is(100 + context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_replace(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> entries = new HashMap<>(context.original());
    entries.replaceAll((key, value) -> value + 1);
    cache.putAll(entries);
    assertThat(cache.asMap(), is(equalTo(entries)));
    assertThat(cache, hasRemovalNotifications(context, entries.size(), RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_mixed(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>(context.original());
    Map<Integer, Integer> entries = new HashMap<>();
    for (int i = 0; i < 2 * context.initialSize(); i++) {
      int value = ((i % 2) == 0) ? i : (i + 1);
      entries.put(i, value);
    }
    expect.putAll(entries);

    cache.putAll(entries);
    assertThat(cache.asMap(), is(equalTo(expect)));
    assertThat(cache, hasRemovalNotifications(context, entries.size() / 2, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(Cache<Integer, Integer> cache, CacheContext context) {
    cache.putAll(new HashMap<>());
    assertThat(cache.size(), is(context.initialSize()));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void putAll_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.putAll(null);
  }

  /* ---------------- invalidate -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void invalidate_absent(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.absentKey());
    assertThat(cache.size(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.invalidate(key);
    }
    assertThat(cache.size(), is(context.initialSize() - context.firstMiddleLastKeys().size()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidate_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(null);
  }

  /* ---------------- invalidateAll -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll();
    assertThat(cache.size(), is(0L));
    assertThat(cache, hasRemovalNotifications(context,
        context.original().size(), RemovalCause.EXPLICIT));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void invalidateAll_empty(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(new HashSet<>());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL })
  public void invalidateAll_partial(Cache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = cache.asMap().keySet().stream()
        .filter(i -> ((i % 2) == 0))
        .collect(Collectors.toList());
    cache.invalidateAll(keys);
    assertThat(cache.size(), is(context.initialSize() - keys.size()));
    assertThat(cache, hasRemovalNotifications(context, keys.size(), RemovalCause.EXPLICIT));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(cache.asMap().keySet());
    assertThat(cache.size(), is(0L));
    assertThat(cache, hasRemovalNotifications(context,
        context.original().size(), RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidateAll_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(null);
  }

  /* ---------------- cleanup -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void cleanup(Cache<Integer, Integer> cache, CacheContext context) {
    cache.cleanUp();
  }

  /* ---------------- stats -------------- */

  /* ---------------- serialize -------------- */

}
