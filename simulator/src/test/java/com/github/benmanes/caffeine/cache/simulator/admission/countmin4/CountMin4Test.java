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

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Retracking mirrors the cache's {@code FrequencySketch.ensureCapacity}: the table is grow-only so
 * that an oversized one is kept rather than reallocated, and the reset period tracks the table so
 * that a cache which grew does not age at the rate its old size earned.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CountMin4Test {

  @Test
  void ensureCapacity_grows_retunesThePeriod() {
    var sketch = new PeriodicResetCountMin4(config(512));
    int initialLength = sketch.table.length;
    int initialPeriod = sketch.period;

    sketch.ensureCapacity(8_192);
    assertThat(sketch.table.length).isGreaterThan(initialLength);
    assertThat(sketch.period).isEqualTo(10 * sketch.table.length);
    assertThat(sketch.period).isGreaterThan(initialPeriod);
  }

  @Test
  void ensureCapacity_shrink_keepsTheTable() {
    var sketch = new PeriodicResetCountMin4(config(8_192));
    int initialLength = sketch.table.length;

    // an oversized table only reduces collisions, so a smaller cache must not wipe the counts
    sketch.increment(1);
    sketch.ensureCapacity(512);
    assertThat(sketch.table.length).isEqualTo(initialLength);
    assertThat(sketch.frequency(1)).isEqualTo(1);
  }

  @Test
  void ensureCapacity_sameSize_keepsTheCounts() {
    var sketch = new PeriodicResetCountMin4(config(512));
    sketch.increment(1);
    sketch.increment(1);

    sketch.ensureCapacity(512);
    assertThat(sketch.frequency(1)).isEqualTo(2);
  }

  private static Config config(long maximumSize) {
    return ConfigFactory.parseMap(Map.of("maximum-size", maximumSize))
        .withFallback(ConfigFactory.load().getConfig("caffeine.simulator"));
  }
}
