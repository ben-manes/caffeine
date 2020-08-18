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

import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.AbstractClimber;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * Nesterov-accelerated Adaptive Moment Estimation (Nadam) optimizer. Nadam modifies the Adam
 * optimizer to replace normal momentum with Nesterov's accelerated gradient. The authors describe
 * it in <a href="https://openreview.net/pdf?id=OM0jvwB8jIp57ZJjtNEZ">Incorporating Nesterov
 * Momentum into Adam<a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Nadam extends AbstractClimber {
  private final int stepSize;
  private final double beta1;
  private final double beta2;
  private final double epsilon;

  private int t;
  private double moment;
  private double velocity;

  public Nadam(Config config) {
    NadamSettings settings = new NadamSettings(config);
    int maximumSize = Ints.checkedCast(settings.maximumSize());
    sampleSize = (int) (settings.percentSample() * maximumSize);
    stepSize = (int) (settings.percentPivot() * maximumSize);
    epsilon = settings.epsilon();
    beta1 = settings.beta1();
    beta2 = settings.beta2();
    t = 1;
  }

  @Override
  protected void resetSample(double hitRate) {
    super.resetSample(hitRate);
    t++;
  }

  @Override
  protected double adjust(double hitRate) {
    double currentMissRate = (1 - hitRate);
    double previousMissRate = (1 - previousHitRate);
    double gradient = currentMissRate - previousMissRate;

    moment = (beta1 * moment) + ((1 - beta1) * gradient);
    velocity = (beta2 * velocity) + ((1 - beta2) * (gradient * gradient));

    // https://towardsdatascience.com/10-gradient-descent-optimisation-algorithms-86989510b5e9#6d4c
    double momentBias = moment / (1 - Math.pow(beta1, t));
    double velocityBias = velocity / (1 - Math.pow(beta2, t));
    return (stepSize / (Math.sqrt(velocityBias) + epsilon))
        * ((beta1 * momentBias) + (((1 - beta1) / (1 - Math.pow(beta1, t))) * gradient));
  }

  static final class NadamSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.nadam.";

    public NadamSettings(Config config) {
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
    public double beta1() {
      return config().getDouble(BASE_PATH + "beta1");
    }
    public double beta2() {
      return config().getDouble(BASE_PATH + "beta2");
    }
    public double epsilon() {
      return config().getDouble(BASE_PATH + "epsilon");
    }
  }
}
