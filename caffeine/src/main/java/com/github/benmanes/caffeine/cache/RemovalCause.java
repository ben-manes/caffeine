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

/**
 * The reason why a cached entry was removed.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum RemovalCause {

  /**
   * The entry was manually removed by the user. This can result from the user invoking any of the
   * following methods on the cache or map view.
   * <ul>
   *   <li>{@link Cache#invalidate}</li>
   *   <li>{@link Cache#invalidateAll(Iterable)}</li>
   *   <li>{@link Cache#invalidateAll()}</li>
   *   <li>{@link LoadingCache#refresh}</li>
   *   <li>{@link java.util.Map#remove}</li>
   *   <li>{@link java.util.Map#computeIfPresent}</li>
   *   <li>{@link java.util.Map#compute}</li>
   *   <li>{@link java.util.Map#merge}</li>
   *   <li>{@link java.util.concurrent.ConcurrentMap#remove}</li>
   * </ul>
   * A manual removal may also be performed through the key, value, or entry collections views by
   * the user invoking any of the following methods.
   * <ul>
   *   <li>{@link java.util.Collection#remove}</li>
   *   <li>{@link java.util.Collection#removeAll}</li>
   *   <li>{@link java.util.Collection#removeIf}</li>
   *   <li>{@link java.util.Collection#retainAll}</li>
   *   <li>{@link java.util.Iterator#remove}</li>
   * </ul>
   */
  EXPLICIT {
    @Override public boolean wasEvicted() {
      return false;
    }
  },

  /**
   * The entry itself was not actually removed, but its value was replaced by the user. This can
   * result from the user invoking any of the following methods on the cache or map view.
   * <ul>
   *   <li>{@link Cache#put}</li>
   *   <li>{@link Cache#putAll}</li>
   *   <li>{@link LoadingCache#getAll}</li>
   *   <li>{@link LoadingCache#refresh}</li>
   *   <li>{@link java.util.Map#put}</li>
   *   <li>{@link java.util.Map#putAll}</li>
   *   <li>{@link java.util.Map#replace}</li>
   *   <li>{@link java.util.Map#computeIfPresent}</li>
   *   <li>{@link java.util.Map#compute}</li>
   *   <li>{@link java.util.Map#merge}</li>
   * </ul>
   */
  REPLACED {
    @Override public boolean wasEvicted() {
      return false;
    }
  },

  /**
   * The entry was removed automatically because its key or value was garbage-collected. This
   * can occur when using {@link Caffeine#weakKeys}, {@link Caffeine#weakValues}, or
   * {@link Caffeine#softValues}.
   */
  COLLECTED {
    @Override public boolean wasEvicted() {
      return true;
    }
  },

  /**
   * The entry's expiration timestamp has passed. This can occur when using
   * {@link Caffeine#expireAfterWrite}, {@link Caffeine#expireAfterAccess},
   * or {@link Caffeine#expireAfter(Expiry)}.
   */
  EXPIRED {
    @Override public boolean wasEvicted() {
      return true;
    }
  },

  /**
   * The entry was evicted due to size constraints. This can occur when using
   * {@link Caffeine#maximumSize} or {@link Caffeine#maximumWeight}.
   */
  SIZE {
    @Override public boolean wasEvicted() {
      return true;
    }
  };

  /**
   * Returns {@code true} if there was an automatic removal due to eviction (the cause is neither
   * {@link #EXPLICIT} nor {@link #REPLACED}).
   *
   * @return if the entry was automatically removed due to eviction
   */
  public abstract boolean wasEvicted();
}
