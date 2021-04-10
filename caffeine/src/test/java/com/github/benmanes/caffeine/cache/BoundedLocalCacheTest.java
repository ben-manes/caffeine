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

import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.IDLE;
import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.PROCESSING_TO_IDLE;
import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.PROCESSING_TO_REQUIRED;
import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.REQUIRED;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.EXPIRE_WRITE_TOLERANCE;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.PERCENT_MAIN_PROTECTED;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.mockito.Mockito;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.PerformCleanupTask;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RemovalNotification;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.testing.GcFinalization;

/**
 * The test cases for the implementation details of {@link BoundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"GuardedBy", "deprecation"})
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class BoundedLocalCacheTest {

  static BoundedLocalCache<Integer, Integer> asBoundedLocalCache(Cache<Integer, Integer> cache) {
    return (BoundedLocalCache<Integer, Integer>) cache.asMap();
  }

  /* --------------- Maintenance --------------- */

  @Test
  @SuppressWarnings("UnusedVariable")
  public void cleanupTask_allowGc() {
    BoundedLocalCache<?, ?> cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {};
    PerformCleanupTask task = cache.drainBuffersTask;
    cache = null;

    GcFinalization.awaitClear(task.reference);
    task.run();
  }

  @Test
  public void scheduleAfterWrite() {
    BoundedLocalCache<?, ?> cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {
      @Override void scheduleDrainBuffers() {}
    };
    Map<Integer, Integer> transitions = ImmutableMap.of(
        IDLE, REQUIRED,
        REQUIRED, REQUIRED,
        PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED,
        PROCESSING_TO_REQUIRED, PROCESSING_TO_REQUIRED);
    transitions.forEach((start, end) -> {
      cache.drainStatus = start;
      cache.scheduleAfterWrite();
      assertThat(cache.drainStatus, is(end));
    });
  }

  @Test
  public void scheduleDrainBuffers() {
    Executor executor = Mockito.mock(Executor.class);
    BoundedLocalCache<?, ?> cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder().executor(executor), /* loader */ null, /* async */ false) {};
    Map<Integer, Integer> transitions = ImmutableMap.of(
        IDLE, PROCESSING_TO_IDLE,
        REQUIRED, PROCESSING_TO_IDLE,
        PROCESSING_TO_IDLE, PROCESSING_TO_IDLE,
        PROCESSING_TO_REQUIRED, PROCESSING_TO_REQUIRED);
    transitions.forEach((start, end) -> {
      cache.drainStatus = start;
      cache.scheduleDrainBuffers();
      assertThat(cache.drainStatus, is(end));

      if (!start.equals(end)) {
        Mockito.verify(executor).execute(any());
        Mockito.reset(executor);
      }
    });
  }

  @Test
  public void rescheduleDrainBuffers() {
    AtomicBoolean evicting = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    var evictionListener = new RemovalListener<Integer, Integer>() {
      @Override public void onRemoval(Integer key, Integer value, RemovalCause cause) {
        evicting.set(true);
        await().untilTrue(done);
      }
    };
    var map = asBoundedLocalCache(Caffeine.newBuilder()
        .evictionListener(evictionListener)
        .maximumSize(0L)
        .build());
    map.put(1, 1);
    await().untilTrue(evicting);

    map.put(2, 2);
    assertThat(map.drainStatus, is(PROCESSING_TO_REQUIRED));

    done.set(true);
    await().until(() -> map.drainStatus, is(IDLE));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL,
      executorFailure = ExecutorFailure.EXPECTED, executor = CacheExecutor.REJECTING,
      removalListener = Listener.CONSUMING)
  public void scheduleDrainBuffers_rejected(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  /* --------------- Eviction --------------- */

  @Test
  public void putWeighted_noOverflow() {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(CacheExecutor.DIRECT.create())
        .weigher(CacheWeigher.MAX_VALUE)
        .maximumWeight(Long.MAX_VALUE)
        .build();
    BoundedLocalCache<Integer, Integer> map = asBoundedLocalCache(cache);

    cache.put(1, 1);
    map.setWindowMaximum(0L);
    map.setWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.put(2, 2);

    assertThat(map.size(), is(1));
    assertThat(Math.max(0, map.weightedSize()), is(BoundedLocalCache.MAXIMUM_CAPACITY));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.ONE)
  public void evict_alreadyRemoved(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Map.Entry<Integer, Integer> oldEntry = Iterables.get(context.absent().entrySet(), 0);
    Map.Entry<Integer, Integer> newEntry = Iterables.get(context.absent().entrySet(), 1);

    AtomicBoolean removed = new AtomicBoolean();
    localCache.put(oldEntry.getKey(), oldEntry.getValue());
    localCache.evictionLock.lock();
    try {
      Object lookupKey = localCache.nodeFactory.newLookupKey(oldEntry.getKey());
      Node<Integer, Integer> node = localCache.data.get(lookupKey);
      checkStatus(node, Status.ALIVE);
      ConcurrentTestHarness.execute(() -> {
        localCache.put(newEntry.getKey(), newEntry.getValue());
        assertThat(localCache.remove(oldEntry.getKey()), is(oldEntry.getValue()));
        removed.set(true);
      });
      await().until(() -> localCache.containsKey(oldEntry.getKey()), is(false));
      await().untilTrue(removed);
      await().until(() -> {
        synchronized (node) {
          return !node.isAlive();
        }
      });
      checkStatus(node, Status.RETIRED);
      localCache.cleanUp();

      checkStatus(node, Status.DEAD);
      assertThat(localCache.containsKey(newEntry.getKey()), is(true));
      verifyRemovalListener(context, verifier -> verifier.hasOnly(1, RemovalCause.EXPLICIT));
    } finally {
      localCache.evictionLock.unlock();
    }
  }

  enum Status { ALIVE, RETIRED, DEAD }

  static void checkStatus(Node<Integer, Integer> node, Status expected) {
    synchronized (node) {
      assertThat(node.isAlive(), is(expected == Status.ALIVE));
      assertThat(node.isRetired(), is(expected == Status.RETIRED));
      assertThat(node.isDead(), is(expected == Status.DEAD));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.DEFAULT)
  public void evict_wtinylfu(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    // Enforce full initialization of internal structures; clear sketch
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    localCache.frequencySketch().ensureCapacity(10);

    for (int i = 0; i < 10; i++) {
      cache.put(i, -i);
    }

    checkContainsInOrder(cache, 9, 0, 1, 2, 3, 4, 5, 6, 7, 8);

    // re-order
    checkReorder(cache, asList(0, 1, 2), 9, 3, 4, 5, 6, 7, 8, 0, 1, 2);

    // evict 9, 10, 11
    checkEvict(cache, asList(10, 11, 12), 12, 3, 4, 5, 6, 7, 8, 0, 1, 2);

    // re-order
    checkReorder(cache, asList(6, 7, 8), 12, 3, 4, 5, 0, 1, 2, 6, 7, 8);

    // evict 12, 13, 14
    checkEvict(cache, asList(13, 14, 15), 15, 3, 4, 5, 0, 1, 2, 6, 7, 8);

    verifyStats(context, verifier -> verifier.evictions(6));
  }

  private void checkReorder(Cache<Integer, Integer> cache, List<Integer> keys, Integer... expect) {
    keys.forEach(cache::getIfPresent);
    checkContainsInOrder(cache, expect);
  }

  private void checkEvict(Cache<Integer, Integer> cache, List<Integer> keys, Integer... expect) {
    keys.forEach(i -> cache.put(i, i));
    checkContainsInOrder(cache, expect);
  }

  private void checkContainsInOrder(Cache<Integer, Integer> cache, Integer... expect) {
    cache.cleanUp();
    List<Integer> evictionList = ImmutableList.copyOf(
        cache.policy().eviction().get().coldest(Integer.MAX_VALUE).keySet());
    assertThat(cache.asMap().size(), is(equalTo(expect.length)));
    assertThat(cache.asMap().keySet(), containsInAnyOrder(expect));
    assertThat(evictionList, is(equalTo(asList(expect))));
  }

  @Test(groups = "slow")
  public void evict_update() {
    Integer key = 0;
    Integer oldValue = 1;
    Integer newValue = 2;

    Thread evictor = Thread.currentThread();
    AtomicBoolean started = new AtomicBoolean();
    AtomicBoolean writing = new AtomicBoolean();
    AtomicInteger evictedValue = new AtomicInteger();
    AtomicInteger removedValues = new AtomicInteger();
    AtomicInteger previousValue = new AtomicInteger();

    RemovalListener<Integer, Integer> evictionListener = (k, v, cause) -> evictedValue.set(v);
    RemovalListener<Integer, Integer> removalListener = (k, v, cause) -> removedValues.addAndGet(v);

    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .evictionListener(evictionListener)
        .removalListener(removalListener)
        .executor(Runnable::run)
        .maximumSize(100)
        .build();
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(key, oldValue);
    started.set(true);

    ConcurrentTestHarness.execute(() -> {
      localCache.compute(key, (k, v) -> {
        if (started.get()) {
          writing.set(true);
          await().until(evictor::getState, is(Thread.State.BLOCKED));
        }
        previousValue.set(v);
        return newValue;
      });
    });
    await().untilTrue(writing);

    Node<Integer, Integer> node = localCache.data.values().iterator().next();
    localCache.evictEntry(node, RemovalCause.SIZE, 0L);

    await().untilAtomic(evictedValue, is(newValue));
    await().untilAtomic(previousValue, is(oldValue));
    await().untilAtomic(removedValues, is(oldValue + newValue));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, initialCapacity = InitialCapacity.EXCESSIVE,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.VALUE, removalListener = Listener.CONSUMING)
  public void evict_update_entryTooBig_window(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(9, 9);
    cache.put(1, 1);

    assertThat(localCache.data.get(1).inWindow(), is(true));
    cache.put(1, 20);

    assertThat(localCache.weightedSize(), is(lessThanOrEqualTo(context.maximumSize())));
    assertThat(context.removalNotifications(), hasItem(
        new RemovalNotification<>(1, 20, RemovalCause.SIZE)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, initialCapacity = InitialCapacity.EXCESSIVE,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.VALUE, removalListener = Listener.CONSUMING)
  public void evict_update_entryTooBig_probation(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    for (int i = 1; i <= 10; i++) {
      cache.put(i, 1);
    }

    assertThat(localCache.data.get(1).inMainProbation(), is(true));
    cache.put(1, 20);

    assertThat(localCache.weightedSize(), is(lessThanOrEqualTo(context.maximumSize())));
    assertThat(context.removalNotifications(), hasItem(
        new RemovalNotification<>(1, 20, RemovalCause.SIZE)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, initialCapacity = InitialCapacity.EXCESSIVE,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG, maximumSize = Maximum.TEN,
      weigher = CacheWeigher.VALUE, removalListener = Listener.CONSUMING)
  public void evict_update_entryTooBig_protected(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    for (int i = 1; i <= 10; i++) {
      cache.put(i, 1);
      cache.getIfPresent(1);
    }
    cache.cleanUp();

    assertThat(localCache.data.get(1).inMainProtected(), is(true));
    cache.put(1, 20);

    assertThat(localCache.weightedSize(), is(lessThanOrEqualTo(context.maximumSize())));
    assertThat(context.removalNotifications(), hasItem(
        new RemovalNotification<>(1, 20, RemovalCause.SIZE)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onGet(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = firstBeforeAccess(localCache, context);
    updateRecency(localCache, context, () -> localCache.get(first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onPutIfAbsent(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = firstBeforeAccess(localCache, context);
    updateRecency(localCache, context, () ->
        localCache.putIfAbsent(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onPut(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = firstBeforeAccess(localCache, context);
    updateRecency(localCache, context, () -> localCache.put(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onReplace(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = firstBeforeAccess(localCache, context);
    updateRecency(localCache, context, () -> localCache.replace(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onReplaceConditionally(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = firstBeforeAccess(localCache, context);
    Integer value = first.getValue();

    updateRecency(localCache, context, () -> localCache.replace(first.getKey(), value, value));
  }

  private static Node<Integer, Integer> firstBeforeAccess(
      BoundedLocalCache<Integer, Integer> localCache, CacheContext context) {
    return context.isZeroWeighted()
        ? localCache.accessOrderWindowDeque().peek()
        : localCache.accessOrderProbationDeque().peek();
  }

  private static void updateRecency(BoundedLocalCache<Integer, Integer> cache,
      CacheContext context, Runnable operation) {
    Node<Integer, Integer> first = firstBeforeAccess(cache, context);

    operation.run();
    cache.maintenance(/* ignored */ null);

    if (context.isZeroWeighted()) {
      assertThat(cache.accessOrderWindowDeque().peekFirst(), is(not(first)));
      assertThat(cache.accessOrderWindowDeque().peekLast(), is(first));
    } else {
      assertThat(cache.accessOrderProbationDeque().peekFirst(), is(not(first)));
      assertThat(cache.accessOrderProtectedDeque().peekLast(), is(first));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void exceedsMaximumBufferSize_onRead(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> dummy = localCache.nodeFactory.newNode(
        new WeakKeyReference<>(null, null), null, null, 1, 0);
    localCache.frequencySketch().ensureCapacity(1);

    Buffer<Node<Integer, Integer>> buffer = localCache.readBuffer;
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      buffer.offer(dummy);
    }
    assertThat(buffer.offer(dummy), is(Buffer.FULL));

    localCache.afterRead(dummy, 0, /* recordHit */ true);
    assertThat(buffer.offer(dummy), is(not(Buffer.FULL)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void exceedsMaximumBufferSize_onWrite(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);

    boolean[] ran = new boolean[1];
    localCache.afterWrite(() -> ran[0] = true);
    assertThat(ran[0], is(true));

    assertThat(localCache.writeBuffer().size(), is(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL, weigher = CacheWeigher.DEFAULT,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      expiry = CacheExpiry.DISABLED, keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void fastpath(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    assertThat(localCache.skipReadBuffer(), is(true));

    for (int i = 0; i < context.maximumSize() / 2; i++) {
      cache.put(i, -i);
    }
    assertThat(localCache.skipReadBuffer(), is(true));

    cache.put(-1, -1);
    assertThat(localCache.skipReadBuffer(), is(false));
    assertThat(cache.getIfPresent(0), is(not(nullValue())));
    assertThat(localCache.readBuffer.writes(), is(1L));

    cache.cleanUp();
    assertThat(localCache.readBuffer.reads(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void afterWrite_drainFullWriteBuffer(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    localCache.drainStatus = PROCESSING_TO_IDLE;

    int[] processed = { 0 };
    Runnable pendingTask = () -> processed[0]++;

    int[] expectedCount = { 0 };
    while (localCache.writeBuffer().offer(pendingTask)) {
      expectedCount[0]++;
    }

    int[] triggered = { 0 };
    Runnable triggerTask = () -> triggered[0] = 1 + expectedCount[0];
    localCache.afterWrite(triggerTask);

    assertThat(processed[0], is(expectedCount[0]));
    assertThat(triggered[0], is(expectedCount[0] + 1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void drain_onRead(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);

    Buffer<Node<Integer, Integer>> buffer = localCache.readBuffer;
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      localCache.get(context.firstKey());
    }

    long pending = buffer.size();
    assertThat(buffer.writes(), is(equalTo(pending)));
    assertThat(pending, is((long) BoundedBuffer.BUFFER_SIZE));

    localCache.get(context.firstKey());
    assertThat(buffer.size(), is(0L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL)
  public void drain_onRead_absent(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Buffer<Node<Integer, Integer>> buffer = localCache.readBuffer;
    localCache.get(context.firstKey());
    assertThat(buffer.size(), is(1L));

    assertThat(localCache.get(context.absentKey()), is(nullValue()));
    assertThat(buffer.size(), is(1L));

    localCache.drainStatus = REQUIRED;
    assertThat(localCache.get(context.absentKey()), is(nullValue()));
    assertThat(buffer.size(), is(0L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_onWrite(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(1, 1);

    int size = localCache.accessOrderWindowDeque().size()
        + localCache.accessOrderProbationDeque().size();
    assertThat(localCache.writeBuffer().size(), is(0));
    assertThat(size, is(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_nonblocking(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    AtomicBoolean done = new AtomicBoolean();
    Runnable task = () -> {
      localCache.lazySetDrainStatus(REQUIRED);
      localCache.scheduleDrainBuffers();
      done.set(true);
    };
    localCache.evictionLock.lock();
    try {
      ConcurrentTestHarness.execute(task);
      await().untilTrue(done);
    } finally {
      localCache.evictionLock.unlock();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_blocksClear(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, localCache::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_blocksOrderedMap(Cache<Integer, Integer> cache,
      CacheContext context, Eviction<Integer, Integer> eviction) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> eviction.coldest(((int) context.maximumSize())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_blocksCapacity(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> cache.policy().eviction().ifPresent(
        policy -> policy.setMaximum(0L)));
  }

  void checkDrainBlocks(BoundedLocalCache<Integer, Integer> localCache, Runnable task) {
    AtomicBoolean done = new AtomicBoolean();
    ReentrantLock lock = localCache.evictionLock;
    lock.lock();
    try {
      ConcurrentTestHarness.execute(() -> {
        localCache.lazySetDrainStatus(REQUIRED);
        task.run();
        done.set(true);
      });
      await().until(lock::hasQueuedThreads);
    } finally {
      lock.unlock();
    }
    await().untilTrue(done);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL,
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN})
  public void adapt_increaseWindow(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = prepareForAdaption(
        cache, context, /* make frequency-bias */ false);

    int sampleSize = localCache.frequencySketch().sampleSize;
    long protectedSize = localCache.mainProtectedWeightedSize();
    long protectedMaximum = localCache.mainProtectedMaximum();
    long windowSize = localCache.windowWeightedSize();
    long windowMaximum = localCache.windowMaximum();

    adapt(cache, localCache, sampleSize);

    assertThat(localCache.mainProtectedMaximum(),
        is(either(lessThan(protectedMaximum)).or(is(0L))));
    assertThat(localCache.mainProtectedWeightedSize(),
        is(either(lessThan(protectedSize)).or(is(0L))));
    assertThat(localCache.windowMaximum(), is(greaterThan(windowMaximum)));
    assertThat(localCache.windowWeightedSize(), is(greaterThan(windowSize)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL,
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN})
  public void adapt_decreaseWindow(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = prepareForAdaption(
        cache, context, /* make recency-bias */ true);

    int sampleSize = localCache.frequencySketch().sampleSize;
    long protectedSize = localCache.mainProtectedWeightedSize();
    long protectedMaximum = localCache.mainProtectedMaximum();
    long windowSize = localCache.windowWeightedSize();
    long windowMaximum = localCache.windowMaximum();

    adapt(cache, localCache, sampleSize);

    assertThat(localCache.mainProtectedMaximum(), is(greaterThan(protectedMaximum)));
    assertThat(localCache.mainProtectedWeightedSize(), is(greaterThan(protectedSize)));
    assertThat(localCache.windowMaximum(), is(either(lessThan(windowMaximum)).or(is(0L))));
    assertThat(localCache.windowWeightedSize(), is(either(lessThan(windowSize)).or(is(0L))));
  }

  private BoundedLocalCache<Integer, Integer> prepareForAdaption(
      Cache<Integer, Integer> cache, CacheContext context, boolean recencyBias) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);

    localCache.setStepSize((recencyBias ? 1 : -1) * Math.abs(localCache.stepSize()));
    localCache.setWindowMaximum((long) (0.5 * context.maximumWeightOrSize()));
    localCache.setMainProtectedMaximum((long)
        (PERCENT_MAIN_PROTECTED * (context.maximumWeightOrSize() - localCache.windowMaximum())));

    // Fill window and main spaces
    cache.invalidateAll();
    cache.asMap().putAll(context.original());
    cache.asMap().keySet().stream().forEach(cache::getIfPresent);
    cache.asMap().keySet().stream().forEach(cache::getIfPresent);
    return localCache;
  }

  private void adapt(Cache<Integer, Integer> cache,
      BoundedLocalCache<Integer, Integer> localCache, int sampleSize) {
    localCache.setPreviousSampleHitRate(0.80);
    localCache.setMissesInSample(sampleSize / 2);
    localCache.setHitsInSample(sampleSize - localCache.missesInSample());
    localCache.climb();

    // Fill main protected space
    cache.asMap().keySet().stream().forEach(cache::getIfPresent);
  }

  /* --------------- Expiration --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, initialCapacity = InitialCapacity.FULL,
      expireAfterWrite = Expire.ONE_MINUTE)
  public void put_expireTolerance_expireAfterWrite(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    boolean mayCheckReads = context.isStrongKeys() && context.isStrongValues()
        && localCache.readBuffer != Buffer.<Node<Integer, Integer>>disabled();

    cache.put(1, 1);
    assertThat(localCache.writeBuffer().producerIndex, is(2L));

    // If within the tolerance, treat the update as a read
    cache.put(1, 2);
    if (mayCheckReads) {
      assertThat(localCache.readBuffer.reads(), is(0L));
      assertThat(localCache.readBuffer.writes(), is(1L));
    }
    assertThat(localCache.writeBuffer().producerIndex, is(2L));

    // If exceeds the tolerance, treat the update as a write
    context.ticker().advance(EXPIRE_WRITE_TOLERANCE + 1, TimeUnit.NANOSECONDS);
    cache.put(1, 3);
    if (mayCheckReads) {
      assertThat(localCache.readBuffer.reads(), is(1L));
      assertThat(localCache.readBuffer.writes(), is(1L));
    }
    assertThat(localCache.writeBuffer().producerIndex, is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void put_expireTolerance_expiry(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(1, 1);
    assertThat(localCache.writeBuffer().producerIndex, is(2L));

    // If within the tolerance, treat the update as a read
    cache.put(1, 2);
    assertThat(localCache.readBuffer.reads(), is(0L));
    assertThat(localCache.readBuffer.writes(), is(1L));
    assertThat(localCache.writeBuffer().producerIndex, is(2L));

    // If exceeds the tolerance, treat the update as a write
    context.ticker().advance(EXPIRE_WRITE_TOLERANCE + 1, TimeUnit.NANOSECONDS);
    cache.put(1, 3);
    assertThat(localCache.readBuffer.reads(), is(1L));
    assertThat(localCache.readBuffer.writes(), is(1L));
    assertThat(localCache.writeBuffer().producerIndex, is(4L));

    // If the expire time reduces by more than the tolerance, treat the update as a write
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Expire.ONE_MILLISECOND.timeNanos());
    cache.put(1, 4);
    assertThat(localCache.readBuffer.reads(), is(1L));
    assertThat(localCache.readBuffer.writes(), is(1L));
    assertThat(localCache.writeBuffer().producerIndex, is(6L));

    // If the expire time increases by more than the tolerance, treat the update as a write
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Expire.FOREVER.timeNanos());
    cache.put(1, 4);
    assertThat(localCache.readBuffer.reads(), is(1L));
    assertThat(localCache.readBuffer.writes(), is(1L));
    assertThat(localCache.writeBuffer().producerIndex, is(8L));
  }
}
