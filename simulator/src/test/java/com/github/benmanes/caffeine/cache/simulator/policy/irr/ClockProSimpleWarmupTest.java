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

import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * ClockProSimple is meant to track ClockPro. On a looping workload larger than the cache, ClockPro
 * warms up a hot set that survives the scan and scores hits; without that warm-up the simplified
 * port inserts every first access as cold, degrades to Clock, and never hits. This pins the
 * simplified port near its reference so the warm-up cannot regress.
 *
 * @author sahvx655@gmail.com (Sahana Bogar)
 */
final class ClockProSimpleWarmupTest {
  private static final int MAXIMUM_SIZE = 100;
  private static final int ITEMS = 250;
  private static final int LOOPS = 5;

  @Test
  void loopMatchesClockPro() {
    var reference = new ClockProPolicy(config());
    var simple = new ClockProSimplePolicy(config());
    for (int loop = 0; loop < LOOPS; loop++) {
      for (long key = 0; key < ITEMS; key++) {
        reference.record(key);
        simple.record(key);
      }
    }
    assertThat(simple.stats().hitCount()).isGreaterThan(0L);
    assertThat(simple.stats().hitCount()).isAtLeast(reference.stats().hitCount() / 2);
  }

  private static Config config() {
    var properties = Map.<String, Object>of("maximum-size", MAXIMUM_SIZE);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
