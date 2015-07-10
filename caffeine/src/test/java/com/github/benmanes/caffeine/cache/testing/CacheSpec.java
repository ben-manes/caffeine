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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * The cache test specification so that a {@link org.testng.annotations.DataProvider} can construct
 * the maximum number of cache combinations to test against.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Target(METHOD) @Retention(RUNTIME)
public @interface CacheSpec {

  /* ---------------- Compute -------------- */

  /**
   * Indicates whether the test supports a cache allowing for asynchronous computations. This is
   * for implementation specific tests that may inspect the internal state of a down casted cache.
   */
  Compute[] compute() default {
    Compute.ASYNC,
    Compute.SYNC
  };

  enum Compute {
    ASYNC,
    SYNC,
  }

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

  /* ---------------- Weigher -------------- */

  /** The weigher, each resulting in a new combination. */
  CacheWeigher[] weigher() default {
    CacheWeigher.DEFAULT,
    CacheWeigher.TEN
  };

  enum CacheWeigher implements Weigher<Object, Object> {
    /** A flag indicating that no weigher is set when building the cache. */
    DEFAULT(1),
    /** A flag indicating that every entry is valued at 10 units. */
    TEN(10),
    /** A flag indicating that every entry is valued at 0 unit. */
    ZERO(0),
    /** A flag indicating that every entry is valued at -1 unit. */
    NEGATIVE(-1),
    /** A flag indicating that every entry is valued at Integer.MAX_VALUE units. */
    MAX_VALUE(Integer.MAX_VALUE),
    /** A flag indicating that the entry is weighted by the integer value. */
    VALUE(1) {
      @Override public int weigh(Object key, Object value) {
        return ((Integer) value).intValue();
      }
    },
    /** A flag indicating that the entry is weighted by the value's collection size. */
    COLLECTION(1) {
      @Override public int weigh(Object key, Object value) {
        return ((Collection<?>) value).size();
      }
    };

    private int units;

    private CacheWeigher(int multiplier) {
      this.units = multiplier;
    }

    @Override
    public int weigh(Object key, Object value) {
      return units;
    }

    public int unitsPerEntry() {
      return units;
    }
  }

  /* ---------------- Expiration -------------- */

  /** Indicates that the combination must have an expiration setting. */
  boolean requiresExpiration() default false;

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

  /** The refresh setting, each resulting in a new combination. */
  Expire[] refreshAfterWrite() default {
    Expire.DISABLED,
    Expire.FOREVER
  };

  /** Indicates if the amount of time that should be auto-advance for each entry when populating. */
  Advance[] advanceOnPopulation() default {
    Advance.ZERO
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

  /** The time increment to advance by after each entry is added when populating the cache. */
  enum Advance {
    ZERO(0),
    ONE_MINUTE(TimeUnit.MINUTES.toNanos(1L));

    private final long timeNanos;

    private Advance(long timeNanos) {
      this.timeNanos = timeNanos;
    }

    public long timeNanos() {
      return timeNanos;
    }
  }

  /* ---------------- Reference-based -------------- */

  /** Indicates that the combination must have a weak reference collection setting. */
  boolean requiresWeakRef() default false;

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

  // FIXME: A hack to allow the NEGATIVE loader's return value to be retained on refresh
  static final ThreadLocal<Interner<Integer>> interner =
      ThreadLocal.withInitial(Interners::newStrongInterner);

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
        return interner.get().intern(-key);
      }
    },
    /** A loader that always throws an exception. */
    EXCEPTIONAL(false) {
      @Override public Integer load(Integer key) {
        throw new IllegalStateException();
      }
    },

    /** A loader that always returns null (no mapping). */
    BULK_NULL(true) {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        return null;
      }
    },
    BULK_IDENTITY(true) {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        Map<Integer, Integer> result = new HashMap<>();
        keys.forEach(key -> result.put(key, key));
        return result;
      }
    },
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
    /** A bulk-only loader that loads more than requested. */
    BULK_NEGATIVE_EXCEEDS(true) {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        List<Integer> moreKeys = new ArrayList<>(ImmutableList.copyOf(keys));
        for (int i = 0; i < 10; i++) {
          moreKeys.add(ThreadLocalRandom.current().nextInt());
        }
        return BULK_NEGATIVE.loadAll(moreKeys);
      }
    },
    /** A bulk-only loader that always throws an exception. */
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

  /* ---------------- CacheWriter -------------- */

  /** Ignored if weak keys are configured. */
  Writer[] writer() default {
    Writer.MOCKITO,
  };

  /** The {@link CacheWriter} for the external resource. */
  enum Writer implements Supplier<CacheWriter<Integer, Integer>> {
    /** A writer that does nothing. */
    DISABLED {
      @Override public CacheWriter<Integer, Integer> get() {
        return CacheWriter.disabledWriter();
      }
    },
    /** A writer that records interactions. */
    MOCKITO {
      @Override public CacheWriter<Integer, Integer> get() {
        @SuppressWarnings("unchecked")
        CacheWriter<Integer, Integer> mock = Mockito.mock(CacheWriter.class);
        return mock;
      }
    },
    /** A writer that always throws an exception. */
    EXCEPTIONAL {
      @Override public CacheWriter<Integer, Integer> get() {
        return new RejectingCacheWriter<Integer, Integer>();
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
      @Override public Executor get() {
        // Use with caution as may be unpredictable during tests if awaiting completion
        return ForkJoinPool.commonPool();
      }
    },
    DIRECT {
      @Override public Executor get() {
        // Cache implementations must avoid deadlocks by incorrectly assuming async execution
        return MoreExecutors.directExecutor();
      }
    },
    SINGLE {
      @Override public Executor get() {
        // Isolated to the test execution - may be shutdown by test to assert completion
        return Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setDaemon(true).build());
      }
    },
    REJECTING {
      @Override public Executor get() {
        // Cache implementations must avoid corrupting internal state due to rejections
        return runnable -> { throw new RejectedExecutionException(); };
      }
    };

    @Override
    public abstract Executor get();
  }

  /* ---------------- Populated -------------- */

  /**
   * The number of entries to populate the cache with. The keys and values are integers starting
   * from above the integer cache limit, with the value being the negated key. The cache will never
   * be populated to exceed the maximum size, if defined, thereby ensuring that no evictions occur
   * prior to the test. Each configuration results in a new combination.
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
