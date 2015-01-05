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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Weigher;
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

  /* ---------------- Implementation -------------- */

  /** The implementation, each resulting in a new combination. */
  Implementation[] implementation() default {
    Implementation.Caffeine,
    Implementation.Guava,
  };

  enum Implementation {
    Caffeine,
    Guava
  }

  /* ---------------- Initial capacity -------------- */

  InitialCapacity[] initialCapacity() default {
    InitialCapacity.DEFAULT
  };

  /** The initial capacities, each resulting in a new combination. */
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

  /* ---------------- Statistics -------------- */

  Stats[] stats() default {
    Stats.ENABLED,
    Stats.DISABLED
  };

  enum Stats {
    ENABLED,
    DISABLED
  }

  /* ---------------- Maximum size -------------- */

  /** The weigher, each resulting in a new combination. */
  CacheWeigher[] weigher() default {
    CacheWeigher.DEFAULT,
    CacheWeigher.TEN
  };

  enum CacheWeigher implements Weigher<Object, Object> {
    /** A flag indicating that no weigher is set when building the cache. */
    DEFAULT(1),
    /** A flag indicating that every entry is valued at 10 units. */
    TEN(10);

    private int multiplier;

    private CacheWeigher(int multiplier) {
      this.multiplier = multiplier;
    }

    @Override
    public int weigh(Object key, Object value) {
      return multiplier;
    }

    public int multipler() {
      return multiplier;
    }
  }

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

  /* ---------------- Expiration -------------- */

  /** The expiration time-to-idle setting, each resulting in a new combination. */
  Expire[] expireAfterAccess() default {
    Expire.DISABLED,
    Expire.FOREVER
  };

  /** The expiration time-to-live setting, each resulting in a new combination. */
  Expire[] expireAfterWrite() default {
    Expire.DISABLED,
    Expire.FOREVER
  };

  enum Expire {
    /** A flag indicating that entries are not evicted due to expiration. */
    DISABLED(Long.MIN_VALUE),
    /** A configuration where entries are evicted immediately. */
    IMMEDIATELY(0L),
    /** A configuration that holds a single entry. */
    ONE_MINUTE(TimeUnit.MINUTES.toNanos(1L)),
    /** A configuration that holds the {@link Population#FULL} count. */
    FOREVER(Long.MAX_VALUE);

    private final long timeNanos;

    private Expire(long timeNanos) {
      this.timeNanos = timeNanos;
    }

    public long timeNanos() {
      return timeNanos;
    }
  }

  /* ---------------- Reference-based -------------- */

  // TODO(ben): Weak & Soft reference tests disabled due to causing GC thrashing / OOME.
  // The build needs to fork those tests into their own JVMs to ensure memory limits

  /** The reference type of that the cache holds a key with (strong or weak only). */
  ReferenceType[] keys() default {
    ReferenceType.STRONG,
    ReferenceType.WEAK
  };

  /** The reference type of that the cache holds a value with (strong, soft, or weak). */
  ReferenceType[] values() default {
    ReferenceType.STRONG,
    ReferenceType.WEAK,
    ReferenceType.SOFT
  };

  /** The reference type of cache keys and/or values. */
  enum ReferenceType {
    /** Prevents referent from being reclaimed by the garbage collector. */
    STRONG,

    /** Referent reclaimed when no strong or soft references exist. */
    WEAK,

    /**
     * Referent reclaimed in an LRU fashion when the VM runs low on memory and no strong
     * references exist.
     */
    SOFT
  }

  /* ---------------- Removal -------------- */

  /** The removal listeners, each resulting in a new combination. */
  Listener[] removalListener() default {
    Listener.CONSUMING,
    Listener.DEFAULT,
  };

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

  /* ---------------- CacheLoader -------------- */

  Loader[] loader() default {
    Loader.NEGATIVE,
  };

  /** The {@link CacheLoader} for constructing the {@link LoadingCache}. */
  enum Loader implements CacheLoader<Integer, Integer> {
    /** A loader that always returns null (no mapping). */
    NULL(false) {
      @Override public Integer load(Integer key) {
        return null;
      }
    },
    /** A loader that returns the key. */
    IDENTITY(false) {
      @Override public Integer load(Integer key) {
        return key;
      }
    },
    /** A loader that returns the key's negation. */
    NEGATIVE(false) {
      @Override public Integer load(Integer key) {
        return -key;
      }
    },
    /** A loader that always throws an exception. */
    EXCEPTIONAL(false) {
      @Override public Integer load(Integer key) {
        throw new IllegalStateException();
      }
    },

    // Bulk versions
    BULK_NEGATIVE(true) {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        Map<Integer, Integer> result = new HashMap<>();
        keys.forEach(key -> result.put(key, -key));
        return result;
      }
    },
    BULK_EXCEPTIONAL(true) {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        throw new IllegalStateException();
      }
    };

    private final boolean bulk;

    private Loader(boolean bulk) {
      this.bulk = bulk;
    }

    public boolean isBulk() {
      return bulk;
    }
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
  }
}
