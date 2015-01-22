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
package com.github.benmanes.playground;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * An entry in the cache containing the key, value, weight, access, and write metadata.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface Node<K, V> /* implements AccessOrder<Node<K, V>>, WriteOrder<Node<K, V>> */{

  /** Return the key or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  K getKey();

  /** Return the value or {@code null} if it has been reclaimed by the garbage collector. */
  @Nullable
  V getValue();

  void setValue(@Nonnull V value);

  @Nonnegative
  default int weight() {
    throw new UnsupportedOperationException();
  }

  /* ---------------- Access order -------------- */

  default long getAccessTime() {
    throw new UnsupportedOperationException();
  }

  /** Sets the access time in nanoseconds. */
  default void setAccessTime(@Nonnegative long time) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default Node<K, V> getPreviousInAccessOrder() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default void setPreviousInAccessOrder(Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default Node<K, V> getNextInAccessOrder() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default void setNextInAccessOrder(Node<K, V> next) {
    throw new UnsupportedOperationException();
  }

  /* ---------------- Write order -------------- */

  @Nonnegative
  default long getWriteTime() {
    throw new UnsupportedOperationException();
  }

  /** Sets the write time in nanoseconds. */
  default void setWriteTime(@Nonnegative long time) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default Node<K, V> getPreviousInWriteOrder() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default void setPreviousInWriteOrder(Node<K, V> prev) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default Node<K, V> getNextInWriteOrder() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  default void setNextInWriteOrder(Node<K, V> next) {
    throw new UnsupportedOperationException();
  }
}
