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
  Object getKeyRef();

  /** Return the value or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  V getValue();

  /**
   * Sets the value, which may be held strongly, weakly, or softly. This update may be set lazily
   * and rely on the memory fence when the lock is released.
   */
  @GuardedBy("this")
  void setValue(@Nonnull V value);

  /** Returns the weight of this entry. */
  @Nonnegative
  default int getWeight() {
    throw new UnsupportedOperationException();
  }

  /** Sets the weight. */
  @Nonnegative
  default void setWeight(int weight) {
    throw new UnsupportedOperationException();
  }

  /* ---------------- Access order -------------- */

  /** Returns the time that this entry was last accessed, in ns. */
  default long getAccessTime() {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the access time in nanoseconds. This update may be set lazily and rely on the memory fence
   * when the lock is released.
   */
  @GuardedBy("this")
  default void setAccessTime(@Nonnegative long time) {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getPreviousInAccessOrder() {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default void setPreviousInAccessOrder(Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getNextInAccessOrder() {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default void setNextInAccessOrder(Node<K, V> next) {
    throw new UnsupportedOperationException();
  }

  /* ---------------- Write order -------------- */

  /** Returns the time that this entry was last written, in ns. */
  @Nonnegative
  default long getWriteTime() {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the write time in nanoseconds. This update may be set lazily and rely on the memory fence
   * when the lock is released.
   */
  @GuardedBy("this")
  default void setWriteTime(@Nonnegative long time) {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getPreviousInWriteOrder() {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default void setPreviousInWriteOrder(Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default Node<K, V> getNextInWriteOrder() {
    throw new UnsupportedOperationException();
  }

  @Override
  @GuardedBy("evictionLock")
  default void setNextInWriteOrder(Node<K, V> next) {
    throw new UnsupportedOperationException();
  }
}
