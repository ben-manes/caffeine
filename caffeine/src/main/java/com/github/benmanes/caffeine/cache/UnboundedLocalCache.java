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

import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * An in-memory cache that has no capabilities for bounding the map. This implementation provides
 * a lightweight wrapper on top of {@link ConcurrentHashMap}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class UnboundedLocalCache<K, V> implements LocalCache<K, V> {
  @Nullable final RemovalListener<K, V> removalListener;
  final ConcurrentHashMap<K, V> data;
  final Executor executor;
  final Ticker ticker;

  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  boolean isRecordingStats;
  StatsCounter statsCounter;

  UnboundedLocalCache(Caffeine<? super K, ? super V> builder, boolean async) {
    this.data = new ConcurrentHashMap<K, V>(builder.getInitialCapacity());
    this.statsCounter = builder.getStatsCounterSupplier().get();
    this.removalListener = builder.getRemovalListener(async);
    this.isRecordingStats = builder.isRecordingStats();
    this.executor = builder.getExecutor();
    this.ticker = builder.getTicker();
  }

  /* ---------------- Cache -------------- */

  @Override
  public V getIfPresent(Object key, boolean recordStats) {
    V value = data.get(key);

    if (recordStats) {
      if (value == null) {
        statsCounter.recordMisses(1);
      } else {
        statsCounter.recordHits(1);
      }
    }
    return value;
  }

  @Override
  public long estimatedSize() {
    return data.mappingCount();
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int hits = 0;
    int misses = 0;
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      V value = data.get(key);
      if (value == null) {
        misses++;
      } else {
        hits++;
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        result.put(castKey, value);
      }
    }
    statsCounter.recordHits(hits);
    statsCounter.recordMisses(misses);
    return Collections.unmodifiableMap(result);
  }

  public void invalidateAll() {
    clear();
  }

  @Override
  public void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      remove(key);
    }
  }

  @Override
  public void cleanUp() {}

  @Override
  public StatsCounter statsCounter() {
    return statsCounter;
  }

  void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    notifyRemoval(new RemovalNotification<K, V>(key, value, cause));
  }

  void notifyRemoval(RemovalNotification<K, V> notification) {
    requireNonNull(removalListener, "Notification should be guarded with a check");
    executor.execute(() -> removalListener.onRemoval(notification));
  }

  boolean hasRemovalListener() {
    return (removalListener != null);
  }

  @Override
  public RemovalListener<K, V> removalListener() {
    return removalListener;
  }

  @Override
  public boolean isRecordingStats() {
    return isRecordingStats;
  }

  @Override
  public Ticker ticker() {
    return ticker;
  }

  @Override
  public Executor executor() {
    return executor;
  }

  /* ---------------- JDK8+ Map extensions -------------- */

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    data.forEach(action);
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    if (!hasRemovalListener()) {
      data.replaceAll(function);
      return;
    }

    // ensures that the removal notification is processed after the removal has completed
    requireNonNull(function);
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    data.replaceAll((key, value) -> {
      if (notification[0] != null) {
        notifyRemoval(notification[0]);
        notification[0] = null;
      }
      V newValue = requireNonNull(function.apply(key, value));
      notification[0] = new RemovalNotification<K, V>(key, value, RemovalCause.REPLACED);
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean isAsync) {
    requireNonNull(mappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    V value = data.get(key);
    if (value != null) {
      statsCounter.recordHits(1);
      return value;
    }

    if (!isRecordingStats) {
      return data.computeIfAbsent(key, mappingFunction);
    }
    boolean[] missed = new boolean[1];
    value = data.computeIfAbsent(key, k -> {
      missed[0] = true;
      try {
        return statsAware(mappingFunction, isAsync).apply(key);
      } catch (RuntimeException | Error e) {
        throw e;
      }
    });
    if (!missed[0]) {
      statsCounter.recordHits(1);
    }
    return value;
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    if (!data.containsKey(key)) {
      return null;
    }
    if (!hasRemovalListener()) {
      return data.computeIfPresent(key, statsAware(remappingFunction, false, false));
    }
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = data.computeIfPresent(key, (K k, V oldValue) -> {
      V newValue = statsAware(remappingFunction, false, false).apply(k, oldValue);
      notification[0] = (newValue == null)
          ? new RemovalNotification<K, V>(key, oldValue, RemovalCause.EXPLICIT)
          : new RemovalNotification<K, V>(key, oldValue, RemovalCause.REPLACED);
          return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss, boolean isAsync) {
    if (!hasRemovalListener()) {
      return data.compute(key, statsAware(remappingFunction, recordMiss, isAsync));
    }

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = data.compute(key, (K k, V oldValue) -> {
      V newValue = statsAware(remappingFunction, recordMiss, isAsync).apply(k, oldValue);
      if (oldValue != null) {
        notification[0] = (newValue == null)
            ? new RemovalNotification<K, V>(key, oldValue, RemovalCause.EXPLICIT)
            : new RemovalNotification<K, V>(key, oldValue, RemovalCause.REPLACED);
      }
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);
    if (!hasRemovalListener()) {
      return data.merge(key, value, statsAware(remappingFunction));
    }

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings("unchecked")
    RemovalNotification<K, V>[] notification = new RemovalNotification[1];
    V nv = data.merge(key, value, (V oldValue, V val) -> {
      V newValue = statsAware(remappingFunction).apply(oldValue, val);
      notification[0] = (newValue == null)
          ? new RemovalNotification<K, V>(key, oldValue, RemovalCause.EXPLICIT)
          : new RemovalNotification<K, V>(key, oldValue, RemovalCause.REPLACED);
      return newValue;
    });
    if (notification[0] != null) {
      notifyRemoval(notification[0]);
    }
    return nv;
  }

  /** Decorates the remapping function to record statistics if enabled. */
  Function<? super K, ? extends V> statsAware(
      Function<? super K, ? extends V> mappingFunction, boolean isAsync) {
    if (!isRecordingStats) {
      return mappingFunction;
    }
    return key -> {
      V value;
      statsCounter.recordMisses(1);
      long startTime = ticker.read();
      try {
        value = mappingFunction.apply(key);
      } catch (RuntimeException | Error e) {
        statsCounter.recordLoadFailure(ticker.read() - startTime);
        throw e;
      }
      long loadTime = ticker.read() - startTime;
      if (!isAsync) {
        if (value == null) {
          statsCounter.recordLoadFailure(loadTime);
        } else {
          statsCounter.recordLoadSuccess(loadTime);
        }
      }
      return value;
    };
  }

  <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction) {
    return statsAware(remappingFunction, true, false);
  }

  /** Decorates the remapping function to record statistics if enabled. */
  <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction,
      boolean recordMiss, boolean isAsync) {
    if (!isRecordingStats) {
      return remappingFunction;
    }
    return (t, u) -> {
      R result;
      if ((u == null) && recordMiss) {
        statsCounter.recordMisses(1);
      }
      long startTime = ticker.read();
      try {
        result = remappingFunction.apply(t, u);
      } catch (RuntimeException | Error e) {
        statsCounter.recordLoadFailure(ticker.read() - startTime);
        throw e;
      }
      long loadTime = ticker.read() - startTime;
      if (!isAsync) {
        if (result == null) {
          statsCounter.recordLoadFailure(loadTime);
        } else {
          statsCounter.recordLoadSuccess(loadTime);
        }
      }
      return result;
    };
  }

  /* ---------------- Concurrent Map -------------- */

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public void clear() {
    if (!hasRemovalListener()) {
      data.clear();
      return;
    }
    for (K key : data.keySet()) {
      V value = data.remove(key);
      notifyRemoval(key, value, RemovalCause.EXPLICIT);
    }
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public boolean containsKey(Object key) {
    return data.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return data.containsValue(value);
  }

  @Override
  public V get(Object key) {
    return getIfPresent(key, false);
  }

  @Override
  public V put(K key, V value) {
    V oldValue = data.put(key, value);
    if (hasRemovalListener() && (oldValue != null) && (oldValue != value)) {
      notifyRemoval(key, oldValue, RemovalCause.REPLACED);
    }
    return oldValue;
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return data.putIfAbsent(key, value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    if (!hasRemovalListener()) {
      data.putAll(map);
      return;
    }
    for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
      V oldValue = data.put(entry.getKey(), entry.getValue());
      if ((oldValue != null) && (oldValue != entry.getValue())) {
        notifyRemoval(entry.getKey(), oldValue, RemovalCause.REPLACED);
      }
    }
  }

  @Override
  public V remove(Object key) {
    V value = data.remove(key);
    if (hasRemovalListener() && (value != null)) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      notifyRemoval(castKey, value, RemovalCause.EXPLICIT);
    }
    return value;
  }

  @Override
  public boolean remove(Object key, Object value) {
    boolean removed = data.remove(key, value);
    if (hasRemovalListener() && removed) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      @SuppressWarnings("unchecked")
      V castValue = (V) value;
      notifyRemoval(castKey, castValue, RemovalCause.EXPLICIT);
    }
    return removed;
  }

  @Override
  public V replace(K key, V value) {
    V prev = data.replace(key, value);
    if ((hasRemovalListener()) && (prev != null) && (prev != value)) {
      notifyRemoval(key, value, RemovalCause.REPLACED);
    }
    return prev;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    boolean replaced = data.replace(key, oldValue, newValue);
    if (hasRemovalListener() && replaced  && (oldValue != newValue)) {
      notifyRemoval(key, oldValue, RemovalCause.REPLACED);
    }
    return replaced;
  }

  @Override
  public boolean equals(Object o) {
    return data.equals(o);
  }

  @Override
  public int hashCode() {
    return data.hashCode();
  }

  @Override
  public String toString() {
    return data.toString();
  }

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySetView<K>(this)) : ks;
  }

  @Override
  public Collection<V> values() {
    final Collection<V> vs = values;
    return (vs == null) ? (values = new ValuesView(this)) : vs;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    final Set<Entry<K, V>> es = entrySet;
    return (es == null) ? (entrySet = new EntrySetView(this)) : es;
  }

  /** An adapter to safely externalize the keys. */
  static class KeySetView<K> extends AbstractSet<K> {
    final UnboundedLocalCache<K, ?> local;

    KeySetView(UnboundedLocalCache<K, ?> local) {
      this.local = requireNonNull(local);
    }

    @Override
    public boolean isEmpty() {
      return local.isEmpty();
    }

    @Override
    public int size() {
      return local.size();
    }

    @Override
    public void clear() {
      local.clear();
    }

    @Override
    public boolean contains(Object o) {
      return local.containsKey(o);
    }

    @Override
    public boolean remove(Object obj) {
      return (local.remove(obj) != null);
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<K>(local);
    }

    @Override
    public Spliterator<K> spliterator() {
      return local.data.keySet().spliterator();
    }
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K> implements Iterator<K> {
    final UnboundedLocalCache<K, ?> local;
    final Iterator<K> iterator;
    K key;

    KeyIterator(UnboundedLocalCache<K, ?> local) {
      this.local = requireNonNull(local);
      this.iterator = local.data.keySet().iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      key = iterator.next();
      return key;
    }

    @Override
    public void remove() {
      Caffeine.requireState(key != null);
      local.remove(key);
      key = null;
    }
  }

  /** An adapter to safely externalize the values. */
  final class ValuesView extends AbstractCollection<V> {
    final UnboundedLocalCache<K, V> local;

    ValuesView(UnboundedLocalCache<K, V> local) {
      this.local = requireNonNull(local);
    }

    @Override
    public boolean isEmpty() {
      return local.isEmpty();
    }

    @Override
    public int size() {
      return local.size();
    }

    @Override
    public void clear() {
      local.clear();
    }

    @Override
    public boolean contains(Object o) {
      return local.containsValue(o);
    }

    @Override
    public boolean remove(Object o) {
      requireNonNull(o);
      return super.remove(o);
    }

    @Override
    public Iterator<V> iterator() {
      return new ValuesIterator<K, V>(local);
    }

    @Override
    public Spliterator<V> spliterator() {
      return local.data.values().spliterator();
    }
  }

  /** An adapter to safely externalize the value iterator. */
  static final class ValuesIterator<K, V> implements Iterator<V> {
    final UnboundedLocalCache<K, V> local;
    final Iterator<Entry<K, V>> iterator;
    Entry<K, V> entry;

    ValuesIterator(UnboundedLocalCache<K, V> local) {
      this.local = requireNonNull(local);
      this.iterator = local.data.entrySet().iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      entry = iterator.next();
      return entry.getValue();
    }

    @Override
    public void remove() {
      Caffeine.requireState(entry != null);
      local.remove(entry.getKey());
      entry = null;
    }
  }

  /** An adapter to safely externalize the entries. */
  final class EntrySetView extends AbstractSet<Entry<K, V>> {
    final UnboundedLocalCache<K, V> local;

    EntrySetView(UnboundedLocalCache<K, V> local) {
      this.local = requireNonNull(local);
    }

    @Override
    public boolean isEmpty() {
      return local.isEmpty();
    }

    @Override
    public int size() {
      return local.size();
    }

    @Override
    public void clear() {
      local.clear();
    }

    @Override
    public boolean contains(Object o) {
      if (!(o instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) o;
      V value = local.get(entry.getKey());
      return (value != null) && value.equals(entry.getValue());
    }

    @Override
    public boolean add(Entry<K, V> entry) {
      return (local.putIfAbsent(entry.getKey(), entry.getValue()) == null);
    }

    @Override
    public boolean remove(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      return local.remove(entry.getKey(), entry.getValue());
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator<K, V>(local);
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      return local.data.entrySet().spliterator();
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
    final UnboundedLocalCache<K, V> local;
    final Iterator<Entry<K, V>> iterator;
    Entry<K, V> entry;

    EntryIterator(UnboundedLocalCache<K, V> local) {
      this.local = requireNonNull(local);
      this.iterator = local.data.entrySet().iterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<K, V> next() {
      entry = iterator.next();
      return new WriteThroughEntry<K, V>(local, entry.getKey(), entry.getValue());
    }

    @Override
    public void remove() {
      Caffeine.requireState(entry != null);
      local.remove(entry.getKey());
      entry = null;
    }
  }

  /* ---------------- Manual Cache -------------- */

  static class UnboundedLocalManualCache<K, V>
      implements LocalManualCache<UnboundedLocalCache<K, V>, K, V>, Serializable {
    final UnboundedLocalCache<K, V> cache;
    Policy<K, V> policy;

    UnboundedLocalManualCache(Caffeine<K, V> builder) {
      cache = new UnboundedLocalCache<>(builder, false);
    }

    @Override
    public UnboundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public Policy<K, V> policy() {
      return (policy == null) ? (policy = new UnboundedPolicy<K, V>()) : policy;
    }

    /* ---------------- Serialization Support -------------- */

    static final long serialVersionUID = 1;

    Object writeReplace() {
      return new ManualSerializationProxy<K, V>(this);
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    /**
     * Serializes the configuration of the cache, reconsitituting it as a Cache using
     * {@link Caffeine} upon deserialization. The data held by the cache is not retained.
     */
    static class ManualSerializationProxy<K, V> implements Serializable {
      private static final long serialVersionUID = 1;

      final Ticker ticker;
      final boolean isRecordingStats;
      final RemovalListener<K, V> removalListener;

      ManualSerializationProxy(UnboundedLocalManualCache<K, V> manual) {
        isRecordingStats = manual.cache.isRecordingStats();
        removalListener = manual.cache.removalListener();
        ticker = (manual.cache.ticker() == Caffeine.DISABLED_TICKER)
            ? null
            : manual.cache.ticker();
      }

      Caffeine<Object, Object> recreateCaffeine() {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (ticker != null) {
          builder.ticker(ticker);
        }
        if (removalListener != null) {
          builder.removalListener(removalListener);
        }
        if (isRecordingStats) {
          builder.recordStats();
        }
        return builder;
      }

      Object readResolve() {
        return recreateCaffeine().build();
      }
    }
  }

  static final class UnboundedPolicy<K, V> implements Policy<K, V> {
    @Override public Optional<Eviction<K, V>> eviction() {
      return Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> expireAfterAccess() {
      return Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> expireAfterWrite() {
      return Optional.empty();
    }
  }

  /* ---------------- Loading Cache -------------- */

  static final class UnboundedLocalLoadingCache<K, V> extends UnboundedLocalManualCache<K, V>
      implements LocalLoadingCache<UnboundedLocalCache<K, V>, K, V> {
    final CacheLoader<? super K, V> loader;
    final boolean hasBulkLoader;

    UnboundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder);
      this.loader = loader;
      this.hasBulkLoader = hasLoadAll(loader);
    }

    @Override
    public CacheLoader<? super K, V> loader() {
      return loader;
    }

    @Override
    public boolean hasBulkLoader() {
      return hasBulkLoader;
    }

    /* ---------------- Serialization Support -------------- */

    static final long serialVersionUID = 1;

    @Override
    Object writeReplace() {
      return new LoadingSerializationProxy<K, V>(this);
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    /**
     * Serializes the configuration of the cache, reconsitituting it as a Cache using
     * {@link Caffeine} upon deserialization. The data held by the cache is not retained.
     */
    static final class LoadingSerializationProxy<K, V> extends ManualSerializationProxy<K, V> {
      private static final long serialVersionUID = 1;

      final CacheLoader<? super K, V> loader;

      LoadingSerializationProxy(UnboundedLocalLoadingCache<K, V> loading) {
        super(loading);
        loader = loading.loader;
      }

      @Override
      Object readResolve() {
        return recreateCaffeine().build(loader);
      }
    }
  }

  /* ---------------- Async Loading Cache -------------- */

  static final class UnboundedAsyncLocalLoadingCache<K, V>
      extends AsyncLocalLoadingCache<UnboundedLocalCache<K, CompletableFuture<V>>, K, V>
      implements Serializable {
    transient Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    UnboundedAsyncLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(new UnboundedLocalCache<K, CompletableFuture<V>>((Caffeine<K, CompletableFuture<V>>) builder, true), loader);
    }

    @Override
    protected Policy<K, V> policy() {
      return (policy == null) ? (policy = new UnboundedPolicy<K, V>()) : policy;
    }

    /* ---------------- Serialization Support -------------- */

    static final long serialVersionUID = 1;

    Object writeReplace() {
      return new AsyncLoadingSerializationProxy<K, V>(this);
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    /**
     * Serializes the configuration of the cache, reconsitituting it as a Cache using
     * {@link Caffeine} upon deserialization. The data held by the cache is not retained.
     */
    static final class AsyncLoadingSerializationProxy<K, V> implements Serializable {
      private static final long serialVersionUID = 1;

      final Ticker ticker;
      final boolean isRecordingStats;
      final CacheLoader<? super K, V> loader;
      final RemovalListener<K, CompletableFuture<V>> removalListener;

      AsyncLoadingSerializationProxy(UnboundedAsyncLocalLoadingCache<K, V> async) {
        isRecordingStats = async.cache.isRecordingStats;
        removalListener = async.cache.removalListener;
        ticker = (async.cache.ticker == Caffeine.DISABLED_TICKER)
            ? null
            : async.cache.ticker;
        loader = async.loader;
      }

      Caffeine<Object, Object> recreateCaffeine() {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (ticker != null) {
          builder.ticker(ticker);
        }
        if (removalListener != null) {
          builder.removalListener(removalListener);
        }
        if (isRecordingStats) {
          builder.recordStats();
        }
        return builder;
      }

      Object readResolve() {
        return recreateCaffeine().buildAsync(loader);
      }
    }
  }
}
