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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;

import site.ycsb.generator.NumberGenerator;
import site.ycsb.generator.ScrambledZipfianGenerator;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class ComputeBenchmark {
  static final int SIZE = (2 << 14);
  static final int MASK = SIZE - 1;
  static final int ITEMS = SIZE / 3;
  static final Integer COMPUTE_KEY = SIZE / 2;
  static final Function<Integer, Boolean> mappingFunction = any -> Boolean.TRUE;
  static final CacheLoader<Integer, Boolean> cacheLoader = CacheLoader.from(key -> Boolean.TRUE);

  @Param({"ConcurrentHashMap", "Caffeine", "Guava"})
  String computeType;

  Function<Integer, Boolean> benchmarkFunction;
  Integer[] ints;

  @State(Scope.Thread)
  public static class ThreadState {
    static final Random random = new Random();
    int index = random.nextInt();
  }

  public ComputeBenchmark() {
    ints = new Integer[SIZE];
    NumberGenerator generator = new ScrambledZipfianGenerator(ITEMS);
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextValue().intValue();
    }
  }

  @Setup
  @SuppressWarnings("ReturnValueIgnored")
  public void setup() {
    if (computeType.equals("ConcurrentHashMap")) {
      setupConcurrentHashMap();
    } else if (computeType.equals("Caffeine")) {
      setupCaffeine();
    } else if (computeType.equals("Guava")) {
      setupGuava();
    } else {
      throw new AssertionError("Unknown computingType: " + computeType);
    }
    Arrays.stream(ints).forEach(benchmarkFunction::apply);
  }

  @Benchmark @Threads(32)
  public Boolean compute_sameKey(ThreadState threadState) {
    return benchmarkFunction.apply(COMPUTE_KEY);
  }

  @Benchmark @Threads(32)
  public Boolean compute_spread(ThreadState threadState) {
    return benchmarkFunction.apply(ints[threadState.index++ & MASK]);
  }

  private void setupConcurrentHashMap() {
    ConcurrentMap<Integer, Boolean> map = new ConcurrentHashMap<>();
    benchmarkFunction = key -> map.computeIfAbsent(key, mappingFunction);
  }

  private void setupCaffeine() {
    Cache<Integer, Boolean> cache = Caffeine.newBuilder().build();
    benchmarkFunction = key -> cache.get(key, mappingFunction);
  }

  private void setupGuava() {
    com.google.common.cache.LoadingCache<Integer, Boolean> cache =
        CacheBuilder.newBuilder().concurrencyLevel(64).build(cacheLoader);
    benchmarkFunction = cache::getUnchecked;
  }
}
