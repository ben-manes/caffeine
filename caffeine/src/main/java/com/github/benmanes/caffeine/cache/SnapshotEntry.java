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

import com.github.benmanes.caffeine.cache.Policy.CacheEntry;


/**
 * An immutable entry that includes a snapshot of the policy metadata at its time of creation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
class SnapshotEntry<K, V> implements CacheEntry<K, V> {
  private final long snapshot;
  private final V value;
  private final K key;

  SnapshotEntry(K key, V value, long snapshot) {
    this.snapshot = snapshot;
    this.key = requireNonNull(key);
    this.value = requireNonNull(value);
  }

  @Override
  public K getKey() {
    return key;
  }

  @Override
  public V getValue() {
    return value;
  }

  @Override
  public V setValue(V value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int weight() {
    return 1;
  }

  @Override
  public long expiresAt() {
    return snapshot + Long.MAX_VALUE;
  }

  @Override
  public long refreshableAt() {
    return snapshot + Long.MAX_VALUE;
  }

  @Override
  public long snapshotAt() {
    return snapshot;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof Map.Entry)) {
      return false;
    }
    var entry = (Map.Entry<?, ?>) o;
    return key.equals(entry.getKey()) && value.equals(entry.getValue());
  }

  @Override
  public int hashCode() {
    return key.hashCode() ^ value.hashCode();
  }

  @Override
  public String toString() {
    return key + "=" + value;
  }

  static class WeightedEntry<K, V> extends SnapshotEntry<K, V> {
    final int weight;

    WeightedEntry(K key, V value, long snapshot, int weight) {
      super(key, value, snapshot);
      this.weight = weight;
    }

    @Override
    public int weight() {
      return weight;
    }
  }

  static class ExpirableEntry<K, V> extends SnapshotEntry<K, V> {
    final long expiresAt;

    ExpirableEntry(K key, V value, long snapshot, long expiresAt) {
      super(key, value, snapshot);
      this.expiresAt = expiresAt;
    }

    @Override
    public long expiresAt() {
      return expiresAt;
    }
  }

  static class ExpirableWeightedEntry<K, V> extends WeightedEntry<K, V> {
    final long expiresAt;

    ExpirableWeightedEntry(K key, V value, long snapshot, int weight, long expiresAt) {
      super(key, value, snapshot, weight);
      this.expiresAt = expiresAt;
    }

    @Override
    public long expiresAt() {
      return expiresAt;
    }
  }

  static class RefreshableExpirableEntry<K, V> extends ExpirableEntry<K, V> {
    final long refreshableAt;

    RefreshableExpirableEntry(K key, V value, long snapshot, long expiresAt, long refreshableAt) {
      super(key, value, snapshot, expiresAt);
      this.refreshableAt = refreshableAt;
    }

    @Override
    public long refreshableAt() {
      return refreshableAt;
    }
  }

  static final class CompleteEntry<K, V> extends ExpirableWeightedEntry<K, V> {
    final long refreshableAt;

    CompleteEntry(K key, V value, long snapshot, int weight, long expiresAt, long refreshableAt, long at) {
      super(key, value, snapshot, weight, expiresAt);
      this.refreshableAt = refreshableAt;
    }

    @Override
    public long refreshableAt() {
      return refreshableAt;
    }
  }
}