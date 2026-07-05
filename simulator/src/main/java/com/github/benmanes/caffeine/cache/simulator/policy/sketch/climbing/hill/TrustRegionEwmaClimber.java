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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractClimber;
import com.typesafe.config.Config;

/**
 * A hill climber that adapts the admission-window size with a trust-region step driven by an
 * EWMA-smoothed |ΔHR| gain ratio.
 * <p>
 * The smoothing converts a noisy two-sample ratio (which biased a plain trust-region toward
 * spurious step growth) into a low-pass-filtered estimate. The multiplicative grow/shrink keeps
 * the step within a stable {@code [min_frac × max, max_frac × max]} band, and the direction uses
 * the raw {@code ΔHR} sign so phase changes are caught on the first sample.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TrustRegionEwmaClimber extends AbstractClimber {
  private final double shrinkThreshold;
  private final double growThreshold;
  private final double shrinkFactor;
  private final double growFactor;
  private final double ewmaAlpha;
  private final double overshoot;
  private final double minStep;
  private final double maxStep;

  private final int initialSampleSize;
  private double previousAbsDelta;
  private double smoothedAbsDelta;
  private boolean increaseWindow;
  private double stepSize;

  public TrustRegionEwmaClimber(Config config) {
    var settings = new TrustRegionEwmaSettings(config);
    long max = settings.maximumSize();
    this.minStep = Math.max(1.0, settings.minFrac() * Math.toIntExact(max));
    this.maxStep = Math.max(minStep + 1.0, settings.maxFrac() * Math.toIntExact(max));
    this.initialSampleSize = (int) (settings.percentSample() * Math.toIntExact(max));
    this.stepSize = settings.percentPivot() * Math.toIntExact(max);
    this.shrinkThreshold = settings.shrinkThreshold();
    this.growThreshold = settings.growThreshold();
    this.shrinkFactor = settings.shrinkFactor();
    this.growFactor = settings.growFactor();
    this.ewmaAlpha = settings.ewmaAlpha();
    this.overshoot = settings.overshoot();
    this.sampleSize = initialSampleSize;
  }

  @Override
  protected double adjust(double hitRate) {
    double delta = (hitRate - previousHitRate);
    double absPrev = previousAbsDelta;
    double absCur = Math.abs(delta);
    smoothedAbsDelta = ((1.0 - ewmaAlpha) * smoothedAbsDelta) + (ewmaAlpha * absCur);

    if (absPrev > 1e-12) {
      double ratio = (smoothedAbsDelta / absPrev);
      if (ratio < shrinkThreshold) {
        stepSize *= shrinkFactor;
      } else if (ratio > growThreshold) {
        stepSize *= growFactor;
      }
    }
    stepSize = Math.clamp(stepSize, minStep, maxStep);
    previousAbsDelta = absCur;

    if (delta < -overshoot) {
      // Reverse at half magnitude to walk back the overshoot.
      increaseWindow = !increaseWindow;
      return increaseWindow ? (stepSize * 0.5) : -(stepSize * 0.5);
    }
    increaseWindow = ((delta >= 0) == increaseWindow);
    return increaseWindow ? stepSize : -stepSize;
  }

  @Override
  protected void resetSample(double hitRate) {
    sampleSize = initialSampleSize;
    super.resetSample(hitRate);
  }

  static final class TrustRegionEwmaSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.trust-region-ewma.";

    public TrustRegionEwmaSettings(Config config) {
      super(config);
    }
    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }
    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }
    public double ewmaAlpha() {
      return config().getDouble(BASE_PATH + "ewma-alpha");
    }
    public double shrinkThreshold() {
      return config().getDouble(BASE_PATH + "shrink-threshold");
    }
    public double growThreshold() {
      return config().getDouble(BASE_PATH + "grow-threshold");
    }
    public double growFactor() {
      return config().getDouble(BASE_PATH + "grow-factor");
    }
    public double shrinkFactor() {
      return config().getDouble(BASE_PATH + "shrink-factor");
    }
    public double overshoot() {
      return config().getDouble(BASE_PATH + "overshoot");
    }
    public double minFrac() {
      return config().getDouble(BASE_PATH + "min-frac");
    }
    public double maxFrac() {
      return config().getDouble(BASE_PATH + "max-frac");
    }
  }
}
