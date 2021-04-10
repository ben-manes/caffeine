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

import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Async.AsyncEvictionListener;
import com.github.benmanes.caffeine.cache.Async.AsyncExpiry;
import com.github.benmanes.caffeine.cache.Async.AsyncRemovalListener;
import com.github.benmanes.caffeine.cache.Async.AsyncWeigher;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.ConcurrentStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.annotations.FormatMethod;

/**
 * A builder of {@link Cache}, {@link LoadingCache}, {@link AsyncCache}, and
 * {@link AsyncLoadingCache} instances having a combination of the following features:
 * <ul>
 *   <li>automatic loading of entries into the cache, optionally asynchronously
 *   <li>size-based eviction when a maximum is exceeded based on frequency and recency
 *   <li>time-based expiration of entries, measured since last access or last write
 *   <li>asynchronously refresh when the first stale request for an entry occurs
 *   <li>keys automatically wrapped in {@linkplain WeakReference weak} references
 *   <li>values automatically wrapped in {@linkplain WeakReference weak} or
 *       {@linkplain SoftReference soft} references
 *   <li>writes propagated to an external resource
 *   <li>notification of evicted (or otherwise removed) entries
 *   <li>accumulation of cache access statistics
 * </ul>
 * <p>
 * These features are all optional; caches can be created using all or none of them. By default
 * cache instances created by {@code Caffeine} will not perform any type of eviction.
 * <p>
 * Usage example:
 * <pre>{@code
 *   LoadingCache<Key, Graph> graphs = Caffeine.newBuilder()
 *       .maximumSize(10_000)
 *       .expireAfterWrite(Duration.ofMinutes(10))
 *       .removalListener((Key key, Graph graph, RemovalCause cause) ->
 *           System.out.printf("Key %s was removed (%s)%n", key, cause))
 *       .build(key -> createExpensiveGraph(key));
 * }</pre>
 * <p>
 * The returned cache is implemented as a hash table with similar performance characteristics to
 * {@link ConcurrentHashMap}. The {@code asMap} view (and its collection views) have <i>weakly
 * consistent iterators</i>. This means that they are safe for concurrent use, but if other threads
 * modify the cache after the iterator is created, it is undefined which of these changes, if any,
 * are reflected in that iterator. These iterators never throw
 * {@link ConcurrentModificationException}.
 * <p>
 * <b>Note:</b> by default, the returned cache uses equality comparisons (the
 * {@link Object#equals equals} method) to determine equality for keys or values. However, if
 * {@link #weakKeys} was specified, the cache uses identity ({@code ==}) comparisons instead for
 * keys. Likewise, if {@link #weakValues} or {@link #softValues} was specified, the cache uses
 * identity comparisons for values.
 * <p>
 * Entries are automatically evicted from the cache when any of
 * {@linkplain #maximumSize(long) maximumSize}, {@linkplain #maximumWeight(long) maximumWeight},
 * {@linkplain #expireAfter(Expiry) expireAfter}, {@linkplain #expireAfterWrite expireAfterWrite},
 * {@linkplain #expireAfterAccess expireAfterAccess}, {@linkplain #weakKeys weakKeys},
 * {@linkplain #weakValues weakValues}, or {@linkplain #softValues softValues} are requested.
 * <p>
 * If {@linkplain #maximumSize(long) maximumSize} or {@linkplain #maximumWeight(long) maximumWeight}
 * is requested entries may be evicted on each cache modification.
 * <p>
 * If {@linkplain #expireAfter(Expiry) expireAfter},
 * {@linkplain #expireAfterWrite expireAfterWrite}, or
 * {@linkplain #expireAfterAccess expireAfterAccess} is requested then entries may be evicted on
 * each cache modification, on occasional cache accesses, or on calls to {@link Cache#cleanUp}. A
 * {@linkplain #scheduler(Scheduler)} may be specified to provide prompt removal of expired entries
 * rather than waiting until activity triggers the periodic maintenance. Expired entries may be
 * counted by {@link Cache#estimatedSize()}, but will never be visible to read or write operations.
 * <p>
 * If {@linkplain #weakKeys weakKeys}, {@linkplain #weakValues weakValues}, or
 * {@linkplain #softValues softValues} are requested, it is possible for a key or value present in
 * the cache to be reclaimed by the garbage collector. Entries with reclaimed keys or values may be
 * removed from the cache on each cache modification, on occasional cache accesses, or on calls to
 * {@link Cache#cleanUp}; such entries may be counted in {@link Cache#estimatedSize()}, but will
 * never be visible to read or write operations.
 * <p>
 * Certain cache configurations will result in the accrual of periodic maintenance tasks which
 * will be performed during write operations, or during occasional read operations in the absence of
 * writes. The {@link Cache#cleanUp} method of the returned cache will also perform maintenance, but
 * calling it should not be necessary with a high throughput cache. Only caches built with
 * {@linkplain #maximumSize maximumSize}, {@linkplain #maximumWeight maximumWeight},
 * {@linkplain #expireAfter(Expiry) expireAfter}, {@linkplain #expireAfterWrite expireAfterWrite},
 * {@linkplain #expireAfterAccess expireAfterAccess}, {@linkplain #weakKeys weakKeys},
 * {@linkplain #weakValues weakValues}, or {@linkplain #softValues softValues} perform periodic
 * maintenance.
 * <p>
 * The caches produced by {@code Caffeine} are serializable, and the deserialized caches retain all
 * the configuration properties of the original cache. Note that the serialized form does <i>not</i>
 * include cache contents, but only configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the most general key type this builder will be able to create caches for. This is
 *     normally {@code Object} unless it is constrained by using a method like {@code
 *     #removalListener}
 * @param <V> the most general value type this builder will be able to create caches for. This is
 *     normally {@code Object} unless it is constrained by using a method like {@code
 *     #removalListener}
 */
public final class Caffeine<K extends @NonNull Object, V extends @NonNull Object> {
  static final Logger logger = System.getLogger(Caffeine.class.getName());
  static final Supplier<StatsCounter> ENABLED_STATS_COUNTER_SUPPLIER = ConcurrentStatsCounter::new;

