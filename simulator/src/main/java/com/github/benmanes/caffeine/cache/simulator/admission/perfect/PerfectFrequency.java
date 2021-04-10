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
package com.github.benmanes.caffeine.cache.simulator.admission.perfect;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * A the perfect frequency with aging performed using a periodic reset.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PerfectFrequency implements Frequency {
  private final Long2IntMap counts;
  private final int sampleSize;

  private int size;

  public PerfectFrequency(Config config) {
    BasicSettings settings = new BasicSettings(config);
    sampleSize = Ints.checkedCast(10 * settings.maximumSize());
    counts = new Long2IntOpenHashMap();
  }

  @Override
  public int frequency(long e) {
    return counts.get(e);
  }

  @Override
  public void increment(long e) {
    counts.put(e, counts.get(e) + 1);

    size++;
    if (size == sampleSize) {
      reset();
    }
  }

  private void reset() {
    for (Long2IntMap.Entry entry : counts.long2IntEntrySet()) {
      entry.setValue(entry.getIntValue() / 2);
    }
    size = (size / 2);
  }
}
