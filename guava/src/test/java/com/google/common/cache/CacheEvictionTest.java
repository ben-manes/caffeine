/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.cache;

import static com.google.common.cache.TestingCacheLoaders.identityLoader;
import static com.google.common.cache.TestingRemovalListeners.countingRemovalListener;
import static com.google.common.cache.TestingWeighers.constantWeigher;
import static com.google.common.cache.TestingWeighers.intKeyWeigher;
import static com.google.common.cache.TestingWeighers.intValueWeigher;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.google.common.cache.TestingCacheLoaders.IdentityLoader;
import com.google.common.cache.TestingRemovalListeners.CountingRemovalListener;
import com.google.common.util.concurrent.MoreExecutors;

import junit.framework.TestCase;

/**
 * Tests relating to cache eviction: what does and doesn't count toward maximumSize, what happens
 * when maximumSize is reached, etc.
 *
 * @author mike nonemacher
 */
public class CacheEvictionTest extends TestCase {
  static final int MAX_SIZE = 100;

  public void testEviction_maxSizeOneSegment() {
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .maximumSize(MAX_SIZE), loader);
    for (int i = 0; i < 2 * MAX_SIZE; i++) {
      cache.getUnchecked(i);
      assertEquals(Math.min(i + 1, MAX_SIZE), cache.size());
    }

