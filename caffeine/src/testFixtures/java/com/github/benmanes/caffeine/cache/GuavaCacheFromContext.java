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

import static com.github.benmanes.caffeine.testing.Nullness.nullRef;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.Policy.CacheEntry;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.Weigher;
import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.ForwardingSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.errorprone.annotations.Var;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"PMD.PreserveStackTrace", "serial"})
public final class GuavaCacheFromContext {
  private static final ThreadLocal<@Nullable Throwable> error = new ThreadLocal<>();

  private GuavaCacheFromContext() {}

  /** Returns a Guava-backed cache. */
  @SuppressWarnings("CheckReturnValue")
  public static <K, V> Cache<K, V> newGuavaCache(CacheContext context) {
    checkState(!context.isAsync(), "Guava caches are synchronous only");

    var builder = CacheBuilder.newBuilder();
    context.with(builder);

    builder.concurrencyLevel(1);
    if (context.initialCapacity() != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity().size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximum() != Maximum.DISABLED) {
      if (context.cacheWeigher() == CacheWeigher.DISABLED) {
        builder.maximumSize(context.maximum().max());
      } else {
        builder.weigher(new GuavaWeigher<>(context.weigher()));
        builder.maximumWeight(context.maximumWeight());
      }
    }
    if (context.expiresAfterAccess()) {
      builder.expireAfterAccess(context.expireAfterAccess().duration());
    }
    if (context.expiresAfterWrite()) {
      builder.expireAfterWrite(context.expireAfterWrite().duration());
    }
    if (context.refreshes()) {
      builder.refreshAfterWrite(context.refreshAfterWrite().duration());
    }
    if (context.expires() || context.refreshes()) {
      builder.ticker(context.ticker());
    }
    if (context.isWeakKeys()) {
      builder.weakKeys();
    } else if (!context.isStrongKeys()) {
      throw new IllegalStateException();
    }
    if (context.isWeakValues()) {
      builder.weakValues();
    } else if (context.isSoftValues()) {
      builder.softValues();
    }
    if (context.removalListenerType() != Listener.DISABLED) {
      boolean translateZeroExpire = (context.expireAfterAccess() == Expire.IMMEDIATELY) ||
          (context.expireAfterWrite() == Expire.IMMEDIATELY);
      builder.removalListener(new GuavaRemovalListener<>(
          translateZeroExpire, context.removalListener()));
    }
    if (context.loader() == Loader.DISABLED) {
      context.with(new GuavaCache<>(builder.<Int, Int>build(), context));
    } else if (context.loader().isBulk()) {
      var loader = new BulkLoader<>(context.loader());
      context.with(new GuavaLoadingCache<>(builder.build(loader), context));
    } else {
      var loader = new SingleLoader<>(context.loader());
      context.with(new GuavaLoadingCache<>(builder.build(loader), context));
    }
    @SuppressWarnings("unchecked")
    var castedCache = (Cache<K, V>) context.cache();
    return castedCache;
  }

  static class GuavaCache<K, V> implements Cache<K, @Nullable V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final com.google.common.cache.Cache<K, V> cache;
    private final boolean isRecordingStats;
    private final boolean canSnapshot;
    private final Ticker ticker;

    transient @Nullable ConcurrentMap<K, V> mapView;
    transient @Nullable StatsCounter statsCounter;
    transient @Nullable Policy<K, V> policy;
    transient @Nullable Set<K> keySet;

    GuavaCache(com.google.common.cache.Cache<K, V> cache, CacheContext context) {
      this.canSnapshot = context.expires() || context.refreshes();
      this.isRecordingStats = context.isRecordingStats();
      this.cache = requireNonNull(cache);
      this.ticker = context.ticker();
    }

    @Override
    public @Nullable V getIfPresent(K key) {
      return cache.getIfPresent(key);
    }

    @Override
    public @Nullable V get(K key, Function<? super K, ? extends @Nullable V> mappingFunction) {
      requireNonNull(mappingFunction);
      try {
        return cache.get(key, () -> {
          V value = mappingFunction.apply(key);
          if (value == null) {
            throw CacheMissException.INSTANCE;
          }
          return value;
        });
      } catch (UncheckedExecutionException e) {
        if (e.getCause() instanceof CacheMissException) {
          return null;
        }
        throw (RuntimeException) e.getCause();
      } catch (ExecutionException e) {
        throw new CompletionException(e.getCause());
      } catch (ExecutionError e) {
        throw (Error) e.getCause();
      }
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<? extends K> keys) {
      for (K key : keys) {
        requireNonNull(key);
      }
      return cache.getAllPresent(keys);
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys, Function<? super Set<? extends K>,
        ? extends Map<? extends K, ? extends V>> mappingFunction) {
      keys.forEach(Objects::requireNonNull);
      requireNonNull(mappingFunction);

      Map<K, V> found = getAllPresent(keys);
      Set<K> keysToLoad = Sets.difference(ImmutableSet.copyOf(keys), found.keySet());
      if (keysToLoad.isEmpty()) {
        return found;
      }

      long start = ticker.read();
      try {
        var loaded = mappingFunction.apply(keysToLoad);
        loaded.forEach(cache::put);
        long end = ticker.read();
        statsCounter().recordLoadSuccess(end - start);

        var result = new LinkedHashMap<K, V>();
        for (K key : keys) {
          @Var V value = found.get(key);
          if (value == null) {
            value = loaded.get(key);
          }
          if (value != null) {
            result.put(key, value);
          }
        }
        return Collections.unmodifiableMap(result);
      } catch (Throwable t) {
        long end = ticker.read();
        statsCounter().recordLoadException(end - start);
        throw t;
      }
    }

