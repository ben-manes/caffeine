/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import javax.annotation.Nonnegative;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.TinyLfuSettings.DoorkeeperSettings;
import com.github.benmanes.caffeine.cache.simulator.membership.FilterType;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.typesafe.config.Config;

/**
 * A sketch where the aging process is a periodic reset.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PeriodicResetCountMin4 extends CountMin4 {
  static final long ONE_MASK = 0x1111111111111111L;

  final Membership doorkeeper;

  int additions;
  int period;

  public PeriodicResetCountMin4(Config config) {
    super(config);

    BasicSettings settings = new BasicSettings(config);
    DoorkeeperSettings doorkeeperSettings = settings.tinyLfu().countMin4().periodic().doorkeeper();
    if (doorkeeperSettings.enabled()) {
      FilterType filterType = settings.membershipFilter();
      double expectedInsertionsMultiplier = doorkeeperSettings.expectedInsertionsMultiplier();
      long expectedInsertions = (long) (expectedInsertionsMultiplier * settings.maximumSize());
      doorkeeper = filterType.create(expectedInsertions, doorkeeperSettings.fpp(), config);
    } else {
      doorkeeper = Membership.disabled();
    }
  }

  @Override
  protected void ensureCapacity(@Nonnegative long maximumSize) {
    super.ensureCapacity(maximumSize);
    period = (maximumSize == 0) ? 10 : (10 * table.length);
    if (period <= 0) {
      period = Integer.MAX_VALUE;
    }
  }

  @Override
  public int frequency(long e) {
    int count = super.frequency(e);
    if (doorkeeper.mightContain(e)) {
      count++;
    }
    return count;
  }

  @Override
  public void increment(long e) {
    if (!doorkeeper.put(e)) {
      super.increment(e);
    }
  }

  /**
   * Reduces every counter by half of its original value. To reduce the truncation error, the sample
   * is reduced by the number of counters with an odd value.
   */
  @Override
  protected void tryReset(boolean added) {
    if (!added) {
      return;
    }

    additions++;
    if (additions != period) {
      return;
    }

    int count = 0;
    for (int i = 0; i < table.length; i++) {
      count += Long.bitCount(table[i] & ONE_MASK);
      table[i] = (table[i] >>> 1) & RESET_MASK;
    }
    additions = (additions >>> 1) - (count >>> 2);
    doorkeeper.clear();
  }
}
