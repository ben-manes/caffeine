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
 * A multiple-producer / single-consumer bounded buffer that rejects new elements if it is full or
 * fails spuriously due to contention. Unlike a queue and stack, a buffer does not guarantee an
 * ordering of elements either in FIFO or LIFO order.
 * <p>
 * Beware that it is the responsibility of the caller to ensure that a consumer has exclusive read
 * access to the buffer. This implementation does <em>not</em> include fail-fast behavior to guard
 * against incorrect consumer usage.
 * <p>
 * This implementation does <em>not</em> support elements of type {@link Long}.
 *
 * @param <E> the type of elements maintained by this buffer; may not be a long
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class BoundedBuffer<E> {

  /*
   * A segmented, non-blocking, bounded buffer variant of the Partitioned Ticket Lock queue.
   *
   * A circular ring buffer is used to store the elements being transfered by the producers to the
   * consumer. The monotonically increasing count of reads and writes are used to index sequentially
   * to the next element location. A free location holds a ticket corresponding to the write count
   * that should acquire it. The producers race to read the next write count and CAS the ticket to
   * the offered element. The addition may be unsuccessful due to the buffer being full or another
   * producer successfully acquiring the location. When the consumer takes the element, it places
   * the next write ticket into the location by adding the array length to the current read count.
   *
   * To further increase concurrency the buffer is internally segmented into multiple ring buffers.
   * The thread id is used as a hash with power-of-two sizing to quickly index to the preferred
   * ring buffer. The number of segments is chosen to minimize contention that may cause spurious
   * failures for producers.
   *
   * https://blogs.oracle.com/dave/entry/ptlqueue_a_scalable_bounded_capacity
   */

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The number of read buffers to use. */
  static final int NUMBER_OF_SEGMENTS = 4 * ceilingNextPowerOfTwo(NCPU);

  /** Mask value for indexing into the read buffers. */
  static final int SEGMENT_MASK = NUMBER_OF_SEGMENTS - 1;

  /** The maximum number of pending reads per buffer. */
  static final int RING_BUFFER_SIZE = 32;

  /** Mask value for indexing into the read buffer. */
  static final int RING_BUFFER_MASK = RING_BUFFER_SIZE - 1;

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  final long[] readCount;
  final AtomicLong[] writeCount;
  final AtomicReference<Object>[][] table;

  @SuppressWarnings({"unchecked", "cast", "rawtypes"})
  public BoundedBuffer() {
    readCount = new long[NUMBER_OF_SEGMENTS];
    writeCount = new AtomicLong[NUMBER_OF_SEGMENTS];
    table = new AtomicReference[NUMBER_OF_SEGMENTS][RING_BUFFER_SIZE];
    for (int i = 0; i < NUMBER_OF_SEGMENTS; i++) {
      table[i] = new AtomicReference[RING_BUFFER_SIZE];
      for (int j = 0; j < RING_BUFFER_SIZE; j++) {
        table[i][j] = new AtomicReference<>((long) j);
      }
      writeCount[i] = new AtomicLong();
    }
  }

  /**
   * Inserts the specified element into this buffer if it is possible to do so immediately without
   * violating capacity restrictions. The addition is allowed to fail spuriously if multiple
   * threads insert concurrently.
   *
   * @param e the element to add
   * @return {@code true} if the element could not be added because the buffer needs to be drained
   */
  public boolean submit(E e) {
    final int segmentIndex = segmentIndex();
    final AtomicLong counter = writeCount[segmentIndex];
    final long writes = counter.get();

    final int index = (int) (writes & RING_BUFFER_MASK);
    final AtomicReference<Object> slot = table[segmentIndex][index];
    final Object value = slot.get();
    if (!(value instanceof Long)) {
      // FIXME(ben): The slot was taken due to either the buffer being full or concurrent readers.
      // The contention is exasperated by lazy writing to the counter so a stale index may be
      // chosen. This may cause premature drains by not detecting the distinctions. When the
      // buffers are dynamically sized (see Striped64) this ignorance will be more acceptable.
      return true;
    } else if (((Long) value).longValue() != writes) {
      // Ensures CAS reference equality, race should rarely occur
      return false;
    } else if (slot.compareAndSet(value, e)) {
      counter.lazySet(writes + 1);
    }
    return false;
  }

  /**
   * Drains the buffer, sending each element to the consumer for processing. The caller must ensure
   * that a consumer has exclusive read access to the buffer.
   *
   * @param consumer the action to perform on each element
   */
  public void drain(Consumer<E> consumer) {
    final int start = segmentIndex();
    final int end = start + NUMBER_OF_SEGMENTS;
    for (int i = start; i < end; i++) {
      drainSegment(consumer, i & SEGMENT_MASK);
    }
  }

  /**
   * Drains an segment.
   *
   * @param consumer the action to perform on each element
   * @param segmentIndex the segment index in the table
   */
  private void drainSegment(Consumer<E> consumer, int segmentIndex) {
    long reads = readCount[segmentIndex];
    for (int i = 0; i < RING_BUFFER_SIZE; i++) {
      final int index = (int) (reads & RING_BUFFER_MASK);
      final AtomicReference<Object> slot = table[segmentIndex][index];
      final Object value = slot.get();
      if (value instanceof Long) {
        break;
      }
      slot.lazySet(reads + RING_BUFFER_SIZE);
      reads++;

      @SuppressWarnings("unchecked")
      E e = (E) value;
      consumer.accept(e);
    }
    readCount[segmentIndex] = reads;
  }

  /**
   * Returns the number of elements residing in the buffer. Beware that this method is <em>NOT</em>
   * a constant-time operation.
   *
   * @return the number of elements in this buffer
   */
  public int size() {
    int size = 0;
    for (AtomicReference<?>[] segment : table) {
      for (AtomicReference<?> slot : segment) {
        if (!(slot.get() instanceof Long)) {
          size++;
        }
      }
    }
    return size;
  }

  /**
   * Returns the number of elements that have been written to the buffer.
   *
   * @return the number of elements written to this buffer
   */
  public int writes() {
    int writes = 0;
    for (AtomicLong counter : writeCount) {
      writes += counter.intValue();
    }
    return writes;
  }

  /**
   * Returns the number of elements that have been read from the buffer.
   *
   * @return the number of elements read from this buffer
   */
  public int reads() {
    int reads = 0;
    for (long counter : readCount) {
      reads += counter;
    }
    return reads;
  }

  /**
   * Returns the index to the ring buffer to record into. Uses a one-step FNV-1a hash code
   * (http://www.isthe.com/chongo/tech/comp/fnv) based on the current thread's id. These hash codes
   * have more uniform distribution properties with respect to small moduli (here 1-31) than do
   * other simple hashing functions.
   */
  static int segmentIndex() {
    int id = (int) Thread.currentThread().getId();
    return ((id ^ 0x811c9dc5) * 0x01000193) & SEGMENT_MASK;
  }
}
