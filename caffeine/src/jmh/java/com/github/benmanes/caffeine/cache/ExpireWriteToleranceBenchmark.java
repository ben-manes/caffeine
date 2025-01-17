/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.BoundedLocalCache.EXPIRE_WRITE_TOLERANCE;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import com.google.common.cache.CacheBuilder;
import com.google.common.testing.FakeTicker;

import site.ycsb.generator.NumberGenerator;
import site.ycsb.generator.ScrambledZipfianGenerator;

/**
 * A benchmark for the {@link BoundedLocalCache#EXPIRE_WRITE_TOLERANCE} optimization.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings({"CanonicalAnnotationSyntax", "LexicographicalAnnotationAttributeListing"})
public class ExpireWriteToleranceBenchmark {
  static final int SIZE = (2 << 14);
  static final int MASK = SIZE - 1;
  static final int NUM_THREADS = 8;
  static final int ITEMS = SIZE / 3;
  static final int INITIAL_CAPACITY = 10_000;

  @Param({
    "Caffeine w/o tolerance",
    "Caffeine w/ tolerance",
    "ConcurrentHashMap",
    "Guava"
  })
  String mapType;

  Map<Integer, Integer> map;
  Integer[] ints;

  @State(Scope.Thread)
  public static class ThreadState {
    static final Random random = new Random();
    int index = random.nextInt();
  }

  @Setup
  @SuppressWarnings("ReturnValueIgnored")
  public void setup() {
    if (mapType.equals("ConcurrentHashMap")) {
      map = new ConcurrentHashMap<>(INITIAL_CAPACITY);
    } else if (mapType.equals("Caffeine w/ tolerance")) {
      Cache<Integer, Integer> cache = Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofDays(1))
          .initialCapacity(INITIAL_CAPACITY)
          .ticker(new FakeTicker()::read)
          .build();
      map = cache.asMap();
    } else if (mapType.equals("Caffeine w/o tolerance")) {
      Cache<Integer, Integer> cache = Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofNanos(EXPIRE_WRITE_TOLERANCE / 2))
          .initialCapacity(INITIAL_CAPACITY)
          .ticker(new FakeTicker()::read)
          .build();
      map = cache.asMap();
    } else if (mapType.equals("Guava")) {
      com.google.common.cache.Cache<Integer, Integer> cache = CacheBuilder.newBuilder()
          .expireAfterWrite(Duration.ofDays(1))
          .initialCapacity(INITIAL_CAPACITY)
          .ticker(new FakeTicker())
          .build();
      map = cache.asMap();
    } else {
      throw new AssertionError("Unknown mapType: " + mapType);
    }

    ints = new Integer[SIZE];
    NumberGenerator generator = new ScrambledZipfianGenerator(ITEMS);
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextValue().intValue();
      map.put(ints[i], ints[i]);
    }
  }

  @Benchmark @Threads(NUM_THREADS)
  public Integer put(ThreadState threadState) {
    var key = ints[threadState.index++ & MASK];
    return map.put(key, key);
  }

  @Benchmark @Threads(NUM_THREADS)
  public Integer replace(ThreadState threadState) {
    var key = ints[threadState.index++ & MASK];
    return map.replace(key, key);
  }

  @Benchmark @Threads(NUM_THREADS)
  public Boolean replaceConditionally(ThreadState threadState) {
    var key = ints[threadState.index++ & MASK];
    return map.replace(key, key, key);
  }

  @Benchmark @Threads(NUM_THREADS)
  public Integer compute(ThreadState threadState) {
    return map.compute(ints[threadState.index++ & MASK], (k, v) -> k);
  }

  @Benchmark @Threads(NUM_THREADS)
  public Integer computeIfPresent(ThreadState threadState) {
    return map.computeIfPresent(ints[threadState.index++ & MASK], (k, v) -> k);
  }

  @Benchmark @Threads(NUM_THREADS)
  public Integer merge(ThreadState threadState) {
    var key = ints[threadState.index++ & MASK];
    return map.merge(key, key, (k, v) -> k);
  }
}
