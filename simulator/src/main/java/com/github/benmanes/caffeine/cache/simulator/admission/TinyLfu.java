/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission;

import static java.util.Locale.US;

import java.util.Random;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor.KeyOnlyAdmittor;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.ClimberResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.IncrementalResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.IndicatorResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin64.CountMin64TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.admission.perfect.PerfectFrequency;
import com.github.benmanes.caffeine.cache.simulator.admission.table.RandomRemovalFrequencyTable;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCacheAdapter;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Admits new entries based on the estimated frequency of its historic use.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TinyLfu implements KeyOnlyAdmittor {
  private final PolicyStats policyStats;
  private final Frequency sketch;
  private final Random random;

  private final double probability;
  private final int threshold;

  public TinyLfu(Config config, PolicyStats policyStats) {
    var settings = new BasicSettings(config);
    this.random = new Random(settings.randomSeed());
    this.sketch = makeSketch(settings);
    this.policyStats = policyStats;
    if (settings.tinyLfu().jitter().enabled()) {
      this.threshold = settings.tinyLfu().jitter().threshold();
      this.probability = settings.tinyLfu().jitter().probability();
    } else {
      this.threshold = Integer.MAX_VALUE;
      this.probability = 1.0;
    }
  }

  public int frequency(long key) {
    return sketch.frequency(key);
  }

  @Override
  public void record(long key) {
    sketch.increment(key);
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    sketch.reportMiss();

    int victimFreq = sketch.frequency(victimKey);
    int candidateFreq = sketch.frequency(candidateKey);
    if ((candidateFreq > victimFreq)
        || ((candidateFreq >= threshold) && (random.nextFloat() < probability))) {
      policyStats.recordAdmission();
      return true;
    }
    policyStats.recordRejection();
    return false;
  }

  /** Returns the frequency histogram. */
  private static Frequency makeSketch(BasicSettings settings) {
    String type = settings.tinyLfu().sketch();
    return switch (type.toLowerCase(US)) {
      case "count-min-4" -> {
        String reset = settings.tinyLfu().countMin4().reset();
        yield switch (reset.toLowerCase(US)) {
          case "climber" -> new ClimberResetCountMin4(settings.config());
          case "periodic" -> new PeriodicResetCountMin4(settings.config());
          case "indicator" -> new IndicatorResetCountMin4(settings.config());
          case "incremental" -> new IncrementalResetCountMin4(settings.config());
          default -> throw new IllegalStateException("Unknown reset type: " + reset);
        };
      }
      case "tiny-table" -> new TinyCacheAdapter(settings.config());
      case "count-min-64" -> new CountMin64TinyLfu(settings.config());
      case "perfect-table" -> new PerfectFrequency(settings.config());
      case "random-table" -> new RandomRemovalFrequencyTable(settings.config());
      default -> throw new IllegalStateException("Unknown sketch type: " + type);
    };
  }
}
