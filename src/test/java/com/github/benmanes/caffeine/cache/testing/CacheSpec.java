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
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
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
    FULL(50),
    /** A configuration where the table grows after the 10 x {@link Population#FULL} count. */
    EXCESSIVE(100);

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
    InitialCapacity.DEFAULT
  };

  /* ---------------- Statistics -------------- */

  enum Stats { ENABLED, DISABLED }

  Stats[] stats() default {
    Stats.ENABLED,
    Stats.DISABLED
  };

  /* ---------------- Maximum size -------------- */

  /** The maximum size, each resulting in a new combination. */
  MaximumSize[] maximumSize() default {
    MaximumSize.DISABLED,
    MaximumSize.UNREACHABLE
  };

  enum MaximumSize {
    /** A flag indicating that entries are not evicted due to a maximum size threshold. */
    DISABLED(Long.MAX_VALUE),
    /** A configuration where entries are evicted immediately. */
    ZERO(0L),
    /** A configuration that holds a single entry. */
    ONE(1L),
    /** A configuration that holds ten entries. */
    TEN(10L),
    /** A configuration that holds the {@link Population#FULL} count. */
    FULL(InitialCapacity.FULL.size()),
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

  /* ---------------- Reference-based -------------- */

  /**
   * Whether to retain a strong reference copy of the initial cache entries within the context. This
   * allows soft/weak combinations to be tested in cases where eviction is not desired. The copy
   * held by the context can be mutated for additional flexibility during testing.
   */
  // FIXME(ben): May not be useful due to Java's integer cache (or needs to exceed cached range?)
  boolean retain() default true;

  /** The reference type of that the cache holds a key with (strong or weak only). */
  ReferenceType[] keys() default { ReferenceType.STRONG, ReferenceType.WEAK };

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

  Loader loader() default Loader.NEGATIVE;

  /** The {@link CacheLoader} for constructing the {@link LoadingCache}. */
  enum Loader implements CacheLoader<Integer, Integer> {
    /** A loader that always returns null (no mapping). */
    NULL {
      @Override public Integer load(Integer key) {
        return null;
      }
    },
    /** A loader that returns the key. */
    IDENTITY {
      @Override public Integer load(Integer key) {
        return key;
      }
    },
    /** A loader that returns the key's negation. */
    NEGATIVE {
      @Override public Integer load(Integer key) {
        return -key;
      }
    },
    /** A loader that always throws an exception. */
    EXCEPTIONAL {
      @Override public Integer load(Integer key) {
        throw new IllegalStateException();
      }
    };
  }

  /* ---------------- Executor -------------- */

  /** The executors retrieved from a supplier, each resulting in a new combination. */
  CacheExecutor[] executor() default {
    CacheExecutor.DIRECT,
  };

  /** The executors that the cache can be configured with. */
  enum CacheExecutor implements Supplier<Executor> {
    DEFAULT { // fork-join common pool
      @Override public Executor get() { return null; }
    },
    DIRECT {
      @Override public Executor get() { return MoreExecutors.directExecutor(); }
    },
    REJECTING {
      @Override public Executor get() { throw new RejectedExecutionException(); }
    };

    @Override
    public abstract Executor get();
  }

  /* ---------------- Populated -------------- */

  /**
   * The number of entries to populate the cache with. The keys and values are integers starting
   * from 0, with the value being the negated key. The cache will never be populated to exceed the
   * maximum size, if defined, thereby ensuring that no evictions occur prior to the test. Each
   * configuration results in a new combination.
   */
  Population[] population() default {
    Population.EMPTY,
    Population.SINGLETON,
    Population.PARTIAL,
    Population.FULL
  };

  /** The population scenarios. */
  enum Population {
    EMPTY(0),
    SINGLETON(1),
    PARTIAL(InitialCapacity.FULL.size() / 2),
    FULL(InitialCapacity.FULL.size());

    private final long size;

    private Population(long size) {
      this.size = size;
    }

    public long size() {
      return size;
    }

    public void populate(Cache<Integer, Integer> cache, CacheContext context) {
      int maximum = (int) Math.min(context.maximumSize(), size());
      context.firstKey = (int) Math.min(1, size());
      context.lastKey = maximum;
      context.middleKey = Math.max(context.firstKey, ((context.lastKey - context.firstKey) / 2));
      for (int i = 1; i <= maximum; i++) {
        context.original.put(i, -i);
        cache.put(i, -i);
      }
    }
  }
}
