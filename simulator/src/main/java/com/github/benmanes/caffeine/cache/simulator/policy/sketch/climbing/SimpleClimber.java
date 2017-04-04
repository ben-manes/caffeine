/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.DECREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.INCREASE_WINDOW;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.typesafe.config.Config;

/**
 * A naive, simple hill climber.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class SimpleClimber implements HillClimber {
  private final int pivot;

  private int sample;
  private final int sampleSize;

  private int hitsInSample;
  private int missesInSample;
  private double previousHitRate;

  private final double tolerance;
  private boolean increaseWindow;

  public SimpleClimber(Config config) {
    SimpleClimberSettings settings = new SimpleClimberSettings(config);

    this.sampleSize = (int) (settings.percentSample() * settings.maximumSize());
    this.pivot = (int) (settings.percentPivot() * settings.maximumSize());
    this.tolerance = 100d * settings.tolerance();
  }

  @Override
  public void onMiss(long key) {
    missesInSample++;
    sample++;
  }

  @Override
  public void onHit(long key, QueueType queueType) {
    hitsInSample++;
    sample++;
  }

  @Override
  public Adaptation adapt(int windowSize, int protectedSize) {
    Adaptation adaption = Adaptation.HOLD;

    if (sample >= sampleSize) {
      double hitRate = (100d * hitsInSample) / (hitsInSample + missesInSample);

      if (!Double.isNaN(hitRate) && !Double.isInfinite(hitRate) && (previousHitRate != 0.0)) {
        adaption = adjust(hitRate);
      }
      previousHitRate = hitRate;

      missesInSample = 0;
      hitsInSample = 0;
      sample = 0;
    }

    return adaption;
  }

  private Adaptation adjust(double hitRate) {
    if (hitRate < (previousHitRate + tolerance)) {
      increaseWindow = !increaseWindow;
    }
    Adaptation.Type adaptionType = increaseWindow ? INCREASE_WINDOW : DECREASE_WINDOW;
    return new Adaptation(adaptionType, pivot);
  }

  static final class SimpleClimberSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.simple.";

    public SimpleClimberSettings(Config config) {
      super(config);
    }
    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }
    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }
    public double tolerance() {
      return config().getInt(BASE_PATH + "tolerance");
    }
  }
}
