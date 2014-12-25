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
import static com.github.benmanes.caffeine.matchers.IsEmptyIterable.deeplyEmpty;
import static com.github.benmanes.caffeine.matchers.IsEmptyMap.emptyMap;
import static com.google.common.collect.Maps.immutableEntry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiFunction;
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
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.SerializableTester;

/**
 * The test cases for the {@link Cache#asMap()} view and its serializability. These tests do not
 * validate eviction management or concurrency behavior.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsMapTest {

  /* ---------------- is empty / size / clear -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void isEmpty(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.isEmpty(), is(context.initiallyEmpty()));
    if (map.isEmpty()) {
      assertThat(map, is(emptyMap()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    map.clear();
    assertThat(map, is(emptyMap()));
    assertThat(map, hasRemovalNotifications(context,
        (int) context.initialSize(), RemovalCause.EXPLICIT));
  }

  /* ---------------- contains -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsKey_null(Map<Integer, Integer> map) {
    map.containsKey(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.containsKey(key), is(true));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.containsKey(context.absentKey()), is(false));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsValue_null(Map<Integer, Integer> map) {
    map.containsValue(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.containsValue(-key), is(true));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.containsValue(-context.absentKey()), is(false));
  }

  /* ---------------- get -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(Map<Integer, Integer> map) {
    map.get(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.get(context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.get(key), is(-key));
    }
  }

  /* ---------------- get -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getOrDefault_nullKey(Map<Integer, Integer> map) {
    map.getOrDefault(null, 1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_default(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.getOrDefault(context.absentKey(), null), is(nullValue()));
    assertThat(map.getOrDefault(context.absentKey(), context.absentKey()), is(context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.getOrDefault(key, context.absentKey()), is(-key));
    }
  }

  /* ---------------- forEach -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void forEach_null(Map<Integer, Integer> map) {
    map.forEach(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_scan(Map<Integer, Integer> map, CacheContext context) {
    Map<Integer, Integer> remaining = new HashMap<>(context.original());
    map.forEach((key, value) -> remaining.remove(key, value));
    assertThat(remaining, is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_modify(Map<Integer, Integer> map, CacheContext context) {
    // non-deterministic traversal behavior with modifications, but shouldn't become corrupted
    map.forEach((key, value) -> map.put(context.lastKey() + key, key));
  }

  /* ---------------- put -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKey(Map<Integer, Integer> map) {
    map.put(null, 1);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullValue(Map<Integer, Integer> map) {
    map.put(1, null);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKeyAndValue(Map<Integer, Integer> map) {
    map.put(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.put(context.absentKey(), -context.absentKey()), is(nullValue()));
    assertThat(map.get(context.absentKey()), is(-context.absentKey()));
    assertThat(map.size(), is((int) context.initialSize() + 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_sameValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.put(key, -key), is(-key));
      assertThat(map.get(key), is(-key));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_differentValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.put(key, -context.absentKey()), is(-key));
      assertThat(map.get(key), is(-context.absentKey()));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- putAll -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_null(Map<Integer, Integer> map) {
    map.putAll(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(Map<Integer, Integer> map, CacheContext context) {
    map.putAll(new HashMap<>());
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_insert(Map<Integer, Integer> map, CacheContext context) {
    int startKey = (int) context.initialSize() + 1;
    Map<Integer, Integer> entries = IntStream
        .range(startKey, 100 + startKey).boxed()
        .collect(Collectors.toMap(Function.identity(), key -> -key));
    map.putAll(entries);
    assertThat(map.size(), is(100 + (int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_replace(Map<Integer, Integer> map, CacheContext context) {
    Map<Integer, Integer> entries = context.original();
    entries.replaceAll((key, value) -> value + 1);
    map.putAll(entries);
    assertThat(map, is(equalTo(entries)));
    assertThat(map, hasRemovalNotifications(context, entries.size(), RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_mixed(Map<Integer, Integer> map, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>(context.original());
    Map<Integer, Integer> entries = new HashMap<>();
    for (int i = 0; i < 2 * context.initialSize(); i++) {
      int value = ((i % 2) == 0) ? i : (i + 1);
      entries.put(i, value);
    }
    expect.putAll(entries);

    map.putAll(entries);
    assertThat(map, is(equalTo(expect)));
    assertThat(map, hasRemovalNotifications(context, entries.size() / 2, RemovalCause.REPLACED));
  }

  /* ---------------- putIfAbsent -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKey(Map<Integer, Integer> map) {
    map.putIfAbsent(null, 2);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullValue(Map<Integer, Integer> map) {
    map.putIfAbsent(1, null);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKeyAndValue(Map<Integer, Integer> map) {
    map.putIfAbsent(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
  removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.putIfAbsent(key, key), is(-key));
      assertThat(map.get(key), is(-key));
    }
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_insert(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.putIfAbsent(context.absentKey(), -context.absentKey()), is(nullValue()));
    assertThat(map.get(context.absentKey()), is(-context.absentKey()));
    assertThat(map.size(), is((int) context.initialSize() + 1));
  }

  /* ---------------- remove -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_nullKey(Map<Integer, Integer> map) {
    map.remove(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.remove(context.absentKey()), is(nullValue()));
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      map.remove(key);
    }
    assertThat(map.size(), is((int) context.initialSize() - context.firstMiddleLastKeys().size()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  /* ---------------- remove conditionally -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKey(Map<Integer, Integer> map) {
    map.remove(null, 1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullValue(Map<Integer, Integer> map) {
    assertThat(map.remove(1, null), is(false)); // see ConcurrentHashMap
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKeyAndValue(Map<Integer, Integer> map) {
    map.remove(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.remove(context.absentKey(), -context.absentKey()), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_presentKey(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.remove(key, key), is(false));
    }
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void removeConditionally_presentKeyAndValue(Map<Integer, Integer> map,
      CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.remove(key, -key), is(true));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is((int) context.initialSize() - count));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  /* ---------------- replace -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_null(Map<Integer, Integer> map) {
    map.replace(null, 1);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullValue(Map<Integer, Integer> map) {
    map.replace(1, null);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullKeyAndValue(Map<Integer, Integer> map) {
    map.replace(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.replace(context.absentKey(), -context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.replace(key, -key), is(-key));
      assertThat(map.get(key), is(-key));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_differentValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.replace(key, -context.absentKey()), is(-key));
      assertThat(map.get(key), is(-context.absentKey()));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- replace conditionally -------------- */

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKey(Map<Integer, Integer> map) {
    map.replace(null, 1, 1);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldValue(Map<Integer, Integer> map) {
    map.replace(1, null, 1);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullNewValue(Map<Integer, Integer> map) {
    map.replace(1, 1, null);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndOldValue(Map<Integer, Integer> map) {
    map.replace(null, null, 1);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndNewValue(Map<Integer, Integer> map) {
    map.replace(null, 1, null);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldAndNewValue(Map<Integer, Integer> map) {
    map.replace(1, null, null);
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndValues(Map<Integer, Integer> map) {
    map.replace(null, null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_absent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.absentKey();
    assertThat(map.replace(key, key, key), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_wrongOldValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.replace(key, key, context.absentKey()), is(false));
      assertThat(map.get(key), is(-key));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.replace(key, -key, -key), is(true));
      assertThat(map.get(key), is(-key));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_differentValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.replace(key, -key, -context.absentKey()), is(true));
      assertThat(map.get(key), is(-context.absentKey()));
    }
    assertThat(map.size(), is((int) context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- replaceAll -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_null(Map<Integer, Integer> map) {
    map.replaceAll(null);
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_nullValue(Map<Integer, Integer> map) {
    map.replaceAll((key, value) -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_sameValue(Map<Integer, Integer> map, CacheContext context) {
    map.replaceAll((key, value) -> value);
    assertThat(map, is(equalTo(context.original())));
    assertThat(map, hasRemovalNotifications(context, map.size(), RemovalCause.REPLACED));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_differentValue(Map<Integer, Integer> map, CacheContext context) {
    map.replaceAll((key, value) -> -value);
    map.forEach((key, value) -> assertThat(value, is(equalTo(key))));
    assertThat(map, hasRemovalNotifications(context, map.size(), RemovalCause.REPLACED));
  }

  /* ---------------- computeIfAbsent -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullKey(Map<Integer, Integer> map) {
    map.computeIfAbsent(null, key -> -key);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullMappingFunction(Map<Integer, Integer> map, CacheContext context) {
    map.computeIfAbsent(context.absentKey(), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_nullValue(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.computeIfAbsent(context.absentKey(), key -> null), is(nullValue()));
    assertThat(map.size(), is(context.original().size()));
  }

  // FIXME: Requires JDK8 release with JDK-8062841 fix
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void computeIfAbsent_recursive(Map<Integer, Integer> map, CacheContext context) {
    Function<Integer, Integer> mappingFunction = new Function<Integer, Integer>() {
      @Override public Integer apply(Integer key) {
        return map.computeIfAbsent(key, this);
      }
    };
    map.computeIfAbsent(context.absentKey(), mappingFunction);
  }

  // FIXME: Requires JDK8 release with JDK-8062841 fix
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void computeIfAbsent_pingpong(Map<Integer, Integer> map, CacheContext context) {
    Function<Integer, Integer> mappingFunction = new Function<Integer, Integer>() {
      @Override public Integer apply(Integer key) {
        return map.computeIfAbsent(-key, this);
      }
    };
    map.computeIfAbsent(context.absentKey(), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_error(Map<Integer, Integer> map, CacheContext context) {
    try {
      map.computeIfAbsent(context.absentKey(), key -> { throw new Error(); });
    } catch (Error e) {}
    assertThat(map, is(equalTo(context.original())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.computeIfAbsent(key, k -> { throw new AssertionError(); }), is(-key));
    }
    assertThat(map.size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.computeIfAbsent(context.absentKey(), key -> -key), is(-context.absentKey()));
    assertThat(map.get(context.absentKey()), is(-context.absentKey()));
    assertThat(map.size(), is(1 + context.original().size()));
  }

  /* ---------------- computeIfPresent -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullKey(Map<Integer, Integer> map) {
    map.computeIfPresent(null, (key, value) -> -key);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullMappingFunction(Map<Integer, Integer> map) {
    map.computeIfPresent(1, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_nullValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      map.computeIfPresent(key, (k, v) -> null);
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size() - count));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void computeIfPresent_recursive(Map<Integer, Integer> map, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    BiFunction<Integer, Integer, Integer> mappingFunction =
        new BiFunction<Integer, Integer, Integer>() {
          boolean recursed;

          @Override public Integer apply(Integer key, Integer value) {
            if (recursed) {
              throw new StackOverflowError();
            }
            recursed = true;
            return map.computeIfPresent(key, this);
          }
        };
    map.computeIfPresent(context.firstKey(), mappingFunction);
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void computeIfPresent_pingpong(Map<Integer, Integer> map, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    BiFunction<Integer, Integer, Integer> mappingFunction =
        new BiFunction<Integer, Integer, Integer>() {
          int recursed;

          @Override public Integer apply(Integer key, Integer value) {
            if (++recursed == 2) {
              throw new StackOverflowError();
            }
            return map.computeIfPresent(context.lastKey(), this);
          }
        };
    map.computeIfPresent(context.firstKey(), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfPresent_error(Map<Integer, Integer> map, CacheContext context) {
    try {
      map.computeIfPresent(context.firstKey(), (key, value) -> { throw new Error(); });
    } catch (Error e) {}
    assertThat(map, is(equalTo(context.original())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfPresent_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.computeIfPresent(context.absentKey(), (key, value) -> -key), is(nullValue()));
    assertThat(map.get(context.absentKey()), is(nullValue()));
    assertThat(map.size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.computeIfPresent(key, (k, v) -> k), is(key));
      assertThat(map.get(key), is(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size()));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- compute -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullKey(Map<Integer, Integer> map) {
    map.compute(null, (key, value) -> -key);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullMappingFunction(Map<Integer, Integer> map) {
    map.computeIfPresent(1, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_remove(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.compute(key, (k, v) -> null), is(nullValue()));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size() - count));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  // FIXME: Requires JDK8 release with JDK-8062841 fix
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void compute_recursive(Map<Integer, Integer> map, CacheContext context) {
    BiFunction<Integer, Integer, Integer> mappingFunction =
        new BiFunction<Integer, Integer, Integer>() {
          @Override public Integer apply(Integer key, Integer value) {
            return map.compute(key, this);
          }
        };
    map.compute(context.absentKey(), mappingFunction);
  }

  // FIXME: Requires JDK8 release with JDK-8062841 fix
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void compute_pingpong(Map<Integer, Integer> map, CacheContext context) {
    BiFunction<Integer, Integer, Integer> mappingFunction =
        new BiFunction<Integer, Integer, Integer>() {
          @Override public Integer apply(Integer key, Integer value) {
            return map.computeIfPresent(context.lastKey(), this);
          }
        };
    map.computeIfPresent(context.firstKey(), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_error(Map<Integer, Integer> map, CacheContext context) {
    try {
      map.compute(context.absentKey(), (key, value) -> { throw new Error(); });
    } catch (Error e) {}
    assertThat(map, is(equalTo(context.original())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.compute(context.absentKey(), (key, value) -> -key), is(-context.absentKey()));
    assertThat(map.get(context.absentKey()), is(-context.absentKey()));
    assertThat(map.size(), is(1 + context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.compute(key, (k, v) -> -k), is(-key));
      assertThat(map.get(key), is(-key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size()));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_differentValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.compute(key, (k, v) -> k), is(key));
      assertThat(map.get(key), is(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size()));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- merge -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullKey(Map<Integer, Integer> map) {
    map.merge(null, 1, (key, value) -> -key);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullValue(Map<Integer, Integer> map) {
    map.merge(1, null, (key, value) -> -key);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullMappingFunction(Map<Integer, Integer> map) {
    map.merge(1, 1, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_remove(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.merge(key, -key, (k, v) -> null), is(nullValue()));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size() - count));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches")
  public void merge_recursive(Map<Integer, Integer> map, CacheContext context) {
    BiFunction<Integer, Integer, Integer> mappingFunction =
        new BiFunction<Integer, Integer, Integer>() {
          @Override public Integer apply(Integer key, Integer value) {
            return map.merge(key, -key, this);
          }
        };
    Integer value = map.merge(context.absentKey(), -context.firstKey(), mappingFunction);
    assertThat(value, is(-context.firstKey()));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void merge_pingpong(Map<Integer, Integer> map, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    BiFunction<Integer, Integer, Integer> mappingFunction =
        new BiFunction<Integer, Integer, Integer>() {
          int recursed;

          @Override public Integer apply(Integer key, Integer value) {
            if (++recursed == 2) {
              throw new StackOverflowError();
            }
            return map.merge(context.lastKey(), -context.lastKey(), this);
          }
        };
    map.merge(context.firstKey(), -context.firstKey(), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_error(Map<Integer, Integer> map, CacheContext context) {
    try {
      map.merge(context.firstKey(), -context.firstKey(), (key, value) -> { throw new Error(); });
    } catch (Error e) {}
    assertThat(map, is(equalTo(context.original())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_absent(Map<Integer, Integer> map, CacheContext context) {
    Integer result = map.merge(context.absentKey(), -context.absentKey(), (key, value) -> -key);
    assertThat(result, is(-context.absentKey()));
    assertThat(map.get(context.absentKey()), is(-context.absentKey()));
    assertThat(map.size(), is(1 + context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.merge(key, -key, (k, v) -> k), is(-key));
      assertThat(map.get(key), is(-key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size()));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_differentValue(Map<Integer, Integer> map, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(map.merge(key, key, (k, v) -> k + v), is(0));
      assertThat(map.get(key), is(0));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map.size(), is(context.original().size()));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  /* ---------------- equals / hashCode -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_null(Map<Integer, Integer> map) {
    assertThat(map.equals(null), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_self(Map<Integer, Integer> map) {
    assertThat(map.equals(map), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.equals(context.original()), is(true));
    assertThat(context.original().equals(map), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map.hashCode(), is(equalTo(context.original().hashCode())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode_self(Map<Integer, Integer> map) {
    assertThat(map.hashCode(), is(equalTo(map.hashCode())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(Map<Integer, Integer> map) {
    Map<Integer, Integer> other = ImmutableMap.of(1, -1, 2, -2, 3, -3);
    assertThat(map.equals(other), is(false));
    assertThat(other.equals(map), is(false));
    assertThat(map.hashCode(), is(not(equalTo(other.hashCode()))));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(Map<Integer, Integer> map) {
    Map<Integer, Integer> other = ImmutableMap.of(1, -1, 2, -2, 3, -3);
    assertThat(map.equals(other), is(false));
    assertThat(other.equals(map), is(false));
    assertThat(map.hashCode(), is(not(equalTo(other.hashCode()))));

    Map<Integer, Integer> empty = ImmutableMap.of();
    assertThat(map.equals(empty), is(false));
    assertThat(empty.equals(map), is(false));
    assertThat(map.hashCode(), is(not(equalTo(empty.hashCode()))));
  }

  /* ---------------- toString -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void toString_empty(Map<Integer, Integer> map, CacheContext context) {
    assertThat(map, hasToString(context.original().toString()));
  }

  /* ---------------- serialize -------------- */

  // FIXME(ben)
  @Test(enabled = false, dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void serialize(Map<Integer, Integer> map) {
    SerializableTester.reserializeAndAssert(map);
  }

  /* ---------------- Key Set -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySetToArray_null(Map<Integer, Integer> map) {
    map.keySet().toArray(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySetToArray(Map<Integer, Integer> map, CacheContext context) {
    int length = context.original().size();

    Integer[] ints = map.keySet().toArray(new Integer[length]);
    assertThat(ints.length, is(length));
    assertThat(Arrays.asList(ints).containsAll(context.original().keySet()), is(true));

    Object[] array = map.keySet().toArray();
    assertThat(array.length, is(length));
    assertThat(Arrays.asList(array).containsAll(context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_whenEmpty(Map<Integer, Integer> map) {
    assertThat(map.keySet(), is(deeplyEmpty()));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void keySet_addNotSupported(Map<Integer, Integer> map) {
    map.keySet().add(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void keySet_clear(Map<Integer, Integer> map, CacheContext context) {
    map.keySet().clear();
    assertThat(map, is(emptyMap()));
    int count = context.original().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void keySet(Map<Integer, Integer> map, CacheContext context) {
    Set<Integer> keys = map.keySet();
    assertThat(keys.contains(new Object()), is(false));
    assertThat(keys.remove(new Object()), is(false));
    assertThat(keys, hasSize(context.original().size()));
    for (Integer key : keys) {
      assertThat(keys.contains(key), is(true));
      assertThat(keys.remove(key), is(true));
      assertThat(keys.remove(key), is(false));
      assertThat(keys.contains(key), is(false));
    }
    assertThat(map, is(emptyMap()));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void keySet_iterator(Map<Integer, Integer> map, CacheContext context) {
    int iterations = 0;
    for (Iterator<Integer> i = map.keySet().iterator(); i.hasNext();) {
      assertThat(map.containsKey(i.next()), is(true));
      iterations++;
      i.remove();
    }
    assertThat(map, hasRemovalNotifications(context, iterations, RemovalCause.EXPLICIT));
    assertThat(iterations, is(context.original().size()));
    assertThat(map, is(emptyMap()));
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void keyIterator_noElement(Map<Integer, Integer> map) {
    map.keySet().iterator().remove();
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void keyIterator_noMoreElements(Map<Integer, Integer> map) {
    map.keySet().iterator().next();
  }

  /* ---------------- Values -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valuesToArray_null(Map<Integer, Integer> map) {
    map.values().toArray(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void valuesToArray(Map<Integer, Integer> map, CacheContext context) {
    int length = context.original().size();

    Integer[] ints = map.values().toArray(new Integer[length]);
    assertThat(ints.length, is(length));
    assertThat(Arrays.asList(ints).containsAll(context.original().values()), is(true));

    Object[] array = map.values().toArray();
    assertThat(array.length, is(length));
    assertThat(Arrays.asList(array).containsAll(context.original().values()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_empty(Map<Integer, Integer> map) {
    assertThat(map.values(), is(deeplyEmpty()));
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void values_addNotSupported(Map<Integer, Integer> map) {
    map.values().add(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void values_clear(Map<Integer, Integer> map, CacheContext context) {
    map.values().clear();
    assertThat(map, is(emptyMap()));
    int count = context.original().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void values(Map<Integer, Integer> map, CacheContext context) {
    Collection<Integer> values = map.values();
    assertThat(values.contains(new Object()), is(false));
    assertThat(values.remove(new Object()), is(false));
    assertThat(values, hasSize(context.original().size()));
    for (Integer key : values) {
      assertThat(values.contains(key), is(true));
      assertThat(values.remove(key), is(true));
      assertThat(values.remove(key), is(false));
      assertThat(values.contains(key), is(false));
    }
    assertThat(map, is(emptyMap()));
    int count = context.original().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void valueIterator(Map<Integer, Integer> map, CacheContext context) {
    int iterations = 0;
    for (Iterator<Integer> i = map.values().iterator(); i.hasNext();) {
      assertThat(map.containsValue(i.next()), is(true));
      iterations++;
      i.remove();
    }
    assertThat(map, hasRemovalNotifications(context, iterations, RemovalCause.EXPLICIT));
    assertThat(iterations, is(context.original().size()));
    assertThat(map, is(emptyMap()));
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void valueIterator_noElement(Map<Integer, Integer> map) {
    map.values().iterator().remove();
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void valueIterator_noMoreElements(Map<Integer, Integer> map) {
    map.values().iterator().next();
  }

  /* ---------------- Entry Set -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySetToArray_null(Map<Integer, Integer> map) {
    map.entrySet().toArray(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entriesToArray(Map<Integer, Integer> map, CacheContext context) {
    int length = context.original().size();

    Object[] ints = map.entrySet().toArray(new Object[length]);
    assertThat(ints.length, is(length));
    assertThat(Arrays.asList(ints).containsAll(context.original().entrySet()), is(true));

    Object[] array = map.entrySet().toArray();
    assertThat(array.length, is(length));
    assertThat(Arrays.asList(array).containsAll(context.original().entrySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_empty(Map<Integer, Integer> map) {
    assertThat(map.entrySet(), is(deeplyEmpty()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_addIsSupported(Map<Integer, Integer> map) {
    assertThat(map.entrySet().add(immutableEntry(1, 2)), is(true));
    assertThat(map.entrySet().add(immutableEntry(1, 2)), is(false));
    assertThat(map.entrySet().size(), is(1));
    assertThat(map.size(), is(1));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void entrySet_clear(Map<Integer, Integer> map, CacheContext context) {
    map.entrySet().clear();
    assertThat(map, is(emptyMap()));
    int count = context.original().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void entrySet(Map<Integer, Integer> map, CacheContext context) {
    Set<Entry<Integer, Integer>> entries = map.entrySet();
    assertThat(entries.contains(new Object()), is(false));
    assertThat(entries.remove(new Object()), is(false));
    assertThat(entries, hasSize(context.original().size()));
    for (Entry<Integer, Integer> entry : entries) {
      assertThat(entries.contains(entry), is(true));
      assertThat(entries.remove(entry), is(true));
      assertThat(entries.remove(entry), is(false));
      assertThat(entries.contains(entry), is(false));
    }
    assertThat(map, is(emptyMap()));
    int count = context.original().size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void entryIterator(Map<Integer, Integer> map, CacheContext context) {
    int iterations = 0;
    for (Iterator<Entry<Integer, Integer>> i = map.entrySet().iterator(); i.hasNext();) {
      Entry<Integer, Integer> entry = i.next();
      assertThat(map, hasEntry(entry.getKey(), entry.getValue()));
      iterations++;
      i.remove();
    }
    assertThat(map, hasRemovalNotifications(context, iterations, RemovalCause.EXPLICIT));
    assertThat(iterations, is(context.original().size()));
    assertThat(map, is(emptyMap()));
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void entryIterator_noElement(Map<Integer, Integer> map) {
    map.entrySet().iterator().remove();
  }

  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void entryIterator_noMoreElements(Map<Integer, Integer> map) {
    map.entrySet().iterator().next();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry(Map<Integer, Integer> map, CacheContext context) {
    Entry<Integer, Integer> entry = map.entrySet().iterator().next();

    entry.setValue(3);
    assertThat(map.get(context.firstKey()), is(3));
    assertThat(map.size(), is(context.original().size()));
    assertThat(map, hasRemovalNotifications(context, 1, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_null(Map<Integer, Integer> map) {
    map.entrySet().iterator().next().setValue(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_serialize(Map<Integer, Integer> map) {
    Entry<Integer, Integer> entry = map.entrySet().iterator().next();
    Object copy = SerializableTester.reserialize(entry);
    assertThat(entry, is(equalTo(copy)));
  }
}
