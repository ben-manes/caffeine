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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A hill climbing algorithm to tune the admission window size.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface HillClimber {

  /**
   * Records that a hit occurred with a full cache.
   *
   * @param key the key accessed
   * @param queue the queue the entry was found in
   * @param isFull if the cache is fully populated
   */
  void onHit(long key, QueueType queue, boolean isFull);

  /**
   * Records that a miss occurred with a full cache.
   *
   * @param key the key accessed
   * @param isFull if the cache is fully populated and had to evict
   */
  void onMiss(long key, boolean isFull);

  /**
   * Determines how to adapt the segment sizes.
   *
   * @param windowSize the current window size
   * @param protectedSize the current protected size
   * @param isFull if the cache is fully populated
   * @return the adjustment to the segments
   */
  Adaptation adapt(int windowSize, int protectedSize, boolean isFull);

  enum QueueType {
    WINDOW, PROBATION, PROTECTED
  }

  /** The adaptation type and its magnitude. */
  final class Adaptation {
    public enum Type {
      HOLD, INCREASE_WINDOW, DECREASE_WINDOW
    }

    private static final Adaptation HOLD = new Adaptation(Type.HOLD, 0);

    public final int amount;
    public final Type type;

    private Adaptation(Type type, int amount) {
      checkArgument(amount >= 0, "Step size %s must be positive", amount);
      this.type = checkNotNull(type);
      this.amount = amount;
    }

    /** Returns the adaption based on the amount, where a negative value decreases the window. */
    public static Adaptation adaptBy(double amount) {
      if (amount == 0) {
        return hold();
      } else if (amount < 0) {
        return decreaseWindow(Math.abs((int) Math.floor(amount)));
      } else {
        return increaseWindow((int) Math.ceil(amount));
      }
    }

    public static Adaptation hold() {
      return HOLD;
    }
    public static Adaptation increaseWindow(int amount) {
      return new Adaptation(Type.INCREASE_WINDOW, amount);
    }
    public static Adaptation decreaseWindow(int amount) {
      return new Adaptation(Type.DECREASE_WINDOW, amount);
    }
  }
}
