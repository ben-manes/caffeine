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

import static com.github.benmanes.caffeine.cache.IsCacheReserializable.reserializable;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;

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

  /* --------------- size --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void estimatedSize(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  /* --------------- getIfPresent --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getIfPresent(null);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getIfPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.lastKey()), is(not(nullValue())));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- get --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.get(null, Function.identity());
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullLoader(Cache<Integer, Integer> cache, CacheContext context) {
    cache.get(context.absentKey(), null);
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKeyAndLoader(Cache<Integer, Integer> cache, CacheContext context) {
    cache.get(null, null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void get_throwsException(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.get(context.absentKey(), key -> { throw new IllegalStateException(); });
    } finally {
      verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));
    }
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent_null(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey(), k -> null), is(nullValue()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = cache.get(key, k -> context.absentValue());
    assertThat(value, is(context.absentValue()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(Cache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader),
        is(context.original().get(context.firstKey())));
    assertThat(cache.get(context.middleKey(), loader),
        is(context.original().get(context.middleKey())));
    assertThat(cache.get(context.lastKey(), loader),
        is(context.original().get(context.lastKey())));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- getAllPresent --------------- */

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAllPresent_iterable_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(null);
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAllPresent_iterable_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(Collections.singletonList(null));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_empty(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(ImmutableList.of());
    assertThat(result.size(), is(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(context.absentKeys());
    assertThat(result.size(), is(0));

    int count = context.absentKeys().size();
    verifyStats(context, verifier -> verifier.hits(0).misses(count).success(0).failures(0));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAllPresent_immutable(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(context.absentKeys()).clear();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_partial(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>();
    expect.put(context.firstKey(), context.original().get(context.firstKey()));
    expect.put(context.middleKey(), context.original().get(context.middleKey()));
    expect.put(context.lastKey(), context.original().get(context.lastKey()));
    Map<Integer, Integer> result = cache.getAllPresent(expect.keySet());
    assertThat(result, is(equalTo(expect)));
    verifyStats(context, verifier -> verifier.hits(expect.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_full(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(context.original().keySet());
    assertThat(result, is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(result.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_duplicates(Cache<Integer, Integer> cache, CacheContext context) {
    Iterable<Integer> keys = Iterables.concat(
        context.absentKeys(), context.absentKeys(),
        context.original().keySet(), context.original().keySet());
    Map<Integer, Integer> result = cache.getAllPresent(keys);

    long hits, misses;
    if (context.implementation() == Implementation.Guava) {
      // Guava does not skip duplicates
      hits = 2L * context.initialSize();
      misses = 2L * context.absentKeys().size();
    } else {
      hits = context.initialSize();
      misses = context.absentKeys().size();
    }
    assertThat(result, is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(hits).misses(misses).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_ordered(Cache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAllPresent(keys).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void getAllPresent_jdk8186171(Cache<Object, Integer> cache, CacheContext context) {
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }

    List<Key> keys = new ArrayList<>();
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(new Key());
    }

    Key key = Iterables.getLast(keys);
    Integer value = context.absentValue();
    cache.put(key, value);

    Map<Object, Integer> result = cache.getAllPresent(keys);
    assertThat(result.values(), not(hasItem(nullValue())));
    assertThat(result, is(equalTo(ImmutableMap.of(key, value))));
  }

  /* --------------- getAll --------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_iterable_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(null, keys -> { throw new AssertionError(); });
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_iterable_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(Collections.singletonList(null), keys -> { throw new AssertionError(); });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_empty(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(ImmutableList.of(),
        keys -> { throw new AssertionError(); });
    assertThat(result.size(), is(0));
    verifyStats(context, verifier -> verifier.hits(0).misses(0));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_function_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(context.absentKeys(), null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAll_function_nullValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAll(context.absentKeys(), keys -> null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAll_immutable(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(context.absentKeys(), bulkMappingFunction());
    result.clear();
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getAll_absent_failure(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys(), keys -> { throw new IllegalStateException(); });
    } finally {
      int misses = context.absentKeys().size();
      verifyStats(context, verifier -> verifier.hits(0).misses(misses).success(0).failures(1));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(context.absentKeys(), bulkMappingFunction());

    int count = context.absentKeys().size();
    assertThat(result, aMapWithSize(count));
    verifyStats(context, verifier -> verifier.hits(0).misses(count).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_partial(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>();
    expect.put(context.firstKey(), -context.firstKey());
    expect.put(context.middleKey(), -context.middleKey());
    expect.put(context.lastKey(), -context.lastKey());
    Map<Integer, Integer> result = cache.getAll(expect.keySet(), bulkMappingFunction());

    assertThat(result, is(equalTo(expect)));
    verifyStats(context, verifier -> verifier.hits(expect.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_full(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAll(context.original().keySet(), bulkMappingFunction());
    assertThat(result, is(equalTo(context.original())));
    verifyStats(context, verifier -> verifier.hits(result.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_duplicates(Cache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    Iterable<Integer> keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    Map<Integer, Integer> result = cache.getAll(keys, bulkMappingFunction());
    assertThat(result.keySet(), is(equalTo(ImmutableSet.copyOf(keys))));

    long hits, misses;
    if (context.implementation() == Implementation.Guava) {
      // Guava does not skip duplicates
      hits = 2L * context.initialSize();
      misses = 2L * absentKeys.size();
    } else {
      hits = context.initialSize();
      misses = absentKeys.size();
    }
    verifyStats(context, verifier -> verifier.hits(hits).misses(misses));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_absent(
      Cache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys, bulkMappingFunction()).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_partial(
      Cache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys, bulkMappingFunction()).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_present(
      Cache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys, bulkMappingFunction()).keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_ordered_exceeds(
      Cache<Integer, Integer> cache, CacheContext context) {
    List<Integer> keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    List<Integer> result = new ArrayList<>(cache.getAll(keys, bulkMappingFunction()).keySet());
    assertThat(result.subList(0, keys.size()), is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void getAll_jdk8186171(CacheContext context) {
    class Key {
      @Override public int hashCode() {
        return 0; // to put keys in one bucket
      }
    }
    Cache<Object, Integer> cache = context.build(key -> null);

    List<Key> keys = new ArrayList<>();
    for (int i = 0; i < Population.FULL.size(); i++) {
      keys.add(new Key());
    }
    Key key = Iterables.getLast(keys);
    Integer value = context.absentValue();
    cache.put(key, value);

    Map<Object, Integer> result = cache.getAll(keys, keysToLoad -> ImmutableMap.of());
    assertThat(result.values(), not(hasItem(nullValue())));
    assertThat(result, is(equalTo(ImmutableMap.of(key, value))));
  }

  static Function<Set<? extends Integer>, Map<Integer, Integer>> bulkMappingFunction() {
    return keys -> {
      Map<Integer, Integer> result = keys.stream().collect(toMap(identity(), key -> -key));
      CacheSpec.interner.get().putAll(result);
      return result;
    };
  }

  /* --------------- put --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    assertThat(cache.estimatedSize(), is(context.initialSize() + 1));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_sameValue(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      Integer value = context.original().get(key);
      cache.put(key, value);
      assertThat(cache.getIfPresent(key), is(value));
    }
    assertThat(cache.estimatedSize(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();

    if (context.isGuava() || context.isAsync()) {
      verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.put(key, context.absentValue());
      assertThat(cache.getIfPresent(key), is(context.absentValue()));
    }
    assertThat(cache.estimatedSize(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, context.absentValue());
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), null);
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKeyAndValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, null);
  }

  /* --------------- put all --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_insert(Cache<Integer, Integer> cache, CacheContext context) {
    int startKey = context.original().size() + 1;
    Map<Integer, Integer> entries = IntStream
        .range(startKey, 100 + startKey).boxed()
        .collect(Collectors.toMap(Function.identity(), key -> -key));
    cache.putAll(entries);
    assertThat(cache.estimatedSize(), is(100 + context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_replace(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> entries = new HashMap<>(context.original());
    entries.replaceAll((key, value) -> value + 1);
    cache.putAll(entries);
    assertThat(cache.asMap(), is(equalTo(entries)));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(entries.size(), RemovalCause.REPLACED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void putAll_mixed(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> entries = new HashMap<>();
    Map<Integer, Integer> replaced = new HashMap<>();
    context.original().forEach((key, value) -> {
      if ((key % 2) == 0) {
        value++;
        replaced.put(key, value);
      }
      entries.put(key, value);
    });

    cache.putAll(entries);
    assertThat(cache.asMap(), is(equalTo(entries)));
    Map<Integer, Integer> expect = (context.isGuava() || context.isAsync()) ? entries : replaced;
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(expect.size(), RemovalCause.REPLACED));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(Cache<Integer, Integer> cache, CacheContext context) {
    cache.putAll(new HashMap<>());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void putAll_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.putAll(null);
  }

  /* --------------- invalidate --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void invalidate_absent(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.absentKey());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.invalidate(key);
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.estimatedSize(), is(context.initialSize() - count));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.EXPLICIT));
  }

  @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidate_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(null);
  }

  /* --------------- invalidateAll --------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll();
    assertThat(cache.estimatedSize(), is(0L));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(context.initialSize(), RemovalCause.EXPLICIT));
  }

  @CheckNoStats
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
    assertThat(cache.estimatedSize(), is(context.initialSize() - keys.size()));
    verifyRemovalListener(context, verifier -> verifier.hasOnly(keys.size(), RemovalCause.EXPLICIT));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(context.original().keySet());
    assertThat(cache.estimatedSize(), is(0L));
    verifyRemovalListener(context, verifier ->
        verifier.hasOnly(context.initialSize(), RemovalCause.EXPLICIT));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidateAll_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(null);
  }

  /* --------------- cleanup --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void cleanup(Cache<Integer, Integer> cache, CacheContext context) {
    cache.cleanUp();
  }

  /* --------------- serialize --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void serialize(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache, is(reserializable()));
  }

  /* --------------- Policy: stats --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void stats(Cache<Integer, Integer> cache, CacheContext context) {
    CacheStats stats = cache.stats()
        .plus(CacheStats.of(1, 2, 3, 4, 5, 6, 7)
        .minus(CacheStats.of(6, 5, 4, 3, 2, 1, 0)));
    assertThat(stats, is(CacheStats.of(0, 0, 0, 1, 3, 5, 7)));
    assertThat(cache.policy().isRecordingStats(), is(context.isRecordingStats()));
  }

  /* --------------- Policy: getIfPresentQuietly --------------- */

  @CheckNoStats
  @CacheSpec(implementation = Implementation.Caffeine,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresentQuietly_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.policy().getIfPresentQuietly(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getIfPresentQuietly_absent(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.policy().getIfPresentQuietly(context.absentKey()), is(nullValue()));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresentQuietly_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.policy().getIfPresentQuietly(context.firstKey()), is(not(nullValue())));
    assertThat(cache.policy().getIfPresentQuietly(context.middleKey()), is(not(nullValue())));
    assertThat(cache.policy().getIfPresentQuietly(context.lastKey()), is(not(nullValue())));
  }

  /* --------------- Policy: refreshes --------------- */

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches")
  public void refreshes_empty(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.policy().refreshes(), is(anEmptyMap()));
  }

  @CacheSpec
  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void refreshes_unmodifiable(Cache<Integer, Integer> cache, CacheContext context) {
    cache.policy().refreshes().clear();
  }
}
