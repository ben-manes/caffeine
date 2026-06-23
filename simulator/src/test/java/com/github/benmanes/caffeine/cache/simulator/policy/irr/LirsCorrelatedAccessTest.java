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
 * LIRS treats a rapid re-access to the same block (a "correlated reference") as a single event, the
 * convention its siblings Lirs2 and ClockPro already follow. These checks confirm a consecutive
 * duplicate is collapsed while a genuine non-consecutive reuse is still counted.
 *
 * @author sahvx655@gmail.com (Sahana Bogar)
 */
final class LirsCorrelatedAccessTest {

  @Test
  void skipsConsecutiveDuplicate() {
    var policy = new LirsPolicy(config());
    policy.record(1);
    policy.record(1);
    assertThat(policy.stats().hitCount()).isEqualTo(0);
  }

  @Test
  void countsNonConsecutiveReuse() {
    var policy = new LirsPolicy(config());
    policy.record(1);
    policy.record(2);
    policy.record(1);
    assertThat(policy.stats().hitCount()).isEqualTo(1);
  }

  private static Config config() {
    var properties = Map.<String, Object>of("maximum-size", 10);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
