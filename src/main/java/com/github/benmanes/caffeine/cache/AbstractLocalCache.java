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

import java.util.AbstractCollection;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class AbstractLocalCache<K, V> implements LocalCache<K, V> {
  @Nullable
  protected final RemovalListener<K, V> removalListener;
  protected final Executor executor;

  protected transient Set<K> keySet;
  protected transient Collection<V> values;
  protected transient Set<Entry<K, V>> entrySet;

  protected AbstractLocalCache(Caffeine<? super K, ? super V> builder) {
    this.removalListener = builder.getRemovalListener();
    this.executor = builder.executor;
  }

  protected void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    notifyRemoval(new RemovalNotification<K, V>(key, value, cause));
  }

  protected void notifyRemoval(RemovalNotification<K, V> notification) {
    requireNonNull(removalListener, "Notification should be guarded with a check");
    executor.execute(() -> removalListener.onRemoval(notification));
  }

  protected boolean hasRemovalListener() {
    return (removalListener != null);
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
    final LocalCache<K, ?> cache;

    KeySetView(LocalCache<K, ?> cache) {
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
    public boolean contains(Object o) {
      return cache.containsKey(o);
    }

    @Override
    public boolean remove(Object obj) {
      return (cache.remove(obj) != null);
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<K>(cache);
    }

    @Override
    public Spliterator<K> spliterator() {
      return cache.keySpliterator();
    }
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K> implements Iterator<K> {
    final LocalCache<K, ?> cache;
    final Iterator<K> iterator;
    K key;

    KeyIterator(LocalCache<K, ?> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.keyIterator();
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
      Caffeine.checkState(key != null);
      cache.remove(key);
      key = null;
    }
  }

  /** An adapter to safely externalize the values. */
  final class ValuesView extends AbstractCollection<V> {
    final LocalCache<K, V> cache;

    ValuesView(LocalCache<K, V> cache) {
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
    public boolean contains(Object o) {
      return cache.containsValue(o);
    }

    @Override
    public boolean remove(Object o) {
      requireNonNull(o);
      return super.remove(o);
    }

    @Override
    public Iterator<V> iterator() {
      return new ValuesIterator<K, V>(cache);
    }

    @Override
    public Spliterator<V> spliterator() {
      return cache.valueSpliterator();
    }
  }

  /** An adapter to safely externalize the value iterator. */
  static final class ValuesIterator<K, V> implements Iterator<V> {
    final LocalCache<?, V> cache;
    final Iterator<Entry<K, V>> iterator;
    Entry<K, V> entry;

    ValuesIterator(LocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.entryIterator();
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
      Caffeine.checkState(entry != null);
      cache.remove(entry.getKey());
      entry = null;
    }
  }

  /** An adapter to safely externalize the entries. */
  final class EntrySetView extends AbstractSet<Entry<K, V>> {
    final LocalCache<K, V> cache;

    EntrySetView(LocalCache<K, V> cache) {
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
    public boolean contains(Object o) {
      if (!(o instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) o;
      V value = cache.get(entry.getKey());
      return (value != null) && value.equals(entry.getValue());
    }

    @Override
    public boolean add(Entry<K, V> entry) {
      return (cache.putIfAbsent(entry.getKey(), entry.getValue()) == null);
    }

    @Override
    public boolean remove(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      return cache.remove(entry.getKey(), entry.getValue());
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator<K, V>(cache);
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      return cache.entrySpliterator();
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
    final Iterator<Entry<K, V>> iterator;
    final LocalCache<K, V> cache;
    Entry<K, V> entry;

    EntryIterator(LocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.entryIterator();
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<K, V> next() {
      entry = iterator.next();
      return new WriteThroughEntry<K, V>(cache, entry);
    }

    @Override
    public void remove() {
      Caffeine.checkState(entry != null);
      cache.remove(entry.getKey());
      entry = null;
    }
  }

  /** An entry that allows updates to write through to the cache. */
  static final class WriteThroughEntry<K, V> extends SimpleEntry<K, V> {
    static final long serialVersionUID = 1;

    final LocalCache<K, V> cache;

    WriteThroughEntry(LocalCache<K, V> cache, Entry<K, V> entry) {
      super(entry.getKey(), entry.getValue());
      this.cache = requireNonNull(cache);
    }

    @Override
    public V setValue(V value) {
      cache.put(getKey(), value);
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
