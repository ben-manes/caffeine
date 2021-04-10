/*
 * Copyright 2015 Gilga Einziger. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.admission.tinycache;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * The TinyCache admission policy.
 *
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class TinyCacheAdapter implements Frequency {
  // the actual data structure.
  final TinyCacheSketch tcs;
  // size between cache and sample.
  static final int sampleFactor = 10;
  // max frequency estimation of an item.
  static final int maxcount = 10;

  /**
   * Note that in this implementation there are always 64 items per set.
   */
  public TinyCacheAdapter(Config config) {
    BasicSettings settings = new BasicSettings(config);
    // number of (independent sets)
    int nrSets = Ints.checkedCast(sampleFactor * settings.maximumSize() / 64);
    tcs = new TinyCacheSketch(nrSets, 64,settings.randomSeed());
  }

  @Override
  public int frequency(long e) {
    return tcs.countItem(e);
  }

  @Override
  public void increment(long e) {
    if (tcs.countItem(e) < maxcount) {
      tcs.addItem(e);
    }
  }
}
