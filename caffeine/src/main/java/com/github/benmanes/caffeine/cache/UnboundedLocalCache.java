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
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * An in-memory cache that has no capabilities for bounding the map. This implementation provides
 * a lightweight wrapper on top of {@link ConcurrentHashMap}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class UnboundedLocalCache<K, V> implements ConcurrentMap<K, V> {
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

  V getIfPresent(Object key, boolean recordStats) {
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

  public long mappingCount() {
    return data.mappingCount();
  }

  public V get(K key, Function<? super K, ? extends V> mappingFunction) {
    return computeIfAbsent(key, mappingFunction);
  }

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

  public void invalidateAll(Iterable<?> keys) {
    for (Object key : keys) {
      remove(key);
    }
  }

  public void cleanUp() {}

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
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    return computeIfAbsent(key, mappingFunction, false);
  }

  V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
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
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    return compute(key, remappingFunction, false, false);
  }

  V compute(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction,
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
    if (hasRemovalListener() && (oldValue != null)) {
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
      if (oldValue != null) {
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
    if ((hasRemovalListener()) && prev != null) {
      notifyRemoval(key, value, RemovalCause.REPLACED);
    }
    return prev;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    boolean replaced = data.replace(key, oldValue, newValue);
    if (hasRemovalListener() && replaced) {
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
      return new WriteThroughEntry<K, V>(local, entry);
    }

    @Override
    public void remove() {
      Caffeine.requireState(entry != null);
      local.remove(entry.getKey());
      entry = null;
    }
  }

  /** An entry that allows updates to write through to the cache. */
  static final class WriteThroughEntry<K, V> extends SimpleEntry<K, V> {
    static final long serialVersionUID = 1;

    transient final UnboundedLocalCache<K, V> local;

    WriteThroughEntry(UnboundedLocalCache<K, V> local, Entry<K, V> entry) {
      super(entry.getKey(), entry.getValue());
      this.local = requireNonNull(local);
    }

    @Override
    public V setValue(V value) {
      local.put(getKey(), value);
      return super.setValue(value);
    }

    @Override
    public boolean equals(Object o) {
      // suppress Findbugs warning
      return super.equals(o);
    }

    Object writeReplace() {
      return new SimpleEntry<K, V>(this);
    }
  }

  /* ---------------- Manual Cache -------------- */

  static class LocalManualCache<K, V> implements Cache<K, V>, Serializable {
    final UnboundedLocalCache<K, V> cache;
    transient Policy<K, V> policy;

    LocalManualCache(Caffeine<K, V> builder) {
      this.cache = new UnboundedLocalCache<>(builder, false);
    }

    @Override
    public long estimatedSize() {
      return cache.mappingCount();
    }

    @Override
    public void cleanUp() {
      cache.cleanUp();
    }

    @Override
    public @Nullable V getIfPresent(Object key) {
      return cache.getIfPresent(key, true);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return cache.get(key, mappingFunction);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      return cache.getAllPresent(keys);
    }

    @Override
    public void put(K key, V value) {
      cache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      cache.putAll(map);
    }

    @Override
    public void invalidate(Object key) {
      requireNonNull(key);
      cache.remove(key);
    }

    @Override
    public void invalidateAll() {
      cache.invalidateAll();
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      cache.invalidateAll(keys);
    }

    @Override
    public CacheStats stats() {
      return cache.statsCounter.snapshot();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
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

      ManualSerializationProxy(LocalManualCache<K, V> manual) {
        isRecordingStats = manual.cache.isRecordingStats;
        removalListener = manual.cache.removalListener;
        ticker = (manual.cache.ticker == Caffeine.DISABLED_TICKER)
            ? null
            : manual.cache.ticker;
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

  static final class LocalLoadingCache<K, V> extends LocalManualCache<K, V>
      implements LoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    final CacheLoader<? super K, V> loader;
    final boolean hasBulkLoader;

    LocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder);
      this.loader = loader;
      this.hasBulkLoader = hasLoadAll(loader);
    }

    static boolean hasLoadAll(CacheLoader<?, ?> loader) {
      try {
        return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
      } catch (NoSuchMethodException | SecurityException e) {
        logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
        return false;
      }
    }

    @Override
    public V get(K key) {
      return cache.computeIfAbsent(key, loader::load);
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      List<K> keysToLoad = new ArrayList<>();
      Map<K, V> result = new HashMap<>();
      for (K key : keys) {
        V value = cache.data.get(key);
        if (value == null) {
          keysToLoad.add(key);
        } else {
          result.put(key, value);
        }
      }
      cache.statsCounter.recordHits(result.size());
      if (keysToLoad.isEmpty()) {
        return result;
      }
      bulkLoad(keysToLoad, result);
      return Collections.unmodifiableMap(result);
    }

    private void bulkLoad(List<K> keysToLoad, Map<K, V> result) {
      cache.statsCounter.recordMisses(keysToLoad.size());
      if (!hasBulkLoader) {
        for (K key : keysToLoad) {
          V value = cache.compute(key, (k, v) -> loader.load(key), false, false);
          result.put(key, value);
        }
        return;
      }

      boolean success = false;
      long startTime = cache.ticker.read();
      try {
        @SuppressWarnings("unchecked")
        Map<K, V> loaded = (Map<K, V>) loader.loadAll(keysToLoad);
        cache.putAll(loaded);
        for (K key : keysToLoad) {
          V value = loaded.get(key);
          if (value != null) {
            result.put(key, value);
          }
        }
        success = !loaded.isEmpty();
      } finally {
        long loadTime = cache.ticker.read() - startTime;
        if (success) {
          cache.statsCounter.recordLoadSuccess(loadTime);
        } else {
          cache.statsCounter.recordLoadFailure(loadTime);
        }
      }
    }

    @Override
    public void refresh(K key) {
      requireNonNull(key);
      cache.executor.execute(() -> {
        try {
          BiFunction<? super K, ? super V, ? extends V> refreshFunction = (k, oldValue) ->
              (oldValue == null)  ? loader.load(key) : loader.reload(key, oldValue);
          cache.compute(key, refreshFunction, false, false);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during refresh", t);
        }
      });
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

      LoadingSerializationProxy(LocalLoadingCache<K, V> loading) {
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

  static final class LocalAsyncLoadingCache<K, V> implements AsyncLoadingCache<K, V>, Serializable {
    static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    final UnboundedLocalCache<K, CompletableFuture<V>> cache;
    final CacheLoader<K, V> loader;
    final boolean canBulkLoad;

    transient LoadingCacheView localCacheView;
    transient Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    LocalAsyncLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      Caffeine<K, CompletableFuture<V>> futureBuilder = (Caffeine<K, CompletableFuture<V>>) builder;
      this.cache = new UnboundedLocalCache<>(futureBuilder, true);
      this.loader = (CacheLoader<K, V>) loader;
      this.canBulkLoad = canBulkLoad(loader);
    }

    static boolean canBulkLoad(CacheLoader<?, ?> loader) {
      try {
        Method loadAll = loader.getClass().getMethod(
            "loadAll", Iterable.class);
        Method asyncLoadAll = loader.getClass().getMethod(
            "asyncLoadAll", Iterable.class, Executor.class);
        return !loadAll.isDefault() || !asyncLoadAll.isDefault();
      } catch (NoSuchMethodException | SecurityException e) {
        logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
        return false;
      }
    }

    @Override
    public CompletableFuture<V> get(@Nonnull K key,
        @Nonnull Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      return get(key, (k1, executor) -> CompletableFuture.<V>supplyAsync(
          () -> mappingFunction.apply(key), executor));
    }

    @Override
    public CompletableFuture<V> get(K key,
        BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction) {
      long now = cache.ticker.read();
      @SuppressWarnings("unchecked")
      CompletableFuture<V>[] result = new CompletableFuture[1];
      CompletableFuture<V> future = cache.computeIfAbsent(key, k -> {
        return mappingFunction.apply(key, cache.executor).whenComplete((value, error) -> {
          long loadTime = cache.ticker.read() - now;
          if (error == null) {
            cache.statsCounter.recordLoadSuccess(loadTime);
          } else {
            cache.statsCounter.recordLoadFailure(loadTime);
            synchronized (result) {
              if (result[0] != null) {
                cache.remove(key, result[0]);
              }
            }
          }
        });
      }, true);
      synchronized (result) {
        result[0] = future;
      }
      if (future.isCompletedExceptionally()) {
        cache.remove(key, future);
      }
      return future;
    }

    @Override
    public CompletableFuture<V> get(K key) {
      return get(key, (k, executor) -> loader.asyncLoad(key, executor));
    }

    @Override
    public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
      Map<K, CompletableFuture<V>> futures = new HashMap<>();
      List<K> keysToLoad = new ArrayList<>();
      for (K key : keys) {
        CompletableFuture<V> valueFuture = cache.data.get(key);
        if ((valueFuture == null) || valueFuture.isCompletedExceptionally()) {
          keysToLoad.add(key);
        } else {
          futures.put(key, valueFuture);
        }
      }
      cache.statsCounter.recordHits(futures.size());

      if (keysToLoad.isEmpty()) {
        return composeResult(futures);
      }
      cache.statsCounter.recordMisses(keysToLoad.size());

      long now = cache.ticker.read();
      if (canBulkLoad) {
        Map<K, CompletableFuture<V>> proxies = new HashMap<>();
        for (K key : keysToLoad) {
          CompletableFuture<V> proxy = new CompletableFuture<>();
          CompletableFuture<V> future = cache.putIfAbsent(key, proxy);
          if (future == null) {
            future = proxy;
            proxies.put(key, proxy);
          }
          futures.put(key, future);
        }

        loader.asyncLoadAll(keysToLoad, cache.executor).whenComplete((result, error) -> {
          for (Entry<K, CompletableFuture<V>> entry : proxies.entrySet()) {
            if (error == null) {
              entry.getValue().obtrudeValue(result.get(entry.getKey()));
            } else {
              entry.getValue().obtrudeException(error);
            }
          }
          long loadTime = cache.ticker.read() - now;
          if (error == null) {
            cache.statsCounter.recordLoadSuccess(loadTime);
          } else {
            cache.statsCounter.recordLoadFailure(loadTime);
          }
        });

        return composeResult(futures);
      }

      // non-bulk
      for (K key : keysToLoad) {
        futures.put(key, loader.asyncLoad(key, cache.executor).whenComplete((value, error) -> {
          long loadTime = cache.ticker.read();
          if (error == null) {
            cache.statsCounter.recordLoadSuccess(loadTime);
          } else {
            cache.statsCounter.recordLoadFailure(loadTime);
          }
        }));
      }
      return composeResult(futures);
    }

    CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
      if (futures.isEmpty()) {
        return CompletableFuture.completedFuture(Collections.emptyMap());
      }
      CompletableFuture<?>[] array = futures.values().toArray(
          new CompletableFuture[futures.size()]);
      return CompletableFuture.allOf(array).thenApply(ignored -> {
        Map<K, V> result = new HashMap<>(futures.size());
        for (Entry<K, CompletableFuture<V>> entry : futures.entrySet()) {
          V value = entry.getValue().getNow(null);
          if (value == null) {
            cache.remove(entry.getKey(), entry.getValue());
          } else {
            result.put(entry.getKey(), value);
          }
        }
        return Collections.unmodifiableMap(result);
      });
    }

    @Override
    public void put(K key, CompletableFuture<V> valueFuture) {
      if (valueFuture.isCompletedExceptionally()) {
        cache.statsCounter.recordLoadFailure(0L);
        return;
      }
      long now = cache.ticker.read();
      @SuppressWarnings("unchecked")
      CompletableFuture<V>[] future = new CompletableFuture[1];
      CompletableFuture<V> errorHandlingFuture = valueFuture.whenComplete((value, error) -> {
        long loadTime = cache.ticker.read() - now;
        if (error == null) {
          cache.statsCounter.recordLoadSuccess(loadTime);
        } else {
          synchronized (future) {
            if (future[0] != null) {
              cache.statsCounter.recordLoadFailure(loadTime);
              cache.remove(key, future[0]);
            }
          }
        }
      });
      cache.put(key, errorHandlingFuture);
      synchronized (future) {
        future[0] = errorHandlingFuture;
      }
      if (errorHandlingFuture.isCompletedExceptionally()) {
        cache.remove(key, future);
      }
    }

    @Override
    public LoadingCache<K, V> synchronous() {
      return (localCacheView == null) ? (localCacheView = new LoadingCacheView()) : localCacheView;
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

      AsyncLoadingSerializationProxy(LocalAsyncLoadingCache<K, V> async) {
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

    /* ---------------- Synchronous views -------------- */

    final class LoadingCacheView implements LoadingCache<K, V>, Serializable {
      private static final long serialVersionUID = 1L;

      transient AsMapView<K, V> asMapView;

      /** A test-only method for validation. */
      LocalAsyncLoadingCache<K, V> getOuter() {
        return LocalAsyncLoadingCache.this;
      }

      @Override
      public V getIfPresent(Object key) {
        CompletableFuture<V> future = cache.getIfPresent(key, true);
        return (future != null) && future.isDone() && !future.isCompletedExceptionally()
            ? future.getNow(null)
            : null;
      }

      @Override
      public Map<K, V> getAllPresent(Iterable<?> keys) {
        int hits = 0;
        int misses = 0;
        Map<K, V> result = new LinkedHashMap<>();
        for (Object key : keys) {
          CompletableFuture<V> future = cache.data.get(key);
          V value = (future == null) ? null : future.getNow(null);
          if (value == null) {
            misses++;
          } else {
            hits++;
            @SuppressWarnings("unchecked")
            K castKey = (K) key;
            result.put(castKey, value);
          }
        }
        cache.statsCounter.recordHits(hits);
        cache.statsCounter.recordMisses(misses);
        return Collections.unmodifiableMap(result);
      }

      @Override
      public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        requireNonNull(mappingFunction);
        CompletableFuture<V> future = LocalAsyncLoadingCache.this.get(key, (k, executor) ->
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
      public V get(K key) {
        try {
          return LocalAsyncLoadingCache.this.get(key).get();
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
      public Map<K, V> getAll(Iterable<? extends K> keys) {
        try {
          return LocalAsyncLoadingCache.this.getAll(keys).get();
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
        cache.put(key, CompletableFuture.completedFuture(value));
      }

      @Override
      public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
          put(entry.getKey(), entry.getValue());
        }
      }

      @Override
      public void invalidate(Object key) {
        cache.remove(key);
      }

      @Override
      public void invalidateAll(Iterable<?> keys) {
        cache.invalidateAll(keys);
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
        return cache.statsCounter.snapshot();
      }

      @Override
      public void cleanUp() {
        cache.cleanUp();
      }

      @Override
      public void refresh(K key) {
        requireNonNull(key);

        BiFunction<K, CompletableFuture<V>, CompletableFuture<V>> refreshFunction =
            (k, oldValueFuture) -> {
              V oldValue = null;
              try {
                oldValue = (oldValueFuture == null) ? null : oldValueFuture.get();
              } catch (InterruptedException e) {
                throw new CompletionException(e);
              } catch (ExecutionException e) {}
              V newValue = (oldValue == null) ? loader.load(key) : loader.reload(key, oldValue);
              return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
            };
        cache.executor.execute(() -> {
          try {
            cache.compute(key, refreshFunction, false, false);
          } catch (Throwable t) {
            logger.log(Level.WARNING, "Exception thrown during refresh", t);
          }
        });
      }

      @Override
      public Policy<K, V> policy() {
        return (policy == null) ? (policy = new UnboundedPolicy<K, V>()) : policy;
      }

      @Override
      public ConcurrentMap<K, V> asMap() {
        return (asMapView == null) ? (asMapView = new AsMapView<K, V>(cache)) : asMapView;
      }
    }
  }

  static final class AsMapView<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    final UnboundedLocalCache<K, CompletableFuture<V>> delegate;

    transient Collection<V> values;
    transient Set<Entry<K, V>> entries;

    AsMapView(UnboundedLocalCache<K, CompletableFuture<V>> delegate) {
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
    public boolean containsKey(Object key) {
      return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      requireNonNull(value);

      for (CompletableFuture<V> valueFuture : delegate.values()) {
        if (value.equals(getIfReady(valueFuture))) {
          return true;
        }
      }
      return false;
    }

    @Override
    public V get(Object key) {
      return getIfReady(delegate.get(key));
    }

    @Override
    public V putIfAbsent(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> valueFuture =
          delegate.putIfAbsent(key, CompletableFuture.completedFuture(value));
      return getWhenSuccessful(valueFuture);
    }

    @Override
    public V put(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.put(key, CompletableFuture.completedFuture(value));
      return getWhenSuccessful(oldValueFuture);
    }

    @Override
    public V remove(Object key) {
      CompletableFuture<V> oldValueFuture = delegate.remove(key);
      return getWhenSuccessful(oldValueFuture);
    }

    @Override
    public V replace(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.replace(key, CompletableFuture.completedFuture(value));
      return getWhenSuccessful(oldValueFuture);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      requireNonNull(oldValue);
      requireNonNull(newValue);
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      return oldValue.equals(getIfReady(oldValueFuture))
          ? delegate.replace(key, oldValueFuture, CompletableFuture.completedFuture(newValue))
          : false;
    }

    @Override
    public boolean remove(Object key, Object value) {
      requireNonNull(key);
      if (value == null) {
        return false;
      }
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      return value.equals(getIfReady(oldValueFuture))
          ? delegate.remove(key, oldValueFuture)
          : false;
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfAbsent(key, k -> {
        V newValue = mappingFunction.apply(key);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return getWhenSuccessful(valueFuture);
    }

    @Override
    public V computeIfPresent(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfPresent(key, (k, oldValueFuture) -> {
        V oldValue = getWhenSuccessful(oldValueFuture);
        if (oldValue == null) {
          return null;
        }
        V newValue = remappingFunction.apply(key, oldValue);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return getWhenSuccessful(valueFuture);
    }

    @Override
    public V compute(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      long now = delegate.ticker.read();
      CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
        V oldValue = getWhenSuccessful(oldValueFuture);
        V newValue = remappingFunction.apply(key, oldValue);
        long loadTime = delegate.ticker.read()- now;
        if (newValue == null) {
          delegate.statsCounter.recordLoadFailure(loadTime);
          return null;
        }
        delegate.statsCounter.recordLoadSuccess(loadTime);
        return CompletableFuture.completedFuture(newValue);
      }, false, true);
      return getWhenSuccessful(valueFuture);
    }

    @Override
    public V merge(K key, V value,
        BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      requireNonNull(value);
      requireNonNull(remappingFunction);
      CompletableFuture<V> mergedValueFuture = delegate.merge(
          key, CompletableFuture.completedFuture(value), (oldValueFuture, valueFuture) -> {
        V oldValue = getWhenSuccessful(oldValueFuture);
        if (oldValue == null) {
          return valueFuture;
        }
        V newValue = remappingFunction.apply(oldValue, value);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return getWhenSuccessful(mergedValueFuture);
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

    /** Retrieves the current value or null if either not done or failed. */
    static <V> V getIfReady(CompletableFuture<V> future) {
      boolean hasValue = (future != null) && future.isDone() && !future.isCompletedExceptionally();
      return hasValue ? future.getNow(null) : null;
    }

    /** Retrieves the value when done successfully or null if failed. */
    static <V> V getWhenSuccessful(CompletableFuture<V> future) {
      try {
        return (future == null) ? null : future.get();
      } catch (InterruptedException | ExecutionException e) {
        return null;
      }
    }

    final class Values extends AbstractCollection<V> {

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
          Iterator<CompletableFuture<V>> iterator = delegate.values().iterator();
          V cursor;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              CompletableFuture<V> future = iterator.next();
              cursor = getIfReady(future);
            }
            return (cursor != null);
          }

          @Override
          public V next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            V value = cursor;
            cursor = null;
            return value;
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }

    final class EntrySet extends AbstractSet<Entry<K, V>> {

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
      public boolean add(Entry<K, V> entry) {
        return (AsMapView.this.putIfAbsent(entry.getKey(), entry.getValue()) == null);
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
          Entry<K, V> cursor;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              Entry<K, CompletableFuture<V>> entry = iterator.next();
              V value = getIfReady(entry.getValue());
              if (value != null) {
                cursor = new WriteThroughAsMapEntry<>(AsMapView.this, entry.getKey(), value);
              }
            }
            return (cursor != null);
          }

          @Override
          public Entry<K, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            Entry<K, V> entry = cursor;
            cursor = null;
            return entry;
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }

    /** An entry that allows updates to write through to the asMap view. */
    static final class WriteThroughAsMapEntry<K, V> extends SimpleEntry<K, V> {
      static final long serialVersionUID = 1;

      transient final ConcurrentMap<K, V> map;

      WriteThroughAsMapEntry(ConcurrentMap<K, V> map, K key, V value) {
        super(key, value);
        this.map = requireNonNull(map);
      }

      @Override
      public V setValue(V value) {
        map.put(getKey(), value);
        return super.setValue(value);
      }

      @Override
      public boolean equals(Object o) {
        // suppress Findbugs warning
        return super.equals(o);
      }

      Object writeReplace() {
        return new SimpleEntry<K, V>(this);
      }
    }
  }
}
