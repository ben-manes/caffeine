/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Objects;

import com.google.common.base.MoreObjects;

/**
 * The key and metadata for accessing a cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class AccessEvent {
  private final long key;

  private AccessEvent(long key) {
    this.key = key;
  }

  /** Returns the key. */
  public Long key() {
    return key;
  }

  /** Returns the weight of the entry. */
  public int weight() {
    return 1;
  }

  /** Returns the hit penalty of the entry. */
  public double hitPenalty() {
    return 0;
  }

  /** Returns the miss penalty of the entry. */
  public double missPenalty() {
    return 0;
  }

  /** Returns if the trace supplies the hit/miss penalty for this entry. */
  public boolean isPenaltyAware() {
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof AccessEvent)) {
      return false;
    }
    AccessEvent event = (AccessEvent) o;
    return Objects.equals(key(), event.key())
        && Objects.equals(weight(), event.weight())
        && Objects.equals(hitPenalty(), event.hitPenalty())
        && Objects.equals(missPenalty(), event.missPenalty());
  }

  @Override
  public int hashCode() {
    return Objects.hash(key(), weight(), missPenalty(), hitPenalty());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("key", key())
        .add("weight", weight())
        .add("hit penalty", hitPenalty())
        .add("miss penalty", missPenalty())
        .toString();
  }

  /** Returns an event for the given key. */
  public static AccessEvent forKey(long key) {
    return new AccessEvent(key);
  }

  /** Returns an event for the given key and weight. */
  public static AccessEvent forKeyAndWeight(long key, int weight) {
    return new WeightedAccessEvent(key, weight);
  }

  /** Returns an event for the given key and penalties. */
  public static AccessEvent forKeyAndPenalties(long key, double hitPenalty, double missPenalty) {
    return new PenaltiesAccessEvent(key, hitPenalty, missPenalty);
  }

  private static final class WeightedAccessEvent extends AccessEvent {
    private final int weight;

    WeightedAccessEvent(long key, int weight) {
      super(key);
      this.weight = weight;
      checkArgument(weight >= 0);
    }
    @Override public int weight() {
      return weight;
    }
  }

  private static final class PenaltiesAccessEvent extends AccessEvent {
    private final double missPenalty;
    private final double hitPenalty;

    PenaltiesAccessEvent(long key, double hitPenalty, double missPenalty) {
      super(key);
      this.hitPenalty = hitPenalty;
      this.missPenalty = missPenalty;
      checkArgument(hitPenalty >= 0);
      checkArgument(missPenalty >= hitPenalty);
    }
    @Override public double missPenalty() {
      return missPenalty;
    }
    @Override public double hitPenalty() {
      return hitPenalty;
    }
    @Override public boolean isPenaltyAware() {
      return true;
    }
  }
}
