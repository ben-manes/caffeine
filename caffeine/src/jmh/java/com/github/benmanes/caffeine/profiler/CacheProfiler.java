/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.profiler;

import java.util.Random;

import com.github.benmanes.caffeine.cache.BasicCache;
import com.github.benmanes.caffeine.cache.CacheType;
import com.yahoo.ycsb.generator.NumberGenerator;
import com.yahoo.ycsb.generator.ScrambledZipfianGenerator;

/**
 * A hook for profiling caches.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class CacheProfiler extends ProfilerHook {
  static final CacheType cacheType = CacheType.Caffeine;
  static final int MAX_SIZE = 10 * NUM_THREADS;
  static final int SIZE = (2 << 14);
  static final int MASK = SIZE - 1;
  static final int ITEMS = SIZE / 4;

  final BasicCache<Integer, Boolean> cache;
  final Random random = new Random();
  final Integer[] ints;
  final boolean reads;

  CacheProfiler() {
    cache = cacheType.create(2 * SIZE);

    // Ensure full initialization of internal structures
    for (int i = 0; i < 2 * SIZE; i++) {
      cache.put(i, Boolean.TRUE);
    }
    cache.clear();

    ints = new Integer[SIZE];
    NumberGenerator generator = new ScrambledZipfianGenerator(ITEMS);
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextValue().intValue();
      cache.put(ints[i], Boolean.TRUE);
    }

    reads = true;
  }

  @Override
  protected void profile() {
    if (reads) {
      reads();
    } else {
      writes();
    }
  }

  /** Spins forever reading from the cache. */
  private void reads() {
    int index = random.nextInt();
    for (;;) {
      Integer key = ints[index++ & MASK];
      cache.get(key);
      calls.increment();
    }
  }

  /** Spins forever writing into the cache. */
  private void writes() {
    int index = random.nextInt();
    for (;;) {
      Integer key = ints[index++ & MASK];
      cache.put(key, Boolean.TRUE);
      calls.increment();
    }
  }

  public static void main(String[] args) {
    CacheProfiler profile = new CacheProfiler();
    profile.run();
  }
}
