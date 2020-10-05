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

import java.util.function.Consumer;

import org.jctools.util.UnsafeAccess;

import com.github.benmanes.caffeine.cache.ReadBuffer;

/**
 * A simple ring buffer implementation that watches both the head and tail counts to acquire a
 * slot. This version uses a contiguous array that spaces each element to avoid false sharing.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ManyToOneSpacedBuffer<E> extends ManyToOneSpacedHeader.ReadAndWriteCounterRef<E> {
  static final int BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(Object[].class);
  static final int SHIFT = 31 - Integer.numberOfLeadingZeros(
      UnsafeAccess.UNSAFE.arrayIndexScale(Object[].class));

  // Assume 4-byte references and 64-byte cache line (16 elements per line)
  static final int SPACED_SIZE = BUFFER_SIZE << 4;
  static final int SPACED_MASK = SPACED_SIZE - 1;
  static final int OFFSET = 16;

  final Object[] buffer;

  ManyToOneSpacedBuffer() {
    buffer = new Object[SPACED_SIZE];
  }

  @Override
  public int offer(E e) {
    long head = readCounter;
    long tail = relaxedWriteCounter();
    long size = (tail - head);
    if (size >= SPACED_SIZE) {
      return FULL;
    }
    if (casWriteCounter(tail, tail + OFFSET)) {
      long offset = ((tail & SPACED_MASK) << SHIFT) + BASE;
      UnsafeAccess.UNSAFE.putOrderedObject(buffer, offset, e);
      return SUCCESS;
    }
    return FAILED;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void drainTo(Consumer<E> consumer) {
    long head = readCounter;
    long tail = relaxedWriteCounter();
    long size = (tail - head);
    if (size == 0) {
      return;
    }
    do {
      long offset = ((head & SPACED_MASK) << SHIFT) + BASE;
      E e = (E) UnsafeAccess.UNSAFE.getObjectVolatile(buffer, offset);
      if (e == null) {
        // not published yet
        break;
      }
      UnsafeAccess.UNSAFE.putOrderedObject(buffer, offset, null);
      consumer.accept(e);
      head += OFFSET;
    } while (head != tail);
    lazySetReadCounter(head);
  }

  @Override
  public int reads() {
    return (int) readCounter / OFFSET;
  }

  @Override
  public int writes() {
    return (int) writeCounter / OFFSET;
  }
}

/** The namespace for field padding through inheritance. */
final class ManyToOneSpacedHeader {

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
