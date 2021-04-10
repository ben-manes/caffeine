/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.membership.bloom;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Arrays;

import org.checkerframework.checker.index.qual.NonNegative;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.MembershipSettings;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.typesafe.config.Config;

/**
 * A Bloom filter is a space and time efficient probabilistic data structure that is used to test
 * whether an element is a member of a set. False positives are possible, but false negatives are
 * not. Elements can be added to the set, but not removed. The more elements that are added the
 * higher the probability of false positives. While risking false positives, Bloom filters have a
 * space advantage over other data structures for representing sets by not storing the items.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BloomFilter implements Membership {
  static final long[] SEED = { // A mixture of seeds from FNV-1a, CityHash, and Murmur3
      0xc3a5c85c97cb3127L, 0xb492b66fbe98f273L, 0x9ae16a3b2f90404fL, 0xcbf29ce484222325L};
  static final int BITS_PER_LONG_SHIFT = 6; // 64-bits
  static final int BITS_PER_LONG_MASK = Long.SIZE - 1;

  int tableShift;
  long[] table;

  /**
   * Creates a lazily initialized membership sketch, requiring {@link #ensureCapacity} be called
   * when the expected number of insertions and the false positive probability have been determined.
   */
  public BloomFilter() {}

  /**
   * Creates a membership sketch based on the expected number of insertions and the false positive
   * probability.
   */
  public BloomFilter(Config config) {
    MembershipSettings settings = new BasicSettings(config).membership();
    ensureCapacity(settings.expectedInsertions(), settings.fpp());
  }

  /**
   * Initializes and increases the capacity of this <tt>BloomFilter</tt> instance, if necessary,
   * to ensure that it can accurately estimate the membership of elements given the expected
   * number of insertions. This operation forgets all previous memberships when resizing.
   *
   * @param expectedInsertions the number of expected insertions
   * @param fpp the false positive probability, where 0.0 > fpp < 1.0
   */
  public void ensureCapacity(@NonNegative long expectedInsertions, @NonNegative double fpp) {
    checkArgument(expectedInsertions >= 0);
    checkArgument(fpp > 0 && fpp < 1);

    double optimalBitsFactor = -Math.log(fpp) / (Math.log(2) * Math.log(2));
    int optimalNumberOfBits = (int) (expectedInsertions * optimalBitsFactor);
    int optimalSize = Math.max(2, optimalNumberOfBits >>> BITS_PER_LONG_SHIFT);
    if ((table != null) && (table.length >= optimalSize)) {
      return;
    } else {
      int powerOfTwoShift = Integer.SIZE - Integer.numberOfLeadingZeros(optimalSize - 1);
      tableShift = Integer.SIZE - powerOfTwoShift;
      table = new long[1 << powerOfTwoShift];
    }
  }

  @Override
  public boolean mightContain(long e) {
    int item = spread(Long.hashCode(e));
    for (int i = 0; i < 4; i++) {
      int hash = seeded(item, i);
      int index = hash >>> tableShift;
      if ((table[index] & bitmask(hash)) == 0L) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void clear() {
    Arrays.fill(table, 0L);
  }

  @Override
  @SuppressWarnings("ShortCircuitBoolean")
  public boolean put(long e) {
    int item = spread(Long.hashCode(e));
    return setAt(item, 0) | setAt(item, 1) | setAt(item, 2) | setAt(item, 3);
  }

  /**
   * Sets the membership flag for the computed bit location.
   *
   * @param item the element's hash
   * @param seedIndex the hash seed index
   * @return if the membership changed as a result of this operation
   */
  @SuppressWarnings("PMD.LinguisticNaming")
  boolean setAt(int item, int seedIndex) {
    int hash = seeded(item, seedIndex);
    int index = hash >>> tableShift;
    long previous = table[index];
    table[index] |= bitmask(hash);
    return (table[index] != previous);
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

  /**
   * Applies the independent hash function for the given seed index.
   *
   * @param item the element's hash
   * @param i the hash seed index
   * @return the table index
   */
  static int seeded(int item, int i) {
    long hash = (item + SEED[i]) * SEED[i];
    hash += hash >>> 32;
    return (int) hash;
  }

  /**
   * Applies a hash function to determine the index of the bit.
   *
   * @param hash the seeded hash code
   * @return the mask to the bit
   */
  static long bitmask(int hash) {
    return 1L << (hash & BITS_PER_LONG_MASK);
  }
}
