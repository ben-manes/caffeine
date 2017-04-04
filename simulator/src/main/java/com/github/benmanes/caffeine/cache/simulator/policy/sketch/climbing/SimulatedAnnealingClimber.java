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

import java.util.Random;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.typesafe.config.Config;

/**
 * A simulated annealing hill climber.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class SimulatedAnnealingClimber implements HillClimber {
  private final Random random;

  private int pivot;
  private final int initialPivot;

  private int sample;
  private final int sampleSize;

  private int hitsInSample;
  private int missesInSample;
  private double previousHitRate;

  private boolean increaseWindow;
  private double temperature = 1.0;

  private final double coolDownRate;
  private final double minTemperature;
  private final double restartTolerance;
  private final double coolDownTolerance;

  public SimulatedAnnealingClimber(Config config) {
    SimulatedAnnealingSettings settings = new SimulatedAnnealingSettings(config);
    this.random = new Random(settings.randomSeed());

    this.initialPivot = (int) (settings.percentPivot() * settings.maximumSize());
    this.sampleSize = (int) (settings.percentSample() * settings.maximumSize());
    this.pivot = initialPivot;

    this.coolDownRate = settings.coolDownRate();
    this.minTemperature = settings.minTemperature();
    this.restartTolerance = 100 * settings.restartTolerance();
    this.coolDownTolerance = 100 * settings.coolDownTolerance();
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
    if ((previousHitRate - hitRate) >= restartTolerance) {
      pivot = initialPivot;
      temperature = 1.0;
    }

    Adaptation.Type adaptionType = Adaptation.Type.HOLD;
    if (temperature > minTemperature) {
      double acceptanceProbability = Math.exp((hitRate - previousHitRate) / (100 * temperature));
      double criteria = random.nextGaussian();

      if ((hitRate < previousHitRate) && acceptanceProbability <= criteria) {
        increaseWindow = !increaseWindow;
        pivot--;
      }

      if ((previousHitRate - hitRate) > coolDownTolerance) {
        temperature = coolDownRate * temperature;
        pivot = 1 + (int) (pivot * temperature);
      }

      adaptionType = increaseWindow ? INCREASE_WINDOW : DECREASE_WINDOW;
    }

    return new Adaptation(adaptionType, pivot);
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
