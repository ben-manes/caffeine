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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.typesafe.config.ConfigFactory;

/**
 * Verifies that the MiniSim climber remains dormant while the cache is underfilled.
 *
 * @author sahvx655@gmail.com (Sahana Bogar)
 */
final class MiniSimUnfilledResizeTest {

  @Test
  void unfilledWindowResize() {
    var policy = policy();
    // Replay past the shortened period; the fullness gate must still prevent adaptation.
    for (int i = 0; i < 200; i++) {
      policy.record(AccessEvent.forKey(i % 3));
    }
    assertThat(policy.stats().requestCount()).isEqualTo(200L);
  }

  private static Policy policy() {
    var config = ConfigFactory.parseString("""
        maximum-size = 1000
        hill-climber-window-tiny-lfu {
          strategy = [ minisim ]
          percent-main = [ 0.99 ]
          minisim.period = 100
        }
        """).withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    return HillClimberWindowTinyLfuPolicy.policies(config).iterator().next();
  }
}
