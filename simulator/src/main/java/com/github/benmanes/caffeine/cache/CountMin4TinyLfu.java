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
package com.github.benmanes.caffeine.cache;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.typesafe.config.Config;

/**
 * An adapter to expose the 4-bit CountMinSketch used by the cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CountMin4TinyLfu<E> implements Frequency<E> {
  private static final int RANDOM_SEED = 1033096058;

  private final FrequencySketch<E> sketch;

  public CountMin4TinyLfu(Config config) {
    BasicSettings settings = new BasicSettings(config);
    sketch = new FrequencySketch<>(settings.maximumSize(), RANDOM_SEED);
  }

  @Override
  public int frequency(E e) {
    return sketch.frequency(e);
  }

  @Override
  public void increment(E e) {
    sketch.increment(e);
  }
}
