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
import com.typesafe.config.Config;

/**
 * A simulated annealing hill climber.
 * <p>
 * This is a faithful, derivative-free Metropolis search (it does not use the hit-rate gradient):
 * each sample period evaluates the window size reached by the previous random proposal and either
 * keeps it as the new incumbent (always if it improved the hit rate, otherwise with the Metropolis
 * probability {@code exp(ΔhitRate / temperature)}) or, if rejected, proposes again from the
 * incumbent. The proposal is a Gaussian perturbation whose width scales with the temperature, so
 * the search explores widely when hot and fine-tunes when cool. The temperature follows a geometric
 * cooling schedule, with an occasional random restart so the search keeps re-adapting on a
 * non-stationary workload rather than freezing at a stale optimum.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SimulatedAnnealingClimber extends AbstractClimber {
  private final double randomRestart;
  private final double minTemperature;
  private final double coolDownRate;
  private final double maxStep;
  private final Random random;

  private double incumbentWindowSize;
  private double incumbentHitRate;
  private double temperature;

  public SimulatedAnnealingClimber(Config config) {
    var settings = new SimulatedAnnealingSettings(config);
    int maximumSize = Math.toIntExact(settings.maximumSize());
    this.sampleSize = (int) (settings.percentSample() * maximumSize);
    this.maxStep = settings.percentPivot() * maximumSize;
    this.randomRestart = settings.randomRestart();
    this.random = new Random(settings.randomSeed());
    this.minTemperature = settings.minTemperature();
    this.coolDownRate = settings.coolDownRate();
    this.temperature = 1.0;
  }

  @Override
  @SuppressWarnings("PMD.UnusedAssignment")
  protected double adjust(double hitRate) {
    if (firstSample) {
      // No proposal has been evaluated yet; adopt the starting window as the incumbent
      incumbentWindowSize = currentWindowSize;
      incumbentHitRate = hitRate;
    } else {
      // Metropolis acceptance of the proposal that was evaluated during this sample period. An
      // improvement is always kept; a worse result is kept with probability exp(ΔhitRate / T).
      double energy = hitRate - incumbentHitRate;
      if ((energy >= 0) || (random.nextDouble() < Math.exp(energy / temperature))) {
        incumbentWindowSize = currentWindowSize;
        incumbentHitRate = hitRate;
      }
      // Re-anneal occasionally so the search keeps adapting on a non-stationary workload; otherwise
      // follow the geometric cooling schedule (floored so exp(ΔhitRate / T) stays well defined).
      if (random.nextDouble() < randomRestart) {
        // Re-anneal from the current position, discarding the prior regime's stale incumbent so the
        // search re-baselines against the current workload instead of an old optimum it can't match
        temperature = 1.0;
        incumbentHitRate = hitRate;
        incumbentWindowSize = currentWindowSize;
      } else {
        temperature = Math.max(minTemperature, coolDownRate * temperature);
      }
    }

    // Propose a new neighbor relative to the incumbent: a Gaussian step whose width shrinks with
    // the temperature. Returning the move relative to the current window implicitly reverts a
    // rejected proposal (the incumbent is unchanged on a rejection, so the window is steered back
    // toward it).
    double proposal = random.nextGaussian() * maxStep * temperature;
    return (incumbentWindowSize + proposal) - currentWindowSize;
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
    public double randomRestart() {
      return config().getDouble(BASE_PATH + "random-restart");
    }
  }
}
