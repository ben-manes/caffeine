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

import java.util.concurrent.ThreadLocalRandom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.github.benmanes.caffeine.cache.sketch.TinyLfuSketch;

import site.ycsb.generator.ScrambledZipfianGenerator;

/**
 * <pre>{@code
 *   ./gradlew jmh -PincludePattern=FrequencySketchBenchmark
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings("LexicographicalAnnotationAttributeListing")
public class FrequencySketchBenchmark {
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;
  private static final int ITEMS = SIZE / 3;

  @Param({"Flat", "Block"})
  SketchType sketchType;

  @Param({"32768", "524288", "8388608", "134217728"})
  int tableSize;

  TinyLfuSketch<Integer> sketch;
  Integer[] ints;
  int index;

  @Setup
  public void setup() {
    var generator = new ScrambledZipfianGenerator(ITEMS);
    sketch = sketchType.create(tableSize);
    ints = new Integer[SIZE];
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextValue().intValue();
      sketch.increment(ints[i]);
    }
    for (int i = 0; i < 5 * SIZE; i++) {
      sketch.increment(ThreadLocalRandom.current().nextInt());
    }
  }

  @Benchmark
  public void increment() {
    sketch.increment(ints[index++ & MASK]);
  }

  @Benchmark
  public int frequency() {
    return sketch.frequency(ints[index++ & MASK]);
  }

  @Benchmark
  public void reset() {
    sketch.reset();
  }
}
