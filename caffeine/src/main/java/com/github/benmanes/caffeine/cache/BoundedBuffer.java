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
 * ordering of elements in either FIFO or LIFO order.
 * <p>
 * Beware that it is the responsibility of the caller to ensure that a consumer has exclusive read
 * access to the buffer. This implementation does <em>not</em> include fail-fast behavior to guard
 * against incorrect consumer usage.
 *
 * @param <E> the type of elements maintained by this buffer
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class BoundedBuffer<E> {

  /*
   * A segmented, non-blocking, circular ring buffer is used to store the elements being transfered
   * by the producers to the consumer. The monotonically increasing count of reads and writes allow
   * indexing sequentially to the next element location. The arrays use power-of-two sizing for
   * quickly determining to the proper location.
   *
   * The producers race to read the counts, check if there is available capacity, and if so then try
   * once to CAS to the next write count. If the increment is successful then the producer lazily
   * publishes the next element. The producer does not retry or block when unsuccessful due to a
   * failed CAS or the buffer being full.
   *
   * The consumer reads the counts and takes the available elements. The clearing of the elements
   * and the next read count are lazily set.
   *
   * To further increase concurrency the buffer is internally segmented into multiple ring buffers.
   * The number of segments is determined as a size that minimize contention that may cause spurious
   * failures for producers. The segment is chosen by a hash of the thread's id.
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

  final AtomicLong[] readCount;
  final AtomicLong[] writeCount;
  final AtomicReference<E>[][] table;

  @SuppressWarnings({"unchecked", "cast", "rawtypes"})
  public BoundedBuffer() {
    readCount = new AtomicLong[NUMBER_OF_SEGMENTS];
    writeCount = new AtomicLong[NUMBER_OF_SEGMENTS];
    table = new AtomicReference[NUMBER_OF_SEGMENTS][RING_BUFFER_SIZE];
    for (int i = 0; i < NUMBER_OF_SEGMENTS; i++) {
      table[i] = new AtomicReference[RING_BUFFER_SIZE];
      for (int j = 0; j < RING_BUFFER_SIZE; j++) {
        table[i][j] = new AtomicReference<>();
      }
      readCount[i] = new AtomicLong();
      writeCount[i] = new AtomicLong();
    }
  }

  /**
   * Inserts the specified element into this buffer if it is possible to do so immediately without
   * violating capacity restrictions. The addition is allowed to fail spuriously if multiple
   * threads insert concurrently.
   *
   * @param e the element to add
   * @return {@code true} if the element was or could have been added; {@code false} if full
   */
  public boolean submit(E e) {
    final int segmentIndex = segmentIndex();
    final AtomicLong readCounter = readCount[segmentIndex];
    final AtomicLong writeCounter = writeCount[segmentIndex];

    long head = readCounter.get();
    long tail = writeCounter.get();
    long size = (tail - head);
    if (size >= RING_BUFFER_SIZE) {
      return false;
    }
    if (writeCounter.compareAndSet(tail, tail + 1)) {
      int index = (int) (tail & RING_BUFFER_MASK);
      table[segmentIndex][index].lazySet(e);
    }
    return true;
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
    final AtomicLong readCounter = readCount[segmentIndex];
    final AtomicLong writeCounter = writeCount[segmentIndex];

    long head = readCounter.get();
    long tail = writeCounter.get();
    long size = (tail - head);
    if (size == 0) {
      return;
    }
    do {
      int index = (int) (head & RING_BUFFER_MASK);
      AtomicReference<E> slot = table[segmentIndex][index];
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

  /**
   * Returns the number of elements residing in the buffer.
   *
   * @return the number of elements in this buffer
   */
  public int size() {
    return writes() - reads();
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
    for (AtomicLong counter : readCount) {
      reads += counter.intValue();
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
