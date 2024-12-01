/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Policy.CacheEntry;
import com.google.errorprone.annotations.Immutable;

/**
 * An immutable entry that includes a snapshot of the policy metadata at its time of creation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Immutable(containerOf = {"K", "V"})
class SnapshotEntry<K, V> implements CacheEntry<K, V> {
  private final long snapshot;
  private final V value;
  private final K key;

  SnapshotEntry(K key, V value, long snapshot) {
    this.snapshot = snapshot;
    this.key = requireNonNull(key);
    this.value = requireNonNull(value);
  }
  @Override public final K getKey() {
    return key;
  }
  @Override public final V getValue() {
    return value;
  }
  @Override public V setValue(V value) {
    throw new UnsupportedOperationException();
  }
  @Override public int weight() {
    return 1;
  }
  @Override public long expiresAt() {
    return snapshot + Long.MAX_VALUE;
  }
  @Override public long refreshableAt() {
    return snapshot + Long.MAX_VALUE;
  }
  @Override public final long snapshotAt() {
    return snapshot;
  }
  @Override public final boolean equals(@Nullable Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof Map.Entry)) {
      return false;
    }
    var entry = (Map.Entry<?, ?>) o;
    return key.equals(entry.getKey()) && value.equals(entry.getValue());
  }
  @Override public final int hashCode() {
    return key.hashCode() ^ value.hashCode();
  }
  @Override public final String toString() {
    return key + "=" + value;
  }

  /** Returns a cache entry containing the given key, value, and snapshot. */
  public static <K, V> SnapshotEntry<K, V> forEntry(K key, V value) {
    return new SnapshotEntry<>(key, value, /* snapshot= */ 0);
  }

  /** Returns a cache entry with the specified metadata. */
  public static <K, V> SnapshotEntry<K, V> forEntry(K key, V value,
      long snapshot, int weight, long expiresAt, long refreshableAt) {
    long unsetTicks = snapshot + Long.MAX_VALUE;
    boolean refresh = (refreshableAt != unsetTicks);
    boolean expires = (expiresAt != unsetTicks);
    boolean weights = (weight != 1);
    int features = // truth table
          (weights ? 0b001 : 0b000)
        | (expires ? 0b010 : 0b000)
        | (refresh ? 0b100 : 0b000);
    switch (features) { // optimized for common cases
      case 0b000: return new SnapshotEntry<>(key, value, snapshot);
      case 0b001: return new WeightedEntry<>(key, value, snapshot, weight);
      case 0b010: return new ExpirableEntry<>(key, value, snapshot, expiresAt);
      case 0b011: return new ExpirableWeightedEntry<>(key, value, snapshot, weight, expiresAt);
      case 0b110: return new RefreshableExpirableEntry<>(
          key, value, snapshot, expiresAt, refreshableAt);
      default: return new CompleteEntry<>(key, value, snapshot, weight, expiresAt, refreshableAt);
    }
  }

  static class WeightedEntry<K, V> extends SnapshotEntry<K, V> {
    final int weight;

    WeightedEntry(K key, V value, long snapshot, int weight) {
      super(key, value, snapshot);
      this.weight = weight;
    }
    @Override public final int weight() {
      return weight;
    }
  }

  static class ExpirableEntry<K, V> extends SnapshotEntry<K, V> {
    final long expiresAt;

    ExpirableEntry(K key, V value, long snapshot, long expiresAt) {
      super(key, value, snapshot);
      this.expiresAt = expiresAt;
    }
    @Override public final long expiresAt() {
      return expiresAt;
    }
  }

  static class ExpirableWeightedEntry<K, V> extends WeightedEntry<K, V> {
    final long expiresAt;

    ExpirableWeightedEntry(K key, V value, long snapshot, int weight, long expiresAt) {
      super(key, value, snapshot, weight);
      this.expiresAt = expiresAt;
    }
    @Override public final long expiresAt() {
      return expiresAt;
    }
  }

  static class RefreshableExpirableEntry<K, V> extends ExpirableEntry<K, V> {
    final long refreshableAt;

    RefreshableExpirableEntry(K key, V value, long snapshot, long expiresAt, long refreshableAt) {
      super(key, value, snapshot, expiresAt);
      this.refreshableAt = refreshableAt;
    }
    @Override public final long refreshableAt() {
      return refreshableAt;
    }
  }

  static final class CompleteEntry<K, V> extends ExpirableWeightedEntry<K, V> {
    final long refreshableAt;

    CompleteEntry(K key, V value, long snapshot, int weight, long expiresAt, long refreshableAt) {
      super(key, value, snapshot, weight, expiresAt);
      this.refreshableAt = refreshableAt;
    }
    @Override public long refreshableAt() {
      return refreshableAt;
    }
  }
}
