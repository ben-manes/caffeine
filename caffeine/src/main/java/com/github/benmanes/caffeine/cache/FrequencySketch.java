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

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

import com.github.benmanes.caffeine.base.UnsafeAccess;

/**
 * A probabilistic multiset for estimating the popularity of an element within a time window. The
 * maximum frequency of an element is limited to 15 (4-bits) and an aging process periodically
 * halves the popularity of all elements.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NotThreadSafe
final class FrequencySketch<E> {

  /*
   * This class maintains a 4-bit CountMinSketch [1] with periodic aging to provide the popularity
   * history for the TinyLfu admission policy [2]. The time and space efficiency of the sketch
   * allows it to estimate the frequency of an entry in a stream of cache access events.
   *
   * The counter matrix is represented as a single dimensional array holding 16 counters per slot. A
   * fixed depth of four balances the accuracy and cost, resulting in a width of four times the
   * length of the array. To retain an accurate estimation the array's length equals the maximum
   * number of entries in the cache, increased to the closest power-of-two to exploit more efficient
   * bit masking.
   *
   * The frequency of all entries is aged periodically using a sampling window based on the maximum
   * number of entries in the cache. This is referred to as the reset operation by TinyLfu and keeps
   * the sketch fresh by dividing all counters by two. The O(n) cost of aging is amortized, ideal
   * for hardware prefetching, and uses inexpensive bit manipulations per array location.
   *
   * [1] An Improved Data Stream Summary: The Count-Min Sketch and its Applications
   * http://dimacs.rutgers.edu/~graham/pubs/papers/cm-full.pdf
   * [2] TinyLFU: A Highly Efficient Cache Admission Policy
   * http://www.cs.technion.ac.il/~gilga/TinyLFU_PDP2014.pdf
   */

  static final long[] SEED = new long[] { // A mixture of seeds from FNV-1a, CityHash, and Murmur3
      0xc3a5c85c97cb3127L, 0xb492b66fbe98f273L, 0x9ae16a3b2f90404fL, 0xcbf29ce484222325L};
  static final long RESET_MASK = 0x1111111111111111L;
  static final long MASK_A = 0xf0f0f0f0f0f0f0f0L;
  static final long MASK_B = 0x0f0f0f0f0f0f0f0fL;
  static final int TABLE_SHIFT;
  static final int TABLE_BASE;

  int sampleSize;
  int tableMask;
  long[] table;
  int size;

  /**
   * Creates a frequency sketch that can accurately estimate the popularity of elements given
   * the maximum size of the cache.
   *
   * @param maximumSize the maximum size of the cache
   */
  public FrequencySketch(@Nonnegative long maximumSize) {
    ensureCapacity(maximumSize);
  }

  /**
   * Increases the capacity of this <tt>FrequencySketch</tt> instance, if necessary, to ensure that
   * it can accurately estimate the popularity of elements given the maximum size of the cache.
   *
   * @param maximumSize the maximum size of the cache
   */
  public void ensureCapacity(@Nonnegative long maximumSize) {
    Caffeine.requireArgument(maximumSize >= 0);
    int maximum = (int) Math.min(maximumSize, Integer.MAX_VALUE);
    if ((table != null) && (table.length >= maximum)) {
      return;
    }

    table = new long[ceilingNextPowerOfTwo(maximum)];
    tableMask = table.length - 1;
    sampleSize = (10 * maximum);
    if (sampleSize <= 0) {
      sampleSize = Integer.MAX_VALUE;
    }
  }

  /**
   * Returns the estimated number of occurrences of an element, up to the maximum (15).
   *
   * @param element the element to count occurrences of
   * @return the estimated number of occurrences of the element; possibly zero but never negative
   */
  @Nonnegative
  public int frequency(@Nonnull E e) {
    int hash = spread(e.hashCode());
    int start = (hash & 3) << 2;
    int frequency = Integer.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      int index = indexOf(hash, i);
      long slot = UnsafeAccess.UNSAFE.getLong(table, byteOffset(index));
      int count = (int) ((slot >>> ((start + i) << 2)) & 0xfL);
      frequency = Math.min(frequency, count);
    }
    return frequency;
  }

  /**
   * Increments the popularity of the element if it does not exceed the maximum (15). The popularity
   * of all elements will be periodically down sampled when the observed events exceeds a threshold.
   * This process provides a frequency aging to allow expired long term entries to fade away.
   *
   * @param e the element to add
   */
  public void increment(@Nonnull E e) {
    int hash = spread(e.hashCode());
    int start = (hash & 3) << 2;

    // Loop unrolling improves throughput by 5m ops/s
    int index0 = indexOf(hash, 0);
    int index1 = indexOf(hash, 1);
    int index2 = indexOf(hash, 2);
    int index3 = indexOf(hash, 3);

    boolean added = incrementAt(index0, start);
    added |= incrementAt(index1, start + 1);
    added |= incrementAt(index2, start + 2);
    added |= incrementAt(index3, start + 3);

    if (added && (++size == sampleSize)) {
      reset();
    }
  }

  /**
   * Increments the specified counter by 1 if it is not already at the maximum value (15).
   *
   * @param i the table index (16 counters)
   * @param j the counter to increment
   * @return if incremented
   */
  boolean incrementAt(int i, int j) {
    int offset = j << 2;
    long mask = (0xfL << offset);
    long slot = UnsafeAccess.UNSAFE.getLong(table, byteOffset(i));
    if ((slot & mask) != mask) {
      table[i] = slot + (1L << offset);
      return true;
    }
    return false;
  }

  /**
   * Reduces every counter by half of its original value. As each table entry represents 16 counters
   * this is performed as two 8 counter reductions OR'd together.
   */
  void reset() {
    size = (sampleSize >>> 1);
    for (int i = 0; i < table.length; i++) {
      size -= Long.bitCount(table[i] & RESET_MASK);
      long a = ((table[i] & MASK_A) >>> 1) & MASK_A;
      long b = ((table[i] & MASK_B) >>> 1) & MASK_B;
      table[i] = a | b;
    }
  }

  /**
   * Returns the table index for the counter at the specified depth.
   *
   * @param item the element's hash
   * @param i the counter depth
   * @return the table index
   */
  int indexOf(int item, int i) {
    long hash = SEED[i] * item;
    hash += hash >> 32;
    return ((int) hash) & tableMask;
  }

  /**
   * Applies a supplemental hash function to a given hashCode, which defends against poor quality
   * hash functions.
   */
  static int spread(int x) {
    x = ((x >>> 16) ^ x) * 0x45d9f3b;
    return (x >>> 16) ^ x;
  }

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  static long byteOffset(int i) {
    return ((long) i << TABLE_SHIFT) + TABLE_BASE;
  }

  static {
    TABLE_BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(long[].class);
    int scale = UnsafeAccess.UNSAFE.arrayIndexScale(long[].class);
    if ((scale & (scale - 1)) != 0) {
      throw new Error("data type scale not a power of two");
    }
    TABLE_SHIFT = 31 - Integer.numberOfLeadingZeros(scale);
  }
}
