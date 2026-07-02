/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import java.util.Random;
import java.util.concurrent.CompletableFuture;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import site.ycsb.generator.ScrambledZipfianGenerator;

/**
 * The asynchronous counterpart to {@link GetPutBenchmark}, evaluating the read/write performance of
 * an {@link AsyncLoadingCache}. The cache is prepopulated for a 100% hit rate and a Zipf
 * distribution of keys is used to mimic application usage patterns.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew jmh -PincludePattern=AsyncGetPutBenchmark -PjavaVersion=21 --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Group)
@SuppressWarnings({"CanonicalAnnotationSyntax", "IdentifierName",
    "LexicographicalAnnotationAttributeListing", "JavadocDeclaration",
    "NotNullFieldNotInitialized", "PMD.MethodNamingConventions", "unused"})
public class AsyncGetPutBenchmark {
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;
  private static final int ITEMS = SIZE / 3;
  private static final CompletableFuture<Boolean> PRESENT =
      CompletableFuture.completedFuture(Boolean.TRUE);

  AsyncLoadingCache<Integer, Boolean> cache;
  Integer[] ints;

  @State(Scope.Thread)
  public static class ThreadState {
    static final Random random = new Random();
    int index = random.nextInt();
  }

  @Setup
  public void setup() {
    ints = new Integer[SIZE];
    cache = Caffeine.newBuilder()
        .maximumSize(2 * SIZE)
        .initialCapacity(2 * SIZE)
        .buildAsync(key -> Boolean.TRUE);

    // Enforce full initialization of internal structures
    for (int i = 0; i < 2 * SIZE; i++) {
      cache.put(i, PRESENT);
    }
    cache.synchronous().invalidateAll();

    // Populate using a realistic access distribution
    var generator = new ScrambledZipfianGenerator(ITEMS);
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextValue().intValue();
      cache.put(ints[i], PRESENT);
    }
  }

  @TearDown(Level.Iteration)
  public void tearDown() {
    cache.synchronous().cleanUp();
  }

  @Benchmark @Group("read_only") @GroupThreads(8)
  public CompletableFuture<Boolean> readOnly(ThreadState threadState) {
    return cache.get(ints[threadState.index++ & MASK]);
  }

  @Benchmark @Group("write_only") @GroupThreads(8)
  public void writeOnly(ThreadState threadState) {
    cache.put(ints[threadState.index++ & MASK], PRESENT);
  }

  @Benchmark @Group("readwrite") @GroupThreads(6)
  public CompletableFuture<Boolean> readwrite_get(ThreadState threadState) {
    return cache.get(ints[threadState.index++ & MASK]);
  }

  @Benchmark @Group("readwrite") @GroupThreads(2)
  public void readwrite_put(ThreadState threadState) {
    cache.put(ints[threadState.index++ & MASK], PRESENT);
  }
}
