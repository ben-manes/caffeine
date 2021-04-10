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
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.ReadBuffer;

/**
 * A bounded buffer that attempts to record once. This design has the benefit of retaining a
 * strict sequence and backing off on contention or when full. It uses a PTL scheme where the empty
 * slot is the next write counter value.
 * <p>
 * The negatives of this algorithm is that it uses a boxed instance of the write index to
 * track if the slot is free. This allows the buffer to be non-blocking, whereas PTL is blocking.
 *
 * https://blogs.oracle.com/dave/entry/ptlqueue_a_scalable_bounded_capacity
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class TicketBuffer<E> extends ReadBuffer<E> {
  final AtomicLong writeCounter;
  final AtomicReference<Object>[] buffer;

  long readCounter;

  @SuppressWarnings({"unchecked", "rawtypes"})
  TicketBuffer() {
    writeCounter = new AtomicLong();
    buffer = new AtomicReference[BUFFER_SIZE];
    for (int i = 0; i < BUFFER_SIZE; i++) {
      buffer[i] = new AtomicReference<>(new Turn(i));
    }
  }

  @Override
  public int offer(E e) {
    final long writeCount = writeCounter.get();

    final int index = (int) (writeCount & BUFFER_MASK);
    AtomicReference<Object> slot = buffer[index];
    Object value = slot.get();
    if (!(value instanceof Turn)) {
      // Either full or lost due to contention - try to drain
      return FULL;
    } else if (((Turn) value).id != writeCount) {
      // Ensures CAS reference equality, race should rarely occur
      return FAILED;
    }

    // Try to record, but we don't care if we win or lose
    if (slot.compareAndSet(value, e)) {
      writeCounter.lazySet(writeCount + 1);
      return SUCCESS;
    }
    return FAILED;
  }

  @Override
  public void drainTo(Consumer<E> consumer) {
    for (int i = 0; i < BUFFER_SIZE; i++) {
      final int index = (int) (readCounter & BUFFER_MASK);
      final AtomicReference<Object> slot = buffer[index];
      if (slot.get() instanceof Turn) {
        break;
      }
      long next = readCounter + BUFFER_SIZE;
      slot.lazySet(new Turn(next));
      readCounter++;
    }
  }

  @Override
  public long reads() {
    return readCounter;
  }

  @Override
  public long writes() {
    return writeCounter.get();
  }

  static final class Turn {
    final long id;

    Turn(long id) {
      this.id = id;
    }

    @Override
    public String toString() {
      return Long.toString(id);
    }
  }
}
