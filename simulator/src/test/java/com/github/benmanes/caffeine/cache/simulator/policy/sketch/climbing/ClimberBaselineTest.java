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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.typesafe.config.ConfigFactory;

/**
 * The absolute-target climbers (indicator, minisim, minisim-leader) derive their starting window
 * from the policy's percent-main. A percent-main sweep builds one policy per element, so each
 * climber must key off its own instance's element rather than the list's first; otherwise every
 * swept instance after the first dead-reckons from the wrong baseline.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ClimberBaselineTest {

  @ParameterizedTest
  @EnumSource(names = {"INDICATOR", "MINISIM", "MINISIM_LEADER"})
  void baselineFollowsInstanceNotFirstSweepElement(HillClimberType strategy) {
    // The instance uses percent-main 0.5, run two ways: alone (0.5 is also the first element, so
    // the baseline is correct either way) and as the second element of a sweep (a climber keyed off
    // the first element would mistakenly baseline it from 0.99)
    var alone = policy(strategy, "[0.5]");
    var swept = policy(strategy, "[0.99, 0.5]");

    // a looping scan over twice the cache is window-sensitive (a large window degrades to ~0%, a
    // small one keeps a stable subset), so a wrong baseline diverges the adaptation and hit count
    for (int i = 0; i < 120_000; i++) {
      long key = i % 2_000;
      alone.record(key);
      swept.record(key);
    }
    assertThat(swept.stats().hitCount()).isEqualTo(alone.stats().hitCount());
  }

  @ParameterizedTest
  @ValueSource(ints = {512, 1000})
  void adaption_unmoved_isZero(int maximumSize) {
    // The starting window is the entries that percent-main leaves (6 of 512, 10 of 1000), so the
    // adaption is measured from it rather than from 1.0 - percent-main
    var config = ConfigFactory.parseString("caffeine.simulator.maximum-size = " + maximumSize)
        .withFallback(ConfigFactory.load())
        .getConfig("caffeine.simulator");
    var policy = new HillClimberWindowTinyLfuPolicy(HillClimberType.SIMPLE, 0.99,
        new HillClimberWindowTinyLfuSettings(config));
    policy.finished();
    assertThat(policy.stats().percentAdaption()).isEqualTo(0.0);
  }

  private static KeyOnlyPolicy policy(HillClimberType strategy, String percentMain) {
    var config = ConfigFactory.parseString(
            "caffeine.simulator.maximum-size = 1000\n"
            + "caffeine.simulator.hill-climber-window-tiny-lfu.percent-main = " + percentMain + "\n"
            + "caffeine.simulator.hill-climber-window-tiny-lfu.minisim.period = 10000\n")
        .withFallback(ConfigFactory.load())
        .getConfig("caffeine.simulator");
    return new HillClimberWindowTinyLfuPolicy(
        strategy, 0.5, new HillClimberWindowTinyLfuSettings(config));
  }
}
