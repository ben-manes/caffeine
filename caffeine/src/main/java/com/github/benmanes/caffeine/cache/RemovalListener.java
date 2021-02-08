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
package com.github.benmanes.caffeine.cache;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that can receive a notification when an entry is removed from a cache. The removal
 * resulting in notification could have occurred to an entry being manually removed or replaced, or
 * due to eviction resulting from timed expiration, exceeding a maximum size, or garbage collection.
 * <p>
 * An instance may be called concurrently by multiple threads to process different entries.
 * Implementations of this interface should avoid performing blocking calls or synchronizing on
 * shared resources.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the most general type of keys this listener can listen for; for example {@code Object}
 *        if any key is acceptable
 * @param <V> the most general type of values this listener can listen for; for example
 *        {@code Object} if any value is acceptable
 */
@FunctionalInterface
public interface RemovalListener<K, V> {

  /**
   * Notifies the listener that a removal occurred at some point in the past.
   * <p>
   * This does not always signify that the key is now absent from the cache, as it may have already
   * been re-added.
   *
   * @param key the key represented by this entry, or {@code null} if collected
   * @param value the value represented by this entry, or {@code null} if collected
   * @param cause the reason for which the entry was removed
   */
  void onRemoval(@Nullable K key, @Nullable V value, RemovalCause cause);
}
