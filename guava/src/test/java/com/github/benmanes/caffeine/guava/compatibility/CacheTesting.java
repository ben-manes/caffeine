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

package com.github.benmanes.caffeine.guava.compatibility;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.EqualsTester;
import com.google.common.testing.FakeTicker;

/**
 * A collection of utilities for {@link Cache} testing.
 *
 * @author mike nonemacher
 */
@SuppressWarnings({"GuardedByChecker", "PreferJavaTimeOverload"})
class CacheTesting {

  private CacheTesting() {}

  static void drainRecencyQueues(Cache<?, ?> cache) {
    cache.cleanUp();
  }

  /**
   * Peeks into the cache's internals to check its internal consistency.
   */
  static void checkValidState(Cache<?, ?> cache) {}

  /**
   * Warms the given cache by getting all values in {@code [start, end)}, in order.
   */
  static void warmUp(LoadingCache<Integer, Integer> map, int start, int end) {
    checkNotNull(map);
    for (int i = start; i < end; i++) {
      map.getUnchecked(i);
    }
  }

  static void expireEntries(Cache<?, ?> cache, long expiringTime, FakeTicker ticker) {
    checkNotNull(ticker);
    ticker.advance(2 * expiringTime, TimeUnit.MILLISECONDS);
    cache.cleanUp();
  }

  static void checkEmpty(Cache<?, ?> cache) {
    assertEquals(0, cache.size());
    assertFalse(cache.asMap().containsKey(null));
    assertFalse(cache.asMap().containsKey(6));
    assertFalse(cache.asMap().containsValue(null));
    assertFalse(cache.asMap().containsValue(6));
    checkEmpty(cache.asMap());
  }

  static void checkEmpty(ConcurrentMap<?, ?> map) {
    checkEmpty(map.keySet());
    checkEmpty(map.values());
    checkEmpty(map.entrySet());
    assertEquals(ImmutableMap.of(), map);
    assertEquals(ImmutableMap.of().hashCode(), map.hashCode());
    assertEquals(ImmutableMap.of().toString(), map.toString());
  }

  @SuppressWarnings("CollectionToArray")
  static void checkEmpty(Collection<?> collection) {
    assertTrue(collection.isEmpty());
    assertEquals(0, collection.size());
    assertFalse(collection.iterator().hasNext());
    assertEquals(0, collection.toArray().length);
    assertEquals(0, collection.toArray(new Object[0]).length);
    if (collection instanceof Set) {
      new EqualsTester()
          .addEqualityGroup(ImmutableSet.of(), collection)
          .addEqualityGroup(ImmutableSet.of(""))
          .testEquals();
    } else if (collection instanceof List) {
      new EqualsTester()
          .addEqualityGroup(ImmutableList.of(), collection)
          .addEqualityGroup(ImmutableList.of(""))
          .testEquals();
    }
  }
}
