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

import com.clearspring.analytics.stream.frequency.CountMinTinyLfu;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.typesafe.config.Config;

/**
 * Admits new entries based on the estimated frequency of its historic use.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TinyLfu implements Admittor {
  private static final int RANDOM_SEED = 1033096058;

  private final CountMinTinyLfu sketch;

  public TinyLfu(Config config) {
    BasicSettings settings = new BasicSettings(config);
    sketch = new CountMinTinyLfu(settings.admission().eps(),
        settings.admission().confidence(), RANDOM_SEED, settings.admission().sampleSize());
  }

  @Override
  public void record(Object key) {
    sketch.add(key.hashCode(), 1);
  }

  @Override
  public boolean admit(Object candidateKey, Object victimKey) {
    long candidateCount = sketch.estimateCount(candidateKey.hashCode());
    long victimCount = sketch.estimateCount(victimKey.hashCode());
    return candidateCount >= victimCount;
  }
}
