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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.LocalManualCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.Node;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;

/**
 * The test cases for the implementation details of {@link BoundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class BoundedLocalCacheTest {

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

  static BoundedLocalCache<Integer, Integer> asBoundedLocalCache(Cache<Integer, Integer> cache) {
    LocalManualCache<Integer, Integer> local = (LocalManualCache<Integer, Integer>) cache;
    return local.cache;
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
}
