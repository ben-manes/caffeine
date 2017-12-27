/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.admission.countmin64;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Random;

/**
 * Count-Min Sketch data structure with optional conservative addition.
 * <p>
 * This is a derived from <tt>CountMinSketch</tt> and <tt>ConservativeAddSketch</tt> provided by
 * <a href="https://github.com/addthis/stream-lib">StreamLib</a>.
 */
final class CountMin64 {
  static final long PRIME_MODULUS = (1L << 31) - 1;

  final long[][] table;
  final long[] hashA;
  final int depth;
  final int width;

  public CountMin64(double eps, double confidence, int seed) {
    // 2/w = eps ; w = 2/eps
    // 1/2^depth <= 1-confidence ; depth >= -log2 (1-confidence)
    this.width = (int) Math.ceil(2 / eps);
    this.depth = (int) Math.ceil(-Math.log(1 - confidence) / Math.log(2));
    this.table = new long[depth][width];
    this.hashA = new long[depth];

    // We're using a linear hash functions of the form ((a*x+b) mod p) where a,b are chosen
    // independently for each hash function. However we can set b = 0 as all it does is shift the
    // results without compromising their uniformity or independence with the other hashes.
    Random r = new Random(seed);
    for (int i = 0; i < depth; ++i) {
      hashA[i] = r.nextInt(Integer.MAX_VALUE);
    }
  }

  /** The estimate is correct within epsilon * (total item count), with probability confidence. */
  public long estimateCount(long item) {
    long count = Long.MAX_VALUE;
    for (int i = 0; i < depth; ++i) {
      count = Math.min(count, table[i][hash(item, i)]);
    }
    return count;
  }

  public void add(boolean conservative, long item, long count) {
    // Actually for negative increments we'll need to use the median instead of minimum, and
    // accuracy will suffer somewhat. Probably makes sense to add an "allow negative increments"
    // parameter to constructor.
    checkArgument(count >= 0, "Negative increments not implemented");

    if (conservative) {
      conservativeAdd(item, count);
    } else {
      add(item, count);
    }
  }

  private void add(long item, long count) {
    for (int i = 0; i < depth; ++i) {
      table[i][hash(item, i)] += count;
    }
  }

  private void conservativeAdd(long item, long count) {
    int[] buckets = new int[depth];
    for (int i = 0; i < depth; ++i) {
      buckets[i] = hash(item, i);
    }
    long min = table[0][buckets[0]];
    for (int i = 1; i < depth; ++i) {
      min = Math.min(min, table[i][buckets[i]]);
    }
    for (int i = 0; i < depth; ++i) {
      long newVal = Math.max(table[i][buckets[i]], min + count);
      table[i][buckets[i]] = newVal;
    }
  }

  private int hash(long item, int i) {
    long hash = hashA[i] * item;
    // A super fast way of computing x mod 2^p-1
    // See http://www.cs.princeton.edu/courses/archive/fall09/cos521/Handouts/universalclasses.pdf
    // page 149, right after Proposition 7.
    hash += hash >> 32;
    hash &= PRIME_MODULUS;
    // Doing "%" after (int) conversion is ~2x faster than %'ing longs.
    return ((int) hash) % width;
  }
}
