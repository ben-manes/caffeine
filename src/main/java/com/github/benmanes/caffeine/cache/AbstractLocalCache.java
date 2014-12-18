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

import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
//TODO(ben): Override default (this & views) interface methods to delegate to optimized versions
abstract class AbstractLocalCache<K, V> implements LocalCache<K, V> {
  final RemovalListener<K, V> removalListener;
  final Executor executor;

  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  protected AbstractLocalCache(Caffeine<? super K, ? super V> builder) {
    this.removalListener = builder.getRemovalListener();
    this.executor = builder.executor;
  }

  protected void notifyRemoval(RemovalCause cause, K key, @Nullable V value) {
    executor.execute(() -> removalListener.onRemoval(new RemovalNotification<K, V>()));
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
  static final class KeySetView<K> implements Set<K> {
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
    public boolean contains(Object o) {
      return false;
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<K>(cache);
    }

    @Override
    public Object[] toArray() {
      return null;
    }

    @Override
    public <T> T[] toArray(T[] a) {
      return null;
    }

    @Override
    public boolean add(K e) {
      return false;
    }

    @Override
    public boolean remove(Object o) {
      return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
      return false;
    }

    @Override
    public boolean addAll(Collection<? extends K> c) {
      return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      return false;
    }

    @Override
    public void clear() {}
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K> implements Iterator<K> {
    final LocalCache<K, ?> cache;
    final Iterator<K> iterator;
    K key;

    KeyIterator(LocalCache<K, ?> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.keySet().iterator();
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
  final class ValuesView implements Collection<V> {
    final LocalCache<K, V> cache;

    ValuesView(LocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public boolean contains(Object o) {
      return false;
    }

    @Override
    public Iterator<V> iterator() {
      return null;
    }

    @Override
    public Object[] toArray() {
      return null;
    }

    @Override
    public <T> T[] toArray(T[] a) {
      return null;
    }

    @Override
    public boolean add(V e) {
      return false;
    }

    @Override
    public boolean remove(Object o) {
      return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
      return false;
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
      return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      return false;
    }

    @Override
    public void clear() {}
  }

  /** An adapter to safely externalize the value iterator. */
  final class ValuesIterator implements Iterator<V> {
    final LocalCache<?, V> cache;
    final Iterator<Entry<K, V>> iterator;
    Entry<K, V> entry;

    ValuesIterator(LocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.entrySet().iterator();
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
  final class EntrySetView implements Set<Entry<K, V>> {
    final LocalCache<K, V> cache;

    EntrySetView(LocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public boolean contains(Object o) {
      return false;
    }

    @Override
    public Iterator<java.util.Map.Entry<K, V>> iterator() {
      return null;
    }

    @Override
    public Object[] toArray() {
      return null;
    }

    @Override
    public <T> T[] toArray(T[] a) {
      return null;
    }

    @Override
    public boolean add(java.util.Map.Entry<K, V> e) {
      return false;
    }

    @Override
    public boolean remove(Object o) {
      return false;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
      return false;
    }

    @Override
    public boolean addAll(Collection<? extends java.util.Map.Entry<K, V>> c) {
      return false;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
      return false;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
      return false;
    }

    @Override
    public void clear() {}
  }

  /** An adapter to safely externalize the entry iterator. */
  final class EntryIterator implements Iterator<Entry<K, V>> {
    final Iterator<Entry<K, V>> iterator;
    final LocalCache<K, V> cache;
    Entry<K, V> entry;


    EntryIterator(LocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
      this.iterator = cache.entrySet().iterator();
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
