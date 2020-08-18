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

import java.util.Random;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractClimber;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * A simulated annealing hill climber.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SimulatedAnnealingClimber extends AbstractClimber {
  private final double coolDownTolerance;
  private final double restartTolerance;
  private final double minTemperature;
  private final double coolDownRate;
  private final int initialStepSize;
  private final Random random;

  private boolean increaseWindow;
  private double temperature;
  private int stepSize;

  public SimulatedAnnealingClimber(Config config) {
    SimulatedAnnealingSettings settings = new SimulatedAnnealingSettings(config);
    int maximumSize = Ints.checkedCast(settings.maximumSize());
    this.initialStepSize = (int) (settings.percentPivot() * maximumSize);
    this.sampleSize = (int) (settings.percentSample() * maximumSize);
    this.coolDownTolerance = 100 * settings.coolDownTolerance();
    this.restartTolerance = 100 * settings.restartTolerance();
    this.random = new Random(settings.randomSeed());
    this.minTemperature = settings.minTemperature();
    this.coolDownRate = settings.coolDownRate();
    restart();
  }

  private void restart() {
    stepSize = initialStepSize;
    temperature = 1.0;
  }

  @Override
  protected double adjust(double hitRate) {
    if ((previousHitRate - hitRate) >= restartTolerance) {
      restart();
    }

    if (temperature <= minTemperature) {
      return 0.0;
    }

    double criteria = random.nextGaussian();
    double acceptanceProbability = Math.exp((hitRate - previousHitRate) / (100 * temperature));
    if ((hitRate < previousHitRate) && (acceptanceProbability <= criteria)) {
      increaseWindow = !increaseWindow;
      stepSize = Math.max(stepSize - 1, 0);
    }

    if ((previousHitRate - hitRate) > coolDownTolerance) {
      temperature = coolDownRate * temperature;
      stepSize = 1 + (int) (stepSize * temperature);
    }

    return increaseWindow ? stepSize : -stepSize;
  }

  static final class SimulatedAnnealingSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.simulated-annealing.";

    public SimulatedAnnealingSettings(Config config) {
      super(config);
    }
    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }
    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }
    public double coolDownRate() {
      return config().getDouble(BASE_PATH + "cool-down-rate");
    }
    public double minTemperature() {
      return config().getDouble(BASE_PATH + "min-temperature");
    }
    public double restartTolerance() {
      return config().getDouble(BASE_PATH + "restart-tolerance");
    }
    public double coolDownTolerance() {
      return config().getDouble(BASE_PATH + "cool-down-tolerance");
    }
  }
}
