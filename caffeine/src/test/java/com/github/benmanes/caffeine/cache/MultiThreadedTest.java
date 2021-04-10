/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.testing.Threads;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.SerializableTester;

/**
 * A test to assert basic concurrency characteristics by validating the internal state after load.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class MultiThreadedTest {

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.DISABLED, stats = Stats.DISABLED,
      population = Population.EMPTY, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, removalListener = Listener.DEFAULT,
      refreshAfterWrite = { Expire.DISABLED, Expire.ONE_MILLISECOND },
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG,
      evictionListener = Listener.DEFAULT)
  public void concurrent_unbounded(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Threads.runTest(cache, operations);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, weigher = {CacheWeigher.DEFAULT, CacheWeigher.RANDOM},
      stats = Stats.DISABLED, population = Population.EMPTY, expireAfterAccess = Expire.FOREVER,
      removalListener = Listener.DEFAULT, expireAfterWrite = Expire.FOREVER,
      refreshAfterWrite = { Expire.DISABLED, Expire.ONE_MILLISECOND },
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG,
      evictionListener = Listener.DEFAULT)
  public void concurrent_bounded(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Threads.runTest(cache, operations);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.DISABLED, stats = Stats.DISABLED,
      population = Population.EMPTY, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, removalListener = Listener.DEFAULT,
      refreshAfterWrite = { Expire.DISABLED, Expire.ONE_MILLISECOND },
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG,
      evictionListener = Listener.DEFAULT)
  public void async_concurrent_unbounded(
      AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Threads.runTest(cache, asyncOperations);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, weigher = {CacheWeigher.DEFAULT, CacheWeigher.RANDOM},
      stats = Stats.DISABLED, population = Population.EMPTY, expireAfterAccess = Expire.FOREVER,
      expireAfterWrite = Expire.FOREVER, removalListener = Listener.DEFAULT,
      refreshAfterWrite = { Expire.DISABLED, Expire.ONE_MILLISECOND },
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG,
      evictionListener = Listener.DEFAULT)
  public void async_concurrent_bounded(
      AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Threads.runTest(cache, asyncOperations);
  }

  @SuppressWarnings({"unchecked", "rawtypes", "ReturnValueIgnored",
    "FutureReturnValueIgnored", "SizeGreaterThanOrEqualsZero", "SelfEquals"})
  List<BiConsumer<LoadingCache<Integer, Integer>, Integer>> operations = ImmutableList.of(
      // LoadingCache
      (cache, key) -> { cache.get(key); },
      (cache, key) -> { cache.getAll(ImmutableList.of(key)); },
      (cache, key) -> { cache.refresh(key); },

      // Cache
      (cache, key) -> { cache.getIfPresent(key); },
      (cache, key) -> { cache.get(key, Function.identity()); },
      (cache, key) -> { cache.getAllPresent(ImmutableList.of(key)); },
      (cache, key) -> { cache.put(key, key); },
      (cache, key) -> { cache.putAll(ImmutableMap.of(key, key)); },
      (cache, key) -> { cache.invalidate(key); },
      (cache, key) -> { cache.invalidateAll(ImmutableList.of(key)); },
      (cache, key) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          cache.invalidateAll();
        }
      },
      (cache, key) -> { Preconditions.checkState(cache.estimatedSize() >= 0); },
      (cache, key) -> { cache.stats(); },
      (cache, key) -> { cache.cleanUp(); },

      // Map
      (cache, key) -> { cache.asMap().containsKey(key); },
      (cache, key) -> { cache.asMap().containsValue(key); },
      (cache, key) -> { cache.asMap().isEmpty(); },
      (cache, key) -> { Preconditions.checkState(cache.asMap().size() >= 0); },
      (cache, key) -> { cache.asMap().get(key); },
      (cache, key) -> { cache.asMap().put(key, key); },
      (cache, key) -> { cache.asMap().putAll(ImmutableMap.of(key, key)); },
      (cache, key) -> { cache.asMap().putIfAbsent(key, key); },
      (cache, key) -> { cache.asMap().remove(key); },
      (cache, key) -> { cache.asMap().remove(key, key); },
      (cache, key) -> { cache.asMap().replace(key, key); },
      (cache, key) -> { cache.asMap().computeIfAbsent(key, k -> k); },
      (cache, key) -> { cache.asMap().computeIfPresent(key, (k, v) -> v); },
      (cache, key) -> { cache.asMap().compute(key, (k, v) -> v); },
      (cache, key) -> { cache.asMap().merge(key, key, (k, v) -> v); },
      (cache, key) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          cache.asMap().clear();
        }
      },
      (cache, key) -> { cache.asMap().keySet().toArray(new Object[cache.asMap().size()]); },
      (cache, key) -> { cache.asMap().values().toArray(new Object[cache.asMap().size()]); },
      (cache, key) -> { cache.asMap().entrySet().toArray(new Map.Entry[cache.asMap().size()]); },
      (cache, key) -> { cache.hashCode(); },
      (cache, key) -> { cache.equals(cache); },
      (cache, key) -> { cache.toString(); },
      (cache, key) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          SerializableTester.reserialize(cache);
        }
      });


  @SuppressWarnings({"unchecked", "rawtypes", "FutureReturnValueIgnored"})
  List<BiConsumer<AsyncLoadingCache<Integer, Integer>, Integer>> asyncOperations = ImmutableList.of(
      (cache, key) -> { cache.getIfPresent(key); },
      (cache, key) -> { cache.get(key, k -> key); },
      (cache, key) -> { cache.get(key, (k, e) -> CompletableFuture.completedFuture(key)); },
      (cache, key) -> { cache.get(key); },
      (cache, key) -> { cache.getAll(ImmutableList.of(key)); },
      (cache, key) -> { cache.put(key, CompletableFuture.completedFuture(key)); });
}
