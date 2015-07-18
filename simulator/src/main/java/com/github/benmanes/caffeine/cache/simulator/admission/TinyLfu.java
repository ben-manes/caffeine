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
package com.github.benmanes.caffeine.cache.simulator.admission;

import com.clearspring.analytics.stream.frequency.ConservativeAddSketch;
import com.clearspring.analytics.stream.frequency.IFrequency;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

/**
 * Admits new entries based on the estimated frequency of its historic use.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TinyLfu implements Admittor {
  /*
   * TinyLFU is an admission policy that uses a probabilistic frequency of the items to determine
   * if the newly added entry should be retained instead of the eviction policy's victim. This
   * augments classic policies like LRU to increase the hit rate and be scan resistant, and does
   * not require retaining non-resident entries like LIRS or ARC style eviction policies.
   *
   * [1] TinyLFU: A Highly Efficient Cache Admission Policy
   * http://www.cs.technion.ac.il/~gilga/TinyLFU_PDP2014.pdf
   */

  private final IFrequency sketch;

  public TinyLfu(double epsOfTotalCount, double confidence) {
    // These parameters are best described in...
    // https://github.com/twitter/algebird/blob/develop/algebird-core/src/main/scala/com/twitter/algebird/CountMinSketch.scala
    this.sketch = new ConservativeAddSketch(epsOfTotalCount, confidence, (int) System.nanoTime());
  }

  @Override
  public void record(int key) {
    sketch.add(key, 1);
  }

  @Override
  public boolean admit(int candidateKey, int victimKey) {
    return sketch.estimateCount(candidateKey) >= sketch.estimateCount(victimKey);
  }

  @SuppressWarnings("unused")
  private static final class PerfectFrequency implements IFrequency {
    final Multiset<Object> keys = HashMultiset.create();

    @Override
    public void add(long item, long count) {
      keys.add(item, (int) count);
    }

    @Override
    public void add(String item, long count) {
      keys.add(item, (int) count);
    }

    @Override
    public long estimateCount(long item) {
      return keys.count(item);
    }

    @Override
    public long estimateCount(String item) {
      return keys.count(item);
    }

    @Override
    public long size() {
      return keys.elementSet().size();
    }
  }
}
