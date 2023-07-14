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
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.function.Function.identity;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.WARN;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.apache.commons.lang3.tuple.Triple;
import org.mockito.stubbing.Answer;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.Policy.FixedExpiration;
import com.github.benmanes.caffeine.cache.Policy.FixedRefresh;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.SnapshotEntry.CompleteEntry;
import com.github.benmanes.caffeine.cache.SnapshotEntry.ExpirableEntry;
import com.github.benmanes.caffeine.cache.SnapshotEntry.ExpirableWeightedEntry;
import com.github.benmanes.caffeine.cache.SnapshotEntry.RefreshableExpirableEntry;
import com.github.benmanes.caffeine.cache.SnapshotEntry.WeightedEntry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.google.common.testing.EqualsTester;
import com.google.common.testing.NullPointerTester;

/**
 * The test cases for the {@link Cache} interface that simulate the most generic usages. These
 * tests do not validate eviction management, concurrency behavior, or the {@link Cache#asMap()}
 * view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckNoEvictions @CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CacheTest {

  /* --------------- size --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void estimatedSize(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.estimatedSize()).isEqualTo(context.initialSize());
  }

  /* --------------- getIfPresent --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresent_nullKey(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.getIfPresent(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresent_absent(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey())).isNull();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey())).isNotNull();
    assertThat(cache.getIfPresent(context.middleKey())).isNotNull();
    assertThat(cache.getIfPresent(context.lastKey())).isNotNull();
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- get --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void get_nullKey(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(null, identity()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void get_nullLoader(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(context.absentKey(), null));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void get_nullKeyAndLoader(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.get(null, null));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent_null(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey(), k -> null)).isNull();
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent_throwsException(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () ->
        cache.get(context.absentKey(), key -> { throw new IllegalStateException(); }));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent_throwsError(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(UnknownError.class, () ->
        cache.get(context.absentKey(), key -> { throw new UnknownError(); }));
    assertThat(context).stats().hits(0).misses(1).success(0).failures(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    Int value = cache.get(key, k -> context.absentValue());
    assertThat(value).isEqualTo(context.absentValue());
    assertThat(context).stats().hits(0).misses(1).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(Cache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader))
        .isEqualTo(context.original().get(context.firstKey()));
    assertThat(cache.get(context.middleKey(), loader))
        .isEqualTo(context.original().get(context.middleKey()));
    assertThat(cache.get(context.lastKey(), loader))
        .isEqualTo(context.original().get(context.lastKey()));
    assertThat(context).stats().hits(3).misses(0).success(0).failures(0);
  }

  /* --------------- getAllPresent --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_iterable_null(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.getAllPresent(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_iterable_nullKey(Cache<Int, Int> cache, CacheContext context) {
    List<Int> keys = Collections.singletonList(null);
    assertThrows(NullPointerException.class, () -> cache.getAllPresent(keys));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_iterable_empty(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.getAllPresent(List.of())).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_absent(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAllPresent(context.absentKeys());
    assertThat(result).isExhaustivelyEmpty();

    int count = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(count).success(0).failures(0);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAllPresent_immutable(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAllPresent(context.firstMiddleLastKeys());
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_nullLookup(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAllPresent(context.firstMiddleLastKeys());
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_present_partial(Cache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.original().get(context.firstKey()));
    expect.put(context.middleKey(), context.original().get(context.middleKey()));
    expect.put(context.lastKey(), context.original().get(context.lastKey()));
    var result = cache.getAllPresent(expect.keySet());
    assertThat(result).containsExactlyEntriesIn(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_present_full(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAllPresent(context.original().keySet());
    assertThat(result).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(result.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_duplicates(Cache<Int, Int> cache, CacheContext context) {
    var keys = Iterables.concat(
        context.absentKeys(), context.absentKeys(),
        context.original().keySet(), context.original().keySet());
    var result = cache.getAllPresent(keys);

    long hits, misses;
    if (context.isGuava()) {
      // Guava does not skip duplicates
      hits = 2L * context.initialSize();
      misses = 2L * context.absentKeys().size();
    } else {
      hits = context.initialSize();
      misses = context.absentKeys().size();
    }
    assertThat(result).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(hits).misses(misses).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAllPresent_ordered(Cache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = cache.getAllPresent(keys);
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void getAllPresent_jdk8186171(Cache<Object, Int> cache, CacheContext context) {
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }

    var keys = intern(new ArrayList<Key>());
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(new Key());
    }

    Key key = Iterables.getLast(keys);
    Int value = context.absentValue();
    cache.put(key, value);

    var result = cache.getAllPresent(keys);
    assertThat(result).containsExactly(key, value);
  }

  /* --------------- getAll --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_null(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () ->
        cache.getAll(null, keys -> { throw new AssertionError(); }));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_nullKey(Cache<Int, Int> cache, CacheContext context) {
    List<Int> keys = Collections.singletonList(null);
    assertThrows(NullPointerException.class, () ->
        cache.getAll(keys, k -> { throw new AssertionError(); }));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_iterable_empty(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(List.of(), keys -> { throw new AssertionError(); });
    assertThat(result).isExhaustivelyEmpty();
    assertThat(context).stats().hits(0).misses(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_function_null(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.getAll(context.absentKeys(), null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_function_nullValue(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class,
        () -> cache.getAll(context.absentKeys(), keys -> null));
    int misses = context.loader().isBulk() ? 1 : context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_immutable_keys(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () -> {
      cache.getAll(context.absentKeys(), keys -> {
        throw assertThrows(UnsupportedOperationException.class, keys::clear);
      });
    });
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_immutable_result(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys(), bulkMappingFunction());
    assertThrows(UnsupportedOperationException.class, result::clear);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_nullLookup(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.firstMiddleLastKeys(), bulkMappingFunction());
    assertThat(result.containsValue(null)).isFalse();
    assertThat(result.containsKey(null)).isFalse();
    assertThat(result.get(null)).isNull();
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_absent_throwsException(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(IllegalStateException.class, () ->
        cache.getAll(context.absentKeys(), keys -> { throw new IllegalStateException(); }));
    int misses = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_function_throwsError(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(UnknownError.class, () ->
        cache.getAll(context.absentKeys(), keys -> { throw new UnknownError(); }));
    int misses = context.absentKeys().size();
    assertThat(context).stats().hits(0).misses(misses).success(0).failures(1);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getAll_absent(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys(), bulkMappingFunction());

    int count = context.absentKeys().size();
    assertThat(result).hasSize(count);
    assertThat(context).stats().hits(0).misses(count).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_partial(Cache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet(), bulkMappingFunction());

    assertThat(result).containsExactlyEntriesIn(expect);
    assertThat(context).stats().hits(expect.size()).misses(0).success(0).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_full(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.original().keySet(), bulkMappingFunction());
    assertThat(result).containsExactlyEntriesIn(context.original());
    assertThat(context).stats().hits(result.size()).misses(0).success(0).failures(0);
  }

  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  @Test(dataProvider = "caches")
  public void getAll_exceeds(Cache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(Set.of(context.absentKey()), keys -> context.absent());

    var expected = new ImmutableMap.Builder<Int, Int>()
        .putAll(context.original())
        .putAll(context.absent())
        .build();
    assertThat(cache).containsExactlyEntriesIn(expected);
    assertThat(context).stats().hits(0).misses(result.size()).success(1).failures(0);
    assertThat(result).containsExactlyEntriesIn(Map.of(context.absentKey(), context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_different(Cache<Int, Int> cache, CacheContext context) {
    var actual = Maps.uniqueIndex(context.absentKeys(), key -> intern(key.negate()));
    var result = cache.getAll(context.absentKeys(), keys -> actual);

    assertThat(result).isEmpty();
    assertThat(cache.asMap()).containsAtLeastEntriesIn(actual);
    assertThat(cache).hasSize(context.initialSize() + actual.size());
    assertThat(context).stats().hits(0).misses(actual.size()).success(1).failures(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_duplicates(Cache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys, bulkMappingFunction());
    assertThat(result).containsExactlyKeys(keys);

    long hits, misses;
    if (context.isGuava()) {
      // Guava does not skip duplicates
      hits = 2L * context.initialSize();
      misses = 2L * absentKeys.size();
    } else {
      hits = context.initialSize();
      misses = absentKeys.size();
    }
    assertThat(context).stats().hits(hits).misses(misses);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_absent(Cache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, bulkMappingFunction());
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_partial(Cache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, bulkMappingFunction());
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_present(Cache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = cache.getAll(keys, bulkMappingFunction());
    assertThat(result).containsExactlyKeys(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getAll_present_ordered_exceeds(Cache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = List.copyOf(cache.getAll(keys, bulkMappingFunction()).keySet());
    assertThat(result.subList(0, keys.size())).containsExactlyElementsIn(keys).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void getAll_jdk8186171(CacheContext context) {
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }
    Cache<Object, Int> cache = context.build(key -> null);

    var keys = new ArrayList<Key>();
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(intern(new Key()));
    }
    Key key = Iterables.getLast(keys);
    Int value = context.absentValue();
    cache.put(key, value);

    var result = cache.getAll(keys, keysToLoad -> Map.of());
    assertThat(result.values()).doesNotContain(null);
    assertThat(result).containsExactly(key, value);
  }

  static Function<Set<? extends Int>, ImmutableMap<Int, Int>> bulkMappingFunction() {
    return keys -> {
      ImmutableMap<Int, Int> result = keys.stream()
          .collect(toImmutableMap(identity(), Int::negate));
      CacheContext.interner().putAll(result);
      return result;
    };
  }

  /* --------------- put --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_insert(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    assertThat(cache).hasSize(context.initialSize() + 1);
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameValue(Cache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      cache.put(key, intern(new Int(value)));
      assertThat(cache).containsEntry(key, value);
      replaced.put(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameInstance(Cache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      Int value = context.original().get(key);
      cache.put(key, value);
      assertThat(cache).containsEntry(key, value);
      replaced.put(key, value);
    }
    assertThat(cache).hasSize(context.initialSize());

    if (context.isGuava()) {
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(replaced).exclusively();
    } else {
      assertThat(context).removalNotifications().isEmpty();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(Cache<Int, Int> cache, CacheContext context) {
    var replaced = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      cache.put(key, context.absentValue());
      assertThat(cache).containsEntry(key, context.absentValue());
      replaced.put(key, context.original().get(key));
    }
    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(replaced).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullKey(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.put(null, context.absentValue()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullValue(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.put(context.absentKey(), null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void put_nullKeyAndValue(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.put(null, null));
  }

  /* --------------- put all --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putAll_insert(Cache<Int, Int> cache, CacheContext context) {
    int startKey = context.original().size() + 1;
    var entries = IntStream
        .range(startKey, 100 + startKey)
        .mapToObj(Int::valueOf)
        .collect(toImmutableMap(identity(), Int::negate));
    cache.putAll(entries);
    assertThat(cache).hasSize(100 + context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void putAll_replace(Cache<Int, Int> cache, CacheContext context) {
    var entries = new HashMap<>(context.original());
    entries.replaceAll((key, value) -> intern(value.add(1)));
    cache.putAll(entries);
    assertThat(cache).containsExactlyEntriesIn(entries);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.original()).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL })
  public void putAll_mixed(Cache<Int, Int> cache, CacheContext context) {
    var entries = new HashMap<Int, Int>();
    var replaced = new HashMap<Int, Int>();
    context.original().forEach((key, value) -> {
      if ((key.intValue() % 2) == 0) {
        value = intern(value.add(1));
        replaced.put(key, value);
      }
      entries.put(key, value);
    });

    cache.putAll(entries);
    assertThat(cache).containsExactlyEntriesIn(entries);
    var expect = context.isGuava() ? entries : replaced;
    for (var entry : expect.entrySet()) {
      entry.setValue(context.original().get(entry.getKey()));
    }
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(expect).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putAll_empty(Cache<Int, Int> cache, CacheContext context) {
    cache.putAll(Map.of());
    assertThat(cache).hasSize(context.initialSize());
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void putAll_null(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.putAll(null));
  }

  /* --------------- invalidate --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void invalidate_absent(Cache<Int, Int> cache, CacheContext context) {
    cache.invalidate(context.absentKey());
    assertThat(cache).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Cache<Int, Int> cache, CacheContext context) {
    var removed = new HashMap<Int, Int>();
    for (Int key : context.firstMiddleLastKeys()) {
      cache.invalidate(key);
      removed.put(key, context.original().get(key));
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache).hasSize(context.initialSize() - count);
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removed).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void invalidate_nullKey(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.invalidate(null));
  }

  /* --------------- invalidateAll --------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Int, Int> cache, CacheContext context) {
    cache.invalidateAll();
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void invalidateAll_empty(Cache<Int, Int> cache, CacheContext context) {
    cache.invalidateAll(Set.of());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL })
  public void invalidateAll_partial(Cache<Int, Int> cache, CacheContext context) {
    var removals = cache.asMap().entrySet().stream()
        .filter(entry -> ((entry.getKey().intValue() % 2) == 0))
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    cache.invalidateAll(removals.keySet());
    assertThat(cache).hasSize(context.initialSize() - removals.size());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(removals).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidateAll_full(Cache<Int, Int> cache, CacheContext context) {
    cache.invalidateAll(context.original().keySet());
    assertThat(cache).isEmpty();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void invalidateAll_null(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.invalidateAll(null));
  }

  @CheckNoStats
  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, compute = Compute.SYNC,
      executorFailure = ExecutorFailure.IGNORED, executor = CacheExecutor.REJECTING,
      removalListener = Listener.CONSUMING)
  public void invalidateAll_removalListener_rejected(Cache<Int, Int> cache, CacheContext context) {
    cache.invalidateAll();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = Listener.MOCKITO)
  public void invalidateAll_removalListener_writeback(Cache<Int, Int> cache, CacheContext context) {
    Answer<?> writeback = invocation -> {
      cache.put(invocation.getArgument(0), invocation.getArgument(0));
      return null;
    };
    doAnswer(writeback)
        .when(context.removalListener())
        .onRemoval(any(), any(), any());

    cache.invalidateAll();
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.original()).exclusively();
    assertThat(cache).containsExactlyEntriesIn(Maps.asMap(context.original().keySet(), key -> key));
  }

  /* --------------- cleanup --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void cleanup(Cache<Int, Int> cache, CacheContext context) {
    cache.cleanUp();
  }

  /* --------------- serialize --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void serialize(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache).isReserialize();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY)
  public void readObject(CacheContext context) throws NoSuchMethodException {
    var cache = context.isAsync() ? context.asyncCache() : context.cache();
    var readObject = cache.getClass().getDeclaredMethod("readObject", ObjectInputStream.class);
    readObject.setAccessible(true);

    var exception = assertThrows(InvocationTargetException.class,
        () -> readObject.invoke(cache, new ObjectInputStream() {}));
    assertThat(exception).hasCauseThat().isInstanceOf(InvalidObjectException.class);
    assertThat(exception).hasCauseThat().hasMessageThat().isEqualTo("Proxy required");
  }

  /* --------------- null parameter --------------- */

  private final ImmutableSetMultimap<Class<?>, Method> testMethods = getPublicMethods();
  private final NullPointerTester npeTester = new NullPointerTester();

  @CacheSpec
  @Test(dataProvider = "caches")
  public void nullParameters(Cache<Int, Int> cache, CacheContext context) {
    checkNullPointer(cache);
    checkNullPointer(cache.asMap());
    checkNullPointer(cache.stats());
    checkNullPointer(cache.policy());
    checkNullPointer(cache.asMap().keySet());
    checkNullPointer(cache.asMap().values());
    checkNullPointer(cache.asMap().entrySet());
    checkNullPointer(cache.policy().eviction().orElse(null));
    checkNullPointer(cache.policy().expireVariably().orElse(null));
    checkNullPointer(cache.policy().expireAfterWrite().orElse(null));
    checkNullPointer(cache.policy().expireAfterAccess().orElse(null));
    checkNullPointer(cache.policy().refreshAfterWrite().orElse(null));

    if (context.isAsync()) {
      checkNullPointer(context.asyncCache());
      checkNullPointer(context.asyncCache().asMap());
      checkNullPointer(context.asyncCache().asMap().keySet());
      checkNullPointer(context.asyncCache().asMap().values());
      checkNullPointer(context.asyncCache().asMap().entrySet());
    }
  }

  private void checkNullPointer(Object o) {
    if (o == null) {
      return;
    }
    testMethods.asMap().entrySet().stream()
        .filter(entry -> entry.getKey().isInstance(o))
        .flatMap(entry -> entry.getValue().stream())
        .forEach(method -> npeTester.testMethod(o, method));
  }

  private ImmutableSetMultimap<Class<?>, Method> getPublicMethods() {
    var classes = List.of(Cache.class, LoadingCache.class, AsyncCache.class,
        AsyncLoadingCache.class, CacheStats.class, Policy.class, Eviction.class,
        FixedRefresh.class, FixedExpiration.class, VarExpiration.class, Map.class,
        Collection.class, Set.class);
    var ignored = ImmutableSet.of(
        Triple.of(Map.class, "equals", List.of(Object.class)),
        Triple.of(Set.class, "equals", List.of(Object.class)),
        Triple.of(Set.class, "remove", List.of(Object.class)),
        Triple.of(Set.class, "contains", List.of(Object.class)),
        Triple.of(Collection.class, "equals", List.of(Object.class)),
        Triple.of(Collection.class, "remove", List.of(Object.class)),
        Triple.of(Collection.class, "contains", List.of(Object.class)),
        Triple.of(Map.class, "remove", List.of(Object.class, Object.class)),
        Triple.of(Map.class, "getOrDefault", List.of(Object.class, Object.class)));
    var builder = new ImmutableSetMultimap.Builder<Class<?>, Method>();
    for (var clazz : classes) {
      for (var method : clazz.getMethods()) {
        var key = Triple.of(clazz, method.getName(), List.of(method.getParameterTypes()));
        if (!ignored.contains(key) && !Modifier.isStatic(method.getModifiers())) {
          builder.put(clazz, method);
        }
      }
    }
    return builder.build();
  }

  /* --------------- Policy: stats --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void stats(Cache<Int, Int> cache, CacheContext context) {
    var stats = cache.stats()
        .plus(CacheStats.of(1, 2, 3, 4, 5, 6, 7)
        .minus(CacheStats.of(6, 5, 4, 3, 2, 1, 0)));
    assertThat(stats).isEqualTo(CacheStats.of(0, 0, 0, 1, 3, 5, 7));
    assertThat(cache.policy().isRecordingStats()).isEqualTo(context.isRecordingStats());
  }

  /* --------------- Policy: getIfPresentQuietly --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresentQuietly_nullKey(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.policy().getIfPresentQuietly(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresentQuietly_absent(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.policy().getIfPresentQuietly(context.absentKey())).isNull();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getIfPresentQuietly_present(Cache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      assertThat(cache.policy().getIfPresentQuietly(key)).isEqualTo(context.original().get(key));
    }
  }

  /* --------------- Policy: getEntryIfPresentQuietly --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getEntryIfPresentQuietly_nullKey(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(NullPointerException.class, () -> cache.policy().getEntryIfPresentQuietly(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getEntryIfPresentQuietly_absent(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.policy().getEntryIfPresentQuietly(context.absentKey())).isNull();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DISABLED, Listener.REJECTING })
  public void getEntryIfPresentQuietly_present(Cache<Int, Int> cache, CacheContext context) {
    for (Int key : context.firstMiddleLastKeys()) {
      var entry = cache.policy().getEntryIfPresentQuietly(key);
      assertThat(context).containsEntry(entry);
    }
  }

  /* --------------- Policy: refreshes --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void refreshes_empty(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.policy().refreshes()).isExhaustivelyEmpty();
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void refreshes_unmodifiable(Cache<Int, Int> cache, CacheContext context) {
    assertThrows(UnsupportedOperationException.class, () -> cache.policy().refreshes().clear());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void refreshes_nullLookup(Cache<Int, Int> cache, CacheContext context) {
    assertThat(cache.policy().refreshes().containsValue(null)).isFalse();
    assertThat(cache.policy().refreshes().containsKey(null)).isFalse();
    assertThat(cache.policy().refreshes().get(null)).isNull();
  }

  /* --------------- Policy: CacheEntry --------------- */

  @Test
  public void cacheEntry_setValue() {
    var entry = SnapshotEntry.forEntry(1, 2);
    assertThrows(UnsupportedOperationException.class, () -> entry.setValue(3));
  }

  @Test
  public void cacheEntry_equals_hashCode_toString() {
    long snapshot = 100;
    int weight = 200;
    long expiresAt = 300;
    long refreshableAt = 400;
    var tester = new EqualsTester()
        .addEqualityGroup(1, 1, 1);

    for (int i = 0; i < 10; i++) {
      var key = i;
      var value = i + 1;
      var group = List.of(Map.entry(key, value),
          new SnapshotEntry<>(key, value, snapshot),
          new WeightedEntry<>(key, value, snapshot, weight),
          new ExpirableEntry<>(key, value, snapshot, expiresAt),
          new ExpirableWeightedEntry<>(key, value, snapshot, weight, expiresAt),
          new RefreshableExpirableEntry<>(key, value, snapshot, expiresAt, refreshableAt),
          new CompleteEntry<>(key, value, snapshot, weight, expiresAt, refreshableAt));
      for (var entry : group) {
        assertWithMessage("%s", entry.getClass())
            .that(entry.toString()).isEqualTo(key + "=" + value);
      }
      tester.addEqualityGroup(group.toArray());
    }
    tester.testEquals();
  }
}
