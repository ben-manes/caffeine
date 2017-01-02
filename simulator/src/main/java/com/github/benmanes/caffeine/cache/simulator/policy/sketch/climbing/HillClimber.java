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
interface HillClimber {

  /**
   * Records that a hit occurred.
   *
   * @param key the key accessed
   * @param queue the queue the entry was found in
   */
  void onHit(long key, QueueType queue);

  /**
   * Records that a miss occurred.
   *
   * @param key the key accessed
   */
  void onMiss(long key);

  /**
   * Determines how to adapt the segment sizes.
   *
   * @param windowSize the current window size
   * @param protectedSize the current protected size
   * @return the adjustment to the segments
   */
  Adaptation adapt(int windowSize, int protectedSize);

  enum QueueType {
    WINDOW, PROBATION, PROTECTED
  }

  /** The adaptation type and its magnitude. */
  final class Adaptation {
    enum Type { HOLD, INCREASE_WINDOW, DECREASE_WINDOW }
    static final Adaptation HOLD = new Adaptation(Type.HOLD, 0);

    final int amount;
    final Type type;

    Adaptation(Type type, int amount) {
      this.type = checkNotNull(type);
      checkArgument(amount >= 0);
      this.amount = amount;
    }
  }
}
