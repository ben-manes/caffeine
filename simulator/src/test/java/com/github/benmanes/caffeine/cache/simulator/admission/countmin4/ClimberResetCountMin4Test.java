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
package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * The climber and periodic resets share the same down-sampling docstring and the production
 * FrequencySketch reset: halve every counter, then reduce the sample by the truncation lost to the
 * odd counters. Given identical table state they must reduce the sample identically.
 *
 * @author sahvx655@gmail.com (Sahana Bogar)
 */
final class ClimberResetCountMin4Test {
  private static final int MAXIMUM_SIZE = 512;

  @Test
  void reset_matchesPeriodicDownSample() {
    var climber = new ClimberResetCountMin4(config());
    var periodic = new PeriodicResetCountMin4(config());

    // An identical table where every counter is odd maximises the truncation correction.
    long[] table = new long[climber.table.length];
    Arrays.fill(table, ClimberResetCountMin4.ONE_MASK);
    climber.table = table.clone();
    periodic.table = table.clone();

    climber.step = 1;
    climber.additions = climber.period - 1;
    periodic.additions = periodic.period - 1;

    climber.tryReset(/* added= */ true);
    periodic.tryReset(/* added= */ true);

    assertThat(climber.additions).isEqualTo(periodic.additions);
  }

  private static Config config() {
    var properties = Map.<String, Object>of("maximum-size", MAXIMUM_SIZE);
    return ConfigFactory.parseMap(properties)
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
