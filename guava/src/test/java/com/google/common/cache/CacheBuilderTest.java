/*
 * Copyright (C) 2009 The Guava Authors
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

package com.google.common.cache;

import static com.google.common.cache.TestingCacheLoaders.constantLoader;
import static com.google.common.cache.TestingCacheLoaders.identityLoader;
import static com.google.common.cache.TestingRemovalListeners.countingRemovalListener;
import static com.google.common.cache.TestingRemovalListeners.nullRemovalListener;
import static com.google.common.cache.TestingRemovalListeners.queuingRemovalListener;
import static com.google.common.cache.TestingWeighers.constantWeigher;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.cache.TestingRemovalListeners.CountingRemovalListener;
import com.google.common.cache.TestingRemovalListeners.QueuingRemovalListener;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.testing.NullPointerTester;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;

import junit.framework.TestCase;

/**
 * Unit tests for Caffeine.
 */
@GwtCompatible(emulated = true)
@SuppressWarnings({"CanonicalDuration", "ThreadPriorityCheck", "PreferJavaTimeOverload"})
public class CacheBuilderTest extends TestCase {

  public void testNewBuilder() {
    CacheLoader<Object, Integer> loader = constantLoader(1);

    LoadingCache<String, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .removalListener(countingRemovalListener()), loader);

