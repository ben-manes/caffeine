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

import org.jctools.util.UnsafeAccess;

import com.github.benmanes.caffeine.cache.LocalAsyncCache.AbstractCacheView;

/**
 * A hook to reset internal details of the cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Reset {
  static final long PROBE = UnsafeAccess.fieldOffset(Thread.class, "threadLocalRandomProbe");
  static final long SEED = UnsafeAccess.fieldOffset(Thread.class, "threadLocalRandomSeed");
  static final int RANDOM_PROBE = 0x9e3779b9;
  static final int RANDOM_SEED = 1033096058;

  private Reset() {}

  /** Forces the eviction jitter to be predictable. */
  public static void resetThreadLocalRandom() {
    setThreadLocalRandom(RANDOM_PROBE, RANDOM_SEED);
  }

  public static void setThreadLocalRandom(int probe, int seed) {
    UnsafeAccess.UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
    UnsafeAccess.UNSAFE.putLong(Thread.currentThread(), SEED, seed);
  }

  /** Clears the internal state of the cache and becomes unusable. */
  public static void destroy(Cache<?, ?> cache) {
    LocalCache<?, ?> local = (cache instanceof AbstractCacheView<?, ?>)
        ? ((AbstractCacheView<?, ?>) cache).asyncCache().cache()
        : (LocalCache<?, ?>) cache.asMap();
    if (local instanceof UnboundedLocalCache) {
      UnboundedLocalCache<?, ?> unbounded = (UnboundedLocalCache<?, ?>) local;
      unbounded.data.clear();
    } else if (local instanceof BoundedLocalCache) {
      @SuppressWarnings("unchecked")
      BoundedLocalCache<Object, Object> bounded = (BoundedLocalCache<Object, Object>) local;
      bounded.evictionLock.lock();
      try {
        for (Node<?, ?> node : bounded.data.values()) {
          destroyNode(bounded, node);
        }
        if (bounded.expiresVariable()) {
          destroyTimerWheel(bounded);
        }
        bounded.data.clear();
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
      for (Node<Object, Object> sentinel : bounded.timerWheel().wheel[i]) {
        sentinel.setPreviousInVariableOrder(sentinel);
        sentinel.setNextInVariableOrder(sentinel);
      }
    }
  }
}
