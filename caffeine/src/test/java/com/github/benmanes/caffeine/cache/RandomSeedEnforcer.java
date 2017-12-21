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

import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.google.common.base.Throwables;

/**
 * A hook to enforce a predictable random seed is used by Caffeine's caches.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RandomSeedEnforcer {
  static final long PROBE = UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomProbe");
  static final long SEED = UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomSeed");
  static final int RANDOM_SEED = 1033096058;

  private RandomSeedEnforcer() {}

  /** Force the random seed to a predictable value. */
  public static void ensureRandomSeed(Cache<?, ?> cache) {
    if (!(cache.asMap() instanceof BoundedLocalCache<?, ?>)) {
      return;
    }

    BoundedLocalCache<?, ?> map = (BoundedLocalCache<?, ?>) cache.asMap();
    resetThreadLocalRandom();
    if (map.evicts()) {
      ensureRandomSeed(map.frequencySketch());
    }
  }

  /** Forces the eviction jitter to be predictable. */
  private static void resetThreadLocalRandom() {
    UnsafeAccess.UNSAFE.putInt(Thread.currentThread(), PROBE, 0x9e3779b9);
    UnsafeAccess.UNSAFE.putLong(Thread.currentThread(), SEED, RANDOM_SEED);
  }

  /** Force the random seed to a predictable value. */
  public static void ensureRandomSeed(FrequencySketch<?> sketch) {
    try {
      Field field = FrequencySketch.class.getDeclaredField("randomSeed");
      field.setAccessible(true);
      field.setInt(sketch, RANDOM_SEED);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }
}
