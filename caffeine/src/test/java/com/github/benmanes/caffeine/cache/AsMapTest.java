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

import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Maps.immutableEntry;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Map.entry;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
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
  // Statistics are recorded only for computing methods for loadSuccess and loadFailure

  /* --------------- is empty / size / clear --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void isEmpty(Map<Int, Int> map, CacheContext context) {
    if (context.original().isEmpty()) {
      assertThat(map).isExhaustivelyEmpty();
    } else {
      assertThat(map.isEmpty()).isFalse();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void size(Map<Int, Int> map, CacheContext context) {
    assertThat(map.size()).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void clear(Map<Int, Int> map, CacheContext context) {
    map.clear();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  /* --------------- contains --------------- */

  @CheckNoStats
  @SuppressWarnings("ReturnValueIgnored")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsKey_null(Map<Int, Int> map, CacheContext context) {
    map.containsKey(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.containsKey(key)).isTrue();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsKey_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.containsKey(context.absentKey())).isFalse();
  }

  @CheckNoStats
  @SuppressWarnings("ReturnValueIgnored")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void containsValue_null(Map<Int, Int> map, CacheContext context) {
    map.containsValue(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.containsValue(context.original().get(key))).isTrue();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void containsValue_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.containsValue(context.absentValue())).isFalse();
  }

  /* --------------- get --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(Map<Int, Int> map, CacheContext context) {
    map.get(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.get(context.absentKey())).isNull();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void get_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.get(key)).isEqualTo(context.original().get(key));
    }
  }

  /* --------------- getOrDefault --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getOrDefault_nullKey(Map<Int, Int> map, CacheContext context) {
    map.getOrDefault(null, Int.valueOf(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_absent(Map<Int, Int> map, CacheContext context) {
    Int key = context.absentKey();
    assertThat(map.getOrDefault(key, null)).isNull();
    assertThat(map.getOrDefault(key, key.negate())).isEqualTo(key.negate());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getOrDefault_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.getOrDefault(key, context.absentKey())).isEqualTo(context.original().get(key));
    }
  }

  /* --------------- forEach --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void forEach_null(Map<Int, Int> map, CacheContext context) {
    map.forEach(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_scan(Map<Int, Int> map, CacheContext context) {
    var remaining = new HashMap<Int, Int>(context.original());
    map.forEach(remaining::remove);
    assertThat(remaining).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void forEach_modify(Map<Int, Int> map, CacheContext context) {
    // non-deterministic traversal behavior with modifications, but shouldn't become corrupted
    @SuppressWarnings("ModifiedButNotUsed")
    var modified = new ArrayList<Int>();
    map.forEach((key, value) -> {
      Int newKey = context.lastKey().add(key);
      modified.add(newKey); // for weak keys
      map.put(newKey, key);
    });
  }

  /* --------------- put --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKey(Map<Int, Int> map, CacheContext context) {
    map.put(null, Int.valueOf(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullValue(Map<Int, Int> map, CacheContext context) {
    map.put(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_nullKeyAndValue(Map<Int, Int> map, CacheContext context) {
    map.put(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(Map<Int, Int> map, CacheContext context) {
    assertThat(map.put(context.absentKey(), context.absentValue())).isNull();
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).hasSize(context.initialSize() + 1);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = intern(new Int(context.original().get(key)));
      assertThat(map.put(key, value)).isSameInstanceAs(context.original().get(key));
      assertThat(map).containsEntry(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameInstance(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.put(key, value)).isSameInstanceAs(context.original().get(key));
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());

    if (context.isGuava()) {
      int count = context.firstMiddleLastKeys().size();
      assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.put(key, context.absentValue())).isEqualTo(value);
      assertThat(map).containsEntry(key, context.absentValue());
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void put_async_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().put(key, newValue);
      assertThat(result).isNull();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).containsEntry(key, newValue);
  }

  /* --------------- putAll --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_null(Map<Int, Int> map, CacheContext context) {
    map.putAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(Map<Int, Int> map, CacheContext context) {
    map.putAll(Map.of());
    assertThat(map).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_insert(Map<Int, Int> map, CacheContext context) {
    int startKey = context.original().size() + 1;
    var entries = IntStream
        .range(startKey, 100 + startKey)
        .mapToObj(Int::valueOf)
        .collect(Collectors.toMap(Function.identity(), key -> key.negate()));
    map.putAll(entries);
    assertThat(map).hasSize(100 + context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void putAll_replace(Map<Int, Int> map, CacheContext context) {
    var entries = new LinkedHashMap<Int, Int>(context.original());
    entries.replaceAll((key, value) -> key);
    map.putAll(entries);
    assertThat(map).isEqualTo(entries);
    assertThat(context).removalNotifications()
        .withCause(REPLACED).hasSize(entries.size()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL })
  public void putAll_mixed(Map<Int, Int> map, CacheContext context) {
    var entries = new HashMap<Int, Int>();
    var replaced = new HashMap<Int, Int>();
    context.original().forEach((key, value) -> {
      if ((key.intValue() % 2) == 0) {
        value = value.add(1);
        replaced.put(key, value);
      }
      entries.put(key, value);
    });

    map.putAll(entries);
    assertThat(map).isEqualTo(entries);
    var expect = context.isGuava() ? entries : replaced;
    assertThat(context).removalNotifications()
        .withCause(REPLACED).hasSize(expect.size()).exclusively();
  }

  /* --------------- putIfAbsent --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKey(Map<Int, Int> map, CacheContext context) {
    map.putIfAbsent(null, Int.valueOf(2));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullValue(Map<Int, Int> map, CacheContext context) {
    map.putIfAbsent(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_nullKeyAndValue(Map<Int, Int> map, CacheContext context) {
    map.putIfAbsent(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.putIfAbsent(key, key)).isEqualTo(value);
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putIfAbsent_insert(Map<Int, Int> map, CacheContext context) {
    assertThat(map.putIfAbsent(context.absentKey(), context.absentValue())).isNull();
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).hasSize(context.initialSize() + 1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void putIfAbsent_async_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().putIfAbsent(key, newValue);
      assertThat(result).isNull();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).containsEntry(key, newValue);
  }

  /* --------------- remove --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_nullKey(Map<Int, Int> map, CacheContext context) {
    map.remove(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void remove_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.remove(context.absentKey())).isNull();
    assertThat(map).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void remove_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      map.remove(key);
    }
    int expectedSize = context.original().size() - context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(expectedSize);

    int count = context.firstMiddleLastKeys().size();
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void remove_async_null(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = new CompletableFuture<Int>();
    Int key = context.absentKey();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().remove(key);
      assertThat(result).isNull();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- remove conditionally --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKey(Map<Int, Int> map, CacheContext context) {
    map.remove(null, context.absentValue());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullValue(Map<Int, Int> map, CacheContext context) {
    assertThat(map.remove(context.absentKey(), null)).isFalse(); // see ConcurrentHashMap
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_nullKeyAndValue(Map<Int, Int> map, CacheContext context) {
    map.remove(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.remove(context.absentKey(), context.absentValue())).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void removeConditionally_presentKey(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.remove(key, key)).isFalse();
    }
    assertThat(map).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void removeConditionally_presentKeyAndValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.remove(key, value)).isTrue();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void removeConditionally_async_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      boolean result = cache.synchronous().asMap().remove(key, newValue);
      assertThat(result).isFalse();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- replace --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_null(Map<Int, Int> map, CacheContext context) {
    map.replace(null, Int.valueOf(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullValue(Map<Int, Int> map, CacheContext context) {
    map.replace(Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_nullKeyAndValue(Map<Int, Int> map, CacheContext context) {
    map.replace(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replace_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.replace(context.absentKey(), context.absentValue())).isNull();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = intern(new Int(context.original().get(key)));
      assertThat(map.replace(key, value)).isSameInstanceAs(context.original().get(key));
      assertThat(map).containsEntry(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameInstance(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.replace(key, value)).isSameInstanceAs(value);
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());

    if (context.isGuava()) {
      int count = context.firstMiddleLastKeys().size();
      assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_differentValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int oldValue = context.original().get(key);
      assertThat(map.replace(key, context.absentValue())).isEqualTo(oldValue);
      assertThat(map).containsEntry(key, context.absentValue());
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void replace_async_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().replace(key, newValue);
      assertThat(result).isNull();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- replace conditionally --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKey(Map<Int, Int> map, CacheContext context) {
    map.replace(null, Int.valueOf(1), Int.valueOf(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldValue(Map<Int, Int> map, CacheContext context) {
    map.replace(Int.valueOf(1), null, Int.valueOf(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullNewValue(Map<Int, Int> map, CacheContext context) {
    map.replace(Int.valueOf(1), Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndOldValue(Map<Int, Int> map, CacheContext context) {
    map.replace(null, null, Int.valueOf(1));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndNewValue(Map<Int, Int> map, CacheContext context) {
    map.replace(null, Int.valueOf(1), null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullOldAndNewValue(Map<Int, Int> map, CacheContext context) {
    map.replace(Int.valueOf(1), null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndValues(Map<Int, Int> map, CacheContext context) {
    map.replace(null, null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_absent(Map<Int, Int> map, CacheContext context) {
    Int key = context.absentKey();
    assertThat(map.replace(key, key, key)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void replaceConditionally_wrongOldValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.replace(key, key, context.absentKey())).isFalse();
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());

    int count = context.firstMiddleLastKeys().size();
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.replace(key, value, value)).isTrue();
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());

    if (context.isGuava()) {
      int count = context.firstMiddleLastKeys().size();
      assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_differentValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.replace(key, context.original().get(key), context.absentValue())).isTrue();
      assertThat(map).containsEntry(key, context.absentValue());
    }
    assertThat(map).hasSize(context.initialSize());

    int count = context.firstMiddleLastKeys().size();
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void replaceConditionally_async_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      boolean replaced = cache.synchronous().asMap().replace(key, key, newValue);
      assertThat(replaced).isFalse();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- replaceAll --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_null(Map<Int, Int> map, CacheContext context) {
    map.replaceAll(null);
  }

  @CheckNoStats
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void replaceAll_nullValue(Map<Int, Int> map, CacheContext context) {
    map.replaceAll((key, value) -> null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void replaceAll_sameValue(Map<Int, Int> map, CacheContext context) {
    map.replaceAll((key, value) -> value);
    assertThat(map).containsExactlyEntriesIn(context.original());

    if (context.isGuava()) {
      assertThat(context).removalNotifications()
          .withCause(REPLACED).hasSize(map.size()).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void replaceAll_differentValue(Map<Int, Int> map, CacheContext context) {
    map.replaceAll((key, value) -> key);
    map.forEach((key, value) -> {
      assertThat(value).isEqualTo(key);
    });
    assertThat(context).removalNotifications()
        .withCause(REPLACED).hasSize(map.size()).exclusively();
  }

  /* --------------- computeIfAbsent --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullKey(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(null, key -> key.negate());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfAbsent_nullMappingFunction(Map<Int, Int> map, CacheContext context) {
    map.computeIfAbsent(context.absentKey(), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_nullValue(Map<Int, Int> map, CacheContext context) {
    assertThat(map.computeIfAbsent(context.absentKey(), key -> null)).isNull();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    assertThat(map).hasSize(context.initialSize());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void computeIfAbsent_recursive(Map<Int, Int> map, CacheContext context) {
    var mappingFunction = new Function<Int, Int>() {
      @Override public Int apply(Int key) {
        return map.computeIfAbsent(key, this);
      }
    };
    try {
      map.computeIfAbsent(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void computeIfAbsent_pingpong(Map<Int, Int> map, CacheContext context) {
    var mappingFunction = new Function<Int, Int>() {
      @Override public Int apply(Int key) {
        return map.computeIfAbsent(key.negate(), this);
      }
    };
    try {
      map.computeIfAbsent(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_error(Map<Int, Int> map, CacheContext context) {
    try {
      map.computeIfAbsent(context.absentKey(), key -> { throw new ExpectedError(); });
    } catch (ExpectedError expected) {}
    assertThat(map).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
    assertThat(map.computeIfAbsent(context.absentKey(), key -> key)).isEqualTo(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_present(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.computeIfAbsent(key, k -> { throw new AssertionError(); })).isEqualTo(value);
    }
    assertThat(map).hasSize(context.initialSize());
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(count).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfAbsent_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.computeIfAbsent(context.absentKey(), key -> context.absentValue()))
        .isEqualTo(context.absentValue());
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).hasSize(1 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void computeIfAbsent_async_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().computeIfAbsent(key, k -> newValue);
      assertThat(result).isEqualTo(newValue);
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).containsEntry(key, newValue);
  }

  /* --------------- computeIfPresent --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullKey(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(null, (key, value) -> key.negate());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void computeIfPresent_nullMappingFunction(Map<Int, Int> map, CacheContext context) {
    map.computeIfPresent(Int.valueOf(1), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_nullValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      map.computeIfPresent(key, (k, v) -> null);
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize() - count);
    assertThat(context).stats().hits(0).misses(0).success(0).failures(count);
    assertThat(context).removalNotifications()
        .withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void computeIfPresent_recursive(Map<Int, Int> map, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      boolean recursed;

      @Override public Int apply(Int key, Int value) {
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
  public void computeIfPresent_pingpong(Map<Int, Int> map, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      int recursed;

      @Override public Int apply(Int key, Int value) {
        if (++recursed == 2) {
          throw new StackOverflowError();
        }
        return map.computeIfPresent(context.lastKey(), this);
      }
    };
    map.computeIfPresent(context.firstKey(), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_error(Map<Int, Int> map, CacheContext context) {
    try {
      map.computeIfPresent(context.firstKey(), (key, value) -> { throw new ExpectedError(); });
    } catch (ExpectedError expected) {}
    assertThat(map).isEqualTo(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
    assertThat(map.computeIfPresent(context.firstKey(), (k, v) -> k.negate()))
        .isEqualTo(context.firstKey().negate());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void computeIfPresent_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.computeIfPresent(context.absentKey(), (key, value) -> value)).isNull();
    assertThat(map).doesNotContainKey(context.absentKey());
    assertThat(map).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present_sameValue(Map<Int, Int> map, CacheContext context) {
    var expectedMap = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = intern(new Int(context.original().get(key)));
      assertThat(map.computeIfPresent(key, (k, v) -> value)).isSameInstanceAs(value);
      expectedMap.put(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    assertThat(map).hasSize(context.initialSize());
    assertThat(map).containsAtLeastEntriesIn(expectedMap);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present_sameInstance(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.computeIfPresent(key, (k, v) -> v))
          .isSameInstanceAs(context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map).containsEntry(key, context.original().get(key));
    }
    assertThat(map).hasSize(context.initialSize());

    if (context.isGuava()) {
      assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present_differentValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.computeIfPresent(key, (k, v) -> k)).isEqualTo(key);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map).containsEntry(key, key);
    }
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void computeIfPresent_async_null(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().computeIfPresent(key, (k, oldValue) -> newValue);
      assertThat(result).isNull();
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- compute --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullKey(Map<Int, Int> map, CacheContext context) {
    map.compute(null, (key, value) -> key.negate());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void compute_nullMappingFunction(Map<Int, Int> map, CacheContext context) {
    map.compute(Int.valueOf(1), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_remove(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.compute(key, (k, v) -> null)).isNull();
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize() - count);
    assertThat(context).stats().hits(0).misses(0).success(0).failures(count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void compute_recursive(Map<Int, Int> map, CacheContext context) {
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      @Override public Int apply(Int key, Int value) {
        return map.compute(key, this);
      }
    };
    try {
      map.compute(context.absentKey(), mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void compute_pingpong(Map<Int, Int> map, CacheContext context) {
    var key1 = Int.valueOf(1);
    var key2 = Int.valueOf(2);
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      @Override public Int apply(Int key, Int value) {
        return map.compute(key.equals(key1) ? key2 : key1, this);
      }
    };
    try {
      map.compute(key1, mappingFunction);
      Assert.fail();
    } catch (StackOverflowError | IllegalStateException e) { /* ignored */ }
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void compute_error(Map<Int, Int> map, CacheContext context) {
    try {
      map.compute(context.absentKey(), (key, value) -> { throw new IllegalStateException(); });
      Assert.fail();
    } catch (IllegalStateException e) { /* ignored */ }

    assertThat(map).isEqualTo(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
    assertThat(map.compute(context.absentKey(), (k, v) -> k.negate()))
        .isEqualTo(context.absentKey().negate());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent_nullValue(Map<Int, Int> map, CacheContext context) {
    assertThat(map.compute(context.absentKey(), (key, value) -> null)).isNull();
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
    assertThat(map.get(context.absentKey())).isNull();
    assertThat(map).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void compute_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.compute(context.absentKey(), (key, value) -> context.absentValue()))
        .isEqualTo(context.absentValue());
    assertThat(context).stats().hits(0).misses(0).success(1).failures(0);
    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).hasSize(1 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameValue(Map<Int, Int> map, CacheContext context) {
    var expectedMap = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = intern(new Int(context.original().get(key)));
      assertThat(map.compute(key, (k, v) -> value)).isSameInstanceAs(value);
      expectedMap.put(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    assertThat(map).hasSize(context.initialSize());
    assertThat(map).containsAtLeastEntriesIn(expectedMap);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameInstance(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.compute(key, (k, v) -> value)).isSameInstanceAs(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());

    if (context.isGuava()) {
      assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_differentValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.compute(key, (k, v) -> k)).isEqualTo(key);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map).containsEntry(key, key);
    }
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void compute_async_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap().compute(key, (k, oldValue) -> newValue);
      assertThat(result).isEqualTo(newValue);
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).containsEntry(key, newValue);
  }

  /* --------------- merge --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullKey(Map<Int, Int> map, CacheContext context) {
    map.merge(null, Int.valueOf(1), (oldValue, value) -> oldValue.negate());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullValue(Map<Int, Int> map, CacheContext context) {
    map.merge(Int.valueOf(1), null, (oldValue, value) -> oldValue.negate());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void merge_nullMappingFunction(Map<Int, Int> map, CacheContext context) {
    map.merge(Int.valueOf(1), Int.valueOf(1), null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_remove(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.merge(key, value, (oldValue, v) -> null)).isNull();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(map).hasSize(context.initialSize() - count);
    assertThat(context).stats().hits(0).misses(0).success(0).failures(count);
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches")
  public void merge_recursive(Map<Int, Int> map, CacheContext context) {
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      @Override public Int apply(Int oldValue, Int value) {
        return map.merge(oldValue, oldValue.negate(), this);
      }
    };
    Int firstValue = context.original().get(context.firstKey());
    Int value = map.merge(context.absentKey(), firstValue, mappingFunction);
    assertThat(value).isEqualTo(firstValue);
  }

  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = StackOverflowError.class)
  public void merge_pingpong(Map<Int, Int> map, CacheContext context) {
    // As we cannot provide immediate checking without an expensive solution, e.g. ThreadLocal,
    // instead we assert that a stack overflow error will occur to inform the developer (vs
    // a live-lock or deadlock alternative).
    var mappingFunction = new BiFunction<Int, Int, Int>() {
      int recursed;

      @Override public Int apply(Int oldValue, Int value) {
        if (++recursed == 2) {
          throw new StackOverflowError();
        }
        return map.merge(context.lastKey(), context.original().get(context.lastKey()), this);
      }
    };
    map.merge(context.firstKey(), context.original().get(context.firstKey()), mappingFunction);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_error(Map<Int, Int> map, CacheContext context) {
    try {
      map.merge(context.firstKey(), context.original().get(context.firstKey()),
          (oldValue, value) -> { throw new ExpectedError(); });
    } catch (ExpectedError expected) {}
    assertThat(map).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(1);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void merge_absent(Map<Int, Int> map, CacheContext context) {
    Int result = map.merge(context.absentKey(),
        context.absentValue(), (oldValue, value) -> value);
    assertThat(result).isEqualTo(context.absentValue());

    assertThat(map).containsEntry(context.absentKey(), context.absentValue());
    assertThat(map).hasSize(1 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameValue(Map<Int, Int> map, CacheContext context) {
    var expectedMap = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = intern(new Int(context.original().get(key)));
      assertThat(map.merge(key, key.negate(), (oldValue, v) -> value)).isSameInstanceAs(value);
      expectedMap.put(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    assertThat(map).hasSize(context.initialSize());
    assertThat(map).containsAtLeastEntriesIn(expectedMap);
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameInstance(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map.merge(key, key.negate(), (oldValue, v) -> value)).isSameInstanceAs(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      assertThat(map).containsEntry(key, value);
    }
    assertThat(map).hasSize(context.initialSize());

    if (context.isGuava()) {
      assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_differentValue(Map<Int, Int> map, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map.merge(key, key, (oldValue, v) -> oldValue.add(v))).isEqualTo(0);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(map).containsEntry(key, Int.valueOf(0));
    }
    assertThat(map).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(count).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = {Listener.DEFAULT, Listener.REJECTING})
  public void merge_async_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int newValue = context.absentValue();
    var future = new CompletableFuture<Int>();

    cache.put(key, future);
    var start = new AtomicBoolean();
    var done = new AtomicBoolean();
    ConcurrentTestHarness.execute(() -> {
      start.set(true);
      Int result = cache.synchronous().asMap()
          .merge(key, newValue, (k, oldValue) -> newValue.add(1));
      assertThat(result).isEqualTo(newValue);
      done.set(true);
    });

    await().untilTrue(start);
    future.complete(null);

    await().untilTrue(done);
    assertThat(cache).containsEntry(key, newValue);
  }

  /* --------------- equals / hashCode --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_null(Map<Int, Int> map, CacheContext context) {
    assertThat(map.equals(null)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @SuppressWarnings("SelfEquals")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals_self(Map<Int, Int> map, CacheContext context) {
    assertThat(map.equals(map)).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equals(Map<Int, Int> map, CacheContext context) {
    assertThat(map.equals(context.original())).isTrue();
    assertThat(context.original().equals(map)).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode(Map<Int, Int> map, CacheContext context) {
    assertThat(map.hashCode()).isEqualTo(context.original().hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hashCode_self(Map<Int, Int> map, CacheContext context) {
    assertThat(map.hashCode()).isEqualTo(map.hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(Map<Int, Int> map, CacheContext context) {
    var other = Int.mapOf(1, -1, 2, -2, 3, -3);
    assertThat(map.equals(other)).isFalse();
    assertThat(other.equals(map)).isFalse();
    assertThat(map.hashCode()).isNotEqualTo(other.hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(Map<Int, Int> map, CacheContext context) {
    var other = Int.mapOf(1, -1, 2, -2, 3, -3);
    assertThat(map.equals(other)).isFalse();
    assertThat(other.equals(map)).isFalse();
    assertThat(map.hashCode()).isNotEqualTo(other.hashCode());

    Map<Int, Int> empty = Map.of();
    assertThat(map.equals(empty)).isFalse();
    assertThat(empty.equals(map)).isFalse();
    assertThat(map.hashCode()).isNotEqualTo(empty.hashCode());
  }

  /* --------------- toString --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void toString(Map<Int, Int> map, CacheContext context) {
    var toString = map.toString();
    if (!context.original().toString().equals(toString)) {
      map.forEach((key, value) -> {
        assertThat(toString).contains(key + "=" + value);
      });
    }
  }

  /* --------------- Key Set --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySetToArray_null(Map<Int, Int> map, CacheContext context) {
    map.keySet().toArray((Int[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySetToArray(Map<Int, Int> map, CacheContext context) {
    var array = map.keySet().toArray();
    assertThat(array).asList().containsExactlyElementsIn(context.original().keySet());

    var ints = map.keySet().toArray(new Int[0]);
    assertThat(ints).asList().containsExactlyElementsIn(context.original().keySet());

    var func = map.keySet().toArray(Int[]::new);
    assertThat(func).asList().containsExactlyElementsIn(context.original().keySet());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_contains_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.keySet().contains(context.absentKey())).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_contains_present(Map<Int, Int> map, CacheContext context) {
    assertThat(map.keySet().contains(context.firstKey())).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_whenEmpty(Map<Int, Int> map, CacheContext context) {
    assertThat(map.keySet()).isExhaustivelyEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void keySet_addNotSupported(Map<Int, Int> map, CacheContext context) {
    map.keySet().add(Int.valueOf(1));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_clear(Map<Int, Int> map, CacheContext context) {
    map.keySet().clear();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet(Map<Int, Int> map, CacheContext context) {
    var keys = map.keySet();
    assertThat(keys).doesNotContain(new Object());
    assertThat(keys.remove(new Object())).isFalse();
    assertThat(keys).hasSize(context.initialSize());
    for (Int key : Set.copyOf(keys)) {
      assertThat(keys).contains(key);
      assertThat(keys.remove(key)).isTrue();
      assertThat(keys.remove(key)).isFalse();
      assertThat(keys).doesNotContain(key);
    }
    assertThat(map).isExhaustivelyEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_iterator(Map<Int, Int> map, CacheContext context) {
    int iterations = 0;
    for (var i = map.keySet().iterator(); i.hasNext();) {
      assertThat(map).containsKey(i.next());
      iterations++;
      i.remove();
    }
    int count = iterations;
    assertThat(map).isExhaustivelyEmpty();
    assertThat(count).isEqualTo(context.initialSize());
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void keyIterator_noElement(Map<Int, Int> map, CacheContext context) {
    map.keySet().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void keyIterator_noMoreElements(Map<Int, Int> map, CacheContext context) {
    map.keySet().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySpliterator_forEachRemaining_null(Map<Int, Int> map, CacheContext context) {
    map.keySet().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    int[] count = new int[1];
    map.keySet().spliterator().forEachRemaining(key -> count[0]++);
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void keySpliterator_tryAdvance_null(Map<Int, Int> map, CacheContext context) {
    map.keySet().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.keySet().spliterator();
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
  public void keySpliterator_trySplit(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.keySet().spliterator();
    var other = firstNonNull(spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(key -> count[0]++);
    other.forEachRemaining(key -> count[0]++);
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_estimateSize(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.keySet().spliterator();
    assertThat(spliterator.estimateSize()).isEqualTo(context.initialSize());
  }

  /* --------------- Values --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valuesToArray_null(Map<Int, Int> map, CacheContext context) {
    map.values().toArray((Int[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void valuesToArray(Map<Int, Int> map, CacheContext context) {
    var array = map.values().toArray();
    assertThat(array).asList().containsExactlyElementsIn(context.original().values());

    var ints = map.values().toArray(new Int[0]);
    assertThat(ints).asList().containsExactlyElementsIn(context.original().values());

    var func = map.values().toArray(Int[]::new);
    assertThat(func).asList().containsExactlyElementsIn(context.original().values());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_contains_absent(Map<Int, Int> map, CacheContext context) {
    assertThat(map.values().contains(context.absentValue())).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_contains_present(Map<Int, Int> map, CacheContext context) {
    assertThat(map.values().contains(context.original().get(context.firstKey()))).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_empty(Map<Int, Int> map, CacheContext context) {
    assertThat(map.values()).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void values_addNotSupported(Map<Int, Int> map, CacheContext context) {
    map.values().add(Int.valueOf(1));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_clear(Map<Int, Int> map, CacheContext context) {
    map.values().clear();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void values_removeIf_null(Map<Int, Int> map, CacheContext context) {
    map.values().removeIf(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf(Map<Int, Int> map, CacheContext context) {
    Predicate<Int> isEven = value -> (value.intValue() % 2) == 0;
    boolean hasEven = map.values().stream().anyMatch(isEven);

    boolean removedIfEven = map.values().removeIf(isEven);
    assertThat(map.values().stream().anyMatch(isEven)).isFalse();
    assertThat(removedIfEven).isEqualTo(hasEven);
    if (removedIfEven) {
      assertThat(map.size()).isLessThan(context.original().size());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values(Map<Int, Int> map, CacheContext context) {
    var values = map.values();
    assertThat(values).doesNotContain(new Object());
    assertThat(values.remove(new Object())).isFalse();
    assertThat(values).hasSize(context.initialSize());
    for (Int value : List.copyOf(values)) {
      assertThat(values).contains(value);
      assertThat(values.remove(value)).isTrue();
      assertThat(values.remove(value)).isFalse();
      assertThat(values).doesNotContain(value);
    }
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueIterator(Map<Int, Int> map, CacheContext context) {
    int iterations = 0;
    for (var i = map.values().iterator(); i.hasNext();) {
      assertThat(map.values()).contains(i.next());
      iterations++;
      i.remove();
    }
    int count = iterations;
    assertThat(map).isExhaustivelyEmpty();
    assertThat(count).isEqualTo(context.initialSize());
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void valueIterator_noElement(Map<Int, Int> map, CacheContext context) {
    map.values().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void valueIterator_noMoreElements(Map<Int, Int> map, CacheContext context) {
    map.values().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valueSpliterator_forEachRemaining_null(Map<Int, Int> map, CacheContext context) {
    map.values().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    int[] count = new int[1];
    map.values().spliterator().forEachRemaining(value -> count[0]++);
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void valueSpliterator_tryAdvance_null(Map<Int, Int> map, CacheContext context) {
    map.values().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.values().spliterator();
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
  public void valueSpliterator_trySplit(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.values().spliterator();
    var other = firstNonNull(spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(value -> count[0]++);
    other.forEachRemaining(value -> count[0]++);
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_estimateSize(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.values().spliterator();
    assertThat(spliterator.estimateSize()).isEqualTo(context.initialSize());
  }

  /* --------------- Entry Set --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySetToArray_null(Map<Int, Int> map, CacheContext context) {
    map.entrySet().toArray((Map.Entry<?, ?>[]) null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entriesToArray(Map<Int, Int> map, CacheContext context) {
    var array = map.entrySet().toArray();
    assertThat(array).asList().containsExactlyElementsIn(context.original().entrySet());

    var ints = map.entrySet().toArray(new Map.Entry<?, ?>[0]);
    assertThat(ints).asList().containsExactlyElementsIn(context.original().entrySet());

    var func = map.entrySet().toArray(Map.Entry<?, ?>[]::new);
    assertThat(func).asList().containsExactlyElementsIn(context.original().entrySet());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_contains_nullKey(Map<Int, Int> map, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(null, context.original().get(context.firstKey()));
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_contains_nullValue(Map<Int, Int> map, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(context.firstKey(), null);
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_contains_absent(Map<Int, Int> map, CacheContext context) {
    var entry = entry(context.absentKey(), context.absentValue());
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_contains_present(Map<Int, Int> map, CacheContext context) {
    var entry = entry(context.firstKey(), context.original().get(context.firstKey()));
    assertThat(map.entrySet().contains(entry)).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_empty(Map<Int, Int> map, CacheContext context) {
    assertThat(map.entrySet()).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.DEFAULT)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void entrySet_addIsNotSupported(Map<Int, Int> map, CacheContext context) {
    try {
      map.entrySet().add(immutableEntry(Int.valueOf(1), Int.valueOf(2)));
    } finally {
      assertThat(map).isExhaustivelyEmpty();
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_clear(Map<Int, Int> map, CacheContext context) {
    map.entrySet().clear();
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySet_removeIf_null(Map<Int, Int> map, CacheContext context) {
    map.entrySet().removeIf(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeIf(Map<Int, Int> map, CacheContext context) {
    Predicate<Map.Entry<Int, Int>> isEven = entry -> (entry.getValue().intValue() % 2) == 0;
    boolean hasEven = map.entrySet().stream().anyMatch(isEven);

    boolean removedIfEven = map.entrySet().removeIf(isEven);
    assertThat(map.entrySet().stream().anyMatch(isEven)).isFalse();
    assertThat(removedIfEven).isEqualTo(hasEven);
    if (removedIfEven) {
      assertThat(map).hasSizeLessThan(context.initialSize());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet(Map<Int, Int> map, CacheContext context) {
    var entries = map.entrySet();
    assertThat(entries).doesNotContain(new Object());
    assertThat(entries.remove(new Object())).isFalse();
    assertThat(entries).hasSize(context.initialSize());
    entries.forEach(entry -> {
      assertThat(entries).contains(entry);
      assertThat(entries.remove(entry)).isTrue();
      assertThat(entries.remove(entry)).isFalse();
      assertThat(entries).doesNotContain(entry);
    });
    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .hasSize(context.initialSize()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entryIterator(Map<Int, Int> map, CacheContext context) {
    int iterations = 0;
    for (var i = map.entrySet().iterator(); i.hasNext();) {
      var entry = i.next();
      assertThat(map).containsEntry(entry.getKey(), entry.getValue());
      iterations++;
      i.remove();
    }
    int count = iterations;
    assertThat(map).isExhaustivelyEmpty();
    assertThat(count).isEqualTo(context.initialSize());
    assertThat(context).removalNotifications().withCause(EXPLICIT).hasSize(count).exclusively();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void entryIterator_noElement(Map<Int, Int> map, CacheContext context) {
    map.entrySet().iterator().remove();
  }

  @CheckNoStats
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NoSuchElementException.class)
  public void entryIterator_noMoreElements(Map<Int, Int> map, CacheContext context) {
    map.entrySet().iterator().next();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySpliterator_forEachRemaining_null(Map<Int, Int> map, CacheContext context) {
    map.entrySet().spliterator().forEachRemaining(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    int[] count = new int[1];
    map.entrySet().spliterator().forEachRemaining(entry -> {
      if (context.isCaffeine()) {
        assertThat(entry).isInstanceOf(WriteThroughEntry.class);
      }
      count[0]++;
      assertThat(context.original()).containsEntry(entry.getKey(), entry.getValue());
    });
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void entrySpliterator_tryAdvance_null(Map<Int, Int> map, CacheContext context) {
    map.entrySet().spliterator().tryAdvance(null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.entrySet().spliterator();
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
  public void entrySpliterator_trySplit(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.entrySet().spliterator();
    var other = firstNonNull(spliterator.trySplit(), Spliterators.emptySpliterator());

    int[] count = new int[1];
    spliterator.forEachRemaining(entry -> count[0]++);
    other.forEachRemaining(entry -> count[0]++);
    assertThat(count[0]).isEqualTo(context.initialSize());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_estimateSize(Map<Int, Int> map, CacheContext context) {
    var spliterator = map.entrySet().spliterator();
    assertThat(spliterator.estimateSize()).isEqualTo(context.initialSize());
  }

  /* --------------- WriteThroughEntry --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry(Map<Int, Int> map, CacheContext context) {
    var entry = map.entrySet().iterator().next();

    entry.setValue(Int.valueOf(3));
    assertThat(map).hasSize(context.initialSize());
    assertThat(map).containsEntry(entry.getKey(), Int.valueOf(3));
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_null(Map<Int, Int> map, CacheContext context) {
    map.entrySet().iterator().next().setValue(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_serialize(Map<Int, Int> map, CacheContext context) {
    var entry = map.entrySet().iterator().next();
    var copy = SerializableTester.reserialize(entry);
    assertThat(entry).isEqualTo(copy);
  }

  @SuppressWarnings("serial")
  static final class ExpectedError extends Error {}
}
