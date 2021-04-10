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
package com.github.benmanes.caffeine.cache;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A facade for benchmark implementations.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public interface BasicCache<K extends @NonNull Object, V extends @NonNull Object> {

  /** Returns the value stored in the cache, or null if not present. */
  @Nullable V get(K key);

  /** Stores the value into the cache, replacing an existing mapping if present. */
  void put(K key, V value);

  /** Removes the entry from the cache, if present. */
  void remove(K key);

  /** Invalidates all entries from the cache. */
  void clear();

  /** Performs any pending maintenance operations needed by the cache. */
  default void cleanUp() {}
}
