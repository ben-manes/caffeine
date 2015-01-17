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
package com.github.benmanes.caffeine.cache.simulator;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.generator.CounterGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.Generator;
import com.github.benmanes.caffeine.cache.simulator.generator.ScrambledZipfianGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.ZipfianGenerator;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent;
import com.github.benmanes.caffeine.cache.tracing.CacheEvent.Action;

/**
 * A generator of synthetic working sets to simulate different caching patterns.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Synthetic {

  private Synthetic() {}

  /**
   * Returns a sequence of events where some items are more popular than others, according to a
   * zipfian distribution. Unlike {@link #zipfian}, the generated sequence scatters the "popular"
   * items across the item space. Use if you don't want the head of the distribution (the popular
   * items) clustered together.
   *
   * @param items the number of items in the distribution
   */
  public static Stream<CacheEvent> scrambledZipfian(int items) {
    return generate(new ScrambledZipfianGenerator(items), items);
  }

  public static Stream<CacheEvent> zipfian(int items) {
    return generate(new ZipfianGenerator(items), items);
  }

  /** Generates a sequence of unique integers. */
  public static Stream<CacheEvent> counter(int items) {
    return generate(new CounterGenerator(items), items);
  }

  private static Stream<CacheEvent> generate(Generator generator, int items) {
    return IntStream.range(0, items).mapToObj(ignored ->
      new CacheEvent(0, Action.READ_OR_CREATE, generator.nextString().hashCode(), 0L));
  }
}
