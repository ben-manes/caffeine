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

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.DECREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.HOLD;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.INCREASE_WINDOW;
import static com.google.common.base.Preconditions.checkState;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type;

/**
 * A skeleton for gradient descent optimizers.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class GradientDescent implements HillClimber {
  protected int sampleSize;
  protected int hitsInMain;
  protected int hitsInWindow;
  protected int hitsInSample;
  protected int missesInSample;
  protected double previousHitRate;

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
  public Adaptation adapt(int windowSize, int probationSize, int protectedSize, boolean isFull) {
    if (!isFull) {
      return Adaptation.hold();
    }

    checkState(sampleSize > 0, "Sample size may not be zero");
    int sampleCount = (hitsInSample + missesInSample);
    if (sampleCount < sampleSize) {
      return Adaptation.hold();
    }

    double hitRate = (double) hitsInSample / sampleCount;
    Adaptation adaption = mayAdjust(hitRate)
        ? hintDirection(adjust(hitRate), hitRate, sampleCount,
            windowSize, probationSize, protectedSize)
        : Adaptation.hold();
    resetSample(hitRate);
    return adaption;
  }

  /** Returns whether to adjust. */
  protected boolean mayAdjust(double hitRate) {
    return !Double.isNaN(hitRate) && !Double.isInfinite(hitRate) && (previousHitRate != 0.0);
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

  /** Returns the adaption, overriding the direction based on the hit density. */
  protected Adaptation hintDirection(double amount, double hitRate,
      int sampleCount, int windowSize, int probationSize, int protectedSize) {
    Type direction;
    if (amount == 0) {
      direction = HOLD;
    } else if (hitRate == 0) {
      direction = (amount > 0) ? INCREASE_WINDOW : DECREASE_WINDOW;
    } else {
      // prefer the region whose hits increases more per resource given
      double windowHitRate = (double) hitsInWindow / sampleCount;
      double windowHitDensity = (windowHitRate / windowSize);
      double mainHitRate = (double) hitsInMain / sampleCount;
      double mainHitDensity = (mainHitRate / (probationSize + protectedSize));
      direction = (windowHitDensity > mainHitDensity) ? INCREASE_WINDOW : DECREASE_WINDOW;
    }
    switch (direction) {
      case HOLD:
        return Adaptation.hold();
      case INCREASE_WINDOW:
        return Adaptation.increaseWindow(Math.abs(Adaptation.roundToInt(amount)));
      case DECREASE_WINDOW:
        return Adaptation.decreaseWindow(Math.abs(Adaptation.roundToInt(amount)));
      default:
        throw new IllegalStateException();
    }
  }
}
