/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import java.lang.reflect.Field;

import com.google.common.base.Throwables;

/**
 * A hook to enforce a predictable random seed is used by Caffeine's caches.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RandomSeedEnforcer {
  static final int RANDOM_SEED = 1033096058;

  private RandomSeedEnforcer() {}

  /** Force the random seed to a predictable value. */
  public static void ensureRandomSeed(Cache<?, ?> cache) {
    if (!(cache.asMap() instanceof BoundedLocalCache<?, ?>)) {
      return;
    }

    BoundedLocalCache<?, ?> localCache = (BoundedLocalCache<?, ?>) cache.asMap();
    if (!localCache.evicts()) {
      return;
    }

    try {
      Field field = FrequencySketch.class.getDeclaredField("randomSeed");
      field.setAccessible(true);
      field.setInt(localCache.frequencySketch(), RANDOM_SEED);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}