    assertEquals(Integer.valueOf(1), cache.getUnchecked("one"));
    assertEquals(1, cache.size());
  }

  public void testInitialCapacity_negative() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.initialCapacity(-1);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  public void testInitialCapacity_setTwice() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder().initialCapacity(16);
    try {
      // even to the same value is not allowed
      builder.initialCapacity(16);
      fail();
    } catch (IllegalStateException expected) {}
  }

  public void testInitialCapacity_large() {
    Caffeine.newBuilder().initialCapacity(Integer.MAX_VALUE);
    // that the builder didn't blow up is enough;
    // don't actually create this monster!
  }

  public void testMaximumSize_negative() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.maximumSize(-1);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  public void testMaximumSize_setTwice() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(16);
    try {
      // even to the same value is not allowed
      builder.maximumSize(16);
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("maximumWeight")
  public void testMaximumSize_andWeight() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(16);
    try {
      builder.maximumWeight(16);
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("maximumWeight")
  public void testMaximumWeight_negative() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.maximumWeight(-1);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  @GwtIncompatible("maximumWeight")
  public void testMaximumWeight_setTwice() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumWeight(16);
    try {
      // even to the same value is not allowed
      builder.maximumWeight(16);
      fail();
    } catch (IllegalStateException expected) {}
    try {
      builder.maximumSize(16);
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("maximumWeight")
  public void testMaximumWeight_withoutWeigher() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .maximumWeight(1);
    try {
      CaffeinatedGuava.build(builder, identityLoader());
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("weigher")
  public void testWeigher_withoutMaximumWeight() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder()
        .weigher(constantWeigher(42));
    try {
      CaffeinatedGuava.build(builder, identityLoader());
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("weigher")
  public void testWeigher_withMaximumSize() {
    try {
      Caffeine.newBuilder()
          .weigher(constantWeigher(42))
          .maximumSize(1);
      fail();
    } catch (IllegalStateException expected) {}
    try {
      Caffeine.newBuilder()
          .maximumSize(1)
          .weigher(constantWeigher(42));
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("weakKeys")
  public void testKeyStrengthSetTwice() {
    Caffeine<Object, Object> builder1 = Caffeine.newBuilder().weakKeys();
    try {
      builder1.weakKeys();
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible("weakValues")
  public void testValueStrengthSetTwice() {
    Caffeine<Object, Object> builder1 = Caffeine.newBuilder().weakValues();
    try {
      builder1.weakValues();
      fail();
    } catch (IllegalStateException expected) {}
    try {
      builder1.softValues();
      fail();
    } catch (IllegalStateException expected) {}

    Caffeine<Object, Object> builder2 = Caffeine.newBuilder().softValues();
    try {
      builder2.softValues();
      fail();
    } catch (IllegalStateException expected) {}
    try {
      builder2.weakValues();
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testLargeDurationsAreOk() {
    java.time.Duration threeHundredYears = java.time.Duration.ofDays(365 * 300);
    Caffeine.newBuilder()
        .expireAfterWrite(threeHundredYears)
        .expireAfterAccess(threeHundredYears)
        .refreshAfterWrite(threeHundredYears);
  }

  public void testTimeToLive_negative() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.expireAfterWrite(-1, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testTimeToLive_negative_duration() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.expireAfterWrite(java.time.Duration.ofSeconds(-1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  public void testTimeToLive_small() {
    CaffeinatedGuava.build(Caffeine.newBuilder()
        .expireAfterWrite(1, NANOSECONDS), identityLoader());
    // well, it didn't blow up.
  }

  public void testTimeToLive_setTwice() {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().expireAfterWrite(3600, SECONDS);
    try {
      // even to the same value is not allowed
      builder.expireAfterWrite(3600, SECONDS);
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testTimeToLive_setTwice_duration() {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().expireAfterWrite(java.time.Duration.ofSeconds(3600));
    try {
      // even to the same value is not allowed
      builder.expireAfterWrite(java.time.Duration.ofSeconds(3600));
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  public void testTimeToIdle_negative() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.expireAfterAccess(-1, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testTimeToIdle_negative_duration() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.expireAfterAccess(java.time.Duration.ofSeconds(-1));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  public void testTimeToIdle_small() {
    CaffeinatedGuava.build(Caffeine.newBuilder()
        .expireAfterAccess(1, NANOSECONDS), identityLoader());
    // well, it didn't blow up.
  }

  public void testTimeToIdle_setTwice() {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().expireAfterAccess(3600, SECONDS);
    try {
      // even to the same value is not allowed
      builder.expireAfterAccess(3600, SECONDS);
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testTimeToIdle_setTwice_duration() {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().expireAfterAccess(java.time.Duration.ofSeconds(3600));
    try {
      // even to the same value is not allowed
      builder.expireAfterAccess(java.time.Duration.ofSeconds(3600));
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  public void testTimeToIdleAndToLive() {
    CaffeinatedGuava.build(Caffeine.newBuilder()
        .expireAfterWrite(1, NANOSECONDS)
        .expireAfterAccess(1, NANOSECONDS),
        identityLoader());
    // well, it didn't blow up.
  }

  @GwtIncompatible("refreshAfterWrite")
  public void testRefresh_zero() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.refreshAfterWrite(0, SECONDS);
      fail();
    } catch (IllegalArgumentException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testRefresh_zero_duration() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    try {
      builder.refreshAfterWrite(java.time.Duration.ZERO);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @GwtIncompatible("refreshAfterWrite")
  public void testRefresh_setTwice() {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().refreshAfterWrite(3600, SECONDS);
    try {
      // even to the same value is not allowed
      builder.refreshAfterWrite(3600, SECONDS);
      fail();
    } catch (IllegalStateException expected) {}
  }

  @GwtIncompatible // java.time.Duration
  public void testRefresh_setTwice_duration() {
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().refreshAfterWrite(java.time.Duration.ofSeconds(3600));
    try {
      // even to the same value is not allowed
      builder.refreshAfterWrite(java.time.Duration.ofSeconds(3600));
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  public void testTicker_setTwice() {
    Ticker testTicker = Ticker.systemTicker();
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().ticker(testTicker);
    try {
      // even to the same instance is not allowed
      builder.ticker(testTicker);
      fail();
    } catch (IllegalStateException expected) {}
  }

  public void testRemovalListener_setTwice() {
    RemovalListener<Object, Object> testListener = nullRemovalListener();
    Caffeine<Object, Object> builder =
        Caffeine.newBuilder().removalListener(testListener);
    try {
      // even to the same instance is not allowed
      builder = builder.removalListener(testListener);
      fail();
    } catch (IllegalStateException expected) {}
  }

  public void testValuesIsNotASet() {
    assertThat(Caffeine.newBuilder().build().asMap().values())
        .isNotInstanceOf(Set.class);
  }

  @GwtIncompatible("CacheTesting")
  public void testNullCache() {
    CountingRemovalListener<Object, Object> listener = countingRemovalListener();
    LoadingCache<Object, Object> nullCache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumSize(0)
        .executor(MoreExecutors.directExecutor())
        .removalListener(listener), identityLoader());
    assertEquals(0, nullCache.size());
    Object key = new Object();
    assertSame(key, nullCache.getUnchecked(key));
    assertEquals(1, listener.getCount());
    assertEquals(0, nullCache.size());
    CacheTesting.checkEmpty(nullCache.asMap());
  }

  @GwtIncompatible("QueuingRemovalListener")
  public void testRemovalNotification_clear() throws InterruptedException {
    // If a clear() happens while a computation is pending, we should not get a removal
    // notification.

    final AtomicBoolean shouldWait = new AtomicBoolean(false);
    final CountDownLatch computingLatch = new CountDownLatch(1);
    CacheLoader<String, String> computingFunction = new CacheLoader<String, String>() {
      @Override public String load(String key) {
        if (shouldWait.get()) {
          assertTrue(Uninterruptibles.awaitUninterruptibly(computingLatch, 300, TimeUnit.MINUTES));
        }
        return key;
      }
    };
    QueuingRemovalListener<String, String> listener = queuingRemovalListener();

    final LoadingCache<String, String> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .removalListener(listener), computingFunction);

    // seed the map, so its segment's count > 0
    cache.getUnchecked("a");
    shouldWait.set(true);

    final CountDownLatch computationStarted = new CountDownLatch(1);
    final CountDownLatch computationComplete = new CountDownLatch(1);
    new Thread(() -> {
      computationStarted.countDown();
      cache.getUnchecked("b");
      computationComplete.countDown();
    }).start();

    // wait for the computingEntry to be created
    assertTrue(computationStarted.await(300, TimeUnit.MINUTES));
    cache.invalidateAll();
    // let the computation proceed
    computingLatch.countDown();
    // don't check cache.size() until we know the get("b") call is complete
    assertTrue(computationComplete.await(300, TimeUnit.MINUTES));

    // At this point, the listener should be holding the seed value (a -> a), and the map should
    // contain the computed value (b -> b), since the clear() happened before the computation
    // completed.
    assertEquals(1, listener.size());
    RemovalNotification<String, String> notification = listener.remove();
    assertEquals("a", notification.getKey());
    assertEquals("a", notification.getValue());
    assertEquals(1, cache.size());
    assertEquals("b", cache.getUnchecked("b"));
  }

  // "Basher tests", where we throw a bunch of stuff at a LoadingCache and check basic invariants.

  /**
   * This is a less carefully-controlled version of {@link #testRemovalNotification_clear} - this is
   * a black-box test that tries to create lots of different thread-interleavings, and asserts that
   * each computation is affected by a call to {@code clear()} (and therefore gets passed to the
   * removal listener), or else is not affected by the {@code clear()} (and therefore exists in the
   * cache afterward).
   */
  @GwtIncompatible("QueuingRemovalListener")
  @SuppressWarnings("FutureReturnValueIgnored")
  public void testRemovalNotification_clear_basher() throws InterruptedException {
    // If a clear() happens close to the end of computation, one of two things should happen:
    // - computation ends first: the removal listener is called, and the cache does not contain the
    //   key/value pair
    // - clear() happens first: the removal listener is not called, and the cache contains the pair
    AtomicBoolean computationShouldWait = new AtomicBoolean();
    CountDownLatch computationLatch = new CountDownLatch(1);
    QueuingRemovalListener<String, String> listener = queuingRemovalListener();
    final LoadingCache <String, String> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .removalListener(listener)
        .executor(MoreExecutors.directExecutor()),
        new DelayingIdentityLoader<String>(computationShouldWait, computationLatch));

    int nThreads = 100;
    int nTasks = 1000;
    int nSeededEntries = 100;
    Set<String> expectedKeys = Sets.newHashSetWithExpectedSize(nTasks + nSeededEntries);
    // seed the map, so its segments have a count>0; otherwise, clear() won't visit the in-progress
    // entries
    for (int i = 0; i < nSeededEntries; i++) {
      String s = "b" + i;
      cache.getUnchecked(s);
      expectedKeys.add(s);
    }
    computationShouldWait.set(true);

    final AtomicInteger computedCount = new AtomicInteger();
    ExecutorService threadPool = Executors.newFixedThreadPool(nThreads);
    final CountDownLatch tasksFinished = new CountDownLatch(nTasks);
    for (int i = 0; i < nTasks; i++) {
      final String s = "a" + i;
      threadPool.submit(new Runnable() {
        @Override public void run() {
          cache.getUnchecked(s);
          computedCount.incrementAndGet();
          tasksFinished.countDown();
        }
      });
      expectedKeys.add(s);
    }

    computationLatch.countDown();
    // let some computations complete
    while (computedCount.get() < nThreads) {
      Thread.yield();
    }
    cache.invalidateAll();
    assertTrue(tasksFinished.await(300, TimeUnit.MINUTES));

    // Check all of the removal notifications we received: they should have had correctly-associated
    // keys and values. (An earlier bug saw removal notifications for in-progress computations,
    // which had real keys with null values.)
    Map<String, String> removalNotifications = Maps.newHashMap();
    for (RemovalNotification<String, String> notification : listener) {
      removalNotifications.put(notification.getKey(), notification.getValue());
      assertEquals("Unexpected key/value pair passed to removalListener",
          notification.getKey(), notification.getValue());
    }

    // All of the seed values should have been visible, so we should have gotten removal
    // notifications for all of them.
    for (int i = 0; i < nSeededEntries; i++) {
      assertEquals("b" + i, removalNotifications.get("b" + i));
    }

    // Each of the values added to the map should either still be there, or have seen a removal
    // notification.
    assertEquals(expectedKeys, Sets.union(cache.asMap().keySet(), removalNotifications.keySet()));
    assertTrue(Sets.intersection(cache.asMap().keySet(), removalNotifications.keySet()).isEmpty());
  }

  /**
   * Calls get() repeatedly from many different threads, and tests that all of the removed entries
   * (removed because of size limits or expiration) trigger appropriate removal notifications.
   */
  @GwtIncompatible("QueuingRemovalListener")
  @SuppressWarnings("FutureReturnValueIgnored")
  // FIXME(ben): disabled due to TravisCI killing the process
  public void testRemovalNotification_get_basher() throws InterruptedException {
    int nTasks = 1000;
    int nThreads = 100;
    final int getsPerTask = 1000;
    final int nUniqueKeys = 10000;
    final Random random = new Random(); // Randoms.insecureRandom();

    QueuingRemovalListener<String, String> removalListener = queuingRemovalListener();
    final AtomicInteger computeCount = new AtomicInteger();
    final AtomicInteger exceptionCount = new AtomicInteger();
    final AtomicInteger computeNullCount = new AtomicInteger();
    CacheLoader<String, String> countingIdentityLoader =
        new CacheLoader<String, String>() {
          @Override public String load(String key) {
            int behavior = random.nextInt(4);
            if (behavior == 0) { // throw an exception
              exceptionCount.incrementAndGet();
              throw new RuntimeException("fake exception for test");
            } else if (behavior == 1) { // return null
              computeNullCount.incrementAndGet();
              return null;
            } else if (behavior == 2) { // slight delay before returning
              Uninterruptibles.sleepUninterruptibly(5, TimeUnit.MILLISECONDS);
              computeCount.incrementAndGet();
              return key;
            } else {
              computeCount.incrementAndGet();
              return key;
            }
          }
        };
    final LoadingCache<String, String> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .executor(MoreExecutors.directExecutor())
        .expireAfterWrite(100, TimeUnit.MILLISECONDS)
        .removalListener(removalListener)
        //.maximumSize(5000)
        ,
        countingIdentityLoader);

    ExecutorService threadPool = Executors.newFixedThreadPool(nThreads);
    for (int i = 0; i < nTasks; i++) {
      threadPool.submit(new Runnable() {
        @Override public void run() {
          for (int j = 0; j < getsPerTask; j++) {
            try {
              cache.getUnchecked("key" + random.nextInt(nUniqueKeys));
            } catch (RuntimeException e) {
            }
          }
        }
      });
    }

    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(300, TimeUnit.SECONDS));

    // Since we're not doing any more cache operations, and the cache only expires/evicts when doing
    // other operations, the cache and the removal queue won't change from this point on.

    // Verify that each received removal notification was valid
    for (RemovalNotification<String, String> notification : removalListener) {
      assertEquals("Invalid removal notification", notification.getKey(), notification.getValue());
    }

    CacheStats stats = cache.stats();
    assertEquals(removalListener.size(), stats.evictionCount());
    assertEquals(computeCount.get(), stats.loadSuccessCount());
    assertEquals(exceptionCount.get() + computeNullCount.get(), stats.loadExceptionCount());
    // each computed value is still in the cache, or was passed to the removal listener
    assertEquals(computeCount.get(), cache.size() + removalListener.size());
  }

  @GwtIncompatible("NullPointerTester")
  public void testNullParameters() throws Exception {
    NullPointerTester tester = new NullPointerTester();
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    tester.testAllPublicInstanceMethods(builder);
  }

  @GwtIncompatible("CountDownLatch")
  static final class DelayingIdentityLoader<T> extends CacheLoader<T, T> {
    private final AtomicBoolean shouldWait;
    private final CountDownLatch delayLatch;

    DelayingIdentityLoader(AtomicBoolean shouldWait, CountDownLatch delayLatch) {
      this.shouldWait = shouldWait;
      this.delayLatch = delayLatch;
    }

    @Override public T load(T key) {
      if (shouldWait.get()) {
        assertTrue(Uninterruptibles.awaitUninterruptibly(delayLatch, 300, TimeUnit.SECONDS));
      }
      return key;
    }
  }
}
