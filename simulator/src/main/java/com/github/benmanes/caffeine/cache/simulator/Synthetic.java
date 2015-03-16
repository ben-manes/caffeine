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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings.SyntheticSource.Hotspot;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.SyntheticSource.Uniform;
import com.github.benmanes.caffeine.cache.simulator.generator.CounterGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.ExponentialGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.Generator;
import com.github.benmanes.caffeine.cache.simulator.generator.HotspotIntegerGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.ScrambledZipfianGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.SkewedLatestGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.UniformIntegerGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.ZipfianGenerator;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent.Action;

/**
 * A generator of synthetic cache events to simulate different caching patterns.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Synthetic {

  private Synthetic() {}

  /** Returns a sequence of events based on the setting's distribution. */
  public static Stream<TraceEvent> generate(BasicSettings settings) {
    int items = settings.synthetic().events();
    switch (settings.synthetic().distribution().toLowerCase()) {
      case "counter":
        return counter(settings.synthetic().counter().start(), items);
      case "exponential":
        return exponential(settings.synthetic().exponential().mean(), items);
      case "hotpot":
        Hotspot hotspot = settings.synthetic().hotspot();
        return Synthetic.hotspot(hotspot.lowerBound(), hotspot.upperBound(),
            hotspot.hotOpnFraction(), hotspot.hotsetFraction(), items);
      case "zipfian":
        return zipfian(items);
      case "scrambled-zipfian":
        return scrambledZipfian(items);
      case "skewed-zipfian-latest":
        return skewedZipfianLatest(items);
      case "uniform":
        Uniform uniform = settings.synthetic().uniform();
        return uniform(uniform.lowerBound(), uniform.upperBound(), items);
      default:
        throw new IllegalStateException("Unknown distribution: "
            + settings.synthetic().distribution());
    }
  }

  /**
   * Returns a sequence of unique integers.
   *
   * @param start the number that the counter starts from
   * @param items the number of items in the distribution
   */
  public static Stream<TraceEvent> counter(int start, int items) {
    return generate(new CounterGenerator(start), items);
  }

  /**
   * Returns a sequence of events based on an exponential distribution. Smaller intervals are more
   * frequent than larger ones, and there is no bound on the length of an interval.
   *
   * @param mean mean arrival rate of gamma (a half life of 1/gamma)
   * @param items the number of items in the distribution
   */
  public static Stream<TraceEvent> exponential(double mean, int items) {
    return generate(new ExponentialGenerator(mean), items);
  }

  /**
   * Returns a sequence of events resembling a hotspot distribution where x% of operations access y%
   * of data items. The parameters specify the bounds for the numbers, the percentage of the of the
   * interval which comprises the hot set and the percentage of operations that access the hot set.
   * Numbers of the hot set are always smaller than any number in the cold set. Elements from the
   * hot set and the cold set are chose using a uniform distribution.
   *
   * @param lowerBound lower bound of the distribution
   * @param upperBound upper bound of the distribution
   * @param hotsetFraction percentage of data item
   * @param hotOpnFraction percentage of operations accessing the hot set
   * @param items the number of items in the distribution
   */
  public static Stream<TraceEvent> hotspot(int lowerBound, int upperBound,
      double hotsetFraction, double hotOpnFraction, int items) {
    return generate(new HotspotIntegerGenerator(lowerBound,
        upperBound, hotsetFraction, hotOpnFraction), items);
  }

  /**
   * Returns a sequence of events where some items are more popular than others, according to a
   * zipfian distribution. Unlike {@link #zipfian}, the generated sequence scatters the "popular"
   * items across the item space. Use if you don't want the head of the distribution (the popular
   * items) clustered together.
   *
   * @param items the number of items in the distribution
   */
  public static Stream<TraceEvent> scrambledZipfian(int items) {
    return generate(new ScrambledZipfianGenerator(items), items);
  }

  /**
   * Returns a zipfian sequence with a popularity distribution of items, skewed to favor recent
   * items significantly more than older items
   *
   * @param items the number of items in the distribution
   */
  public static Stream<TraceEvent> skewedZipfianLatest(int items) {
    return generate(new SkewedLatestGenerator(new CounterGenerator(items)), items);
  }

  /**
   * Returns a sequence of events where some items are more popular than others, according to a
   * zipfian distribution.
   *
   * @param items the number of items in the distribution
   */
  public static Stream<TraceEvent> zipfian(int items) {
    return generate(new ZipfianGenerator(items), items);
  }

  /**
   * Returns a sequence of events where items are selected uniformly randomly from the interval
   * inclusively.
   *
   * @param lowerBound lower bound of the distribution
   * @param upperBound upper bound of the distribution
   * @param items the number of items in the distribution
   * @return a stream of cache events
   */
  public static Stream<TraceEvent> uniform(int lowerBound, int upperBound, int items) {
    return generate(new UniformIntegerGenerator(lowerBound, upperBound), items);
  }

  /** Returns a sequence of items constructed by the generator. */
  private static Stream<TraceEvent> generate(Generator generator, int items) {
    return IntStream.range(0, items).mapToObj(ignored ->
      new TraceEvent(null, 0, Action.WRITE, generator.nextString().hashCode(), 1, 0L));
  }
}
