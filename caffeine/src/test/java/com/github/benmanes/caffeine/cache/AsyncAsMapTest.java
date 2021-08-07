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
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Maps.immutableEntry;
import static com.google.common.truth.Truth.assertThat;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
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
    assertThat(cache.asMap().isEmpty()).isEqualTo(context.original().isEmpty());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().size()).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().clear();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
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
      assertThat(cache.asMap().containsKey(key)).isTrue();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().containsKey(context.absentKey())).isFalse();
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
      assertThat(cache.asMap().containsValue(cache.asMap().get(key))).isTrue();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().containsValue(context.absentValue().asFuture())).isFalse();
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
    assertThat(cache.asMap().get(context.absentKey())).isNull();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().get(key)).succeedsWith(context.original().get(key));
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
    assertThat(cache.asMap().getOrDefault(context.absentKey(), null)).isNull();
    assertThat(cache.asMap().getOrDefault(context.absentKey(), value)).isEqualTo(value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_present(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().getOrDefault(key, value)).succeedsWith(context.original().get(key));
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
    assertThat(remaining).isExhaustivelyEmpty();
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
    assertThat(cache.asMap().put(context.absentKey(), value)).isNull();
    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(context.initialSize() + 1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().put(key, value)).isEqualTo(value);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void put_replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var newValue = context.absentValue().asFuture();
      assertThat(cache.asMap().put(key, newValue)).succeedsWith(context.original().get(key));
      assertThat(cache).containsEntry(key, newValue);
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .hasSize(count).exclusively();
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
    cache.asMap().putAll(Map.of());
    assertThat(cache).hasSize(context.initialSize());
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
    assertThat(cache).hasSize(100 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = context.original().keySet().stream().collect(toMap(identity(), Int::asFuture));
    cache.asMap().putAll(entries);
    assertThat(cache).containsExactlyEntriesIn(entries);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .hasSize(entries.size()).exclusively();
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
    assertThat(cache).containsExactlyEntriesIn(entries);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .hasSize(replaced.size()).exclusively();
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
      assertThat(cache.asMap().putIfAbsent(key, key.asFuture())).isEqualTo(value);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().putIfAbsent(context.absentKey(), value)).isNull();
    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(context.initialSize() + 1);
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
    assertThat(cache.asMap().remove(context.absentKey())).isNull();
    assertThat(cache).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void remove_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      cache.asMap().remove(key);
    }
    assertThat(cache).hasSize(context.initialSize() - context.firstMiddleLastKeys().size());

    int count = context.firstMiddleLastKeys().size();
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
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
    assertThat(cache.asMap().remove(context.absentKey(), null)).isFalse(); // see ConcurrentHashMap
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
    assertThat(cache.asMap().remove(context.absentKey(), value)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_presentKey(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().remove(key, key.asFuture())).isFalse();
    }
    assertThat(cache).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void removeConditionally_presentKeyAndValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().remove(key, value)).isTrue();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
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
    var result = cache.asMap().replace(context.absentKey(), context.absentValue().asFuture());
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value)).isEqualTo(value);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      assertThat(cache.asMap().replace(key, value)).isEqualTo(oldValue);
      assertThat(cache).containsEntry(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
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
    assertThat(cache.asMap().replace(context.absentKey(), value, value)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_wrongOldValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      assertThat(cache.asMap().replace(key, value, value)).isFalse();
      assertThat(cache).containsEntry(key, oldValue);
    }
    assertThat(cache).hasSize(context.initialSize());

    int count = context.firstMiddleLastKeys().size();
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value, value)).isTrue();
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_differentValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      assertThat(cache.asMap().replace(key, oldValue, value)).isTrue();
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());

    int count = context.firstMiddleLastKeys().size();
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
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
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void replaceAll_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replaceAll((key, value) -> key.asFuture());
    cache.asMap().forEach((key, value) -> {
      assertThat(value).succeedsWith(key);
    });
    assertThat(context).removalNotifications().withCause(REPLACED)
        .hasSize(context.initialSize()).exclusively();
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
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> null)).isNull();
    assertThat(cache).hasSize(context.initialSize());
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

    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);
    var future = cache.asMap().computeIfAbsent(context.absentKey(), Int::asFuture);
    assertThat(future).succeedsWith(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var result = cache.asMap().computeIfAbsent(key, k -> { throw new AssertionError(); });
      assertThat(result).isNotNull();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).stats().hits(count).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(stats = CacheSpec.Stats.ENABLED,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> value)).isEqualTo(value);
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);

    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(1 + context.original().size());
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
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(count).exclusively();
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
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);

    var future = cache.asMap().computeIfPresent(
        context.firstKey(), (k, v) -> k.negate().asFuture());
    assertThat(future).succeedsWith(context.firstKey().negate());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfPresent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfPresent(context.absentKey(), (key, value) -> value)).isNull();
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = key.asFuture();
      assertThat(cache.asMap().computeIfPresent(key, (k, v) -> value)).isEqualTo(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, key);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
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
      assertThat(cache.asMap().compute(key, (k, v) -> null)).isNull();
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
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
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);

    var future = context.absentKey().negate().asFuture();
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> future)).isEqualTo(future);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().compute(context.absentKey(), (key, value) -> null)).isNull();
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> value)).isEqualTo(value);
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(1 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().compute(key, (k, v) -> v)).isEqualTo(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = key.asFuture();
      assertThat(cache.asMap().compute(key, (k, v) -> value)).isEqualTo(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, key);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
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
      assertThat(cache.asMap().merge(key, value, (oldValue, v) -> null)).isNull();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
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
    assertThat(value).isEqualTo(firstValue);
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
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);

    var result = cache.asMap().merge(context.firstKey(), cache.asMap().get(context.firstKey()),
        (oldValue, value) -> context.absentValue().asFuture());
    assertThat(result).succeedsWith(context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var absent = context.absentValue().asFuture();
    var result = cache.asMap().merge(context.absentKey(), absent, (oldValue, value) -> value);
    assertThat(result).isEqualTo(absent);
    assertThat(cache).containsEntry(context.absentKey(), absent);
    assertThat(cache).hasSize(1 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      var result = cache.asMap().merge(key, key.negate().asFuture(), (oldValue, v) -> oldValue);
      assertThat(result).isEqualTo(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    Int mergedValue = context.absentValue();
    for (Int key : context.firstMiddleLastKeys()) {
      var result = cache.asMap().merge(key, key.asFuture(),
          (oldValue, v) -> mergedValue.asFuture());
      assertThat(result).succeedsWith(mergedValue);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, mergedValue);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  /* ---------------- equals / hashCode -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().equals(null)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @SuppressWarnings("SelfEquals")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().equals(cache.asMap())).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals(AsyncCache<Int, Int> cache, CacheContext context) {
    var map = Map.copyOf(cache.asMap());
    assertThat(cache.asMap().equals(map)).isTrue();
    assertThat(map.equals(cache.asMap())).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode()).isEqualTo(Map.copyOf(cache.asMap()).hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode()).isEqualTo(cache.asMap().hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var other = Stream.of(1, 2, 3).collect(toMap(Int::valueOf, key -> Int.futureOf(-key)));
    assertThat(cache.asMap().equals(other)).isFalse();
    assertThat(other.equals(cache.asMap())).isFalse();
    assertThat(cache.asMap().hashCode()).isNotEqualTo(other.hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var other = Stream.of(1, 2, 3).collect(toMap(Int::valueOf, key -> Int.futureOf(-key)));
    assertThat(cache.asMap().equals(other)).isFalse();
    assertThat(other.equals(cache.asMap())).isFalse();
    assertThat(cache.asMap().hashCode()).isNotEqualTo(other.hashCode());

    var empty = Map.of();
    assertThat(cache.asMap().equals(empty)).isFalse();
    assertThat(empty.equals(cache.asMap())).isFalse();
    assertThat(cache.asMap().hashCode()).isNotEqualTo(empty.hashCode());
  }

  /* ---------------- toString -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void toString(AsyncCache<Int, Int> cache, CacheContext context) {
    String toString = cache.asMap().toString();
    if (!context.original().toString().equals(toString)) {
      cache.asMap().forEach((key, value) -> {
        assertThat(toString).contains(key + "=" + value);
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
    var ints = cache.asMap().keySet().toArray(new Int[0]);
    assertThat(ints).asList().containsExactlyElementsIn(context.original().keySet());

    var array = cache.asMap().keySet().toArray();
    assertThat(array).asList().containsExactlyElementsIn(context.original().keySet());

    var func = cache.asMap().keySet().toArray(Int[]::new);
    assertThat(func).asList().containsExactlyElementsIn(context.original().keySet());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_whenEmpty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet()).isExhaustivelyEmpty();
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
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = cache.asMap().keySet();
    assertThat(keys).doesNotContain(new Object());
    assertThat(keys.remove(new Object())).isFalse();
    assertThat(keys).hasSize(context.initialSize());
    for (Int key : Set.copyOf(keys)) {
      assertThat(keys).contains(key);
      assertThat(keys.remove(key)).isTrue();
      assertThat(keys.remove(key)).isFalse();
      assertThat(keys).doesNotContain(key);
    }
    assertThat(cache).isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_iterator(AsyncCache<Int, Int> cache, CacheContext context) {
    int count = 0;
    for (var i = cache.asMap().keySet().iterator(); i.hasNext();) {
      assertThat(cache).containsKey(i.next());
      count++;
      i.remove();
    }
    assertThat(cache).isEmpty();
    assertThat(count).isEqualTo(context.initialSize());
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
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
    assertThat(count[0]).isEqualTo(context.initialSize());
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
    assertThat(count[0]).isEqualTo(context.initialSize());
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
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_estimateSize(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().keySet().spliterator();
    assertThat(spliterator.estimateSize()).isEqualTo(context.initialSize());
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
    var futures = cache.asMap().values().toArray(new CompletableFuture<?>[0]);
    var values1 = Stream.of(futures).map(CompletableFuture::join).collect(toList());
    assertThat(values1).containsExactlyElementsIn(context.original().values());

    var array = cache.asMap().values().toArray(new CompletableFuture<?>[0]);
    var values2 = Stream.of(array).map(CompletableFuture::join).collect(toList());
    assertThat(values2).containsExactlyElementsIn(context.original().values());

    var func = cache.asMap().values().toArray(CompletableFuture<?>[]::new);
    var values3 = Stream.of(func).map(CompletableFuture::join).collect(toList());
    assertThat(values3).containsExactlyElementsIn(context.original().values());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values()).isExhaustivelyEmpty();
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
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
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
    assertThat(cache.asMap().values().stream().anyMatch(isEven)).isFalse();
    assertThat(removedIfEven).isEqualTo(hasEven);
    if (removedIfEven) {
      assertThat(cache).hasSizeLessThan(context.initialSize());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values(AsyncCache<Int, Int> cache, CacheContext context) {
    var values = cache.asMap().values();
    assertThat(values).doesNotContain(new Object());
    assertThat(values.remove(new Object())).isFalse();
    assertThat(values).hasSize(context.initialSize());
    for (var value : List.copyOf(values)) {
      assertThat(values).contains(value);
      assertThat(values.remove(value)).isTrue();
      assertThat(values.remove(value)).isFalse();
      assertThat(values).doesNotContain(value);
    }
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueIterator(AsyncCache<Int, Int> cache, CacheContext context) {
    int count = 0;
    for (var i = cache.asMap().values().iterator(); i.hasNext();) {
      assertThat(cache).containsValue(i.next());
      count++;
      i.remove();
    }
    assertThat(cache).isEmpty();
    assertThat(count).isEqualTo(context.initialSize());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(count).exclusively();
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
    assertThat(count[0]).isEqualTo(context.initialSize());
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
    assertThat(count[0]).isEqualTo(context.initialSize());
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
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_estimateSize(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().values().spliterator();
    assertThat(spliterator.estimateSize()).isEqualTo(context.initialSize());
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
    @SuppressWarnings("unchecked")
    var entries = (Map.Entry<Int, CompletableFuture<Int>>[])
        cache.asMap().entrySet().toArray(new Map.Entry<?, ?>[0]);
    assertThat(entries).hasLength(context.original().size());
    for (var entry : entries) {
      assertThat(entry.getValue()).succeedsWith(context.original().get(entry.getKey()));
    }

    var array = cache.asMap().entrySet().toArray();
    assertThat(array).hasLength(context.original().size());
    for (var item : array) {
      @SuppressWarnings("unchecked")
      var entry = (Map.Entry<Int, CompletableFuture<Int>>) item;
      assertThat(entry.getValue()).succeedsWith(context.original().get(entry.getKey()));
    }

    @SuppressWarnings("unchecked")
    var func = (Map.Entry<Int, CompletableFuture<Int>>[])
        cache.asMap().entrySet().toArray(Map.Entry<?, ?>[]::new);
    assertThat(func).hasLength(context.original().size());
    for (var entry : entries) {
      assertThat(entry.getValue()).succeedsWith(context.original().get(entry.getKey()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet()).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.DEFAULT)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void entrySet_addIsNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    try {
      cache.asMap().entrySet().add(immutableEntry(Int.valueOf(1), Int.valueOf(2).asFuture()));
    } finally {
      assertThat(cache).isEmpty();
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().clear();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
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
    assertThat(cache.asMap().entrySet().stream().anyMatch(isEven)).isFalse();
    assertThat(removedIfEven).isEqualTo(hasEven);
    if (removedIfEven) {
      assertThat(cache).hasSizeLessThan(context.initialSize());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = cache.asMap().entrySet();
    assertThat(entries).doesNotContain(new Object());
    assertThat(entries.remove(new Object())).isFalse();
    assertThat(entries).hasSize(context.initialSize());
    entries.forEach(entry -> {
      assertThat(entries).contains(entry);
      assertThat(entries.remove(entry)).isTrue();
      assertThat(entries.remove(entry)).isFalse();
      assertThat(entries).doesNotContain(entry);
    });
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entryIterator(AsyncCache<Int, Int> cache, CacheContext context) {
    var i = cache.asMap().entrySet().iterator();
    int iterations = 0;
    while (i.hasNext()) {
      var entry = i.next();
      assertThat(cache).containsEntry(entry.getKey(), entry.getValue());
      iterations++;
      i.remove();
    }
    int count = iterations;
    assertThat(cache).isEmpty();
    assertThat(count).isEqualTo(context.initialSize());
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
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
        assertThat(entry).isInstanceOf(WriteThroughEntry.class);
      }
      count[0]++;
      assertThat(entry.getValue()).succeedsWith(context.original().get(entry.getKey()));
    });
    assertThat(count[0]).isEqualTo(context.initialSize());
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
          assertThat(entry).isInstanceOf(WriteThroughEntry.class);
        }
        count[0]++;
      });
    } while (advanced);
    assertThat(count[0]).isEqualTo(context.initialSize());
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
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_estimateSize(AsyncCache<Int, Int> cache, CacheContext context) {
    var spliterator = cache.asMap().entrySet().spliterator();
    assertThat(spliterator.estimateSize()).isEqualTo(context.initialSize());
  }

  /* ---------------- WriteThroughEntry -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = cache.asMap().entrySet().iterator().next();
    var value = Int.valueOf(3).asFuture();

    entry.setValue(value);
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).containsEntry(entry.getKey(), value);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_null(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().iterator().next().setValue(null);
  }

  // writeThroughEntry_serialize() - CompletableFuture is not serializable
}
