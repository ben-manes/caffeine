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
package com.github.benmanes.caffeine.cache.simulator.admission;

import com.clearspring.analytics.stream.frequency.CountMin64TinyLfu;
import com.github.benmanes.caffeine.cache.CountMin4TinyLfu;
import com.github.benmanes.caffeine.cache.RandomRemovalFrequencyTable;
import com.github.benmanes.caffeine.cache.TinyCacheAdapter;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Admits new entries based on the estimated frequency of its historic use.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TinyLfu implements Admittor {
  private final Frequency sketch;

  public TinyLfu(Config config) {
    BasicSettings settings = new BasicSettings(config);
    String type = settings.tinyLfu().sketch();
    if (type.equalsIgnoreCase("count-min-4")) {
      sketch = new CountMin4TinyLfu(config);
    } else if (type.equalsIgnoreCase("count-min-64")) {
      sketch = new CountMin64TinyLfu(config);
    } else if (type.equalsIgnoreCase("random-table")) {
      sketch = new RandomRemovalFrequencyTable(config);
    } else if (type.equalsIgnoreCase("tiny-table")) {
      sketch = new TinyCacheAdapter(config);
    } else if (type.equalsIgnoreCase("perfect-table")) {
      sketch = new PerfectTinyLfu(config);
    } else {
      throw new IllegalStateException("Unknown sketch type: " + type);
    }
  }

  @Override
  public void record(long key) {
    sketch.increment(key);
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    long candidateFreq = sketch.frequency(candidateKey);
    long victimFreq = sketch.frequency(victimKey);
    return candidateFreq > victimFreq;
  }

  private static final class PerfectTinyLfu implements Frequency {
    private final Long2IntMap counts;
    private final int sampleSize;

    private int size;

    PerfectTinyLfu(Config config) {
      sampleSize = 10 * new BasicSettings(config).maximumSize();
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
        entry.setValue(entry.getValue() / 2);
      }
      size = (size / 2);
    }
  }
}
