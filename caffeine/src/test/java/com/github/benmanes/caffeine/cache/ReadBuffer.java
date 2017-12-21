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

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A skeletal implementation of a read buffer strategy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class ReadBuffer<E> implements Buffer<E> {
  public static final int BUFFER_SIZE = 32;
  public static final int BUFFER_MASK = BUFFER_SIZE - 1;

  final Consumer<E> consumer = any -> {};
  final Lock evictionLock = new ReentrantLock();

  /**
   * Attempts to record an event.
   *
   * @return if a drain is needed
   */
  public boolean record(E e) {
    return offer(e) == Buffer.FULL;
  }

  /** Drains the events. */
  public void drain() {
    if (evictionLock.tryLock()) {
      try {
        drainTo(consumer);
      } finally {
        evictionLock.unlock();
      }
    }
  }

  /** Returns the total number of events recorded. */
  public int recorded() {
    return writes();
  }

  /** Returns the total number of events consumed. */
  public int drained() {
    drain();
    return reads();
  }
}
