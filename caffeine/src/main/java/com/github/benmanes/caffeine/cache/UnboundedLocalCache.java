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

import static com.github.benmanes.caffeine.cache.Caffeine.calculateHashMapCapacity;
import static com.github.benmanes.caffeine.cache.LocalLoadingCache.newBulkMappingFunction; // NOPMD
import static com.github.benmanes.caffeine.cache.LocalLoadingCache.newMappingFunction; // NOPMD
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * An in-memory cache that has no capabilities for bounding the map. This implementation provides
 * a lightweight wrapper on top of {@link ConcurrentHashMap}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("serial")
final class UnboundedLocalCache<K, V> implements LocalCache<K, V> {
  static final Logger logger = System.getLogger(UnboundedLocalCache.class.getName());
  static final VarHandle REFRESHES;

  final @Nullable RemovalListener<K, V> removalListener;
  final ConcurrentHashMap<K, V> data;
  final StatsCounter statsCounter;
  final boolean isRecordingStats;
  final Executor executor;
  final boolean isAsync;

  @Nullable Set<K> keySet;
  @Nullable Collection<V> values;
  @Nullable Set<Entry<K, V>> entrySet;
  volatile @Nullable ConcurrentMap<Object, CompletableFuture<?>> refreshes;

  UnboundedLocalCache(Caffeine<? super K, ? super V> builder, boolean isAsync) {
    this.data = new ConcurrentHashMap<>(builder.getInitialCapacity());
    this.statsCounter = builder.getStatsCounterSupplier().get();
    this.removalListener = builder.getRemovalListener(isAsync);
    this.isRecordingStats = builder.isRecordingStats();
    this.executor = builder.getExecutor();
    this.isAsync = isAsync;
  }

