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
 * AMSGrad is an improvement on Adam that ensures that the velocity is always increasing, correcting
 * for cases where Adam fails to converge to the optimal solution. The authors describe these
 * changes in <a href="https://openreview.net/pdf?id=ryQu7f-RZ">On the Convergence of Adam and
 * Beyond</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AmsGrad extends AbstractClimber {
  private final int stepSize;
  private final double beta1;
  private final double beta2;
  private final double epsilon;

  private double moment;
  private double velocity;
  private double maxVelocity;

  public AmsGrad(Config config) {
    AmsGradSettings settings = new AmsGradSettings(config);
    int maximumSize = Ints.checkedCast(settings.maximumSize());
    sampleSize = (int) (settings.percentSample() * maximumSize);
    stepSize = (int) (settings.percentPivot() * maximumSize);
    epsilon = settings.epsilon();
    beta1 = settings.beta1();
    beta2 = settings.beta2();
  }

  @Override
  protected double adjust(double hitRate) {
    double currentMissRate = (1 - hitRate);
    double previousMissRate = (1 - previousHitRate);
    double gradient = currentMissRate - previousMissRate;

    moment = (beta1 * moment) + ((1 - beta1) * gradient);
    velocity = (beta2 * velocity) + ((1 - beta2) * (gradient * gradient));
    maxVelocity = Math.max(velocity, maxVelocity);

    return (stepSize * moment) / (Math.sqrt(maxVelocity) + epsilon);
  }

  static final class AmsGradSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.amsgrad.";

    public AmsGradSettings(Config config) {
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
