/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.benmanes.caffeine.guava.compatibility;

import static com.github.benmanes.caffeine.guava.compatibility.TestingCacheLoaders.identityLoader;
import static com.google.common.truth.Truth.assertThat;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.NullPointerTester;
import com.google.common.util.concurrent.MoreExecutors;

import junit.framework.TestCase;

/**
 * @author Charles Fry
 */
@SuppressWarnings("JUnit3FloatingPointComparisonWithoutDelta")
public class LocalLoadingCacheTest extends TestCase {
  static final CacheStats EMPTY_STATS = new CacheStats(0, 0, 0, 0, 0, 0);

  private static <K, V> LoadingCache<K, V> makeCache(
      Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
    return CaffeinatedGuava.build(builder, loader);
  }

  private Caffeine<Object, Object> createCacheBuilder() {
    return Caffeine.newBuilder().executor(MoreExecutors.directExecutor()).recordStats();
  }

  // constructor tests

  @SuppressWarnings("CheckReturnValue")
  public void testComputingFunction() {
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object from) {
        return new Object();
      }
    };
    makeCache(createCacheBuilder(), loader);
  }

  // null parameters test

  public void testNullParameters() throws Exception {
    NullPointerTester tester = new NullPointerTester();
    CacheLoader<Object, Object> loader = identityLoader();
    tester.testAllPublicInstanceMethods(makeCache(createCacheBuilder(), loader));
  }

  // stats tests

  public void testStats() {
    Caffeine<Object, Object> builder = createCacheBuilder().maximumSize(2);
    LoadingCache<Object, Object> cache = makeCache(builder, identityLoader());
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    cache.getUnchecked(one);
    CacheStats stats = cache.stats();
    assertEquals(1, stats.requestCount());
    assertEquals(0, stats.hitCount());
    assertEquals(0.0, stats.hitRate());
    assertEquals(1, stats.missCount());
    assertEquals(1.0, stats.missRate());
    assertEquals(1, stats.loadCount());
    long totalLoadTime = stats.totalLoadTime();
    assertTrue(totalLoadTime >= 0);
    assertTrue(stats.averageLoadPenalty() >= 0.0);
    assertEquals(0, stats.evictionCount());

    cache.getUnchecked(one);
    stats = cache.stats();
    assertEquals(2, stats.requestCount());
    assertEquals(1, stats.hitCount());
    assertEquals(1.0/2, stats.hitRate());
    assertEquals(1, stats.missCount());
    assertEquals(1.0/2, stats.missRate());
    assertEquals(1, stats.loadCount());
    assertEquals(0, stats.evictionCount());

    Object two = new Object();
    cache.getUnchecked(two);
    stats = cache.stats();
    assertEquals(3, stats.requestCount());
    assertEquals(1, stats.hitCount());
    assertEquals(1.0/3, stats.hitRate());
    assertEquals(2, stats.missCount());
    assertEquals(2.0/3, stats.missRate());
    assertEquals(2, stats.loadCount());
    assertTrue(stats.totalLoadTime() >= totalLoadTime);
    totalLoadTime = stats.totalLoadTime();
    assertTrue(stats.averageLoadPenalty() >= 0.0);
    assertEquals(0, stats.evictionCount());

    Object three = new Object();
    cache.getUnchecked(three);
    stats = cache.stats();
    assertEquals(4, stats.requestCount());
    assertEquals(1, stats.hitCount());
    assertEquals(1.0/4, stats.hitRate());
    assertEquals(3, stats.missCount());
    assertEquals(3.0/4, stats.missRate());
    assertEquals(3, stats.loadCount());
    assertTrue(stats.totalLoadTime() >= totalLoadTime);
    stats.totalLoadTime();
    assertTrue(stats.averageLoadPenalty() >= 0.0);
    assertEquals(1, stats.evictionCount());
  }

  public void testStatsNoops() {
    Caffeine<Object, Object> builder = createCacheBuilder();
    LoadingCache<Object, Object> cache = makeCache(builder, identityLoader());
    ConcurrentMap<Object, Object> map = cache.asMap(); // modifiable map view
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    assertNull(map.put(one, one));
    assertSame(one, map.get(one));
    assertTrue(map.containsKey(one));
    assertTrue(map.containsValue(one));
    Object two = new Object();
    assertSame(one, map.replace(one, two));
    assertTrue(map.containsKey(one));
    assertFalse(map.containsValue(one));
    Object three = new Object();
    assertTrue(map.replace(one, two, three));
    assertTrue(map.remove(one, three));
    assertFalse(map.containsKey(one));
    assertFalse(map.containsValue(one));
    assertNull(map.putIfAbsent(two, three));
    assertSame(three, map.remove(two));
    assertNull(map.put(three, one));
    assertNull(map.put(one, two));

    assertThat(map).containsEntry(three, one);
    assertThat(map).containsEntry(one, two);

    //TODO(user): Confirm with fry@ that this is a reasonable substitute.
    //Set<Map.Entry<Object, Object>> entries = map.entrySet();
    //assertThat(entries).containsExactly(
    //    Maps.immutableEntry(three, one), Maps.immutableEntry(one, two));
    //Set<Object> keys = map.keySet();
    //assertThat(keys).containsExactly(one, three);
    //Collection<Object> values = map.values();
    //assertThat(values).containsExactly(one, two);

    map.clear();

    assertEquals(EMPTY_STATS, cache.stats());
  }

  public void testNoStats() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(2);
    LoadingCache<Object, Object> cache = makeCache(builder, identityLoader());
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    cache.getUnchecked(one);
    assertEquals(EMPTY_STATS, cache.stats());

    cache.getUnchecked(one);
    assertEquals(EMPTY_STATS, cache.stats());

    Object two = new Object();
    cache.getUnchecked(two);
    assertEquals(EMPTY_STATS, cache.stats());

    Object three = new Object();
    cache.getUnchecked(three);
    assertEquals(EMPTY_STATS, cache.stats());
  }

  public void testRecordStats() {
    Caffeine<Object, Object> builder = createCacheBuilder().maximumSize(2);
    LoadingCache<Object, Object> cache = makeCache(builder, identityLoader());
    assertEquals(0, cache.stats().hitCount());
    assertEquals(0, cache.stats().missCount());

    Object one = new Object();
    cache.getUnchecked(one);
    assertEquals(0, cache.stats().hitCount());
    assertEquals(1, cache.stats().missCount());

    cache.getUnchecked(one);
    assertEquals(1, cache.stats().hitCount());
    assertEquals(1, cache.stats().missCount());

    Object two = new Object();
    cache.getUnchecked(two);
    assertEquals(1, cache.stats().hitCount());
    assertEquals(2, cache.stats().missCount());

    Object three = new Object();
    cache.getUnchecked(three);
    assertEquals(1, cache.stats().hitCount());
    assertEquals(3, cache.stats().missCount());
  }

  // asMap tests

  public void testAsMap() {
    Caffeine<Object, Object> builder = createCacheBuilder();
    LoadingCache<Object, Object> cache = makeCache(builder, identityLoader());
    assertEquals(EMPTY_STATS, cache.stats());

    Object one = new Object();
    Object two = new Object();
    Object three = new Object();

    ConcurrentMap<Object, Object> map = cache.asMap();
    assertNull(map.put(one, two));
    assertSame(two, map.get(one));
    map.putAll(ImmutableMap.of(two, three));
    assertSame(three, map.get(two));
    assertSame(two, map.putIfAbsent(one, three));
    assertSame(two, map.get(one));
    assertNull(map.putIfAbsent(three, one));
    assertSame(one, map.get(three));
    assertSame(two, map.replace(one, three));
    assertSame(three, map.get(one));
    assertFalse(map.replace(one, two, three));
    assertSame(three, map.get(one));
    assertTrue(map.replace(one, three, two));
    assertSame(two, map.get(one));
    assertEquals(3, map.size());

    map.clear();
    assertTrue(map.isEmpty());
    assertEquals(0, map.size());

    cache.getUnchecked(one);
    assertEquals(1, map.size());
    assertSame(one, map.get(one));
    assertTrue(map.containsKey(one));
    assertTrue(map.containsValue(one));
    assertSame(one, map.remove(one));
    assertEquals(0, map.size());

    cache.getUnchecked(one);
    assertEquals(1, map.size());
    assertFalse(map.remove(one, two));
    assertTrue(map.remove(one, one));
    assertEquals(0, map.size());

    cache.getUnchecked(one);
    var newMap = ImmutableMap.of(one, one);
    assertEquals(newMap, map);
    assertEquals(newMap.entrySet(), map.entrySet());
    assertEquals(newMap.keySet(), map.keySet());
    var expectedValues = ImmutableSet.of(one);
    var actualValues = ImmutableSet.copyOf(map.values());
    assertEquals(expectedValues, actualValues);
  }

  // Bug in JDK8; fixed but not released as of 1.8.0_25-b17
  public void disabled_testRecursiveComputation() throws InterruptedException {
    final AtomicReference<LoadingCache<Integer, String>> cacheRef =
        new AtomicReference<LoadingCache<Integer, String>>();
    CacheLoader<Integer, String> recursiveLoader = new CacheLoader<Integer, String>() {
      @Override
      public String load(Integer key) {
        if (key > 0) {
          return key + ", " + cacheRef.get().getUnchecked(key - 1);
        } else {
          return "0";
        }
      }
    };

    LoadingCache<Integer, String> recursiveCache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .weakKeys()
        .weakValues(), recursiveLoader);
    cacheRef.set(recursiveCache);
    assertEquals("3, 2, 1, 0", recursiveCache.getUnchecked(3));

    recursiveLoader = new CacheLoader<Integer, String>() {
      @Override
      public String load(Integer key) {
        return cacheRef.get().getUnchecked(key);
      }
    };

    recursiveCache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .weakKeys()
        .weakValues(), recursiveLoader);
    cacheRef.set(recursiveCache);

    // tells the test when the compution has completed
    final CountDownLatch doneSignal = new CountDownLatch(1);

    Thread thread = new Thread() {
      @Override
      public void run() {
        try {
          cacheRef.get().getUnchecked(3);
        } finally {
          doneSignal.countDown();
        }
      }
    };
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread t, Throwable e) {}
    });
    thread.start();

    boolean done = doneSignal.await(1, TimeUnit.SECONDS);
    if (!done) {
      StringBuilder builder = new StringBuilder();
      for (StackTraceElement trace : thread.getStackTrace()) {
        builder.append("\tat ").append(trace).append('\n');
      }
      fail(builder.toString());
    }
  }
}
