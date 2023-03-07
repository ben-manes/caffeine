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
import static com.google.common.cache.CacheLoader.asyncReloading;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

import java.io.Serializable;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

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
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
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
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.MoreObjects;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.FakeTicker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * The cache configuration context for a test case.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheContext {
  private static final ThreadLocal<Map<Object, Object>> interner =
      ThreadLocal.withInitial(HashMap::new);

  final RemovalListener<Int, Int> evictionListener;
  final RemovalListener<Int, Int> removalListener;
  final Weigher<Object, Object> weigher;
  final InitialCapacity initialCapacity;
  final Implementation implementation;
  final CacheScheduler cacheScheduler;
  final SerializableFakeTicker ticker;
  final Listener evictionListenerType;
  final Listener removalListenerType;
  final CacheExecutor cacheExecutor;
  final ReferenceType valueStrength;
  final TrackingExecutor executor;
  final ReferenceType keyStrength;
  final CacheWeigher cacheWeigher;
  final Expiry<Int, Int> expiry;
  final Map<Int, Int> original;
  final CacheExpiry expiryType;
  final Population population;
  final Maximum maximumSize;
  final Scheduler scheduler;
  final Expire afterAccess;
  final Expire afterWrite;
  final Expire expiryTime;
  final Compute compute;
  final Expire refresh;
  final Loader loader;
  final Stats stats;

  final boolean isAsyncLoader;

  CacheBuilder<Object, Object> guava;
  Caffeine<Object, Object> caffeine;
  AsyncCache<?, ?> asyncCache;
  Cache<?, ?> cache;

  @Nullable Int firstKey;
  @Nullable Int middleKey;
  @Nullable Int lastKey;
  long initialSize;

  // Generated on-demand
  Int absentKey;
  Int absentValue;

  Map<Int, Int> absent;

  public CacheContext(InitialCapacity initialCapacity, Stats stats, CacheWeigher cacheWeigher,
      Maximum maximumSize, CacheExpiry expiryType, Expire afterAccess, Expire afterWrite,
      Expire refresh, ReferenceType keyStrength, ReferenceType valueStrength,
      CacheExecutor cacheExecutor, CacheScheduler cacheScheduler, Listener removalListenerType,
      Listener evictionListenerType, Population population, boolean isAsyncLoader, Compute compute,
      Loader loader, Implementation implementation, CacheSpec cacheSpec) {
    this.initialCapacity = requireNonNull(initialCapacity);
    this.stats = requireNonNull(stats);
    this.weigher = cacheWeigher.create();
    this.cacheWeigher = cacheWeigher;
    this.maximumSize = requireNonNull(maximumSize);
    this.afterAccess = requireNonNull(afterAccess);
    this.afterWrite = requireNonNull(afterWrite);
    this.refresh = requireNonNull(refresh);
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
    this.loader = requireNonNull(loader);
    this.isAsyncLoader = isAsyncLoader;
    this.ticker = new SerializableFakeTicker();
    this.implementation = requireNonNull(implementation);
    this.original = new LinkedHashMap<>();
    this.initialSize = -1;
    this.compute = compute;
    this.expiryType = expiryType;
    this.expiryTime = cacheSpec.expiryTime();
    this.expiry = expiryType.createExpiry(expiryTime);
  }

  /** Returns a thread local interner for explicit caching. */
  public static Map<Object, Object> interner() {
    return interner.get();
  }

  /** Returns a thread local interned value. */
  @CanIgnoreReturnValue
  @SuppressWarnings("unchecked")
  public static <T> T intern(T o) {
    return (T) interner.get().computeIfAbsent(o, identity());
  }

  /** Returns a thread local interned value. */
  @SuppressWarnings("unchecked")
  public static <K, V> V intern(K key, Function<K, V> mappingFunction) {
    return (V) interner.get().computeIfAbsent(key, k -> mappingFunction.apply((K) k));
  }

  public Caffeine<Object, Object> caffeine() {
    return caffeine;
  }

  public CacheBuilder<Object, Object> guava() {
    return guava;
  }

  public AsyncCache<?, ?> asyncCache() {
    return asyncCache;
  }

  public Cache<?, ?> cache() {
    return cache;
  }

  public InitialCapacity initialCapacity() {
    return initialCapacity;
  }

  public boolean isAsync() {
    return (compute == Compute.ASYNC);
  }

  public boolean isSync() {
    return (compute == Compute.SYNC);
  }

  public Population population() {
    return population;
  }

  public Int firstKey() {
    assertWithMessage("Invalid usage of context").that(firstKey).isNotNull();
    return firstKey;
  }

  public Int middleKey() {
    assertWithMessage("Invalid usage of context").that(middleKey).isNotNull();
    return middleKey;
  }

  public Int lastKey() {
    assertWithMessage("Invalid usage of context").that(lastKey).isNotNull();
    return lastKey;
  }

  public ImmutableSet<Int> firstMiddleLastKeys() {
    return (firstKey == null)
        ? ImmutableSet.of()
        : ImmutableSet.of(firstKey(), middleKey(), lastKey());
  }

  public void cleanUp() {
    cache.cleanUp();
  }

  public void clear() {
    interner().clear();
    initialSize();
    original.clear();
    absent = null;
    absentKey = null;
    absentValue = null;
    firstKey = null;
    middleKey = null;
    lastKey = null;
  }

  public Int absentKey() {
    return (absentKey == null) ? (absentKey = nextAbsentKey()) : absentKey;
  }

  public Int absentValue() {
    return (absentValue == null) ? (absentValue = absentKey().negate()) : absentValue;
  }

  public Map<Int, Int> absent() {
    if (absent != null) {
      return absent;
    }
    absent = new LinkedHashMap<>();
    absent.put(absentKey(), absentValue());
    do {
      Int key = nextAbsentKey();
      absent.put(key, key.negate());
    } while (absent.size() < 10);
    return absent;
  }

  public Set<Int> absentKeys() {
    return absent().keySet();
  }

  private Int nextAbsentKey() {
    return Int.valueOf(ThreadLocalRandom.current().nextInt(
        (Integer.MAX_VALUE / 2), Integer.MAX_VALUE));
  }

  @CanIgnoreReturnValue
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
    assertWithMessage("Invalid usage of context").that(isWeighted()).isTrue();
    long maximum = cacheWeigher.unitsPerEntry() * maximumSize.max();
    return (maximum < 0) ? Long.MAX_VALUE : maximum;
  }

  public long maximumWeightOrSize() {
    return isWeighted() ? maximumWeight() : maximumSize();
  }

  public Weigher<Object, Object> weigher() {
    return weigher;
  }

  public CacheWeigher cacheWeigher() {
    return cacheWeigher;
  }

  public boolean isWeighted() {
    return (cacheWeigher != CacheWeigher.DISABLED);
  }

  public boolean isZeroWeighted() {
    return (cacheWeigher == CacheWeigher.ZERO);
  }

  public boolean refreshes() {
    return (refresh != Expire.DISABLED);
  }

  public Expire refreshAfterWrite() {
    return refresh;
  }

  /** The initial entries in the cache, iterable in insertion order. */
  public Map<Int, Int> original() {
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
    return (loader != Loader.DISABLED);
  }

  public boolean isAsyncLoader() {
    return isAsyncLoader;
  }

  public Loader loader() {
    return loader;
  }

  public Listener removalListenerType() {
    return removalListenerType;
  }

  public RemovalListener<Int, Int> removalListener() {
    return requireNonNull(removalListener);
  }

  public void clearRemovalNotifications() {
    if (removalListenerType() == Listener.CONSUMING) {
      var listener = (ConsumingRemovalListener<?, ?>) removalListener;
      listener.removed().clear();
    }
  }

  public Listener evictionListenerType() {
    return evictionListenerType;
  }

  public RemovalListener<Int, Int> evictionListener() {
    return requireNonNull(evictionListener);
  }

  public boolean isRecordingStats() {
    return (stats == Stats.ENABLED);
  }

  public CacheStats stats() {
    return cache.stats();
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

  public Expiry<Int, Int> expiry() {
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
      var guavaLoader = new SingleLoader<>(loader);
      cache = new GuavaLoadingCache<>(guava.build(asyncReloading(guavaLoader, executor)), this);
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

  public boolean isCaffeine() {
    return (implementation == Implementation.Caffeine);
  }

  public boolean isGuava() {
    return (implementation == Implementation.Guava);
  }

  public CacheExecutor executorType() {
    return cacheExecutor;
  }

  public TrackingExecutor executor() {
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
        .add("weigher", cacheWeigher)
        .add("expiry", expiryType)
        .add("expiryTime", expiryTime)
        .add("afterAccess", afterAccess)
        .add("afterWrite", afterWrite)
        .add("refreshAfterWrite", refresh)
        .add("keyStrength", keyStrength)
        .add("valueStrength", valueStrength)
        .add("compute", compute)
        .add("loader", loader)
        .add("isAsyncLoader", isAsyncLoader)
        .add("cacheExecutor", cacheExecutor)
        .add("cacheScheduler", cacheScheduler)
        .add("removalListener", removalListenerType)
        .add("evictionListener", evictionListenerType)
        .add("initialCapacity", initialCapacity)
        .add("stats", stats)
        .add("implementation", implementation)
        .add("startTime", ticker.startTime)
        .toString();
  }

  static final class SerializableFakeTicker extends FakeTicker implements Serializable {
    private static final long serialVersionUID = 1L;

    final long startTime;

    public SerializableFakeTicker() {
      startTime = ThreadLocalRandom.current().nextLong(Long.MIN_VALUE, Long.MAX_VALUE);
      advance(Duration.ofNanos(startTime));
    }
  }
}
