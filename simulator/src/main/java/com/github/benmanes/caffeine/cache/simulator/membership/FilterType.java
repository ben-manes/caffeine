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
package com.github.benmanes.caffeine.cache.simulator.membership;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.membership.bloom.BloomFilter;
import com.github.benmanes.caffeine.cache.simulator.membership.bloom.GuavaBloomFilter;
import com.typesafe.config.Config;

/**
 * The membership filters.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum FilterType {
  CAFFEINE {
    @Override public Membership create(long expectedInsertions, double fpp, Config config) {
      int randomSeed = new BasicSettings(config).randomSeed();
      return new BloomFilter(expectedInsertions, fpp, randomSeed);
    }
    @Override public String toString() {
      return "Caffeine";
    }
  },
  GUAVA {
    @Override public Membership create(long expectedInsertions, double fpp, Config config) {
      return new GuavaBloomFilter(expectedInsertions, fpp);
    }
    @Override public String toString() {
      return "Guava";
    }
  };

  /**
   * Returns a new membership filter.
   *
   * @param expectedInsertions the number of expected insertions
   * @param fpp the desired false positive probability
   * @param config the simulator's configuration
   * @return a membership filter
   */
  public abstract Membership create(long expectedInsertions, double fpp, Config config);
}
