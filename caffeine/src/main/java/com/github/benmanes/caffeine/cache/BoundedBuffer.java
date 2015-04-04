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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A striped, non-blocking, bounded buffer.
 *
 * @param <E> the type of elements maintained by this buffer
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class BoundedBuffer<E> extends StripedBuffer<E> {
  /*
   * A circular ring buffer stores the elements being transfered by the producers to the consumer.
   * The monotonically increasing count of reads and writes allow indexing sequentially to the next
   * element location based upon a power-of-two sizing.
   *
   * The producers race to read the counts, check if there is available capacity, and if so then try
   * once to CAS to the next write count. If the increment is successful then the producer lazily
   * publishes the element. The producer does not retry or block when unsuccessful due to a failed
   * CAS or the buffer being full.
   *
   * The consumer reads the counts and takes the available elements. The clearing of the elements
   * and the next read count are lazily set.
   *
   * This implementation is striped to further increase concurrency by rehashing and dynamically
   * adding new buffers when contention is detected, up to an internal maximum. When rehashing in
   * order to discover an available buffer, the producer may retry adding its element to determine
   * whether it found a satisfactory buffer or if resizing is necessary.
   */

  /** The maximum number of pending reads per buffer. */
  static final int BUFFER_SIZE = 32;

  /** Mask value for indexing into the read buffer. */
  static final int BUFFER_MASK = BUFFER_SIZE - 1;

  @Override
  protected Buffer<E> create(E e) {
    return new RingBuffer<>(e);
  }

  static final class RingBuffer<E> implements Buffer<E> {
    final AtomicLong readCounter;
    final AtomicLong writeCounter;
    final AtomicReference<E>[] buffer;

    @SuppressWarnings({"unchecked", "cast", "rawtypes"})
    public RingBuffer(E e) {
      readCounter = new AtomicLong();
      writeCounter = new AtomicLong(1);
      buffer = new AtomicReference[BUFFER_SIZE];
      for (int i = 0; i < BUFFER_SIZE; i++) {
        buffer[i] = new AtomicReference<>();
      }
      buffer[0].lazySet(e);
    }

    @Override
    public int offer(E e) {
      long head = readCounter.get();
      long tail = writeCounter.get();
      long size = (tail - head);
      if (size >= BUFFER_SIZE) {
        return Buffer.FULL;
      }
      if (writeCounter.compareAndSet(tail, tail + 1)) {
        int index = (int) (tail & BUFFER_MASK);
        buffer[index].lazySet(e);
        return Buffer.SUCCESS;
      }
      return Buffer.FAILED;
    }

    @Override
    public void drainTo(Consumer<E> consumer) {
      long head = readCounter.get();
      long tail = writeCounter.get();
      long size = (tail - head);
      if (size == 0) {
        return;
      }
      do {
        int index = (int) (head & BUFFER_MASK);
        AtomicReference<E> slot = buffer[index];
        E e = slot.get();
        if (e == null) {
          // not published yet
          break;
        }
        slot.lazySet(null);
        consumer.accept(e);
        head++;
      } while (head != tail);
      readCounter.lazySet(head);
    }

    @Override
    public int size() {
      return writes() - reads();
    }

    @Override
    public int reads() {
      return readCounter.intValue();
    }

    @Override
    public int writes() {
      return writeCounter.intValue();
    }
  }
}
