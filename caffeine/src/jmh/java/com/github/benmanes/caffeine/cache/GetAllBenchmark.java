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

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A benchmark of the bulk reads when every requested key is present, comparing
 * {@link Cache#getAll} with {@link Cache#getAllPresent} on the same batches of distinct keys.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew jmh -PincludePattern=GetAllBenchmark --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings({"CanonicalAnnotationSyntax", "JavadocDeclaration",
    "LexicographicalAnnotationAttributeListing", "NotNullFieldNotInitialized", "unused"})
public class GetAllBenchmark {
  private static final int SIZE = (2 << 16);
  private static final int BATCHES = (2 << 10);
  private static final int MASK = BATCHES - 1;

  @Param({"Bounded", "Unbounded"})
  String cacheType;

  @Param({"10", "100", "1000"})
  int batchSize;

  Cache<Integer, Integer> cache;
  List<List<Integer>> batches;

  @State(Scope.Thread)
  public static class ThreadState {
    int index;
  }

  @Setup
  public void setup() {
    if (cacheType.equals("Bounded")) {
      cache = Caffeine.newBuilder().maximumSize(2 * SIZE).build();
    } else if (cacheType.equals("Unbounded")) {
      cache = Caffeine.newBuilder().build();
    } else {
      throw new AssertionError("Unknown cacheType: " + cacheType);
    }
    for (int i = 0; i < SIZE; i++) {
      cache.put(i, i);
    }
    cache.cleanUp();

    var random = new Random(0);
    batches = random.ints(BATCHES).mapToObj(seed -> new Random(seed).ints(0, SIZE)
        .distinct().limit(batchSize).boxed().collect(Collectors.toUnmodifiableList()))
        .collect(Collectors.toUnmodifiableList());
  }

  @Benchmark
  public Map<Integer, Integer> getAll(ThreadState threadState) {
    return cache.getAll(batches.get(threadState.index++ & MASK), GetAllBenchmark::loadAll);
  }

  @Benchmark
  public Map<Integer, Integer> getAllPresent(ThreadState threadState) {
    return cache.getAllPresent(batches.get(threadState.index++ & MASK));
  }

  /** Fails the run on an absent key, which would measure a load instead of a read. */
  private static Map<Integer, Integer> loadAll(Set<? extends Integer> keys) {
    throw new AssertionError("Unexpected load of " + keys);
  }
}
