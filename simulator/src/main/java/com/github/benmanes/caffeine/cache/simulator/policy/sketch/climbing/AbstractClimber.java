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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import static com.google.common.base.Preconditions.checkState;

/**
 * A skeleton for hill climbers that walk using the hit rate.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class AbstractClimber implements HillClimber {
  protected int sampleSize;
  protected int hitsInMain;
  protected int hitsInWindow;
  protected int hitsInSample;
  protected int missesInSample;
  protected double previousHitRate;

  static final boolean debug = false;

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

      if (queueType == QueueType.WINDOW) {
        hitsInWindow++;
      } else {
        hitsInMain++;
      }
    }
  }

  @Override
  public Adaptation adapt(double windowSize, double probationSize,
      double protectedSize, boolean isFull) {
    if (!isFull) {
      return Adaptation.hold();
    }

    checkState(sampleSize > 0, "Sample size may not be zero");
    int sampleCount = (hitsInSample + missesInSample);
    if (sampleCount < sampleSize) {
      return Adaptation.hold();
    }

    double hitRate = (double) hitsInSample / sampleCount;
    Adaptation adaption = Adaptation.adaptBy(adjust(hitRate));
    resetSample(hitRate);

    if (debug) {
      System.out.printf("%.2f\t%.2f%n", 100 * hitRate, windowSize);
    }
    return adaption;
  }

  /** Returns the amount to adapt by. */
  protected abstract double adjust(double hitRate);

  /** Starts the next sample period. */
  protected void resetSample(double hitRate) {
    previousHitRate = hitRate;
    missesInSample = 0;
    hitsInSample = 0;
    hitsInWindow = 0;
    hitsInMain = 0;
  }
}
