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

import static java.util.Objects.requireNonNull;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Static utility methods and classes pertaining to weak and soft references.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
final class References {

  private References() {}

  /** A weak or soft reference that includes the entry's key reference. */
  interface InternalReference<E> {

    /**
     * Returns this reference object's referent. If this reference object has been cleared, either
     * by the program or by the garbage collector, then this method returns {@code null}.
     *
     * @return The object to which this reference refers, or {@code null} if this reference object
     *         has been cleared
     */
    @Nullable
    E get();

    /**
     * Returns the key that is associated to the cache entry holding this reference. If the cache
     * holds keys strongly, this is that key instance. Otherwise the cache holds keys weakly and the
     * {@link WeakKeyReference} is returned.
     *
     * @return the key that is associated to the cached entry
     */
    Object getKeyReference();

    /**
     * Returns {@code true} if the arguments is an {@linkplain InternalReference} that holds the
     * same element. A weakly or softly held element is compared using identity equality.
     *
     * @param object the reference object with which to compare
     * @return {@code true} if this object is the same as the argument; {@code false} otherwise
     */
    default boolean referenceEquals(@Nullable Object object) {
      if (object == this) {
        return true;
      } else if (object instanceof InternalReference<?>) {
        InternalReference<?> referent = (InternalReference<?>) object;
        return (get() == referent.get());
      }
      return false;
    }
  }

  /**
   * A short-lived adapter used for looking up an entry in the cache where the keys are weakly held.
   * This {@linkplain InternalReference} implementation is not suitable for storing in the cache as
   * the key is strongly held.
   */
  static final class LookupKeyReference<E> implements InternalReference<E> {
    private final int hashCode;
    private final E e;

    public LookupKeyReference(E e) {
      this.hashCode = System.identityHashCode(e);
      this.e = requireNonNull(e);
    }

    @Override
    public E get() {
      return e;
    }

    @Override
    public Object getKeyReference() {
      return this;
    }

    @Override
    public boolean equals(Object object) {
      return referenceEquals(object);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /**
   * The key in a cache that holds keys weakly. This class retains the key's identity hash code in
   * the advent that the key is reclaimed so that the entry can be removed from the cache in
   * constant time.
   */
  static class WeakKeyReference<K> extends WeakReference<K> implements InternalReference<K> {
    private final int hashCode;

    public WeakKeyReference(@Nullable K key, @Nullable ReferenceQueue<K> queue) {
      super(key, queue);
      hashCode = System.identityHashCode(key);
    }

    @Override
    public Object getKeyReference() {
      return this;
    }

    @Override
    public boolean equals(Object object) {
      return referenceEquals(object);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /**
   * The value in a cache that holds values weakly. This class retains a reference to the key in
   * the advent that the value is reclaimed so that the entry can be removed from the cache in
   * constant time.
   */
  static final class WeakValueReference<V> extends WeakReference<V>
      implements InternalReference<V> {
    private final Object keyReference;

    public WeakValueReference(Object keyReference,
        @Nullable V value, @Nullable ReferenceQueue<V> queue) {
      super(value, queue);
      this.keyReference = keyReference;
    }

    @Override
    public Object getKeyReference() {
      return keyReference;
    }

    @Override
    public boolean equals(Object object) {
      return referenceEquals(object);
    }

    @Override
    @SuppressWarnings("PMD.UselessOverridingMethod")
    public int hashCode() {
      return super.hashCode();
    }
  }

  /**
   * The value in a cache that holds values softly. This class retains a reference to the key in
   * the advent that the value is reclaimed so that the entry can be removed from the cache in
   * constant time.
   */
  static final class SoftValueReference<V> extends SoftReference<V>
      implements InternalReference<V> {
    private final Object keyReference;

    public SoftValueReference(Object keyReference,
        @Nullable V value, @Nullable ReferenceQueue<V> queue) {
      super(value, queue);
      this.keyReference = keyReference;
    }

    @Override
    public Object getKeyReference() {
      return keyReference;
    }

    @Override
    public boolean equals(Object object) {
      return referenceEquals(object);
    }

    @Override
    @SuppressWarnings("PMD.UselessOverridingMethod")
    public int hashCode() {
      return super.hashCode();
    }
  }
}
