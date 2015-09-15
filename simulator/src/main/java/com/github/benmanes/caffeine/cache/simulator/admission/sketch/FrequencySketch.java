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
package com.github.benmanes.caffeine.cache.simulator.admission.sketch;

import javax.annotation.Nonnull;

import org.jctools.util.UnsafeAccess;

/**
 * A 4-bit variant of CountMinSketch, allowing an element to have a maximum popularity of 15.
 * <p>
 * The counter matrix is represented as a single array of N longs, which allows for 16 counters per
 * slot. A depth of 4 was chosen as a balance for accuracy and time, resulting in a matrix width of
 * 4 x N. The length N must be a power-of-two so that more efficient bit masking can be used.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class FrequencySketch<E> implements Frequency<E> {
  private static final long[] SEED = new long[] {-7701517898679364118L, -8602375887669043078L,
      5691832600956327737L, -1886344194585363204L};
  private static final long RESET_MASK = 0x1111111111111111L;
  private static final long MASK_A = 0xf0f0f0f0f0f0f0f0L;
  private static final long MASK_B = 0x0f0f0f0f0f0f0f0fL;

  private static final int TABLE_BASE;
  private static final int TABLE_SHIFT;

  static {
    TABLE_BASE = UnsafeAccess.UNSAFE.arrayBaseOffset(long[].class);
    int scale = UnsafeAccess.UNSAFE.arrayIndexScale(long[].class);
    if ((scale & (scale - 1)) != 0) {
      throw new Error("data type scale not a power of two");
    }
    TABLE_SHIFT = 31 - Integer.numberOfLeadingZeros(scale);
  }

  final int sampleSize;
  final int tableMask;
  final long[] table;

  int size;

  public FrequencySketch(int expectedSize) {
    table = new long[ceilingNextPowerOfTwo(expectedSize)];
    sampleSize = 10 * expectedSize;
    tableMask = table.length - 1;
  }

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  static long byteOffset(int i) {
    return ((long) i << TABLE_SHIFT) + TABLE_BASE;
  }

  @Override
  public int frequency(@Nonnull E e) {
    int item = e.hashCode();
    int start = (item & 3) << 2;
    int frequency = Integer.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      int index = indexOf(item, i);
      long slot = UnsafeAccess.UNSAFE.getLong(table, byteOffset(index));
      int count = (int) ((slot >>> ((start + i) << 2)) & 0xfL);
      frequency = Math.min(frequency, count);
    }
    return frequency;
  }

  @Override
  public void increment(@Nonnull E e) {
    int item = e.hashCode();
    int start = (item & 3) << 2;

    // Loop unrolling improves throughput by 5m ops/s
    int index0 = indexOf(item, 0);
    int index1 = indexOf(item, 1);
    int index2 = indexOf(item, 2);
    int index3 = indexOf(item, 3);

    boolean added = increment(index0, start);
    added |= increment(index1, start + 1);
    added |= increment(index2, start + 2);
    added |= increment(index3, start + 3);

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
  boolean increment(int i, int j) {
    int offset = j << 2;
    long mask = (0xfL << offset);
    long byteOffset = byteOffset(i);
    long slot = UnsafeAccess.UNSAFE.getLong(table, byteOffset);
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
}
