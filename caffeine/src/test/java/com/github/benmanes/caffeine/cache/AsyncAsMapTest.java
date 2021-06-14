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
import static com.github.benmanes.caffeine.testing.IsInt.isInt;
import static com.google.common.base.MoreObjects.firstNonNull;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
import com.github.benmanes.caffeine.testing.Int;

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
  public void isEmpty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().isEmpty(), is(context.original().isEmpty()));
    if (cache.asMap().isEmpty()) {
      assertThat(cache.asMap(), is(emptyMap()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void clear(AsyncCache<Int, Int> cache, CacheContext context) {
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
  public void containsKey_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().containsKey(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().containsKey(key), is(true));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().containsKey(context.absentKey()), is(false));
  }

  @CheckNoStats
  @SuppressWarnings("ReturnValueIgnored")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsValue_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().containsValue(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().containsValue(cache.asMap().get(key)), is(true));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().containsValue(context.absentValue().asFuture()), is(false));
  }

  /* ---------------- get -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().get(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().get(context.absentKey()), is(nullValue()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().get(key).join(), is(context.original().get(key)));
    }
  }

  /* ---------------- getOrDefault -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getOrDefault_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().getOrDefault(null, Int.valueOf(1).asFuture());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().getOrDefault(context.absentKey(), null), is(nullValue()));
    assertThat(cache.asMap().getOrDefault(context.absentKey(), value), is(value));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_present(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().getOrDefault(key, value).join(), is(context.original().get(key)));
    }
  }

  /* ---------------- forEach -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void forEach_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().forEach(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_scan(AsyncCache<Int, Int> cache, CacheContext context) {
    var remaining = new HashMap<>(context.original());
    cache.asMap().forEach((k, v) -> remaining.remove(k, v.join()));
    assertThat(remaining, is(emptyMap()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_modify(AsyncCache<Int, Int> cache, CacheContext context) {
    // non-deterministic traversal behavior with modifications, but shouldn't become corrupted
    @SuppressWarnings("ModifiedButNotUsed")
    var modified = new ArrayList<Int>();
    cache.asMap().forEach((key, value) -> {
      Int newKey = context.lastKey().add(key);
      modified.add(newKey); // for weak keys
      cache.synchronous().put(newKey, key);
    });
  }

  /* ---------------- put -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().put(null, Int.valueOf(1).asFuture());
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().put(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().put(null, null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().put(context.absentKey(), value), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(value));
    assertThat(cache.asMap().size(), is(context.original().size() + 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().put(key, value), is(value));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var newValue = context.absentValue().asFuture();
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
  public void putAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().putAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().putAll(Collections.emptyMap());
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    int startKey = context.original().size() + 1;
    var entries = IntStream
        .range(startKey, 100 + startKey)
        .mapToObj(Int::valueOf)
        .collect(toMap(identity(), key -> key.negate().asFuture()));
    cache.asMap().putAll(entries);
    assertThat(cache.asMap().size(), is(100 + context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = context.original().keySet().stream().collect(toMap(identity(), Int::asFuture));
    cache.asMap().putAll(entries);
    assertThat(cache.asMap(), is(equalTo(entries)));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(entries.size(), RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_mixed(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = new HashMap<Int, CompletableFuture<Int>>();
    var replaced = new ArrayList<Int>();
    context.original().forEach((key, value) -> {
      if ((key.intValue() % 2) == 0) {
        replaced.add(key);
        entries.put(key, value.add(1).asFuture());
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
  public void putIfAbsent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().putIfAbsent(null, Int.valueOf(2).asFuture());
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().putIfAbsent(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().putIfAbsent(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
  removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().putIfAbsent(key, key.asFuture()), is(value));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().putIfAbsent(context.absentKey(), value), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(value));
    assertThat(cache.asMap().size(), is(context.original().size() + 1));
  }

  /* ---------------- remove -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().remove(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().remove(context.absentKey()), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void remove_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      cache.asMap().remove(key);
    }
    assertThat(cache.asMap().size(),
        is(context.original().size() - context.firstMiddleLastKeys().size()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  /* ---------------- remove conditionally -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().remove(null, context.absentValue().asFuture());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().remove(context.absentKey(), null), is(false)); // see ConcurrentHashMap
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKeyAndValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().remove(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().remove(context.absentKey(), value), is(false));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_presentKey(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().remove(key, key.asFuture()), is(false));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void removeConditionally_presentKeyAndValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
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
  public void replace_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replace(null, Int.valueOf(1).asFuture());
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replace(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replace(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().replace(context.absentKey(),
        context.absentValue().asFuture()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value), is(value));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
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
  public void replaceConditionally_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.valueOf(1).asFuture();
    cache.asMap().replace(null, value, value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.valueOf(1).asFuture();
    cache.asMap().replace(Int.valueOf(1), null, value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullNewValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.futureOf(1);
    cache.asMap().replace(Int.valueOf(1), value, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndOldValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.futureOf(1);
    cache.asMap().replace(null, null, value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndNewValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.futureOf(1);
    cache.asMap().replace(null, value, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldAndNewValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replace(Int.valueOf(1), null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndValues(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replace(null, null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().replace(context.absentKey(), value, value), is(false));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_wrongOldValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      assertThat(cache.asMap().replace(key, value, value), is(false));
      assertThat(cache.asMap().get(key), is(oldValue));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value, value), is(true));
      assertThat(cache.asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_differentValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
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
  public void replaceAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replaceAll(null);
  }

  @CheckNoStats
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> value);
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> key.asFuture());
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
  public void computeIfAbsent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().computeIfAbsent(null, key -> key.negate().asFuture());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullMappingFunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().computeIfAbsent(context.absentKey(), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> null), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine)
  public void computeIfAbsent_recursive(AsyncCache<Int, Int> cache, CacheContext context) {
    var mappingFunction = new Function<Int, CompletableFuture<Int>>() {
      @Override public CompletableFuture<Int> apply(Int key) {
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
  public void computeIfAbsent_pingpong(AsyncCache<Int, Int> cache, CacheContext context) {
    var mappingFunction = new Function<Int, CompletableFuture<Int>>() {
      @Override public CompletableFuture<Int> apply(Int key) {
        return cache.asMap().computeIfAbsent(key.negate(), this);
      }
    };
    try {
      cache.asMap().computeIfAbsent(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_error(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.asMap().computeIfAbsent(context.absentKey(),
          key -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException expected) {}

    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), Int::asFuture).join(),
        is(context.absentKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
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
  public void computeIfAbsent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> value), is(value));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(1).failures(0));

    assertThat(cache.asMap().get(context.absentKey()), is(value));
    assertThat(cache.asMap().size(), is(1 + context.original().size()));
  }

  /* ---------------- computeIfPresent -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().computeIfPresent(null, (key, value) -> key.negate().asFuture());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullMappingFunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().computeIfPresent(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      cache.asMap().computeIfPresent(key, (k, v) -> null);
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void computeIfPresent_recursive(AsyncCache<Int, Int> cache, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    var mappingFunction = new BiFunction<Int, CompletableFuture<Int>, CompletableFuture<Int>>() {
      boolean recursed;

      @Override public CompletableFuture<Int> apply(Int key, CompletableFuture<Int> value) {
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
  public void computeIfPresent_pingpong(AsyncCache<Int, Int> cache, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    var mappingFunction = new BiFunction<Int, CompletableFuture<Int>, CompletableFuture<Int>>() {
      int recursed;

      @Override public CompletableFuture<Int> apply(Int key, CompletableFuture<Int> value) {
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
  public void computeIfPresent_error(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.asMap().computeIfPresent(context.firstKey(),
          (key, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException expected) {}
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));

    assertThat(cache.asMap().computeIfPresent(context.firstKey(),
        (k, v) -> k.negate().asFuture()).join(), is(context.firstKey().negate()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfPresent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfPresent(
        context.absentKey(), (key, value) -> value), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = key.asFuture();
      assertThat(cache.asMap().computeIfPresent(key, (k, v) -> value), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.synchronous().asMap().get(key), is(key));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- compute -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().compute(null, (key, value) -> key.negate().asFuture());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullMappingFunction(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().compute(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().compute(key, (k, v) -> null), is(nullValue()));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine)
  public void compute_recursive(AsyncCache<Int, Int> cache, CacheContext context) {
    var mappingFunction = new BiFunction<Int, CompletableFuture<Int>, CompletableFuture<Int>>() {
      @Override public CompletableFuture<Int> apply(Int key, CompletableFuture<Int> value) {
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
  public void compute_pingpong(AsyncCache<Int, Int> cache, CacheContext context) {
    var key1 = Int.valueOf(1);
    var key2 = Int.valueOf(2);
    var mappingFunction = new BiFunction<Int, CompletableFuture<Int>, CompletableFuture<Int>>() {
      @Override public CompletableFuture<Int> apply(Int key, CompletableFuture<Int> value) {
        return cache.asMap().compute(key.equals(key1) ? key2 : key1, this);
      }
    };
    try {
      cache.asMap().compute(key1, mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_error(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.asMap().compute(context.absentKey(),
          (key, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException expected) {}
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));

    var future = context.absentKey().negate().asFuture();
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> future), is(future));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().compute(context.absentKey(), (key, value) -> null), is(nullValue()));
    assertThat(cache.asMap().get(context.absentKey()), is(nullValue()));
    assertThat(cache.asMap().size(), is(context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> value), is(value));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(1).failures(0));

    assertThat(cache.asMap().size(), is(1 + context.original().size()));
    assertThat(cache.asMap().get(context.absentKey()), is(value));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().compute(key, (k, v) -> v), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(cache.synchronous().asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = key.asFuture();
      assertThat(cache.asMap().compute(key, (k, v) -> value), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.synchronous().asMap().get(key), is(key));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- merge -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().merge(null, Int.valueOf(-1).asFuture(), (oldValue, value) -> value);
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().merge(Int.valueOf(1), null, (oldValue, value) -> null);
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullMappingFunction(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().merge(Int.valueOf(1), Int.valueOf(1).asFuture(), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().merge(key, value, (oldValue, v) -> null), is(nullValue()));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.asMap().size(), is(context.original().size() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches")
  public void merge_recursive(AsyncCache<Int, Int> cache, CacheContext context) {
    var mappingFunction =
        new BiFunction<CompletableFuture<Int>, CompletableFuture<Int>, CompletableFuture<Int>>() {
          @Override public CompletableFuture<Int> apply(
              CompletableFuture<Int> oldValue, CompletableFuture<Int> value) {
            return cache.asMap().merge(context.absentKey(), oldValue, this);
          }
        };
    var firstValue = cache.asMap().get(context.firstKey());
    var value = cache.asMap().merge(context.absentKey(), firstValue, mappingFunction);
    assertThat(value, is(firstValue));
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void merge_pingpong(AsyncCache<Int, Int> cache, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    var mappingFunction =
        new BiFunction<CompletableFuture<Int>, CompletableFuture<Int>, CompletableFuture<Int>>() {
          int recursed;

          @Override public CompletableFuture<Int> apply(
              CompletableFuture<Int> oldValue, CompletableFuture<Int> value) {
            if (++recursed == 2) {
              throw new StackOverflowError();
            }
            var lastValue = cache.asMap().get(context.lastKey());
            return cache.asMap().merge(context.lastKey(), lastValue, this);
          }
        };
    cache.asMap().merge(context.firstKey(), cache.asMap().get(context.firstKey()), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_error(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.asMap().merge(context.firstKey(), cache.asMap().get(context.firstKey()),
          (oldValue, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException expected) {}
    assertThat(cache.synchronous().asMap(), is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(0).failures(0));

    assertThat(cache.asMap().merge(context.firstKey(), cache.asMap().get(context.firstKey()),
        (oldValue, value) -> context.absentValue().asFuture()).join(),
        is(context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var absent = context.absentValue().asFuture();
    var result = cache.asMap().merge(context.absentKey(), absent, (oldValue, value) -> value);
    assertThat(result, is(absent));
    assertThat(cache.asMap().get(context.absentKey()), is(absent));
    assertThat(cache.asMap().size(), is(1 + context.original().size()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().merge(key, key.negate().asFuture(),
          (oldValue, v) -> oldValue), is(value));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(cache.synchronous().asMap().get(key), is(value));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    assertThat(context.removalNotifications(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().merge(key, key.asFuture(),
          (oldValue, v) -> oldValue.join().add(v.join()).asFuture()).join(), isInt(0));
    }
    int count = context.firstMiddleLastKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(0).success(count).failures(0));

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.synchronous().asMap().get(key), isInt(0));
    }
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* ---------------- equals / hashCode -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().equals(null), is(false));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @SuppressWarnings("SelfEquals")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().equals(cache.asMap()), is(true));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals(AsyncCache<Int, Int> cache, CacheContext context) {
    var map = Map.copyOf(cache.asMap());
    assertThat(cache.asMap().equals(map), is(true));
    assertThat(map.equals(cache.asMap()), is(true));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode(), is(Map.copyOf(cache.asMap()).hashCode()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode(), is(equalTo(cache.asMap().hashCode())));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var other = Stream.of(1, 2, 3).collect(toMap(Int::valueOf, key -> Int.futureOf(-key)));
    assertThat(cache.asMap().equals(other), is(false));
    assertThat(other.equals(cache.asMap()), is(false));
    assertThat(cache.asMap().hashCode(), is(not(equalTo(other.hashCode()))));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var other = Stream.of(1, 2, 3).collect(toMap(Int::valueOf, key -> Int.futureOf(-key)));
    assertThat(cache.asMap().equals(other), is(false));
    assertThat(other.equals(cache.asMap()), is(false));
    assertThat(cache.asMap().hashCode(), is(not(equalTo(other.hashCode()))));

    var empty = Map.of();
    assertThat(cache.asMap().equals(empty), is(false));
    assertThat(empty.equals(cache.asMap()), is(false));
    assertThat(cache.asMap().hashCode(), is(not(equalTo(empty.hashCode()))));
  }

  /* ---------------- toString -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void toString(AsyncCache<Int, Int> cache, CacheContext context) {
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
  public void keySetToArray_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().toArray((Int[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySetToArray(AsyncCache<Int, Int> cache, CacheContext context) {
    int length = context.original().size();

    var ints = cache.asMap().keySet().toArray(new Int[length]);
    assertThat(ints.length, is(length));
    assertThat(Arrays.asList(ints).containsAll(context.original().keySet()), is(true));

    var array = cache.asMap().keySet().toArray();
    assertThat(array.length, is(length));
    assertThat(Arrays.asList(array).containsAll(context.original().keySet()), is(true));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_whenEmpty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet(), is(deeplyEmpty()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void keySet_addNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().add(Int.valueOf(1));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = cache.asMap().keySet();
    assertThat(keys.contains(new Object()), is(false));
    assertThat(keys.remove(new Object()), is(false));
    assertThat(keys, hasSize(context.original().size()));
    for (Int key : Set.copyOf(keys)) {
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
  public void keySet_iterator(AsyncCache<Int, Int> cache, CacheContext context) {
    int iterations = 0;
    for (var i = cache.asMap().keySet().iterator(); i.hasNext();) {
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
  public void keyIterator_noElement(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void keyIterator_noMoreElements(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySpliterator_forEachRemaining_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_forEachRemaining(AsyncCache<Int, Int> cache, CacheContext context) {
    int[] count = new int[1];
    cache.asMap().keySet().spliterator().forEachRemaining(key -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySpliterator_tryAdvance_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_tryAdvance(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().keySet().spliterator();
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
  public void keySpliterator_trySplit(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().keySet().spliterator();
    var other = firstNonNull(spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(key -> count[0]++);
    other.forEachRemaining(key -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_estimateSize(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().keySet().spliterator();
    assertThat((int) spliterator.estimateSize(), is(cache.asMap().size()));
  }

  /* ---------------- Values -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valuesToArray_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().toArray((CompletableFuture<Int>[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void valuesToArray(AsyncCache<Int, Int> cache, CacheContext context) {
    int length = context.original().size();

    @SuppressWarnings("unchecked")
    var futures = (CompletableFuture<Int>[]) cache.asMap().values()
        .toArray(new CompletableFuture<?>[length]);
    var values = Stream.of(futures).map(CompletableFuture::join).collect(toList());
    assertThat(values.containsAll(context.original().values()), is(true));
    assertThat(futures.length, is(length));

    var array = cache.asMap().values().toArray();
    assertThat(array.length, is(length));
    for (Object item : array) {
      @SuppressWarnings("unchecked")
      var future = (CompletableFuture<Int>) item;
      assertThat(context.original().values(), hasItem(future.join()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values(), is(deeplyEmpty()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void values_addNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().add(Int.valueOf(1).asFuture());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void values_removeIf_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().removeIf(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf(AsyncCache<Int, Int> cache, CacheContext context) {
    Predicate<CompletableFuture<Int>> isEven = value -> (value.join().intValue() % 2) == 0;
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
  public void values(AsyncCache<Int, Int> cache, CacheContext context) {
    var values = cache.asMap().values();
    assertThat(values.contains(new Object()), is(false));
    assertThat(values.remove(new Object()), is(false));
    assertThat(values, hasSize(context.original().size()));
    for (var value : List.copyOf(values)) {
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
  public void valueIterator(AsyncCache<Int, Int> cache, CacheContext context) {
    int iterations = 0;
    for (var i = cache.asMap().values().iterator(); i.hasNext();) {
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
  public void valueIterator_noElement(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void valueIterator_noMoreElements(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valueSpliterator_forEachRemaining_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_forEachRemaining(AsyncCache<Int, Int> cache, CacheContext context) {
    int[] count = new int[1];
    cache.asMap().values().spliterator().forEachRemaining(value -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valueSpliterator_tryAdvance_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_tryAdvance(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().values().spliterator();
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
  public void valueSpliterator_trySplit(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().values().spliterator();
    var other = firstNonNull(spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(value -> count[0]++);
    other.forEachRemaining(value -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_estimateSize(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().values().spliterator();
    assertThat((int) spliterator.estimateSize(), is(cache.asMap().size()));
  }

  /* ---------------- Entry Set -------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySetToArray_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().toArray((Map.Entry<?, ?>[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entriesToArray(AsyncCache<Int, Int> cache, CacheContext context) {
    int length = context.original().size();

    @SuppressWarnings("unchecked")
    var entries = (Map.Entry<Int, CompletableFuture<Int>>[])
        cache.asMap().entrySet().toArray(new Map.Entry<?, ?>[length]);
    assertThat(entries.length, is(length));
    for (var entry : entries) {
      assertThat(context.original(), hasEntry(entry.getKey(), entry.getValue().join()));
    }

    Object[] array = cache.asMap().entrySet().toArray();
    assertThat(array.length, is(length));
    for (Object item : array) {
      @SuppressWarnings("unchecked")
      var entry = (Map.Entry<Int, CompletableFuture<Int>>) item;
      assertThat(context.original(), hasEntry(entry.getKey(), entry.getValue().join()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet(), is(deeplyEmpty()));
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.DEFAULT)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void entrySet_addIsNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.asMap().entrySet().add(immutableEntry(Int.valueOf(1), Int.valueOf(2).asFuture()));
    } finally {
      assertThat(cache.asMap().size(), is(0));
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().clear();
    assertThat(cache.asMap(), is(emptyMap()));
    int count = context.original().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySet_removeIf_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().removeIf(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeIf(AsyncCache<Int, Int> cache, CacheContext context) {
    Predicate<Map.Entry<Int, CompletableFuture<Int>>> isEven =
        entry -> (entry.getValue().join().intValue() % 2) == 0;
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
  public void entrySet(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = cache.asMap().entrySet();
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
  public void entryIterator(AsyncCache<Int, Int> cache, CacheContext context) {
    var i = cache.asMap().entrySet().iterator();
    int iterations = 0;
    while (i.hasNext()) {
      var entry = i.next();
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
  public void entryIterator_noElement(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void entryIterator_noMoreElements(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySpliterator_forEachRemaining_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_forEachRemaining(AsyncCache<Int, Int> cache, CacheContext context) {
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
  public void entrySpliterator_tryAdvance_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_tryAdvance(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().entrySet().spliterator();
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
  public void entrySpliterator_trySplit(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().entrySet().spliterator();
    var other = firstNonNull(spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(entry -> count[0]++);
    other.forEachRemaining(entry -> count[0]++);
    assertThat(count[0], is(cache.asMap().size()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_estimateSize(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().entrySet().spliterator();
    assertThat((int) spliterator.estimateSize(), is(cache.asMap().size()));
  }

  /* ---------------- WriteThroughEntry -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = cache.asMap().entrySet().iterator().next();
    var value = Int.valueOf(3).asFuture();

    entry.setValue(value);
    assertThat(cache.asMap().get(entry.getKey()), is(value));
    assertThat(cache.asMap().size(), is(context.original().size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.REPLACED));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().next().setValue(null);
  }

  // writeThroughEntry_serialize() - CompletableFuture is not serializable
}
