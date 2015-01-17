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


/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyStats {
  private final String name;

  private int hitCount;
  private int missCount;
  private int evictionCount;

  public PolicyStats(String name) {
    this.name = name;
  }

  public String name() {
    return name;
  }

  public void recordHit() {
    hitCount++;
  }

  public void recordMiss() {
    missCount++;
  }

  public void recordEviction() {
    evictionCount++;
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

  public long evictionCount() {
    return evictionCount;
  }
}
