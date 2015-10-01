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

import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.REQUIRED;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasEvictionCount;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.locks.NonReentrantLock;
import com.github.benmanes.caffeine.testing.Awaits;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.collect.ImmutableList;
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
  public void putWeighted_noOverflow() {
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .executor(CacheExecutor.DIRECT.get())
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
      population = Population.FULL, maximumSize = MaximumSize.FULL,
      executor = CacheExecutor.REJECTING, removalListener = Listener.CONSUMING)
  public void evict_rejected(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.ONE)
  public void evict_alreadyRemoved(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Entry<Integer, Integer> oldEntry = Iterables.get(context.absent().entrySet(), 0);
    Entry<Integer, Integer> newEntry = Iterables.get(context.absent().entrySet(), 1);

    localCache.put(oldEntry.getKey(), oldEntry.getValue());
    localCache.evictionLock.lock();
    try {
      Object keyRef = localCache.nodeFactory.newLookupKey(oldEntry.getKey());
      Node<Integer, Integer> node = localCache.data.get(keyRef);
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
      localCache.maintenance();

      checkStatus(node, Status.DEAD);
      assertThat(localCache.containsKey(newEntry.getKey()), is(true));
      assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
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
      maximumSize = MaximumSize.TEN, weigher = CacheWeigher.DEFAULT)
  public void evict_wtinylfu(Cache<Integer, Integer> cache, CacheContext context) {
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

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onGet(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.accessOrderMainDeque().peek();
    updateRecency(localCache, () -> localCache.get(first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onPutIfAbsent(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.accessOrderMainDeque().peek();
    updateRecency(localCache, () -> localCache.putIfAbsent(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onPut(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.accessOrderMainDeque().peek();
    updateRecency(localCache, () -> localCache.put(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onReplace(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.accessOrderMainDeque().peek();
    updateRecency(localCache, () -> localCache.replace(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onReplaceConditionally(
      Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> first = localCache.accessOrderMainDeque().peek();
    Integer key = first.getKey();
    Integer value = context.original().get(key);

    updateRecency(localCache, () -> localCache.replace(first.getKey(), value, value));
  }

  private void updateRecency(BoundedLocalCache<Integer, Integer> cache, Runnable operation) {
    Node<Integer, Integer> first = cache.accessOrderMainDeque().peek();

    operation.run();
    cache.maintenance();

    assertThat(cache.accessOrderMainDeque().peekFirst(), is(not(first)));
    assertThat(cache.accessOrderMainDeque().peekLast(), is(first));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
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

    localCache.afterRead(dummy, 0, true);
    assertThat(buffer.offer(dummy), is(not(Buffer.FULL)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void exceedsMaximumBufferSize_onWrite(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Node<Integer, Integer> dummy = localCache.nodeFactory.newNode(null, null, null, 1, 0);

    boolean[] ran = new boolean[1];
    localCache.afterWrite(dummy, () -> ran[0] = true, 0);
    assertThat(ran[0], is(true));

    assertThat(localCache.writeQueue(), hasSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
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
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_onWrite(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    cache.put(1, 1);

    int size = localCache.accessOrderEdenDeque().size() + localCache.accessOrderMainDeque().size();
    assertThat(localCache.writeQueue(), hasSize(0));
    assertThat(size, is(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_nonblocking(Cache<Integer, Integer> cache) {
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
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_blocksClear(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, localCache::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_blocksOrderedMap(Cache<Integer, Integer> cache,
      CacheContext context, Eviction<Integer, Integer> eviction) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> eviction.coldest(((int) context.maximumSize())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void drain_blocksCapacity(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    checkDrainBlocks(localCache, () -> cache.policy().eviction().ifPresent(
        policy -> policy.setMaximum(0L)));
  }

  void checkDrainBlocks(BoundedLocalCache<Integer, Integer> localCache, Runnable task) {
    AtomicBoolean done = new AtomicBoolean();
    Lock lock = localCache.evictionLock;
    lock.lock();
    try {
      executor.execute(() -> {
        localCache.lazySetDrainStatus(REQUIRED);
        task.run();
        done.set(true);
      });
      Awaits.await().until(() -> hasQueuedThreads(lock));
    } finally {
      lock.unlock();
    }
    Awaits.await().untilTrue(done);
  }

  private boolean hasQueuedThreads(Lock lock) {
    return (lock instanceof NonReentrantLock)
        ? ((NonReentrantLock) lock).hasQueuedThreads()
        : ((ReentrantLock) lock).hasQueuedThreads();
  }
}
