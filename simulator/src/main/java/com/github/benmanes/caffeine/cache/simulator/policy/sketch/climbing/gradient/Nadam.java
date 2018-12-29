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

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.adaptBy;

import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.typesafe.config.Config;

/**
 * Nesterov-accelerated Adaptive Moment Estimation (Nadam) optimizer. Nadam modifies the Adam
 * optimizer to replace normal momentum with Nesterov's accelerated gradient. The authors describe
 * it in <a href="https://openreview.net/pdf?id=OM0jvwB8jIp57ZJjtNEZ">Incorporating Nesterov
 * Momentum into Adam<a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Nadam implements HillClimber {
  private final int sampleSize;
  private final int stepSize;
  private final double beta1;
  private final double beta2;
  private final double epsilon;

  private int hitsInSample;
  private int missesInSample;
  private double previousHitRate;

  private int t;
  private double moment;
  private double velocity;

  public Nadam(Config config) {
    AdamSettings settings = new AdamSettings(config);
    sampleSize = (int) (settings.percentSample() * settings.maximumSize());
    stepSize = (int) (settings.percentPivot() * settings.maximumSize());
    epsilon = settings.epsilon();
    beta1 = settings.beta1();
    beta2 = settings.beta2();
    t = 1;
  }

  @Override
  public void onMiss(long key, boolean isFull) {
    if (isFull) {
      missesInSample++;
    }
  }

  @Override
  public void onHit(long key, QueueType queueType, boolean isFull) {
    if (isFull) {
      hitsInSample++;
    }
  }

  @Override
  public Adaptation adapt(int windowSize, int protectedSize, boolean isFull) {
    if (!isFull) {
      return Adaptation.hold();
    }

    int sampleCount = (hitsInSample + missesInSample);
    if (sampleCount < sampleSize) {
      return Adaptation.hold();
    }

    double hitRate = (double) hitsInSample / sampleCount;
    Adaptation adaption = adjust(hitRate);
    previousHitRate = hitRate;
    missesInSample = 0;
    hitsInSample = 0;
    t++;

    return adaption;
  }

  private Adaptation adjust(double hitRate) {
    if (Double.isNaN(hitRate) || Double.isInfinite(hitRate) || (previousHitRate == 0.0)) {
      return Adaptation.hold();
    }

    double currentMissRate = (1 - hitRate);
    double previousMissRate = (1 - previousHitRate);
    double gradient = currentMissRate - previousMissRate;

    moment = (beta1 * moment) + ((1 - beta1) * gradient);
    velocity = (beta2 * velocity) + ((1 - beta2) * (gradient * gradient));

    // https://towardsdatascience.com/10-gradient-descent-optimisation-algorithms-86989510b5e9#6d4c
    double momentBias = moment / (1 - Math.pow(beta1, t));
    double velocityBias = velocity / (1 - Math.pow(beta2, t));
    double amount = (stepSize / (Math.sqrt(velocityBias) + epsilon))
        * ((beta1 * momentBias) + (((1 - beta1) / (1 - Math.pow(beta1, t))) * gradient));

    return adaptBy(amount);
  }

  static final class AdamSettings extends BasicSettings {
    static final String BASE_PATH = "hill-climber-window-tiny-lfu.nadam.";

    public AdamSettings(Config config) {
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
