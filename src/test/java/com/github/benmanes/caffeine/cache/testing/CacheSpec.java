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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The cache test specification so that a {@link org.testng.annotations.DataProvider} can construct
 * the maximum number of cache combinations to test against.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Target(METHOD) @Retention(RUNTIME)
public @interface CacheSpec {

  /* ---------------- Initial capacity -------------- */

  enum InitialCapacity {
    /** A flag indicating that the initial capacity is not configured. */
    DEFAULT(16),
    /** A configuration where the table grows on the first addition. */
    ZERO(0),
    /** A configuration where the table grows on the second addition. */
    ONE(1),
    /** A configuration where the table grows after the {@link Population#FULL} count. */
    FULL(100),
    /** A configuration where the table grows after the 10 x {@link Population#FULL} count. */
    EXCESSIVE(10 * 100);

    private final int size;

    private InitialCapacity(int size) {
      this.size = size;
    }

    public int size() {
      return size;
    }
  }

  /** The initial capacities, each resulting in a new combination. */
  InitialCapacity[] initialCapacity() default {
    InitialCapacity.DEFAULT,
    InitialCapacity.ZERO,
    InitialCapacity.ONE,
    InitialCapacity.FULL,
    InitialCapacity.EXCESSIVE
  };

  /* ---------------- Maximum size -------------- */

  enum MaximumSize {
    /** A flag indicating that entries are not evicted due to a maximum size threshold. */
    DISABLED(Long.MAX_VALUE),
    /** A configuration where entries are evicted immediately. */
    ZERO(0L),
    /** A configuration that holds a single entry. */
    ONE(1L),
    /** A configuration that holds the {@link Population#FULL} count. */
    FULL(100L),
    /** A configuration where the threshold is too high for eviction to occur. */
    UNREACHABLE(Long.MAX_VALUE);

    private final long max;

    private MaximumSize(long max) {
      this.max = max;
    }

    public long max() {
      return max;
    }
  }

  /** The maximum size, each resulting in a new combination. */
  MaximumSize[] maximumSize() default { MaximumSize.DISABLED, MaximumSize.UNREACHABLE };

  /* ---------------- Reference-based -------------- */

  /**
   * Whether to retain a strong reference copy of the initial cache entries within the context. This
   * allows soft/weak combinations to be tested in cases where eviction is not desired. The copy
   * held by the context can be mutated for additional flexibility during testing.
   */
  boolean retain() default true;

  /** The reference type of that the cache holds a key with (strong or soft only). */
  ReferenceType[] keys() default { ReferenceType.STRONG, ReferenceType.SOFT };

  /** The reference type of that the cache holds a value with (strong, soft, or weak). */
  ReferenceType[] values() default { ReferenceType.STRONG, ReferenceType.WEAK, ReferenceType.SOFT };

  /** The reference type of cache keys and/or values. */
  enum ReferenceType {
    /** Prevents referent from being reclaimed by the garbage collector. */
    STRONG,

    /**
     * Referent reclaimed in an LRU fashion when the VM runs low on memory and no strong
     * references exist.
     */
    SOFT,

    /** Referent reclaimed when no strong or soft references exist. */
    WEAK
  }

  /* ---------------- Removal -------------- */

  /** The removal listeners, each resulting in a new combination. */
  Listener[] removalListener() default {
    Listener.DEFAULT,
    Listener.CONSUMING,
  };

  /** The removal listeners, each resulting in a new combination. */
  enum Listener {
    /** A flag indicating that no removal listener is configured. */
    DEFAULT {
      @Override public <K, V> RemovalListener<K, V> create() {
        return null;
      }
    },
    /** A removal listener that rejects all notifications. */
    REJECTING {
      @Override public <K, V> RemovalListener<K, V> create() {
        return RemovalListeners.rejecting();
      }
    },
    /** A {@link ConsumingRemovalListener} retains all notifications for evaluation by the test. */
    CONSUMING {
      @Override public <K, V> RemovalListener<K, V> create() {
        return RemovalListeners.consuming();
      }
    };

    public abstract <K, V> RemovalListener<K, V> create();
  }

  /* ---------------- Executor -------------- */

  /** The executors retrieved from a supplier, each resulting in a new combination. */
  CacheExecutor[] executor() default {
    CacheExecutor.DEFAULT, CacheExecutor.DIRECT, CacheExecutor.FORK_JOIN_COMMON_POOL
  };

  /** The executors that the cache can be configured with. */
  enum CacheExecutor implements Supplier<Executor> {
    DEFAULT {
      @Override public Executor get() { return null; }
    },
    DIRECT {
      @Override public Executor get() { return MoreExecutors.directExecutor(); }
    },
    FORK_JOIN_COMMON_POOL {
      @Override public Executor get() { return ForkJoinPool.commonPool(); }
    },
    REJECTING {
      @Override public Executor get() { throw new RejectedExecutionException(); }
    };

    @Override
    public abstract Executor get();
  }

  /* ---------------- Populated -------------- */

  /** The default maximum number of size if eviction is enabled. */
  static final long FULL_SIZE = InitialCapacity.FULL.size();

  /**
   * The number of entries to populate the cache with. The keys and values are integers starting
   * from 0, with the value being the negated key. Each configuration results in a new combination.
   */
  Population[] population() default {
    Population.EMPTY, Population.SINGLETON, Population.PARTIAL, Population.FULL
  };

  /** The population scenarios. */
  enum Population {
    EMPTY() {
      @Override public void populate(CacheContext context, Cache<Integer, Integer> cache) {}
    },
    SINGLETON() {
      @Override public void populate(CacheContext context, Cache<Integer, Integer> cache) {
        context.firstKey = 0;
        context.lastKey = 0;
        context.midKey = 0;
        cache.put(0, 0);
      }
    },
    PARTIAL() {
      @Override public void populate(CacheContext context, Cache<Integer, Integer> cache) {
        int maximum = context.isUnbounded()
            ? (int) (CacheSpec.FULL_SIZE / 2)
            : (int) context.getMaximumSize();
        context.firstKey = 0;
        context.lastKey = maximum - 1;
        context.midKey = (context.lastKey - context.firstKey) / 2;
        for (int i = 0; i < maximum; i++) {
          cache.put(i, -i);
        }
      }
    },
    FULL() {
      @Override public void populate(CacheContext context, Cache<Integer, Integer> cache) {
        int maximum = context.isUnbounded()
            ? (int) CacheSpec.FULL_SIZE
            : (int) context.getMaximumSize();
        context.firstKey = 0;
        context.lastKey = maximum - 1;
        context.midKey = (context.lastKey - context.firstKey) / 2;
        for (int i = 0; i < maximum; i++) {
          cache.put(i, -i);
        }
      }
    };

    abstract void populate(CacheContext context, Cache<Integer, Integer> cache);
  }
}
