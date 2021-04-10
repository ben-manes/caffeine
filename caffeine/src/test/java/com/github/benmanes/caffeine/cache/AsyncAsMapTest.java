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

import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static com.github.benmanes.caffeine.testing.IsEmptyIterable.deeplyEmpty;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.google.common.collect.Maps.immutableEntry;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * The test cases for the {@link AsyncCache#asMap()} view and its serializability. These tests do
 * not validate eviction management or concurrency behavior.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsyncAsMapTest {
  // Statistics are recorded only for computing methods for loadSuccess and loadFailure

  /* ---------------- is empty / size / clear -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void isEmpty(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().isEmpty(), is(context.original().isEmpty()));
    if (cache.asMap().isEmpty()) {
      assertThat(cache.asMap(), is(emptyMap()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void clear(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(context.original().size(), RemovalCause.EXPLICIT));
  }

  /* ---------------- contains -------------- */

  @CheckNoStats
  @SuppressWarnings("ReturnValueIgnored")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsKey_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().containsKey(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().containsKey(key), is(true));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().containsKey(context.absentKey()), is(false));
  }

  @CheckNoStats
  @SuppressWarnings("ReturnValueIgnored")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsValue_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().containsValue(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().containsValue(cache.asMap().get(key)), is(true));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().containsValue(
        CompletableFuture.completedFuture(context.absentValue())), is(false));
  }

  /* ---------------- get -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().get(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().get(context.absentKey()), is(nullValue()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().get(key).join(), is(context.original().get(key)));
    }
  }

  /* ---------------- getOrDefault -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getOrDefault_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().getOrDefault(null, CompletableFuture.completedFuture(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().getOrDefault(context.absentKey(), null), is(nullValue()));
    assertThat(cache.asMap().getOrDefault(context.absentKey(), value), is(value));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().getOrDefault(key, value).join(), is(context.original().get(key)));
    }
  }

  /* ---------------- forEach -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void forEach_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().forEach(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_scan(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> remaining = new HashMap<>(context.original());
    cache.asMap().forEach((k, v) -> remaining.remove(k, v.join()));
    assertThat(remaining, is(emptyMap()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_modify(AsyncCache<Integer, Integer> cache, CacheContext context) {
    // non-deterministic traversal behavior with modifications, but shouldn't become corrupted
    @SuppressWarnings("ModifiedButNotUsed")
    List<Integer> modified = new ArrayList<>();
    cache.asMap().forEach((key, value) -> {
      Integer newKey = context.lastKey() + key;
      modified.add(newKey); // for weak keys
      cache.synchronous().put(newKey, key);
    });
  }

  /* ---------------- put -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().put(null, CompletableFuture.completedFuture(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().put(1, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKeyAndValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().put(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().put(context.absentKey(), value), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(value));
    assertThat(cache.asMap().size(), is(context.original().size() + 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_sameValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().put(key, value), is(value));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_differentValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> newValue =
          CompletableFuture.completedFuture(context.absentValue());
      assertThat(cache.asMap().put(key, newValue).join(), is(context.original().get(key)));
      assertThat(cache.asMap().get(key), is(newValue));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- putAll -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().putAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().putAll(Collections.emptyMap());
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_insert(AsyncCache<Integer, Integer> cache, CacheContext context) {
    int startKey = context.original().size() + 1;
    Map<Integer, CompletableFuture<Integer>> entries = IntStream
        .range(startKey, 100 + startKey).boxed()
        .collect(toMap(identity(), key -> CompletableFuture.completedFuture(-key)));
    cache.asMap().putAll(entries);
    assertThat(cache.asMap().size(), is(100 + context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_replace(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, CompletableFuture<Integer>> entries = context.original().keySet().stream()
        .collect(toMap(identity(), CompletableFuture::completedFuture));
    cache.asMap().putAll(entries);
    assertThat(cache.asMap(), is(equalTo(entries)));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(entries.size(), RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_mixed(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, CompletableFuture<Integer>> entries = new HashMap<>();
    List<Integer> replaced = new ArrayList<>();
    context.original().forEach((key, value) -> {
      if ((key % 2) == 0) {
        value++;
        replaced.add(key);
        entries.put(key, CompletableFuture.completedFuture(value));
      } else {
        entries.put(key, cache.asMap().get(key));
      }
    });

    cache.asMap().putAll(entries);
    assertThat(cache.asMap(), is(equalTo(entries)));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(replaced.size(), RemovalCause.REPLACED));
  }

  /* ---------------- putIfAbsent -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().putIfAbsent(null, CompletableFuture.completedFuture(2));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().putIfAbsent(1, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKeyAndValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().putIfAbsent(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
  removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().putIfAbsent(key, CompletableFuture.completedFuture(key)), is(value));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_insert(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().putIfAbsent(context.absentKey(), value), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(value));
    assertThat(cache.asMap().size(), is(context.original().size() + 1));
  }

  /* ---------------- remove -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().remove(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().remove(context.absentKey()), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void remove_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.asMap().remove(key);
    }
    assertThat(cache.asMap().size(), is(
        context.original().size() - context.firstMiddleLastKeys().size()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  /* ---------------- remove conditionally -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKey(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().remove(null, 1);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().remove(1, null), is(false)); // see ConcurrentHashMap
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKeyAndValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().remove(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().remove(context.absentKey(), value), is(false));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_presentKey(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = CompletableFuture.completedFuture(key);
      assertThat(cache.asMap().remove(key, value), is(false));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void removeConditionally_presentKeyAndValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().remove(key, value), is(true));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  /* ---------------- replace -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replace(null, CompletableFuture.completedFuture(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replace(1, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullKeyAndValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replace(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().replace(context.absentKey(),
        CompletableFuture.completedFuture(context.absentValue())), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value), is(value));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_differentValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> oldValue = cache.asMap().get(key);
      CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
      assertThat(cache.asMap().replace(key, value), is(oldValue));
      assertThat(cache.asMap().get(key), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- replace conditionally -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKey(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(1);
    cache.asMap().replace(null, value, value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(1);
    cache.asMap().replace(1, null, value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullNewValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(1);
    cache.asMap().replace(1, value, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndOldValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(1);
    cache.asMap().replace(null, null, value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndNewValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(1);
    cache.asMap().replace(null, value, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldAndNewValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replace(1, null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndValues(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replace(null, null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_absent(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().replace(context.absentKey(), value, value), is(false));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_wrongOldValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> oldValue = cache.asMap().get(key);
      CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
      assertThat(cache.asMap().replace(key, value, value), is(false));
      assertThat(cache.asMap().get(key), is(oldValue));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value, value), is(true));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_differentValue(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> oldValue = cache.asMap().get(key);
      CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
      assertThat(cache.asMap().replace(key, oldValue, value), is(true));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- replaceAll -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replaceAll(null);
  }

  @CheckNoStats
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_sameValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> value);
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_differentValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> CompletableFuture.completedFuture(key));
    cache.asMap().forEach((key, value) -> {
      assertThat(value.join(), is(equalTo(key)));
    });
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(cache.asMap().size(), RemovalCause.REPLACED));
  }

  /* ---------------- computeIfAbsent -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().computeIfAbsent(null, key -> CompletableFuture.completedFuture(-key));
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullMappingFunction(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().computeIfAbsent(context.absentKey(), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> null), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine)
  public void computeIfAbsent_recursive(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, CompletableFuture<Integer>> mappingFunction =
        new Function<Integer, CompletableFuture<Integer>>() {
          @Override public CompletableFuture<Integer> apply(Integer key) {
            return cache.asMap().computeIfAbsent(key, this);
          }
        };
    try {
      cache.asMap().computeIfAbsent(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine)
  public void computeIfAbsent_pingpong(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, CompletableFuture<Integer>> mappingFunction =
        new Function<Integer, CompletableFuture<Integer>>() {
          @Override public CompletableFuture<Integer> apply(Integer key) {
            return cache.asMap().computeIfAbsent(-key, this);
          }
        };
    try {
      cache.asMap().computeIfAbsent(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_error(AsyncCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.asMap().computeIfAbsent(context.absentKey(),
          key -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException e) {}

    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(),
        key -> CompletableFuture.completedFuture(key)).join(), is(context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().computeIfAbsent(key,
          k -> { throw new AssertionError(); }), is(not(nullValue())));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyStats(context, verifier -> verifier.hits(count).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(stats = CacheSpec.Stats.ENABLED,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> value), is(value));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(1).failures(0));

    assertThat(cache.asMap().get(context.absentKey()), is(value));
    assertThat(cache.asMap().size(), is(1 + context.original().size()));
  }

  /* ---------------- computeIfPresent -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().computeIfPresent(null, (key, value) -> CompletableFuture.completedFuture(-key));
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullMappingFunction(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().computeIfPresent(1, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.asMap().computeIfPresent(key, (k, v) -> null);
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void computeIfPresent_recursive(AsyncCache<Integer, Integer> cache, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>> mappingFunction =
        new BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>>() {
          boolean recursed;

          @Override public CompletableFuture<Integer> apply(
              Integer key, CompletableFuture<Integer> value) {
            if (recursed) {
              throw new StackOverflowError();
            }
            recursed = true;
            return cache.asMap().computeIfPresent(key, this);
          }
        };
    cache.asMap().computeIfPresent(context.firstKey(), mappingFunction);
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void computeIfPresent_pingpong(AsyncCache<Integer, Integer> cache, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>> mappingFunction =
        new BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>>() {
          int recursed;

          @Override public CompletableFuture<Integer> apply(
              Integer key, CompletableFuture<Integer> value) {
            if (++recursed == 2) {
              throw new StackOverflowError();
            }
            return cache.asMap().computeIfPresent(context.lastKey(), this);
          }
        };
    cache.asMap().computeIfPresent(context.firstKey(), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_error(AsyncCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.asMap().computeIfPresent(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException e) {}
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));

    assertThat(cache.asMap().computeIfPresent(context.firstKey(),
        (k, v) -> CompletableFuture.completedFuture(-k)).join(), is(-context.firstKey()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfPresent_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfPresent(
        context.absentKey(), (key, value) -> value), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = CompletableFuture.completedFuture(key);
      assertThat(cache.asMap().computeIfPresent(key, (k, v) -> value), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.synchronous().asMap().get(key), is(key));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- compute -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().compute(null, (key, value) -> CompletableFuture.completedFuture(-key));
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullMappingFunction(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().compute(1, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_remove(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().compute(key, (k, v) -> null), is(nullValue()));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine)
  public void compute_recursive(AsyncCache<Integer, Integer> cache, CacheContext context) {
    BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>> mappingFunction =
        new BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>>() {
          @Override public CompletableFuture<Integer> apply(
              Integer key, CompletableFuture<Integer> value) {
            return cache.asMap().compute(key, this);
          }
        };
    try {
      cache.asMap().compute(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @CacheSpec(population = Population.EMPTY, implementation = Implementation.Caffeine)
  @Test(dataProvider = "caches")
  public void compute_pingpong(AsyncCache<Integer, Integer> cache, CacheContext context) {
    var key1 = 1;
    var key2 = 2;
    BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>> mappingFunction =
        new BiFunction<Integer, CompletableFuture<Integer>, CompletableFuture<Integer>>() {
          @Override public CompletableFuture<Integer> apply(
              Integer key, CompletableFuture<Integer> value) {
            return cache.asMap().compute((key == key1) ? key2 : key1, this);
          }
        };
    try {
      cache.asMap().compute(key1, mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_error(AsyncCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.asMap().compute(context.absentKey(),
          (key, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException e) {}
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));

    var future = CompletableFuture.completedFuture(-context.absentKey());
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> future), is(future));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().compute(context.absentKey(), (key, value) -> null), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(context.absentValue());
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> value), is(value));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(1).failures(0));

    assertThat(cache.asMap().size(), is(1 + context.original().size()));
    assertThat(cache.asMap().get(context.absentKey()), is(value));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().compute(key, (k, v) -> v), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Integer key : context.firstMiddleLastKeys()) {
      Integer value = context.original().get(key);
      assertThat(cache.synchronous().asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_differentValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = CompletableFuture.completedFuture(key);
      assertThat(cache.asMap().compute(key, (k, v) -> value), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.synchronous().asMap().get(key), is(key));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- merge -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullKey(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().merge(null, CompletableFuture.completedFuture(-1), (oldValue, value) -> value);
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().merge(1, null, (oldValue, value) -> value);
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullMappingFunction(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().merge(1, CompletableFuture.completedFuture(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_remove(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().merge(key, value, (oldValue, v) -> null), is(nullValue()));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches")
  public void merge_recursive(AsyncCache<Integer, Integer> cache, CacheContext context) {
    BiFunction<CompletableFuture<Integer>, CompletableFuture<Integer>, CompletableFuture<Integer>> mappingFunction =
        new BiFunction<CompletableFuture<Integer>, CompletableFuture<Integer>, CompletableFuture<Integer>>() {
          @Override public CompletableFuture<Integer> apply(
              CompletableFuture<Integer> oldValue, CompletableFuture<Integer> value) {
            return cache.asMap().merge(context.absentKey(), oldValue, this);
          }
        };
    CompletableFuture<Integer> firstValue = cache.asMap().get(context.firstKey());
    CompletableFuture<Integer> value = cache.asMap().merge(
        context.absentKey(), firstValue, mappingFunction);
    assertThat(value, is(firstValue));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void merge_pingpong(AsyncCache<Integer, Integer> cache, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    BiFunction<CompletableFuture<Integer>, CompletableFuture<Integer>, CompletableFuture<Integer>> mappingFunction =
        new BiFunction<CompletableFuture<Integer>, CompletableFuture<Integer>, CompletableFuture<Integer>>() {
          int recursed;

          @Override public CompletableFuture<Integer> apply(
              CompletableFuture<Integer> oldValue, CompletableFuture<Integer> value) {
            if (++recursed == 2) {
              throw new StackOverflowError();
            }
            CompletableFuture<Integer> lastValue = cache.asMap().get(context.lastKey());
            return cache.asMap().merge(context.lastKey(), lastValue, this);
          }
        };
    cache.asMap().merge(context.firstKey(),
        cache.asMap().get(context.firstKey()), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_error(AsyncCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.asMap().merge(context.firstKey(), cache.asMap().get(context.firstKey()),
          (oldValue, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException e) {}
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));

    assertThat(cache.asMap().merge(context.firstKey(), cache.asMap().get(context.firstKey()),
        (oldValue, value) -> CompletableFuture.completedFuture(context.absentValue())).join(),
        is(context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_absent(AsyncCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> absent = CompletableFuture.completedFuture(context.absentValue());
    CompletableFuture<Integer> result = cache.asMap().merge(
        context.absentKey(), absent, (oldValue, value) -> value);
    assertThat(result, is(absent));
    assertThat(cache.asMap().get(context.absentKey()), is(absent));
    assertThat(cache.asMap().size(), is(1 + context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      CompletableFuture<Integer> value = cache.asMap().get(key);
      assertThat(cache.asMap().merge(key, CompletableFuture.completedFuture(-key),
          (oldValue, v) -> oldValue), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Integer key : context.firstMiddleLastKeys()) {
      Integer value = context.original().get(key);
      assertThat(cache.synchronous().asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_differentValue(AsyncCache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().merge(key, CompletableFuture.completedFuture(key), (oldValue, v) ->
          CompletableFuture.completedFuture(oldValue.join() + v.join())).join(), is(0));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Integer key : context.firstMiddleLastKeys()) {
      assertThat(cache.synchronous().asMap().get(key), is(0));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- equals / hashCode -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().equals(null), is(false));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @SuppressWarnings("SelfEquals")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_self(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().equals(cache.asMap()), is(true));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, CompletableFuture<Integer>> map = ImmutableMap.copyOf(cache.asMap());
    assertThat(cache.asMap().equals(map), is(true));
    assertThat(map.equals(cache.asMap()), is(true));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode(), is(ImmutableMap.copyOf(cache.asMap()).hashCode()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode_self(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode(), is(equalTo(cache.asMap().hashCode())));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, CompletableFuture<Integer>> other = ImmutableMap.of(
        1, CompletableFuture.completedFuture(-1),
        2, CompletableFuture.completedFuture(-2),
        3, CompletableFuture.completedFuture(-3));
    assertThat(cache.asMap().equals(other), is(false));
    assertThat(other.equals(cache.asMap()), is(false));
    assertThat(cache.asMap().hashCode(), is(not(equalTo(other.hashCode()))));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, CompletableFuture<Integer>> other = ImmutableMap.of(
        1, CompletableFuture.completedFuture(-1),
        2, CompletableFuture.completedFuture(-2),
        3, CompletableFuture.completedFuture(-3));
    assertThat(cache.asMap().equals(other), is(false));
    assertThat(other.equals(cache.asMap()), is(false));
    assertThat(cache.asMap().hashCode(), is(not(equalTo(other.hashCode()))));

    Map<Integer, CompletableFuture<Integer>> empty = ImmutableMap.of();
    assertThat(cache.asMap().equals(empty), is(false));
    assertThat(empty.equals(cache.asMap()), is(false));
    assertThat(cache.asMap().hashCode(), is(not(equalTo(empty.hashCode()))));
  }

  /* ---------------- toString -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void toString(AsyncCache<Integer, Integer> cache, CacheContext context) {
    String toString = cache.asMap().toString();
    if (!context.original().toString().equals(toString)) {
      cache.asMap().forEach((key, value) -> {
        assertThat(toString, containsString(key + "=" + value));
      });
    }
  }

  /* ---------------- Key Set -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySetToArray_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().toArray((Integer[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySetToArray(AsyncCache<Integer, Integer> cache, CacheContext context) {
    int length = context.original().size();

    Integer[] ints = cache.asMap().keySet().toArray(new Integer[length]);
    assertThat(ints.length, is(length));
    assertThat(Arrays.asList(ints).containsAll(context.original().keySet()), is(true));

    Object[] array = cache.asMap().keySet().toArray();
    assertThat(array.length, is(length));
    assertThat(Arrays.asList(array).containsAll(context.original().keySet()), is(true));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_whenEmpty(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().keySet(), is(deeplyEmpty()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void keySet_addNotSupported(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().add(1);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_clear(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = cache.asMap().keySet();
    assertThat(keys.contains(new Object()), is(false));
    assertThat(keys.remove(new Object()), is(false));
    assertThat(keys, hasSize(context.original().size()));
    for (Integer key : ImmutableSet.copyOf(keys)) {
      assertThat(keys.contains(key), is(true));
      assertThat(keys.remove(key), is(true));
      assertThat(keys.remove(key), is(false));
      assertThat(keys.contains(key), is(false));
    }
    assertThat(cache.asMap(), is(emptyMap()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_iterator(AsyncCache<Integer, Integer> cache, CacheContext context) {
    int iterations = 0;
    for (Iterator<Integer> i = cache.asMap().keySet().iterator(); i.hasNext();) {
      assertThat(cache.asMap().containsKey(i.next()), is(true));
      iterations++;
      i.remove();
    }
    int count = iterations;
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
    assertThat(iterations, is(context.original().size()));
    assertThat(cache.asMap(), is(emptyMap()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void keyIterator_noElement(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void keyIterator_noMoreElements(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySpliterator_forEachRemaining_null(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_forEachRemaining(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    int[] count = new int[1];
    cache.asMap().keySet().spliterator().forEachRemaining(key -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySpliterator_tryAdvance_null(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().keySet().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_tryAdvance(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<Integer> spliterator = cache.asMap().keySet().spliterator();
    int[] count = new int[1];
    boolean advanced;
    do {
      advanced = spliterator.tryAdvance(key -> count[0]++);
    } while (advanced);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_trySplit(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<Integer> spliterator = cache.asMap().keySet().spliterator();
    Spliterator<Integer> other = MoreObjects.firstNonNull(
        spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(key -> count[0]++);
    other.forEachRemaining(key -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_estimateSize(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<Integer> spliterator = cache.asMap().keySet().spliterator();
    assertThat((int) spliterator.estimateSize(), is(cache.asMap().size()));
  }

  /* ---------------- Values -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valuesToArray_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().toArray((CompletableFuture<Integer>[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void valuesToArray(AsyncCache<Integer, Integer> cache, CacheContext context) {
    int length = context.original().size();

    @SuppressWarnings("unchecked")
    CompletableFuture<Integer>[] futures = (CompletableFuture<Integer>[]) cache.asMap().values()
        .toArray(new CompletableFuture<?>[length]);
    List<Integer> values = Arrays.stream(futures).map(CompletableFuture::join).collect(toList());
    assertThat(values.containsAll(context.original().values()), is(true));
    assertThat(futures.length, is(length));

    Object[] array = cache.asMap().values().toArray();
    assertThat(array.length, is(length));
    for (Object item : array) {
      @SuppressWarnings("unchecked")
      CompletableFuture<Integer> future = (CompletableFuture<Integer>) item;
      assertThat(context.original().values(), hasItem(future.join()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_empty(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().values(), is(deeplyEmpty()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void values_addNotSupported(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().add(CompletableFuture.completedFuture(1));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_clear(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void values_removeIf_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().removeIf(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Predicate<CompletableFuture<Integer>> isEven = value -> (value.join() % 2) == 0;
    boolean hasEven = cache.asMap().values().stream().anyMatch(isEven);

    boolean removedIfEven = cache.asMap().values().removeIf(isEven);
    assertThat(cache.asMap().values().stream().anyMatch(isEven), is(false));
    assertThat(removedIfEven, is(hasEven));
    if (removedIfEven) {
      assertThat(cache.asMap().size(), is(lessThan(context.original().size())));
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Collection<CompletableFuture<Integer>> values = cache.asMap().values();
    assertThat(values.contains(new Object()), is(false));
    assertThat(values.remove(new Object()), is(false));
    assertThat(values, hasSize(context.original().size()));
    for (CompletableFuture<Integer> value : ImmutableList.copyOf(values)) {
      assertThat(values.contains(value), is(true));
      assertThat(values.remove(value), is(true));
      assertThat(values.remove(value), is(false));
      assertThat(values.contains(value), is(false));
    }
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueIterator(AsyncCache<Integer, Integer> cache, CacheContext context) {
    int iterations = 0;
    for (Iterator<CompletableFuture<Integer>> i = cache.asMap().values().iterator(); i.hasNext();) {
      assertThat(cache.asMap().containsValue(i.next()), is(true));
      iterations++;
      i.remove();
    }
    int count = iterations;
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
    assertThat(iterations, is(context.original().size()));
    assertThat(cache.asMap(), is(emptyMap()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void valueIterator_noElement(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void valueIterator_noMoreElements(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valueSpliterator_forEachRemaining_null(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_forEachRemaining(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    int[] count = new int[1];
    cache.asMap().values().spliterator().forEachRemaining(value -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valueSpliterator_tryAdvance_null(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().values().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_tryAdvance(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<CompletableFuture<Integer>> spliterator = cache.asMap().values().spliterator();
    int[] count = new int[1];
    boolean advanced;
    do {
      advanced = spliterator.tryAdvance(value -> count[0]++);
    } while (advanced);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_trySplit(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<CompletableFuture<Integer>> spliterator = cache.asMap().values().spliterator();
    Spliterator<CompletableFuture<Integer>> other = MoreObjects.firstNonNull(
        spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(value -> count[0]++);
    other.forEachRemaining(value -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_estimateSize(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<CompletableFuture<Integer>> spliterator = cache.asMap().values().spliterator();
    assertThat((int) spliterator.estimateSize(), is(cache.asMap().size()));
  }

  /* ---------------- Entry Set -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySetToArray_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().toArray((Map.Entry<?, ?>[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entriesToArray(AsyncCache<Integer, Integer> cache, CacheContext context) {
    int length = context.original().size();

    @SuppressWarnings("unchecked")
    Map.Entry<Integer, CompletableFuture<Integer>>[] entries =
        (Map.Entry<Integer, CompletableFuture<Integer>>[])
        cache.asMap().entrySet().toArray(new Map.Entry<?, ?>[length]);
    assertThat(entries.length, is(length));
    for (Map.Entry<Integer, CompletableFuture<Integer>> entry : entries) {
      assertThat(context.original(), hasEntry(entry.getKey(), entry.getValue().join()));
    }

    Object[] array = cache.asMap().entrySet().toArray();
    assertThat(array.length, is(length));
    for (Object item : array) {
      @SuppressWarnings("unchecked")
      Map.Entry<Integer, CompletableFuture<Integer>> entry =
          (Map.Entry<Integer, CompletableFuture<Integer>>) item;
      assertThat(context.original(), hasEntry(entry.getKey(), entry.getValue().join()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_empty(AsyncCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet(), is(deeplyEmpty()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.DEFAULT)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void entrySet_addIsNotSupported(AsyncCache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.asMap().entrySet().add(immutableEntry(1, CompletableFuture.completedFuture(2)));
    } finally {
      assertThat(cache.asMap().size(), is(0));
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_clear(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySet_removeIf_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().removeIf(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeIf(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Predicate<Map.Entry<Integer, CompletableFuture<Integer>>> isEven =
        entry -> (entry.getValue().join() % 2) == 0;
    boolean hasEven = cache.asMap().entrySet().stream().anyMatch(isEven);

    boolean removedIfEven = cache.asMap().entrySet().removeIf(isEven);
    assertThat(cache.asMap().entrySet().stream().anyMatch(isEven), is(false));
    assertThat(removedIfEven, is(hasEven));
    if (removedIfEven) {
      assertThat(cache.asMap().size(), is(lessThan(context.original().size())));
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Set<Map.Entry<Integer, CompletableFuture<Integer>>> entries = cache.asMap().entrySet();
    assertThat(entries.contains(new Object()), is(false));
    assertThat(entries.remove(new Object()), is(false));
    assertThat(entries, hasSize(context.original().size()));
    entries.forEach(entry -> {
      assertThat(entries.contains(entry), is(true));
      assertThat(entries.remove(entry), is(true));
      assertThat(entries.remove(entry), is(false));
      assertThat(entries.contains(entry), is(false));
    });
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entryIterator(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Iterator<Map.Entry<Integer, CompletableFuture<Integer>>> i =
        cache.asMap().entrySet().iterator();
    int iterations = 0;
    while (i.hasNext()) {
      Map.Entry<Integer, CompletableFuture<Integer>> entry = i.next();
      assertThat(cache.asMap(), hasEntry(entry.getKey(), entry.getValue()));
      iterations++;
      i.remove();
    }
    int count = iterations;
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
    assertThat(iterations, is(context.original().size()));
    assertThat(cache.asMap(), is(emptyMap()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void entryIterator_noElement(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void entryIterator_noMoreElements(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySpliterator_forEachRemaining_null(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_forEachRemaining(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    int[] count = new int[1];
    cache.asMap().entrySet().spliterator().forEachRemaining(entry -> {
      if (context.isCaffeine()) {
        assertThat(entry, is(instanceOf(WriteThroughEntry.class)));
      }
      count[0]++;
      assertThat(context.original(), hasEntry(entry.getKey(), entry.getValue().join()));
    });
    assertThat(count[0], is(context.original().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySpliterator_tryAdvance_null(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_tryAdvance(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<Map.Entry<Integer, CompletableFuture<Integer>>> spliterator =
        cache.asMap().entrySet().spliterator();
    int[] count = new int[1];
    boolean advanced;
    do {
      advanced = spliterator.tryAdvance(entry -> {
        if (context.isCaffeine()) {
          assertThat(entry, is(instanceOf(WriteThroughEntry.class)));
        }
        count[0]++;
      });
    } while (advanced);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_trySplit(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<Map.Entry<Integer, CompletableFuture<Integer>>> spliterator =
        cache.asMap().entrySet().spliterator();
    Spliterator<Map.Entry<Integer, CompletableFuture<Integer>>> other = MoreObjects.firstNonNull(
        spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(entry -> count[0]++);
    other.forEachRemaining(entry -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_estimateSize(
      AsyncCache<Integer, Integer> cache, CacheContext context) {
    Spliterator<Map.Entry<Integer, CompletableFuture<Integer>>> spliterator =
        cache.asMap().entrySet().spliterator();
    assertThat((int) spliterator.estimateSize(), is(cache.asMap().size()));
  }

  /* ---------------- WriteThroughEntry -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry(AsyncCache<Integer, Integer> cache, CacheContext context) {
    Map.Entry<Integer, CompletableFuture<Integer>> entry =
        cache.asMap().entrySet().iterator().next();
    CompletableFuture<Integer> value = CompletableFuture.completedFuture(3);

    entry.setValue(value);
    assertThat(cache.asMap().get(entry.getKey()), is(value));
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_null(AsyncCache<Integer, Integer> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().next().setValue(null);
  }

  // writeThroughEntry_serialize() - CompletableFuture is not serializable
}
