/*
 * Copyright 2018 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.gradient;

import static java.util.Locale.US;

import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractClimber;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * Stochastic gradient descent (SGD) optimizer.
 * <p>
 * <tt>w(t+1) = w(t) - alpha * dL/dw(t)</tt> where,
 * <ul>
 *   <li><b>w(t)</b> is the current window size
 *   <li><b>alpha</b> is the learning rate (step size)
 *   <li><b>dL/dw(t)</b> is the gradient of the curve
 *   <li><b>w(t+1)</b> is the new window size configuration
 * </ul>
 * <p>
 * SGC may be enhanced using momentum, either classical or Nesterov's. For details see
 * <ul>
 *   <li>https://towardsdatascience.com/10-gradient-descent-optimisation-algorithms-86989510b5e9
 *   <li>http://ruder.io/optimizing-gradient-descent/index.html#momentum
 *   <li>http://cs231n.github.io/neural-networks-3/#sgd
 * </ul>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Stochastic extends AbstractClimber {
  private final Acceleration acceleration;
  private final int stepSize;
  private final double beta;

  private double velocity;

  public Stochastic(Config config) {
    StochasticSettings settings = new StochasticSettings(config);
    int maximumSize = Ints.checkedCast(settings.maximumSize());
    sampleSize = (int) (settings.percentSample() * maximumSize);
    stepSize = (int) (settings.percentPivot() * maximumSize);
    acceleration = settings.acceleration();
    beta = settings.beta();
  }

  @Override
  protected double adjust(double hitRate) {
    double currentMissRate = (1 - hitRate);
    double previousMissRate = (1 - previousHitRate);
    double gradient = currentMissRate - previousMissRate;

    switch (acceleration) {
      case NONE:
        return stepSize * gradient;
      case MOMENTUM:
        velocity = (beta * velocity) + (1 - beta) * gradient;
        return stepSize * velocity;
      case NESTEROV:
        // http://cs231n.github.io/neural-networks-3/#sgd
        double previousVelocity = velocity;
        velocity = (beta * velocity) + stepSize * gradient;
        return -(beta * previousVelocity) + ((1 + beta) * velocity);
    }
    throw new IllegalStateException("Unknown acceleration type: " + acceleration);
  }

  enum Acceleration { NONE, MOMENTUM, NESTEROV }

  static final class StochasticSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.stochastic-gradient-descent.";

    public StochasticSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("hill-climber-window-tiny-lfu.percent-main");
    }
    public double percentPivot() {
      return config().getDouble(BASE_PATH + "percent-pivot");
    }
    public double percentSample() {
      return config().getDouble(BASE_PATH + "percent-sample");
    }
    public Acceleration acceleration() {
      return Acceleration.valueOf(config().getString(BASE_PATH + "acceleration").toUpperCase(US));
    }
    public double beta() {
      return config().getDouble(BASE_PATH + "beta");
    }
  }
}
