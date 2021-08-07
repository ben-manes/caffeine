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

import java.util.AbstractMap.SimpleImmutableEntry;

import org.checkerframework.checker.nullness.qual.Nullable;

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
public final class RemovalNotification<K, V> extends SimpleImmutableEntry<K, V> {
  private static final long serialVersionUID = 1L;

  private final RemovalCause cause;

  /**
   * Creates an entry representing a mapping from the specified key to the specified value.
   *
   * @param key the key represented by this entry
   * @param value the value represented by this entry
   * @param cause the reason for which the entry was removed
   */
  public RemovalNotification(@Nullable K key, @Nullable V value, RemovalCause cause) {
    super(key, value);
    this.cause = requireNonNull(cause);
  }

  /**
   * @return the cause for which the entry was removed
   */
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

  @Override
  public String toString() {
    return getKey() + "=" + getValue() + " [" + cause + "]";
  }
}
