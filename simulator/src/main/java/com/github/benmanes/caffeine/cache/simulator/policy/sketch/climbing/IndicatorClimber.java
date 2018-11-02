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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
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
    this.prevPercent = 1 - settings.percentMain().get(0);
    this.cacheSize = settings.maximumSize();
    this.indicator = new Indicator(config);
  }

  @Override
  public void onHit(long key, QueueType queue) {
    indicator.record(key);
  }

  @Override
  public void onMiss(long key) {
    indicator.record(key);
  }

  @Override
  public Adaptation adapt(int windowSize, int protectedSize) {
    if (indicator.getSample() == 50000) {
      double oldPercent = prevPercent;
      double ind = indicator.getIndicator();
      double newPercent;
      newPercent = prevPercent = ind * 80 / 100.0;

      indicator.reset();
      if (newPercent > oldPercent) {
        return new Adaptation(Adaptation.Type.INCREASE_WINDOW,
            (int) ((newPercent - oldPercent) * cacheSize));
      }
      return new Adaptation(Adaptation.Type.DECREASE_WINDOW,
          (int) ((oldPercent - newPercent) * cacheSize));
    }
    return Adaptation.HOLD;
  }
}
