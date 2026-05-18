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
 * A hill climber that uses the sign of {@code Δw · ΔHR} as the direction signal: positive when
 * the last window move and the resulting hit-rate move were correlated (keep going), negative
 * when they were anti-correlated (reverse). The step magnitude is multiplicative on the previous
 * move {@code (scale · |Δw|)}, so the climber naturally accelerates when sustained correlation
 * pulls it the same way, and naturally converges as |Δw| shrinks near an optimum.
 * <p>
 * The shape is borrowed from the learning-rate hill climber in
 * <a href="https://www.usenix.org/conference/fast21/presentation/rodriguez">Learning Cache
 * Replacement with CACHEUS</a> (Rodriguez et al., FAST '21, Algorithm 3), adapted from tuning the
 * learning rate of a multiplicative-weights expert to tuning the admission window directly. A
 * sustained-degradation counter triggers a reset to the initial step size when the gradient signal
 * has been consistently bad, escaping a local pit without the random-walk plateau jitter that
 * Cacheus uses on flat regions.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CorrelationClimber extends AbstractClimber {
  private static final int MIN_STEP = 1;

  private final int restartThreshold;
  private final int initialStep;
  private final double scale;

  private int degradeCount;

  public CorrelationClimber(Config config) {
    var settings = new CorrelationClimberSettings(config);
    int maximumSize = Math.toIntExact(settings.maximumSize());
    this.sampleSize = (int) (settings.percentSample() * maximumSize);
    this.initialStep = Math.max(1, (int) (settings.percentPivot() * maximumSize));
    this.restartThreshold = settings.restartThreshold();
    this.scale = settings.scale();
  }

  @Override
  protected double adjust(double hitRate) {
    if (firstSample) {
      // No movement history; probe in the positive direction so there's a Δw to correlate against
      return initialStep;
    }

    double dw = currentWindowSize - previousWindowSize;
    if (dw == 0) {
      // The framework clamped the previous step to zero (cache wasn't full or at a bound). Probe
      // again rather than stall the climber forever.
      return initialStep;
    }

    double dHr = hitRate - previousHitRate;
    if (dHr < 0) {
      degradeCount++;
      if (degradeCount >= restartThreshold) {
        // Sustained degradation — the multiplicative step has likely collapsed us into a pit.
        // Restart from the configured initial step so we can climb out.
        degradeCount = 0;
        return initialStep;
      }
    } else {
      degradeCount = 0;
    }

    var sign = (int) Math.signum(dw * dHr);
    if (sign == 0) {
      // Flat hit-rate response; hold and let the next sample give us a signal
      return 0;
    }
    double magnitude = Math.max(MIN_STEP, scale * Math.abs(dw));
    return sign * magnitude;
  }

  static final class CorrelationClimberSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.correlation.";

    public CorrelationClimberSettings(Config config) {
      super(config);
    }
    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }
    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }
    public double scale() {
      return config().getDouble(BASE_PATH + "scale");
    }
    public int restartThreshold() {
      return config().getInt(BASE_PATH + "restart-threshold");
    }
  }
}
