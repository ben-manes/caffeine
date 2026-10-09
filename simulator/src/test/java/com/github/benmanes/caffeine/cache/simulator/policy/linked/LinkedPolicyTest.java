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
package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LinkedPolicy.EvictionPolicy;
import com.typesafe.config.ConfigFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class LinkedPolicyTest {

  @Test
  void clock_allMarked_retainsNewcomer() {
    var config = ConfigFactory.parseMap(Map.of("maximum-size", 3))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    var policy = new LinkedPolicy(config, Set.of(), Admission.ALWAYS, EvictionPolicy.CLOCK);

    // When every resident is marked, second-chance clears the marks and evicts the oldest; the
    // newcomer is not a candidate, so its next request hits
    for (long key : new long[] {1, 2, 3, 1, 2, 3, 4, 4}) {
      policy.record(AccessEvent.forKey(key));
    }
    assertThat(policy.stats().hitCount()).isEqualTo(4);
  }
}
