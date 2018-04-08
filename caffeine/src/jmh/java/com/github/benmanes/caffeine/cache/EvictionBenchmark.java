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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * A benchmark that evaluates the eviction performance of a cache. The cache is pre-populated for
 * a 100% eviction rate to mimic worst case behavior.
 * <p>
 * <pre>{@code
 *   ./gradlew jmh -PincludePattern=EvictionBenchmark
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class EvictionBenchmark {

  @Param({
    "LinkedHashMap_Lru",
    "Caffeine",
    "Ehcache3",
  })
  CacheType cacheType;

  @Param({"0", "100", "10000", "1000000", "10000000"})
  int size;

  BasicCache<Integer, Boolean> cache;

  @State(Scope.Thread)
  public static class ThreadState {
    int key = 0;
  }

  @Setup
  public void setup() {
    cache = cacheType.create(size);
    for (int i = 0; i < size; i++) {
      cache.put(Integer.MIN_VALUE + i, Boolean.TRUE);
    }
  }

  @TearDown(Level.Iteration)
  public void tearDown() {
    cache.cleanUp();
  }

  @Benchmark
  public void evict(ThreadState threadState) {
    cache.put(threadState.key++, Boolean.TRUE);
  }
}
