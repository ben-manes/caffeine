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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.truth.Truth.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.typesafe.config.ConfigFactory;

/**
 * The boundary properties of the weighted form: region weights are conserved and the capacity is
 * honored (asserted by {@code finished}), an entry that can never fit is discarded, an entry too
 * heavy for the protected region is not promoted into it, and an unweighted trace ignores the
 * event weight.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class WindowTinyLfuPolicyTest {

  @Test
  void weighted_oversizedEntry_isDiscarded() {
    var policy = policy(Set.of(WEIGHTED), 100);
    policy.record(AccessEvent.forKeyAndWeight(1, 101));
    policy.record(AccessEvent.forKeyAndWeight(1, 101));
    policy.finished();

    assertThat(policy.stats().hitCount()).isEqualTo(0);
    assertThat(policy.stats().missCount()).isEqualTo(2);
    assertThat(policy.stats().evictionCount()).isEqualTo(2);
  }

  @Test
  void weighted_oversizedCandidate_doesNotDisplaceResidents() {
    var policy = policy(Set.of(WEIGHTED), 100);
    for (int key = 1; key <= 9; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, 10));
      policy.record(AccessEvent.forKeyAndWeight(key, 10));
    }
    long misses = policy.stats().missCount();

    // the candidate cannot fit, so the admission filter discards it rather than the victims
    policy.record(AccessEvent.forKeyAndWeight(100, 101));
    policy.finished();
    assertThat(policy.stats().evictionCount()).isEqualTo(1);

    policy.record(AccessEvent.forKeyAndWeight(100, 101));
    assertThat(policy.stats().missCount()).isEqualTo(misses + 2);
  }

  @Test
  void weighted_oversizedPromotion_keepsProtectedIntact() {
    var policy = policy(Set.of(WEIGHTED), 100);
    for (int key = 1; key <= 2; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, 10));
      policy.record(AccessEvent.forKeyAndWeight(key, 10));
    }

    // a probation hit on an entry heavier than the protected maximum must not promote it, else
    // the demotions flush the protected region and its residents become the next eviction victims
    policy.record(AccessEvent.forKeyAndWeight(9, 80));
    policy.record(AccessEvent.forKeyAndWeight(9, 80));
    policy.record(AccessEvent.forKeyAndWeight(20, 10));
    policy.record(AccessEvent.forKeyAndWeight(1, 10));
    policy.finished();

    assertThat(policy.stats().hitCount()).isEqualTo(4);
    assertThat(policy.stats().missCount()).isEqualTo(4);
    assertThat(policy.stats().evictionCount()).isEqualTo(1);
  }

  @Test
  void weighted_update_evictsBackUnderCapacity() {
    var policy = policy(Set.of(WEIGHTED), 100);
    for (int key = 1; key <= 6; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, 15));
      policy.record(AccessEvent.forKeyAndWeight(key, 15));
    }

    // growing a resident forces evictions until the cache fits again
    policy.record(AccessEvent.forKeyAndWeight(1, 60));
    policy.finished();
    assertThat(policy.stats().evictionCount()).isAtLeast(1);
  }

  @Test
  void unweighted_ignoresTheEventWeight() {
    var policy = policy(Set.of(), 3);
    for (int key = 1; key <= 3; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, 1000));
    }
    for (int key = 1; key <= 3; key++) {
      policy.record(AccessEvent.forKeyAndWeight(key, 1000));
    }
    policy.finished();

    assertThat(policy.stats().hitCount()).isEqualTo(3);
    assertThat(policy.stats().missCount()).isEqualTo(3);
    assertThat(policy.stats().evictionCount()).isEqualTo(0);
  }

  private static WindowTinyLfuPolicy policy(Set<Characteristic> characteristics, long maximum) {
    var config = ConfigFactory.parseMap(Map.of("maximum-size", maximum))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
    return new WindowTinyLfuPolicy(0.99, characteristics, new WindowTinyLfuSettings(config));
  }
}
