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
package com.github.benmanes.caffeine.cache.buffer;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jctools.queues.MessagePassingQueue;

import com.google.errorprone.annotations.concurrent.GuardedBy;

/**
 * A skeletal implementation of a read buffer strategy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class ReadBuffer<E> {
  public static final int FULL = 1;     // if the buffer is full
  public static final int FAILED = -1;  // if the CAS failed
  public static final int SUCCESS = 0;  // if added

  public static final int BUFFER_SIZE = 16;
  public static final int BUFFER_MASK = BUFFER_SIZE - 1;

  final Consumer<E> consumer = any -> {};
  final Lock lock = new ReentrantLock();

  /** Returns the number of elements that have been read from the buffer. */
  public abstract long reads();

  /** Returns the number of elements that have been written to the buffer. */
  public abstract long writes();

  /**
   * Inserts the specified element into this buffer if it is possible to do so immediately without
   * violating capacity restrictions.
   */
  public abstract int offer(E e);

  /** Drains the buffer, sending each element to the consumer for processing. */
  @GuardedBy("lock")
  protected abstract void drainTo(Consumer<E> consumer);

  /** Drains the events. */
  public void drain() {
    if (lock.tryLock()) {
      try {
        drainTo(consumer);
      } finally {
        lock.unlock();
      }
    }
  }

  public interface Consumer<E>
      extends java.util.function.Consumer<E>, MessagePassingQueue.Consumer<E> {}
}
