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
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.IntSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.function.Function.identity;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.WARN;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
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

import org.eclipse.collections.impl.factory.Sets;
import org.mockito.Mockito;
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
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

/**
 * The test cases for the {@link AsyncCache#asMap()} view and its serializability. These tests do
 * not validate eviction management or concurrency behavior.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckNoEvictions @CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsyncAsMapTest {
  // Statistics are recorded only for computing methods for loadSuccess and loadFailure

  /* ---------------- is empty / size / clear -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void isEmpty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().isEmpty()).isEqualTo(context.original().isEmpty());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
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
        .contains(context.original()).exclusively();
  }

  /* ---------------- contains -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void containsKey_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().containsKey(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void containsKey_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().containsKey(key)).isTrue();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void containsKey_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().containsKey(context.absentKey())).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void containsValue_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().containsValue(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void containsValue_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().containsValue(cache.asMap().get(key))).isTrue();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void containsValue_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().containsValue(context.absentValue().asFuture())).isFalse();
  }

  /* ---------------- get -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void get_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().get(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void get_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().get(context.absentKey())).isNull();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void get_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().get(key)).succeedsWith(context.original().get(key));
    }
  }

  /* ---------------- getOrDefault -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getOrDefault_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().getOrDefault(null, Int.valueOf(1).asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getOrDefault_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().getOrDefault(context.absentKey(), null)).isNull();
    assertThat(cache.asMap().getOrDefault(context.absentKey(), value)).isEqualTo(value);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getOrDefault_present(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().getOrDefault(key, value)).succeedsWith(context.original().get(key));
    }
  }

  /* ---------------- forEach -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void forEach_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().forEach(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void forEach_scan(AsyncCache<Int, Int> cache, CacheContext context) {
    var remaining = new HashMap<>(context.original());
    cache.asMap().forEach((key, future) -> {
      assertThat(key).isNotNull();
      assertThat(future).isNotNull();
      assertThat(future.join()).isNotNull();
      assertThat(remaining.remove(key, future.join())).isTrue();
    });
    assertThat(remaining).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void forEach_modify(AsyncCache<Int, Int> cache, CacheContext context) {
    // non-deterministic traversal behavior with modifications, but shouldn't become corrupted
    cache.asMap().forEach((key, value) -> {
      Int newKey = intern(context.lastKey().add(key));
      cache.synchronous().put(newKey, key);
    });
  }

  /* ---------------- put -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().put(null, Int.valueOf(1).asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().put(Int.valueOf(1), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().put(null, null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().put(context.absentKey(), value)).isNull();
    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(context.initialSize() + 1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = cache.asMap().get(key).thenApply(val -> intern(new Int(val)));
      assertThat(cache.asMap().put(key, value)).isSameInstanceAs(oldValue);
      assertThat(cache).containsEntry(key, value);
      replaced.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameInstance(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().put(key, value)).isSameInstanceAs(value);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var newValue = context.absentValue().asFuture();
      assertThat(cache.asMap().put(key, newValue)).succeedsWith(context.original().get(key));
      assertThat(cache).containsEntry(key, newValue);
      replaced.put(key, context.original().get(key));
    }

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void put_recursiveUpdate(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.synchronous().put(context.absentKey(), context.absentValue());
    var result = cache.asMap().compute(context.absentKey(), (key, future) -> {
      var oldValue = cache.synchronous().asMap().put(key, intern(future.join().add(1)));
      assertThat(oldValue).isEqualTo(future.join());
      return key.asFuture();
    });
    assertThat(result).succeedsWith(context.absentKey());
    assertThat(cache).containsEntry(context.absentKey(), context.absentKey());
  }

  /* ---------------- putAll -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().putAll(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putAll_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().putAll(Map.of());
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putAll_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    int startKey = context.original().size() + 1;
    var entries = IntStream
        .range(startKey, 100 + startKey)
        .mapToObj(Int::valueOf)
        .collect(toImmutableMap(identity(), key -> key.negate().asFuture()));
    cache.asMap().putAll(entries);
    assertThat(cache).hasSize(100 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void putAll_replace(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = Maps.toMap(context.original().keySet(), Int::asFuture);
    cache.asMap().putAll(entries);
    assertThat(cache).containsExactlyEntriesIn(entries);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL })
  public void putAll_mixed(AsyncCache<Int, Int> cache, CacheContext context) {
    var entries = new HashMap<Int, CompletableFuture<Int>>();
    var replaced = new HashMap<Int, Int>();
    context.original().forEach((key, value) -> {
      if ((key.intValue() % 2) == 0) {
        replaced.put(key, value);
        entries.put(key, value.add(1).asFuture());
      } else {
        entries.put(key, cache.asMap().get(key));
      }
    });

    cache.asMap().putAll(entries);
    assertThat(cache).containsExactlyEntriesIn(entries);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* ---------------- putIfAbsent -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putIfAbsent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().putIfAbsent(null, Int.valueOf(2).asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putIfAbsent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().putIfAbsent(Int.valueOf(1), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putIfAbsent_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().putIfAbsent(null, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
  removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putIfAbsent_present(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().putIfAbsent(key, key.asFuture())).isEqualTo(value);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putIfAbsent_insert(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().putIfAbsent(context.absentKey(), value)).isNull();
    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(context.initialSize() + 1);
  }

  /* ---------------- remove -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void remove_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().remove(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void remove_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().remove(context.absentKey())).isNull();
    assertThat(cache).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void remove_present(AsyncCache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      cache.asMap().remove(key);
      removed.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize() - context.firstMiddleLastKeys().size());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  /* ---------------- remove conditionally -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void removeConditionally_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().remove(null, context.absentValue().asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void removeConditionally_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().remove(context.absentKey(), null)).isFalse(); // see ConcurrentHashMap
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void removeConditionally_nullKeyAndValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().remove(null, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void removeConditionally_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().remove(context.absentKey(), value)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
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
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      removed.put(key, context.original().get(key));
      assertThat(cache.asMap().remove(key, value)).isTrue();
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  /* ---------------- replace -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replace_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().replace(null, Int.valueOf(1).asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replace_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().replace(Int.valueOf(1), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replace_nullKeyAndValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().replace(null, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replace_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var result = cache.asMap().replace(context.absentKey(), context.absentValue().asFuture());
    assertThat(result).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.SINGLETON, removalListener = Listener.CONSUMING)
  public void replace_failure(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().replace(context.firstKey(), CompletableFuture.failedFuture(new Exception()));
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var newValue = cache.asMap().get(key).thenApply(val -> intern(new Int(val)));
      assertThat(cache.asMap().replace(key, newValue)).isSameInstanceAs(oldValue);
      assertThat(cache).containsEntry(key, newValue);
      replaced.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_sameInstance(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().replace(key, value)).isSameInstanceAs(value);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replace_differentValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      replaced.put(key, context.original().get(key));
      assertThat(cache.asMap().replace(key, value)).isEqualTo(oldValue);
      assertThat(cache).containsEntry(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* ---------------- replace conditionally -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.valueOf(1).asFuture();
    assertThrows(NullPointerException.class, () -> cache.asMap().replace(null, value, value));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullOldValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.valueOf(1).asFuture();
    assertThrows(NullPointerException.class, () ->
        cache.asMap().replace(Int.valueOf(1), null, value));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullNewValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.futureOf(1);
    assertThrows(NullPointerException.class, () ->
        cache.asMap().replace(Int.valueOf(1), value, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndOldValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.futureOf(1);
    assertThrows(NullPointerException.class, () -> cache.asMap().replace(null, null, value));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndNewValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var value = Int.futureOf(1);
    assertThrows(NullPointerException.class, () -> cache.asMap().replace(null, value, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullOldAndNewValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().replace(Int.valueOf(1), null, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_nullKeyAndValues(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().replace(null, null, null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceConditionally_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().replace(context.absentKey(), value, value)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_wrongOldValue(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      assertThat(cache.asMap().replace(key, value, value)).isFalse();
      assertThat(cache).containsEntry(key, oldValue);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var newValue = cache.asMap().get(key).thenApply(val -> intern(new Int(val)));
      assertThat(cache.asMap().replace(key, oldValue, newValue)).isTrue();
      assertThat(cache).containsEntry(key, newValue);
      replaced.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void replaceConditionally_sameInstance(AsyncCache<Int, Int> cache, CacheContext context) {
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
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var oldValue = cache.asMap().get(key);
      var value = context.absentValue().asFuture();
      assertThat(cache.asMap().replace(key, oldValue, value)).isTrue();
      assertThat(cache).containsEntry(key, value);
      replaced.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* ---------------- replaceAll -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().replaceAll(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void replaceAll_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().replaceAll((key, value) -> null));
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
        .contains(context.original()).exclusively();
  }

  /* ---------------- computeIfAbsent -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfAbsent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().computeIfAbsent(null, key -> key.negate().asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfAbsent_nullMappingFunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().computeIfAbsent(context.absentKey(), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfAbsent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> null)).isNull();
    assertThat(cache).hasSize(context.initialSize());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
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

  @CacheSpec
  @Test(dataProvider = "caches")
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
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfAbsent_error(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        cache.asMap().computeIfAbsent(context.absentKey(), key -> { throw expected; }));

    assertThat(actual).isSameInstanceAs(expected);
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);
    var future = cache.asMap().computeIfAbsent(context.absentKey(), Int::asFuture);
    assertThat(future).succeedsWith(context.absentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
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
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfAbsent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    assertThat(cache.asMap().computeIfAbsent(context.absentKey(), key -> value)).isEqualTo(value);
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);

    assertThat(cache).containsEntry(context.absentKey(), value);
    assertThat(cache).hasSize(1 + context.original().size());
  }

  /* ---------------- computeIfPresent -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfPresent_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().computeIfPresent(null, (key, value) -> key.negate().asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfPresent_nullMappingFunction(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().computeIfPresent(Int.valueOf(1), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      cache.asMap().computeIfPresent(key, (k, v) -> null);
      removed.put(key, context.original().get(key));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
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
    assertThrows(StackOverflowError.class, () ->
        cache.asMap().computeIfPresent(context.firstKey(), mappingFunction));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
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
    assertThrows(StackOverflowError.class, () ->
        cache.asMap().computeIfPresent(context.firstKey(), mappingFunction));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_error(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        cache.asMap().computeIfPresent(context.firstKey(), (key, value) -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);

    var future = cache.asMap().computeIfPresent(
        context.firstKey(), (k, v) -> k.negate().asFuture());
    assertThat(future).succeedsWith(context.firstKey().negate());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void computeIfPresent_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().computeIfPresent(context.absentKey(), (key, value) -> value)).isNull();
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present_sameValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, CompletableFuture<Int>>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = intern(new Int(context.original().get(key))).asFuture();
      assertThat(cache.asMap().computeIfPresent(key, (k, v) -> value)).isSameInstanceAs(value);
      replaced.put(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache.asMap()).containsAtLeastEntriesIn(replaced);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Maps.transformValues(replaced, CompletableFuture::join))
        .exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present_sameInstance(
      AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().computeIfPresent(key, (k, v) -> value)).isSameInstanceAs(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);
    assertThat(context).removalNotifications().isEmpty();
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void computeIfPresent_present_differentValue(
      AsyncCache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = key.asFuture();
      replaced.put(key, context.original().get(key));
      assertThat(cache.asMap().computeIfPresent(key, (k, v) -> value)).isEqualTo(value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, key);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* ---------------- compute -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void compute_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().compute(null, (key, value) -> key.negate().asFuture()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void compute_nullMappingFunction(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().compute(Int.valueOf(1), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.asMap().compute(key, (k, v) -> null)).isNull();
      removed.put(key, context.original().get(key));
    }

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @CacheSpec
  @Test(dataProvider = "caches")
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
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
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void compute_error(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        cache.asMap().compute(context.absentKey(), (key, value) -> { throw expected; }));

    assertThat(expected).isSameInstanceAs(actual);
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);

    var future = context.absentKey().negate().asFuture();
    assertThat(cache.asMap().compute(context.absentKey(), (k, v) -> future)).isEqualTo(future);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void compute_absent_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().compute(context.absentKey(), (key, value) -> null)).isNull();
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
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
    var replaced = new HashMap<Int, CompletableFuture<Int>>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = intern(new Int(context.original().get(key))).asFuture();
      assertThat(cache.asMap().compute(key, (k, v) -> value)).isSameInstanceAs(value);
      replaced.put(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache.asMap()).containsAtLeastEntriesIn(replaced);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Maps.transformValues(replaced, CompletableFuture::join))
        .exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void compute_sameInstance(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().compute(key, (k, v) -> value)).isSameInstanceAs(value);
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
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = key.asFuture();
      assertThat(cache.asMap().compute(key, (k, v) -> value)).isEqualTo(value);
      replaced.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, key);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* ---------------- merge -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void merge_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().merge(null, Int.valueOf(-1).asFuture(), (oldValue, value) -> value));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void merge_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().merge(Int.valueOf(1), null, (oldValue, value) -> null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void merge_nullMappingFunction(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().merge(Int.valueOf(1), Int.valueOf(1).asFuture(), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      assertThat(cache.asMap().merge(key, value, (oldValue, v) -> null)).isNull();
      removed.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
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
    assertThrows(StackOverflowError.class, () -> {
      cache.asMap().merge(context.firstKey(),
          cache.asMap().get(context.firstKey()), mappingFunction);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void merge_error(AsyncCache<Int, Int> cache, CacheContext context) {
    var key = context.firstKey();
    var future = key.asFuture();
    var expected = new IllegalStateException();
    var actual = assertThrows(IllegalStateException.class, () ->
        cache.asMap().merge(key, future, (oldValue, value) -> { throw expected; }));
    assertThat(actual).isSameInstanceAs(expected);
    assertThat(cache).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(0).misses(0).success(0).failures(0);

    var result = cache.asMap().merge(context.firstKey(), future,
        (oldValue, value) -> context.absentValue().asFuture());
    assertThat(result).succeedsWith(context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
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
    var replaced = new HashMap<Int, CompletableFuture<Int>>();
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key).thenApply(Int::new);
      var result = cache.asMap().merge(key, key.negate().asFuture(), (oldValue, v) -> value);
      assertThat(result).isSameInstanceAs(value);
      replaced.put(key, value);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache.asMap()).containsAtLeastEntriesIn(replaced);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Maps.transformValues(replaced, CompletableFuture::join))
        .exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void merge_sameInstance(AsyncCache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var value = cache.asMap().get(key);
      var result = cache.asMap().merge(key, key.negate().asFuture(), (oldValue, v) -> value);
      assertThat(result).isSameInstanceAs(value);
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
    var replaced = new HashMap<Int, Int>();
    Int mergedValue = context.absentValue();
    for (Int key : context.firstMiddleLastKeys()) {
      var result = cache.asMap().merge(key, key.asFuture(),
          (oldValue, v) -> mergedValue.asFuture());
      assertThat(result).succeedsWith(mergedValue);
      replaced.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(context).stats().hits(0).misses(0).success(count).failures(0);

    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache).containsEntry(key, mergedValue);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  /* ---------------- equals / hashCode -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void equals_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().equals(null)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @SuppressWarnings("SelfEquals")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void equals_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().equals(cache.asMap())).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void equals(AsyncCache<Int, Int> cache, CacheContext context) {
    var map = Map.copyOf(cache.asMap());
    assertThat(cache.asMap().equals(map)).isTrue();
    assertThat(map.equals(cache.asMap())).isTrue();

    var absent = Maps.asMap(context.absentKeys(), CompletableFuture::completedFuture);
    assertThat(cache.asMap().equals(absent)).isFalse();
    assertThat(absent.equals(cache.asMap())).isFalse();

    if (!cache.asMap().isEmpty()) {
      var other = Maps.asMap(cache.asMap().keySet(), CompletableFuture::completedFuture);
      assertThat(cache.asMap().equals(other)).isFalse();
      assertThat(other.equals(cache.asMap())).isFalse();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void hashCode(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode()).isEqualTo(Map.copyOf(cache.asMap()).hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void hashCode_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().hashCode()).isEqualTo(cache.asMap().hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void equalsAndHashCodeFail_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    var other = Map.of(Int.valueOf(1), Int.futureOf(-1),
        Int.valueOf(2), Int.futureOf(-2), Int.valueOf(3), Int.futureOf(-3));
    assertThat(cache.asMap().equals(other)).isFalse();
    assertThat(other.equals(cache.asMap())).isFalse();
    assertThat(cache.asMap().hashCode()).isNotEqualTo(other.hashCode());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void equalsAndHashCodeFail_present(AsyncCache<Int, Int> cache, CacheContext context) {
    var other = Map.of(Int.valueOf(1), Int.futureOf(-1),
        Int.valueOf(2), Int.futureOf(-2), Int.valueOf(3), Int.futureOf(-3));
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
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void toString(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(parseToString(cache.asMap()))
        .containsExactlyEntriesIn(parseToString(Map.copyOf(cache.asMap())));
  }

  private static Map<String, String> parseToString(Map<Int, CompletableFuture<Int>> map) {
    return Splitter.on(',').trimResults().omitEmptyStrings().withKeyValueSeparator("=")
        .split(map.toString().replaceAll("\\{|\\}", ""));
  }

  /* ---------------- Key Set -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keySet_toArray_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().keySet().toArray((Int[]) null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keySet_toArray(AsyncCache<Int, Int> cache, CacheContext context) {
    var ints = cache.asMap().keySet().toArray(new Int[0]);
    assertThat(ints).asList().containsExactlyElementsIn(context.original().keySet());

    var array = cache.asMap().keySet().toArray();
    assertThat(array).asList().containsExactlyElementsIn(context.original().keySet());

    var func = cache.asMap().keySet().toArray(Int[]::new);
    assertThat(func).asList().containsExactlyElementsIn(context.original().keySet());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keySet_contains_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().contains(context.absentKey())).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keySet_contains_present(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().contains(context.firstKey())).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keySet_whenEmpty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet()).isExhaustivelyEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_addNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () ->
        cache.asMap().keySet().add(Int.valueOf(1)));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().clear();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().keySet().removeAll(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeAll_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().removeAll(Arrays.asList((Object) null));
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeAll_none_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().removeAll(Set.of())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeAll_none_populated(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().removeAll(Set.of(context.absentKey()))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_removeAll_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new HashMap<>(context.original());
    expected.keySet().removeAll(context.firstMiddleLastKeys());
    assertThat(cache.asMap().keySet().removeAll(context.firstMiddleLastKeys())).isTrue();
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Maps.asMap(context.firstMiddleLastKeys(), context.original()::get)).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_removeAll_all(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().removeAll(context.original().keySet())).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_removeAll_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().removeAll(cache.asMap().keySet())).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, implementation = Implementation.Caffeine)
  public void keySet_removeAll_byCollection(AsyncCache<Int, Int> cache, CacheContext context) {
    var delegate = Sets.union(context.original().keySet(), context.absentKeys());
    var keys = Mockito.mock(Collection.class);
    when(keys.iterator()).thenReturn(delegate.iterator());

    assertThat(cache.asMap().keySet().removeAll(keys)).isTrue();
    verify(keys).iterator();
    verifyNoMoreInteractions(keys);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, implementation = Implementation.Caffeine)
  public void keySet_removeAll_bySet(AsyncCache<Int, Int> cache, CacheContext context) {
    var delegate = Sets.union(context.original().keySet(), context.absentKeys());
    var keys = Mockito.mock(Set.class);
    when(keys.size()).thenReturn(delegate.size());
    when(keys.contains(any())).thenAnswer(invocation ->
        delegate.contains(invocation.getArgument(0)));

    assertThat(cache.asMap().keySet().removeAll(keys)).isTrue();
    verify(keys).size();
    verify(keys, times(context.original().size())).contains(any());
    verifyNoMoreInteractions(keys);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_remove_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().keySet().remove(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_remove_none(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().remove(context.absentKey())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().remove(context.firstKey())).isTrue();
    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeIf_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().keySet().removeIf(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeIf_none(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().removeIf(v -> false)).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeIf_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    Predicate<Int> isEven = key -> (key.intValue() % 2) == 0;
    boolean hasEven = cache.asMap().keySet().stream().anyMatch(isEven);

    boolean removedIfEven = cache.asMap().keySet().removeIf(isEven);
    assertThat(cache.asMap().keySet().stream().anyMatch(isEven)).isFalse();
    assertThat(removedIfEven).isEqualTo(hasEven);
    if (removedIfEven) {
      assertThat(cache).hasSizeLessThan(context.initialSize());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_removeIf_all(AsyncCache<Int, Int> cache, CacheContext context) {
    if (context.population() == Population.EMPTY) {
      assertThat(cache.asMap().keySet().removeIf(v -> true)).isFalse();
      assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
      assertThat(context).removalNotifications().isEmpty();
    } else {
      assertThat(cache.asMap().keySet().removeIf(v -> true)).isTrue();
      assertThat(cache).isEmpty();
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(context.original());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_retainAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().keySet().retainAll(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_retainAll_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().keySet().retainAll(Arrays.asList((Object) null));
    assertThat(cache).isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySet_retainAll_none_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    boolean modified = cache.asMap().keySet().retainAll(Set.of());
    assertThat(cache).isEmpty();
    if (context.original().isEmpty()) {
      assertThat(modified).isFalse();
      assertThat(context).removalNotifications().isEmpty();
    } else {
      assertThat(modified).isTrue();
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(context.original()).exclusively();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_retainAll_none_populated(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().retainAll(Set.of(context.absentKey()))).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_retainAll_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new HashMap<>(cache.asMap());
    expected.keySet().removeAll(context.firstMiddleLastKeys());

    assertThat(cache.asMap().keySet().retainAll(expected.keySet())).isTrue();
    assertThat(cache.asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Maps.asMap(context.firstMiddleLastKeys(), context.original()::get)).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_retainAll_all(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().retainAll(context.original().keySet())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void keySet_retainAll_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().keySet().retainAll(cache.asMap().keySet())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
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
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keyIterator_noElement(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.asMap().keySet().iterator().remove());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void keyIterator_noMoreElements(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NoSuchElementException.class, () -> cache.asMap().keySet().iterator().next());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void keySpliterator_forEachRemaining_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().keySet().spliterator().forEachRemaining(null));
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
  @Test(dataProvider = "caches")
  public void keySpliterator_tryAdvance_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().keySet().spliterator().tryAdvance(null));
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
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void values_toArray_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().values().toArray((CompletableFuture<Int>[]) null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void values_toArray(AsyncCache<Int, Int> cache, CacheContext context) {
    var futures = cache.asMap().values().toArray(new CompletableFuture<?>[0]);
    var values1 = Arrays.stream(futures).map(CompletableFuture::join).collect(toImmutableList());
    assertThat(values1).containsExactlyElementsIn(context.original().values());

    var array = cache.asMap().values().toArray(new CompletableFuture<?>[0]);
    var values2 = Arrays.stream(array).map(CompletableFuture::join).collect(toImmutableList());
    assertThat(values2).containsExactlyElementsIn(context.original().values());

    var func = cache.asMap().values().toArray(CompletableFuture<?>[]::new);
    var values3 = Arrays.stream(func).map(CompletableFuture::join).collect(toImmutableList());
    assertThat(values3).containsExactlyElementsIn(context.original().values());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void values_contains_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().contains(context.absentValue().asFuture())).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void values_contains_present(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().contains(cache.asMap().get(context.firstKey()))).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void values_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values()).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void values_addNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () ->
        cache.asMap().values().add(Int.valueOf(1).asFuture()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().clear();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().values().removeAll(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeAll_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().removeAll(Arrays.asList((Object) null));
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeAll_none_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().removeAll(Set.of())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeAll_none_populated(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().removeAll(
        Set.of(context.absentValue().asFuture()))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_removeAll_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new HashMap<>(context.original());
    expected.keySet().removeAll(context.firstMiddleLastKeys());
    var removed = Maps.asMap(context.firstMiddleLastKeys(), cache.asMap()::get);

    assertThat(cache.asMap().values().removeAll(removed.values())).isTrue();
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Maps.asMap(context.firstMiddleLastKeys(), context.original()::get)).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_removeAll_all(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().removeAll(List.copyOf(cache.asMap().values()))).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_removeAll_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().removeAll(cache.asMap().values())).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_remove_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().remove(null)).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_remove_none(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().remove(context.absentValue().asFuture())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = cache.asMap().get(context.firstKey());
    assertThat(cache.asMap().values().remove(future)).isTrue();
    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.firstKey(), future.join()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_remove_once(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new HashMap<>(context.original());
    var future = context.absentValue().asFuture();
    for (Int key : context.firstMiddleLastKeys()) {
      expected.put(key, context.absentValue());
      cache.put(key, future);
    }
    context.clearRemovalNotifications();

    assertThat(cache.asMap().values().remove(future)).isTrue();
    var removedKey = context.firstMiddleLastKeys().stream()
        .filter(key -> !cache.asMap().containsKey(key))
        .findAny().orElseThrow();
    expected.remove(removedKey);
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removedKey, context.absentValue()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().values().removeIf(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf_none(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().removeIf(v -> false)).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    Predicate<CompletableFuture<Int>> isEven = value -> (value.join().intValue() % 2) == 0;
    boolean hasEven = cache.asMap().values().stream().anyMatch(isEven);

    boolean removedIfEven = cache.asMap().values().removeIf(isEven);
    assertThat(cache.asMap().values().stream().anyMatch(isEven)).isFalse();
    assertThat(removedIfEven).isEqualTo(hasEven);
    if (removedIfEven) {
      assertThat(cache).hasSizeLessThan(context.original().size());
    }
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_removeIf_all(AsyncCache<Int, Int> cache, CacheContext context) {
    if (context.population() == Population.EMPTY) {
      assertThat(cache.asMap().values().removeIf(v -> true)).isFalse();
      assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
      assertThat(context).removalNotifications().isEmpty();
    } else {
      assertThat(cache.asMap().values().removeIf(v -> true)).isTrue();
      assertThat(cache).isEmpty();
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(context.original());
    }
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
  public void values_retainAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().values().retainAll(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_retainAll_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().values().retainAll(Arrays.asList((Object) null));
    assertThat(cache).isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void values_retainAll_none_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    boolean modified = cache.asMap().values().retainAll(Set.of());
    assertThat(cache).isEmpty();
    if (context.original().isEmpty()) {
      assertThat(modified).isFalse();
      assertThat(context).removalNotifications().isEmpty();
    } else {
      assertThat(modified).isTrue();
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(context.original()).exclusively();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_retainAll_none_populated(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().retainAll(Set.of(context.absentValue().asFuture()))).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_retainAll_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new HashMap<>(cache.asMap());
    expected.keySet().removeAll(context.firstMiddleLastKeys());

    assertThat(cache.asMap().values().retainAll(expected.values())).isTrue();
    assertThat(cache.asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Maps.asMap(context.firstMiddleLastKeys(), context.original()::get)).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_retainAll_all(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().retainAll(List.copyOf(cache.asMap().values()))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void values_retainAll_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().retainAll(cache.asMap().values())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
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
        .contains(context.original()).exclusively();
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
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void valueIterator_noElement(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.asMap().values().iterator().remove());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void valueIterator_noMoreElements(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NoSuchElementException.class, () -> cache.asMap().values().iterator().next());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void valueSpliterator_forEachRemaining_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().values().spliterator().forEachRemaining(null));
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
  @Test(dataProvider = "caches")
  public void valueSpliterator_tryAdvance_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().values().spliterator().tryAdvance(null));
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
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_toArray_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().entrySet().toArray((Map.Entry<?, ?>[]) null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_toArray(AsyncCache<Int, Int> cache, CacheContext context) {
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
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet()).isExhaustivelyEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_contains_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(null, cache.asMap().get(context.firstKey()));
    assertThat(cache.asMap().entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_contains_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(context.firstKey(), null);
    assertThat(cache.asMap().entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_contains_absent(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = Map.entry(context.absentKey(), context.absentValue().asFuture());
    assertThat(cache.asMap().entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entrySet_contains_present(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = Map.entry(context.firstKey(), cache.asMap().get(context.firstKey()));
    assertThat(cache.asMap().entrySet().contains(entry)).isTrue();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.DISABLED)
  public void entrySet_addIsNotSupported(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () ->
        cache.asMap().entrySet().add(Map.entry(Int.valueOf(1), Int.valueOf(2).asFuture())));
    assertThat(cache).isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_clear(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().clear();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().entrySet().removeAll(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeAll_nullEntry(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().removeAll(Arrays.asList((Object) null));
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeAll_none_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().removeAll(Set.of())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeAll_none_populated(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().removeAll(
        Set.of(Map.entry(context.absentKey(), context.absentKey().asFuture())))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_removeAll_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var removed = Maps.asMap(context.firstMiddleLastKeys(), cache.asMap()::get);
    var expected = new HashMap<>(context.original());
    expected.keySet().removeAll(removed.keySet());

    assertThat(cache.asMap().entrySet().removeAll(removed.entrySet())).isTrue();
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Maps.asMap(context.firstMiddleLastKeys(), context.original()::get)).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_removeAll_all(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().removeAll(Map.copyOf(cache.asMap()).entrySet())).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_removeAll_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().removeAll(cache.asMap().entrySet())).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, implementation = Implementation.Caffeine)
  public void entrySet_removeAll_byCollection(AsyncCache<Int, Int> cache, CacheContext context) {
    var delegate = Sets.union(cache.asMap().entrySet(),
        Maps.transformValues(context.absent(), Int::asFuture).entrySet());
    var entries = Mockito.mock(Collection.class);
    when(entries.iterator()).thenReturn(delegate.iterator());

    assertThat(cache.asMap().entrySet().removeAll(entries)).isTrue();
    verify(entries).iterator();
    verifyNoMoreInteractions(entries);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, implementation = Implementation.Caffeine)
  public void entrySet_removeAll_bySet(AsyncCache<Int, Int> cache, CacheContext context) {
    var delegate = Sets.union(cache.asMap().entrySet(),
        Maps.transformValues(context.absent(), Int::asFuture).entrySet());
    var entries = Mockito.mock(Set.class);
    when(entries.size()).thenReturn(delegate.size());
    when(entries.contains(any())).thenAnswer(invocation ->
        delegate.contains(invocation.getArgument(0)));

    assertThat(cache.asMap().entrySet().removeAll(entries)).isTrue();
    verify(entries).size();
    verify(entries, times(context.original().size())).contains(any());
    verifyNoMoreInteractions(entries);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_remove_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().values().remove(null)).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @SuppressWarnings("MapEntry")
  @Test(dataProvider = "caches")
  public void entrySet_remove_nullKey(AsyncCache<Int, Int> cache, CacheContext context) {
    var future = Iterables.getFirst(cache.asMap().values(), context.absentValue().asFuture());
    assertThat(cache.asMap().entrySet().remove(Maps.immutableEntry(null, future))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @SuppressWarnings("MapEntry")
  @Test(dataProvider = "caches")
  public void entrySet_remove_nullValue(AsyncCache<Int, Int> cache, CacheContext context) {
    var key = Iterables.getFirst(context.original().keySet(), context.absentKey());
    assertThat(cache.asMap().entrySet().remove(Maps.immutableEntry(key, null))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @SuppressWarnings("MapEntry")
  @Test(dataProvider = "caches")
  public void entrySet_remove_nullKeyValue(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().remove(Maps.immutableEntry(null, null))).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_remove_none(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = Map.entry(context.absentKey(), context.absentValue().asFuture());
    assertThat(cache.asMap().entrySet().remove(entry)).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_remove(AsyncCache<Int, Int> cache, CacheContext context) {
    var entry = Map.entry(context.firstKey(), cache.asMap().get(context.firstKey()));
    assertThat(cache.asMap().entrySet().remove(entry)).isTrue();

    var expected = new HashMap<>(context.original());
    expected.remove(context.firstKey());
    assertThat(cache.synchronous().asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.firstKey(), context.original().get(context.firstKey())).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeIf_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().entrySet().removeIf(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeIf_none(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().removeIf(v -> false)).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_removeIf_partial(AsyncCache<Int, Int> cache, CacheContext context) {
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
  public void entrySet_removeIf_all(AsyncCache<Int, Int> cache, CacheContext context) {
    if (context.population() == Population.EMPTY) {
      assertThat(cache.asMap().entrySet().removeIf(v -> true)).isFalse();
      assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
      assertThat(context).removalNotifications().isEmpty();
    } else {
      assertThat(cache.asMap().entrySet().removeIf(v -> true)).isTrue();
      assertThat(cache).isEmpty();
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(context.original());
    }
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
  public void entrySet_retainAll_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.asMap().entrySet().retainAll(null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_retainAll_nullEntry(AsyncCache<Int, Int> cache, CacheContext context) {
    cache.asMap().entrySet().retainAll(Arrays.asList((Object) null));
    assertThat(cache).isEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySet_retainAll_none_empty(AsyncCache<Int, Int> cache, CacheContext context) {
    boolean modified = cache.asMap().entrySet().retainAll(Set.of());
    assertThat(cache).isEmpty();
    if (context.original().isEmpty()) {
      assertThat(modified).isFalse();
      assertThat(context).removalNotifications().isEmpty();
    } else {
      assertThat(modified).isTrue();
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(context.original()).exclusively();
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_retainAll_none_populated(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().retainAll(
        Set.of(Map.entry(context.absentKey(), context.absentValue().asFuture())))).isTrue();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_retainAll_partial(AsyncCache<Int, Int> cache, CacheContext context) {
    var expected = new HashMap<>(cache.asMap());
    expected.keySet().removeAll(context.firstMiddleLastKeys());

    assertThat(cache.asMap().entrySet().retainAll(expected.entrySet())).isTrue();
    assertThat(cache.asMap()).isEqualTo(expected);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Maps.asMap(context.firstMiddleLastKeys(), context.original()::get)).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_retainAll_all(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().retainAll(Map.copyOf(cache.asMap()).entrySet())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL)
  public void entrySet_retainAll_self(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.asMap().entrySet().retainAll(cache.asMap().entrySet())).isFalse();
    assertThat(cache.synchronous().asMap()).isEqualTo(context.original());
    assertThat(context).removalNotifications().isEmpty();
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
        .contains(context.original()).exclusively();
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
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entryIterator_noElement(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () -> cache.asMap().entrySet().iterator().remove());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY,
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void entryIterator_noMoreElements(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NoSuchElementException.class, () -> cache.asMap().entrySet().iterator().next());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void entrySpliterator_forEachRemaining_null(
      AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().entrySet().spliterator().forEachRemaining(null));
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
  @Test(dataProvider = "caches")
  public void entrySpliterator_tryAdvance_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().entrySet().spliterator().tryAdvance(null));
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
    var oldValue = entry.getValue().join();
    var value = Int.valueOf(3).asFuture();

    entry.setValue(value);
    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache).containsEntry(entry.getKey(), value);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(entry.getKey(), oldValue).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void writeThroughEntry_null(AsyncCache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.asMap().entrySet().iterator().next().setValue(null));
  }

  // writeThroughEntry_serialize() - CompletableFuture is not serializable
}
