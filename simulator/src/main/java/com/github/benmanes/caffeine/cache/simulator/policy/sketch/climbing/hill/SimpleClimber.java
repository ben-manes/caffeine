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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractClimber;
import com.typesafe.config.Config;

/**
 * A simple hill climber that adapts the admission window size by walking in the direction that
 * improves the hit rate. For small cache sizes the step decays more slowly so the climber remains
 * reactive to workload shifts, and the sample period grows proportionally as the step shrinks to
 * reduce noise when fine-tuning.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SimpleClimber extends AbstractClimber {
  private final double smallCacheSampleRatioCap;
  private final double smallCacheStepDecayRate;
  private final long smallCacheThreshold;
  private final double restartThreshold;
  private final double initialStepSize;
  private final double sampleDecayRate;
  private final int initialSampleSize;
  private final double stepDecayRate;
  private final double tolerance;
  private final long maximumSize;

  private boolean increaseWindow;
  private double stepSize;

  public SimpleClimber(Config config) {
    var settings = new SimpleClimberSettings(config);
    this.maximumSize = settings.maximumSize();
    this.initialStepSize = Math.max(
        settings.percentPivot() * Math.toIntExact(maximumSize), settings.minInitialStep());
    this.initialSampleSize = (int) (settings.percentSample() * Math.toIntExact(maximumSize));
    this.smallCacheStepDecayRate = settings.smallCacheStepDecayRate();
    this.smallCacheSampleRatioCap = settings.smallCacheSampleRatioCap();
    this.smallCacheThreshold = settings.smallCacheThreshold();
    this.restartThreshold = settings.restartThreshold();
    this.sampleDecayRate = settings.sampleDecayRate();
    this.stepDecayRate = settings.stepDecayRate();
    this.tolerance = settings.tolerance();
    this.increaseWindow = isSmallCache();
    this.sampleSize = initialSampleSize;
    this.stepSize = initialStepSize;
  }

  /** Small caches grow the window first, larger caches shrink first. */
  private boolean isSmallCache() {
    return (smallCacheThreshold > 0) && (maximumSize <= smallCacheThreshold);
  }

  @Override
  protected double adjust(double hitRate) {
    if (hitRate < (previousHitRate + tolerance)) {
      increaseWindow = !increaseWindow;
    }
    if (Math.abs(hitRate - previousHitRate) >= restartThreshold) {
      sampleSize = initialSampleSize;
      stepSize = initialStepSize;
    }
    return increaseWindow ? stepSize : -stepSize;
  }

  @Override
  protected void resetSample(double hitRate) {
    super.resetSample(hitRate);

    if (isSmallCache()) {
      // Small caches decay step more slowly so the early exploration step stays large enough for
      // HR shifts to clear restart-threshold on workload transitions, and grow the sample period
      // proportionally as the step decays so the climber sees a less noisy gradient when tuning.
      // Sample ratio clamped to [1, cap] so a step magnitude above initialStep doesn't shrink it.
      stepSize *= smallCacheStepDecayRate;
      double magnitude = Math.max(initialStepSize / smallCacheSampleRatioCap, Math.abs(stepSize));
      double ratio = Math.clamp(initialStepSize / magnitude, 1.0, smallCacheSampleRatioCap);
      sampleSize = (int) (initialSampleSize * ratio);
    } else {
      // Decay the step toward zero but keep sampling, so a workload shift still trips
      // restart-threshold and revives the step -- mirrors BLC, which never freezes
      stepSize *= stepDecayRate;
      sampleSize = Math.max(1, (int) (sampleSize * sampleDecayRate));
    }
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
      return config().getDouble(BASE_PATH + "tolerance");
    }
    public double stepDecayRate() {
      return config().getDouble(BASE_PATH + "step-decay-rate");
    }
    public double sampleDecayRate() {
      return config().getDouble(BASE_PATH + "sample-decay-rate");
    }
    public double restartThreshold() {
      return config().getDouble(BASE_PATH + "restart-threshold");
    }
    public double minInitialStep() {
      return config().getDouble(BASE_PATH + "min-initial-step");
    }
    public long smallCacheThreshold() {
      return config().getLong(BASE_PATH + "small-cache-threshold");
    }
    public double smallCacheSampleRatioCap() {
      return config().getDouble(BASE_PATH + "small-cache-sample-ratio-cap");
    }
    public double smallCacheStepDecayRate() {
      return config().getDouble(BASE_PATH + "small-cache-step-decay-rate");
    }
  }
}
