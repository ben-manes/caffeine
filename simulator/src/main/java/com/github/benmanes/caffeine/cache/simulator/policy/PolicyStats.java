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

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;

/**
 * Statistics gathered by a policy execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyStats {
  private final String name;

  private long hitCount;
  private long missCount;
  private long evictionCount;

  private Stopwatch stopwatch;

  public PolicyStats(String name) {
    this.stopwatch = Stopwatch.createUnstarted();
    this.name = name;
  }

  public Stopwatch stopwatch() {
    return stopwatch;
  }

  public String name() {
    return name;
  }

  public void recordHit() {
    hitCount++;
  }

  public void setHitCount(long hitCount) {
    this.hitCount = hitCount;
  }

  public void recordMiss() {
    missCount++;
  }

  public void setMissCount(long missCount) {
    this.missCount = missCount;
  }

  public void recordEviction() {
    evictionCount++;
  }

  public void setEvictionCount(long evictionCount) {
    this.evictionCount = evictionCount;
  }

  public long evictionCount() {
    return evictionCount;
  }

  public long requestCount() {
    return hitCount + missCount;
  }

  public double hitRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 1.0 : (double) hitCount / requestCount;
  }

  public double missRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : (double) missCount / requestCount;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).addValue(name).toString();
  }
}
