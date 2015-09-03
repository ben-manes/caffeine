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
package com.github.benmanes.caffeine.cache.simulator.policy.opt;

import java.util.HashSet;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

/**
 * A cache that has no maximum size. This demonstrates the upper bound of the hit rate due to
 * compulsory misses (first reference misses), which can only be avoided if the application can
 * intelligently prefetch the data prior to the request.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class UnboundedPolicy implements Policy {
  private final PolicyStats policyStats;
  private final Set<Object> data;

  public UnboundedPolicy(String name) {
    this.policyStats = new PolicyStats(name);
    this.data = new HashSet<>();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(Comparable<Object> key) {
    policyStats.recordOperation();
    if (data.add(key)) {
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
    }
  }
}
