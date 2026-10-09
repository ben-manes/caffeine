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
package com.github.benmanes.caffeine.cache.simulator.policy.sampled;

import static com.google.common.truth.Truth.assertThat;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy.EvictionPolicy;
import com.typesafe.config.ConfigFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class SampledPolicyTest {

  @ParameterizedTest
  @EnumSource
  void evict_retainsNewcomer(EvictionPolicy evictionPolicy) {
    var config = ConfigFactory.parseMap(Map.of("maximum-size", 8))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    var policy = new SampledPolicy(Admission.ALWAYS, evictionPolicy, config);

    // Each new key evicts a resident, so its immediate re-request hits only if the victim is
    // never the entry just inserted
    for (long key = 0; key < 1_000; key++) {
      policy.record(key);
      policy.record(key);
    }
    assertThat(policy.stats().hitCount()).isEqualTo(1_000);
  }
}
