/*
 * Copyright 2015 Gilga Einziger. All Rights Reserved.
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
package com.clearspring.analytics.stream.frequency;

/**
 * A version of the TinyLFU sketch based on a regular conservative update sketch. The difference is
 * that any time the sum of counters reach a predefined values we divide all counters by 2 in what
 * is called a reset operation.
 * <p>
 * For more details see <a href="http://www.cs.technion.ac.il/~gilga/TinyLFU_PDP2014.pdf">TinyLFU:
 * A Highly Efficient Cache Admission Policy</a>.
 * <p>
 * The CountMinSketch parameters are described in
 * <a href="https://github.com/twitter/algebird/blob/develop/algebird-core/src/main/scala/com/twitter/algebird/CountMinSketch.scala">
 * Twitter's implementation</a>.
 *
 * @author gilga1983@gmail.com (Gilga Einziger)
 */
public final class CountMinTinyLfu {
  final ConservativeAddSketch sketch;
  final int sampleSize;
  int size;

  public CountMinTinyLfu(int depth, int width, int seed, int samplesize) {
    sketch = new ConservativeAddSketch(depth, width, seed);
    sampleSize = samplesize;
  }

  public CountMinTinyLfu(double epsOfTotalCount, double confidence, int seed, int samplesize) {
    sketch = new ConservativeAddSketch(epsOfTotalCount, confidence, seed);
    sampleSize = samplesize;
  }

  /** Returns the estimated usage frequency of the item. */
  public long frequency(long item) {
    return sketch.estimateCount(item);
  }

  public void add(long item, long count) {
    if (sketch.estimateCount(item) < 10) {
      sketch.add(item, count);
    }
    size += count;
    resetIfNeeded();
  }

  private void resetIfNeeded() {
    if (size > sampleSize) {
      size /= 2;
      for (int i = 0; i < sketch.depth; i++) {
        for (int j = 0; j < sketch.width; j++) {
          size -= sketch.table[i][j] & 1;
          sketch.table[i][j] >>>= 1;
        }
      }
    }
  }
}
