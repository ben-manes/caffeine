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

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.jctools.util.UnsafeAccess;

import com.github.benmanes.caffeine.cache.ReadBuffer;

/**
 * A simple ring buffer implementation that watches both the head and tail counts to acquire a
 * slot.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ManyToOneBuffer<E> extends ManyToOneHeader.ReadAndWriteCounterRef<E> {
  final AtomicReference<E>[] buffer;

  @SuppressWarnings({"unchecked", "rawtypes"})
  ManyToOneBuffer() {
    buffer = new AtomicReference[BUFFER_SIZE];
    for (int i = 0; i < BUFFER_SIZE; i++) {
      buffer[i] = new AtomicReference<>();
    }
  }

  @Override
  public int offer(E e) {
    long head = readCounter;
    long tail = relaxedWriteCounter();
    long size = (tail - head);
    if (size >= BUFFER_SIZE) {
      return FULL;
    }
    if (casWriteCounter(tail, tail + 1)) {
      int index = (int) (tail & BUFFER_MASK);
      buffer[index].lazySet(e);
      return SUCCESS;
    }
    return FAILED;
  }

  @Override
  public void drainTo(Consumer<E> consumer) {
    long head = readCounter;
    long tail = relaxedWriteCounter();
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
    lazySetReadCounter(head);
  }

  @Override
  public int reads() {
    return (int) readCounter;
  }

  @Override
  public int writes() {
    return (int) writeCounter;
  }
}

/** The namespace for field padding through inheritance. */
final class ManyToOneHeader {

  abstract static class PadReadCounter<E> extends ReadBuffer<E> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p10, p11, p12, p13, p14, p15, p16, p17;
  }

  /** Enforces a memory layout to avoid false sharing by padding the read count. */
  abstract static class ReadCounterRef<E> extends PadReadCounter<E> {
    static final long READ_OFFSET = UnsafeAccess.fieldOffset(ReadCounterRef.class, "readCounter");

    volatile long readCounter;

    void lazySetReadCounter(long count) {
      UnsafeAccess.UNSAFE.putOrderedLong(this, READ_OFFSET, count);
    }
  }

  abstract static class PadWriteCounter<E> extends ReadCounterRef<E> {
    long p20, p21, p22, p23, p24, p25, p26, p27;
    long p30, p31, p32, p33, p34, p35, p36, p37;
  }

  /** Enforces a memory layout to avoid false sharing by padding the write count. */
  abstract static class ReadAndWriteCounterRef<E> extends PadWriteCounter<E> {
    static final long WRITE_OFFSET =
        UnsafeAccess.fieldOffset(ReadAndWriteCounterRef.class, "writeCounter");

    volatile long writeCounter;

    long relaxedWriteCounter() {
      return UnsafeAccess.UNSAFE.getLong(this, WRITE_OFFSET);
    }

    boolean casWriteCounter(long expect, long update) {
      return UnsafeAccess.UNSAFE.compareAndSwapLong(this, WRITE_OFFSET, expect, update);
    }
  }
}
