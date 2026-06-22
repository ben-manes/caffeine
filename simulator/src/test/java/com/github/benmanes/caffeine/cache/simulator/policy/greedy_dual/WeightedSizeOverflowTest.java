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
package com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * The cost-aware policies accumulate the cached weight, which the maximum size allows to exceed the
 * signed int range. These checks fill a multi-gigabyte cache with byte-sized entries and confirm
 * the weighted size still bounds the cache so eviction fires.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class WeightedSizeOverflowTest {
  private static final long MAXIMUM_SIZE = 3_000_000_000L;
  private static final int WEIGHT = 1_000_000_000;

  @Test
  void camp() {
    var policy = new CampPolicy(config());
    for (int key = 0; key < 4; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, WEIGHT));
    }
    assertThat(policy.stats().evictionCount()).isGreaterThan(0L);
  }

  @Test
  void gdwheel() {
    var policy = new GDWheelPolicy(config());
    for (int key = 0; key < 4; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, WEIGHT));
    }
    assertThat(policy.stats().evictionCount()).isGreaterThan(0L);
  }

  private static Config config() {
    var properties = Map.<String, Object>of("maximum-size", MAXIMUM_SIZE);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
