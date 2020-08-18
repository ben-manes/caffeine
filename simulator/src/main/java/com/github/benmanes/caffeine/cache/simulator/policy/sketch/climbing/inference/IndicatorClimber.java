/*
 * Copyright 2018 Ohad Eytan. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.inference;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * Adjust the window size based on the Indicator value.
 *
 * @author ohadey@gmail.com (Ohad Eytan)
 */
public final class IndicatorClimber implements HillClimber {
  private final Indicator indicator;
  private final int cacheSize;

  private double prevPercent;

  public IndicatorClimber(Config config) {
    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
    this.cacheSize = Ints.checkedCast(settings.maximumSize());
    this.prevPercent = 1 - settings.percentMain().get(0);
    this.indicator = new Indicator(config);
  }

  @Override
  public void onHit(long key, QueueType queue, boolean isFull) {
    if (isFull) {
      indicator.record(key);
    }
  }

  @Override
  public void onMiss(long key, boolean isFull) {
    if (isFull) {
      indicator.record(key);
    }
  }

  @Override
  public Adaptation adapt(double windowSize,
      double probationSize, double protectedSize, boolean isFull) {
    if (indicator.getSample() != 50_000) {
      return Adaptation.hold();
    }

    double newPercent = (indicator.getIndicator() * 80) / 100.0;
    double oldPercent = prevPercent;
    prevPercent = newPercent;
    indicator.reset();

    return (newPercent > oldPercent)
        ? Adaptation.increaseWindow((int) ((newPercent - oldPercent) * cacheSize))
        : Adaptation.decreaseWindow((int) ((oldPercent - newPercent) * cacheSize));
  }
}
