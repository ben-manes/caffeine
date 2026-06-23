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
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * A correlated reference — the same key accessed twice in a row — is collapsed to a single logical
 * access in the LIRS / CLOCK-Pro family, following Song Jiang's and Chen Zhong's reference
 * implementations. Those references skip the duplicate's algorithm step but increment the
 * hit-rate denominator before doing so, so the duplicate counts as a non-miss (a hit), never a
 * second miss and never a dropped event. The policies that must short-circuit (a non-idempotent
 * role swap or coldTarget adaption) therefore score the repeat as a hit rather than dropping it.
 */
final class CorrelatedAccessTest {

  @Test
  void lirs() {
    assertCorrelatedReferenceIsAHit(LirsPolicy::new);
  }

  @Test
  void lirs2() {
    assertCorrelatedReferenceIsAHit(Lirs2Policy::new);
  }

  @Test
  void clockPro() {
    assertCorrelatedReferenceIsAHit(ClockProPolicy::new);
  }

  @Test
  void clockProPlus() {
    assertCorrelatedReferenceIsAHit(ClockProPlusPolicy::new);
  }

  @Test
  void clockProSimple() {
    assertCorrelatedReferenceIsAHit(ClockProSimplePolicy::new);
  }

  private static void assertCorrelatedReferenceIsAHit(Function<Config, KeyOnlyPolicy> factory) {
    // A back-to-back repeat is one miss then one hit (counted, not dropped from the denominator).
    var consecutive = factory.apply(config());
    consecutive.record(1);
    consecutive.record(1);
    assertThat(consecutive.stats().hitCount()).isEqualTo(1);
    assertThat(consecutive.stats().requestCount()).isEqualTo(2);

    // A non-consecutive reuse is a genuine hit and is unaffected by the collapse.
    var reuse = factory.apply(config());
    reuse.record(1);
    reuse.record(2);
    reuse.record(1);
    assertThat(reuse.stats().hitCount()).isEqualTo(1);
  }

  private static Config config() {
    return ConfigFactory.parseMap(Map.of("maximum-size", 100))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
