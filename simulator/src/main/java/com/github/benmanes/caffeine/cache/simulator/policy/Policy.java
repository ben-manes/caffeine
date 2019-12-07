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
package com.github.benmanes.caffeine.cache.simulator.policy;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;

import java.util.Set;

/**
 * A cache that implements a page replacement policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface Policy {

  /** Records that the entry was accessed. */
  void record(AccessEvent entry);

  /** Indicates that the recording has completed. */
  default void finished() {}

  /** Returns the cache efficiency statistics. */
  PolicyStats stats();

  /** Returns the policy's set of supported characteristics. */
  Set<Characteristics> getCharacteristicsSet();
}
