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
package com.github.benmanes.caffeine.cache.simulator.policy.irr;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.google.common.collect.Streams;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * ClockPro faults exactly as the author's reference implementation, {@code clock-pro.c}, on the
 * bundled LIRS traces. The expected counts are the reference's page faults with its fixed 5% cold
 * allocation and a non-resident bound of the cache size.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ClockProReferenceTest {
  private static final int[] SIZES = { 500, 1000, 2000 };

  @ParameterizedTest
  @MethodSource("references")
  void misses_matchReference(String trace, int maximumSize, long misses) {
    var policy = new ClockProPolicy(config(maximumSize));
    try (var keys = new LirsTraceReader(trace + ".trace.gz").keys()) {
      keys.forEach(policy::record);
    }
    policy.finished();
    assertThat(policy.stats().missCount()).isEqualTo(misses);
  }

  static Stream<Object[]> references() {
    return Streams.concat(
        misses("2_pools", 48_078, 45_665, 40_856),
        misses("backf", 6229, 751, 751),
        misses("cpp", 1269, 1226, 1223),
        misses("cs", 4873, 2903, 1409),
        misses("gli", 4098, 3004, 2529),
        misses("loop", 268_475, 31_450, 1011),
        misses("multi1", 7049, 5151, 2648),
        misses("multi2", 13_280, 11_389, 7841),
        misses("multi3", 18_060, 14_558, 11_600),
        misses("ps", 4557, 3607, 3083),
        misses("scan", 38_625, 27_225, 4425),
        misses("sprite", 31_061, 15_534, 10_384),
        misses("zigzag", 51_401, 50_776, 3115));
  }

  private static Stream<Object[]> misses(String trace, long... misses) {
    return IntStream.range(0, SIZES.length)
        .mapToObj(i -> new Object[] { trace, SIZES[i], misses[i] });
  }

  private static Config config(int maximumSize) {
    var properties = Map.<String, Object>of(
        "maximum-size", maximumSize,
        "clockpro.percent-min-resident-cold", 0.05,
        "clockpro.percent-max-resident-cold", 0.05,
        "clockpro.lower-bound-resident-cold", 2,
        "clockpro.non-resident-multiplier", 1.0,
        "clockpro.cold-hand", "replace");
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
