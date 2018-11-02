/*
 * Copyright 2018 Gilga Einziger, Ben Manes and Ohad Eytan. All Rights Reserved.
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

import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.typesafe.config.Config;

/**
 * A sketch where the aging process is a dynamic process and adjusts to the
 * recency/frequency bias of the actual workload recognized by the indicator.
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 * @author ben.manes@gmail.com (Ben Manes)
 * @author ohadey@gmail.com (Ohad Eytan)
 */
public final class IndicatorResetCountMin4 implements Frequency {
  private final ClimberResetCountMin4 sketch;

  int prevHintSum;
  int prevHintCount;
  Indicator indicator;

  public IndicatorResetCountMin4(Config config) {
    this.sketch = new ClimberResetCountMin4(config);
    this.indicator = new Indicator(config);
  }

  @Override
  public int frequency(long e) {
    return sketch.frequency(e);
  }

  @Override
  public void increment(long e) {
    sketch.increment(e);
    indicator.record(e);
  }

  @Override
  public void reportMiss() {
    if (sketch.getEventsToCount() <= 0) {
      sketch.resetEventsToCount();
      double ind = getIndicator();
      sketch.setStep(hintToStep(ind));
      indicator.reset();
    }
  }

  private double getIndicator() {
    return indicator.getIndicator();
  }

  private int hintToStep(double ind) {
    return (int) (ind * 30);
  }

  public int getEventsToCount() {
    return sketch.getEventsToCount();
  }

  public int getPeriod() {
    return sketch.getPeriod();
  }

  public int getHintSum() {
    return prevHintSum;
  }

  public int getHintCount() {
    return prevHintCount;
  }
}
