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

import org.jctools.queues.MpscArrayQueue;

import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * A ring buffer implemented using the FastFlow technique.
 * <p>
 * This is an adaption of the {@link MpscArrayQueue} where the padding has been stripped off. The
 * lower throughput is offset by the reduced memory overhead.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class FastFlowBuffer implements Buffer {
  final Lock evictionLock;

  final AtomicLong readCache;
  final AtomicLong readCounter;
  final AtomicLong writeCounter;
  final AtomicReference<Object>[] buffer;

  @SuppressWarnings({"unchecked", "rawtypes"})
  FastFlowBuffer() {
    readCache = new AtomicLong();
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
    long tail = writeCounter.get();
    long headCache = readCache.get();
    long wrap = tail - MAX_SIZE;

    if (headCache <= wrap) {
      long head = readCounter.get();
      if (head <= wrap) {
        return true;
      }
      readCache.lazySet(head);
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

    for (;;) {
      int index = (int) (head & MAX_SIZE_MASK);
      if (buffer[index].get() == null) {
        // empty or not published yet
        break;
      }
      buffer[index].lazySet(null);
      head++;
    }
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
