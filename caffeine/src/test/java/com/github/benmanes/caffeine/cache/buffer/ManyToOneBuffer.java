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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import javax.annotation.concurrent.GuardedBy;

import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * A simple ring buffer implementation that watches both the head and tail counts to acquire a
 * slot.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ManyToOneBuffer implements ReadBuffer {
  final Lock evictionLock;
  final AtomicLong readCounter;
  final AtomicLong writeCounter;
  final AtomicReference<Object>[] buffer;

  @SuppressWarnings({"unchecked", "rawtypes"})
  ManyToOneBuffer() {
    readCounter = new AtomicLong();
    writeCounter = new AtomicLong();
    evictionLock = new NonReentrantLock();
    buffer = new AtomicReference[MAX_SIZE];
    for (int i = 0; i < MAX_SIZE; i++) {
      buffer[i] = new AtomicReference<>();
    }
  }

  @Override
  public boolean record() {
    long head = readCounter.get();
    long tail = writeCounter.get();
    long size = (tail - head);
    if (size >= MAX_SIZE) {
      return true;
    }
    if (writeCounter.compareAndSet(tail, tail + 1)) {
      int index = (int) (tail & MAX_SIZE_MASK);
      buffer[index].lazySet(Boolean.TRUE);
    }
    return false;
  }

  @Override
  public void drain() {
    if (evictionLock.tryLock()) {
      drainUnderLock();
      evictionLock.unlock();
    }
  }

  @GuardedBy("evictionLock")
  private void drainUnderLock() {
    long head = readCounter.get();
    long tail = writeCounter.get();
    long size = (tail - head);
    if (size == 0) {
      return;
    }
    do {
      int index = (int) (head & MAX_SIZE_MASK);
      if (buffer[index].get() == null) {
        // not published yet
        break;
      }
      buffer[index].lazySet(null);
      head++;
    } while (head != tail);
    readCounter.lazySet(head);
  }

  @Override
  public long recorded() {
    return writeCounter.get();
  }

  @Override
  public long drained() {
    evictionLock.lock();
    drainUnderLock();
    evictionLock.unlock();
    return readCounter.get();
  }
}
