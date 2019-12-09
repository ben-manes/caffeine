/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;

import java.util.Map.Entry;
import java.util.Objects;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.errorprone.annotations.Immutable;

/**
 * A notification of the removal of a single entry. The key and/or value may be null if they were
 * already garbage collected. This class holds strong references to the key and value, regardless
 * of the type of references the cache may be using.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Immutable(containerOf = {"K", "V"})
public final class RemovalNotification<K, V> implements Entry<K, V> {
  @Nullable private final K key;
  @Nullable private final V value;
  private final RemovalCause cause;

  /**
   * Creates an entry representing a mapping from the specified key to the specified value.
   *
   * @param key the key represented by this entry
   * @param value the value represented by this entry
   * @param cause the reason for which the entry was removed
   */
  public RemovalNotification(@Nullable K key, @Nullable V value, @NonNull RemovalCause cause) {
    this.cause = requireNonNull(cause);
    this.value = value;
    this.key = key;
  }

  /**
   * @return the cause for which the entry was removed
   */
  @NonNull
  public RemovalCause getCause() {
    return cause;
  }

  /**
   * Returns {@code true} if there was an automatic removal due to eviction (the cause is neither
   * {@link RemovalCause#EXPLICIT} nor {@link RemovalCause#REPLACED}).
   *
   * @return if the entry was removed due to eviction
   */
  public boolean wasEvicted() {
    return cause.wasEvicted();
  }

  /**
   * Returns the key of the removed entry or null if it was garbage collected due to
   * {@link Caffeine#weakKeys()} eviction.
   */
  @Override @Nullable
  public K getKey() {
    return key;
  }

  /**
   * Returns the key of the removed entry or null if it was garbage collected due to
   * {@link Caffeine#weakValues()} or {@link Caffeine#softValues()} eviction.
   */
  @Override @Nullable
  public V getValue() {
    return value;
  }

  @Override
  public V setValue(V value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof Entry<?, ?>)) {
      return false;
    }
    Entry<?, ?> entry = (Entry<?, ?>) o;
    return Objects.equals(key, entry.getKey())
        && Objects.equals(value, entry.getValue());
  }

  @Override
  public int hashCode() {
    return ((key == null) ? 0 : key.hashCode()) ^ ((value == null) ? 0 : value.hashCode());
  }

  /** Returns a string representation of the form <code>{key}={value}</code>. */
  @Override
  public String toString() {
    return key + "=" + value;
  }
}
