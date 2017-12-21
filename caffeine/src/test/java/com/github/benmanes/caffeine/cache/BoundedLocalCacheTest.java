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
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasEvictionCount;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.mockito.Mockito;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

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
import com.github.benmanes.caffeine.testing.Awaits;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * The test cases for the implementation details of {@link BoundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class BoundedLocalCacheTest {
  final Executor executor = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setDaemon(true).build());

  static BoundedLocalCache<Integer, Integer> asBoundedLocalCache(Cache<Integer, Integer> cache) {
    return (BoundedLocalCache<Integer, Integer>) cache.asMap();
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

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = Maximum.FULL,
      executorFailure = ExecutorFailure.EXPECTED, executor = CacheExecutor.REJECTING,
      removalListener = Listener.CONSUMING)
  public void scheduleDrainBuffers_rejected(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @Test
  public void putWeighted_noOverflow() {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(CacheExecutor.DIRECT.create())
        .weigher(CacheWeigher.MAX_VALUE)
        .maximumWeight(Long.MAX_VALUE)
        .build();
    BoundedLocalCache<Integer, Integer> map = asBoundedLocalCache(cache);

    cache.put(1, 1);
    map.lazySetEdenMaximum(0L);
    map.lazySetWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.put(2, 2);

    assertThat(map.size(), is(1));
    assertThat(map.adjustedWeightedSize(), is(BoundedLocalCache.MAXIMUM_CAPACITY));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.ONE)
  public void evict_alreadyRemoved(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Entry<Integer, Integer> oldEntry = Iterables.get(context.absent().entrySet(), 0);
    Entry<Integer, Integer> newEntry = Iterables.get(context.absent().entrySet(), 1);

    localCache.put(oldEntry.getKey(), oldEntry.getValue());
    localCache.evictionLock.lock();
    try {
      Object lookupKey = localCache.nodeFactory.newLookupKey(oldEntry.getKey());
      Node<Integer, Integer> node = localCache.data.get(lookupKey);
      checkStatus(node, Status.ALIVE);
      ConcurrentTestHarness.execute(() -> {
        localCache.put(newEntry.getKey(), newEntry.getValue());
        assertThat(localCache.remove(oldEntry.getKey()), is(oldEntry.getValue()));
      });
      Awaits.await().until(() -> localCache.containsKey(oldEntry.getKey()), is(false));
      Awaits.await().until(() -> {
        synchronized (node) {
          return !node.isAlive();
        }
      });
      checkStatus(node, Status.RETIRED);
      localCache.cleanUp();

      checkStatus(node, Status.DEAD);
      assertThat(localCache.containsKey(newEntry.getKey()), is(true));
      Awaits.await().until(() -> cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
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

    assertThat(context, hasEvictionCount(6));
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

    CacheWriter<Integer, Integer> writer = new CacheWriter<Integer, Integer>() {
      @Override public void write(Integer key, Integer value) {
        if (started.get()) {
          writing.set(true);
          await().until(evictor::getState, is(Thread.State.BLOCKED));
        }
      }
      @Override public void delete(Integer key, Integer value, RemovalCause cause) {
        evictedValue.set(value);
      }
    };
    RemovalListener<Integer, Integer> listener = (k, v, cause) -> removedValues.addAndGet(v);

    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .removalListener(listener)
        .executor(Runnable::run)
        .maximumSize(100)
        .writer(writer)
        .build();
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(key, oldValue);
    started.set(true);

    ConcurrentTestHarness.execute(() -> previousValue.set(localCache.put(key, newValue)));
    await().untilTrue(writing);

    Node<Integer, Integer> node = localCache.data.values().iterator().next();
    localCache.evictEntry(node, RemovalCause.SIZE, 0L);

    await().untilAtomic(evictedValue, is(newValue));
    await().untilAtomic(previousValue, is(oldValue));
    await().untilAtomic(removedValues, is(oldValue + newValue));
  }

  @Test(groups = "slow")
  public void clear_update() {
    Integer key = 0;
    Integer oldValue = 1;
    Integer newValue = 2;

    Thread invalidator = Thread.currentThread();
    AtomicBoolean started = new AtomicBoolean();
    AtomicBoolean writing = new AtomicBoolean();
    AtomicInteger clearedValue = new AtomicInteger();
    AtomicInteger removedValues = new AtomicInteger();
    AtomicInteger previousValue = new AtomicInteger();

    CacheWriter<Integer, Integer> writer = new CacheWriter<Integer, Integer>() {
      @Override public void write(Integer key, Integer value) {
        if (started.get()) {
          writing.set(true);
          await().until(invalidator::getState, is(Thread.State.BLOCKED));
        }
      }
      @Override public void delete(Integer key, Integer value, RemovalCause cause) {
        clearedValue.set(value);
      }
    };
    RemovalListener<Integer, Integer> listener = (k, v, cause) -> removedValues.addAndGet(v);

    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .removalListener(listener)
        .executor(Runnable::run)
        .maximumSize(100)
        .writer(writer)
        .build();
    cache.put(key, oldValue);
    started.set(true);

    ConcurrentTestHarness.execute(() -> previousValue.set(cache.asMap().put(key, newValue)));
    await().untilTrue(writing);
    cache.asMap().clear();

    await().untilAtomic(clearedValue, is(newValue));
    await().untilAtomic(previousValue, is(oldValue));
    await().untilAtomic(removedValues, is(oldValue + newValue));
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
    updateRecency(localCache, context, () -> localCache.putIfAbsent(first.getKey(), first.getKey()));
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
        ? localCache.accessOrderEdenDeque().peek()
        : localCache.accessOrderProbationDeque().peek();
  }

  private static void updateRecency(BoundedLocalCache<Integer, Integer> cache,
      CacheContext context, Runnable operation) {
    Node<Integer, Integer> first = firstBeforeAccess(cache, context);

    operation.run();
    cache.maintenance(/* ignored */ null);

    if (context.isZeroWeighted()) {
      assertThat(cache.accessOrderEdenDeque().peekFirst(), is(not(first)));
      assertThat(cache.accessOrderEdenDeque().peekLast(), is(first));
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
  public void exceedsMaximumBufferSize_onWrite(Cache<Integer, Integer> cache, CacheContext context) {
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
    assertThat(localCache.readBuffer.writes(), is(1));

    cache.cleanUp();
    assertThat(localCache.readBuffer.reads(), is(1));
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

    int pending = buffer.size();
    assertThat(buffer.writes(), is(equalTo(pending)));
    assertThat(pending, is(BoundedBuffer.BUFFER_SIZE));

    localCache.get(context.firstKey());
    assertThat(buffer.size(), is(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_onWrite(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(1, 1);

    int size = localCache.accessOrderEdenDeque().size()
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
      Awaits.await().untilTrue(done);
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
      executor.execute(() -> {
        localCache.lazySetDrainStatus(REQUIRED);
        task.run();
        done.set(true);
      });
      Awaits.await().until(lock::hasQueuedThreads);
    } finally {
      lock.unlock();
    }
    Awaits.await().untilTrue(done);
  }
}