  enum Strength { WEAK, SOFT }
  static final int UNSET_INT = -1;

  static final int DEFAULT_INITIAL_CAPACITY = 16;
  static final int DEFAULT_EXPIRATION_NANOS = 0;
  static final int DEFAULT_REFRESH_NANOS = 0;

  boolean strictParsing = true;

  long maximumSize = UNSET_INT;
  long maximumWeight = UNSET_INT;
  int initialCapacity = UNSET_INT;

  long expireAfterWriteNanos = UNSET_INT;
  long expireAfterAccessNanos = UNSET_INT;
  long refreshAfterWriteNanos = UNSET_INT;

  @Nullable RemovalListener<? super K, ? super V> evictionListener;
  @Nullable RemovalListener<? super K, ? super V> removalListener;
  @Nullable Supplier<StatsCounter> statsCounterSupplier;
  @Nullable Weigher<? super K, ? super V> weigher;
  @Nullable Expiry<? super K, ? super V> expiry;
  @Nullable Scheduler scheduler;
  @Nullable Executor executor;
  @Nullable Ticker ticker;

  @Nullable Strength keyStrength;
  @Nullable Strength valueStrength;

  private Caffeine() {}

  /** Ensures that the argument expression is true. */
  @FormatMethod
  static void requireArgument(boolean expression, String template, @Nullable Object... args) {
    if (!expression) {
      throw new IllegalArgumentException(String.format(template, args));
    }
  }

  /** Ensures that the argument expression is true. */
  static void requireArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /** Ensures that the state expression is true. */
  static void requireState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /** Ensures that the state expression is true. */
  @FormatMethod
  static void requireState(boolean expression, String template, @Nullable Object... args) {
    if (!expression) {
      throw new IllegalStateException(String.format(template, args));
    }
  }

