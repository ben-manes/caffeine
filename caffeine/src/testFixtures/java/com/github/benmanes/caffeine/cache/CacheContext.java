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

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Expiration;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.CacheSpec.StartTime;
import com.github.benmanes.caffeine.cache.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.GuavaCacheFromContext.GuavaLoadingCache;
import com.github.benmanes.caffeine.cache.GuavaCacheFromContext.SingleLoader;
import com.github.benmanes.caffeine.cache.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.testing.Int;
import com.github.benmanes.caffeine.testing.TrackingExecutor;
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

  private final Weigher<Object, Object> weigher;
  private final InitialCapacity initialCapacity;
  private final Implementation implementation;
  private final CacheScheduler cacheScheduler;
  private final SerializableFakeTicker ticker;
  private final Listener evictionListenerType;
  private final Listener removalListenerType;
  private final CacheExecutor cacheExecutor;
  private final ReferenceType valueStrength;
  private final ReferenceType keyStrength;
  private final CacheWeigher cacheWeigher;
  private final Map<Int, Int> original;
  private final CacheExpiry expiryType;
  private final Population population;
  private final Maximum maximumSize;
  private final StartTime startTime;
  private final Expire afterAccess;
  private final Expire afterWrite;
  private final Expire expiryTime;
  private final Compute compute;
  private final Expire refresh;
  private final Loader loader;
  private final Stats stats;

  private final @Nullable RemovalListener<Int, Int> evictionListener;
  private final @Nullable RemovalListener<Int, Int> removalListener;
  private final @Nullable TrackingExecutor executor;
  private final @Nullable Expiry<Int, Int> expiry;
  private final @Nullable Scheduler scheduler;

  private final boolean isAsyncLoader;

  private @Nullable CacheBuilder<Object, Object> guava;
  private @Nullable Caffeine<Object, Object> caffeine;
  private @Nullable AsyncCache<?, ?> asyncCache;
  private @Nullable Cache<?, ?> cache;
  private @Nullable Int firstKey;
  private @Nullable Int middleKey;
  private @Nullable Int lastKey;
  private long initialSize;

  // Generated on-demand
  private @Nullable Int absentKey;
  private @Nullable Int absentValue;
  private @Nullable Map<Int, Int> absent;

  /** A copy constructor that does not include the cache instance or any generated fields. */
  @SuppressWarnings("CopyConstructorMissesField")
  public CacheContext(CacheContext context) {
    this(context.initialCapacity, context.stats, context.cacheWeigher, context.maximumSize,
        context.expiryType, context.afterAccess, context.afterWrite, context.refresh,
        context.keyStrength, context.valueStrength, context.cacheExecutor, context.cacheScheduler,
        context.removalListenerType, context.evictionListenerType, context.population,
        context.isAsyncLoader, context.compute, context.loader, context.implementation,
        context.startTime, context.expiryTime);
  }

  @SuppressWarnings({"NullAway.Init", "PMD.ExcessiveParameterList", "TooManyParameters"})
  public CacheContext(InitialCapacity initialCapacity, Stats stats, CacheWeigher cacheWeigher,
      Maximum maximumSize, CacheExpiry expiryType, Expire afterAccess, Expire afterWrite,
      Expire refresh, ReferenceType keyStrength, ReferenceType valueStrength,
      CacheExecutor cacheExecutor, CacheScheduler cacheScheduler, Listener removalListenerType,
      Listener evictionListenerType, Population population, boolean isAsyncLoader, Compute compute,
      Loader loader, Implementation implementation, StartTime startTime, Expire expiryTime) {
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
    this.implementation = requireNonNull(implementation);
    this.original = new LinkedHashMap<>();
    this.initialSize = -1;
    this.compute = requireNonNull(compute);
    this.expiryType = requireNonNull(expiryType);
    this.expiryTime = requireNonNull(expiryTime);
    this.startTime = requireNonNull(startTime);
    this.ticker = new SerializableFakeTicker(startTime.create());
    this.expiry = (expiryType == CacheExpiry.DISABLED) ? null : expiryType.createExpiry(expiryTime);
  }

  void with(@Nullable Int firstKey, @Nullable Int middleKey,
      @Nullable Int lastKey, Map<Int, Int> original) {
    checkState(initialSize < 0);
    this.lastKey = lastKey;
    this.firstKey = firstKey;
    this.middleKey = middleKey;
    this.original.putAll(original);
  }

  void with(CacheBuilder<Object, Object> guava) {
    checkState(this.guava == null);
    this.guava = guava;
  }

  void with(Caffeine<Object, Object> caffeine) {
    checkState(this.caffeine == null);
    this.caffeine = caffeine;
  }

  void with(AsyncCache<?, ?> asyncCache) {
    checkState(this.asyncCache == null);
    this.asyncCache = asyncCache;
  }

  void with(Cache<?, ?> cache) {
    checkState(this.cache == null);
    this.cache = cache;
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
    return requireNonNull(caffeine);
  }

  public CacheBuilder<Object, Object> guava() {
    return requireNonNull(guava);
  }

  public AsyncCache<?, ?> asyncCache() {
    return requireNonNull(asyncCache);
  }

  public Cache<?, ?> cache() {
    return requireNonNull(cache);
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
    requireNonNull(firstKey);
    return firstKey;
  }

  public Int middleKey() {
    assertWithMessage("Invalid usage of context").that(middleKey).isNotNull();
    requireNonNull(middleKey);
    return middleKey;
  }

  public Int lastKey() {
    assertWithMessage("Invalid usage of context").that(lastKey).isNotNull();
    requireNonNull(lastKey);
    return lastKey;
  }

  public ImmutableSet<Int> firstMiddleLastKeys() {
    return (firstKey == null)
        ? ImmutableSet.of()
        : ImmutableSet.of(firstKey(), middleKey(), lastKey());
  }

  public void cleanUp() {
    cache().cleanUp();
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

  private static Int nextAbsentKey() {
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
      var listener = (ConsumingRemovalListener<?, ?>) removalListener();
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
    return cache().stats();
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
    return requireNonNull(expiry);
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
    LoadingCache<K, V> loading;
    if (isCaffeine()) {
      loading = isAsync() ? caffeine().buildAsync(loader).synchronous() : caffeine().build(loader);
    } else {
      var guavaLoader = new SingleLoader<>(loader);
      loading = new GuavaLoadingCache<>(guava().build(
          asyncReloading(guavaLoader, executor())), this);
    }
    this.cache = loading;
    return loading;
  }

  public <K, V> AsyncLoadingCache<K, V> buildAsync(CacheLoader<K, V> loader) {
    checkState(isCaffeine() && isAsync());
    AsyncLoadingCache<K, V> async = caffeine().buildAsync(loader);
    this.cache = async.synchronous();
    this.asyncCache = async;
    return async;
  }

  public <K, V> AsyncLoadingCache<K, V> buildAsync(AsyncCacheLoader<K, V> loader) {
    checkState(isCaffeine() && isAsync());
    AsyncLoadingCache<K, V> async = caffeine().buildAsync(loader);
    this.cache = async.synchronous();
    this.asyncCache = async;
    return async;
  }

  public boolean isCaffeine() {
    return (implementation == Implementation.Caffeine);
  }

  public boolean isGuava() {
    return (implementation == Implementation.Guava);
  }

  public CacheScheduler schedulerType() {
    return cacheScheduler;
  }

  public CacheExecutor executorType() {
    return cacheExecutor;
  }

  public TrackingExecutor executor() {
    return requireNonNull(executor);
  }

  public Scheduler scheduler() {
    return requireNonNull(scheduler);
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

    public SerializableFakeTicker(long startTime) {
      advance(Duration.ofNanos(startTime));
      this.startTime = startTime;
    }
  }
}
