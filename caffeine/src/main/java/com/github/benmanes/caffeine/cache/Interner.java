/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.NullMarked;

import com.github.benmanes.caffeine.cache.References.LookupKeyEqualsReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyEqualsReference;

/**
 * Provides similar behavior to {@link String#intern} for any immutable type.
 * <p>
 * Note that {@code String.intern()} has some well-known performance limitations and should
 * generally be avoided. Prefer {@link Interner#newWeakInterner} or another {@code Interner}
 * implementation even for {@code String} interning.
 *
 * @param <E> the type of elements
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NullMarked
@FunctionalInterface
public interface Interner<E> {

  /**
   * Chooses and returns the representative instance for any collection of instances that are
   * equal to each other. If two {@linkplain Object#equals equal} inputs are given to this method,
   * both calls will return the same instance. That is, {@code intern(a).equals(a)} always holds,
   * and {@code intern(a) == intern(b)} if and only if {@code a.equals(b)}. Note that {@code
   * intern(a)} is permitted to return one instance now and a different instance later if the
   * original interned instance was garbage-collected.
   * <p>
   * <b>Warning:</b> Do not use with mutable objects.
   *
   * @param sample the element to add if absent
   * @return the representative instance, possibly the {@code sample} if absent
   * @throws NullPointerException if {@code sample} is null
   */
  E intern(E sample);

  /**
   * Returns a new thread-safe interner that retains a strong reference to each instance it has
   * interned, thus preventing these instances from being garbage-collected.
   *
   * @param <E> the type of elements
   * @return an interner for retrieving the canonical instance
   */
  static <E> Interner<E> newStrongInterner() {
    return new StrongInterner<>();
  }

  /**
   * Returns a new thread-safe interner that retains a weak reference to each instance it has
   * interned, and so does not prevent these instances from being garbage-collected.
   *
   * @param <E> the type of elements
   * @return an interner for retrieving the canonical instance
   */
  static <E> Interner<E> newWeakInterner() {
    return new WeakInterner<>();
  }
}

final class StrongInterner<E> implements Interner<E> {
  final ConcurrentMap<E, E> map;

  StrongInterner() {
    map = new ConcurrentHashMap<>();
  }
  @Override public E intern(E sample) {
    E canonical = map.get(sample);
    if (canonical != null) {
      return canonical;
    }

    var value = map.putIfAbsent(sample, sample);
    return (value == null) ? sample : value;
  }
}

final class WeakInterner<E> implements Interner<E> {
  final BoundedLocalCache<E, Boolean> cache;

  WeakInterner() {
    cache = Caffeine.newWeakInterner();
  }
  @Override public E intern(E sample) {
    for (;;) {
      E canonical = cache.getKey(sample);
      if (canonical != null) {
        return canonical;
      }

      var value = cache.putIfAbsent(sample, Boolean.TRUE);
      if (value == null) {
        return sample;
      }
    }
  }
}

@SuppressWarnings({"NullAway", "unchecked"})
final class Interned<K, V> extends Node<K, V> implements NodeFactory<K, V> {
  static final NodeFactory<Object, Object> FACTORY = new Interned<>();

  volatile Reference<?> keyReference;

  Interned() {}

  Interned(Reference<K> keyReference) {
    this.keyReference = keyReference;
  }
  @Override public K getKey() {
    return (K) keyReference.get();
  }
  @Override public Object getKeyReference() {
    return keyReference;
  }
  @Override public V getValue() {
    return (V) Boolean.TRUE;
  }
  @Override public V getValueReference() {
    return (V) Boolean.TRUE;
  }
  @Override public void setValue(V value, ReferenceQueue<V> referenceQueue) {}
  @Override public boolean containsValue(Object value) {
    return Objects.equals(value, getValue());
  }
  @Override public Node<K, V> newNode(K key, ReferenceQueue<K> keyReferenceQueue,
      V value, ReferenceQueue<V> valueReferenceQueue, int weight, long now) {
    return new Interned<>(new WeakKeyEqualsReference<>(key, keyReferenceQueue));
  }
  @Override public Node<K, V> newNode(Object keyReference, V value,
      ReferenceQueue<V> valueReferenceQueue, int weight, long now) {
    return new Interned<>((Reference<K>) keyReference);
  }
  @Override public Object newLookupKey(Object key) {
    return new LookupKeyEqualsReference<>(key);
  }
  @Override public Object newReferenceKey(K key, ReferenceQueue<K> referenceQueue) {
    return new WeakKeyEqualsReference<>(key, referenceQueue);
  }
  @Override public boolean isAlive() {
    Object keyRef = keyReference;
    return (keyRef != RETIRED_WEAK_KEY) && (keyRef != DEAD_WEAK_KEY);
  }
  @Override public boolean isRetired() {
    return (keyReference == RETIRED_WEAK_KEY);
  }
  @Override public void retire() {
    var keyRef = keyReference;
    keyReference = RETIRED_WEAK_KEY;
    keyRef.clear();
  }
  @Override public boolean isDead() {
    return (keyReference == DEAD_WEAK_KEY);
  }
  @Override public void die() {
    var keyRef = keyReference;
    keyReference = DEAD_WEAK_KEY;
    keyRef.clear();
  }
}
