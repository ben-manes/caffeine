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
package com.github.benmanes.caffeine.cache.simulator.policy.product;

import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.product.CoherencePolicy.CoherenceSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.product.CoherencePolicy.Eviction;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Some product caches rank their eviction victims by when each entry was last used. These checks
 * replay the same trace with and without pauses between its phases and confirm that the hits
 * agree, so a row does not depend on how fast the simulator replays it.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ReplaySpeedTest {

  @ParameterizedTest
  @MethodSource("policies")
  void hits_independentOfReplaySpeed(Function<Config, Policy> factory)
      throws InterruptedException {
    long paced = replay(factory, /* pause= */ true);
    long unpaced = replay(factory, /* pause= */ false);
    assertThat(unpaced).isEqualTo(paced);
  }

  /**
   * Returns the hits from inserting an older pair and a newer pair, overflowing the cache, and then
   * reading the newer pair, optionally pausing between the phases.
   */
  private static long replay(Function<Config, Policy> factory, boolean pause)
      throws InterruptedException {
    var config = ConfigFactory.parseMap(Map.of("maximum-size", 4))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    var policy = factory.apply(config);
    long[][] phases = { {3, 4}, {1, 2}, {5}, {1, 2} };
    for (long[] phase : phases) {
      for (long key : phase) {
        policy.record(AccessEvent.forKey(key));
      }
      if (pause) {
        Thread.sleep(Duration.ofMillis(2));
      }
    }
    policy.finished();
    return policy.stats().hitCount();
  }

  static Stream<Named<Function<Config, Policy>>> policies() {
    return Stream.of(
        Named.of("Coherence (Hybrid)", config -> coherence(config, Eviction.HYBRID)),
        Named.of("Coherence (Lfu)", config -> coherence(config, Eviction.LFU)),
        Named.of("Coherence (Lru)", config -> coherence(config, Eviction.LRU)),
        Named.of("Ehcache3", Ehcache3Policy::new));
  }

  private static Policy coherence(Config config, Eviction eviction) {
    return new CoherencePolicy(new CoherenceSettings(config), eviction);
  }
}
