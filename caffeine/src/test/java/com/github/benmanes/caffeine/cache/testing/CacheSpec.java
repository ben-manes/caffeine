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

import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.scheduledExecutor;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.testing.TestingExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * The cache test specification so that a {@link org.testng.annotations.DataProvider} can construct
 * the maximum number of cache combinations to test against.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ImmutableEnumChecker")
@Target(METHOD) @Retention(RUNTIME)
public @interface CacheSpec {

  /* --------------- Compute --------------- */

  /** Indicates whether the test supports a cache allowing for asynchronous computations. */
  Compute[] compute() default {
    Compute.ASYNC,
    Compute.SYNC
  };

  enum Compute {
    ASYNC,
    SYNC,
  }

  /* --------------- Implementation --------------- */

  /** The implementation, each resulting in a new combination. */
  Implementation[] implementation() default {
    Implementation.Caffeine,
    Implementation.Guava,
  };

  enum Implementation {
    Caffeine,
    Guava
  }

  /* --------------- Initial capacity --------------- */

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

    InitialCapacity(int size) {
      this.size = size;
    }

    public int size() {
      return size;
    }
  }

  /* --------------- Statistics --------------- */

  Stats[] stats() default {
    Stats.ENABLED,
    Stats.DISABLED
  };

  enum Stats {
    ENABLED,
    DISABLED
  }

  /* --------------- Maximum size --------------- */

  /** The maximum size, each resulting in a new combination. */
  Maximum[] maximumSize() default {
    Maximum.DISABLED,
    Maximum.UNREACHABLE
  };

  enum Maximum {
    /** A flag indicating that entries are not evicted due to a maximum threshold. */
    DISABLED(Long.MAX_VALUE),
    /** A configuration where entries are evicted immediately. */
    ZERO(0),
    /** A configuration that holds a single unit. */
    ONE(1),
    /** A configuration that holds 10 units. */
    TEN(10),
    /** A configuration that holds 150 units. */
    ONE_FIFTY(150),
    /** A configuration that holds the {@link Population#FULL} unit count. */
    FULL(InitialCapacity.FULL.size()),
    /** A configuration where the threshold is too high for eviction to occur. */
    UNREACHABLE(Long.MAX_VALUE);

    private final long max;

    Maximum(long max) {
      this.max = max;
    }

    public long max() {
      return max;
    }
  }

  /* --------------- Weigher --------------- */

  /** The weigher, each resulting in a new combination. */
  CacheWeigher[] weigher() default {
    CacheWeigher.DISABLED,
    CacheWeigher.ZERO,
    CacheWeigher.TEN
  };

  enum CacheWeigher {
    /** A flag indicating that no weigher is set when building the cache. */
    DISABLED(1),
    /** A flag indicating that every entry is valued at 10 units. */
    TEN(10),
    /** A flag indicating that every entry is valued at 0 unit. */
    ZERO(0),
    /** A flag indicating that every entry is valued at -1 unit. */
    NEGATIVE(-1),
    /** A flag indicating that every entry is valued at Integer.MAX_VALUE units. */
    MAX_VALUE(Integer.MAX_VALUE),
    /** A flag indicating that the entry is weighted by the absolute integer value. */
    VALUE(() -> (key, value) -> Math.abs(((Int) value).intValue()), 1),
    /** A flag indicating that the entry is weighted by the value's collection size. */
    COLLECTION(() -> (key, value) -> ((Collection<?>) value).size(), 1),
    /** A flag indicating that the entry's weight is randomly changing. */
    RANDOM(Weighers::random, 1),
    /** A flag indicating that the entry's weight records interactions. */
    @SuppressWarnings("unchecked")
    MOCKITO(() -> {
      var weigher = Mockito.mock(Weigher.class);
      when(weigher.weigh(any(), any())).thenReturn(1);
      return weigher;
    }, 1);

    private final Supplier<Weigher<Object, Object>> factory;
    private final int units;

    CacheWeigher(int units) {
      this.factory = () -> Weighers.constant(units);
      this.units = units;
    }
    CacheWeigher(Supplier<Weigher<Object, Object>> factory, int units) {
      this.factory = factory;
      this.units = units;
    }
    public int unitsPerEntry() {
      return units;
    }
    @SuppressWarnings("unchecked")
    public <K, V> Weigher<K, V> create() {
      return (Weigher<K, V>) factory.get();
    }
  }

  /* --------------- Expiration --------------- */

  /** Indicates that the combination must have any of the expiration settings. */
  Expiration[] mustExpireWithAnyOf() default {};

  enum Expiration {
    AFTER_WRITE, AFTER_ACCESS, VARIABLE
  }

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

  /** The variable expiration setting, each resulting in a new combination. */
  CacheExpiry[] expiry() default {
    CacheExpiry.DISABLED,
    CacheExpiry.ACCESS
  };

  /** The fixed duration for the expiry. */
  Expire expiryTime() default Expire.FOREVER;

  enum CacheExpiry {
    DISABLED {
      @Override public <K, V> Expiry<K, V> createExpiry(Expire expiryTime) {
        return null;
      }
    },
    MOCKITO {
      @Override public <K, V> Expiry<K, V> createExpiry(Expire expiryTime) {
        Expiry<K, V> mock = Mockito.mock();
        when(mock.expireAfterCreate(any(), any(), anyLong()))
            .thenReturn(expiryTime.timeNanos());
        when(mock.expireAfterUpdate(any(), any(), anyLong(), anyLong()))
            .thenReturn(expiryTime.timeNanos());
        when(mock.expireAfterRead(any(), any(), anyLong(), anyLong()))
            .thenReturn(expiryTime.timeNanos());
        return mock;
      }
    },
    CREATE {
      @Override public <K, V> Expiry<K, V> createExpiry(Expire expiryTime) {
        return ExpiryBuilder
            .expiringAfterCreate(expiryTime.duration())
            .build();
      }
    },
    WRITE {
      @Override public <K, V> Expiry<K, V> createExpiry(Expire expiryTime) {
        return ExpiryBuilder
            .expiringAfterCreate(expiryTime.duration())
            .expiringAfterUpdate(expiryTime.duration())
            .build();
      }
    },
    ACCESS {
      @Override public <K, V> Expiry<K, V> createExpiry(Expire expiryTime) {
        return ExpiryBuilder
            .expiringAfterCreate(expiryTime.duration())
            .expiringAfterUpdate(expiryTime.duration())
            .expiringAfterRead(expiryTime.duration())
            .build();
      }
    };

    public abstract <K, V> Expiry<K, V> createExpiry(Expire expiryTime);
  }

  enum Expire {
    /** A flag indicating that entries are not evicted due to expiration. */
    DISABLED(Long.MIN_VALUE),
    /** A configuration where entries are evicted immediately. */
    IMMEDIATELY(0),
    /** A configuration where entries are evicted almost immediately. */
    ONE_MILLISECOND(TimeUnit.MILLISECONDS.toNanos(1)),
    /** A configuration where entries are after a time duration. */
    ONE_MINUTE(TimeUnit.MINUTES.toNanos(1)),
    /** A configuration where entries should never expire. */
    /** A configuration that holds the {@link Population#FULL} count. */
    FOREVER(Long.MAX_VALUE);

    private final long timeNanos;
    private final Duration duration;

    Expire(long timeNanos) {
      this.timeNanos = timeNanos;
      this.duration = Duration.ofNanos(timeNanos);
    }
    public Duration duration() {
      return duration;
    }
    public long timeNanos() {
      return timeNanos;
    }
  }

  /* --------------- Reference-based --------------- */

  /** Indicates that the combination must have a weak or soft reference collection setting. */
  boolean requiresWeakOrSoft() default false;

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

    /** The referent is reclaimed when there are no strong or soft references. */
    WEAK,

    /**
     * The referent is reclaimed in an LRU fashion when the JVM runs low on memory and there are no
     * strong references.
     */
    SOFT
  }

  /* --------------- Removal --------------- */

  /** The removal listeners, each resulting in a new combination. */
  Listener[] removalListener() default {
    Listener.CONSUMING,
    Listener.DISABLED,
  };

  /** The eviction listeners, each resulting in a new combination. */
  Listener[] evictionListener() default {
    Listener.CONSUMING,
    Listener.DISABLED,
  };

  @SuppressWarnings("unchecked")
  enum Listener {
    /** A flag indicating that no removal listener is configured. */
    DISABLED(() -> null),
    /** A removal listener that rejects all notifications. */
    REJECTING(RemovalListeners::rejecting),
    /** A {@link ConsumingRemovalListener} retains all notifications for evaluation by the test. */
    CONSUMING(RemovalListeners::consuming),
    /** A removal listener that records interactions. */
    MOCKITO(() -> Mockito.mock(RemovalListener.class));

    private final Supplier<RemovalListener<Object, Object>> factory;

    Listener(Supplier<RemovalListener<Object, Object>> factory) {
      this.factory = factory;
    }
    public <K, V> RemovalListener<K, V> create() {
      return (RemovalListener<K, V>) factory.get();
    }
  }

  /* --------------- CacheLoader --------------- */

  Loader[] loader() default {
    Loader.DISABLED,
    Loader.NEGATIVE,
  };

  /** The {@link CacheLoader} for constructing the {@link LoadingCache}. */
  enum Loader implements CacheLoader<Int, Int> {
    /** A flag indicating that a loader should not be configured. */
    DISABLED {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
    },
    /** A loader that always returns null (no mapping). */
    NULL {
      @Override public Int load(Int key) {
        return null;
      }
    },
    /** A loader that returns the key. */
    IDENTITY {
      @Override public Int load(Int key) {
        requireNonNull(key);
        return intern(key);
      }
    },
    /** A loader that returns the key's negation. */
    NEGATIVE {
      @Override public Int load(Int key) {
        // Intern the loader's return value so that it is retained on a refresh
        return intern(key, k -> new Int(-k.intValue()));
      }
    },
    /** A loader that always throws a runtime exception. */
    EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
    },
    /** A loader that always throws a checked exception. */
    CHECKED_EXCEPTIONAL {
      @Override public Int load(Int key) throws ExecutionException {
        throw new ExecutionException(null);
      }
    },
    /** A loader that always throws an interrupted exception. */
    INTERRUPTED {
      @Override public Int load(Int key) throws InterruptedException {
        throw new InterruptedException();
      }
    },

    /** A loader that always returns null (no mapping). */
    BULK_NULL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @SuppressWarnings("ReturnsNullCollection")
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) {
        return null;
      }
    },
    BULK_IDENTITY {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) {
        var result = new HashMap<Int, Int>(keys.size());
        for (Int key : keys) {
          result.put(key, key);
          intern(key);
        }
        return result;
      }
    },
    BULK_NEGATIVE {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) throws Exception {
        var result = new HashMap<Int, Int>(keys.size());
        for (Int key : keys) {
          result.put(key, NEGATIVE.load(key));
          intern(key);
        }
        return result;
      }
    },
    /** A bulk-only loader that loads only keys that were not requested. */
    BULK_DIFFERENT {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) {
        return keys.stream().collect(toUnmodifiableMap(
            key -> intern(intern(key).negate()), identity()));
      }
    },
    /** A bulk-only loader that loads more than requested. */
    BULK_NEGATIVE_EXCEEDS {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<? extends Int, ? extends Int> loadAll(
          Set<? extends Int> keys) throws Exception {
        var moreKeys = new LinkedHashSet<Int>(keys.size() + 10);
        moreKeys.addAll(keys);
        for (int i = 0; i < 10; i++) {
          moreKeys.add(Int.valueOf(ThreadLocalRandom.current().nextInt()));
        }
        return BULK_NEGATIVE.loadAll(moreKeys);
      }
    },
    /** A bulk-only loader that always throws a runtime exception. */
    BULK_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) {
        throw new IllegalStateException();
      }
    },
    /** A bulk-only loader that always throws a checked exception. */
    BULK_CHECKED_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) throws ExecutionException {
        throw new ExecutionException(null);
      }
    },
    /** A bulk-only loader that always throws an interrupted exception. */
    BULK_INTERRUPTED {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) throws InterruptedException {
        throw new InterruptedException();
      }
    },
    /** A bulk loader that tries to modify the keys. */
    BULK_MODIFY_KEYS {
      @CanIgnoreReturnValue
      @Override public Int load(Int key) {
        return key;
      }
      @Override public Map<Int, Int> loadAll(Set<? extends Int> keys) {
        keys.clear();
        return Map.of();
      }
    },

    /** A loader that always throws a runtime exception. */
    ASYNC_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        throw new IllegalStateException();
      }
    },
    /** A loader that always throws a checked exception. */
    ASYNC_CHECKED_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Int> asyncLoad(
          Int key, Executor executor) throws ExecutionException {
        throw new ExecutionException(null);
      }
    },
    /** An async loader that returns a incomplete future. */
    ASYNC_INCOMPLETE {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        executor.execute(() -> {});
        return new CompletableFuture<>();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        executor.execute(() -> {});
        return new CompletableFuture<>();
      }
    },
    /** A loader that always throws an interrupted exception. */
    ASYNC_INTERRUPTED {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Int> asyncLoad(
          Int key, Executor executor) throws InterruptedException {
        throw new InterruptedException();
      }
    },
    /** A loader that always throws a runtime exception. */
    ASYNC_BULK_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Map<Int, Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) {
        throw new IllegalStateException();
      }
    },
    /** A loader that always throws a checked exception. */
    ASYNC_BULK_CHECKED_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Map<Int, Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) throws ExecutionException {
        throw new ExecutionException(null);
      }
    },
    /** An async bulk loader that tries to modify the keys. */
    ASYNC_BULK_MODIFY_KEYS {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Map<Int, Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) {
        keys.clear();
        return CompletableFuture.completedFuture(Map.of());
      }
    },
    /** A loader that always throws an interrupted exception. */
    ASYNC_BULK_INTERRUPTED {
      @Override public Int load(Int key) {
        throw new UnsupportedOperationException();
      }
      @Override public CompletableFuture<Map<Int, Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) throws InterruptedException {
        throw new InterruptedException();
      }
    },

    /** A loader that always throws a runtime exception. */
    REFRESH_EXCEPTIONAL {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        throw new IllegalStateException();
      }
    },
    /** A loader that always throws a checked exception. */
    REFRESH_CHECKED_EXCEPTIONAL {
      @Override public Int load(Int key) throws ExecutionException {
        throw new ExecutionException(null);
      }
      @Override public CompletableFuture<Int> asyncLoad(
          Int key, Executor executor) throws ExecutionException {
        throw new ExecutionException(null);
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) throws ExecutionException {
        throw new ExecutionException(null);
      }
    },
    /** A loader that always throws an interrupted exception. */
    REFRESH_INTERRUPTED {
      @Override public Int load(Int key) throws InterruptedException {
        throw new InterruptedException();
      }
      @Override public CompletableFuture<Int> asyncLoad(
          Int key, Executor executor) throws InterruptedException {
        throw new InterruptedException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) throws InterruptedException {
        throw new InterruptedException();
      }
    };

    private final boolean bulk;
    private final AsyncCacheLoader<Int, Int> asyncLoader;

    Loader() {
      bulk = name().contains("BULK");
      asyncLoader = bulk
          ? new BulkSeriazableAsyncCacheLoader(this)
          : new SeriazableAsyncCacheLoader(this);
    }

    public boolean isBulk() {
      return bulk;
    }

    /** Returns a serializable view restricted to the {@link AsyncCacheLoader} interface. */
    public AsyncCacheLoader<Int, Int> async() {
      return asyncLoader;
    }

    private static class SeriazableAsyncCacheLoader
        implements AsyncCacheLoader<Int, Int>, Serializable {
      private static final long serialVersionUID = 1L;

      final Loader loader;

      SeriazableAsyncCacheLoader(Loader loader) {
        this.loader = loader;
      }
      @Override
      public CompletableFuture<? extends Int> asyncLoad(
          Int key, Executor executor) throws Exception {
        return loader.asyncLoad(key, executor);
      }
      private Object readResolve() {
        return loader.asyncLoader;
      }
    }

    private static final class BulkSeriazableAsyncCacheLoader extends SeriazableAsyncCacheLoader {
      private static final long serialVersionUID = 1L;

      BulkSeriazableAsyncCacheLoader(Loader loader) {
        super(loader);
      }
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        throw new IllegalStateException();
      }
      @Override
      public CompletableFuture<? extends Map<? extends Int, ? extends Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) throws Exception {
        return loader.asyncLoadAll(keys, executor);
      }
    }
  }

  /* --------------- Executor --------------- */

  /** The executors retrieved from a supplier, each resulting in a new combination. */
  CacheExecutor[] executor() default {
    CacheExecutor.DIRECT,
  };

  /** If the executor is allowed to have failures. */
  ExecutorFailure executorFailure() default ExecutorFailure.DISALLOWED;

  enum ExecutorFailure {
    EXPECTED, DISALLOWED, IGNORED
  }

  /** The executors that the cache can be configured with. */
  enum CacheExecutor {
    // Use with caution as may be unpredictable during tests if awaiting completion
    DEFAULT(() -> null), // fork-join common pool
    // Cache implementations must avoid deadlocks by incorrectly assuming async execution
    DIRECT(() -> new TrackingExecutor(MoreExecutors.newDirectExecutorService())),
    // Cache implementations must continue to evict if the maintenance task is lost
    DISCARDING(() -> new TrackingExecutor(TestingExecutors.noOpScheduledExecutor())),
    // Use with caution as may be unpredictable during tests if awaiting completion
    THREADED(() -> new TrackingExecutor(ConcurrentTestHarness.executor)),
    // Cache implementations must avoid corrupting internal state due to rejections
    REJECTING(() -> {
      return new TrackingExecutor(new ForkJoinPool() {
        @Override public void execute(Runnable task) {
          throw new RejectedExecutionException();
        }
      });
    });

    private final Supplier<TrackingExecutor> executor;

    CacheExecutor(Supplier<TrackingExecutor> executor) {
      this.executor = requireNonNull(executor);
    }

    public TrackingExecutor create() {
      return executor.get();
    }
  }

  /* --------------- Scheduler --------------- */

  /** The executors retrieved from a supplier, each resulting in a new combination. */
  CacheScheduler[] scheduler() default {
    CacheScheduler.DISABLED,
  };

  /** The scheduler that the cache can be configured with. */
  enum CacheScheduler {
    DISABLED(() -> null),
    SYSTEM(Scheduler::systemScheduler),
    THREADED(() -> Scheduler.forScheduledExecutorService(scheduledExecutor)),
    MOCKITO(() -> Mockito.mock(Scheduler.class));

    private final Supplier<Scheduler> scheduler;

    CacheScheduler(Supplier<Scheduler> scheduler) {
      this.scheduler = requireNonNull(scheduler);
    }

    public Scheduler create() {
      return scheduler.get();
    }
  }

  /* --------------- Populated --------------- */

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

    Population(long size) {
      this.size = size;
    }

    public long size() {
      return size;
    }
  }
}
