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
package com.github.benmanes.caffeine.cache.simulator.membership.bloom;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.MembershipSettings;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.typesafe.config.Config;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GuavaBloomFilter implements Membership {
  private final long expectedInsertions;
  private final double fpp;

  private BloomFilter<Long> bloomFilter;

  public GuavaBloomFilter(Config config) {
    MembershipSettings settings = new BasicSettings(config).membership();
    this.expectedInsertions = settings.expectedInsertions();
    this.fpp = settings.fpp();
    reset();
  }

  @Override
  public boolean mightContain(long e) {
    return bloomFilter.mightContain(e);
  }

  @Override
  public void clear() {
    reset();
  }

  @Override
  public boolean put(long e) {
    return bloomFilter.put(e);
  }

  private void reset() {
    bloomFilter = BloomFilter.create(Funnels.longFunnel(), expectedInsertions, fpp);
  }
}
