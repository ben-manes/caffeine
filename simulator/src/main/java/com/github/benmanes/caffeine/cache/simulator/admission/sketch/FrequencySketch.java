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
  private static final int SAMPLE_SIZE = 5_000;
  private static final int LENGTH = 512; // must be power-of-two
  private static final int LENGTH_MASK = LENGTH - 1;

  final long[] table;

  int size;

  public FrequencySketch() {
    table = new long[LENGTH];
  }

  @Override
  public int frequency(@Nonnull E e) {
    int item = e.hashCode();
    int start = (item & 3) << 2;
    int frequency = Integer.MAX_VALUE;
    for (int i = start; i < (start + 4); i++) {
      int index = indexOf(item, i);
      int count = (int) ((table[index] >>> (i << 2)) & 0xfL);
      frequency = Math.min(frequency, count);
    }
    return frequency;
  }

  @Override
  public void increment(@Nonnull E e) {
    int item = e.hashCode();
    int start = (item & 3) << 2;
    for (int i = start; i < (start + 4); i++) {
      int index = indexOf(item, i);
      increment(index, i);
    }

    if (++size == SAMPLE_SIZE) {
      reset();
    }
  }

  /**
   * Increments the specified counter by 1 if it is not already at the maximum value (15).
   *
   * @param i the table index (16 counters)
   * @param j the counter to increment
   */
  void increment(int i, int j) {
    int offset = j << 2;
    long mask = (0xfL << offset);
    if ((table[i] & mask) != mask) {
      table[i] += (1L << offset);
    }
  }

  /**
   * Reduces every counter by half of its original value. As each table entry represents 16 counters
   * this is performed as two 8 counter reductions OR'd together.
   * */
  void reset() {
    size = (SAMPLE_SIZE >>> 1);
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
   * @param i the counter (depth)
   * @return the table index
   */
  int indexOf(int item, int i) {
    long hash = SEED[i & 3] * item;
    hash += hash >> 32;
    return (int) (hash & LENGTH_MASK);
  }
}
