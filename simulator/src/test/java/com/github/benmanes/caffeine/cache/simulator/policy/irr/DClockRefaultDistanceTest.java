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

import com.github.benmanes.caffeine.cache.simulator.policy.irr.DClockPolicy.DClockSettings;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * DClock stamps an evicted shadow with the refault distance measured from the nonresident age. The
 * cited reference (linux/mm/workingset.c) advances that age via {@code workingset_age_nonresident}
 * before reading it to stamp the page, so the stamp counts the eviction itself; a page that refaults
 * at exactly the active size is then optimistically activated. This pins that boundary case: the
 * hot key survives a following scan only if the refault promoted it to the active list.
 *
 * @author sahvx655@gmail.com (Sahana Bogar)
 */
final class DClockRefaultDistanceTest {

  @Test
  void boundaryRefaultIsActivated() {
    var policy = new DClockPolicy(new DClockSettings(config()), /* percentActive= */ 0.5);

    // A B C prime the two-entry cache and evict A to the shadow list at refault distance 0. The
    // re-access of A refaults at exactly the (empty) active size, so it must be promoted. D E F G
    // scan the inactive list; A survives only if it was activated, and the final A then hits.
    for (long key : new long[] {0, 1, 2, 0, 3, 4, 5, 6, 0}) {
      policy.record(key);
    }
    policy.finished();

    assertThat(policy.stats().hitCount()).isEqualTo(1);
  }

  private static Config config() {
    var properties = Map.<String, Object>of("maximum-size", 2);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