  /** Returns the smallest power of two greater than or equal to {@code x}. */
  static int ceilingPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << -Integer.numberOfLeadingZeros(x - 1);
  }

  /** Returns the smallest power of two greater than or equal to {@code x}. */
  static long ceilingPowerOfTwo(long x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1L << -Long.numberOfLeadingZeros(x - 1);
  }

  /**
   * Constructs a new {@code Caffeine} instance with default settings, including strong keys, strong
   * values, and no automatic eviction of any kind.
   * <p>
   * Note that while this return type is {@code Caffeine<Object, Object>}, type parameters on the
   * {@link #build} methods allow you to create a cache of any key and value type desired.
   *
   * @return a new instance with default settings
   */
  @CheckReturnValue
  public static Caffeine<Object, Object> newBuilder() {
    return new Caffeine<>();
  }

  /**
   * Constructs a new {@code Caffeine} instance with the settings specified in {@code spec}.
   *
   * @param spec the specification to build from
   * @return a new instance with the specification's settings
   */
  @CheckReturnValue
  public static Caffeine<Object, Object> from(CaffeineSpec spec) {
    Caffeine<Object, Object> builder = spec.toBuilder();
    builder.strictParsing = false;
    return builder;
  }

  /**
   * Constructs a new {@code Caffeine} instance with the settings specified in {@code spec}.
   *
   * @param spec a String in the format specified by {@link CaffeineSpec}
   * @return a new instance with the specification's settings
   */
  @CheckReturnValue
  public static Caffeine<Object, Object> from(String spec) {
    return from(CaffeineSpec.parse(spec));
  }

  /**
   * Sets the minimum total size for the internal data structures. Providing a large enough estimate
   * at construction time avoids the need for expensive resizing operations later, but setting this
   * value unnecessarily high wastes memory.
   *
   * @param initialCapacity minimum total size for the internal data structures
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code initialCapacity} is negative
   * @throws IllegalStateException if an initial capacity was already set
   */
  public Caffeine<K, V> initialCapacity(@NonNegative int initialCapacity) {
    requireState(this.initialCapacity == UNSET_INT,
        "initial capacity was already set to %s", this.initialCapacity);
    requireArgument(initialCapacity >= 0);
    this.initialCapacity = initialCapacity;
    return this;
  }

  boolean hasInitialCapacity() {
    return (initialCapacity != UNSET_INT);
  }

  int getInitialCapacity() {
    return hasInitialCapacity() ? initialCapacity : DEFAULT_INITIAL_CAPACITY;
  }

  /**
   * Specifies the executor to use when running asynchronous tasks. The executor is delegated to
   * when sending removal notifications, when asynchronous computations are performed by
   * {@link AsyncCache} or {@link LoadingCache#refresh} or {@link #refreshAfterWrite}, or when
   * performing periodic maintenance. By default, {@link ForkJoinPool#commonPool()} is used.
   * <p>
   * The primary intent of this method is to facilitate testing of caches which have been configured
   * with {@link #removalListener} or utilize asynchronous computations. A test may instead prefer
   * to configure the cache to execute tasks directly on the same thread.
   * <p>
   * Beware that configuring a cache with an executor that throws {@link RejectedExecutionException}
   * may experience non-deterministic behavior.
   *
   * @param executor the executor to use for asynchronous execution
   * @return this {@code Caffeine} instance (for chaining)
   * @throws NullPointerException if the specified executor is null
   */
  public Caffeine<K, V> executor(Executor executor) {
    requireState(this.executor == null, "executor was already set to %s", this.executor);
    this.executor = requireNonNull(executor);
    return this;
  }

  Executor getExecutor() {
    return (executor == null) ? ForkJoinPool.commonPool() : executor;
  }

  /**
   * Specifies the scheduler to use when scheduling routine maintenance based on an expiration
   * event. This augments the periodic maintenance that occurs during normal cache operations to
   * allow for the prompt removal of expired entries regardless of whether any cache activity is
   * occurring at that time. By default, {@link Scheduler#disabledScheduler()} is used.
   * <p>
   * The scheduling between expiration events is paced to exploit batching and to minimize
   * executions in short succession. This minimum difference between the scheduled executions is
   * implementation-specific, currently at ~1 second (2^30 ns). In addition, the provided scheduler
   * may not offer real-time guarantees (including {@link ScheduledThreadPoolExecutor}). The
   * scheduling is best-effort and does not make any hard guarantees of when an expired entry will
   * be removed.
   * <p>
   * <b>Note for Java 9 and later:</b> consider using {@link Scheduler#systemScheduler()} to
   * leverage the dedicated, system-wide scheduling thread.
   *
   * @param scheduler the scheduler that submits a task to the {@link #executor(Executor)} after a
   *        given delay
   * @return this {@code Caffeine} instance (for chaining)
   * @throws NullPointerException if the specified scheduler is null
   */
  public Caffeine<K, V> scheduler(Scheduler scheduler) {
    requireState(this.scheduler == null, "scheduler was already set to %s", this.scheduler);
    this.scheduler = requireNonNull(scheduler);
    return this;
  }

  Scheduler getScheduler() {
    if ((scheduler == null) || (scheduler == Scheduler.disabledScheduler())) {
      return Scheduler.disabledScheduler();
    } else if (scheduler == Scheduler.systemScheduler()) {
      return scheduler;
    }
    return Scheduler.guardedScheduler(scheduler);
  }

  /**
   * Specifies the maximum number of entries the cache may contain. Note that the cache <b>may evict
   * an entry before this limit is exceeded or temporarily exceed the threshold while evicting</b>.
   * As the cache size grows close to the maximum, the cache evicts entries that are less likely to
   * be used again. For example, the cache may evict an entry because it hasn't been used recently
   * or very often.
   * <p>
   * When {@code size} is zero, elements will be evicted immediately after being loaded into the
   * cache. This can be useful in testing, or to disable caching temporarily without a code change.
   * As eviction is scheduled on the configured {@link #executor}, tests may instead prefer
   * to configure the cache to execute tasks directly on the same thread.
   * <p>
   * This feature cannot be used in conjunction with {@link #maximumWeight}.
   *
   * @param maximumSize the maximum size of the cache
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code size} is negative
   * @throws IllegalStateException if a maximum size or weight was already set
   */
  public Caffeine<K, V> maximumSize(@NonNegative long maximumSize) {
    requireState(this.maximumSize == UNSET_INT,
        "maximum size was already set to %s", this.maximumSize);
    requireState(this.maximumWeight == UNSET_INT,
        "maximum weight was already set to %s", this.maximumWeight);
    requireState(this.weigher == null, "maximum size can not be combined with weigher");
    requireArgument(maximumSize >= 0, "maximum size must not be negative");
    this.maximumSize = maximumSize;
    return this;
  }

  /**
   * Specifies the maximum weight of entries the cache may contain. Weight is determined using the
   * {@link Weigher} specified with {@link #weigher}, and use of this method requires a
   * corresponding call to {@link #weigher} prior to calling {@link #build}.
   * <p>
   * Note that the cache <b>may evict an entry before this limit is exceeded or temporarily exceed
   * the threshold while evicting</b>. As the cache size grows close to the maximum, the cache
   * evicts entries that are less likely to be used again. For example, the cache may evict an entry
   * because it hasn't been used recently or very often.
   * <p>
   * When {@code maximumWeight} is zero, elements will be evicted immediately after being loaded
   * into the cache. This can be useful in testing, or to disable caching temporarily without a code
   * change. As eviction is scheduled on the configured {@link #executor}, tests may instead prefer
   * to configure the cache to execute tasks directly on the same thread.
   * <p>
   * Note that weight is only used to determine whether the cache is over capacity; it has no effect
   * on selecting which entry should be evicted next.
   * <p>
   * This feature cannot be used in conjunction with {@link #maximumSize}.
   *
   * @param maximumWeight the maximum total weight of entries the cache may contain
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code maximumWeight} is negative
   * @throws IllegalStateException if a maximum weight or size was already set
   */
  public Caffeine<K, V> maximumWeight(@NonNegative long maximumWeight) {
    requireState(this.maximumWeight == UNSET_INT,
        "maximum weight was already set to %s", this.maximumWeight);
    requireState(this.maximumSize == UNSET_INT,
        "maximum size was already set to %s", this.maximumSize);
    requireArgument(maximumWeight >= 0, "maximum weight must not be negative");
    this.maximumWeight = maximumWeight;
    return this;
  }

  /**
   * Specifies the weigher to use in determining the weight of entries. Entry weight is taken into
   * consideration by {@link #maximumWeight(long)} when determining which entries to evict, and use
   * of this method requires a corresponding call to {@link #maximumWeight(long)} prior to calling
   * {@link #build}. Weights are measured and recorded when entries are inserted into or updated in
   * the cache, and are thus effectively static during the lifetime of a cache entry.
   * <p>
   * When the weight of an entry is zero it will not be considered for size-based eviction (though
   * it still may be evicted by other means).
   * <p>
   * <b>Important note:</b> Instead of returning <em>this</em> as a {@code Caffeine} instance, this
   * method returns {@code Caffeine<K1, V1>}. From this point on, either the original reference or
   * the returned reference may be used to complete configuration and build the cache, but only the
   * "generic" one is type-safe. That is, it will properly prevent you from building caches whose
   * key or value types are incompatible with the types accepted by the weigher already provided;
   * the {@code Caffeine} type cannot do this. For best results, simply use the standard
   * method-chaining idiom, as illustrated in the documentation at top, configuring a
   * {@code Caffeine} and building your {@link Cache} all in a single statement.
   * <p>
   * <b>Warning:</b> if you ignore the above advice, and use this {@code Caffeine} to build a cache
   * whose key or value type is incompatible with the weigher, you will likely experience a
   * {@link ClassCastException} at some <i>undefined</i> point in the future.
   *
   * @param weigher the weigher to use in calculating the weight of cache entries
   * @param <K1> key type of the weigher
   * @param <V1> value type of the weigher
   * @return the cache builder reference that should be used instead of {@code this} for any
   *         remaining configuration and cache building
   * @throws IllegalStateException if a weigher was already set
   */
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> weigher(
      Weigher<? super K1, ? super V1> weigher) {
    requireNonNull(weigher);
    requireState(this.weigher == null, "weigher was already set to %s", this.weigher);
    requireState(!strictParsing || this.maximumSize == UNSET_INT,
        "weigher can not be combined with maximum size");

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.weigher = weigher;
    return self;
  }

  boolean evicts() {
    return getMaximum() != UNSET_INT;
  }

  boolean isWeighted() {
    return (weigher != null);
  }

  long getMaximum() {
    return isWeighted() ? maximumWeight : maximumSize;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  <K1 extends K, V1 extends V> Weigher<K1, V1> getWeigher(boolean isAsync) {
    Weigher<K1, V1> delegate = (weigher == null) || (weigher == Weigher.singletonWeigher())
        ? Weigher.singletonWeigher()
        : Weigher.boundedWeigher((Weigher<K1, V1>) weigher);
    return isAsync ? (Weigher<K1, V1>) new AsyncWeigher(delegate) : delegate;
  }

  /**
   * Specifies that each key (not value) stored in the cache should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   * <p>
   * <b>Warning:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of keys. Its {@link Cache#asMap} view will therefore
   * technically violate the {@link Map} specification (in the same way that {@link IdentityHashMap}
   * does).
   * <p>
   * Entries with keys that have been garbage collected may be counted in
   * {@link Cache#estimatedSize()}, but will never be visible to read or write operations; such
   * entries are cleaned up as part of the routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction when {@link #weakKeys()} is combined with
   * {@link #buildAsync}.
   *
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalStateException if the key strength was already set
   */
  public Caffeine<K, V> weakKeys() {
    requireState(keyStrength == null, "Key strength was already set to %s", keyStrength);
    keyStrength = Strength.WEAK;
    return this;
  }

  boolean isStrongKeys() {
    return (keyStrength == null);
  }

  /**
   * Specifies that each value (not key) stored in the cache should be wrapped in a
   * {@link WeakReference} (by default, strong references are used).
   * <p>
   * Weak values will be garbage collected once they are weakly reachable. This makes them a poor
   * candidate for caching; consider {@link #softValues} instead.
   * <p>
   * <b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of values.
   * <p>
   * Entries with values that have been garbage collected may be counted in
   * {@link Cache#estimatedSize()}, but will never be visible to read or write operations; such
   * entries are cleaned up as part of the routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction with {@link #buildAsync}.
   *
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalStateException if the value strength was already set
   */
  public Caffeine<K, V> weakValues() {
    requireState(valueStrength == null, "Value strength was already set to %s", valueStrength);
    valueStrength = Strength.WEAK;
    return this;
  }

  boolean isStrongValues() {
    return (valueStrength == null);
  }

  boolean isWeakValues() {
    return (valueStrength == Strength.WEAK);
  }

  /**
   * Specifies that each value (not key) stored in the cache should be wrapped in a
   * {@link SoftReference} (by default, strong references are used). Softly-referenced objects will
   * be garbage-collected in a <i>globally</i> least-recently-used manner, in response to memory
   * demand.
   * <p>
   * <b>Warning:</b> in most circumstances it is better to set a per-cache
   * {@linkplain #maximumSize(long) maximum size} instead of using soft references. You should only
   * use this method if you are very familiar with the practical consequences of soft references.
   * <p>
   * <b>Note:</b> when this method is used, the resulting cache will use identity ({@code ==})
   * comparison to determine equality of values.
   * <p>
   * Entries with values that have been garbage collected may be counted in
   * {@link Cache#estimatedSize()}, but will never be visible to read or write operations; such
   * entries are cleaned up as part of the routine maintenance described in the class javadoc.
   * <p>
   * This feature cannot be used in conjunction with {@link #buildAsync}.
   *
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalStateException if the value strength was already set
   */
  public Caffeine<K, V> softValues() {
    requireState(valueStrength == null, "Value strength was already set to %s", valueStrength);
    valueStrength = Strength.SOFT;
    return this;
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, or the most recent replacement of its value.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc. A {@link #scheduler(Scheduler)} may be configured for a prompt
   * removal of expired entries.
   *
   * @param duration the length of time after an entry is created that it should be automatically
   *        removed
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to live or time to idle was already set
   * @throws ArithmeticException for durations greater than +/- approximately 292 years
   */
  public Caffeine<K, V> expireAfterWrite(Duration duration) {
    return expireAfterWrite(saturatedToNanos(duration), TimeUnit.NANOSECONDS);
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, or the most recent replacement of its value.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc. A {@link #scheduler(Scheduler)} may be configured for a prompt
   * removal of expired entries.
   * <p>
   * If you can represent the duration as a {@link java.time.Duration} (which should be preferred
   * when feasible), use {@link #expireAfterWrite(Duration)} instead.
   *
   * @param duration the length of time after an entry is created that it should be automatically
   *        removed
   * @param unit the unit that {@code duration} is expressed in
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to live or variable expiration was already set
   */
  public Caffeine<K, V> expireAfterWrite(@NonNegative long duration, TimeUnit unit) {
    requireState(expireAfterWriteNanos == UNSET_INT,
        "expireAfterWrite was already set to %s ns", expireAfterWriteNanos);
    requireState(expiry == null, "expireAfterWrite may not be used with variable expiration");
    requireArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
    this.expireAfterWriteNanos = unit.toNanos(duration);
    return this;
  }

  long getExpiresAfterWriteNanos() {
    return expiresAfterWrite() ? expireAfterWriteNanos : DEFAULT_EXPIRATION_NANOS;
  }

  boolean expiresAfterWrite() {
    return (expireAfterWriteNanos != UNSET_INT);
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, the most recent replacement of its value, or its last
   * access. Access time is reset by all cache read and write operations (including {@code
   * Cache.asMap().get(Object)} and {@code Cache.asMap().put(K, V)}), but not by operations on the
   * collection-views of {@link Cache#asMap}.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc. A {@link #scheduler(Scheduler)} may be configured for a prompt
   * removal of expired entries.
   *
   * @param duration the length of time after an entry is last accessed that it should be
   *        automatically removed
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to idle or time to live was already set
   * @throws ArithmeticException for durations greater than +/- approximately 292 years
   */
  public Caffeine<K, V> expireAfterAccess(Duration duration) {
    return expireAfterAccess(saturatedToNanos(duration), TimeUnit.NANOSECONDS);
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a fixed duration
   * has elapsed after the entry's creation, the most recent replacement of its value, or its last
   * read. Access time is reset by all cache read and write operations (including
   * {@code Cache.asMap().get(Object)} and {@code Cache.asMap().put(K, V)}), but not by operations
   * on the collection-views of {@link Cache#asMap}.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc. A {@link #scheduler(Scheduler)} may be configured for a prompt
   * removal of expired entries.
   * <p>
   * If you can represent the duration as a {@link java.time.Duration} (which should be preferred
   * when feasible), use {@link #expireAfterAccess(Duration)} instead.
   *
   * @param duration the length of time after an entry is last accessed that it should be
   *        automatically removed
   * @param unit the unit that {@code duration} is expressed in
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code duration} is negative
   * @throws IllegalStateException if the time to idle or variable expiration was already set
   */
  public Caffeine<K, V> expireAfterAccess(@NonNegative long duration, TimeUnit unit) {
    requireState(expireAfterAccessNanos == UNSET_INT,
        "expireAfterAccess was already set to %s ns", expireAfterAccessNanos);
    requireState(expiry == null, "expireAfterAccess may not be used with variable expiration");
    requireArgument(duration >= 0, "duration cannot be negative: %s %s", duration, unit);
    this.expireAfterAccessNanos = unit.toNanos(duration);
    return this;
  }

  long getExpiresAfterAccessNanos() {
    return expiresAfterAccess() ? expireAfterAccessNanos : DEFAULT_EXPIRATION_NANOS;
  }

  boolean expiresAfterAccess() {
    return (expireAfterAccessNanos != UNSET_INT);
  }

  /**
   * Specifies that each entry should be automatically removed from the cache once a duration has
   * elapsed after the entry's creation, the most recent replacement of its value, or its last
   * read. The expiration time is reset by all cache read and write operations (including
   * {@code Cache.asMap().get(Object)} and {@code Cache.asMap().put(K, V)}), but not by operations
   * on the collection-views of {@link Cache#asMap}.
   * <p>
   * Expired entries may be counted in {@link Cache#estimatedSize()}, but will never be visible to
   * read or write operations. Expired entries are cleaned up as part of the routine maintenance
   * described in the class javadoc. A {@link #scheduler(Scheduler)} may be configured for a prompt
   * removal of expired entries.
   * <p>
   * <b>Important note:</b> after invoking this method, do not continue to use <i>this</i> cache
   * builder reference; instead use the reference this method <i>returns</i>. At runtime, these
   * point to the same instance, but only the returned reference has the correct generic type
   * information so as to ensure type safety. For best results, use the standard method-chaining
   * idiom illustrated in the class documentation above, configuring a builder and building your
   * cache in a single statement. Failure to heed this advice can result in a
   * {@link ClassCastException} being thrown by a cache operation at some <i>undefined</i> point in
   * the future.
   *
   * @param expiry the expiry to use in calculating the expiration time of cache entries
   * @param <K1> key type of the weigher
   * @param <V1> value type of the weigher
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalStateException if expiration was already set
   */
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> expireAfter(
      Expiry<? super K1, ? super V1> expiry) {
    requireNonNull(expiry);
    requireState(this.expiry == null, "Expiry was already set to %s", this.expiry);
    requireState(this.expireAfterAccessNanos == UNSET_INT,
        "Expiry may not be used with expiresAfterAccess");
    requireState(this.expireAfterWriteNanos == UNSET_INT,
        "Expiry may not be used with expiresAfterWrite");

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.expiry = expiry;
    return self;
  }

  boolean expiresVariable() {
    return expiry != null;
  }

  @SuppressWarnings("unchecked")
  @Nullable Expiry<K, V> getExpiry(boolean isAsync) {
    return isAsync && (expiry != null)
        ? (Expiry<K, V>) new AsyncExpiry<>(expiry)
        : (Expiry<K, V>) expiry;
  }

  /**
   * Specifies that active entries are eligible for automatic refresh once a fixed duration has
   * elapsed after the entry's creation, or the most recent replacement of its value. The semantics
   * of refreshes are specified in {@link LoadingCache#refresh}, and are performed by calling {@link
   * CacheLoader#reload}.
   * <p>
   * Automatic refreshes are performed when the first stale request for an entry occurs. The request
   * triggering refresh will make an asynchronous call to {@link CacheLoader#reload} and immediately
   * return the old value.
   * <p>
   * <b>Note:</b> <i>all exceptions thrown during refresh will be logged and then swallowed</i>.
   *
   * @param duration the length of time after an entry is created that it should be considered
   *     stale, and thus eligible for refresh
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code duration} is zero or negative
   * @throws IllegalStateException if the refresh interval was already set
   * @throws ArithmeticException for durations greater than +/- approximately 292 years
   */
  public Caffeine<K, V> refreshAfterWrite(Duration duration) {
    return refreshAfterWrite(saturatedToNanos(duration), TimeUnit.NANOSECONDS);
  }

  /**
   * Specifies that active entries are eligible for automatic refresh once a fixed duration has
   * elapsed after the entry's creation, or the most recent replacement of its value. The semantics
   * of refreshes are specified in {@link LoadingCache#refresh}, and are performed by calling
   * {@link CacheLoader#reload}.
   * <p>
   * Automatic refreshes are performed when the first stale request for an entry occurs. The request
   * triggering refresh will make an asynchronous call to {@link CacheLoader#reload} and immediately
   * return the old value.
   * <p>
   * <b>Note:</b> <i>all exceptions thrown during refresh will be logged and then swallowed</i>.
   * <p>
   * If you can represent the duration as a {@link java.time.Duration} (which should be preferred
   * when feasible), use {@link #refreshAfterWrite(Duration)} instead.
   *
   * @param duration the length of time after an entry is created that it should be considered
   *        stale, and thus eligible for refresh
   * @param unit the unit that {@code duration} is expressed in
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalArgumentException if {@code duration} is zero or negative
   * @throws IllegalStateException if the refresh interval was already set
   */
  public Caffeine<K, V> refreshAfterWrite(@NonNegative long duration, TimeUnit unit) {
    requireNonNull(unit);
    requireState(refreshAfterWriteNanos == UNSET_INT,
        "refreshAfterWriteNanos was already set to %s ns", refreshAfterWriteNanos);
    requireArgument(duration > 0, "duration must be positive: %s %s", duration, unit);
    this.refreshAfterWriteNanos = unit.toNanos(duration);
    return this;
  }

  long getRefreshAfterWriteNanos() {
    return refreshAfterWrite() ? refreshAfterWriteNanos : DEFAULT_REFRESH_NANOS;
  }

  boolean refreshAfterWrite() {
    return refreshAfterWriteNanos != UNSET_INT;
  }

  /**
   * Specifies a nanosecond-precision time source for use in determining when entries should be
   * expired or refreshed. By default, {@link System#nanoTime} is used.
   * <p>
   * The primary intent of this method is to facilitate testing of caches which have been configured
   * with {@link #expireAfterWrite}, {@link #expireAfterAccess}, or {@link #refreshAfterWrite}.
   *
   * @param ticker a nanosecond-precision time source
   * @return this {@code Caffeine} instance (for chaining)
   * @throws IllegalStateException if a ticker was already set
   * @throws NullPointerException if the specified ticker is null
   */
  public Caffeine<K, V> ticker(Ticker ticker) {
    requireState(this.ticker == null, "Ticker was already set to %s", this.ticker);
    this.ticker = requireNonNull(ticker);
    return this;
  }

  Ticker getTicker() {
    boolean useTicker = expiresVariable() || expiresAfterAccess()
        || expiresAfterWrite() || refreshAfterWrite() || isRecordingStats();
    return useTicker
        ? (ticker == null) ? Ticker.systemTicker() : ticker
        : Ticker.disabledTicker();
  }

  /**
   * Specifies a listener instance that caches should notify each time an entry is evicted. The
   * cache will invoke this listener during the atomic operation to remove the entry. In the case of
   * expiration or reference collection, the entry may be pending removal and will be discarded as
   * as part of the routine maintenance described in the class documentation above. For a more
   * prompt notification on expiration a {@link #scheduler(Scheduler)} may be configured. A
   * {@link #removalListener(RemovalListener)} may be preferred when the listener should be invoked
   * for any {@linkplain RemovalCause reason}, be performed outside of the atomic operation to
   * remove the entry, and delegated to the configured {@link #executor(Executor)}.
   * <p>
   * <b>Important note:</b> after invoking this method, do not continue to use <i>this</i> cache
   * builder reference; instead use the reference this method <i>returns</i>. At runtime, these
   * point to the same instance, but only the returned reference has the correct generic type
   * information so as to ensure type safety. For best results, use the standard method-chaining
   * idiom illustrated in the class documentation above, configuring a builder and building your
   * cache in a single statement. Failure to heed this advice can result in a
   * {@link ClassCastException} being thrown by a cache operation at some <i>undefined</i> point in
   * the future.
   * <p>
   * <b>Warning:</b> any exception thrown by {@code listener} will <i>not</i> be propagated to the
   * {@code Cache} user, only logged via a {@link Logger}.
   * <p>
   * This feature cannot be used in conjunction when {@link #weakKeys()} is combined with
   * {@link #buildAsync}.
   *
   * @param evictionListener a listener instance that caches should notify each time an entry is
   *        being automatically removed due to eviction
   * @param <K1> the key type of the listener
   * @param <V1> the value type of the listener
   * @return the cache builder reference that should be used instead of {@code this} for any
   *         remaining configuration and cache building
   * @throws IllegalStateException if a removal listener was already set
   * @throws NullPointerException if the specified removal listener is null
   */
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> evictionListener(
      RemovalListener<? super K1, ? super V1> evictionListener) {
    requireState(this.evictionListener == null,
        "eviction listener was already set to %s", this.evictionListener);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.evictionListener = requireNonNull(evictionListener);
    return self;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  <K1 extends K, V1 extends V> @Nullable RemovalListener<K1, V1> getEvictionListener(
      boolean async) {
    var castedListener = (RemovalListener<K1, V1>) evictionListener;
    return async && (castedListener != null)
        ? new AsyncEvictionListener(castedListener)
        : castedListener;
  }

  /**
   * Specifies a listener instance that caches should notify each time an entry is removed for any
   * {@linkplain RemovalCause reason}. The cache will invoke this listener on the configured
   * {@link #executor(Executor)} after the entry's removal operation has completed. In the case of
   * expiration or reference collection, the entry may be pending removal and will be discarded as
   * as part of the routine maintenance described in the class documentation above. For a more
   * prompt notification on expiration a {@link #scheduler(Scheduler)} may be configured. An
   * {@link #evictionListener(RemovalListener)} may be preferred when the listener should be invoked
   * as part of the atomic operation to remove the entry.
   * <p>
   * <b>Important note:</b> after invoking this method, do not continue to use <i>this</i> cache
   * builder reference; instead use the reference this method <i>returns</i>. At runtime, these
   * point to the same instance, but only the returned reference has the correct generic type
   * information so as to ensure type safety. For best results, use the standard method-chaining
   * idiom illustrated in the class documentation above, configuring a builder and building your
   * cache in a single statement. Failure to heed this advice can result in a
   * {@link ClassCastException} being thrown by a cache operation at some <i>undefined</i> point in
   * the future.
   * <p>
   * <b>Warning:</b> any exception thrown by {@code listener} will <i>not</i> be propagated to the
   * {@code Cache} user, only logged via a {@link Logger}.
   *
   * @param removalListener a listener instance that caches should notify each time an entry is
   *        removed
   * @param <K1> the key type of the listener
   * @param <V1> the value type of the listener
   * @return the cache builder reference that should be used instead of {@code this} for any
   *         remaining configuration and cache building
   * @throws IllegalStateException if a removal listener was already set
   * @throws NullPointerException if the specified removal listener is null
   */
  public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
      RemovalListener<? super K1, ? super V1> removalListener) {
    requireState(this.removalListener == null,
        "removal listener was already set to %s", this.removalListener);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    self.removalListener = requireNonNull(removalListener);
    return self;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Nullable <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener(boolean async) {
    RemovalListener<K1, V1> castedListener = (RemovalListener<K1, V1>) removalListener;
    return async && (castedListener != null)
        ? new AsyncRemovalListener(castedListener, getExecutor())
        : castedListener;
  }

  /**
   * Enables the accumulation of {@link CacheStats} during the operation of the cache. Without this
   * {@link Cache#stats} will return zero for all statistics. Note that recording statistics
   * requires bookkeeping to be performed with each operation, and thus imposes a performance
   * penalty on cache operation.
   *
   * @return this {@code Caffeine} instance (for chaining)
   */
  public Caffeine<K, V> recordStats() {
    requireState(this.statsCounterSupplier == null, "Statistics recording was already set");
    statsCounterSupplier = ENABLED_STATS_COUNTER_SUPPLIER;
    return this;
  }

  /**
   * Enables the accumulation of {@link CacheStats} during the operation of the cache. Without this
   * {@link Cache#stats} will return zero for all statistics. Note that recording statistics
   * requires bookkeeping to be performed with each operation, and thus imposes a performance
   * penalty on cache operation. Any exception thrown by the supplied {@link StatsCounter} will be
   * suppressed and logged.
   *
   * @param statsCounterSupplier a supplier instance that returns a new {@link StatsCounter}
   * @return this {@code Caffeine} instance (for chaining)
   */
  public Caffeine<K, V> recordStats(Supplier<? extends StatsCounter> statsCounterSupplier) {
    requireState(this.statsCounterSupplier == null, "Statistics recording was already set");
    requireNonNull(statsCounterSupplier);
    this.statsCounterSupplier = () -> StatsCounter.guardedStatsCounter(statsCounterSupplier.get());
    return this;
  }

  boolean isRecordingStats() {
    return (statsCounterSupplier != null);
  }

  Supplier<StatsCounter> getStatsCounterSupplier() {
    return (statsCounterSupplier == null)
        ? StatsCounter::disabledStatsCounter
        : statsCounterSupplier;
  }

  boolean isBounded() {
    return (maximumSize != UNSET_INT)
        || (maximumWeight != UNSET_INT)
        || (expireAfterAccessNanos != UNSET_INT)
        || (expireAfterWriteNanos != UNSET_INT)
        || (expiry != null)
        || (keyStrength != null)
        || (valueStrength != null);
  }

  /**
   * Builds a cache which does not automatically load values when keys are requested unless a
   * mapping function is provided. Note that multiple threads can concurrently load values for
   * distinct keys.
   * <p>
   * Consider {@link #build(CacheLoader)} instead, if it is feasible to implement a
   * {@code CacheLoader}.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   *
   * @param <K1> the key type of the cache
   * @param <V1> the value type of the cache
   * @return a cache having the requested features
   */
  @CheckReturnValue
  public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
    requireWeightWithWeigher();
    requireNonLoadingCache();

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return isBounded()
        ? new BoundedLocalCache.BoundedLocalManualCache<>(self)
        : new UnboundedLocalCache.UnboundedLocalManualCache<>(self);
  }

  /**
   * Builds a cache, which either returns an already-loaded value for a given key or atomically
   * computes or retrieves it using the supplied {@code CacheLoader}. If another thread is currently
   * loading the value for this key, simply waits for that thread to finish and returns its loaded
   * value. Note that multiple threads can concurrently load values for distinct keys.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   *
   * @param loader the cache loader used to obtain new values
   * @param <K1> the key type of the loader
   * @param <V1> the value type of the loader
   * @return a cache having the requested features
   */
  @CheckReturnValue
  public <K1 extends K, V1 extends V> LoadingCache<K1, V1> build(
      CacheLoader<? super K1, V1> loader) {
    requireWeightWithWeigher();

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return isBounded() || refreshAfterWrite()
        ? new BoundedLocalCache.BoundedLocalLoadingCache<>(self, loader)
        : new UnboundedLocalCache.UnboundedLocalLoadingCache<>(self, loader);
  }

  /**
   * Builds a cache which does not automatically load values when keys are requested unless a
   * mapping function is provided. The returned {@link CompletableFuture} may be already loaded or
   * currently computing the value for a given key. If the asynchronous computation fails or
   * computes a {@code null} value then the entry will be automatically removed. Note that multiple
   * threads can concurrently load values for distinct keys.
   * <p>
   * Consider {@link #buildAsync(CacheLoader)} or {@link #buildAsync(AsyncCacheLoader)} instead, if
   * it is feasible to implement an {@code CacheLoader} or {@code AsyncCacheLoader}.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   * <p>
   * This construction cannot be used with {@link #weakValues()}, {@link #softValues()}, or when
   * {@link #weakKeys()} are combined with {@link #evictionListener(RemovalListener)}.
   *
   * @param <K1> the key type of the cache
   * @param <V1> the value type of the cache
   * @return a cache having the requested features
   */
  @CheckReturnValue
  public <K1 extends K, V1 extends V> AsyncCache<K1, V1> buildAsync() {
    requireState(valueStrength == null, "Weak or soft values can not be combined with AsyncCache");
    requireState(isStrongKeys() || (evictionListener == null),
        "Weak keys cannot be combined eviction listener and with AsyncLoadingCache");
    requireWeightWithWeigher();
    requireNonLoadingCache();

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return isBounded()
        ? new BoundedLocalCache.BoundedLocalAsyncCache<>(self)
        : new UnboundedLocalCache.UnboundedLocalAsyncCache<>(self);
  }

  /**
   * Builds a cache, which either returns a {@link CompletableFuture} already loaded or currently
   * computing the value for a given key, or atomically computes the value asynchronously through a
   * supplied mapping function or the supplied {@code CacheLoader}. If the asynchronous computation
   * fails or computes a {@code null} value then the entry will be automatically removed. Note that
   * multiple threads can concurrently load values for distinct keys.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   * <p>
   * This construction cannot be used with {@link #weakValues()}, {@link #softValues()}, or when
   * {@link #weakKeys()} are combined with {@link #evictionListener(RemovalListener)}.
   *
   * @param loader the cache loader used to obtain new values
   * @param <K1> the key type of the loader
   * @param <V1> the value type of the loader
   * @return a cache having the requested features
   */
  @CheckReturnValue
  public <K1 extends K, V1 extends V> AsyncLoadingCache<K1, V1> buildAsync(
      CacheLoader<? super K1, V1> loader) {
    return buildAsync((AsyncCacheLoader<? super K1, V1>) loader);
  }

  /**
   * Builds a cache, which either returns a {@link CompletableFuture} already loaded or currently
   * computing the value for a given key, or atomically computes the value asynchronously through a
   * supplied mapping function or the supplied {@code AsyncCacheLoader}. If the asynchronous
   * computation fails or computes a {@code null} value then the entry will be automatically
   * removed. Note that multiple threads can concurrently load values for distinct keys.
   * <p>
   * This method does not alter the state of this {@code Caffeine} instance, so it can be invoked
   * again to create multiple independent caches.
   * <p>
   * This construction cannot be used with {@link #weakValues()}, {@link #softValues()}, or when
   * {@link #weakKeys()} are combined with {@link #evictionListener(RemovalListener)}.
   *
   * @param loader the cache loader used to obtain new values
   * @param <K1> the key type of the loader
   * @param <V1> the value type of the loader
   * @return a cache having the requested features
   */
  @CheckReturnValue
  public <K1 extends K, V1 extends V> AsyncLoadingCache<K1, V1> buildAsync(
      AsyncCacheLoader<? super K1, V1> loader) {
    requireState(valueStrength == null,
        "Weak or soft values can not be combined with AsyncLoadingCache");
    requireState(isStrongKeys() || (evictionListener == null),
        "Weak keys cannot be combined eviction listener and with AsyncLoadingCache");
    requireWeightWithWeigher();
    requireNonNull(loader);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return isBounded() || refreshAfterWrite()
        ? new BoundedLocalCache.BoundedLocalAsyncLoadingCache<K1, V1>(self, loader)
        : new UnboundedLocalCache.UnboundedLocalAsyncLoadingCache<K1, V1>(self, loader);
  }

  void requireNonLoadingCache() {
    requireState(refreshAfterWriteNanos == UNSET_INT, "refreshAfterWrite requires a LoadingCache");
  }

  void requireWeightWithWeigher() {
    if (weigher == null) {
      requireState(maximumWeight == UNSET_INT, "maximumWeight requires weigher");
    } else if (strictParsing) {
      requireState(maximumWeight != UNSET_INT, "weigher requires maximumWeight");
    } else if (maximumWeight == UNSET_INT) {
      logger.log(Level.WARNING, "ignoring weigher specified without maximumWeight");
    }
  }

  /**
   * Returns the number of nanoseconds of the given duration without throwing or overflowing.
   * <p>
   * Instead of throwing {@link ArithmeticException}, this method silently saturates to either
   * {@link Long#MAX_VALUE} or {@link Long#MIN_VALUE}. This behavior can be useful when decomposing
   * a duration in order to call a legacy API which requires a {@code long, TimeUnit} pair.
   */
  private static long saturatedToNanos(Duration duration) {
    // Using a try/catch seems lazy, but the catch block will rarely get invoked (except for
    // durations longer than approximately +/- 292 years).
    try {
      return duration.toNanos();
    } catch (ArithmeticException tooBig) {
      return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
  }

  /**
   * Returns a string representation for this Caffeine instance. The exact form of the returned
   * string is not specified.
   */
  @Override
  public String toString() {
    StringBuilder s = new StringBuilder(75);
    s.append(getClass().getSimpleName()).append('{');
    int baseLength = s.length();
    if (initialCapacity != UNSET_INT) {
      s.append("initialCapacity=").append(initialCapacity).append(", ");
    }
    if (maximumSize != UNSET_INT) {
      s.append("maximumSize=").append(maximumSize).append(", ");
    }
    if (maximumWeight != UNSET_INT) {
      s.append("maximumWeight=").append(maximumWeight).append(", ");
    }
    if (expireAfterWriteNanos != UNSET_INT) {
      s.append("expireAfterWrite=").append(expireAfterWriteNanos).append("ns, ");
    }
    if (expireAfterAccessNanos != UNSET_INT) {
      s.append("expireAfterAccess=").append(expireAfterAccessNanos).append("ns, ");
    }
    if (expiry != null) {
      s.append("expiry, ");
    }
    if (refreshAfterWriteNanos != UNSET_INT) {
      s.append("refreshAfterWriteNanos=").append(refreshAfterWriteNanos).append("ns, ");
    }
    if (keyStrength != null) {
      s.append("keyStrength=").append(keyStrength.toString().toLowerCase(US)).append(", ");
    }
    if (valueStrength != null) {
      s.append("valueStrength=").append(valueStrength.toString().toLowerCase(US)).append(", ");
    }
    if (evictionListener != null) {
      s.append("evictionListener, ");
    }
    if (removalListener != null) {
      s.append("removalListener, ");
    }
    if (s.length() > baseLength) {
      s.deleteCharAt(s.length() - 2);
    }
    return s.append('}').toString();
  }
}
