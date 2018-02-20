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
import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.CheckNoWriter;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.DeleteException;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.WriteException;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

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

  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void estimatedSize(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  /* ---------------- getIfPresent -------------- */

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getIfPresent(null);
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getIfPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.lastKey()), is(not(nullValue())));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- get -------------- */

  @CacheSpec
  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.get(null, Function.identity());
  }

  @CacheSpec
  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullLoader(Cache<Integer, Integer> cache, CacheContext context) {
    cache.get(context.absentKey(), null);
  }

  @CacheSpec
  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKeyAndLoader(Cache<Integer, Integer> cache, CacheContext context) {
    cache.get(null, null);
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void get_throwsException(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.get(context.absentKey(), key -> { throw new IllegalStateException(); });
    } finally {
      assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
      assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
    }
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void get_absent_null(LoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey(), k -> null), is(nullValue()));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches")
  public void get_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    Integer value = cache.get(key, k -> context.absentValue());
    assertThat(value, is(context.absentValue()));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
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

    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- getAllPresent -------------- */

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAllPresent_iterable_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(null);
  }

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getAllPresent_iterable_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(Collections.singletonList(null));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_empty(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(ImmutableList.of());
    assertThat(result.size(), is(0));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(context.absentKeys());
    assertThat(result.size(), is(0));

    int count = context.absentKeys().size();
    assertThat(context, both(hasMissCount(count)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CacheSpec
  @CheckNoWriter
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAllPresent_immutable(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getAllPresent(context.absentKeys()).clear();
  }

  @CheckNoWriter
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
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(expect.size())));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_full(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(context.original().keySet());
    assertThat(result, is(equalTo(context.original())));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(result.size())));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_duplicates(Cache<Integer, Integer> cache, CacheContext context) {
    Iterable<Integer> keys = Iterables.concat(
        context.absentKeys(), context.absentKeys(),
        context.original().keySet(), context.original().keySet());
    Map<Integer, Integer> result = cache.getAllPresent(keys);

    int misses = context.absentKeys().size();
    int hits = context.original().keySet().size();
    if (context.isGuava()) {
      // does not filter duplicate queries
      misses += misses;
      hits += hits;
    }

    assertThat(result, is(equalTo(context.original())));
    assertThat(context, both(hasMissCount(misses)).and(hasHitCount(hits)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  @CheckNoWriter
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
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG, writer = Writer.DISABLED)
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

  /* ---------------- put -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void put_insert(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    assertThat(cache.estimatedSize(), is(context.initialSize() + 1));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
  }

  @CheckNoWriter
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
      assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace_differentValue(Cache<Integer, Integer> cache, CacheContext context) {
    for (Integer key : context.firstMiddleLastKeys()) {
      cache.put(key, context.absentValue());
      assertThat(cache.getIfPresent(key), is(context.absentValue()));
      verifyWriter(context, (verifier, writer) -> {
        verifier.wrote(key, context.absentValue());
      });
    }
    assertThat(cache.estimatedSize(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.REPLACED));
  }

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, context.absentValue());
  }

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), null);
  }

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void put_nullKeyAndValue(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(null, null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = WriteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void put_insert_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.put(context.absentKey(), context.absentValue());
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = WriteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void put_replace_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.put(context.middleKey(), context.absentValue());
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
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
    assertThat(cache.estimatedSize(), is(100 + context.initialSize()));

    verifyWriter(context, (verifier, writer) -> {
      verifier.wroteAll(entries);
    });
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

    verifyWriter(context, (verifier, writer) -> {
      verifier.wroteAll(entries);
    });
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
    assertThat(cache, hasRemovalNotifications(context, expect.size(), RemovalCause.REPLACED));

    verifyWriter(context, (verifier, writer) -> {
      verifier.wroteAll(replaced);
    });
  }

  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void putAll_empty(Cache<Integer, Integer> cache, CacheContext context) {
    cache.putAll(new HashMap<>());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void putAll_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.putAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = WriteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void putAll_insert_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.putAll(context.absent());
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = WriteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void putAll_replace_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.putAll(ImmutableMap.of(context.middleKey(), context.absentValue()));
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  /* ---------------- invalidate -------------- */

  @CheckNoWriter @CheckNoStats
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
      verifyWriter(context, (verifier, writer) -> {
        verifier.deleted(key, context.original().get(key), RemovalCause.EXPLICIT);
      });
    }
    int count = context.firstMiddleLastKeys().size();
    assertThat(cache.estimatedSize(), is(context.initialSize() - count));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPLICIT));
  }

  @CheckNoWriter @CheckNoStats
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidate_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidate_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.invalidate(context.middleKey());
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  /* ---------------- invalidateAll -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll();
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(cache, hasRemovalNotifications(context,
        context.original().size(), RemovalCause.EXPLICIT));
    verifyWriter(context, (verifier, writer) -> {
      verifier.deletedAll(context.original(), RemovalCause.EXPLICIT);
    });
  }

  @CheckNoWriter @CheckNoStats
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
    assertThat(cache, hasRemovalNotifications(context, keys.size(), RemovalCause.EXPLICIT));

    verifyWriter(context, (verifier, writer) -> {
      verifier.deletedAll(Maps.filterKeys(context.original(), Predicates.in(keys)), RemovalCause.EXPLICIT);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(context.original().keySet());
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(cache, hasRemovalNotifications(context,
        context.original().size(), RemovalCause.EXPLICIT));
    verifyWriter(context, (verifier, writer) -> {
      verifier.deletedAll(context.original(), RemovalCause.EXPLICIT);
    });
  }

  @CacheSpec
  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidateAll_null(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidateAll_partial_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.invalidateAll(context.firstMiddleLastKeys());
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidateAll_full_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.invalidateAll();
    } finally {
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  /* ---------------- cleanup -------------- */

  @CacheSpec
  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches")
  public void cleanup(Cache<Integer, Integer> cache, CacheContext context) {
    cache.cleanUp();
  }

  /* ---------------- stats -------------- */

  @CacheSpec
  @CheckNoWriter @CheckNoStats
  @Test(dataProvider = "caches")
  public void stats(Cache<Integer, Integer> cache, CacheContext context) {
    CacheStats stats = cache.stats()
        .plus(new CacheStats(1, 2, 3, 4, 5, 6, 7)
        .minus(new CacheStats(6, 5, 4, 3, 2, 1, 0)));
    assertThat(stats, is(new CacheStats(0, 0, 0, 1, 3, 5, 7)));
    assertThat(cache.policy().isRecordingStats(), is(context.isRecordingStats()));
  }

  /* ---------------- serialize -------------- */

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(writer = Writer.EXCEPTIONAL)
  public void serialize(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache, is(reserializable()));
  }
}
