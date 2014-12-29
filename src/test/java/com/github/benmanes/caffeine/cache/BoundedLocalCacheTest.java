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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.jayway.awaitility.Awaitility.await;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.mockito.Mockito;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.atomic.PaddedAtomicLong;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.LocalManualCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.Node;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.Lists;

/**
 * The test cases for the implementation details of {@link BoundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class BoundedLocalCacheTest {

  static BoundedLocalCache<Integer, Integer> asBoundedLocalCache(Cache<Integer, Integer> cache) {
    LocalManualCache<Integer, Integer> local = (LocalManualCache<Integer, Integer>) cache;
    return local.cache;
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = MaximumSize.ONE)
  public void evict_alreadyRemoved(Cache<Integer, Integer> cache, CacheContext context) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    localCache.put(context.firstKey(), -context.firstKey());
    localCache.evictionLock.lock();
    try {
      Node<Integer, Integer> node = localCache.data.get(context.firstKey());
      checkStatus(localCache, node, Status.ALIVE);
      new Thread(() -> {
        localCache.put(context.absentKey(), -context.absentKey());
        assertThat(localCache.remove(context.firstKey()), is(-context.firstKey()));
      }).start();
      await().until(() -> localCache.containsKey(context.firstKey()), is(false));
      checkStatus(localCache, node, Status.RETIRED);
      localCache.drainBuffers();

      checkStatus(localCache, node, Status.DEAD);
      assertThat(localCache.containsKey(context.absentKey()), is(true));
      assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPLICIT));
    } finally {
      localCache.evictionLock.unlock();
    }
  }

  enum Status { ALIVE, RETIRED, DEAD }

  static void checkStatus(BoundedLocalCache<Integer, Integer> localCache,
      Node<Integer, Integer> node, Status expected) {
    assertThat(node.get().isAlive(), is(expected == Status.ALIVE));
    assertThat(node.get().isRetired(), is(expected == Status.RETIRED));
    assertThat(node.get().isDead(), is(expected == Status.DEAD));

    if (node.get().isRetired() || node.get().isDead()) {
      assertThat(localCache.tryToRetire(node, node.get()), is(false));
    }
    if (node.get().isDead()) {
      localCache.makeRetired(node);
      assertThat(node.get().isRetired(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = MaximumSize.TEN)
  public void evict_lru(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    for (int i = 0; i < 10; i++) {
      cache.put(i, -i);
    }

    checkContainsInOrder(localCache, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    // re-order
    checkReorder(localCache, asList(0, 1, 2), 3, 4, 5, 6, 7, 8, 9, 0, 1, 2);

    // evict 3, 4, 5
    checkEvict(localCache, asList(10, 11, 12), 6, 7, 8, 9, 0, 1, 2, 10, 11, 12);

    // re-order
    checkReorder(localCache, asList(6, 7, 8), 9, 0, 1, 2, 10, 11, 12, 6, 7, 8);

    // evict 9, 0, 1
    checkEvict(localCache, asList(13, 14, 15), 2, 10, 11, 12, 6, 7, 8, 13, 14, 15);
  }

  private void checkReorder(BoundedLocalCache<Integer, Integer> localCache,
      List<Integer> keys, Integer... expect) {
    keys.forEach(localCache::get);
    checkContainsInOrder(localCache, expect);
  }

  private void checkEvict(BoundedLocalCache<Integer, Integer> localCache,
      List<Integer> keys, Integer... expect) {
    keys.forEach(i -> localCache.put(i, i));
    checkContainsInOrder(localCache, expect);
  }

  private void checkContainsInOrder(BoundedLocalCache<Integer, Integer> localCache,
      Integer... expect) {
    localCache.drainBuffers();
    List<Integer> evictionList = Lists.newArrayList();
    localCache.evictionDeque.forEach(node -> evictionList.add(node.key));
    assertThat(localCache.size(), is(equalTo(expect.length)));
    assertThat(localCache.keySet(), containsInAnyOrder(expect));
    assertThat(evictionList, is(equalTo(asList(expect))));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onGet(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    final Node<Integer, Integer> first = localCache.evictionDeque.peek();
    updateRecency(localCache, () -> localCache.get(first.key));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onGetQuietly(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    int index = BoundedLocalCache.readBufferIndex();
    PaddedAtomicLong drainCounter = localCache.readBufferDrainAtWriteCount[index];

    Node<Integer, Integer> first = localCache.evictionDeque.peek();
    Node<Integer, Integer> last = localCache.evictionDeque.peekLast();
    long drained = drainCounter.get();

    localCache.getQuietly(first.key);
    localCache.drainBuffers();

    assertThat(localCache.evictionDeque.peekFirst(), is(first));
    assertThat(localCache.evictionDeque.peekLast(), is(last));
    assertThat(drainCounter.get(), is(drained));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onPutIfAbsent(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    final Node<Integer, Integer> first = localCache.evictionDeque.peek();
    updateRecency(localCache, () -> localCache.putIfAbsent(first.key, first.key));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onPut(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    final Node<Integer, Integer> first = localCache.evictionDeque.peek();
    updateRecency(localCache, () -> localCache.put(first.key, first.key));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onReplace(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    final Node<Integer, Integer> first = localCache.evictionDeque.peek();
    updateRecency(localCache, () -> localCache.replace(first.key, first.key));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void updateRecency_onReplaceConditionally(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    final Node<Integer, Integer> first = localCache.evictionDeque.peek();
    updateRecency(localCache, () -> localCache.replace(first.key, -first.key, -first.key));
  }

  private void updateRecency(BoundedLocalCache<Integer, Integer> cache, Runnable operation) {
    Node<Integer, Integer> first = cache.evictionDeque.peek();

    operation.run();
    cache.drainBuffers();

    assertThat(cache.evictionDeque.peekFirst(), is(not(first)));
    assertThat(cache.evictionDeque.peekLast(), is(first));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void exceedsMaximumBufferSize_onRead(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);

    int index = BoundedLocalCache.readBufferIndex();
    PaddedAtomicLong drainCounter = localCache.readBufferDrainAtWriteCount[index];
    localCache.readBufferWriteCount[index].set(BoundedLocalCache.READ_BUFFER_THRESHOLD - 1);

    localCache.afterRead(null);
    assertThat(drainCounter.get(), is(0L));

    localCache.afterRead(null);
    assertThat(drainCounter.get(), is(BoundedLocalCache.READ_BUFFER_THRESHOLD + 1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = MaximumSize.FULL)
  public void exceedsMaximumBufferSize_onWrite(Cache<Integer, Integer> cache) {
    BoundedLocalCache<Integer, Integer> localCache = asBoundedLocalCache(cache);
    Runnable task = Mockito.mock(Runnable.class);
    localCache.afterWrite(task);
    verify(task).run();

    assertThat(localCache.writeBuffer, hasSize(0));
  }
}
