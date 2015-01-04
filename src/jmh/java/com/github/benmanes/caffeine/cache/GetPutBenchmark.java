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
package com.github.benmanes.caffeine.cache;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.github.benmanes.caffeine.generator.IntegerGenerator;
import com.github.benmanes.caffeine.generator.ScrambledZipfianGenerator;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Group)
public class GetPutBenchmark {
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;

  @Param({
//    "LinkedHashMap_Lru",
//    "ConcurrentHashMap",
    "ConcurrentLinkedHashMap",
    "Caffeine",
    "Guava",
  })
  CacheType cacheType;

  Map<Integer, Boolean> cache;
  Integer[] ints;

  @State(Scope.Thread)
  public static class ThreadState {
    int index = ThreadLocalRandom.current().nextInt();
  }

  @Setup
  public void setup() {
    cache = cacheType.create(2 * SIZE, 64);
    for (int i = 0; i < SIZE; i++) {
      cache.put(i, Boolean.TRUE);
    }

    ints = new Integer[SIZE];
    IntegerGenerator generator = new ScrambledZipfianGenerator(SIZE);
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextInt();
    }
  }

  @Benchmark @Group("read_only") @GroupThreads(4)
  public void get_readOnly(ThreadState threadState) {
    cache.get(ints[threadState.index++ & MASK]);
  }

  @Benchmark @Group("write_only") @GroupThreads(4)
  public void put_writeOnly(ThreadState threadState) {
    cache.put(ints[threadState.index++ & MASK], Boolean.FALSE);
  }

  @Benchmark @Group("readwrite") @GroupThreads(3)
  public void readwrite_get(ThreadState threadState) {
    cache.get(ints[threadState.index++ & MASK]);
  }

  @Benchmark @Group("readwrite") @GroupThreads(1)
  public void readwrite_put(ThreadState threadState) {
    cache.put(ints[threadState.index++ & MASK], Boolean.FALSE);
  }
}
