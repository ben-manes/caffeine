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
package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import static com.google.common.base.Preconditions.checkArgument;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.google.common.math.IntMath;
import com.typesafe.config.Config;

/**
 * A probabilistic multiset for estimating the popularity of an element within a time window. The
 * maximum frequency of an element is limited to 15 (4-bits) and extensions provide the aging
 * process.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class CountMin4 implements Frequency {
  static final long[] SEED = { // A mixture of seeds from FNV-1a, CityHash, and Murmur3
      0xc3a5c85c97cb3127L, 0xb492b66fbe98f273L, 0x9ae16a3b2f90404fL, 0xcbf29ce484222325L};
  static final long RESET_MASK = 0x7777777777777777L;

  protected final boolean conservative;

  protected int tableMask;
  protected long[] table;
  protected int step = 1;

  /**
   * Creates a frequency sketch that can accurately estimate the popularity of elements given
   * the maximum size of the cache.
   */
  protected CountMin4(Config config) {
    BasicSettings settings = new BasicSettings(config);
    conservative = settings.tinyLfu().conservative();

    double countersMultiplier = settings.tinyLfu().countMin4().countersMultiplier();
    long counters = (long) (countersMultiplier * settings.maximumSize());
    ensureCapacity(counters);

  }

  /**
   * Increases the capacity of this <tt>FrequencySketch</tt> instance, if necessary, to ensure that
   * it can accurately estimate the popularity of elements given the maximum size of the cache. This
   * operation forgets all previous counts when resizing.
   *
   * @param maximumSize the maximum size of the cache
   */
  protected void ensureCapacity(long maximumSize) {
    checkArgument(maximumSize >= 0);
    int maximum = (int) Math.min(maximumSize, Integer.MAX_VALUE >>> 1);
    if ((table != null) && (table.length >= maximum)) {
      return;
    }

    table = new long[(maximum == 0) ? 1 : IntMath.ceilingPowerOfTwo(maximum)];
    tableMask = Math.max(0, table.length - 1);
  }

  /**
   * Returns the estimated number of occurrences of an element, up to the maximum (15).
   *
   * @param e the element to count occurrences of
   * @return the estimated number of occurrences of the element; possibly zero but never negative
   */
  @Override
  public int frequency(long e) {
    int hash = spread(Long.hashCode(e));
    int start = (hash & 3) << 2;
    int frequency = Integer.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      int index = indexOf(hash, i);
      int count = (int) ((table[index] >>> ((start + i) << 2)) & 0xfL);
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
  @Override
  public void increment(long e) {
    if (conservative) {
      conservativeIncrement(e);
    } else {
      regularIncrement(e);
    }
  }

  /** Increments all of the associated counters. */
  void regularIncrement(long e) {
    int hash = spread(Long.hashCode(e));
    int start = (hash & 3) << 2;

    // Loop unrolling improves throughput by 5m ops/s
    int index0 = indexOf(hash, 0);
    int index1 = indexOf(hash, 1);
    int index2 = indexOf(hash, 2);
    int index3 = indexOf(hash, 3);

    boolean added = incrementAt(index0, start, step);
    added |= incrementAt(index1, start + 1, step);
    added |= incrementAt(index2, start + 2, step);
    added |= incrementAt(index3, start + 3, step);

    tryReset(added);
  }

  /** Increments the associated counters that are at the observed minimum. */
  void conservativeIncrement(long e) {
    int hash = spread(Long.hashCode(e));
    int start = (hash & 3) << 2;

    int[] index = new int[4];
    int[] count = new int[4];
    int min = Integer.MAX_VALUE;
    for (int i = 0; i < 4; i++) {
      index[i] = indexOf(hash, i);
      count[i] = (int) ((table[index[i]] >>> ((start + i) << 2)) & 0xfL);
      min = Math.min(min, count[i]);
    }

    if (min == 15) {
      tryReset(false);
      return;
    }

    for (int i = 0; i < 4; i++) {
      if (count[i] == min) {
        incrementAt(index[i], start + i, step);
      }
    }
    tryReset(true);
  }

  /** Performs the aging process after an addition to allow old entries to fade away. */
  protected void tryReset(boolean added) {}

  /**
   * Increments the specified counter by 1 if it is not already at the maximum value (15).
   *
   * @param i the table index (16 counters)
   * @param j the counter to increment
   * @param step the increase amount
   * @return if incremented
   */
  boolean incrementAt(int i, int j, long step) {
    int offset = j << 2;
    long mask = (0xfL << offset);
    if ((table[i] & mask) != mask) {
      long current = (table[i] & mask) >>> offset;
      long update = Math.min(current + step, 15);
      table[i] = (table[i] & ~mask) | (update << offset);
      return true;
    }
    return false;
  }

  /**
   * Returns the table index for the counter at the specified depth.
   *
   * @param item the element's hash
   * @param i the counter depth
   * @return the table index
   */
  int indexOf(int item, int i) {
    long hash = (item + SEED[i]) * SEED[i];
    hash += (hash >>> 32);
    return ((int) hash) & tableMask;
  }

  /**
   * Applies a supplemental hash function to a given hashCode, which defends against poor quality
   * hash functions.
   */
  int spread(int x) {
    x = ((x >>> 16) ^ x) * 0x45d9f3b;
    x = ((x >>> 16) ^ x) * 0x45d9f3b;
    return (x >>> 16) ^ x;
  }
}
