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
  private final Long key;

  private AccessEvent(long key) {
    this.key = key;
  }

  /** Returns the key. */
  public long key() {
    return key;
  }

  /** Returns the weight of the entry. */
  public int weight() {
    return 1;
  }

  /** Returns the miss penalty of the entry */
  public int missPenalty() {
    return 1;
  }

  /** Returns the hit penalty of the entry */
  public int hitPenalty() {
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof AccessEvent)) {
      return false;
    }
    AccessEvent event = (AccessEvent) o;
    return Objects.equals(key(), event.key()) && Objects.equals(weight(), event.weight());
  }

  @Override
  public int hashCode() {
    return Objects.hash(key(), weight());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("key", key())
        .add("weight", weight())
        .add("miss penalty", missPenalty())
        .add("hit penalty", hitPenalty())
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
  public static AccessEvent forKeyAndPenalties(long key, int hitPenalty, int missPenalty) {
    return new PenaltiesAccessEvent(key, hitPenalty, missPenalty);
  }

  private static final class WeightedAccessEvent extends AccessEvent {
    private final int weight;

    WeightedAccessEvent(long key, int weight) {
      super(key);
      this.weight = weight;
      checkArgument(weight >= 0);
    }

    @Override
    public int weight() {
      return weight;
    }
  }

  private static final class PenaltiesAccessEvent extends AccessEvent {
    private final int missPenalty;
    private final int hitPenalty;

    PenaltiesAccessEvent(long key, int hitPenalty, int missPenalty) {
      super(key);
      this.missPenalty = missPenalty;
      this.hitPenalty = hitPenalty;
      checkArgument(hitPenalty >= 0);
      checkArgument(missPenalty > hitPenalty);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (!(o instanceof PenaltiesAccessEvent)) {
        return false;
      }
      PenaltiesAccessEvent event = (PenaltiesAccessEvent) o;
      return super.equals(o)
          && Objects.equals(missPenalty(), event.missPenalty())
          && Objects.equals(hitPenalty(), event.hitPenalty());
    }

    @Override
    public int hashCode() {
      return Objects.hash(key(), weight(), missPenalty(), hitPenalty());
    }

    @Override
    public int missPenalty() {
      return missPenalty;
    }

    @Override
    public int hitPenalty() {
      return hitPenalty;
    }
  }
}
