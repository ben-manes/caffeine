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

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;

/**
 * Statistics gathered by a policy execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PolicyStats {
  private final Stopwatch stopwatch;

  private String name;
  private long hitCount;
  private long missCount;
  private long evictionCount;
  private long admittedCount;
  private long rejectedCount;
  private long operationCount;

  public PolicyStats(String name) {
    this.name = requireNonNull(name);
    this.stopwatch = Stopwatch.createUnstarted();
  }

  public Stopwatch stopwatch() {
    return stopwatch;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = requireNonNull(name);
  }

  public void recordOperation() {
    operationCount++;
  }

  public long operationCount() {
    return operationCount;
  }

  public void addOperations(long operations) {
    operationCount += operations;
  }

  public void recordHit() {
    hitCount++;
  }

  public long hitCount() {
    return hitCount;
  }

  public void addHits(long hits) {
    hitCount += hits;
  }

  public void recordMiss() {
    missCount++;
  }

  public long missCount() {
    return missCount;
  }

  public void addMisses(long misses) {
    missCount += misses;
  }

  public long evictionCount() {
    return evictionCount;
  }

  public void recordEviction() {
    evictionCount++;
  }

  public void addEvictions(long evictions) {
    evictionCount += evictions;
  }

  public long requestCount() {
    return hitCount + missCount;
  }

  public long admissionCount() {
    return admittedCount;
  }

  public void recordAdmission() {
    admittedCount++;
  }

  public long rejectionCount() {
    return rejectedCount;
  }

  public void recordRejection() {
    rejectedCount++;
  }

  public double hitRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 1.0 : (double) hitCount / requestCount;
  }

  public double missRate() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : (double) missCount / requestCount;
  }

  public double admissionRate() {
    long candidateCount = admittedCount + rejectedCount;
    return (candidateCount == 0) ? 1.0 : (double) admittedCount / candidateCount;
  }

  public double complexity() {
    long requestCount = requestCount();
    return (requestCount == 0) ? 0.0 : (double) operationCount / requestCount;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).addValue(name).toString();
  }
}
