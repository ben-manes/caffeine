/*
 * Copyright 2018 Ben Manes. All Rights Reserved.
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

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * This class provides a skeletal implementation of the {@link AsyncLoadingCache} interface to
 * minimize the effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalAsyncCache<K, V> extends AsyncCache<K, V> {
  Logger logger = Logger.getLogger(LocalAsyncCache.class.getName());

  /** Returns the backing {@link LocalCache} data store. */
  LocalCache<K, CompletableFuture<V>> cache();

  /** Returns the policy supported by this implementation and its configuration. */
  Policy<K, V> policy();

  @Override
  default @Nullable CompletableFuture<V> getIfPresent(@Nonnull Object key) {
    return cache().getIfPresent(key, /* recordStats */ true);
  }

  @Override
  default CompletableFuture<V> get(@Nonnull K key,
      @Nonnull Function<? super K, ? extends V> mappingFunction) {
    requireNonNull(mappingFunction);
    return get(key, (k1, executor) -> CompletableFuture.supplyAsync(
        () -> mappingFunction.apply(key), executor));
  }

  @Override
  default CompletableFuture<V> get(K key,
      BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction) {
    return get(key, mappingFunction, /* recordStats */ true);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "NullAway"})
  default CompletableFuture<V> get(K key,
      BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction, boolean recordStats) {
    long startTime = cache().statsTicker().read();
    @SuppressWarnings({"unchecked", "rawtypes"})
    CompletableFuture<V>[] result = new CompletableFuture[1];
    CompletableFuture<V> future = cache().computeIfAbsent(key, k -> {
      result[0] = mappingFunction.apply(key, cache().executor());
      return requireNonNull(result[0]);
    }, recordStats, /* recordLoad */ false);
    if (result[0] != null) {
      AtomicBoolean completed = new AtomicBoolean();
      result[0].whenComplete((value, error) -> {
        if (!completed.compareAndSet(false, true)) {
          // Ignore multiple invocations due to ForkJoinPool retrying on delays
          return;
        }
        long loadTime = cache().statsTicker().read() - startTime;
        if (value == null) {
          if (error != null) {
            logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
          }
          cache().statsCounter().recordLoadFailure(loadTime);
          cache().remove(key, result[0]);
        } else {
          // update the weight and expiration timestamps
          cache().replace(key, result[0], result[0]);
          cache().statsCounter().recordLoadSuccess(loadTime);
        }
      });
    }
    return future;
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  default void put(K key, CompletableFuture<V> valueFuture) {
    if (valueFuture.isCompletedExceptionally()
        || (valueFuture.isDone() && (valueFuture.join() == null))) {
      cache().statsCounter().recordLoadFailure(0L);
      cache().remove(key);
      return;
    }
    AtomicBoolean completed = new AtomicBoolean();
    long startTime = cache().statsTicker().read();
    cache().put(key, valueFuture);
    valueFuture.whenComplete((value, error) -> {
      if (!completed.compareAndSet(false, true)) {
        // Ignore multiple invocations due to ForkJoinPool retrying on delays
        return;
      }
      long loadTime = cache().statsTicker().read() - startTime;
      if (value == null) {
        if (error != null) {
          logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
        }
        cache().remove(key, valueFuture);
        cache().statsCounter().recordLoadFailure(loadTime);
      } else {
        // update the weight and expiration timestamps
        cache().replace(key, valueFuture, valueFuture);
        cache().statsCounter().recordLoadSuccess(loadTime);
      }
    });
  }

  /* ---------------- Synchronous views -------------- */

  final class CacheView<K, V> extends AbstractCacheView<K, V> {
    private static final long serialVersionUID = 1L;

    final LocalAsyncCache<K, V> asyncCache;

    CacheView(LocalAsyncCache<K, V> asyncCache) {
      this.asyncCache = requireNonNull(asyncCache);
    }
    @Override LocalAsyncCache<K, V> asyncCache() {
      return asyncCache;
    }
  }

  @SuppressWarnings("serial")
  abstract class AbstractCacheView<K, V> implements Cache<K, V>, Serializable {
    transient @Nullable AsMapView<K, V> asMapView;

    abstract LocalAsyncCache<K, V> asyncCache();

    @Override
    public @Nullable V getIfPresent(Object key) {
      CompletableFuture<V> future = asyncCache().cache().getIfPresent(key, /* recordStats */ true);
      return Async.getIfReady(future);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      Set<Object> uniqueKeys = new LinkedHashSet<>();
      for (Object key : keys) {
        uniqueKeys.add(key);
      }

      int misses = 0;
      Map<Object, Object> result = new LinkedHashMap<>();
      for (Object key : uniqueKeys) {
        CompletableFuture<V> future = asyncCache().cache().get(key);
        Object value = Async.getIfReady(future);
        if (value == null) {
          misses++;
        } else {
          result.put(key, value);
        }
      }
      asyncCache().cache().statsCounter().recordMisses(misses);
      asyncCache().cache().statsCounter().recordHits(result.size());

      @SuppressWarnings("unchecked")
      Map<K, V> castedResult = (Map<K, V>) result;
      return Collections.unmodifiableMap(castedResult);
    }

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace")
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      CompletableFuture<V> future = asyncCache().get(key, (k, executor) ->
          CompletableFuture.supplyAsync(() -> mappingFunction.apply(key), executor));
      try {
        return future.get();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        } else if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw new CompletionException(e.getCause());
      } catch (InterruptedException e) {
        throw new CompletionException(e);
      }
    }

    @Override
    public void put(K key, V value) {
      requireNonNull(value);
      asyncCache().cache().put(key, CompletableFuture.completedFuture(value));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      map.forEach(this::put);
    }

    @Override
    public void invalidate(Object key) {
      asyncCache().cache().remove(key);
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      asyncCache().cache().invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
      asyncCache().cache().clear();
    }

    @Override
    public long estimatedSize() {
      return asyncCache().cache().size();
    }

    @Override
    public CacheStats stats() {
      return asyncCache().cache().statsCounter().snapshot();
    }

    @Override
    public void cleanUp() {
      asyncCache().cache().cleanUp();
    }

    @Override
    public Policy<K, V> policy() {
      return asyncCache().policy();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return (asMapView == null) ? (asMapView = new AsMapView<>(asyncCache().cache())) : asMapView;
    }
  }

  final class AsMapView<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    final LocalCache<K, CompletableFuture<V>> delegate;

    @Nullable Collection<V> values;
    @Nullable Set<Entry<K, V>> entries;

    AsMapView(LocalCache<K, CompletableFuture<V>> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public boolean containsKey(Object key) {
      return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      requireNonNull(value);

      for (CompletableFuture<V> valueFuture : delegate.values()) {
        if (value.equals(Async.getIfReady(valueFuture))) {
          return true;
        }
      }
      return false;
    }

    @Override
    public @Nullable V get(Object key) {
      return Async.getIfReady(delegate.get(key));
    }

    @Override
    public @Nullable V putIfAbsent(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> valueFuture =
          delegate.putIfAbsent(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(valueFuture);
    }

    @Override
    public @Nullable V put(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.put(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public @Nullable V remove(Object key) {
      CompletableFuture<V> oldValueFuture = delegate.remove(key);
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public boolean remove(Object key, Object value) {
      requireNonNull(key);
      if (value == null) {
        return false;
      }

      @SuppressWarnings("unchecked")
      K castedKey = (K) key;
      boolean[] removed = { false };
      boolean[] done = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        V oldValue = Async.getWhenSuccessful(future);
        if ((future != null) && !value.equals(oldValue)) {
          // Optimistically check if the current value is equal, but don't skip if it may be loading
          return false;
        }

        delegate.compute(castedKey, (k, oldValueFuture) -> {
          if (future != oldValueFuture) {
            return oldValueFuture;
          }
          done[0] = true;
          removed[0] = value.equals(oldValue);
          return removed[0] ? null : oldValueFuture;
        }, /* recordStats */ false, /* recordLoad */ false);
        if (done[0]) {
          return removed[0];
        }
      }
    }

    @Override
    public @Nullable V replace(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.replace(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      requireNonNull(oldValue);
      requireNonNull(newValue);
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      if ((oldValueFuture != null) && !oldValue.equals(Async.getWhenSuccessful(oldValueFuture))) {
        // Optimistically check if the current value is equal, but don't skip if it may be loading
        return false;
      }

      @SuppressWarnings("unchecked")
      K castedKey = key;
      boolean[] replaced = { false };
      delegate.compute(castedKey, (k, value) -> {
        replaced[0] = oldValue.equals(Async.getWhenSuccessful(value));
        return replaced[0] ? CompletableFuture.completedFuture(newValue) : value;
      }, /* recordStats */ false, /* recordLoad */ false);
      return replaced[0];
    }

    @Override
    public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfAbsent(key, k -> {
        V newValue = mappingFunction.apply(key);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Async.getWhenSuccessful(valueFuture);
    }

    @Override
    public @Nullable V computeIfPresent(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      boolean[] computed = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        V oldValue = Async.getWhenSuccessful(future);
        if (oldValue == null) {
          return null;
        }
        CompletableFuture<V> valueFuture = delegate.computeIfPresent(key, (k, oldValueFuture) -> {
          if (future != oldValueFuture) {
            return oldValueFuture;
          }
          computed[0] = true;
          V newValue = remappingFunction.apply(key, oldValue);
          return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
        });
        if (computed[0] || (valueFuture == null)) {
          return Async.getWhenSuccessful(valueFuture);
        }
      }
    }

    @Override
    public @Nullable V compute(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      boolean[] computed = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        V oldValue = Async.getWhenSuccessful(future);
        CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
          if (future != oldValueFuture) {
            return oldValueFuture;
          }
          computed[0] = true;
          long startTime = delegate.statsTicker().read();
          V newValue = remappingFunction.apply(key, oldValue);
          long loadTime = delegate.statsTicker().read() - startTime;
          if (newValue == null) {
            delegate.statsCounter().recordLoadFailure(loadTime);
            return null;
          }
          delegate.statsCounter().recordLoadSuccess(loadTime);
          return CompletableFuture.completedFuture(newValue);
        }, /* recordMiss */ false, /* recordLoad */ false);
        if (computed[0]) {
          return Async.getWhenSuccessful(valueFuture);
        }
      }
    }

    @Override
    public @Nullable V merge(K key, V value,
        BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      requireNonNull(value);
      requireNonNull(remappingFunction);
      CompletableFuture<V> newValueFuture = CompletableFuture.completedFuture(value);
      boolean[] merged = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        V oldValue = Async.getWhenSuccessful(future);
        CompletableFuture<V> mergedValueFuture = delegate.merge(
            key, newValueFuture, (oldValueFuture, valueFuture) -> {
          if (future != oldValueFuture) {
            return oldValueFuture;
          }
          merged[0] = true;
          if (oldValue == null) {
            return valueFuture;
          }
          V mergedValue = remappingFunction.apply(oldValue, value);
          if (mergedValue == null) {
            return null;
          } else if (mergedValue == oldValue) {
            return oldValueFuture;
          } else if (mergedValue == value) {
            return valueFuture;
          }
          return CompletableFuture.completedFuture(mergedValue);
        });
        if (merged[0] || (mergedValueFuture == newValueFuture)) {
          return Async.getWhenSuccessful(mergedValueFuture);
        }
      }
    }

    @Override
    public Set<K> keySet() {
      return delegate.keySet();
    }

    @Override
    public Collection<V> values() {
      return (values == null) ? (values = new Values()) : values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return (entries == null) ? (entries = new EntrySet()) : entries;
    }

    private final class Values extends AbstractCollection<V> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public boolean contains(Object o) {
        return AsMapView.this.containsValue(o);
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public Iterator<V> iterator() {
        return new Iterator<V>() {
          Iterator<Entry<K, V>> iterator = entrySet().iterator();

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public V next() {
            return iterator.next().getValue();
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }

    private final class EntrySet extends AbstractSet<Entry<K, V>> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public boolean contains(Object o) {
        if (!(o instanceof Entry<?, ?>)) {
          return false;
        }
        Entry<?, ?> entry = (Entry<?, ?>) o;
        V value = AsMapView.this.get(entry.getKey());
        return (value != null) && value.equals(entry.getValue());
      }

      @Override
      public boolean remove(Object obj) {
        if (!(obj instanceof Entry<?, ?>)) {
          return false;
        }
        Entry<?, ?> entry = (Entry<?, ?>) obj;
        return AsMapView.this.remove(entry.getKey(), entry.getValue());
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
        return new Iterator<Entry<K, V>>() {
          Iterator<Entry<K, CompletableFuture<V>>> iterator = delegate.entrySet().iterator();
          @Nullable Entry<K, V> cursor;
          @Nullable K removalKey;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              Entry<K, CompletableFuture<V>> entry = iterator.next();
              V value = Async.getIfReady(entry.getValue());
              if (value != null) {
                cursor = new WriteThroughEntry<>(AsMapView.this, entry.getKey(), value);
              }
            }
            return (cursor != null);
          }

          @Override
          public Entry<K, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            @SuppressWarnings("NullAway")
            K key = cursor.getKey();
            Entry<K, V> entry = cursor;
            removalKey = key;
            cursor = null;
            return entry;
          }

          @Override
          public void remove() {
            Caffeine.requireState(removalKey != null);
            delegate.remove(removalKey);
            removalKey = null;
          }
        };
      }
    }
  }
}
