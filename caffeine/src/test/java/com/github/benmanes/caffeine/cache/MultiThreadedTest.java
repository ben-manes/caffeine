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

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.testing.Int;
import com.github.benmanes.caffeine.testing.Threads;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.SerializableTester;

/**
 * A test to assert basic concurrency characteristics by validating the internal state after load.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
@Listeners(CacheValidationListener.class)
@Test(groups = "isolated", dataProviderClass = CacheProvider.class)
public final class MultiThreadedTest {

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = {Maximum.DISABLED, Maximum.FULL},
      weigher = {CacheWeigher.DISABLED, CacheWeigher.RANDOM},
      stats = Stats.DISABLED, population = Population.EMPTY,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MILLISECOND},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MILLISECOND},
      refreshAfterWrite = {Expire.DISABLED, Expire.ONE_MILLISECOND},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expiryTime = Expire.ONE_MILLISECOND, keys = ReferenceType.STRONG,
      removalListener = Listener.DISABLED, evictionListener = Listener.DISABLED)
  public void concurrent(Cache<Int, Int> cache, CacheContext context) {
    Threads.runTest(operations(cache, context));
  }

  @SuppressWarnings({"CollectionToArray", "CollectionUndefinedEquality", "FutureReturnValueIgnored",
      "MethodReferenceUsage", "PMD.OptimizableToArrayCall", "rawtypes", "ReturnValueIgnored",
      "SelfEquals", "SizeGreaterThanOrEqualsZero", "unchecked"})
  private static ImmutableList<Consumer<Int>> operations(
      Cache<Int, Int> cache, CacheContext context) {
    var builder = new ImmutableList.Builder<Consumer<Int>>();
    builder.add(
        key -> { cache.getIfPresent(key); },
        key -> { cache.get(key, identity()); },
        key -> { cache.getAllPresent(List.of(key)); },
        key -> { cache.put(key, key); },
        key -> { cache.putAll(Map.of(key, key)); },
        key -> { cache.invalidate(key); },
        key -> { cache.invalidateAll(List.of(key)); },
        key -> {
          int random = ThreadLocalRandom.current().nextInt();
          // expensive so do it less frequently
          if ((random & 255) == 0) {
            cache.invalidateAll();
          }
        },
        key -> { checkState(cache.estimatedSize() >= 0); },
        key -> { cache.stats(); },
        key -> { cache.cleanUp(); },
        key -> { cache.hashCode(); },
        key -> { cache.equals(cache); },
        key -> { cache.toString(); },
        key -> {
          int random = ThreadLocalRandom.current().nextInt();
          // expensive so do it less frequently
          if ((random & 255) == 0) {
            SerializableTester.reserialize(cache);
          }
        },

        // Map
        key -> { cache.asMap().containsKey(key); },
        key -> { cache.asMap().containsValue(key); },
        key -> { cache.asMap().isEmpty(); },
        key -> { checkState(cache.asMap().size() >= 0); },
        key -> { cache.asMap().get(key); },
        key -> { cache.asMap().put(key, key); },
        key -> { cache.asMap().putAll(Map.of(key, key)); },
        key -> { cache.asMap().putIfAbsent(key, key); },
        key -> { cache.asMap().remove(key); },
        key -> { cache.asMap().remove(key, key); },
        key -> { cache.asMap().replace(key, key); },
        key -> { cache.asMap().computeIfAbsent(key, k -> k); },
        key -> { cache.asMap().computeIfPresent(key, (k, v) -> v); },
        key -> { cache.asMap().compute(key, (k, v) -> v); },
        key -> { cache.asMap().merge(key, key, (k, v) -> v); },
        key -> { // expensive so do it less frequently
          int random = ThreadLocalRandom.current().nextInt();
          if ((random & 255) == 0) {
            cache.asMap().clear();
          }
        },
        key -> { cache.asMap().keySet().toArray(new Object[cache.asMap().size()]); },
        key -> { cache.asMap().values().toArray(new Object[cache.asMap().size()]); },
        key -> { cache.asMap().entrySet().toArray(new Map.Entry[cache.asMap().size()]); },
        key -> { cache.asMap().hashCode(); },
        key -> { cache.asMap().equals(cache.asMap()); },
        key -> { cache.asMap().toString(); });

    if (cache instanceof LoadingCache<?, ?>) {
      var loadingCache = (LoadingCache<Int, Int>) cache;
      builder.add(
          key -> { loadingCache.get(key); },
          key -> { loadingCache.getAll(List.of(key)); },
          key -> { loadingCache.refresh(key); });
    }

    if (context.isAsync()) {
      var asyncCache = (AsyncCache<Int, Int>) context.asyncCache();
      builder.add(
          key -> { asyncCache.getIfPresent(key); },
          key -> { asyncCache.get(key, k -> key); },
          key -> { asyncCache.get(key, (k, e) -> completedFuture(key)); },
          key -> { asyncCache.put(key, completedFuture(key)); },

          // Map
          key -> { asyncCache.asMap().containsKey(key); },
          key -> { asyncCache.asMap().containsValue(key.toFuture()); },
          key -> { asyncCache.asMap().isEmpty(); },
          key -> { checkState(asyncCache.asMap().size() >= 0); },
          key -> { asyncCache.asMap().get(key); },
          key -> { asyncCache.asMap().put(key, completedFuture(null)); },
          key -> { asyncCache.asMap().putAll(Map.of(key, completedFuture(null))); },
          key -> { asyncCache.asMap().putIfAbsent(key, completedFuture(null)); },
          key -> { asyncCache.asMap().remove(key); },
          key -> { asyncCache.asMap().remove(key, key); },
          key -> { asyncCache.asMap().replace(key, completedFuture(null)); },
          key -> { asyncCache.asMap().computeIfAbsent(key, k -> completedFuture(null)); },
          key -> { asyncCache.asMap().computeIfPresent(key, (k, v) -> v); },
          key -> { asyncCache.asMap().compute(key, (k, v) -> v); },
          key -> { asyncCache.asMap().merge(key, key.toFuture(), (k, v) -> v); },
          key -> { // expensive so do it less frequently
            int random = ThreadLocalRandom.current().nextInt();
            if ((random & 255) == 0) {
              asyncCache.asMap().clear();
            }
          },
          key -> { asyncCache.asMap().keySet().toArray(new Object[cache.asMap().size()]); },
          key -> { asyncCache.asMap().values().toArray(new Object[cache.asMap().size()]); },
          key -> { asyncCache.asMap().entrySet().toArray(new Map.Entry[cache.asMap().size()]); },
          key -> { asyncCache.asMap().hashCode(); },
          key -> { asyncCache.asMap().equals(asyncCache.asMap()); },
          key -> { asyncCache.asMap().toString(); });

      if (asyncCache instanceof AsyncLoadingCache<?, ?>) {
        var asyncLoadingCache = (AsyncLoadingCache<Int, Int>) asyncCache;
        builder.add(
            key -> { asyncLoadingCache.get(key); },
            key -> { asyncLoadingCache.getAll(List.of(key)); });
      }
    }

    cache.policy().expireVariably().ifPresent(policy -> {
      var duration = Duration.ofDays(1);
      builder.add(
          key -> { policy.put(key, key, duration); },
          key -> { policy.putIfAbsent(key, key, duration); },
          key -> { policy.compute(key, (k, v) -> v, duration); });
    });

    return builder.build();
  }
}
