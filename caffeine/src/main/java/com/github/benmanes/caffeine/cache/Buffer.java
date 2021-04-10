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

import java.util.function.Consumer;

/**
 * A multiple-producer / single-consumer buffer that rejects new elements if it is full or
 * fails spuriously due to contention. Unlike a queue and stack, a buffer does not guarantee an
 * ordering of elements in either FIFO or LIFO order.
 * <p>
 * Beware that it is the responsibility of the caller to ensure that a consumer has exclusive read
 * access to the buffer. This implementation does <em>not</em> include fail-fast behavior to guard
 * against incorrect consumer usage.
 *
 * @param <E> the type of elements maintained by this buffer
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface Buffer<E> {
  int FULL = 1;     // if the buffer is full
  int FAILED = -1;  // if the CAS failed
  int SUCCESS = 0;  // if added

  /** Returns a no-op implementation. */
  @SuppressWarnings("unchecked")
  static <E> Buffer<E> disabled() {
    return (Buffer<E>) DisabledBuffer.INSTANCE;
  }

  /**
   * Inserts the specified element into this buffer if it is possible to do so immediately without
   * violating capacity restrictions. The addition is allowed to fail spuriously if multiple
   * threads insert concurrently.
   *
   * @param e the element to add
   * @return {@code Buffer.SUCCESS}, {@code Buffer.FAILED}, or {@code Buffer.FULL}
   */
  int offer(E e);

  /**
   * Drains the buffer, sending each element to the consumer for processing. The caller must ensure
   * that a consumer has exclusive read access to the buffer.
   *
   * @param consumer the action to perform on each element
   */
  void drainTo(Consumer<E> consumer);

  /**
   * Returns the number of elements residing in the buffer.
   *
   * @return the number of elements in this buffer
   */
  default long size() {
    return writes() - reads();
  }

  /**
   * Returns the number of elements that have been read from the buffer.
   *
   * @return the number of elements read from this buffer
   */
  long reads();

  /**
   * Returns the number of elements that have been written to the buffer.
   *
   * @return the number of elements written to this buffer
   */
  long writes();
}

enum DisabledBuffer implements Buffer<Object> {
  INSTANCE;

  @Override public int offer(Object e) { return Buffer.SUCCESS; }
  @Override public void drainTo(Consumer<Object> consumer) {}
  @Override public long size() { return 0; }
  @Override public long reads() { return 0; }
  @Override public long writes() { return 0; }
}
