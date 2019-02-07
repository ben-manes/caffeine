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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.typesafe.config.Config;

/**
 * A sketch where the aging process is an incremental reset.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IncrementalResetCountMin4 extends CountMin4 {
  final int interval;

  int additions;
  int cursor;

  public IncrementalResetCountMin4(Config config) {
    super(config);
    BasicSettings settings = new BasicSettings(config);
    interval = settings.tinyLfu().countMin4().incremental().interval();
    cursor = settings.randomSeed();
  }

  @Override
  protected void tryReset(boolean added) {
    if (!added) {
      return;
    }

    additions++;

    if (additions != interval) {
      return;
    }

    int i = cursor & tableMask;
    table[i] = (table[i] >>> 1) & RESET_MASK;

    cursor++;
    additions = 0;
  }
}
