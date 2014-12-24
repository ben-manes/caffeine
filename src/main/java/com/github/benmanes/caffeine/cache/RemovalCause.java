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

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * The reason why a cached entry was removed.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum RemovalCause {

  /**
   * The entry was manually removed by the user. This can result from the user invoking
   * {@link Cache#invalidate}, {@link Cache#invalidateAll(Iterable)}, {@link Cache#invalidateAll()},
   * {@link Map#remove}, {@link Map#computeIfPresent}, {@link Map#compute}, {@link Map#merge}, or
   * {@link ConcurrentMap#remove} on the cache or map view. A manual removal my also be performed
   * through the key/value/entry collections views by the user invoking {@link Collection#remove},
   * {@link Collection#removeAll}, {@link Collection#removeIf}, {@link Collection#retainAll}, or
   * {@link Iterator#remove}.
   */
  EXPLICIT {
    @Override boolean wasEvicted() {
      return false;
    }
  },

  /**
   * The entry itself was not actually removed, but its value was replaced by the user. This can
   * result from the user invoking {@link Cache#put}, {@link Cache#putAll},
   * {@link LoadingCache#refresh}, {@link Map#put}, {@link Map#putAll}, {@link Map#replace},
   * {@link Map#computeIfPresent}, {@link Map#compute}, or {@link Map#merge}.
   */
  REPLACED {
    @Override boolean wasEvicted() {
      return false;
    }
  },

  /**
   * The entry was removed automatically because its key or value was garbage-collected. This
   * can occur when using {@link Caffeine#weakKeys}, {@link Caffeine#weakValues}, or
   * {@link Caffeine#softValues}.
   */
  COLLECTED {
    @Override boolean wasEvicted() {
      return true;
    }
  },

  /**
   * The entry's expiration timestamp has passed. This can occur when using
   * {@link Caffeine#expireAfterWrite} or {@link Caffeine#expireAfterAccess}.
   */
  EXPIRED {
    @Override boolean wasEvicted() {
      return true;
    }
  },

  /**
   * The entry was evicted due to size constraints. This can occur when using
   * {@link Caffeine#maximumSize} or {@link Caffeine#maximumWeight}.
   */
  SIZE {
    @Override boolean wasEvicted() {
      return true;
    }
  };

  /**
   * Returns {@code true} if there was an automatic removal due to eviction (the cause is neither
   * {@link #EXPLICIT} nor {@link #REPLACED}).
   */
  abstract boolean wasEvicted();
}
