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
package com.github.benmanes.caffeine.cache.testing;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Advance;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.GuavaCacheFromContext.GuavaLoadingCache;
import com.github.benmanes.caffeine.cache.testing.GuavaCacheFromContext.SingleLoader;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.google.common.base.MoreObjects;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.FakeTicker;

/**
 * The cache configuration context for a test case.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("deprecation")
public final class CacheContext {
  final RemovalListener<Integer, Integer> evictionListener;
  final RemovalListener<Integer, Integer> removalListener;
  final InitialCapacity initialCapacity;
  final Expiry<Integer, Integer> expiry;
  final Map<Integer, Integer> original;
  final Implementation implementation;
  final CacheScheduler cacheScheduler;
  final Listener evictionListenerType;
  final Listener removalListenerType;
  final CacheExecutor cacheExecutor;
  final ReferenceType valueStrength;
  final ReferenceType keyStrength;
  final CacheExpiry expiryType;
  final Population population;
  final CacheWeigher weigher;
  final Maximum maximumSize;
  final Scheduler scheduler;
  final Expire afterAccess;
  final Expire afterWrite;
  final Expire expiryTime;
  final Executor executor;
  final FakeTicker ticker;
  final Compute compute;
  final Advance advance;
  final Expire refresh;
  final Loader loader;
  final Stats stats;

  final boolean isAsyncLoading;

  Cache<?, ?> cache;
  AsyncCache<?, ?> asyncCache;
  Caffeine<Object, Object> caffeine;
  CacheBuilder<Object, Object> guava;

  @Nullable Integer firstKey;
  @Nullable Integer middleKey;
  @Nullable Integer lastKey;
  long initialSize;

  // Generated on-demand
  Integer absentKey;
  Integer absentValue;

  Map<Integer, Integer> absent;

  public CacheContext(InitialCapacity initialCapacity, Stats stats, CacheWeigher weigher,
      Maximum maximumSize, CacheExpiry expiryType, Expire afterAccess, Expire afterWrite,
      Expire refresh, Advance advance, ReferenceType keyStrength, ReferenceType valueStrength,
      CacheExecutor cacheExecutor, CacheScheduler cacheScheduler, Listener removalListenerType,
      Listener evictionListenerType, Population population, boolean isLoading,
      boolean isAsyncLoading, Compute compute, Loader loader,
      Implementation implementation, CacheSpec cacheSpec) {
    this.initialCapacity = requireNonNull(initialCapacity);
    this.stats = requireNonNull(stats);
    this.weigher = requireNonNull(weigher);
    this.maximumSize = requireNonNull(maximumSize);
    this.afterAccess = requireNonNull(afterAccess);
    this.afterWrite = requireNonNull(afterWrite);
    this.refresh = requireNonNull(refresh);
    this.advance = requireNonNull(advance);
    this.keyStrength = requireNonNull(keyStrength);
    this.valueStrength = requireNonNull(valueStrength);
    this.cacheExecutor = requireNonNull(cacheExecutor);
    this.executor = cacheExecutor.create();
    this.cacheScheduler = requireNonNull(cacheScheduler);
    this.scheduler = cacheScheduler.create();
    this.removalListenerType = removalListenerType;
    this.removalListener = removalListenerType.create();
    this.evictionListenerType = evictionListenerType;
    this.evictionListener = evictionListenerType.create();
    this.population = requireNonNull(population);
    this.loader = isLoading ? requireNonNull(loader) : null;
    this.isAsyncLoading = isAsyncLoading;
    this.ticker = new SerializableFakeTicker();
    this.implementation = requireNonNull(implementation);
    this.original = new LinkedHashMap<>();
    this.initialSize = -1;
    this.compute = compute;
    this.expiryType = expiryType;
    this.expiryTime = cacheSpec.expiryTime();
    this.expiry = expiryType.createExpiry(expiryTime);
  }

  public InitialCapacity initialCapacity() {
    return initialCapacity;
  }

  public boolean isAsync() {
    return (compute == Compute.ASYNC);
  }

  public Population population() {
    return population;
  }

  public Integer firstKey() {
    assertThat("Invalid usage of context", firstKey, is(not(nullValue())));
    return firstKey;
  }

  public Integer middleKey() {
    assertThat("Invalid usage of context", middleKey, is(not(nullValue())));
    return middleKey;
  }

  public Integer lastKey() {
    assertThat("Invalid usage of context", lastKey, is(not(nullValue())));
    return lastKey;
  }

  public Set<Integer> firstMiddleLastKeys() {
    return ImmutableSet.of(firstKey(), middleKey(), lastKey());
  }

  public void cleanUp() {
    cache.cleanUp();
  }

  public void clear() {
    CacheSpec.interner.get().clear();
    initialSize();
    original.clear();
    absent = null;
    absentKey = null;
    absentValue = null;
    firstKey = null;
    middleKey = null;
    lastKey = null;
  }

  public Integer absentKey() {
    return (absentKey == null) ? (absentKey = nextAbsentKey()) : absentKey;
  }

  public Integer absentValue() {
    return (absentValue == null) ? (absentValue = -absentKey()) : absentValue;
  }

  public Map<Integer, Integer> absent() {
    if (absent != null) {
      return absent;
    }
    absent = new LinkedHashMap<>();
    do {
      Integer key = nextAbsentKey();
      absent.put(key, -key);
    } while (absent.size() < 10);
    return absent;
  }

  public Set<Integer> absentKeys() {
    return absent().keySet();
  }

  private Integer nextAbsentKey() {
    return ThreadLocalRandom.current().nextInt((Integer.MAX_VALUE / 2), Integer.MAX_VALUE);
  }

  public long initialSize() {
    return (initialSize < 0) ? (initialSize = original.size()) : initialSize;
  }

  public Maximum maximum() {
    return maximumSize;
  }

  public long maximumSize() {
    return maximumSize.max();
  }

  public long maximumWeight() {
    assertThat("Invalid usage of context", isWeighted(), is(not(nullValue())));
    long maximum = weigher.unitsPerEntry() * maximumSize.max();
    return (maximum < 0) ? Long.MAX_VALUE : maximum;
  }

  public long maximumWeightOrSize() {
    return isWeighted() ? maximumWeight() : maximumSize();
  }

  public CacheWeigher weigher() {
    return weigher;
  }

  public boolean isWeighted() {
    return (weigher != CacheWeigher.DEFAULT);
  }

  public boolean isZeroWeighted() {
    return (weigher == CacheWeigher.ZERO);
  }

  public boolean isUnbounded() {
    return (maximumSize == Maximum.DISABLED);
  }

  public boolean refreshes() {
    return (refresh != Expire.DISABLED);
  }

  public Expire refreshAfterWrite() {
    return refresh;
  }

  /** The initial entries in the cache, iterable in insertion order. */
  public Map<Integer, Integer> original() {
    initialSize(); // lazy initialize
    return original;
  }

  public boolean isStrongKeys() {
    return keyStrength == ReferenceType.STRONG;
  }

  public boolean isWeakKeys() {
    return keyStrength == ReferenceType.WEAK;
  }

  public boolean isStrongValues() {
    return valueStrength == ReferenceType.STRONG;
  }

  public boolean isWeakValues() {
    return valueStrength == ReferenceType.WEAK;
  }

  public boolean isSoftValues() {
    return valueStrength == ReferenceType.SOFT;
  }

  public boolean isLoading() {
    return (loader != null);
  }

  public boolean isAsyncLoading() {
    return isAsyncLoading;
  }

  public Loader loader() {
    return loader;
  }

  public Listener removalListenerType() {
    return removalListenerType;
  }

  public RemovalListener<Integer, Integer> removalListener() {
    return requireNonNull(removalListener);
  }

  public List<RemovalNotification<Integer, Integer>> removalNotifications() {
    return (removalListenerType() == Listener.CONSUMING)
        ? ((ConsumingRemovalListener<Integer, Integer>) removalListener).removed()
        : Collections.emptyList();
  }

  public Listener evictionListenerType() {
    return evictionListenerType;
  }

  public RemovalListener<Integer, Integer> evictionListener() {
    return requireNonNull(evictionListener);
  }

  public List<RemovalNotification<Integer, Integer>> evictionNotifications() {
    return (evictionListenerType() == Listener.CONSUMING)
        ? ((ConsumingRemovalListener<Integer, Integer>) evictionListener).removed()
        : Collections.emptyList();
  }

  public boolean isRecordingStats() {
    return (stats == Stats.ENABLED);
  }

  public CacheStats stats() {
    return cache.stats();
  }

  public Cache<?, ?> cache() {
    return cache;
  }

  public boolean expires(Expiration expiration) {
    return (expiresAfterAccess() && (expiration == Expiration.AFTER_ACCESS))
        || (expiresAfterWrite() && (expiration == Expiration.AFTER_WRITE))
        || (expiresVariably() && (expiration == Expiration.VARIABLE));
  }

  public boolean expires() {
    return expiresVariably() || expiresAfterAccess() || expiresAfterWrite();
  }

  public boolean expiresAfterAccess() {
    return (afterAccess != Expire.DISABLED);
  }

  public boolean expiresAfterWrite() {
    return (afterWrite != Expire.DISABLED);
  }

  public boolean expiresVariably() {
    return (expiryType != CacheExpiry.DISABLED);
  }

  public Expiry<Integer, Integer> expiry() {
    return expiry;
  }

  public CacheExpiry expiryType() {
    return expiryType;
  }

  public Expire expiryTime() {
    return expiryTime;
  }

  public Expire expireAfterAccess() {
    return afterAccess;
  }

  public Expire expireAfterWrite() {
    return afterWrite;
  }

  public FakeTicker ticker() {
    return ticker;
  }

  public <K, V> LoadingCache<K, V> build(CacheLoader<K, V> loader) {
    LoadingCache<K, V> cache;
    if (isCaffeine()) {
      cache = isAsync() ? caffeine.buildAsync(loader).synchronous() : caffeine.build(loader);
    } else {
      cache = new GuavaLoadingCache<>(guava.build(
          com.google.common.cache.CacheLoader.asyncReloading(
              new SingleLoader<>(loader), executor)), ticker, isRecordingStats());
    }
    this.cache = cache;
    return cache;
  }

  public <K, V> AsyncLoadingCache<K, V> buildAsync(CacheLoader<K, V> loader) {
    checkState(isCaffeine() && isAsync());
    AsyncLoadingCache<K, V> cache = caffeine.buildAsync(loader);
    this.cache = cache.synchronous();
    return cache;
  }

  public <K, V> AsyncLoadingCache<K, V> buildAsync(AsyncCacheLoader<K, V> loader) {
    checkState(isCaffeine() && isAsync());
    AsyncLoadingCache<K, V> cache = caffeine.buildAsync(loader);
    this.cache = cache.synchronous();
    return cache;
  }

  public Implementation implementation() {
    return implementation;
  }

  public boolean isCaffeine() {
    return (implementation == Implementation.Caffeine);
  }

  public boolean isGuava() {
    return (implementation == Implementation.Guava);
  }

  public CacheExecutor executorType() {
    return cacheExecutor;
  }

  public Executor executor() {
    return executor;
  }

  public Scheduler scheduler() {
    return scheduler;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("population", population)
        .add("maximumSize", maximumSize)
        .add("weigher", weigher)
        .add("expiry", expiryType)
        .add("expiryTime", expiryTime)
        .add("afterAccess", afterAccess)
        .add("afterWrite", afterWrite)
        .add("refreshAfterWrite", refresh)
        .add("keyStrength", keyStrength)
        .add("valueStrength", valueStrength)
        .add("compute", compute)
        .add("loader", loader)
        .add("isAsyncLoading", isAsyncLoading)
        .add("cacheExecutor", cacheExecutor)
        .add("cacheScheduler", cacheScheduler)
        .add("removalListener", removalListenerType)
        .add("evictionListener", evictionListenerType)
        .add("initialCapacity", initialCapacity)
        .add("stats", stats)
        .add("implementation", implementation)
        .toString();
  }

  @SuppressWarnings("serial")
  static final class SerializableFakeTicker extends FakeTicker implements Serializable {
    private static final long START_TIME = new Random().nextLong();

    @SuppressWarnings("PreferJavaTimeOverload")
    public SerializableFakeTicker() {
      advance(START_TIME);
    }
  }
}