  static {
    try {
      REFRESHES = MethodHandles.lookup()
          .findVarHandle(UnboundedLocalCache.class, "refreshes", ConcurrentMap.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public boolean isAsync() {
    return isAsync;
  }

  @Override
  @SuppressWarnings("NullAway")
  public @Nullable Expiry<K, V> expiry() {
    return null;
  }

  @Override
  @CanIgnoreReturnValue
  public Object referenceKey(K key) {
    return key;
  }

  @Override
  public boolean isPendingEviction(K key) {
    return false;
  }

  /* --------------- Cache --------------- */

  @Override
  @SuppressWarnings("SuspiciousMethodCalls")
  public @Nullable V getIfPresent(Object key, boolean recordStats) {
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
  @SuppressWarnings("SuspiciousMethodCalls")
  public @Nullable V getIfPresentQuietly(Object key) {
    return data.get(key);
  }

  @Override
  public long estimatedSize() {
    return data.mappingCount();
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<? extends K> keys) {
    var result = new LinkedHashMap<K, V>(calculateHashMapCapacity(keys));
    for (K key : keys) {
      result.put(key, null);
    }

    int uniqueKeys = result.size();
    for (var iter = result.entrySet().iterator(); iter.hasNext();) {
      Map.Entry<K, V> entry = iter.next();
      V value = data.get(entry.getKey());
      if (value == null) {
        iter.remove();
      } else {
        entry.setValue(value);
      }
    }
    statsCounter.recordHits(result.size());
    statsCounter.recordMisses(uniqueKeys - result.size());

    return Collections.unmodifiableMap(result);
  }

  @Override
  public void cleanUp() {}

  @Override
  public StatsCounter statsCounter() {
    return statsCounter;
  }

  private boolean hasRemovalListener() {
    return (removalListener != null);
  }

  @Override
  @SuppressWarnings("NullAway")
  public void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    if (!hasRemovalListener()) {
      return;
    }
    Runnable task = () -> {
      try {
        removalListener.onRemoval(key, value, cause);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", t);
      }
    };
    try {
      executor.execute(task);
    } catch (Throwable t) {
      logger.log(Level.ERROR, "Exception thrown when submitting removal listener", t);
      task.run();
    }
  }

  @Override
  public boolean isRecordingStats() {
    return isRecordingStats;
  }

  @Override
  public Executor executor() {
    return executor;
  }

  @Override
  @SuppressWarnings("NullAway")
  public ConcurrentMap<Object, CompletableFuture<?>> refreshes() {
    @Var var pending = refreshes;
    if (pending == null) {
      pending = new ConcurrentHashMap<>();
      if (!REFRESHES.compareAndSet(this, null, pending)) {
        pending = refreshes;
      }
    }
    return pending;
  }

  /** Invalidate the in-flight refresh. */
  void discardRefresh(Object keyReference) {
    var pending = refreshes;
    if (pending != null) {
      pending.remove(keyReference);
    }
  }

  @Override
  public Ticker statsTicker() {
    return isRecordingStats ? Ticker.systemTicker() : Ticker.disabledTicker();
  }

  /* --------------- JDK8+ Map extensions --------------- */

  @Override
  public void forEach(BiConsumer<? super K, ? super V> action) {
    data.forEach(action);
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    requireNonNull(function);

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"rawtypes", "unchecked", "Varifier"})
    @Nullable K[] notificationKey = (K[]) new Object[1];
    @SuppressWarnings({"rawtypes", "unchecked", "Varifier"})
    @Nullable V[] notificationValue = (V[]) new Object[1];
    data.replaceAll((key, value) -> {
      if (notificationKey[0] != null) {
        notifyRemoval(notificationKey[0], notificationValue[0], RemovalCause.REPLACED);
        notificationValue[0] = null;
        notificationKey[0] = null;
      }

      V newValue = requireNonNull(function.apply(key, value));
      if (newValue != value) {
        notificationKey[0] = key;
        notificationValue[0] = value;
      }

      return newValue;
    });
    if (notificationKey[0] != null) {
      notifyRemoval(notificationKey[0], notificationValue[0], RemovalCause.REPLACED);
    }
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean recordStats, boolean recordLoad) {
    requireNonNull(mappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    @Var V value = data.get(key);
    if (value != null) {
      if (recordStats) {
        statsCounter.recordHits(1);
      }
      return value;
    }

    boolean[] missed = new boolean[1];
    value = data.computeIfAbsent(key, k -> {
      // Do not communicate to CacheWriter on a load
      missed[0] = true;
      return recordStats
          ? statsAware(mappingFunction, recordLoad).apply(key)
          : mappingFunction.apply(key);
    });
    if (!missed[0] && recordStats) {
      statsCounter.recordHits(1);
    }
    return value;
  }

  @Override
  public @Nullable V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    if (!data.containsKey(key)) {
      return null;
    }

    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"rawtypes", "unchecked"})
    var oldValue = (V[]) new Object[1];
    boolean[] replaced = new boolean[1];
    V nv = data.computeIfPresent(key, (K k, V value) -> {
      BiFunction<? super K, ? super V, ? extends V> function = statsAware(remappingFunction,
          /* recordLoad= */ true, /* recordLoadFailure= */ true);
      V newValue = function.apply(k, value);

      replaced[0] = (newValue != null);
      if (newValue != value) {
        oldValue[0] = value;
      }

      discardRefresh(k);
      return newValue;
    });
    if (replaced[0]) {
      notifyOnReplace(key, oldValue[0], nv);
    } else if (oldValue[0] != null) {
      notifyRemoval(key, oldValue[0], RemovalCause.EXPLICIT);
    }
    return nv;
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      @Nullable Expiry<? super K, ? super V> expiry, boolean recordLoad,
      boolean recordLoadFailure) {
    requireNonNull(remappingFunction);
    return remap(key, statsAware(remappingFunction, recordLoad, recordLoadFailure));
  }

  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);
    requireNonNull(value);

    return remap(key, (k, oldValue) ->
      (oldValue == null) ? value : statsAware(remappingFunction).apply(oldValue, value));
  }

  /**
   * A {@link Map#compute(Object, BiFunction)} that does not directly record any cache statistics.
   *
   * @param key key with which the specified value is to be associated
   * @param remappingFunction the function to compute a value
   * @return the new value associated with the specified key, or null if none
   */
  V remap(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    // ensures that the removal notification is processed after the removal has completed
    @SuppressWarnings({"rawtypes", "unchecked"})
    var oldValue = (V[]) new Object[1];
    boolean[] replaced = new boolean[1];
    V nv = data.compute(key, (K k, V value) -> {
      V newValue = remappingFunction.apply(k, value);
      if ((value == null) && (newValue == null)) {
        return null;
      }

      replaced[0] = (newValue != null);
      if (newValue != value) {
        oldValue[0] = value;
      }

      discardRefresh(k);
      return newValue;
    });
    if (replaced[0]) {
      notifyOnReplace(key, oldValue[0], nv);
    } else if (oldValue[0] != null) {
      notifyRemoval(key, oldValue[0], RemovalCause.EXPLICIT);
    }
    return nv;
  }

  /* --------------- Concurrent Map --------------- */

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public void clear() {
    if (!hasRemovalListener() && ((refreshes == null) || refreshes.isEmpty())) {
      data.clear();
      return;
    }
    for (K key : List.copyOf(data.keySet())) {
      remove(key);
    }
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
  public @Nullable V get(Object key) {
    return getIfPresent(key, /* recordStats= */ false);
  }

  @Override
  public @Nullable V put(K key, V value) {
    V oldValue = data.put(key, value);
    notifyOnReplace(key, oldValue, value);
    return oldValue;
  }

  @Override
  public @Nullable V putIfAbsent(K key, V value) {
    return data.putIfAbsent(key, value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    if (hasRemovalListener()) {
      map.forEach(this::put);
    } else {
      data.putAll(map);
    }
  }

  @Override
  public @Nullable V remove(Object key) {
    @SuppressWarnings("unchecked")
    var castKey = (K) key;
    @SuppressWarnings({"rawtypes", "unchecked"})
    var oldValue = (V[]) new Object[1];
    data.computeIfPresent(castKey, (k, v) -> {
      discardRefresh(k);
      oldValue[0] = v;
      return null;
    });

    if (oldValue[0] != null) {
      notifyRemoval(castKey, oldValue[0], RemovalCause.EXPLICIT);
    }

    return oldValue[0];
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (value == null) {
      requireNonNull(key);
      return false;
    }

    @SuppressWarnings("unchecked")
    var castKey = (K) key;
    @SuppressWarnings({"rawtypes", "unchecked"})
    var oldValue = (V[]) new Object[1];

    data.computeIfPresent(castKey, (k, v) -> {
      if (v.equals(value)) {
        discardRefresh(k);
        oldValue[0] = v;
        return null;
      }
      return v;
    });

    if (oldValue[0] != null) {
      notifyRemoval(castKey, oldValue[0], RemovalCause.EXPLICIT);
      return true;
    }
    return false;
  }

  @Override
  public @Nullable V replace(K key, V value) {
    requireNonNull(value);

    @SuppressWarnings({"rawtypes", "unchecked"})
    var oldValue = (V[]) new Object[1];
    data.computeIfPresent(key, (k, v) -> {
      discardRefresh(k);
      oldValue[0] = v;
      return value;
    });

    if ((oldValue[0] != null) && (oldValue[0] != value)) {
      notifyRemoval(key, oldValue[0], RemovalCause.REPLACED);
    }
    return oldValue[0];
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    return replace(key, oldValue, newValue, /* shouldDiscardRefresh= */ true);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue, boolean shouldDiscardRefresh) {
    requireNonNull(oldValue);
    requireNonNull(newValue);

    @SuppressWarnings({"rawtypes", "unchecked"})
    var prev = (V[]) new Object[1];
    data.computeIfPresent(key, (k, v) -> {
      if (v.equals(oldValue)) {
        if (shouldDiscardRefresh) {
          discardRefresh(k);
        }
        prev[0] = v;
        return newValue;
      }
      return v;
    });

    boolean replaced = (prev[0] != null);
    if (replaced && (prev[0] != newValue)) {
      notifyRemoval(key, prev[0], RemovalCause.REPLACED);
    }
    return replaced;
  }

  @Override
  @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
  public boolean equals(@Nullable Object o) {
    return (o == this) || data.equals(o);
  }

  @Override
  public int hashCode() {
    return data.hashCode();
  }

  @Override
  public String toString() {
    var result = new StringBuilder(50).append('{');
    data.forEach((key, value) -> {
      if (result.length() != 1) {
        result.append(", ");
      }
      result.append((key == this) ? "(this Map)" : key)
          .append('=')
          .append((value == this) ? "(this Map)" : value);
    });
    return result.append('}').toString();
  }

  @Override
  public Set<K> keySet() {
    Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySetView<>(this)) : ks;
  }

  @Override
  public Collection<V> values() {
    Collection<V> vs = values;
    return (vs == null) ? (values = new ValuesView<>(this)) : vs;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    Set<Entry<K, V>> es = entrySet;
    return (es == null) ? (entrySet = new EntrySetView<>(this)) : es;
  }

  /** An adapter to safely externalize the keys. */
  static final class KeySetView<K> extends AbstractSet<K> {
    final UnboundedLocalCache<K, ?> cache;

    KeySetView(UnboundedLocalCache<K, ?> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public boolean isEmpty() {
      return cache.isEmpty();
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean contains(Object o) {
      return cache.containsKey(o);
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
      requireNonNull(collection);
      @Var boolean modified = false;
      if ((collection instanceof Set<?>) && (collection.size() > size())) {
        for (K key : this) {
          if (collection.contains(key)) {
            modified |= remove(key);
          }
        }
      } else {
        for (var o : collection) {
          modified |= (o != null) && remove(o);
        }
      }
      return modified;
    }

    @Override
    public boolean remove(Object o) {
      return (cache.remove(o) != null);
    }

    @Override
    public boolean removeIf(Predicate<? super K> filter) {
      requireNonNull(filter);
      @Var boolean modified = false;
      for (K key : this) {
        if (filter.test(key) && remove(key)) {
          modified = true;
        }
      }
      return modified;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
      requireNonNull(collection);
      @Var boolean modified = false;
      for (K key : this) {
        if (!collection.contains(key) && remove(key)) {
          modified = true;
        }
      }
      return modified;
    }

    @Override
    public void forEach(Consumer<? super K> action) {
      cache.data.keySet().forEach(action);
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<>(cache);
    }

    @Override
    public Spliterator<K> spliterator() {
      return cache.data.keySet().spliterator();
    }

    @Override
    public Object[] toArray() {
      return cache.data.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      return cache.data.keySet().toArray(array);
    }
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K> implements Iterator<K> {
    final UnboundedLocalCache<K, ?> cache;
    final Iterator<K> iterator;
    @Nullable K current;

    KeyIterator(UnboundedLocalCache<K, ?> cache) {
      this.iterator = cache.data.keySet().iterator();
      this.cache = cache;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      current = iterator.next();
      return current;
    }

    @Override
    public void remove() {
      if (current == null) {
        throw new IllegalStateException();
      }
      cache.remove(current);
      current = null;
    }
  }

  /** An adapter to safely externalize the values. */
  static final class ValuesView<K, V> extends AbstractCollection<V> {
    final UnboundedLocalCache<K, V> cache;

    ValuesView(UnboundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public boolean isEmpty() {
      return cache.isEmpty();
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean contains(Object o) {
      return cache.containsValue(o);
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
      requireNonNull(collection);
      @Var boolean modified = false;
      for (var entry : cache.data.entrySet()) {
        if (collection.contains(entry.getValue())
            && cache.remove(entry.getKey(), entry.getValue())) {
          modified = true;
        }
      }
      return modified;
    }

    @Override
    public boolean remove(Object o) {
      if (o == null) {
        return false;
      }
      for (var entry : cache.data.entrySet()) {
        if (o.equals(entry.getValue()) && cache.remove(entry.getKey(), entry.getValue())) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean removeIf(Predicate<? super V> filter) {
      requireNonNull(filter);
      @Var boolean removed = false;
      for (var entry : cache.data.entrySet()) {
        if (filter.test(entry.getValue())) {
          removed |= cache.remove(entry.getKey(), entry.getValue());
        }
      }
      return removed;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
      requireNonNull(collection);
      @Var boolean modified = false;
      for (var entry : cache.data.entrySet()) {
        if (!collection.contains(entry.getValue())
            && cache.remove(entry.getKey(), entry.getValue())) {
          modified = true;
        }
      }
      return modified;
    }

    @Override
    public void forEach(Consumer<? super V> action) {
      cache.data.values().forEach(action);
    }

    @Override
    public Iterator<V> iterator() {
      return new ValuesIterator<>(cache);
    }

    @Override
    public Spliterator<V> spliterator() {
      return cache.data.values().spliterator();
    }

    @Override
    public Object[] toArray() {
      return cache.data.values().toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      return cache.data.values().toArray(array);
    }
  }

  /** An adapter to safely externalize the value iterator. */
  static final class ValuesIterator<K, V> implements Iterator<V> {
    final UnboundedLocalCache<K, V> cache;
    final Iterator<Entry<K, V>> iterator;
    @Nullable Entry<K, V> entry;

    ValuesIterator(UnboundedLocalCache<K, V> cache) {
      this.iterator = cache.data.entrySet().iterator();
      this.cache = cache;
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
      if (entry == null) {
        throw new IllegalStateException();
      }
      cache.remove(entry.getKey());
      entry = null;
    }
  }

  /** An adapter to safely externalize the entries. */
  static final class EntrySetView<K, V> extends AbstractSet<Entry<K, V>> {
    final UnboundedLocalCache<K, V> cache;

    EntrySetView(UnboundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public boolean isEmpty() {
      return cache.isEmpty();
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean contains(Object o) {
      if (!(o instanceof Entry<?, ?>)) {
        return false;
      }
      var entry = (Entry<?, ?>) o;
      var key = entry.getKey();
      var value = entry.getValue();
      if ((key == null) || (value == null)) {
        return false;
      }
      V cachedValue = cache.get(key);
      return (cachedValue != null) && cachedValue.equals(value);
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
      requireNonNull(collection);
      @Var boolean modified = false;
      if ((collection instanceof Set<?>) && (collection.size() > size())) {
        for (var entry : this) {
          if (collection.contains(entry)) {
            modified |= remove(entry);
          }
        }
      } else {
        for (var o : collection) {
          modified |= remove(o);
        }
      }
      return modified;
    }

    @Override
    @SuppressWarnings("SuspiciousMethodCalls")
    public boolean remove(Object o) {
      if (!(o instanceof Entry<?, ?>)) {
        return false;
      }
      var entry = (Entry<?, ?>) o;
      var key = entry.getKey();
      return (key != null) && cache.remove(key, entry.getValue());
    }

    @Override
    public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
      requireNonNull(filter);
      @Var boolean removed = false;
      for (var entry : cache.data.entrySet()) {
        if (filter.test(entry)) {
          removed |= cache.remove(entry.getKey(), entry.getValue());
        }
      }
      return removed;
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
      requireNonNull(collection);
      @Var boolean modified = false;
      for (var entry : this) {
        if (!collection.contains(entry) && remove(entry)) {
          modified = true;
        }
      }
      return modified;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator<>(cache);
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      return new EntrySpliterator<>(cache);
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
    final UnboundedLocalCache<K, V> cache;
    final Iterator<Entry<K, V>> iterator;
    @Nullable Entry<K, V> entry;

    EntryIterator(UnboundedLocalCache<K, V> cache) {
      this.iterator = cache.data.entrySet().iterator();
      this.cache = cache;
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<K, V> next() {
      entry = iterator.next();
      return new WriteThroughEntry<>(cache, entry.getKey(), entry.getValue());
    }

    @Override
    public void remove() {
      if (entry == null) {
        throw new IllegalStateException();
      }
      cache.remove(entry.getKey());
      entry = null;
    }
  }

  /** An adapter to safely externalize the entry spliterator. */
  static final class EntrySpliterator<K, V> implements Spliterator<Entry<K, V>> {
    final Spliterator<Entry<K, V>> spliterator;
    final UnboundedLocalCache<K, V> cache;

    EntrySpliterator(UnboundedLocalCache<K, V> cache) {
      this(cache, cache.data.entrySet().spliterator());
    }

    EntrySpliterator(UnboundedLocalCache<K, V> cache, Spliterator<Entry<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
      requireNonNull(action);
      spliterator.forEachRemaining(entry -> {
        var e = new WriteThroughEntry<>(cache, entry.getKey(), entry.getValue());
        action.accept(e);
      });
    }

    @Override
    public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
      requireNonNull(action);
      return spliterator.tryAdvance(entry -> {
        var e = new WriteThroughEntry<>(cache, entry.getKey(), entry.getValue());
        action.accept(e);
      });
    }

    @Override
    public @Nullable EntrySpliterator<K, V> trySplit() {
      Spliterator<Entry<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new EntrySpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return spliterator.characteristics();
    }
  }

  /* --------------- Manual Cache --------------- */

  static class UnboundedLocalManualCache<K, V> implements LocalManualCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final UnboundedLocalCache<K, V> cache;
    @Nullable Policy<K, V> policy;

    UnboundedLocalManualCache(Caffeine<K, V> builder) {
      cache = new UnboundedLocalCache<>(builder, /* isAsync= */ false);
    }

    @Override
    public final UnboundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public final Policy<K, V> policy() {
      if (policy == null) {
        @SuppressWarnings("NullAway")
        Function<@Nullable V, @Nullable V> identity = identity();
        policy = new UnboundedPolicy<>(cache, identity);
      }
      return policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      var proxy = new SerializationProxy<K, V>();
      proxy.isRecordingStats = cache.isRecordingStats;
      proxy.removalListener = cache.removalListener;
      return proxy;
    }
  }

  /** An eviction policy that supports no bounding. */
  static final class UnboundedPolicy<K, V> implements Policy<K, V> {
    final Function<@Nullable V, @Nullable V> transformer;
    final UnboundedLocalCache<K, V> cache;

    UnboundedPolicy(UnboundedLocalCache<K, V> cache,
        Function<@Nullable V, @Nullable V> transformer) {
      this.transformer = transformer;
      this.cache = cache;
    }
    @Override public boolean isRecordingStats() {
      return cache.isRecordingStats;
    }
    @Override public @Nullable V getIfPresentQuietly(K key) {
      return transformer.apply(cache.data.get(key));
    }
    @Override public @Nullable CacheEntry<K, V> getEntryIfPresentQuietly(K key) {
      V value = transformer.apply(cache.data.get(key));
      return (value == null) ? null : SnapshotEntry.forEntry(key, value);
    }
    @SuppressWarnings("Java9CollectionFactory")
    @Override public Map<K, CompletableFuture<V>> refreshes() {
      var refreshes = cache.refreshes;
      if ((refreshes == null) || refreshes.isEmpty()) {
        @SuppressWarnings("ImmutableMapOf")
        Map<K, CompletableFuture<V>> emptyMap = Collections.unmodifiableMap(Collections.emptyMap());
        return emptyMap;
      }
      @SuppressWarnings("unchecked")
      var castedRefreshes = (Map<K, CompletableFuture<V>>) (Object) refreshes;
      return Collections.unmodifiableMap(new HashMap<>(castedRefreshes));
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

  /* --------------- Loading Cache --------------- */

  static final class UnboundedLocalLoadingCache<K, V> extends UnboundedLocalManualCache<K, V>
      implements LocalLoadingCache<K, V> {
    private static final long serialVersionUID = 1;

    final Function<K, V> mappingFunction;
    final CacheLoader<? super K, V> cacheLoader;
    final @Nullable Function<Set<? extends K>, Map<K, V>> bulkMappingFunction;

    UnboundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> cacheLoader) {
      super(builder);
      this.cacheLoader = cacheLoader;
      this.mappingFunction = newMappingFunction(cacheLoader);
      this.bulkMappingFunction = newBulkMappingFunction(cacheLoader);
    }

    @Override
    public AsyncCacheLoader<? super K, V> cacheLoader() {
      return cacheLoader;
    }

    @Override
    public Function<K, V> mappingFunction() {
      return mappingFunction;
    }

    @Override
    public @Nullable Function<Set<? extends K>, Map<K, V>>  bulkMappingFunction() {
      return bulkMappingFunction;
    }

    @Override
    Object writeReplace() {
      @SuppressWarnings("unchecked")
      var proxy = (SerializationProxy<K, V>) super.writeReplace();
      proxy.cacheLoader = cacheLoader;
      return proxy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }
  }

  /* --------------- Async Cache --------------- */

  static final class UnboundedLocalAsyncCache<K, V> implements LocalAsyncCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final UnboundedLocalCache<K, CompletableFuture<V>> cache;

    @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
    @Nullable CacheView<K, V> cacheView;
    @Nullable Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    UnboundedLocalAsyncCache(Caffeine<K, V> builder) {
      cache = new UnboundedLocalCache<>(
          (Caffeine<K, CompletableFuture<V>>) builder, /* isAsync= */ true);
    }

    @Override
    public UnboundedLocalCache<K, CompletableFuture<V>> cache() {
      return cache;
    }

    @Override
    public ConcurrentMap<K, CompletableFuture<V>> asMap() {
      return (mapView == null) ? (mapView = new AsyncAsMapView<>(this)) : mapView;
    }

    @Override
    public Cache<K, V> synchronous() {
      return (cacheView == null) ? (cacheView = new CacheView<>(this)) : cacheView;
    }

    @Override
    public Policy<K, V> policy() {
      @SuppressWarnings("unchecked")
      var castCache = (UnboundedLocalCache<K, V>) cache;
      Function<CompletableFuture<V>, @Nullable V> transformer = Async::getIfReady;
      @SuppressWarnings({"NullAway", "unchecked", "Varifier"})
      Function<@Nullable V, @Nullable V> castTransformer = (Function<V, V>) transformer;
      return (policy == null)
          ? (policy = new UnboundedPolicy<>(castCache, castTransformer))
          : policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      var proxy = new SerializationProxy<K, V>();
      proxy.isRecordingStats = cache.isRecordingStats;
      proxy.removalListener = cache.removalListener;
      proxy.async = true;
      return proxy;
    }
  }

  /* --------------- Async Loading Cache --------------- */

  static final class UnboundedLocalAsyncLoadingCache<K, V>
      extends LocalAsyncLoadingCache<K, V> implements Serializable {
    private static final long serialVersionUID = 1;

    final UnboundedLocalCache<K, CompletableFuture<V>> cache;

    @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
    @Nullable Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    UnboundedLocalAsyncLoadingCache(Caffeine<K, V> builder, AsyncCacheLoader<? super K, V> loader) {
      super(loader);
      cache = new UnboundedLocalCache<>(
          (Caffeine<K, CompletableFuture<V>>) builder, /* isAsync= */ true);
    }

    @Override
    public LocalCache<K, CompletableFuture<V>> cache() {
      return cache;
    }

    @Override
    public ConcurrentMap<K, CompletableFuture<V>> asMap() {
      return (mapView == null) ? (mapView = new AsyncAsMapView<>(this)) : mapView;
    }

    @Override
    public Policy<K, V> policy() {
      @SuppressWarnings("unchecked")
      var castCache = (UnboundedLocalCache<K, V>) cache;
      Function<CompletableFuture<V>, @Nullable V> transformer = Async::getIfReady;
      @SuppressWarnings({"NullAway", "unchecked", "Varifier"})
      Function<@Nullable V, @Nullable V> castTransformer = (Function<V, V>) transformer;
      return (policy == null)
          ? (policy = new UnboundedPolicy<>(castCache, castTransformer))
          : policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      var proxy = new SerializationProxy<K, V>();
      proxy.isRecordingStats = cache.isRecordingStats();
      proxy.removalListener = cache.removalListener;
      proxy.cacheLoader = cacheLoader;
      proxy.async = true;
      return proxy;
    }
  }
}
