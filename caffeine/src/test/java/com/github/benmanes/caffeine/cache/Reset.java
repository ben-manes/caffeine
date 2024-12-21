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
import java.lang.reflect.InaccessibleObjectException;

import com.github.benmanes.caffeine.cache.LocalAsyncCache.AbstractCacheView;

/**
 * A hook to reset internal details of the cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Reset {
  private static final int RANDOM_SEED = 1_033_096_058;
  private static final int RANDOM_PROBE = 0x9e3779b9;

  private Reset() {}

  /** Forces the eviction jitter to be predictable. */
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  public static void resetThreadLocalRandom() {
    try {
      Field probe = Thread.class.getDeclaredField("threadLocalRandomProbe");
      Field seed = Thread.class.getDeclaredField("threadLocalRandomSeed");
      probe.setAccessible(true);
      seed.setAccessible(true);

      probe.setInt(Thread.currentThread(), RANDOM_PROBE);
      seed.setLong(Thread.currentThread(), RANDOM_SEED);
    } catch (InaccessibleObjectException e) {
      throw new AssertionError("Requires --add-opens java.base/java.lang=ALL-UNNAMED", e);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new LinkageError("Failed to set ThreadLocalRandom fields", e);
    }
  }

  /** Clears the internal state of the cache and becomes unusable. */
  public static void destroy(Cache<?, ?> cache) {
    var local = (cache instanceof AbstractCacheView<?, ?>)
        ? ((AbstractCacheView<?, ?>) cache).asyncCache().cache()
        : (LocalCache<?, ?>) cache.asMap();
    if (local instanceof UnboundedLocalCache) {
      var unbounded = (UnboundedLocalCache<?, ?>) local;
      unbounded.data.clear();
    } else if (local instanceof BoundedLocalCache) {
      @SuppressWarnings("unchecked")
      var bounded = (BoundedLocalCache<Object, Object>) local;
      bounded.evictionLock.lock();
      try {
        for (var node : bounded.data.values()) {
          destroyNode(bounded, node);
        }
        if (bounded.expiresVariable()) {
          destroyTimerWheel(bounded);
        }
        bounded.data.clear();
        bounded.writeBuffer.clear();
        bounded.readBuffer.drainTo(e -> {});
      } finally {
        bounded.evictionLock.unlock();
      }
    }
  }

  @SuppressWarnings("GuardedBy")
  private static void destroyNode(BoundedLocalCache<?, ?> bounded, Node<?, ?> node) {
    if (bounded.expiresAfterAccess()) {
      node.setPreviousInAccessOrder(null);
      node.setNextInAccessOrder(null);
    }
    if (bounded.expiresAfterWrite()) {
      node.setPreviousInWriteOrder(null);
      node.setNextInWriteOrder(null);
    }
    if (bounded.expiresVariable()) {
      node.setPreviousInVariableOrder(null);
      node.setNextInVariableOrder(null);
    }
    node.die();
  }

  private static void destroyTimerWheel(BoundedLocalCache<Object, Object> bounded) {
    for (int i = 0; i < bounded.timerWheel().wheel.length; i++) {
      for (var sentinel : bounded.timerWheel().wheel[i]) {
        sentinel.setPreviousInVariableOrder(sentinel);
        sentinel.setNextInVariableOrder(sentinel);
      }
    }
  }
}
