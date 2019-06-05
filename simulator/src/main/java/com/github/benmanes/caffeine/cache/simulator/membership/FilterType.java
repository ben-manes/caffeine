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

import java.util.function.Function;

import com.github.benmanes.caffeine.cache.simulator.membership.bloom.BloomFilter;
import com.github.benmanes.caffeine.cache.simulator.membership.bloom.FastFilter;
import com.github.benmanes.caffeine.cache.simulator.membership.bloom.GuavaBloomFilter;
import com.typesafe.config.Config;

/**
 * The membership filters.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
public enum FilterType {
  CAFFEINE(BloomFilter::new),
  FAST_FILTER(FastFilter::new),
  GUAVA(GuavaBloomFilter::new);

  private final Function<Config, Membership> factory;

  FilterType(Function<Config, Membership> factory) {
    this.factory = factory;
  }

  public Membership create(Config config) {
    return factory.apply(config);
  }
}
