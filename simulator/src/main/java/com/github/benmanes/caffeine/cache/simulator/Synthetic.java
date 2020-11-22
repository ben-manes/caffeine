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

import static java.util.Locale.US;

import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings.SyntheticSettings.HotspotSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.SyntheticSettings.UniformSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.TraceSettings;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;

import site.ycsb.generator.CounterGenerator;
import site.ycsb.generator.ExponentialGenerator;
import site.ycsb.generator.HotspotIntegerGenerator;
import site.ycsb.generator.NumberGenerator;
import site.ycsb.generator.ScrambledZipfianGenerator;
import site.ycsb.generator.SkewedLatestGenerator;
import site.ycsb.generator.UniformLongGenerator;
import site.ycsb.generator.ZipfianGenerator;

/**
 * A generator of synthetic cache events to simulate different caching patterns.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Synthetic {

  private Synthetic() {}

  /** Returns a sequence of events based on the setting's distribution. */
  public static KeyOnlyTraceReader generate(TraceSettings settings) {
    int events = settings.synthetic().events();
    switch (settings.synthetic().distribution().toLowerCase(US)) {
      case "counter":
        return () -> counter(settings.synthetic().counter().start(), events);
      case "exponential":
        return () -> exponential(settings.synthetic().exponential().mean(), events);
      case "hotspot":
        HotspotSettings hotspot = settings.synthetic().hotspot();
        return () -> Synthetic.hotspot(hotspot.lowerBound(), hotspot.upperBound(),
            hotspot.hotOpnFraction(), hotspot.hotsetFraction(), events);
      case "zipfian":
        return () -> zipfian(settings.synthetic().zipfian().items(),
            settings.synthetic().zipfian().constant(), events);
      case "scrambled-zipfian":
        return () -> scrambledZipfian(settings.synthetic().zipfian().items(),
            settings.synthetic().zipfian().constant(), events);
      case "skewed-zipfian-latest":
        return () -> skewedZipfianLatest(settings.synthetic().zipfian().items(), events);
      case "uniform":
        UniformSettings uniform = settings.synthetic().uniform();
        return () -> uniform(uniform.lowerBound(), uniform.upperBound(), events);
      default:
        throw new IllegalStateException("Unknown distribution: "
            + settings.synthetic().distribution());
    }
  }

  /**
   * Returns a sequence of unique integers.
   *
   * @param start the number that the counter starts from
   * @param events the number of events in the distribution
   */
  public static LongStream counter(int start, int events) {
    return generate(new CounterGenerator(start), events);
  }

  /**
   * Returns a sequence of events based on an exponential distribution. Smaller intervals are more
   * frequent than larger ones, and there is no bound on the length of an interval.
   *
   * @param mean mean arrival rate of gamma (a half life of 1/gamma)
   * @param events the number of events in the distribution
   */
  public static LongStream exponential(double mean, int events) {
    return generate(new ExponentialGenerator(mean), events);
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
   * @param events the number of events in the distribution
   */
  public static LongStream hotspot(int lowerBound, int upperBound,
      double hotsetFraction, double hotOpnFraction, int events) {
    return generate(new HotspotIntegerGenerator(lowerBound,
        upperBound, hotsetFraction, hotOpnFraction), events);
  }

  /**
   * Returns a sequence of events where some items are more popular than others, according to a
   * zipfian distribution. Unlike {@link #zipfian}, the generated sequence scatters the "popular"
   * items across the item space. Use if you don't want the head of the distribution (the popular
   * items) clustered together.
   *
   * @param items the number of items in the distribution
   * @param constant the skew factor for the distribution
   * @param events the number of events in the distribution
   */
  public static LongStream scrambledZipfian(int items, double constant, int events) {
    return generate(new ScrambledZipfianGenerator(0, items - 1, constant), events);
  }

  /**
   * Returns a zipfian sequence with a popularity distribution of items, skewed to favor recent
   * items significantly more than older items
   *
   * @param items the number of items in the distribution
   * @param events the number of events in the distribution
   */
  public static LongStream skewedZipfianLatest(int items, int events) {
    return generate(new SkewedLatestGenerator(new CounterGenerator(items)), events);
  }

  /**
   * Returns a sequence of events where some items are more popular than others, according to a
   * zipfian distribution.
   *
   * @param items the number of items in the distribution
   * @param constant the skew factor for the distribution
   * @param events the number of events in the distribution
   */
  public static LongStream zipfian(int items, double constant, int events) {
    return generate(new ZipfianGenerator(items, constant), events);
  }

  /**
   * Returns a sequence of events where items are selected uniformly randomly from the interval
   * inclusively.
   *
   * @param lowerBound lower bound of the distribution
   * @param upperBound upper bound of the distribution
   * @param events the number of events in the distribution
   * @return a stream of cache events
   */
  public static LongStream uniform(int lowerBound, int upperBound, int events) {
    return generate(new UniformLongGenerator(lowerBound, upperBound), events);
  }

  /** Returns a sequence of items constructed by the generator. */
  private static LongStream generate(NumberGenerator generator, long count) {
    return LongStream.range(0, count).map(ignored -> generator.nextValue().longValue());
  }
}
