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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

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

  /** A flag indicating that the initial capacity is not configured. */
  static final int DEFAULT_INITIAL_CAPACITY = -1;

  /** The initial capacities, each resulting in a new combination. */
  int[] initialCapacity() default { DEFAULT_INITIAL_CAPACITY };

  /* ---------------- General Eviction -------------- */

  /** The removal listeners, each resulting in a new combination. */
  Class<RemovalListener<?, ?>>[] removalListener() default {};

  /* ---------------- Maximum size -------------- */

  /** A flag indicating that entries are not evicted due to a maximum size threshold. */
  static final long UNBOUNDED = -1L;

  /** The default maximum number of size if eviction is enabled. */
  static final long DEFAULT_MAXIMUM_SIZE = 100L;

  /** The maximum size, each resulting in a new combination. */
  long[] maximumSize() default { UNBOUNDED };

  /* ---------------- Reference-based -------------- */

  /** The reference type of that the cache holds a key with (strong or soft only). */
  ReferenceType[] keys() default { ReferenceType.STRONG };

  /** The reference type of that the cache holds a value with (strong, soft, or weak). */
  ReferenceType[] values() default { ReferenceType.STRONG };

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

  /* ---------------- Executor -------------- */

  /** The executors retrieved from a supplier, each resulting in a new combination. */
  CacheExecutor[] executor() default { CacheExecutor.DEFAULT };

  /** The executors that the cache can be configured with. */
  enum CacheExecutor implements Supplier<Executor> {
    DEFAULT() {
      @Override public Executor get() { throw new AssertionError("Should never be called"); }
    },
    DIRECT() {
      @Override public Executor get() { return MoreExecutors.directExecutor(); }
    },
    FORK_JOIN_COMMON_POOL() {
      @Override public Executor get() { return ForkJoinPool.commonPool(); }
    };

    @Override
    public abstract Executor get();
  }

  /* ---------------- Populated -------------- */

  /** The default maximum number of size if eviction is enabled. */
  static final long DEFAULT_FULL = DEFAULT_MAXIMUM_SIZE;

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
            ? (int) context.getMaximumSize()
            : (int) (CacheSpec.DEFAULT_MAXIMUM_SIZE / 2);
        context.firstKey = 0;
        context.lastKey = maximum;
        context.midKey = maximum / 2;
        for (int i = 0; i < maximum; i++) {
          cache.put(i, -i);
        }
      }
    },
    FULL() {
      @Override public void populate(CacheContext context, Cache<Integer, Integer> cache) {
        int maximum = context.isUnbounded()
            ? (int) context.getMaximumSize()
            : (int) CacheSpec.DEFAULT_MAXIMUM_SIZE;
        context.firstKey = 0;
        context.lastKey = maximum;
        context.midKey = maximum / 2;
        for (int i = 0; i < maximum; i++) {
          cache.put(i, -i);
        }
      }
    };

    abstract void populate(CacheContext context, Cache<Integer, Integer> cache);
  }
}
