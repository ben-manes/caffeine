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

import java.lang.ref.ReferenceQueue;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * An entry in the cache containing the key, value, weight, access, and write metadata. The key
 * or value may be held weakly or softly requiring identity comparison.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface Node<K, V> extends AccessOrder<Node<K, V>>, WriteOrder<Node<K, V>> {

  /** Return the key or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  K getKey();

  /**
   * Returns the reference that the cache is holding the entry by. This is either the key if
   * strongly held or a {@link java.lang.ref.WeakReference} to that key.
   */
  @Nonnull
  Object getKeyReference();

  /** Return the value or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  V getValue();

  /**
   * Sets the value, which may be held strongly, weakly, or softly. This update may be set lazily
   * and rely on the memory fence when the lock is released.
   */
  @GuardedBy("this")
  void setValue(@Nonnull V value, @Nullable ReferenceQueue<V> referenceQueue);

  /**
   * Returns {@code true} if the given objects are considered equivalent. A strongly held value is
   * compared by equality and a weakly or softly held value is compared by identity.
   */
  boolean containsValue(@Nonnull Object value);

  /** If the entry is available in the hash-table and page replacement policy. */
  default boolean isAlive() {
    return getWeight() >= 0;
  }

  /**
   * If the entry was removed from the hash-table and is awaiting removal from the page
   * replacement policy.
   */
  default boolean isRetired() {
    return !isDead() && (getWeight() < 0);
  }

  /** If the entry was removed from the hash-table and the page replacement policy. */
  default boolean isDead() {
    return (getWeight() == Integer.MIN_VALUE);
  }

  /**
   * Atomically transitions the node from the <tt>alive</tt> state to the <tt>retired</tt> state, if
   * a valid transition.
   *
   * @return the retired weighted value if the transition was successful or null otherwise
   */
  default @Nullable V makeRetired() {
    synchronized (this) {
      if (!isAlive()) {
        return null;
      }
      setWeight(-getWeight());
      return getValue();
    }
  }

  /* ---------------- Access order -------------- */

  /** Returns the weight of this entry. */
  @Nonnegative
  @GuardedBy("this")
  default int getWeight() {
    return 1;
  }

  /** Sets the weight. */
  @Nonnegative
  @GuardedBy("this")
  default void setWeight(int weight) {}

  /* ---------------- Access order -------------- */

  /** Returns the time that this entry was last accessed, in ns. */
  default long getAccessTime() {
    return 0L;
  }

  /**
   * Sets the access time in nanoseconds. This update may be set lazily and rely on the memory fence
   * when the lock is released.
   */
  @GuardedBy("this")
  default void setAccessTime(@Nonnegative long time) {}

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getPreviousInAccessOrder() {
    return null;
  }

  @Override
  @GuardedBy("evictionLock")
  default void setPreviousInAccessOrder(Node<K, V> prev) {}

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getNextInAccessOrder() {
    return null;
  }

  @Override
  @GuardedBy("evictionLock")
  default void setNextInAccessOrder(Node<K, V> next) {}

  /* ---------------- Write order -------------- */

  /** Returns the time that this entry was last written, in ns. */
  @Nonnegative
  default long getWriteTime() {
    return 0L;
  }

  /**
   * Sets the write time in nanoseconds. This update may be set lazily and rely on the memory fence
   * when the lock is released.
   */
  @GuardedBy("this")
  default void setWriteTime(@Nonnegative long time) {}

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getPreviousInWriteOrder() {
    return null;
  }

  @Override
  @GuardedBy("evictionLock")
  default void setPreviousInWriteOrder(Node<K, V> prev) {}

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getNextInWriteOrder() {
    return null;
  }

  @Override
  @GuardedBy("evictionLock")
  default void setNextInWriteOrder(Node<K, V> next) {}
}
