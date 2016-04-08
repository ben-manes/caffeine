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
package com.github.benmanes.caffeine.cache;

import static java.util.function.Function.identity;

import java.io.PrintStream;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.github.jamm.MemoryMeter;
import org.github.jamm.MemoryMeter.Guess;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.math.LongMath;
import com.jakewharton.fliptables.FlipTable;

/**
 * A non-JMH benchmark to compare the memory overhead of different cache implementations. Note that
 * the measurements estimate based on the current JVM configuration, e.g. 64-bit with compressed
 * references if the benchmark is executed with a heap under 32GB. This can means that object
 * padding may or may not have a visible effect.
 * <p>
 * This benchmark requires a JavaAgent to evaluate the object sizes and can be executed using
 * <tt>gradle -q memoryOverhead</tt>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MemoryBenchmark {
  // The number of entries added to minimize skew due to non-entry factors
  static final int FUZZY_SIZE = 25_000;
  // The maximum size, which is larger than the fuzzy factor due to Guava's early eviction
  static final int MAXIMUM_SIZE = 2 * FUZZY_SIZE;
  // The pre-computed entries to store into the cache when computing the per-entry overhead
  static final Map<Integer, Integer> workingSet = IntStream.range(0, FUZZY_SIZE)
      .boxed().collect(Collectors.toMap(identity(), i -> -i));

  final MemoryMeter meter = new MemoryMeter()
      .withGuessing(Guess.FALLBACK_BEST)
      .ignoreKnownSingletons();
  final PrintStream out = System.out;

  public void run() throws Exception {
    if (!MemoryMeter.hasInstrumentation()) {
      out.println("WARNING: Java agent not installed - guessing instead");
    }
    out.println();
    unbounded();
    maximumSize();
    maximumSize_expireAfterAccess();
    maximumSize_expireAfterWrite();
    maximumSize_refreshAfterWrite();
    maximumWeight();
    expireAfterAccess();
    expireAfterWrite();
    expireAfterAccess_expireAfterWrite();
    weakKeys();
    weakValues();
    weakKeys_weakValues();
    weakKeys_softValues();
    softValues();
  }

  private Caffeine<Object, Object> builder() {
    // Avoid counting ForkJoinPool in estimates
    return Caffeine.newBuilder().executor(Runnable::run);
  }

  private void unbounded() {
    Cache<Integer, Integer> caffeine = builder().build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder().build();
    compare("Unbounded", caffeine, guava);
  }

  private void maximumSize() {
    Cache<Integer, Integer> caffeine = builder().maximumSize(MAXIMUM_SIZE).build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .maximumSize(MAXIMUM_SIZE).build();
    compare("Maximum Size", caffeine, guava);
  }

  private void maximumWeight() {
    Cache<Integer, Integer> caffeine = builder()
        .maximumWeight(MAXIMUM_SIZE).weigher((k, v) -> 1).build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .maximumWeight(MAXIMUM_SIZE).weigher((k, v) -> 1).build();
    compare("Maximum Weight", caffeine, guava);
  }

  private void maximumSize_expireAfterAccess() {
    Cache<Integer, Integer> caffeine = builder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(MAXIMUM_SIZE)
        .build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(MAXIMUM_SIZE)
        .build();
    compare("Maximum Size & Expire after Access", caffeine, guava);
  }

  private void maximumSize_expireAfterWrite() {
    Cache<Integer, Integer> caffeine = builder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(MAXIMUM_SIZE)
        .build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(MAXIMUM_SIZE)
        .build();
    compare("Maximum Size & Expire after Write", caffeine, guava);
  }

  private void maximumSize_refreshAfterWrite() {
    Cache<Integer, Integer> caffeine = builder()
        .refreshAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(MAXIMUM_SIZE)
        .build(k -> k);
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .refreshAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(MAXIMUM_SIZE)
        .build(new CacheLoader<Integer, Integer>() {
          @Override public Integer load(Integer key) {
            return key;
          }
        });
    compare("Maximum Size & Refresh after Write", caffeine, guava);
  }

  private void expireAfterAccess() {
    Cache<Integer, Integer> caffeine = builder()
        .expireAfterAccess(1, TimeUnit.MINUTES).build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES).build();
    compare("Expire after Access", caffeine, guava);
  }

  private void expireAfterWrite() {
    Cache<Integer, Integer> caffeine = builder()
        .expireAfterWrite(1, TimeUnit.MINUTES).build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES).build();
    compare("Expire after Write", caffeine, guava);
  }

  private void expireAfterAccess_expireAfterWrite() {
    Cache<Integer, Integer> caffeine = builder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();
    compare("Expire after Access & after Write", caffeine, guava);
  }

  private void weakKeys() {
    Cache<Integer, Integer> caffeine = builder().weakKeys().build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .weakKeys().build();
    compare("Weak Keys", caffeine, guava);
  }

  private void weakValues() {
    Cache<Integer, Integer> caffeine = builder().weakValues().build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .weakValues().build();
    compare("Weak Values", caffeine, guava);
  }

  private void weakKeys_weakValues() {
    Cache<Integer, Integer> caffeine = builder().weakKeys().weakValues().build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .weakKeys().weakValues().build();
    compare("Weak Keys & Weak Values", caffeine, guava);
  }

  private void weakKeys_softValues() {
    Cache<Integer, Integer> caffeine = builder().weakKeys().softValues().build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .weakKeys().softValues().build();
    compare("Weak Keys & Soft Values", caffeine, guava);
  }

  private void softValues() {
    Cache<Integer, Integer> caffeine = builder().softValues().build();
    com.google.common.cache.Cache<Integer, Integer> guava = CacheBuilder.newBuilder()
        .softValues().build();
    compare("Soft Values", caffeine, guava);
  }

  private void compare(String label, Cache<Integer, Integer> caffeine,
      com.google.common.cache.Cache<Integer, Integer> guava) {
    caffeine.cleanUp();
    guava.cleanUp();

    int leftPadded = Math.max((36 - label.length()) / 2 - 1, 1);
    out.printf(" %2$-" + leftPadded + "s %s%n", label, " ");
    String result = FlipTable.of(new String[] { "Cache", "Baseline", "Per Entry" },new String[][] {
        evaluate("Caffeine", caffeine.asMap()),
        evaluate("Guava", guava.asMap())
    });
    out.println(result);
  }

  private String[] evaluate(String label, Map<Integer, Integer> map) {
    long base = meter.measureDeep(map);
    map.putAll(workingSet);

    long populated = meter.measureDeep(map);
    long entryOverhead = 2 * FUZZY_SIZE * meter.measureDeep(workingSet.keySet().iterator().next());
    long perEntry = LongMath.divide(populated - entryOverhead - base,
        FUZZY_SIZE, RoundingMode.HALF_EVEN);
    perEntry += ((perEntry & 1) == 0) ? 0 : 1;
    long aligned = ((perEntry % 8) == 0) ? perEntry : ((1 + perEntry / 8) * 8);
    return new String[] {
        label,
        String.format("%,d bytes", base),
        String.format("%,d bytes (%,d aligned)", perEntry, aligned)
    };
  }

  public static void main(String[] args) throws Exception {
    new MemoryBenchmark().run();
  }
}