    @Override
    public void put(K key, V value) {
      requireNonNull(key);
      requireNonNull(value);
      cache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      cache.putAll(map);
    }

    @Override
    public void invalidate(K key) {
      cache.invalidate(key);
    }

    @Override
    public void invalidateAll(Iterable<? extends K> keys) {
      keys.forEach(this::invalidate);
    }

    @Override
    public void invalidateAll() {
      cache.invalidateAll();
    }

    @Override
    public long estimatedSize() {
      return cache.size();
    }

    @Override
    public CacheStats stats() {
      var stats = statsCounter().snapshot().plus(cache.stats());
      return CacheStats.of(stats.hitCount(), stats.missCount(), stats.loadSuccessCount(),
          stats.loadExceptionCount(), stats.totalLoadTime(), stats.evictionCount(), 0);
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return (mapView == null) ? (mapView = new AsMapView()) : mapView;
    }

    @Override
    public void cleanUp() {
      cache.cleanUp();
    }

    @Override
    public Policy<K, V> policy() {
      return (policy == null) ? (policy = new GuavaPolicy()) : policy;
    }

    private StatsCounter statsCounter() {
      return (statsCounter == null) ? (statsCounter = new SimpleStatsCounter()) : statsCounter;
    }

    final class AsMapView extends ForwardingConcurrentMap<K, V> {
      @Override
      public boolean containsKey(@Nullable Object key) {
        requireNonNull(key);
        return delegate().containsKey(key);
      }
      @Override
      public boolean containsValue(@Nullable Object value) {
        requireNonNull(value);
        return delegate().containsValue(value);
      }
      @Override
      public @Nullable V get(@Nullable Object key) {
        requireNonNull(key);
        return delegate().get(key);
      }
      @Override
      public @Nullable V remove(@Nullable Object key) {
        requireNonNull(key);
        return delegate().remove(key);
      }
      @Override
      public boolean remove(@Nullable Object key, @Nullable Object value) {
        requireNonNull(key);
        return delegate().remove(key, value);
      }
      @Override
      public boolean replace(K key, V oldValue, V newValue) {
        requireNonNull(oldValue);
        return delegate().replace(key, oldValue, newValue);
      }
      @Override
      public @Nullable V computeIfAbsent(K key,
          Function<? super K, ? extends @Nullable V> mappingFunction) {
        requireNonNull(mappingFunction);
        boolean[] present = { true };
        long now = ticker.read();
        V result = delegate().computeIfAbsent(key, k -> {
          statsCounter().recordMisses(1);
          present[0] = false;
          try {
            V value = mappingFunction.apply(key);
            if (value == null) {
              statsCounter().recordLoadException(ticker.read() - now);
            }
            return value;
          } catch (Throwable t) {
            statsCounter().recordLoadException(ticker.read() - now);
            throw t;
          }
        });
        if (present[0]) {
          statsCounter().recordHits(1);
        }
        return result;
      }
      @Override
      public @Nullable V computeIfPresent(K key,
          BiFunction<? super K, ? super V, ? extends @Nullable V> remappingFunction) {
        requireNonNull(remappingFunction);
        long now = ticker.read();
        return delegate().computeIfPresent(key, (k, v) -> {
          try {
            V value = remappingFunction.apply(k, v);
            if (value == null) {
              statsCounter().recordLoadException(ticker.read() - now);
            } else if (value == v) {
              statsCounter().recordLoadSuccess(ticker.read() - now);
            }
            return value;
          } catch (Throwable t) {
            statsCounter().recordLoadException(ticker.read() - now);
            throw t;
          }
        });
      }
      @Override
      public @Nullable V compute(K key,
          BiFunction<? super K, ? super @Nullable V, ? extends @Nullable V> remappingFunction) {
        requireNonNull(remappingFunction);
        long now = ticker.read();
        return delegate().compute(key, (k, v) -> {
          try {
            V value = remappingFunction.apply(k, v);
            if (value == null) {
              statsCounter().recordLoadException(ticker.read() - now);
            } else if (value == v) {
              statsCounter().recordLoadSuccess(ticker.read() - now);
            }
            return value;
          } catch (Throwable t) {
            statsCounter().recordLoadException(ticker.read() - now);
            throw t;
          }
        });
      }
      @Override
      public @Nullable V merge(K key, V value,
          BiFunction<? super V, ? super V, ? extends @Nullable V> remappingFunction) {
        requireNonNull(remappingFunction);
        long now = ticker.read();
        return delegate().merge(key, value, (k, v) -> {
          try {
            V newValue = remappingFunction.apply(k, v);
            if (newValue == null) {
              statsCounter().recordLoadException(ticker.read() - now);
            }
            return newValue;
          } catch (Throwable t) {
            statsCounter().recordLoadException(ticker.read() - now);
            throw t;
          }
        });
      }
      @Override
      public Set<K> keySet() {
        return (keySet == null) ? (keySet = new KeySetView()) : keySet;
      }
      @Override
      protected ConcurrentMap<K, V> delegate() {
        return cache.asMap();
      }
      final class KeySetView extends ForwardingSet<K> {
        @Override public boolean remove(@Nullable Object o) {
          requireNonNull(o);
          return delegate().remove(o);
        }
        @Override protected Set<K> delegate() {
          return cache.asMap().keySet();
        }
      }
    }