    assertEquals(MAX_SIZE, cache.size());
    CacheTesting.checkValidState(cache);
  }

  public void testEviction_maxWeightOneSegment() {
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .maximumWeight(2 * MAX_SIZE)
        .weigher(constantWeigher(2)), loader);
    for (int i = 0; i < 2 * MAX_SIZE; i++) {
      cache.getUnchecked(i);
      assertEquals(Math.min(i + 1, MAX_SIZE), cache.size());
    }

    assertEquals(MAX_SIZE, cache.size());
    CacheTesting.checkValidState(cache);
  }

  public void testEviction_maxSize() {
    CountingRemovalListener<Integer, Integer> removalListener = countingRemovalListener();
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumSize(MAX_SIZE)
        .executor(MoreExecutors.directExecutor())
        .removalListener(removalListener), loader);
    for (int i = 0; i < 2 * MAX_SIZE; i++) {
      cache.getUnchecked(i);
      assertTrue(cache.size() <= MAX_SIZE);
    }

    assertEquals(MAX_SIZE, cache.size());
    assertEquals(MAX_SIZE, removalListener.getCount());
    CacheTesting.checkValidState(cache);
  }

  public void testEviction_maxWeight() {
    CountingRemovalListener<Integer, Integer> removalListener = countingRemovalListener();
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumWeight(2 * MAX_SIZE)
        .weigher(constantWeigher(2))
        .executor(MoreExecutors.directExecutor())
        .removalListener(removalListener), loader);
    for (int i = 0; i < 2 * MAX_SIZE; i++) {
      cache.getUnchecked(i);
      assertTrue(cache.size() <= MAX_SIZE);
    }

    assertEquals(MAX_SIZE, cache.size());
    assertEquals(MAX_SIZE, removalListener.getCount());
    CacheTesting.checkValidState(cache);
  }

  /**
   * With an unlimited-size cache with maxWeight of 0, entries weighing 0 should still be cached.
   * Entries with positive weight should not be cached (nor dump existing cache).
   */
  public void testEviction_maxWeight_zero() {
    CountingRemovalListener<Integer, Integer> removalListener = countingRemovalListener();
    IdentityLoader<Integer> loader = identityLoader();

    // Even numbers are free, odd are too expensive
    Weigher<Integer, Integer> evensOnly = (k, v) -> k % 2;

    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumWeight(0)
        .weigher(evensOnly)
        .removalListener(removalListener)
        .executor(MoreExecutors.directExecutor()),
        loader);

    // 1 won't be cached
    assertThat(cache.getUnchecked(1)).isEqualTo(1);
    assertThat(cache.asMap().keySet()).isEmpty();

    cache.cleanUp();
    assertThat(removalListener.getCount()).isEqualTo(1);

    // 2 will be cached
    assertThat(cache.getUnchecked(2)).isEqualTo(2);
    assertThat(cache.asMap().keySet()).containsExactly(2);

    cache.cleanUp();
    CacheTesting.checkValidState(cache);
    assertThat(removalListener.getCount()).isEqualTo(1);

    // 4 will be cached
    assertThat(cache.getUnchecked(4)).isEqualTo(4);
    assertThat(cache.asMap().keySet()).containsExactly(2, 4);

    cache.cleanUp();
    assertThat(removalListener.getCount()).isEqualTo(1);

    // 5 won't be cached, won't dump cache
    assertThat(cache.getUnchecked(5)).isEqualTo(5);
    assertThat(cache.asMap().keySet()).containsExactly(2, 4);

    cache.cleanUp();
    assertThat(removalListener.getCount()).isEqualTo(2);

    // Should we pepper more of these calls throughout the above? Where?
    CacheTesting.checkValidState(cache);
  }

  /**
   * Tests that when a single entry exceeds the segment's max weight, the new entry is
   * immediately evicted and nothing else.
   */
  public void testEviction_maxWeight_entryTooBig() {
    CountingRemovalListener<Integer, Integer> removalListener = countingRemovalListener();
    IdentityLoader<Integer> loader = identityLoader();

    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumWeight(4)
        .weigher(intValueWeigher())
        .removalListener(removalListener)
        .executor(MoreExecutors.directExecutor()),
        loader);

    // caches 2
    assertThat(cache.getUnchecked(2)).isEqualTo(2);
    assertThat(cache.asMap().keySet()).containsExactly(2);

    assertThat(removalListener.getCount()).isEqualTo(0);

    // caches 3, evicts 2
    assertThat(cache.getUnchecked(3)).isEqualTo(3);
    assertThat(cache.asMap().keySet()).containsExactly(3);

    assertThat(removalListener.getCount()).isEqualTo(1);

    // doesn't cache 5, doesn't evict
    assertThat(cache.getUnchecked(5)).isEqualTo(5);
    assertThat(cache.asMap().keySet()).containsExactly(3);

    assertThat(removalListener.getCount()).isEqualTo(2);

    // caches 1, evicts nothing
    assertThat(cache.getUnchecked(1)).isEqualTo(1);
    assertThat(cache.asMap().keySet()).containsExactly(3, 1);

    assertThat(removalListener.getCount()).isEqualTo(2);

    // caches 4, evicts 1 and 3
    assertThat(cache.getUnchecked(4)).isEqualTo(4);
    assertThat(cache.asMap().keySet()).containsExactly(4);

    assertThat(removalListener.getCount()).isEqualTo(4);

    // Should we pepper more of these calls throughout the above? Where?
    CacheTesting.checkValidState(cache);
  }

  public void testEviction_overflow() {
    CountingRemovalListener<Object, Object> removalListener = countingRemovalListener();
    IdentityLoader<Object> loader = identityLoader();
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumWeight(1L << 31)
        .executor(MoreExecutors.directExecutor())
        .weigher(constantWeigher(Integer.MAX_VALUE))
        .removalListener(removalListener), loader);
    cache.getUnchecked(objectWithHash(0));
    cache.getUnchecked(objectWithHash(0));
    assertEquals(1, removalListener.getCount());
  }

  // FIXME(ben): Caffeine uses W-TinyLfu, not Lru
  public void disabled_testEviction_lru() {
    // test lru within a single segment
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache =
        CaffeinatedGuava.build(Caffeine.newBuilder()
            .executor(MoreExecutors.directExecutor())
            .maximumSize(10), loader);
    CacheTesting.warmUp(cache, 0, 10);
    Set<Integer> keySet = cache.asMap().keySet();
    assertThat(keySet).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    // re-order
    getAll(cache, asList(0, 1, 2));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(3, 4, 5, 6, 7, 8, 9, 0, 1, 2);

    // evict 3, 4, 5
    getAll(cache, asList(10, 11, 12));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(6, 7, 8, 9, 0, 1, 2, 10, 11, 12);

    // re-order
    getAll(cache, asList(6, 7, 8));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(9, 0, 1, 2, 10, 11, 12, 6, 7, 8);

    // evict 9, 0, 1
    getAll(cache, asList(13, 14, 15));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(2, 10, 11, 12, 6, 7, 8, 13, 14, 15);
  }

  // FIXME(ben): Caffeine uses W-TinyLfu, not Lru
  public void disabled_testEviction_weightedLru() {
    // test weighted lru within a single segment
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .maximumWeight(45)
        .weigher(intKeyWeigher()), loader);
    CacheTesting.warmUp(cache, 0, 10);
    Set<Integer> keySet = cache.asMap().keySet();
    assertThat(keySet).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    // re-order
    getAll(cache, asList(0, 1, 2));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(3, 4, 5, 6, 7, 8, 9, 0, 1, 2);

    // evict 3, 4, 5
    getAll(cache, asList(10));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(6, 7, 8, 9, 0, 1, 2, 10);

    // re-order
    getAll(cache, asList(6, 7, 8));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(9, 0, 1, 2, 10, 6, 7, 8);

    // evict 9, 1, 2, 10
    getAll(cache, asList(15));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(0, 6, 7, 8, 15);

    // fill empty space
    getAll(cache, asList(9));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(0, 6, 7, 8, 15, 9);

    // evict 6
    getAll(cache, asList(1));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(0, 7, 8, 15, 9, 1);
  }

  public void testEviction_overweight() {
    // test weighted lru within a single segment
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .maximumWeight(45)
        .weigher(intKeyWeigher()), loader);
    CacheTesting.warmUp(cache, 0, 10);
    Set<Integer> keySet = cache.asMap().keySet();
    assertThat(keySet).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    // add an at-the-maximum-weight entry
    getAll(cache, asList(45));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(0, 45);

    // add an over-the-maximum-weight entry
    getAll(cache, asList(46));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).contains(0);
  }

  public void testEviction_invalidateAll() {
    // test that .invalidateAll() resets total weight state correctly
    IdentityLoader<Integer> loader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumSize(10), loader);

    Set<Integer> keySet = cache.asMap().keySet();
    assertThat(keySet).isEmpty();

    // add 0, 1, 2, 3, 4
    getAll(cache, asList(0, 1, 2, 3, 4));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(0, 1, 2, 3, 4);

    // invalidate all
    cache.invalidateAll();
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).isEmpty();

    // add 5, 6, 7, 8, 9, 10, 11, 12
    getAll(cache, asList(5, 6, 7, 8, 9, 10, 11, 12));
    CacheTesting.drainRecencyQueues(cache);
    assertThat(keySet).containsExactly(5, 6, 7, 8, 9, 10, 11, 12);
  }

  private void getAll(LoadingCache<Integer, Integer> cache, List<Integer> keys) {
    for (int i : keys) {
      cache.getUnchecked(i);
    }
  }

  private Object objectWithHash(final int hash) {
    return new Object() {
      @Override public int hashCode() {
        return hash;
      }
    };
  }
}