    final class GuavaPolicy implements Policy<K, V> {
      @Override public boolean isRecordingStats() {
        return isRecordingStats;
      }
      @Override public @Nullable V getIfPresentQuietly(K key) {
        checkNotNull(key);
        return cache.asMap().get(key);
      }
      @Override public @Nullable CacheEntry<K, V> getEntryIfPresentQuietly(K key) {
        checkNotNull(key);
        V value = cache.asMap().get(key);
        if (value == null) {
          return null;
        }
        long snapshotAt = canSnapshot ? ticker.read() : 0L;
        return new GuavaCacheEntry<>(key, value, snapshotAt);
      }
      @SuppressWarnings("RedundantUnmodifiable")
      @Override public Map<K, CompletableFuture<V>> refreshes() {
        return Collections.unmodifiableMap(Collections.emptyMap());
      }
      @Override public Optional<Eviction<K, V>> eviction() {
        return Optional.empty();
      }
      @Override public Optional<FixedExpiration<K, V>> expireAfterAccess() {
        return Optional.empty();
      }
      @Override public Optional<FixedExpiration<K, V>> expireAfterWrite() {
        return Optional.empty();
      }
      @Override public Optional<VarExpiration<K, V>> expireVariably() {
        return Optional.empty();
      }
      @Override public Optional<FixedRefresh<K, V>> refreshAfterWrite() {
        return Optional.empty();
      }
    }
  }

  static class GuavaLoadingCache<K, V> extends GuavaCache<K, V> implements LoadingCache<K, V> {
    private static final long serialVersionUID = 1L;

    private final com.google.common.cache.LoadingCache<K, V> cache;

    GuavaLoadingCache(com.google.common.cache.LoadingCache<K, V> cache, CacheContext context) {
      super(cache, context);
      this.cache = requireNonNull(cache);
    }

    @Override
    public V get(K key) {
      try {
        return cache.get(key);
      } catch (UncheckedExecutionException e) {
        if (e.getCause() instanceof CacheMissException) {
          return nullRef();
        }
        throw (RuntimeException) e.getCause();
      } catch (ExecutionException e) {
        throw new CompletionException(e.getCause());
      } catch (ExecutionError e) {
        throw (Error) e.getCause();
      }
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      try {
        return cache.getAll(keys);
      } catch (UncheckedExecutionException e) {
        if (e.getCause() instanceof CacheMissException) {
          var results = new LinkedHashMap<K, V>();
          for (K key : keys) {
            var value = cache.asMap().get(key);
            if (value != null) {
              results.put(key, value);
            }
          }
          return Collections.unmodifiableMap(results);
        }
        throw (RuntimeException) e.getCause();
      } catch (ExecutionException e) {
        throw new CompletionException(e.getCause());
      } catch (ExecutionError e) {
        throw (Error) e.getCause();
      }
    }

    @Override
    @SuppressFBWarnings("ITC_INHERITANCE_TYPE_CHECKING")
    public CompletableFuture<V> refresh(K key) {
      error.remove();
      cache.refresh(key);

      var e = error.get();
      if (e == null) {
        return CompletableFuture.completedFuture(cache.asMap().get(key));
      } else if (e instanceof InterruptedException) {
        throw new CompletionException(e);
      } else if (e instanceof CacheMissException) {
        return CompletableFuture.completedFuture(null);
      }

      error.remove();
      return CompletableFuture.failedFuture(e);
    }

    @Override
    public CompletableFuture<Map<K, V>> refreshAll(Iterable<? extends K> keys) {
      var result = new LinkedHashMap<K, CompletableFuture<@Nullable V>>();
      for (K key : keys) {
        result.computeIfAbsent(key, this::refresh);
      }
      return composeResult(result);
    }

    @SuppressWarnings("RedundantUnmodifiable")
    CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<@Nullable V>> futures) {
      if (futures.isEmpty()) {
        return CompletableFuture.completedFuture(
            Collections.unmodifiableMap(Collections.emptyMap()));
      }
      @SuppressWarnings("rawtypes")
      CompletableFuture<?>[] array = futures.values().toArray(new CompletableFuture[0]);
      return CompletableFuture.allOf(array).thenApply(ignored -> {
        var result = new LinkedHashMap<K, V>(futures.size());
        futures.forEach((key, future) -> {
          V value = future.getNow(null);
          if (value != null) {
            result.put(key, value);
          }
        });
        return Collections.unmodifiableMap(result);
      });
    }
  }

  static final class GuavaWeigher<K, V> implements Weigher<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    final com.github.benmanes.caffeine.cache.Weigher<K, V> weigher;

    GuavaWeigher(com.github.benmanes.caffeine.cache.Weigher<K, V> weigher) {
      this.weigher = com.github.benmanes.caffeine.cache.Weigher.boundedWeigher(weigher);
    }

    @Override public int weigh(K key, V value) {
      return weigher.weigh(key, value);
    }
  }

  static final class GuavaRemovalListener<K, V> implements RemovalListener<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    final com.github.benmanes.caffeine.cache.RemovalListener<K, V> delegate;
    final boolean translateZeroExpire;

    GuavaRemovalListener(boolean translateZeroExpire,
        com.github.benmanes.caffeine.cache.RemovalListener<K, V> delegate) {
      this.translateZeroExpire = translateZeroExpire;
      this.delegate = delegate;
    }

    @Override
    public void onRemoval(RemovalNotification<K, V> notification) {
      @Var var cause = RemovalCause.valueOf(notification.getCause().name());
      if (translateZeroExpire && (cause == RemovalCause.SIZE)) {
        // Guava internally uses sizing logic for null cache case
        cause = RemovalCause.EXPIRED;
      }
      delegate.onRemoval(notification.getKey(), notification.getValue(), cause);
    }
  }

  static class SingleLoader<K, V> extends CacheLoader<K, V> implements Serializable {
    private static final long serialVersionUID = 1L;

    final com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate;

    SingleLoader(com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    @SuppressWarnings({"ConstantValue", "PMD.ExceptionAsFlowControl"})
    public V load(K key) throws Exception {
      try {
        error.remove();
        V value = delegate.load(key);
        if (value == null) {
          throw CacheMissException.INSTANCE;
        }
        return value;
      } catch (Exception e) {
        error.set(e);
        throw e;
      }
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public ListenableFuture<V> reload(K key, V oldValue) throws Exception {
      error.remove();
      var future = SettableFuture.<V>create();
      delegate.asyncReload(key, oldValue, Runnable::run).whenComplete((r, e) -> {
        if (e == null) {
          future.set(r);
        } else {
          future.setException(e);
          error.set(e);
        }
      });
      return future;
    }
  }

  static class BulkLoader<K, V> extends SingleLoader<K, V> {
    private static final long serialVersionUID = 1L;

    BulkLoader(com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate) {
      super(delegate);
    }

    @Override
    public Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception {
      var keysToLoad = (keys instanceof Set) ? (Set<? extends K>) keys : ImmutableSet.copyOf(keys);
      @SuppressWarnings("unchecked")
      var loaded = (Map<K, V>) delegate.loadAll(keysToLoad);
      return loaded;
    }
  }

  @SuppressFBWarnings("EQ_DOESNT_OVERRIDE_EQUALS")
  static final class GuavaCacheEntry<K, V>
      extends SimpleImmutableEntry<K, V> implements CacheEntry<K, V> {
    private static final long serialVersionUID = 1L;

    private final long snapshot;

    public GuavaCacheEntry(K key, V value, long snapshot) {
      super(key, value);
      this.snapshot = snapshot;
    }

    @Override public int weight() {
      return 1;
    }
    @Override public long expiresAt() {
      return snapshot + Long.MAX_VALUE;
    }
    @Override public long refreshableAt() {
      return snapshot + Long.MAX_VALUE;
    }
    @Override public long snapshotAt() {
      return snapshot;
    }
  }

  static final class CacheMissException extends RuntimeException {
    private static final CacheMissException INSTANCE = new CacheMissException();
    private static final long serialVersionUID = 1L;

    CacheMissException() {
      super(/* message= */ null, /* cause= */ null,
          /* enableSuppression= */ false, /* writableStackTrace= */ false);
    }
  }
}
