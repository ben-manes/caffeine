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

import static com.github.benmanes.caffeine.cache.Async.ASYNC_EXPIRY;
import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;
import static com.github.benmanes.caffeine.cache.Caffeine.requireArgument;
import static com.github.benmanes.caffeine.cache.LocalLoadingCache.newBulkMappingFunction;
import static com.github.benmanes.caffeine.cache.LocalLoadingCache.newMappingFunction;
import static com.github.benmanes.caffeine.cache.Node.PROBATION;
import static com.github.benmanes.caffeine.cache.Node.PROTECTED;
import static com.github.benmanes.caffeine.cache.Node.WINDOW;
import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Async.AsyncExpiry;
import com.github.benmanes.caffeine.cache.LinkedDeque.PeekingIterator;
import com.github.benmanes.caffeine.cache.References.InternalReference;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.google.errorprone.annotations.concurrent.GuardedBy;

/**
 * An in-memory cache implementation that supports full concurrency of retrievals, a high expected
 * concurrency for updates, and multiple ways to bound the cache.
 * <p>
 * This class is abstract and code generated subclasses provide the complete implementation for a
 * particular configuration. This is to ensure that only the fields and execution paths necessary
 * for a given configuration are used.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
@SuppressWarnings("deprecation")
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef<K, V>
    implements LocalCache<K, V> {

  /*
   * This class performs a best-effort bounding of a ConcurrentHashMap using a page-replacement
   * algorithm to determine which entries to evict when the capacity is exceeded.
   *
   * Concurrency:
   * ------------
   * The page replacement algorithms are kept eventually consistent with the map. An update to the
   * map and recording of reads may not be immediately reflected on the policy's data structures.
   * These structures are guarded by a lock and operations are applied in batches to avoid lock
   * contention. The penalty of applying the batches is spread across threads so that the amortized
   * cost is slightly higher than performing just the ConcurrentHashMap operation [1].
   *
   * A memento of the reads and writes that were performed on the map are recorded in buffers. These
   * buffers are drained at the first opportunity after a write or when a read buffer is full. The
   * reads are offered to a buffer that will reject additions if contended on or if it is full. Due
   * to the concurrent nature of the read and write operations a strict policy ordering is not
   * possible, but may be observably strict when single threaded. The buffers are drained
   * asynchronously to minimize the request latency and uses a state machine to determine when to
   * schedule this work on an executor.
   *
   * Due to a lack of a strict ordering guarantee, a task can be executed out-of-order, such as a
   * removal followed by its addition. The state of the entry is encoded using the key field to
   * avoid additional memory. An entry is "alive" if it is in both the hash table and the page
   * replacement policy. It is "retired" if it is not in the hash table and is pending removal from
   * the page replacement policy. Finally an entry transitions to the "dead" state when it is not in
   * the hash table nor the page replacement policy. Both the retired and dead states are
   * represented by a sentinel key that should not be used for map operations.
   *
   * Eviction:
   * ---------
   * Maximum size is implemented using the Window TinyLfu policy [2] due to its high hit rate, O(1)
   * time complexity, and small footprint. A new entry starts in the admission window and remains
   * there as long as it has high temporal locality (recency). Eventually an entry will slip from
   * the window into the main space. If the main space is already full, then a historic frequency
   * filter determines whether to evict the newly admitted entry or the victim entry chosen by the
   * eviction policy. This process ensures that the entries in the window were very recently used
   * and entries in the main space are accessed very frequently and are moderately recent. The
   * windowing allows the policy to have a high hit rate when entries exhibit bursty access pattern
   * while the filter ensures that popular items are retained. The admission window uses LRU and
   * the main space uses Segmented LRU.
   *
   * The optimal size of the window vs main spaces is workload dependent [3]. A large admission
   * window is favored by recency-biased workloads while a small one favors frequency-biased
   * workloads. When the window is too small then recent arrivals are prematurely evicted, but when
   * too large then they pollute the cache and force the eviction of more popular entries. The
   * configuration is dynamically determined using hill climbing to walk the hit rate curve. This is
   * done by sampling the hit rate and adjusting the window size in the direction that is improving
   * (making positive or negative steps). At each interval the step size is decreased until the
   * climber converges at the optimal setting. The process is restarted when the hit rate changes
   * over a threshold, indicating that the workload altered and a new setting may be required.
   *
   * The historic usage is retained in a compact popularity sketch, which uses hashing to
   * probabilistically estimate an item's frequency. This exposes a flaw where an adversary could
   * use hash flooding [4] to artificially raise the frequency of the main space's victim and cause
   * all candidates to be rejected. In the worst case, by exploiting hash collisions an attacker
   * could cause the cache to never hit and hold only worthless items, resulting in a
   * denial-of-service attack against the underlying resource. This is protected against by
   * introducing jitter so that candidates which are at least moderately popular have a small,
   * random chance of being admitted. This causes the victim to be evicted, but in a way that
   * marginally impacts the hit rate.
   *
   * Expiration:
   * -----------
   * Expiration is implemented in O(1) time complexity. The time-to-idle policy uses an access-order
   * queue, the time-to-live policy uses a write-order queue, and variable expiration uses a
   * hierarchical timer wheel [5]. The queuing policies allow for peeking at the oldest entry to
   * see if it has expired and, if it has not, then the younger entries must not have too. If a
   * maximum size is set then expiration will share the queues so that the per-entry footprint is
   * minimized. The timer wheel based policy uses hashing and cascading in a manner that amortizes
   * the penalty of sorting to achieve a similar algorithmic cost.
   *
   * The expiration updates are applied in a best effort fashion. The reordering of variable or
   * access-order expiration may be discarded by the read buffer if full or contended on. Similarly
   * the reordering of write expiration may be ignored for an entry if the last update was within a
   * short time window in order to avoid overwhelming the write buffer.
   *
   * [1] BP-Wrapper: A Framework Making Any Replacement Algorithms (Almost) Lock Contention Free
   * http://web.cse.ohio-state.edu/hpcs/WWW/HTML/publications/papers/TR-09-1.pdf
   * [2] TinyLFU: A Highly Efficient Cache Admission Policy
   * https://dl.acm.org/citation.cfm?id=3149371
   * [3] Adaptive Software Cache Management
   * https://dl.acm.org/citation.cfm?id=3274816
   * [4] Denial of Service via Algorithmic Complexity Attack
   * https://www.usenix.org/legacy/events/sec03/tech/full_papers/crosby/crosby.pdf
   * [5] Hashed and Hierarchical Timing Wheels
   * http://www.cs.columbia.edu/~nahum/w6998/papers/ton97-timing-wheels.pdf
   */

  static final Logger logger = System.getLogger(BoundedLocalCache.class.getName());

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();
  /** The initial capacity of the write buffer. */
  static final int WRITE_BUFFER_MIN = 4;
  /** The maximum capacity of the write buffer. */
  static final int WRITE_BUFFER_MAX = 128 * ceilingPowerOfTwo(NCPU);
  /** The number of attempts to insert into the write buffer before yielding. */
  static final int WRITE_BUFFER_RETRIES = 100;
  /** The maximum weighted capacity of the map. */
  static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;
  /** The initial percent of the maximum weighted capacity dedicated to the main space. */
  static final double PERCENT_MAIN = 0.99d;
  /** The percent of the maximum weighted capacity dedicated to the main's protected space. */
  static final double PERCENT_MAIN_PROTECTED = 0.80d;
  /** The difference in hit rates that restarts the climber. */
  static final double HILL_CLIMBER_RESTART_THRESHOLD = 0.05d;
  /** The percent of the total size to adapt the window by. */
  static final double HILL_CLIMBER_STEP_PERCENT = 0.0625d;
  /** The rate to decrease the step size to adapt by. */
  static final double HILL_CLIMBER_STEP_DECAY_RATE = 0.98d;
  /** The maximum number of entries that can be transfered between queues. */
  static final int QUEUE_TRANSFER_THRESHOLD = 1_000;
  /** The maximum time window between entry updates before the expiration must be reordered. */
  static final long EXPIRE_WRITE_TOLERANCE = TimeUnit.SECONDS.toNanos(1);
  /** The maximum duration before an entry expires. */
  static final long MAXIMUM_EXPIRY = (Long.MAX_VALUE >> 1); // 150 years
  /** The handle for the in-flight refresh operations. */
  static final VarHandle REFRESHES;

  final @Nullable RemovalListener<K, V> evictionListener;
  final @Nullable CacheLoader<K, V> cacheLoader;

  final ConcurrentHashMap<Object, Node<K, V>> data;
  final PerformCleanupTask drainBuffersTask;
  final Consumer<Node<K, V>> accessPolicy;
  final Buffer<Node<K, V>> readBuffer;
  final NodeFactory<K, V> nodeFactory;
  final ReentrantLock evictionLock;
  final Weigher<K, V> weigher;
  final Executor executor;
  final boolean isAsync;

  @Nullable Set<K> keySet;
  @Nullable Collection<V> values;
  @Nullable Set<Entry<K, V>> entrySet;
  @Nullable volatile ConcurrentMap<Object, CompletableFuture<?>> refreshes;

  /** Creates an instance based on the builder's configuration. */
  protected BoundedLocalCache(Caffeine<K, V> builder,
      @Nullable CacheLoader<K, V> cacheLoader, boolean isAsync) {
    this.isAsync = isAsync;
    this.cacheLoader = cacheLoader;
    executor = builder.getExecutor();
    evictionLock = new ReentrantLock();
    weigher = builder.getWeigher(isAsync);
    drainBuffersTask = new PerformCleanupTask(this);
    nodeFactory = NodeFactory.newFactory(builder, isAsync);
    evictionListener = builder.getEvictionListener(isAsync);
    data = new ConcurrentHashMap<>(builder.getInitialCapacity());
    readBuffer = evicts() || collectKeys() || collectValues() || expiresAfterAccess()
        ? new BoundedBuffer<>()
        : Buffer.disabled();
    accessPolicy = (evicts() || expiresAfterAccess()) ? this::onAccess : e -> {};

    if (evicts()) {
      setMaximumSize(builder.getMaximum());
    }
  }

  static {
    try {
      REFRESHES = MethodHandles.lookup()
          .findVarHandle(BoundedLocalCache.class, "refreshes", ConcurrentMap.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /* --------------- Shared --------------- */

  /** Returns if the node's value is currently being computed, asynchronously. */
  final boolean isComputingAsync(Node<?, ?> node) {
    return isAsync && !Async.isReady((CompletableFuture<?>) node.getValue());
  }

  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderWindowDeque() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderProbationDeque() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderProtectedDeque() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected WriteOrderDeque<Node<K, V>> writeOrderDeque() {
    throw new UnsupportedOperationException();
  }

  /** If the page replacement policy buffers writes. */
  protected boolean buffersWrites() {
    return false;
  }

  protected MpscGrowableArrayQueue<Runnable> writeBuffer() {
    throw new UnsupportedOperationException();
  }

  @Override
  public final Executor executor() {
    return executor;
  }

  @Override
  @SuppressWarnings("NullAway")
  public ConcurrentMap<Object, CompletableFuture<?>> refreshes() {
    var pending = refreshes;
    if (pending == null) {
      pending = new ConcurrentHashMap<>();
      if (!REFRESHES.compareAndSet(this, null, pending)) {
        pending = refreshes;
      }
    }
    return pending;
  }

  /** Invalidate the in-flight refresh. */
  void discardRefresh(Object keyReference) {
    var pending = refreshes;
    if (pending != null) {
      pending.remove(keyReference);
    }
  }

  @Override
  public Object referenceKey(K key) {
    return nodeFactory.newLookupKey(key);
  }

  /* --------------- Stats Support --------------- */

  @Override
  public boolean isRecordingStats() {
    return false;
  }

  @Override
  public StatsCounter statsCounter() {
    return StatsCounter.disabledStatsCounter();
  }

  @Override
  public Ticker statsTicker() {
    return Ticker.disabledTicker();
  }

  /* --------------- Removal Listener Support --------------- */

  @SuppressWarnings("NullAway")
  protected RemovalListener<K, V> removalListener() {
    return null;
  }

  protected boolean hasRemovalListener() {
    return false;
  }

  @Override
  public void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    if (!hasRemovalListener()) {
      return;
    }
    Runnable task = () -> {
      try {
        removalListener().onRemoval(key, value, cause);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", t);
      }
    };
    try {
      executor.execute(task);
    } catch (Throwable t) {
      logger.log(Level.ERROR, "Exception thrown when submitting removal listener", t);
      task.run();
    }
  }

  /* --------------- Eviction Listener Support --------------- */

  public void notifyEviction(@Nullable K key, @Nullable V value, RemovalCause cause) {
    if (evictionListener == null) {
      return;
    }
    try {
      evictionListener.onRemoval(key, value, cause);
    } catch (Throwable t) {
      logger.log(Level.WARNING, "Exception thrown by eviction listener", t);
    }
  }

  /* --------------- Reference Support --------------- */

  /** Returns if the keys are weak reference garbage collected. */
  protected boolean collectKeys() {
    return false;
  }

  /** Returns if the values are weak or soft reference garbage collected. */
  protected boolean collectValues() {
    return false;
  }

  @SuppressWarnings("NullAway")
  protected ReferenceQueue<K> keyReferenceQueue() {
    return null;
  }

  @SuppressWarnings("NullAway")
  protected ReferenceQueue<V> valueReferenceQueue() {
    return null;
  }

  /* --------------- Expiration Support --------------- */

  /** Returns the {@link Pacer} used to schedule the maintenance task. */
  protected @Nullable Pacer pacer() {
    return null;
  }

  /** Returns if the cache expires entries after a variable time threshold. */
  protected boolean expiresVariable() {
    return false;
  }

  /** Returns if the cache expires entries after an access time threshold. */
  protected boolean expiresAfterAccess() {
    return false;
  }

  /** Returns how long after the last access to an entry the map will retain that entry. */
  protected long expiresAfterAccessNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setExpiresAfterAccessNanos(long expireAfterAccessNanos) {
    throw new UnsupportedOperationException();
  }

  /** Returns if the cache expires entries after an write time threshold. */
  protected boolean expiresAfterWrite() {
    return false;
  }

  /** Returns how long after the last write to an entry the map will retain that entry. */
  protected long expiresAfterWriteNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setExpiresAfterWriteNanos(long expireAfterWriteNanos) {
    throw new UnsupportedOperationException();
  }

  /** Returns if the cache refreshes entries after an write time threshold. */
  protected boolean refreshAfterWrite() {
    return false;
  }

  /** Returns how long after the last write an entry becomes a candidate for refresh. */
  protected long refreshAfterWriteNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setRefreshAfterWriteNanos(long refreshAfterWriteNanos) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasWriteTime() {
    return expiresAfterWrite() || refreshAfterWrite();
  }

  @Override
  @SuppressWarnings("NullAway")
  public Expiry<K, V> expiry() {
    return null;
  }

  @Override
  public Ticker expirationTicker() {
    return Ticker.disabledTicker();
  }

  protected TimerWheel<K, V> timerWheel() {
    throw new UnsupportedOperationException();
  }

  /* --------------- Eviction Support --------------- */

  /** Returns if the cache evicts entries due to a maximum size or weight threshold. */
  protected boolean evicts() {
    return false;
  }

  /** Returns if entries may be assigned different weights. */
  protected boolean isWeighted() {
    return (weigher != Weigher.singletonWeigher());
  }

  protected FrequencySketch<K> frequencySketch() {
    throw new UnsupportedOperationException();
  }

  /** Returns if an access to an entry can skip notifying the eviction policy. */
  protected boolean fastpath() {
    return false;
  }

  /** Returns the maximum weighted size. */
  protected long maximum() {
    throw new UnsupportedOperationException();
  }

  /** Returns the maximum weighted size of the window space. */
  protected long windowMaximum() {
    throw new UnsupportedOperationException();
  }

  /** Returns the maximum weighted size of the main's protected space. */
  protected long mainProtectedMaximum() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setWindowMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMainProtectedMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  /** Returns the combined weight of the values in the cache (may be negative). */
  protected long weightedSize() {
    throw new UnsupportedOperationException();
  }

  /** Returns the uncorrected combined weight of the values in the window space. */
  protected long windowWeightedSize() {
    throw new UnsupportedOperationException();
  }

  /** Returns the uncorrected combined weight of the values in the main's protected space. */
  protected long mainProtectedWeightedSize() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setWindowWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMainProtectedWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  protected int hitsInSample() {
    throw new UnsupportedOperationException();
  }

  protected int missesInSample() {
    throw new UnsupportedOperationException();
  }

  protected int sampleCount() {
    throw new UnsupportedOperationException();
  }

  protected double stepSize() {
    throw new UnsupportedOperationException();
  }

  protected double previousSampleHitRate() {
    throw new UnsupportedOperationException();
  }

  protected long adjustment() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setHitsInSample(int hitCount) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setMissesInSample(int missCount) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setSampleCount(int sampleCount) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setStepSize(double stepSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setPreviousSampleHitRate(double hitRate) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected void setAdjustment(long amount) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum weighted size of the cache. The caller may need to perform a maintenance cycle
   * to eagerly evicts entries until the cache shrinks to the appropriate size.
   */
  @GuardedBy("evictionLock")
  void setMaximumSize(long maximum) {
    requireArgument(maximum >= 0, "maximum must not be negative");
    if (maximum == maximum()) {
      return;
    }

    long max = Math.min(maximum, MAXIMUM_CAPACITY);
    long window = max - (long) (PERCENT_MAIN * max);
    long mainProtected = (long) (PERCENT_MAIN_PROTECTED * (max - window));

    setMaximum(max);
    setWindowMaximum(window);
    setMainProtectedMaximum(mainProtected);

    setHitsInSample(0);
    setMissesInSample(0);
    setStepSize(-HILL_CLIMBER_STEP_PERCENT * max);

    if ((frequencySketch() != null) && !isWeighted() && (weightedSize() >= (max >>> 1))) {
      // Lazily initialize when close to the maximum size
      frequencySketch().ensureCapacity(max);
    }
  }

  /** Evicts entries if the cache exceeds the maximum. */
  @GuardedBy("evictionLock")
  void evictEntries() {
    if (!evicts()) {
      return;
    }
    int candidates = evictFromWindow();
    evictFromMain(candidates);
  }

  /**
   * Evicts entries from the window space into the main space while the window size exceeds a
   * maximum.
   *
   * @return the number of candidate entries evicted from the window space
   */
  @GuardedBy("evictionLock")
  int evictFromWindow() {
    int candidates = 0;
    Node<K, V> node = accessOrderWindowDeque().peek();
    while (windowWeightedSize() > windowMaximum()) {
      // The pending operations will adjust the size to reflect the correct weight
      if (node == null) {
        break;
      }

      Node<K, V> next = node.getNextInAccessOrder();
      if (node.getPolicyWeight() != 0) {
        node.makeMainProbation();
        accessOrderWindowDeque().remove(node);
        accessOrderProbationDeque().add(node);
        candidates++;

        setWindowWeightedSize(windowWeightedSize() - node.getPolicyWeight());
      }
      node = next;
    }

    return candidates;
  }

  /**
   * Evicts entries from the main space if the cache exceeds the maximum capacity. The main space
   * determines whether admitting an entry (coming from the window space) is preferable to retaining
   * the eviction policy's victim. This decision is made using a frequency filter so that the
   * least frequently used entry is removed.
   *
   * The window space's candidates were previously placed in the MRU position and the eviction
   * policy's victim is at the LRU position. The two ends of the queue are evaluated while an
   * eviction is required. The number of remaining candidates is provided and decremented on
   * eviction, so that when there are no more candidates the victim is evicted.
   *
   * @param candidates the number of candidate entries evicted from the window space
   */
  @GuardedBy("evictionLock")
  void evictFromMain(int candidates) {
    int victimQueue = PROBATION;
    Node<K, V> victim = accessOrderProbationDeque().peekFirst();
    Node<K, V> candidate = accessOrderProbationDeque().peekLast();
    while (weightedSize() > maximum()) {
      // Search the admission window for additional candidates
      if (candidates == 0) {
        candidate = accessOrderWindowDeque().peekLast();
      }

      // Try evicting from the protected and window queues
      if ((candidate == null) && (victim == null)) {
        if (victimQueue == PROBATION) {
          victim = accessOrderProtectedDeque().peekFirst();
          victimQueue = PROTECTED;
          continue;
        } else if (victimQueue == PROTECTED) {
          victim = accessOrderWindowDeque().peekFirst();
          victimQueue = WINDOW;
          continue;
        }

        // The pending operations will adjust the size to reflect the correct weight
        break;
      }

      // Skip over entries with zero weight
      if ((victim != null) && (victim.getPolicyWeight() == 0)) {
        victim = victim.getNextInAccessOrder();
        continue;
      } else if ((candidate != null) && (candidate.getPolicyWeight() == 0)) {
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        candidates--;
        continue;
      }

      // Evict immediately if only one of the entries is present
      if (victim == null) {
        @SuppressWarnings("NullAway")
        Node<K, V> previous = candidate.getPreviousInAccessOrder();
        Node<K, V> evict = candidate;
        candidate = previous;
        candidates--;
        evictEntry(evict, RemovalCause.SIZE, 0L);
        continue;
      } else if (candidate == null) {
        Node<K, V> evict = victim;
        victim = victim.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.SIZE, 0L);
        continue;
      }

      // Evict immediately if an entry was collected
      K victimKey = victim.getKey();
      K candidateKey = candidate.getKey();
      if (victimKey == null) {
        Node<K, V> evict = victim;
        victim = victim.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.COLLECTED, 0L);
        continue;
      } else if (candidateKey == null) {
        Node<K, V> evict = candidate;
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        candidates--;
        evictEntry(evict, RemovalCause.COLLECTED, 0L);
        continue;
      }

      // Evict immediately if the candidate's weight exceeds the maximum
      if (candidate.getPolicyWeight() > maximum()) {
        Node<K, V> evict = candidate;
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        candidates--;
        evictEntry(evict, RemovalCause.SIZE, 0L);
        continue;
      }

      // Evict the entry with the lowest frequency
      candidates--;
      if (admit(candidateKey, victimKey)) {
        Node<K, V> evict = victim;
        victim = victim.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.SIZE, 0L);
        candidate = candidate.getPreviousInAccessOrder();
      } else {
        Node<K, V> evict = candidate;
        candidate = (candidates > 0)
            ? candidate.getPreviousInAccessOrder()
            : candidate.getNextInAccessOrder();
        evictEntry(evict, RemovalCause.SIZE, 0L);
      }
    }
  }

  /**
   * Determines if the candidate should be accepted into the main space, as determined by its
   * frequency relative to the victim. A small amount of randomness is used to protect against hash
   * collision attacks, where the victim's frequency is artificially raised so that no new entries
   * are admitted.
   *
   * @param candidateKey the key for the entry being proposed for long term retention
   * @param victimKey the key for the entry chosen by the eviction policy for replacement
   * @return if the candidate should be admitted and the victim ejected
   */
  @GuardedBy("evictionLock")
  boolean admit(K candidateKey, K victimKey) {
    int victimFreq = frequencySketch().frequency(victimKey);
    int candidateFreq = frequencySketch().frequency(candidateKey);
    if (candidateFreq > victimFreq) {
      return true;
    } else if (candidateFreq <= 5) {
      // The maximum frequency is 15 and halved to 7 after a reset to age the history. An attack
      // exploits that a hot candidate is rejected in favor of a hot victim. The threshold of a warm
      // candidate reduces the number of random acceptances to minimize the impact on the hit rate.
      return false;
    }
    int random = ThreadLocalRandom.current().nextInt();
    return ((random & 127) == 0);
  }

  /** Expires entries that have expired by access, write, or variable. */
  @GuardedBy("evictionLock")
  void expireEntries() {
    long now = expirationTicker().read();
    expireAfterAccessEntries(now);
    expireAfterWriteEntries(now);
    expireVariableEntries(now);

    Pacer pacer = pacer();
    if (pacer != null) {
      long delay = getExpirationDelay(now);
      if (delay != Long.MAX_VALUE) {
        pacer.schedule(executor, drainBuffersTask, now, delay);
      }
    }
  }

  /** Expires entries in the access-order queue. */
  @GuardedBy("evictionLock")
  void expireAfterAccessEntries(long now) {
    if (!expiresAfterAccess()) {
      return;
    }

    expireAfterAccessEntries(accessOrderWindowDeque(), now);
    if (evicts()) {
      expireAfterAccessEntries(accessOrderProbationDeque(), now);
      expireAfterAccessEntries(accessOrderProtectedDeque(), now);
    }
  }

  /** Expires entries in an access-order queue. */
  @GuardedBy("evictionLock")
  void expireAfterAccessEntries(AccessOrderDeque<Node<K, V>> accessOrderDeque, long now) {
    long duration = expiresAfterAccessNanos();
    for (;;) {
      Node<K, V> node = accessOrderDeque.peekFirst();
      if ((node == null) || ((now - node.getAccessTime()) < duration)) {
        return;
      }
      evictEntry(node, RemovalCause.EXPIRED, now);
    }
  }

  /** Expires entries on the write-order queue. */
  @GuardedBy("evictionLock")
  void expireAfterWriteEntries(long now) {
    if (!expiresAfterWrite()) {
      return;
    }
    long duration = expiresAfterWriteNanos();
    for (;;) {
      final Node<K, V> node = writeOrderDeque().peekFirst();
      if ((node == null) || ((now - node.getWriteTime()) < duration)) {
        break;
      }
      evictEntry(node, RemovalCause.EXPIRED, now);
    }
  }

  /** Expires entries in the timer wheel. */
  @GuardedBy("evictionLock")
  void expireVariableEntries(long now) {
    if (expiresVariable()) {
      timerWheel().advance(now);
    }
  }

  /** Returns the duration until the next item expires, or {@link Long.MAX_VALUE} if none. */
  @GuardedBy("evictionLock")
  private long getExpirationDelay(long now) {
    long delay = Long.MAX_VALUE;
    if (expiresAfterAccess()) {
      Node<K, V> node = accessOrderWindowDeque().peekFirst();
      if (node != null) {
        delay = Math.min(delay, expiresAfterAccessNanos() - (now - node.getAccessTime()));
      }
      if (evicts()) {
        node = accessOrderProbationDeque().peekFirst();
        if (node != null) {
          delay = Math.min(delay, expiresAfterAccessNanos() - (now - node.getAccessTime()));
        }
        node = accessOrderProtectedDeque().peekFirst();
        if (node != null) {
          delay = Math.min(delay, expiresAfterAccessNanos() - (now - node.getAccessTime()));
        }
      }
    }
    if (expiresAfterWrite()) {
      Node<K, V> node = writeOrderDeque().peekFirst();
      if (node != null) {
        delay = Math.min(delay, expiresAfterWriteNanos() - (now - node.getWriteTime()));
      }
    }
    if (expiresVariable()) {
      delay = Math.min(delay, timerWheel().getExpirationDelay());
    }
    return delay;
  }

  /** Returns if the entry has expired. */
  @SuppressWarnings("ShortCircuitBoolean")
  boolean hasExpired(Node<K, V> node, long now) {
    return (expiresAfterAccess() && (now - node.getAccessTime() >= expiresAfterAccessNanos()))
        | (expiresAfterWrite() && (now - node.getWriteTime() >= expiresAfterWriteNanos()))
        | (expiresVariable() && (now - node.getVariableTime() >= 0));
  }

  /**
   * Attempts to evict the entry based on the given removal cause. A removal due to expiration or
   * size may be ignored if the entry was updated and is no longer eligible for eviction.
   *
   * @param node the entry to evict
   * @param cause the reason to evict
   * @param now the current time, used only if expiring
   * @return if the entry was evicted
   */
  @GuardedBy("evictionLock")
  @SuppressWarnings({"PMD.CollapsibleIfStatements", "GuardedByChecker", "NullAway"})
  boolean evictEntry(Node<K, V> node, RemovalCause cause, long now) {
    K key = node.getKey();
    @SuppressWarnings("unchecked")
    V[] value = (V[]) new Object[1];
    boolean[] removed = new boolean[1];
    boolean[] resurrect = new boolean[1];
    RemovalCause[] actualCause = new RemovalCause[1];
    Object keyReference = node.getKeyReference();

    data.computeIfPresent(keyReference, (k, n) -> {
      if (n != node) {
        return n;
      }
      synchronized (n) {
        value[0] = n.getValue();

        actualCause[0] = (key == null) || (value[0] == null) ? RemovalCause.COLLECTED : cause;
        if (actualCause[0] == RemovalCause.EXPIRED) {
          boolean expired = false;
          if (expiresAfterAccess()) {
            expired |= ((now - n.getAccessTime()) >= expiresAfterAccessNanos());
          }
          if (expiresAfterWrite()) {
            expired |= ((now - n.getWriteTime()) >= expiresAfterWriteNanos());
          }
          if (expiresVariable()) {
            expired |= (n.getVariableTime() <= now);
          }
          if (!expired) {
            resurrect[0] = true;
            return n;
          }
        } else if (actualCause[0] == RemovalCause.SIZE) {
          int weight = node.getWeight();
          if (weight == 0) {
            resurrect[0] = true;
            return n;
          }
        }

        notifyEviction(key, value[0], actualCause[0]);
        makeDead(n);
      }
      discardRefresh(keyReference);
      removed[0] = true;
      return null;
    });

    // The entry is no longer eligible for eviction
    if (resurrect[0]) {
      return false;
    }

    // If the eviction fails due to a concurrent removal of the victim, that removal may cancel out
    // the addition that triggered this eviction. The victim is eagerly unlinked before the removal
    // task so that if an eviction is still required then a new victim will be chosen for removal.
    if (node.inWindow() && (evicts() || expiresAfterAccess())) {
      accessOrderWindowDeque().remove(node);
    } else if (evicts()) {
      if (node.inMainProbation()) {
        accessOrderProbationDeque().remove(node);
      } else {
        accessOrderProtectedDeque().remove(node);
      }
    }
    if (expiresAfterWrite()) {
      writeOrderDeque().remove(node);
    } else if (expiresVariable()) {
      timerWheel().deschedule(node);
    }

    if (removed[0]) {
      statsCounter().recordEviction(node.getWeight(), actualCause[0]);

      // Notify the listener only if the entry was evicted. This must be performed as the last
      // step during eviction to safe guard against the executor rejecting the notification task.
      notifyRemoval(key, value[0], actualCause[0]);
    } else {
      // Eagerly decrement the size to potentially avoid an additional eviction, rather than wait
      // for the removal task to do it on the next maintenance cycle.
      makeDead(node);
    }

    return true;
  }

  /** Adapts the eviction policy to towards the optimal recency / frequency configuration. */
  @GuardedBy("evictionLock")
  void climb() {
    if (!evicts()) {
      return;
    }

    determineAdjustment();
    demoteFromMainProtected();
    long amount = adjustment();
    if (amount == 0) {
      return;
    } else if (amount > 0) {
      increaseWindow();
    } else {
      decreaseWindow();
    }
  }

  /** Calculates the amount to adapt the window by and sets {@link #adjustment()} accordingly. */
  @GuardedBy("evictionLock")
  void determineAdjustment() {
    if (frequencySketch().isNotInitialized()) {
      setPreviousSampleHitRate(0.0);
      setMissesInSample(0);
      setHitsInSample(0);
      return;
    }

    int requestCount = hitsInSample() + missesInSample();
    if (requestCount < frequencySketch().sampleSize) {
      return;
    }

    double hitRate = (double) hitsInSample() / requestCount;
    double hitRateChange = hitRate - previousSampleHitRate();
    double amount = (hitRateChange >= 0) ? stepSize() : -stepSize();
    double nextStepSize = (Math.abs(hitRateChange) >= HILL_CLIMBER_RESTART_THRESHOLD)
        ? HILL_CLIMBER_STEP_PERCENT * maximum() * (amount >= 0 ? 1 : -1)
        : HILL_CLIMBER_STEP_DECAY_RATE * amount;
    setPreviousSampleHitRate(hitRate);
    setAdjustment((long) amount);
    setStepSize(nextStepSize);
    setMissesInSample(0);
    setHitsInSample(0);
  }

  /**
   * Increases the size of the admission window by shrinking the portion allocated to the main
   * space. As the main space is partitioned into probation and protected regions (80% / 20%), for
   * simplicity only the protected is reduced. If the regions exceed their maximums, this may cause
   * protected items to be demoted to the probation region and probation items to be demoted to the
   * admission window.
   */
  @GuardedBy("evictionLock")
  void increaseWindow() {
    if (mainProtectedMaximum() == 0) {
      return;
    }

    long quota = Math.min(adjustment(), mainProtectedMaximum());
    setMainProtectedMaximum(mainProtectedMaximum() - quota);
    setWindowMaximum(windowMaximum() + quota);
    demoteFromMainProtected();

    for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
      Node<K, V> candidate = accessOrderProbationDeque().peek();
      boolean probation = true;
      if ((candidate == null) || (quota < candidate.getPolicyWeight())) {
        candidate = accessOrderProtectedDeque().peek();
        probation = false;
      }
      if (candidate == null) {
        break;
      }

      int weight = candidate.getPolicyWeight();
      if (quota < weight) {
        break;
      }

      quota -= weight;
      if (probation) {
        accessOrderProbationDeque().remove(candidate);
      } else {
        setMainProtectedWeightedSize(mainProtectedWeightedSize() - weight);
        accessOrderProtectedDeque().remove(candidate);
      }
      setWindowWeightedSize(windowWeightedSize() + weight);
      accessOrderWindowDeque().add(candidate);
      candidate.makeWindow();
    }

    setMainProtectedMaximum(mainProtectedMaximum() + quota);
    setWindowMaximum(windowMaximum() - quota);
    setAdjustment(quota);
  }

  /** Decreases the size of the admission window and increases the main's protected region. */
  @GuardedBy("evictionLock")
  void decreaseWindow() {
    if (windowMaximum() <= 1) {
      return;
    }

    long quota = Math.min(-adjustment(), Math.max(0, windowMaximum() - 1));
    setMainProtectedMaximum(mainProtectedMaximum() + quota);
    setWindowMaximum(windowMaximum() - quota);

    for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
      Node<K, V> candidate = accessOrderWindowDeque().peek();
      if (candidate == null) {
        break;
      }

      int weight = candidate.getPolicyWeight();
      if (quota < weight) {
        break;
      }

      quota -= weight;
      setWindowWeightedSize(windowWeightedSize() - weight);
      accessOrderWindowDeque().remove(candidate);
      accessOrderProbationDeque().add(candidate);
      candidate.makeMainProbation();
    }

    setMainProtectedMaximum(mainProtectedMaximum() - quota);
    setWindowMaximum(windowMaximum() + quota);
    setAdjustment(-quota);
  }

  /** Transfers the nodes from the protected to the probation region if it exceeds the maximum. */
  @GuardedBy("evictionLock")
  void demoteFromMainProtected() {
    long mainProtectedMaximum = mainProtectedMaximum();
    long mainProtectedWeightedSize = mainProtectedWeightedSize();
    if (mainProtectedWeightedSize <= mainProtectedMaximum) {
      return;
    }

    for (int i = 0; i < QUEUE_TRANSFER_THRESHOLD; i++) {
      if (mainProtectedWeightedSize <= mainProtectedMaximum) {
        break;
      }

      Node<K, V> demoted = accessOrderProtectedDeque().poll();
      if (demoted == null) {
        break;
      }
      demoted.makeMainProbation();
      accessOrderProbationDeque().add(demoted);
      mainProtectedWeightedSize -= demoted.getPolicyWeight();
    }
    setMainProtectedWeightedSize(mainProtectedWeightedSize);
  }

  /**
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   * @param now the current time, in nanoseconds
   * @param recordHit if the hit count should be incremented
   */
  void afterRead(Node<K, V> node, long now, boolean recordHit) {
    if (recordHit) {
      statsCounter().recordHits(1);
    }

    boolean delayable = skipReadBuffer() || (readBuffer.offer(node) != Buffer.FULL);
    if (shouldDrainBuffers(delayable)) {
      scheduleDrainBuffers();
    }
    refreshIfNeeded(node, now);
  }

  /** Returns if the cache should bypass the read buffer. */
  boolean skipReadBuffer() {
    return fastpath() && frequencySketch().isNotInitialized();
  }

  /**
   * Asynchronously refreshes the entry if eligible.
   *
   * @param node the entry in the cache to refresh
   * @param now the current time, in nanoseconds
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  void refreshIfNeeded(Node<K, V> node, long now) {
    if (!refreshAfterWrite()) {
      return;
    }

    K key;
    V oldValue;
    long writeTime = node.getWriteTime();
    Object keyReference = node.getKeyReference();
    if (((now - writeTime) > refreshAfterWriteNanos()) && (keyReference != null)
        && ((key = node.getKey()) != null) && ((oldValue = node.getValue()) != null)
        && !refreshes().containsKey(keyReference)) {
      long[] startTime = new long[1];
      @SuppressWarnings({"unchecked", "rawtypes"})
      CompletableFuture<? extends V>[] refreshFuture = new CompletableFuture[1];
      refreshes().computeIfAbsent(keyReference, k -> {
        try {
          startTime[0] = statsTicker().read();
          if (isAsync) {
            @SuppressWarnings("unchecked")
            CompletableFuture<V> future = (CompletableFuture<V>) oldValue;
            if (Async.isReady(future)) {
              @SuppressWarnings("NullAway")
              var refresh = cacheLoader.asyncReload(key, future.join(), executor);
              refreshFuture[0] = refresh;
            } else {
              // no-op if load is pending
              return future;
            }
          } else {
            @SuppressWarnings("NullAway")
            var refresh = cacheLoader.asyncReload(key, oldValue, executor);
            refreshFuture[0] = refresh;
          }
          return refreshFuture[0];
        } catch (RuntimeException e) {
          throw e;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CompletionException(e);
        } catch (Exception e) {
          throw new CompletionException(e);
        }
      });

      if (refreshFuture[0] != null) {
        refreshFuture[0].whenComplete((newValue, error) -> {
          long loadTime = statsTicker().read() - startTime[0];
          if (error != null) {
            logger.log(Level.WARNING, "Exception thrown during refresh", error);
            refreshes().remove(keyReference, refreshFuture[0]);
            statsCounter().recordLoadFailure(loadTime);
            return;
          }

          @SuppressWarnings("unchecked")
          V value = (isAsync && (newValue != null)) ? (V) refreshFuture[0] : newValue;

          boolean[] discard = new boolean[1];
          compute(key, (k, currentValue) -> {
            if (currentValue == null) {
              if (value == null) {
                return null;
              } else if (refreshes().get(key) == refreshFuture[0]) {
                return value;
              }
            } else if ((currentValue == oldValue) && (node.getWriteTime() == writeTime)) {
              return value;
            }
            discard[0] = true;
            return currentValue;
          }, expiry(), /* recordMiss */ false,
              /* recordLoad */ false, /* recordLoadFailure */ true);

          if (discard[0]) {
            notifyRemoval(key, value, RemovalCause.REPLACED);
          }
          if (newValue == null) {
            statsCounter().recordLoadFailure(loadTime);
          } else {
            statsCounter().recordLoadSuccess(loadTime);
          }

          refreshes().remove(keyReference, refreshFuture[0]);
        });
      }
    }
  }

  /**
   * Returns the expiration time for the entry after being created.
   *
   * @param key the key of the entry that was created
   * @param value the value of the entry that was created
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   * @return the expiration time
   */
  long expireAfterCreate(@Nullable K key, @Nullable V value, Expiry<K, V> expiry, long now) {
    if (expiresVariable() && (key != null) && (value != null)) {
      long duration = expiry.expireAfterCreate(key, value, now);
      return isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
    }
    return 0L;
  }

  /**
   * Returns the expiration time for the entry after being updated.
   *
   * @param node the entry in the page replacement policy
   * @param key the key of the entry that was updated
   * @param value the value of the entry that was updated
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   * @return the expiration time
   */
  long expireAfterUpdate(Node<K, V> node, @Nullable K key,
      @Nullable V value, Expiry<K, V> expiry, long now) {
    if (expiresVariable() && (key != null) && (value != null)) {
      long currentDuration = Math.max(1, node.getVariableTime() - now);
      long duration = expiry.expireAfterUpdate(key, value, now, currentDuration);
      return isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
    }
    return 0L;
  }

  /**
   * Returns the access time for the entry after a read.
   *
   * @param node the entry in the page replacement policy
   * @param key the key of the entry that was read
   * @param value the value of the entry that was read
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   * @return the expiration time
   */
  long expireAfterRead(Node<K, V> node, @Nullable K key,
      @Nullable V value, Expiry<K, V> expiry, long now) {
    if (expiresVariable() && (key != null) && (value != null)) {
      long currentDuration = Math.max(1, node.getVariableTime() - now);
      long duration = expiry.expireAfterRead(key, value, now, currentDuration);
      return isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
    }
    return 0L;
  }

  /**
   * Attempts to update the access time for the entry after a read.
   *
   * @param node the entry in the page replacement policy
   * @param key the key of the entry that was read
   * @param value the value of the entry that was read
   * @param expiry the calculator for the expiration time
   * @param now the current time, in nanoseconds
   */
  void tryExpireAfterRead(Node<K, V> node, @Nullable K key,
      @Nullable V value, Expiry<K, V> expiry, long now) {
    if (!expiresVariable() || (key == null) || (value == null)) {
      return;
    }

    long variableTime = node.getVariableTime();
    long currentDuration = Math.max(1, variableTime - now);
    if (isAsync && (currentDuration > MAXIMUM_EXPIRY)) {
      // expireAfterCreate has not yet set the duration after completion
      return;
    }

    long duration = expiry.expireAfterRead(key, value, now, currentDuration);
    if (duration != currentDuration) {
      long expirationTime = isAsync ? (now + duration) : (now + Math.min(duration, MAXIMUM_EXPIRY));
      node.casVariableTime(variableTime, expirationTime);
    }
  }

  void setVariableTime(Node<K, V> node, long expirationTime) {
    if (expiresVariable()) {
      node.setVariableTime(expirationTime);
    }
  }

  void setWriteTime(Node<K, V> node, long now) {
    if (expiresAfterWrite() || refreshAfterWrite()) {
      node.setWriteTime(now);
    }
  }

  void setAccessTime(Node<K, V> node, long now) {
    if (expiresAfterAccess()) {
      node.setAccessTime(now);
    }
  }

  /**
   * Performs the post-processing work required after a write.
   *
   * @param task the pending operation to be applied
   */
  void afterWrite(Runnable task) {
    if (buffersWrites()) {
      for (int i = 0; i < WRITE_BUFFER_RETRIES; i++) {
        if (writeBuffer().offer(task)) {
          scheduleAfterWrite();
          return;
        }
        scheduleDrainBuffers();
      }

      // The maintenance task may be scheduled but not running due to all of the executor's threads
      // being busy. If all of the threads are writing into the cache then no progress can be made
      // without assistance.
      try {
        performCleanUp(task);
      } catch (RuntimeException e) {
        logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", e);
      }
    } else {
      scheduleAfterWrite();
    }
  }

  /**
   * Conditionally schedules the asynchronous maintenance task after a write operation. If the
   * task status was IDLE or REQUIRED then the maintenance task is scheduled immediately. If it
   * is already processing then it is set to transition to REQUIRED upon completion so that a new
   * execution is triggered by the next operation.
   */
  void scheduleAfterWrite() {
    for (;;) {
      switch (drainStatus()) {
        case IDLE:
          casDrainStatus(IDLE, REQUIRED);
          scheduleDrainBuffers();
          return;
        case REQUIRED:
          scheduleDrainBuffers();
          return;
        case PROCESSING_TO_IDLE:
          if (casDrainStatus(PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED)) {
            return;
          }
          continue;
        case PROCESSING_TO_REQUIRED:
          return;
        default:
          throw new IllegalStateException();
      }
    }
  }

  /**
   * Attempts to schedule an asynchronous task to apply the pending operations to the page
   * replacement policy. If the executor rejects the task then it is run directly.
   */
  void scheduleDrainBuffers() {
    if (drainStatus() >= PROCESSING_TO_IDLE) {
      return;
    }
    if (evictionLock.tryLock()) {
      try {
        int drainStatus = drainStatus();
        if (drainStatus >= PROCESSING_TO_IDLE) {
          return;
        }
        lazySetDrainStatus(PROCESSING_TO_IDLE);
        executor.execute(drainBuffersTask);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown when submitting maintenance task", t);
        maintenance(/* ignored */ null);
      } finally {
        evictionLock.unlock();
      }
    }
  }

  @Override
  public void cleanUp() {
    try {
      performCleanUp(/* ignored */ null);
    } catch (RuntimeException e) {
      logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", e);
    }
  }

  /**
   * Performs the maintenance work, blocking until the lock is acquired.
   *
   * @param task an additional pending task to run, or {@code null} if not present
   */
  void performCleanUp(@Nullable Runnable task) {
    evictionLock.lock();
    try {
      maintenance(task);
    } finally {
      evictionLock.unlock();
    }
    if ((drainStatus() == REQUIRED) && (executor == ForkJoinPool.commonPool())) {
      scheduleDrainBuffers();
    }
  }

  /**
   * Performs the pending maintenance work and sets the state flags during processing to avoid
   * excess scheduling attempts. The read buffer, write buffer, and reference queues are
   * drained, followed by expiration, and size-based eviction.
   *
   * @param task an additional pending task to run, or {@code null} if not present
   */
  @GuardedBy("evictionLock")
  void maintenance(@Nullable Runnable task) {
    lazySetDrainStatus(PROCESSING_TO_IDLE);

    try {
      drainReadBuffer();

      drainWriteBuffer();
      if (task != null) {
        task.run();
      }

      drainKeyReferences();
      drainValueReferences();

      expireEntries();
      evictEntries();

      climb();
    } finally {
      if ((drainStatus() != PROCESSING_TO_IDLE) || !casDrainStatus(PROCESSING_TO_IDLE, IDLE)) {
        lazySetDrainStatus(REQUIRED);
      }
    }
  }

  /** Drains the weak key references queue. */
  @GuardedBy("evictionLock")
  void drainKeyReferences() {
    if (!collectKeys()) {
      return;
    }
    Reference<? extends K> keyRef;
    while ((keyRef = keyReferenceQueue().poll()) != null) {
      Node<K, V> node = data.get(keyRef);
      if (node != null) {
        evictEntry(node, RemovalCause.COLLECTED, 0L);
      }
    }
  }

  /** Drains the weak / soft value references queue. */
  @GuardedBy("evictionLock")
  void drainValueReferences() {
    if (!collectValues()) {
      return;
    }
    Reference<? extends V> valueRef;
    while ((valueRef = valueReferenceQueue().poll()) != null) {
      @SuppressWarnings("unchecked")
      InternalReference<V> ref = (InternalReference<V>) valueRef;
      Node<K, V> node = data.get(ref.getKeyReference());
      if ((node != null) && (valueRef == node.getValueReference())) {
        evictEntry(node, RemovalCause.COLLECTED, 0L);
      }
    }
  }

  /** Drains the read buffer. */
  @GuardedBy("evictionLock")
  void drainReadBuffer() {
    if (!skipReadBuffer()) {
      readBuffer.drainTo(accessPolicy);
    }
  }

  /** Updates the node's location in the page replacement policy. */
  @GuardedBy("evictionLock")
  void onAccess(Node<K, V> node) {
    if (evicts()) {
      K key = node.getKey();
      if (key == null) {
        return;
      }
      frequencySketch().increment(key);
      if (node.inWindow()) {
        reorder(accessOrderWindowDeque(), node);
      } else if (node.inMainProbation()) {
        reorderProbation(node);
      } else {
        reorder(accessOrderProtectedDeque(), node);
      }
      setHitsInSample(hitsInSample() + 1);
    } else if (expiresAfterAccess()) {
      reorder(accessOrderWindowDeque(), node);
    }
    if (expiresVariable()) {
      timerWheel().reschedule(node);
    }
  }

  /** Promote the node from probation to protected on an access. */
  @GuardedBy("evictionLock")
  void reorderProbation(Node<K, V> node) {
    if (!accessOrderProbationDeque().contains(node)) {
      // Ignore stale accesses for an entry that is no longer present
      return;
    } else if (node.getPolicyWeight() > mainProtectedMaximum()) {
      reorder(accessOrderProbationDeque(), node);
      return;
    }

    // If the protected space exceeds its maximum, the LRU items are demoted to the probation space.
    // This is deferred to the adaption phase at the end of the maintenance cycle.
    setMainProtectedWeightedSize(mainProtectedWeightedSize() + node.getPolicyWeight());
    accessOrderProbationDeque().remove(node);
    accessOrderProtectedDeque().add(node);
    node.makeMainProtected();
  }

  /** Updates the node's location in the policy's deque. */
  static <K, V> void reorder(LinkedDeque<Node<K, V>> deque, Node<K, V> node) {
    // An entry may be scheduled for reordering despite having been removed. This can occur when the
    // entry was concurrently read while a writer was removing it. If the entry is no longer linked
    // then it does not need to be processed.
    if (deque.contains(node)) {
      deque.moveToBack(node);
    }
  }

  /** Drains the write buffer. */
  @GuardedBy("evictionLock")
  void drainWriteBuffer() {
    if (!buffersWrites()) {
      return;
    }

    for (int i = 0; i < WRITE_BUFFER_MAX; i++) {
      Runnable task = writeBuffer().poll();
      if (task == null) {
        return;
      }
      task.run();
    }
    lazySetDrainStatus(PROCESSING_TO_REQUIRED);
  }

  /**
   * Atomically transitions the node to the <tt>dead</tt> state and decrements the
   * <tt>weightedSize</tt>.
   *
   * @param node the entry in the page replacement policy
   */
  @GuardedBy("evictionLock")
  void makeDead(Node<K, V> node) {
    synchronized (node) {
      if (node.isDead()) {
        return;
      }
      if (evicts()) {
        // The node's policy weight may be out of sync due to a pending update waiting to be
        // processed. At this point the node's weight is finalized, so the weight can be safely
        // taken from the node's perspective and the sizes will be adjusted correctly.
        if (node.inWindow()) {
          setWindowWeightedSize(windowWeightedSize() - node.getWeight());
        } else if (node.inMainProtected()) {
          setMainProtectedWeightedSize(mainProtectedWeightedSize() - node.getWeight());
        }
        setWeightedSize(weightedSize() - node.getWeight());
      }
      node.die();
    }
  }

  /** Adds the node to the page replacement policy. */
  final class AddTask implements Runnable {
    final Node<K, V> node;
    final int weight;

    AddTask(Node<K, V> node, int weight) {
      this.weight = weight;
      this.node = node;
    }

    @Override
    @GuardedBy("evictionLock")
    @SuppressWarnings("FutureReturnValueIgnored")
    public void run() {
      if (evicts()) {
        long weightedSize = weightedSize();
        setWeightedSize(weightedSize + weight);
        setWindowWeightedSize(windowWeightedSize() + weight);
        node.setPolicyWeight(node.getPolicyWeight() + weight);

        long maximum = maximum();
        if (weightedSize >= (maximum >>> 1)) {
          // Lazily initialize when close to the maximum
          long capacity = isWeighted() ? data.mappingCount() : maximum;
          frequencySketch().ensureCapacity(capacity);
        }

        K key = node.getKey();
        if (key != null) {
          frequencySketch().increment(key);
        }

        setMissesInSample(missesInSample() + 1);
      }

      // ignore out-of-order write operations
      boolean isAlive;
      synchronized (node) {
        isAlive = node.isAlive();
      }
      if (isAlive) {
        if (expiresAfterWrite()) {
          writeOrderDeque().add(node);
        }
        if (evicts() && (weight > windowMaximum())) {
          accessOrderWindowDeque().offerFirst(node);
        } else if (evicts() || expiresAfterAccess()) {
          accessOrderWindowDeque().offerLast(node);
        }
        if (expiresVariable()) {
          timerWheel().schedule(node);
        }
      }

      // Ensure that in-flight async computation cannot expire (reset on a completion callback)
      if (isComputingAsync(node)) {
        synchronized (node) {
          if (!Async.isReady((CompletableFuture<?>) node.getValue())) {
            long expirationTime = expirationTicker().read() + ASYNC_EXPIRY;
            setVariableTime(node, expirationTime);
            setAccessTime(node, expirationTime);
            setWriteTime(node, expirationTime);
          }
        }
      }
    }
  }

  /** Removes a node from the page replacement policy. */
  final class RemovalTask implements Runnable {
    final Node<K, V> node;

    RemovalTask(Node<K, V> node) {
      this.node = node;
    }

    @Override
    @GuardedBy("evictionLock")
    public void run() {
      // add may not have been processed yet
      if (node.inWindow() && (evicts() || expiresAfterAccess())) {
        accessOrderWindowDeque().remove(node);
      } else if (evicts()) {
        if (node.inMainProbation()) {
          accessOrderProbationDeque().remove(node);
        } else {
          accessOrderProtectedDeque().remove(node);
        }
      }
      if (expiresAfterWrite()) {
        writeOrderDeque().remove(node);
      } else if (expiresVariable()) {
        timerWheel().deschedule(node);
      }
      makeDead(node);
    }
  }

  /** Updates the weighted size. */
  final class UpdateTask implements Runnable {
    final int weightDifference;
    final Node<K, V> node;

    public UpdateTask(Node<K, V> node, int weightDifference) {
      this.weightDifference = weightDifference;
      this.node = node;
    }

    @Override
    @GuardedBy("evictionLock")
    public void run() {
      if (evicts()) {
        int oldWeightedSize = node.getPolicyWeight();
        node.setPolicyWeight(oldWeightedSize + weightDifference);
        if (node.inWindow()) {
          if (node.getPolicyWeight() <= windowMaximum()) {
            onAccess(node);
          } else if (accessOrderWindowDeque().contains(node)) {
            accessOrderWindowDeque().moveToFront(node);
          }
          setWindowWeightedSize(windowWeightedSize() + weightDifference);
        } else if (node.inMainProbation()) {
            if (node.getPolicyWeight() <= maximum()) {
              onAccess(node);
            } else if (accessOrderProbationDeque().remove(node)) {
              accessOrderWindowDeque().addFirst(node);
              setWindowWeightedSize(windowWeightedSize() + node.getPolicyWeight());
            }
        } else if (node.inMainProtected()) {
          if (node.getPolicyWeight() <= maximum()) {
            onAccess(node);
            setMainProtectedWeightedSize(mainProtectedWeightedSize() + weightDifference);
          } else if (accessOrderProtectedDeque().remove(node)) {
            accessOrderWindowDeque().addFirst(node);
            setWindowWeightedSize(windowWeightedSize() + node.getPolicyWeight());
            setMainProtectedWeightedSize(mainProtectedWeightedSize() - oldWeightedSize);
          } else {
            setMainProtectedWeightedSize(mainProtectedWeightedSize() - oldWeightedSize);
          }
        }
        setWeightedSize(weightedSize() + weightDifference);
      } else if (expiresAfterAccess()) {
        onAccess(node);
      }
      if (expiresAfterWrite()) {
        reorder(writeOrderDeque(), node);
      } else if (expiresVariable()) {
        timerWheel().reschedule(node);
      }
    }
  }

  /* --------------- Concurrent Map Support --------------- */

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public long estimatedSize() {
    return data.mappingCount();
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public void clear() {
    evictionLock.lock();
    try {
      long now = expirationTicker().read();

      // Apply all pending writes
      Runnable task;
      while (buffersWrites() && (task = writeBuffer().poll()) != null) {
        task.run();
      }

      // Discard all entries
      for (var entry : data.entrySet()) {
        removeNode(entry.getValue(), now);
      }

      // Discard all pending reads
      readBuffer.drainTo(e -> {});
    } finally {
      evictionLock.unlock();
    }
  }

  @GuardedBy("evictionLock")
  @SuppressWarnings("GuardedByChecker")
  void removeNode(Node<K, V> node, long now) {
    K key = node.getKey();
    @SuppressWarnings("unchecked")
    V[] value = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];

    data.computeIfPresent(node.getKeyReference(), (k, n) -> {
      if (n != node) {
        return n;
      }
      synchronized (n) {
        value[0] = n.getValue();

        if ((key == null) || (value[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now)) {
          cause[0] = RemovalCause.EXPIRED;
        } else {
          cause[0] = RemovalCause.EXPLICIT;
        }

        if (cause[0].wasEvicted()) {
          notifyEviction(key, value[0], cause[0]);
        }

        discardRefresh(node.getKeyReference());
        makeDead(n);
        return null;
      }
    });

    if (node.inWindow() && (evicts() || expiresAfterAccess())) {
      accessOrderWindowDeque().remove(node);
    } else if (evicts()) {
      if (node.inMainProbation()) {
        accessOrderProbationDeque().remove(node);
      } else {
        accessOrderProtectedDeque().remove(node);
      }
    }
    if (expiresAfterWrite()) {
      writeOrderDeque().remove(node);
    } else if (expiresVariable()) {
      timerWheel().deschedule(node);
    }

    if (cause[0] != null) {
      notifyRemoval(key, value[0], cause[0]);
    }
  }

  @Override
  public boolean containsKey(Object key) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    return (node != null) && (node.getValue() != null)
        && !hasExpired(node, expirationTicker().read());
  }

  @Override
  public boolean containsValue(Object value) {
    requireNonNull(value);

    long now = expirationTicker().read();
    for (Node<K, V> node : data.values()) {
      if (node.containsValue(value) && !hasExpired(node, now) && (node.getKey() != null)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public @Nullable V get(Object key) {
    return getIfPresent(key, /* recordStats */ false);
  }

  @Override
  public @Nullable V getIfPresent(Object key, boolean recordStats) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node == null) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      if (drainStatus() == REQUIRED) {
        scheduleDrainBuffers();
      }
      return null;
    }

    V value = node.getValue();
    long now = expirationTicker().read();
    if (hasExpired(node, now) || (collectValues() && (value == null))) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      scheduleDrainBuffers();
      return null;
    }

    if (!isComputingAsync(node)) {
      @SuppressWarnings("unchecked")
      K castedKey = (K) key;
      setAccessTime(node, now);
      tryExpireAfterRead(node, castedKey, value, expiry(), now);
    }
    afterRead(node, now, recordStats);
    return value;
  }

  @Override
  public @Nullable V getIfPresentQuietly(K key, long[/* 1 */] writeTime) {
    V value;
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if ((node == null) || ((value = node.getValue()) == null)
        || hasExpired(node, expirationTicker().read())) {
      return null;
    }
    writeTime[0] = node.getWriteTime();
    return value;
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<? extends K> keys) {
    Map<Object, Object> result = new LinkedHashMap<>();
    for (Object key : keys) {
      result.put(key, null);
    }

    int uniqueKeys = result.size();
    long now = expirationTicker().read();
    for (var iter = result.entrySet().iterator(); iter.hasNext();) {
      Map.Entry<Object, Object> entry = iter.next();
      V value;
      Node<K, V> node = data.get(nodeFactory.newLookupKey(entry.getKey()));
      if ((node == null) || ((value = node.getValue()) == null) || hasExpired(node, now)) {
        iter.remove();
      } else {
        entry.setValue(value);

        if (!isComputingAsync(node)) {
          @SuppressWarnings("unchecked")
          K castedKey = (K) entry.getKey();
          tryExpireAfterRead(node, castedKey, value, expiry(), now);
          setAccessTime(node, now);
        }
        afterRead(node, now, /* recordHit */ false);
      }
    }
    statsCounter().recordHits(result.size());
    statsCounter().recordMisses(uniqueKeys - result.size());

    @SuppressWarnings("unchecked")
    Map<K, V> castedResult = (Map<K, V>) result;
    return Collections.unmodifiableMap(castedResult);
  }

  @Override
  public @Nullable V put(K key, V value) {
    return put(key, value, expiry(), /* onlyIfAbsent */ false);
  }

  @Override
  public @Nullable V putIfAbsent(K key, V value) {
    return put(key, value, expiry(), /* onlyIfAbsent */ true);
  }

  /**
   * Adds a node to the policy and the data store. If an existing node is found, then its value is
   * updated if allowed.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param expiry the calculator for the expiration time
   * @param onlyIfAbsent a write is performed only if the key is not already associated with a value
   * @return the prior value in or null if no mapping was found
   */
  @Nullable V put(K key, V value, Expiry<K, V> expiry, boolean onlyIfAbsent) {
    requireNonNull(key);
    requireNonNull(value);

    Node<K, V> node = null;
    long now = expirationTicker().read();
    int newWeight = weigher.weigh(key, value);
    for (;;) {
      Node<K, V> prior = data.get(nodeFactory.newLookupKey(key));
      if (prior == null) {
        if (node == null) {
          node = nodeFactory.newNode(key, keyReferenceQueue(),
              value, valueReferenceQueue(), newWeight, now);
          setVariableTime(node, expireAfterCreate(key, value, expiry, now));
        }
        prior = data.putIfAbsent(node.getKeyReference(), node);
        if (prior == null) {
          afterWrite(new AddTask(node, newWeight));
          return null;
        } else if (onlyIfAbsent) {
          // An optimistic fast path to avoid unnecessary locking
          V currentValue = prior.getValue();
          if ((currentValue != null) && !hasExpired(prior, now)) {
            if (!isComputingAsync(prior)) {
              tryExpireAfterRead(prior, key, currentValue, expiry(), now);
              setAccessTime(prior, now);
            }
            afterRead(prior, now, /* recordHit */ false);
            return currentValue;
          }
        }
      } else if (onlyIfAbsent) {
        // An optimistic fast path to avoid unnecessary locking
        V currentValue = prior.getValue();
        if ((currentValue != null) && !hasExpired(prior, now)) {
          if (!isComputingAsync(prior)) {
            tryExpireAfterRead(prior, key, currentValue, expiry(), now);
            setAccessTime(prior, now);
          }
          afterRead(prior, now, /* recordHit */ false);
          return currentValue;
        }
      } else {
        discardRefresh(prior.getKeyReference());
      }

      V oldValue;
      long varTime;
      int oldWeight;
      boolean expired = false;
      boolean mayUpdate = true;
      boolean exceedsTolerance = false;
      synchronized (prior) {
        if (!prior.isAlive()) {
          continue;
        }
        oldValue = prior.getValue();
        oldWeight = prior.getWeight();
        if (oldValue == null) {
          varTime = expireAfterCreate(key, value, expiry, now);
          notifyEviction(key, null, RemovalCause.COLLECTED);
        } else if (hasExpired(prior, now)) {
          expired = true;
          varTime = expireAfterCreate(key, value, expiry, now);
          notifyEviction(key, oldValue, RemovalCause.EXPIRED);
        } else if (onlyIfAbsent) {
          mayUpdate = false;
          varTime = expireAfterRead(prior, key, value, expiry, now);
        } else {
          varTime = expireAfterUpdate(prior, key, value, expiry, now);
        }

        if (mayUpdate) {
          exceedsTolerance =
              (expiresAfterWrite() && (now - prior.getWriteTime()) > EXPIRE_WRITE_TOLERANCE)
              || (expiresVariable()
                  && Math.abs(varTime - prior.getVariableTime()) > EXPIRE_WRITE_TOLERANCE);

          setWriteTime(prior, now);
          prior.setWeight(newWeight);
          prior.setValue(value, valueReferenceQueue());
        }

        setVariableTime(prior, varTime);
        setAccessTime(prior, now);
      }

      if (expired) {
        notifyRemoval(key, oldValue, RemovalCause.EXPIRED);
      } else if (oldValue == null) {
        notifyRemoval(key, /* oldValue */ null, RemovalCause.COLLECTED);
      } else if (mayUpdate && (value != oldValue)) {
        notifyRemoval(key, oldValue, RemovalCause.REPLACED);
      }

      int weightedDifference = mayUpdate ? (newWeight - oldWeight) : 0;
      if ((oldValue == null) || (weightedDifference != 0) || expired) {
        afterWrite(new UpdateTask(prior, weightedDifference));
      } else if (!onlyIfAbsent && exceedsTolerance) {
        afterWrite(new UpdateTask(prior, weightedDifference));
      } else {
        if (mayUpdate) {
          setWriteTime(prior, now);
        }
        afterRead(prior, now, /* recordHit */ false);
      }

      return expired ? null : oldValue;
    }
  }

  @Override
  public @Nullable V remove(Object key) {
    @SuppressWarnings("unchecked")
    K castKey = (K) key;
    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] node = new Node[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];
    Object lookupKey = nodeFactory.newLookupKey(key);

    data.computeIfPresent(lookupKey, (k, n) -> {
      synchronized (n) {
        oldValue[0] = n.getValue();
        if (oldValue[0] == null) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, expirationTicker().read())) {
          cause[0] = RemovalCause.EXPIRED;
        } else {
          cause[0] = RemovalCause.EXPLICIT;
        }
        if (cause[0].wasEvicted()) {
          notifyEviction(castKey, oldValue[0], cause[0]);
        }
        n.retire();
      }
      discardRefresh(lookupKey);
      node[0] = n;
      return null;
    });

    if (cause[0] != null) {
      afterWrite(new RemovalTask(node[0]));
      notifyRemoval(castKey, oldValue[0], cause[0]);
    }
    return (cause[0] == RemovalCause.EXPLICIT) ? oldValue[0] : null;
  }

  @Override
  public boolean remove(Object key, Object value) {
    requireNonNull(key);
    if (value == null) {
      return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] removed = new Node[1];
    @SuppressWarnings("unchecked")
    K[] oldKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];
    Object lookupKey = nodeFactory.newLookupKey(key);

    data.computeIfPresent(lookupKey, (kR, node) -> {
      synchronized (node) {
        oldKey[0] = node.getKey();
        oldValue[0] = node.getValue();
        if (oldKey[0] == null) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(node, expirationTicker().read())) {
          cause[0] = RemovalCause.EXPIRED;
        } else if (node.containsValue(value)) {
          cause[0] = RemovalCause.EXPLICIT;
        } else {
          return node;
        }
        if (cause[0].wasEvicted()) {
          notifyEviction(oldKey[0], oldValue[0], cause[0]);
        }
        discardRefresh(lookupKey);
        removed[0] = node;
        node.retire();
        return null;
      }
    });

    if (removed[0] == null) {
      return false;
    }
    afterWrite(new RemovalTask(removed[0]));
    notifyRemoval(oldKey[0], oldValue[0], cause[0]);

    return (cause[0] == RemovalCause.EXPLICIT);
  }

  @Override
  public @Nullable V replace(K key, V value) {
    requireNonNull(key);
    requireNonNull(value);

    int[] oldWeight = new int[1];
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    long[] now = new long[1];
    int weight = weigher.weigh(key, value);
    Node<K, V> node = data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
      synchronized (n) {
        nodeKey[0] = n.getKey();
        oldValue[0] = n.getValue();
        oldWeight[0] = n.getWeight();
        if ((nodeKey[0] == null) || (oldValue[0] == null)
            || hasExpired(n, now[0] = expirationTicker().read())) {
          oldValue[0] = null;
          return n;
        }

        long varTime = expireAfterUpdate(n, key, value, expiry(), now[0]);
        n.setValue(value, valueReferenceQueue());
        n.setWeight(weight);

        setVariableTime(n, varTime);
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        discardRefresh(k);
        return n;
      }
    });

    if (oldValue[0] == null) {
      return null;
    }

    int weightedDifference = (weight - oldWeight[0]);
    if (expiresAfterWrite() || (weightedDifference != 0)) {
      afterWrite(new UpdateTask(node, weightedDifference));
    } else {
      afterRead(node, now[0], /* recordHit */ false);
    }

    if (value != oldValue[0]) {
      notifyRemoval(nodeKey[0], oldValue[0], RemovalCause.REPLACED);
    }
    return oldValue[0];
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(key);
    requireNonNull(oldValue);
    requireNonNull(newValue);

    int weight = weigher.weigh(key, newValue);
    boolean[] replaced = new boolean[1];
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] prevValue = (V[]) new Object[1];
    int[] oldWeight = new int[1];
    long[] now = new long[1];
    Node<K, V> node = data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
      synchronized (n) {
        nodeKey[0] = n.getKey();
        prevValue[0] = n.getValue();
        oldWeight[0] = n.getWeight();
        if ((nodeKey[0] == null) || (prevValue[0] == null) || !n.containsValue(oldValue)
            || hasExpired(n, now[0] = expirationTicker().read())) {
          return n;
        }

        long varTime = expireAfterUpdate(n, key, newValue, expiry(), now[0]);
        n.setValue(newValue, valueReferenceQueue());
        n.setWeight(weight);

        setVariableTime(n, varTime);
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        replaced[0] = true;
        discardRefresh(k);
      }
      return n;
    });

    if (!replaced[0]) {
      return false;
    }

    int weightedDifference = (weight - oldWeight[0]);
    if (expiresAfterWrite() || (weightedDifference != 0)) {
      afterWrite(new UpdateTask(node, weightedDifference));
    } else {
      afterRead(node, now[0], /* recordHit */ false);
    }

    if (oldValue != newValue) {
      notifyRemoval(nodeKey[0], prevValue[0], RemovalCause.REPLACED);
    }
    return true;
  }

  @Override
  public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
    requireNonNull(function);

    BiFunction<K, V, V> remappingFunction = (key, oldValue)
        -> requireNonNull(function.apply(key, oldValue));
    for (K key : keySet()) {
      long[] now = { expirationTicker().read() };
      Object lookupKey = nodeFactory.newLookupKey(key);
      remap(key, lookupKey, remappingFunction, expiry(), now, /* computeIfAbsent */ false);
    }
  }

  @Override
  public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean recordStats, boolean recordLoad) {
    requireNonNull(key);
    requireNonNull(mappingFunction);
    long now = expirationTicker().read();

    // An optimistic fast path to avoid unnecessary locking
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node != null) {
      V value = node.getValue();
      if ((value != null) && !hasExpired(node, now)) {
        if (!isComputingAsync(node)) {
          tryExpireAfterRead(node, key, value, expiry(), now);
          setAccessTime(node, now);
        }

        afterRead(node, now, /* recordHit */ recordStats);
        return value;
      }
    }
    if (recordStats) {
      mappingFunction = statsAware(mappingFunction, recordLoad);
    }
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    return doComputeIfAbsent(key, keyRef, mappingFunction, new long[] { now }, recordStats);
  }

  /** Returns the current value from a computeIfAbsent invocation. */
  @Nullable V doComputeIfAbsent(K key, Object keyRef,
      Function<? super K, ? extends V> mappingFunction, long[/* 1 */] now, boolean recordStats) {
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] removed = new Node[1];

    int[] weight = new int[2]; // old, new
    RemovalCause[] cause = new RemovalCause[1];
    Node<K, V> node = data.compute(keyRef, (k, n) -> {
      if (n == null) {
        newValue[0] = mappingFunction.apply(key);
        if (newValue[0] == null) {
          return null;
        }
        now[0] = expirationTicker().read();
        weight[1] = weigher.weigh(key, newValue[0]);
        n = nodeFactory.newNode(key, keyReferenceQueue(),
            newValue[0], valueReferenceQueue(), weight[1], now[0]);
        setVariableTime(n, expireAfterCreate(key, newValue[0], expiry(), now[0]));
        return n;
      }

      synchronized (n) {
        nodeKey[0] = n.getKey();
        weight[0] = n.getWeight();
        oldValue[0] = n.getValue();
        if ((nodeKey[0] == null) || (oldValue[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now[0])) {
          cause[0] = RemovalCause.EXPIRED;
        } else {
          return n;
        }

        if (cause[0].wasEvicted()) {
          notifyEviction(nodeKey[0], oldValue[0], cause[0]);
        }
        newValue[0] = mappingFunction.apply(key);
        if (newValue[0] == null) {
          removed[0] = n;
          n.retire();
          return null;
        }
        weight[1] = weigher.weigh(key, newValue[0]);
        n.setValue(newValue[0], valueReferenceQueue());
        n.setWeight(weight[1]);

        now[0] = expirationTicker().read();
        setVariableTime(n, expireAfterCreate(key, newValue[0], expiry(), now[0]));
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        discardRefresh(k);
        return n;
      }
    });

    if (node == null) {
      if (removed[0] != null) {
        afterWrite(new RemovalTask(removed[0]));
      }
      return null;
    }
    if (cause[0] != null) {
      notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
      statsCounter().recordEviction(weight[0], cause[0]);
    }
    if (newValue[0] == null) {
      if (!isComputingAsync(node)) {
        tryExpireAfterRead(node, key, oldValue[0], expiry(), now[0]);
        setAccessTime(node, now[0]);
      }

      afterRead(node, now[0], /* recordHit */ recordStats);
      return oldValue[0];
    }
    if ((oldValue[0] == null) && (cause[0] == null)) {
      afterWrite(new AddTask(node, weight[1]));
    } else {
      int weightedDifference = (weight[1] - weight[0]);
      afterWrite(new UpdateTask(node, weightedDifference));
    }

    return newValue[0];
  }

  @Override
  public @Nullable V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    // A optimistic fast path to avoid unnecessary locking
    Object lookupKey = nodeFactory.newLookupKey(key);
    @Nullable Node<K, V> node = data.get(lookupKey);
    long now;
    if (node == null) {
      return null;
    } else if ((node.getValue() == null) || hasExpired(node, (now = expirationTicker().read()))) {
      scheduleDrainBuffers();
      return null;
    }

    BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
        statsAware(remappingFunction, /* recordMiss */ false,
            /* recordLoad */ true, /* recordLoadFailure */ true);
    return remap(key, lookupKey, statsAwareRemappingFunction,
        expiry(), new long[] { now }, /* computeIfAbsent */ false);
  }

  @Override
  @SuppressWarnings("NullAway")
  public @Nullable V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      @Nullable Expiry<K, V> expiry, boolean recordMiss,
      boolean recordLoad, boolean recordLoadFailure) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    long[] now = { expirationTicker().read() };
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
        statsAware(remappingFunction, recordMiss, recordLoad, recordLoadFailure);
    return remap(key, keyRef, statsAwareRemappingFunction,
        expiry, now, /* computeIfAbsent */ true);
  }

  @Override
  public @Nullable V merge(K key, V value,
      BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(value);
    requireNonNull(remappingFunction);

    long[] now = { expirationTicker().read() };
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    BiFunction<? super K, ? super V, ? extends V> mergeFunction = (k, oldValue) ->
        (oldValue == null) ? value : statsAware(remappingFunction).apply(oldValue, value);
    return remap(key, keyRef, mergeFunction, expiry(), now, /* computeIfAbsent */ true);
  }

  /**
   * Attempts to compute a mapping for the specified key and its current mapped value (or
   * {@code null} if there is no current mapping).
   * <p>
   * An entry that has expired or been reference collected is evicted and the computation continues
   * as if the entry had not been present. This method does not pre-screen and does not wrap the
   * remappingFunction to be statistics aware.
   *
   * @param key key with which the specified value is to be associated
   * @param keyRef the key to associate with or a lookup only key if not <tt>computeIfAbsent</tt>
   * @param remappingFunction the function to compute a value
   * @param expiry the calculator for the expiration time
   * @param now the current time, according to the ticker
   * @param computeIfAbsent if an absent entry can be computed
   * @return the new value associated with the specified key, or null if none
   */
  @SuppressWarnings("PMD.EmptyIfStmt")
  @Nullable V remap(K key, Object keyRef,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      Expiry<K, V> expiry, long[/* 1 */] now, boolean computeIfAbsent) {
    @SuppressWarnings("unchecked")
    K[] nodeKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] removed = new Node[1];

    int[] weight = new int[2]; // old, new
    RemovalCause[] cause = new RemovalCause[1];

    Node<K, V> node = data.compute(keyRef, (kr, n) -> {
      if (n == null) {
        if (!computeIfAbsent) {
          return null;
        }
        newValue[0] = remappingFunction.apply(key, null);
        if (newValue[0] == null) {
          return null;
        }
        now[0] = expirationTicker().read();
        weight[1] = weigher.weigh(key, newValue[0]);
        n = nodeFactory.newNode(keyRef, newValue[0],
            valueReferenceQueue(), weight[1], now[0]);
        setVariableTime(n, expireAfterCreate(key, newValue[0], expiry, now[0]));
        return n;
      }

      synchronized (n) {
        nodeKey[0] = n.getKey();
        oldValue[0] = n.getValue();
        if ((nodeKey[0] == null) || (oldValue[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now[0])) {
          cause[0] = RemovalCause.EXPIRED;
        }
        if (cause[0] != null) {
          notifyEviction(nodeKey[0], oldValue[0], cause[0]);
          if (!computeIfAbsent) {
            removed[0] = n;
            n.retire();
            return null;
          }
        }

        newValue[0] = remappingFunction.apply(nodeKey[0],
            (cause[0] == null) ? oldValue[0] : null);
        if (newValue[0] == null) {
          if (cause[0] == null) {
            cause[0] = RemovalCause.EXPLICIT;
            discardRefresh(kr);
          }
          removed[0] = n;
          n.retire();
          return null;
        }

        weight[0] = n.getWeight();
        weight[1] = weigher.weigh(key, newValue[0]);
        now[0] = expirationTicker().read();
        if (cause[0] == null) {
          if (newValue[0] != oldValue[0]) {
            cause[0] = RemovalCause.REPLACED;
          }
          setVariableTime(n, expireAfterUpdate(n, key, newValue[0], expiry, now[0]));
        } else {
          setVariableTime(n, expireAfterCreate(key, newValue[0], expiry, now[0]));
        }
        n.setValue(newValue[0], valueReferenceQueue());
        n.setWeight(weight[1]);
        setAccessTime(n, now[0]);
        setWriteTime(n, now[0]);
        discardRefresh(kr);
        return n;
      }
    });

    if (cause[0] != null) {
      if (cause[0].wasEvicted()) {
        statsCounter().recordEviction(weight[0], cause[0]);
      }
      notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
    }

    if (removed[0] != null) {
      afterWrite(new RemovalTask(removed[0]));
    } else if (node == null) {
      // absent and not computable
    } else if ((oldValue[0] == null) && (cause[0] == null)) {
      afterWrite(new AddTask(node, weight[1]));
    } else {
      int weightedDifference = weight[1] - weight[0];
      if (expiresAfterWrite() || (weightedDifference != 0)) {
        afterWrite(new UpdateTask(node, weightedDifference));
      } else {
        if ((cause[0] == null) && !isComputingAsync(node)) {
          tryExpireAfterRead(node, key, newValue[0], expiry, now[0]);
          setAccessTime(node, now[0]);
        }
        afterRead(node, now[0], /* recordHit */ false);
        if ((cause[0] != null) && cause[0].wasEvicted()) {
          scheduleDrainBuffers();
        }
      }
    }

    return newValue[0];
  }

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySetView<>(this)) : ks;
  }

  @Override
  public Collection<V> values() {
    final Collection<V> vs = values;
    return (vs == null) ? (values = new ValuesView<>(this)) : vs;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    final Set<Entry<K, V>> es = entrySet;
    return (es == null) ? (entrySet = new EntrySetView<>(this)) : es;
  }

  /**
   * Returns an unmodifiable snapshot map ordered in eviction order, either ascending or descending.
   * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param hottest the iteration order
   * @return an unmodifiable snapshot in a specified order
   */
  @SuppressWarnings("GuardedByChecker")
  Map<K, V> evictionOrder(int limit, Function<V, V> transformer, boolean hottest) {
    Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> {
      Comparator<Node<K, V>> comparator = Comparator.comparingInt(node -> {
          K key = node.getKey();
          return (key == null) ? 0 : frequencySketch().frequency(key);
      });
      if (hottest) {
        PeekingIterator<Node<K, V>> secondary = PeekingIterator.comparing(
            accessOrderProbationDeque().descendingIterator(),
            accessOrderWindowDeque().descendingIterator(), comparator);
        return PeekingIterator.concat(accessOrderProtectedDeque().descendingIterator(), secondary);
      } else {
        PeekingIterator<Node<K, V>> primary = PeekingIterator.comparing(
            accessOrderWindowDeque().iterator(), accessOrderProbationDeque().iterator(),
            comparator.reversed());
        return PeekingIterator.concat(primary, accessOrderProtectedDeque().iterator());
      }
    };
    return fixedSnapshot(iteratorSupplier, limit, transformer);
  }

  /**
   * Returns an unmodifiable snapshot map ordered in access expiration order, either ascending or
   * descending. Beware that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param oldest the iteration order
   * @return an unmodifiable snapshot in a specified order
   */
  @SuppressWarnings("GuardedByChecker")
  Map<K, V> expireAfterAccessOrder(int limit, Function<V, V> transformer, boolean oldest) {
    if (!evicts()) {
      Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> oldest
          ? accessOrderWindowDeque().iterator()
          : accessOrderWindowDeque().descendingIterator();
      return fixedSnapshot(iteratorSupplier, limit, transformer);
    }

    Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> {
        Comparator<Node<K, V>> comparator = Comparator.comparingLong(Node::getAccessTime);
        PeekingIterator<Node<K, V>> first, second, third;
        if (oldest) {
          first = accessOrderWindowDeque().iterator();
          second = accessOrderProbationDeque().iterator();
          third = accessOrderProtectedDeque().iterator();
        } else {
          comparator = comparator.reversed();
          first = accessOrderWindowDeque().descendingIterator();
          second = accessOrderProbationDeque().descendingIterator();
          third = accessOrderProtectedDeque().descendingIterator();
        }
        return PeekingIterator.comparing(
            PeekingIterator.comparing(first, second, comparator), third, comparator);
    };
    return fixedSnapshot(iteratorSupplier, limit, transformer);
  }

  /**
   * Returns an unmodifiable snapshot map ordered in write expiration order, either ascending or
   * descending. Beware that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param oldest the iteration order
   * @return an unmodifiable snapshot in a specified order
   */
  @SuppressWarnings("GuardedByChecker")
  Map<K, V> expireAfterWriteOrder(int limit, Function<V, V> transformer, boolean oldest) {
    Supplier<Iterator<Node<K, V>>> iteratorSupplier = () -> oldest
        ? writeOrderDeque().iterator()
        : writeOrderDeque().descendingIterator();
    return fixedSnapshot(iteratorSupplier, limit, transformer);
  }

  /**
   * Returns an unmodifiable snapshot map ordered by the provided iterator. Beware that obtaining
   * the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param iteratorSupplier the iterator
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @return an unmodifiable snapshot in the iterator's order
   */
  Map<K, V> fixedSnapshot(Supplier<Iterator<Node<K, V>>> iteratorSupplier,
      int limit, Function<V, V> transformer) {
    requireArgument(limit >= 0);
    evictionLock.lock();
    try {
      maintenance(/* ignored */ null);

      int initialCapacity = Math.min(limit, size());
      Iterator<Node<K, V>> iterator = iteratorSupplier.get();
      Map<K, V> map = new LinkedHashMap<>(initialCapacity);
      while ((map.size() < limit) && iterator.hasNext()) {
        Node<K, V> node = iterator.next();
        K key = node.getKey();
        V value = transformer.apply(node.getValue());
        if ((key != null) && (value != null) && node.isAlive()) {
          map.put(key, value);
        }
      }
      return Collections.unmodifiableMap(map);
    } finally {
      evictionLock.unlock();
    }
  }

  /**
   * Returns an unmodifiable snapshot map roughly ordered by the expiration time. The wheels are
   * evaluated in order, but the timers that fall within the bucket's range are not sorted. Beware
   * that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param ascending the direction
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @return an unmodifiable snapshot in the desired order
   */
  Map<K, V> variableSnapshot(boolean ascending, int limit, Function<V, V> transformer) {
    evictionLock.lock();
    try {
      maintenance(/* ignored */ null);
      return timerWheel().snapshot(ascending, limit, transformer);
    } finally {
      evictionLock.unlock();
    }
  }

  /** An adapter to safely externalize the keys. */
  static final class KeySetView<K, V> extends AbstractSet<K> {
    final BoundedLocalCache<K, V> cache;

    KeySetView(BoundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object obj) {
      return cache.containsKey(obj);
    }

    @Override
    public boolean remove(Object obj) {
      return (cache.remove(obj) != null);
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator<>(cache);
    }

    @Override
    public Spliterator<K> spliterator() {
      return new KeySpliterator<>(cache);
    }

    @Override
    public Object[] toArray() {
      List<Object> keys = new ArrayList<>(size());
      for (Object key : this) {
        keys.add(key);
      }
      return keys.toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      List<Object> keys = new ArrayList<>(size());
      for (Object key : this) {
        keys.add(key);
      }
      return keys.toArray(array);
    }
  }

  /** An adapter to safely externalize the key iterator. */
  static final class KeyIterator<K, V> implements Iterator<K> {
    final EntryIterator<K, V> iterator;

    KeyIterator(BoundedLocalCache<K, V> cache) {
      this.iterator = new EntryIterator<>(cache);
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      return iterator.nextKey();
    }

    @Override
    public void remove() {
      iterator.remove();
    }
  }

  /** An adapter to safely externalize the key spliterator. */
  static final class KeySpliterator<K, V> implements Spliterator<K> {
    final Spliterator<Node<K, V>> spliterator;
    final BoundedLocalCache<K, V> cache;

    KeySpliterator(BoundedLocalCache<K, V> cache) {
      this(cache, cache.data.values().spliterator());
    }

    KeySpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super K> action) {
      requireNonNull(action);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(key);
        }
      };
      spliterator.forEachRemaining(consumer);
    }

    @Override
    public boolean tryAdvance(Consumer<? super K> action) {
      requireNonNull(action);
      boolean[] advanced = { false };
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(key);
          advanced[0] = true;
        }
      };
      for (;;) {
        if (spliterator.tryAdvance(consumer)) {
          if (advanced[0]) {
            return true;
          }
          continue;
        }
        return false;
      }
    }

    @Override
    public @Nullable Spliterator<K> trySplit() {
      Spliterator<Node<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new KeySpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL;
    }
  }

  /** An adapter to safely externalize the values. */
  static final class ValuesView<K, V> extends AbstractCollection<V> {
    final BoundedLocalCache<K, V> cache;

    ValuesView(BoundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object o) {
      return cache.containsValue(o);
    }

    @Override
    public boolean removeIf(Predicate<? super V> filter) {
      requireNonNull(filter);
      boolean removed = false;
      for (Entry<K, V> entry : cache.entrySet()) {
        if (filter.test(entry.getValue())) {
          removed |= cache.remove(entry.getKey(), entry.getValue());
        }
      }
      return removed;
    }

    @Override
    public Iterator<V> iterator() {
      return new ValueIterator<>(cache);
    }

    @Override
    public Spliterator<V> spliterator() {
      return new ValueSpliterator<>(cache);
    }
  }

  /** An adapter to safely externalize the value iterator. */
  static final class ValueIterator<K, V> implements Iterator<V> {
    final EntryIterator<K, V> iterator;

    ValueIterator(BoundedLocalCache<K, V> cache) {
      this.iterator = new EntryIterator<>(cache);
    }

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      return iterator.nextValue();
    }

    @Override
    public void remove() {
      iterator.remove();
    }
  }

  /** An adapter to safely externalize the value spliterator. */
  static final class ValueSpliterator<K, V> implements Spliterator<V> {
    final Spliterator<Node<K, V>> spliterator;
    final BoundedLocalCache<K, V> cache;

    ValueSpliterator(BoundedLocalCache<K, V> cache) {
      this(cache, cache.data.values().spliterator());
    }

    ValueSpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super V> action) {
      requireNonNull(action);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(value);
        }
      };
      spliterator.forEachRemaining(consumer);
    }

    @Override
    public boolean tryAdvance(Consumer<? super V> action) {
      requireNonNull(action);
      boolean[] advanced = { false };
      long now = cache.expirationTicker().read();
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        if ((key != null) && (value != null) && !cache.hasExpired(node, now) && node.isAlive()) {
          action.accept(value);
          advanced[0] = true;
        }
      };
      for (;;) {
        if (spliterator.tryAdvance(consumer)) {
          if (advanced[0]) {
            return true;
          }
          continue;
        }
        return false;
      }
    }

    @Override
    public @Nullable Spliterator<V> trySplit() {
      Spliterator<Node<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new ValueSpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return Spliterator.CONCURRENT | Spliterator.NONNULL;
    }
  }

  /** An adapter to safely externalize the entries. */
  static final class EntrySetView<K, V> extends AbstractSet<Entry<K, V>> {
    final BoundedLocalCache<K, V> cache;

    EntrySetView(BoundedLocalCache<K, V> cache) {
      this.cache = requireNonNull(cache);
    }

    @Override
    public int size() {
      return cache.size();
    }

    @Override
    public void clear() {
      cache.clear();
    }

    @Override
    public boolean contains(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(entry.getKey()));
      return (node != null) && Objects.equals(node.getValue(), entry.getValue());
    }

    @Override
    public boolean remove(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      return cache.remove(entry.getKey(), entry.getValue());
    }

    @Override
    public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
      requireNonNull(filter);
      boolean removed = false;
      for (Entry<K, V> entry : this) {
        if (filter.test(entry)) {
          removed |= cache.remove(entry.getKey(), entry.getValue());
        }
      }
      return removed;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator<>(cache);
    }

    @Override
    public Spliterator<Entry<K, V>> spliterator() {
      return new EntrySpliterator<>(cache);
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  static final class EntryIterator<K, V> implements Iterator<Entry<K, V>> {
    final BoundedLocalCache<K, V> cache;
    final Iterator<Node<K, V>> iterator;
    final long now;

    @Nullable K key;
    @Nullable V value;
    @Nullable K removalKey;
    @Nullable Node<K, V> next;

    EntryIterator(BoundedLocalCache<K, V> cache) {
      this.iterator = cache.data.values().iterator();
      this.now = cache.expirationTicker().read();
      this.cache = cache;
    }

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }
      for (;;) {
        if (iterator.hasNext()) {
          next = iterator.next();
          value = next.getValue();
          key = next.getKey();

          boolean evictable = cache.hasExpired(next, now) || (key == null) || (value == null);
          if (evictable || !next.isAlive()) {
            if (evictable) {
              cache.scheduleDrainBuffers();
            }
            value = null;
            next = null;
            key = null;
            continue;
          }
          return true;
        }
        return false;
      }
    }

    @SuppressWarnings("NullAway")
    K nextKey() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      removalKey = key;
      value = null;
      next = null;
      key = null;
      return removalKey;
    }

    @SuppressWarnings("NullAway")
    V nextValue() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      removalKey = key;
      V val = value;
      value = null;
      next = null;
      key = null;
      return val;
    }

    @Override
    public Entry<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      @SuppressWarnings("NullAway")
      Entry<K, V> entry = new WriteThroughEntry<>(cache, key, value);
      removalKey = key;
      value = null;
      next = null;
      key = null;
      return entry;
    }

    @Override
    public void remove() {
      if (removalKey == null) {
        throw new IllegalStateException();
      }
      cache.remove(removalKey);
      removalKey = null;
    }
  }

  /** An adapter to safely externalize the entry spliterator. */
  static final class EntrySpliterator<K, V> implements Spliterator<Entry<K, V>> {
    final Spliterator<Node<K, V>> spliterator;
    final BoundedLocalCache<K, V> cache;

    EntrySpliterator(BoundedLocalCache<K, V> cache) {
      this(cache, cache.data.values().spliterator());
    }

    EntrySpliterator(BoundedLocalCache<K, V> cache, Spliterator<Node<K, V>> spliterator) {
      this.spliterator = requireNonNull(spliterator);
      this.cache = requireNonNull(cache);
    }

    @Override
    public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
      requireNonNull(action);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(new WriteThroughEntry<>(cache, key, value));
        }
      };
      spliterator.forEachRemaining(consumer);
    }

    @Override
    public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
      requireNonNull(action);
      boolean[] advanced = { false };
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        long now = cache.expirationTicker().read();
        if ((key != null) && (value != null) && node.isAlive() && !cache.hasExpired(node, now)) {
          action.accept(new WriteThroughEntry<>(cache, key, value));
          advanced[0] = true;
        }
      };
      for (;;) {
        if (spliterator.tryAdvance(consumer)) {
          if (advanced[0]) {
            return true;
          }
          continue;
        }
        return false;
      }
    }

    @Override
    public @Nullable Spliterator<Entry<K, V>> trySplit() {
      Spliterator<Node<K, V>> split = spliterator.trySplit();
      return (split == null) ? null : new EntrySpliterator<>(cache, split);
    }

    @Override
    public long estimateSize() {
      return spliterator.estimateSize();
    }

    @Override
    public int characteristics() {
      return Spliterator.DISTINCT | Spliterator.CONCURRENT | Spliterator.NONNULL;
    }
  }

  /** A reusable task that performs the maintenance work; used to avoid wrapping by ForkJoinPool. */
  static final class PerformCleanupTask extends ForkJoinTask<Void> implements Runnable {
    private static final long serialVersionUID = 1L;

    final WeakReference<BoundedLocalCache<?, ?>> reference;

    PerformCleanupTask(BoundedLocalCache<?, ?> cache) {
      reference = new WeakReference<BoundedLocalCache<?,?>>(cache);
    }

    @Override
    public boolean exec() {
      try {
        run();
      } catch (Throwable t) {
        logger.log(Level.ERROR, "Exception thrown when performing the maintenance task", t);
      }

      // Indicates that the task has not completed to allow subsequent submissions to execute
      return false;
    }

    @Override
    public void run() {
      BoundedLocalCache<?, ?> cache = reference.get();
      if (cache != null) {
        cache.performCleanUp(/* ignored */ null);
      }
    }

    /**
     * This method cannot be ignored due to being final, so a hostile user supplied Executor could
     * forcibly complete the task and halt future executions. There are easier ways to intentionally
     * harm a system, so this is assumed to not happen in practice.
     */
    // public final void quietlyComplete() {}

    @Override public Void getRawResult() { return null; }
    @Override public void setRawResult(Void v) {}
    @Override public void complete(Void value) {}
    @Override public void completeExceptionally(Throwable ex) {}
    @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
  }

  /** Creates a serialization proxy based on the common configuration shared by all cache types. */
  static <K, V> SerializationProxy<K, V> makeSerializationProxy(
      BoundedLocalCache<?, ?> cache, boolean isWeighted) {
    SerializationProxy<K, V> proxy = new SerializationProxy<>();
    proxy.weakKeys = cache.collectKeys();
    proxy.weakValues = cache.nodeFactory.weakValues();
    proxy.softValues = cache.nodeFactory.softValues();
    proxy.isRecordingStats = cache.isRecordingStats();
    proxy.evictionListener = cache.evictionListener;
    proxy.removalListener = cache.removalListener();
    proxy.ticker = cache.expirationTicker();
    if (cache.expiresAfterAccess()) {
      proxy.expiresAfterAccessNanos = cache.expiresAfterAccessNanos();
    }
    if (cache.expiresAfterWrite()) {
      proxy.expiresAfterWriteNanos = cache.expiresAfterWriteNanos();
    }
    if (cache.expiresVariable()) {
      proxy.expiry = cache.expiry();
    }
    if (cache.evicts()) {
      if (isWeighted) {
        proxy.weigher = cache.weigher;
        proxy.maximumWeight = cache.maximum();
      } else {
        proxy.maximumSize = cache.maximum();
      }
    }
    return proxy;
  }

  /* --------------- Manual Cache --------------- */

  static class BoundedLocalManualCache<K, V> implements LocalManualCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, V> cache;
    final boolean isWeighted;

    @Nullable Policy<K, V> policy;

    BoundedLocalManualCache(Caffeine<K, V> builder) {
      this(builder, null);
    }

    BoundedLocalManualCache(Caffeine<K, V> builder, @Nullable CacheLoader<? super K, V> loader) {
      cache = LocalCacheFactory.newBoundedLocalCache(builder, loader, /* async */ false);
      isWeighted = builder.isWeighted();
    }

    @Override
    public BoundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public Policy<K, V> policy() {
      return (policy == null)
          ? (policy = new BoundedPolicy<>(cache, Function.identity(), isWeighted))
          : policy;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      return makeSerializationProxy(cache, isWeighted);
    }
  }

  static final class BoundedPolicy<K, V> implements Policy<K, V> {
    final BoundedLocalCache<K, V> cache;
    final Function<V, V> transformer;
    final boolean isWeighted;

    @Nullable Optional<Eviction<K, V>> eviction;
    @Nullable Optional<FixedRefresh<K, V>> refreshes;
    @Nullable Optional<FixedExpiration<K, V>> afterWrite;
    @Nullable Optional<FixedExpiration<K, V>> afterAccess;
    @Nullable Optional<VarExpiration<K, V>> variable;

    BoundedPolicy(BoundedLocalCache<K, V> cache, Function<V, V> transformer, boolean isWeighted) {
      this.transformer = transformer;
      this.isWeighted = isWeighted;
      this.cache = cache;
    }

    @Override public boolean isRecordingStats() {
      return cache.isRecordingStats();
    }
    @Override public @Nullable V getIfPresentQuietly(K key) {
      Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(key));
      if ((node == null) || cache.hasExpired(node, cache.expirationTicker().read())) {
        return null;
      }
      return transformer.apply(node.getValue());
    }
    @Override public Map<K, CompletableFuture<V>> refreshes() {
      var refreshes = cache.refreshes;
      if ((refreshes == null) || refreshes.isEmpty()) {
        return Map.of();
      } if (cache.collectKeys()) {
        var inFlight = new IdentityHashMap<K, CompletableFuture<V>>(refreshes.size());
        for (var entry : refreshes.entrySet()) {
          @SuppressWarnings("unchecked")
          var key = ((InternalReference<K>) entry.getKey()).get();
          @SuppressWarnings("unchecked")
          var future = (CompletableFuture<V>) entry.getValue();
          if (key != null) {
            inFlight.put(key, future);
          }
        }
        return Collections.unmodifiableMap(inFlight);
      }
      @SuppressWarnings("unchecked")
      var castedRefreshes = (Map<K, CompletableFuture<V>>) (Object) refreshes;
      return Map.copyOf(castedRefreshes);
    }
    @Override public Optional<Eviction<K, V>> eviction() {
      return cache.evicts()
          ? (eviction == null) ? (eviction = Optional.of(new BoundedEviction())) : eviction
          : Optional.empty();
    }
    @Override public Optional<FixedExpiration<K, V>> expireAfterAccess() {
      if (!cache.expiresAfterAccess()) {
        return Optional.empty();
      }
      return (afterAccess == null)
          ? (afterAccess = Optional.of(new BoundedExpireAfterAccess()))
          : afterAccess;
    }
    @Override public Optional<FixedExpiration<K, V>> expireAfterWrite() {
      if (!cache.expiresAfterWrite()) {
        return Optional.empty();
      }
      return (afterWrite == null)
          ? (afterWrite = Optional.of(new BoundedExpireAfterWrite()))
          : afterWrite;
    }
    @Override public Optional<VarExpiration<K, V>> expireVariably() {
      if (!cache.expiresVariable()) {
        return Optional.empty();
      }
      return (variable == null)
          ? (variable = Optional.of(new BoundedVarExpiration()))
          : variable;
    }
    @Override public Optional<FixedRefresh<K, V>> refreshAfterWrite() {
      if (!cache.refreshAfterWrite()) {
        return Optional.empty();
      }
      return (refreshes == null)
          ? (refreshes = Optional.of(new BoundedRefreshAfterWrite()))
          : refreshes;
    }

    final class BoundedEviction implements Eviction<K, V> {
      @Override public boolean isWeighted() {
        return isWeighted;
      }
      @Override public OptionalInt weightOf(K key) {
        requireNonNull(key);
        if (!isWeighted) {
          return OptionalInt.empty();
        }
        Node<K, V> node = cache.data.get(cache.nodeFactory.newLookupKey(key));
        if (node == null) {
          return OptionalInt.empty();
        }
        synchronized (node) {
          return OptionalInt.of(node.getWeight());
        }
      }
      @Override public OptionalLong weightedSize() {
        if (cache.evicts() && isWeighted()) {
          cache.evictionLock.lock();
          try {
            return OptionalLong.of(Math.max(0, cache.weightedSize()));
          } finally {
            cache.evictionLock.unlock();
          }
        }
        return OptionalLong.empty();
      }
      @Override public long getMaximum() {
        cache.evictionLock.lock();
        try {
          return cache.maximum();
        } finally {
          cache.evictionLock.unlock();
        }
      }
      @Override public void setMaximum(long maximum) {
        cache.evictionLock.lock();
        try {
          cache.setMaximumSize(maximum);
          cache.maintenance(/* ignored */ null);
        } finally {
          cache.evictionLock.unlock();
        }
      }
      @Override public Map<K, V> coldest(int limit) {
        return cache.evictionOrder(limit, transformer, /* hottest */ false);
      }
      @Override public Map<K, V> hottest(int limit) {
        return cache.evictionOrder(limit, transformer, /* hottest */ true);
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedExpireAfterAccess implements FixedExpiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.expirationTicker().read() - node.getAccessTime();
        return (age > cache.expiresAfterAccessNanos())
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.expiresAfterAccessNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        requireArgument(duration >= 0);
        cache.setExpiresAfterAccessNanos(unit.toNanos(duration));
        cache.scheduleAfterWrite();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.expireAfterAccessOrder(limit, transformer, /* oldest */ true);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.expireAfterAccessOrder(limit, transformer, /* oldest */ false);
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedExpireAfterWrite implements FixedExpiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.expirationTicker().read() - node.getWriteTime();
        return (age > cache.expiresAfterWriteNanos())
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.expiresAfterWriteNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        requireArgument(duration >= 0);
        cache.setExpiresAfterWriteNanos(unit.toNanos(duration));
        cache.scheduleAfterWrite();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.expireAfterWriteOrder(limit, transformer, /* oldest */ true);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.expireAfterWriteOrder(limit, transformer, /* oldest */ false);
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedVarExpiration implements VarExpiration<K, V> {
      @Override public OptionalLong getExpiresAfter(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long duration = node.getVariableTime() - cache.expirationTicker().read();
        return (duration <= 0)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(duration, TimeUnit.NANOSECONDS));
      }
      @Override public void setExpiresAfter(K key, long duration, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        requireArgument(duration >= 0);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<K, V> node = cache.data.get(lookupKey);
        if (node != null) {
          long now;
          long durationNanos = TimeUnit.NANOSECONDS.convert(duration, unit);
          synchronized (node) {
            now = cache.expirationTicker().read();
            node.setVariableTime(now + Math.min(durationNanos, MAXIMUM_EXPIRY));
          }
          cache.afterRead(node, now, /* recordHit */ false);
        }
      }
      @Override public @Nullable V put(K key, V value, long duration, TimeUnit unit) {
        return cache.isAsync
            ? putAsync(key, value, duration, unit, /* onlyIfAbsent */ false)
            : putSync(key, value, duration, unit, /* onlyIfAbsent */ false);
      }
      @Override public @Nullable V putIfAbsent(K key, V value, long duration, TimeUnit unit) {
        return cache.isAsync
            ? putAsync(key, value, duration, unit, /* onlyIfAbsent */ true)
            : putSync(key, value, duration, unit, /* onlyIfAbsent */ true);
      }
      @Nullable V putSync(K key, V value, long duration, TimeUnit unit, boolean onlyIfAbsent) {
        requireNonNull(unit);
        requireNonNull(value);
        requireArgument(duration >= 0);
        var expiry = new FixedExpiry<K, V>(duration, unit);
        return cache.put(key, value, expiry, onlyIfAbsent);
      }
      @SuppressWarnings("unchecked")
      @Nullable V putAsync(K key, V value, long duration, TimeUnit unit, boolean onlyIfAbsent) {
        requireNonNull(unit);
        requireNonNull(value);
        requireArgument(duration >= 0);
        var expiry = (Expiry<K, V>) new AsyncExpiry<>(new FixedExpiry<>(duration, unit));
        V asyncValue = (V) CompletableFuture.completedFuture(value);

        if (!onlyIfAbsent) {
          var oldValueFuture = (CompletableFuture<V>) cache.put(
              key, asyncValue, expiry, onlyIfAbsent);
          return Async.getWhenSuccessful(oldValueFuture);
        }

        for (;;) {
          var priorFuture = (CompletableFuture<V>) cache.getIfPresent(key, /* recordStats */ false);
          if (priorFuture != null) {
            if (!priorFuture.isDone()) {
              Async.getWhenSuccessful(priorFuture);
              continue;
            }

            V prior = Async.getWhenSuccessful(priorFuture);
            if (prior != null) {
              return prior;
            }
          }

          boolean[] added = { false };
          var computed = (CompletableFuture<V>) cache.compute(key, (k, oldValue) -> {
            var oldValueFuture = (CompletableFuture<V>) oldValue;
            added[0] = (oldValueFuture == null)
                || (oldValueFuture.isDone() && (Async.getIfReady(oldValueFuture) == null));
            return added[0] ? asyncValue : oldValue;
          }, expiry, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

          if (added[0]) {
            return null;
          } else {
            V prior = Async.getWhenSuccessful(computed);
            if (prior != null) {
              return prior;
            }
          }
        }
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.variableSnapshot(/* ascending */ true, limit, transformer);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.variableSnapshot(/* ascending */ false, limit, transformer);
      }
    }

    static final class FixedExpiry<K, V> implements Expiry<K, V> {
      final long duration;
      final TimeUnit unit;

      FixedExpiry(long duration, TimeUnit unit) {
        this.duration = duration;
        this.unit = unit;
      }
      @Override public long expireAfterCreate(K key, V value, long currentTime) {
        return unit.toNanos(duration);
      }
      @Override public long expireAfterUpdate(
          K key, V value, long currentTime, long currentDuration) {
        return unit.toNanos(duration);
      }
      @Override public long expireAfterRead(
          K key, V value, long currentTime, long currentDuration) {
        return currentDuration;
      }
    }

    @SuppressWarnings("PreferJavaTimeOverload")
    final class BoundedRefreshAfterWrite implements FixedRefresh<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        requireNonNull(key);
        requireNonNull(unit);
        Object lookupKey = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(lookupKey);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.expirationTicker().read() - node.getWriteTime();
        return (age > cache.refreshAfterWriteNanos())
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
      }
      @Override public long getRefreshesAfter(TimeUnit unit) {
        return unit.convert(cache.refreshAfterWriteNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setRefreshesAfter(long duration, TimeUnit unit) {
        requireArgument(duration >= 0);
        cache.setRefreshAfterWriteNanos(unit.toNanos(duration));
        cache.scheduleAfterWrite();
      }
    }
  }

  /* --------------- Loading Cache --------------- */

  static final class BoundedLocalLoadingCache<K, V>
      extends BoundedLocalManualCache<K, V> implements LocalLoadingCache<K, V> {
    private static final long serialVersionUID = 1;

    final Function<K, V> mappingFunction;
    @Nullable final Function<Set<? extends K>, Map<K, V>> bulkMappingFunction;

    BoundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder, loader);
      requireNonNull(loader);
      mappingFunction = newMappingFunction(loader);
      bulkMappingFunction = newBulkMappingFunction(loader);
    }

    @Override
    @SuppressWarnings("NullAway")
    public CacheLoader<? super K, V> cacheLoader() {
      return cache.cacheLoader;
    }

    @Override
    public Function<K, V> mappingFunction() {
      return mappingFunction;
    }

    @Override
    public @Nullable Function<Set<? extends K>, Map<K, V>> bulkMappingFunction() {
      return bulkMappingFunction;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    @Override
    Object writeReplace() {
      @SuppressWarnings("unchecked")
      SerializationProxy<K, V> proxy = (SerializationProxy<K, V>) super.writeReplace();
      if (cache.refreshAfterWrite()) {
        proxy.refreshAfterWriteNanos = cache.refreshAfterWriteNanos();
      }
      proxy.loader = cache.cacheLoader;
      return proxy;
    }
  }

  /* --------------- Async Cache --------------- */

  static final class BoundedLocalAsyncCache<K, V> implements LocalAsyncCache<K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, CompletableFuture<V>> cache;
    final boolean isWeighted;

    @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
    @Nullable CacheView<K, V> cacheView;
    @Nullable Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    BoundedLocalAsyncCache(Caffeine<K, V> builder) {
      cache = (BoundedLocalCache<K, CompletableFuture<V>>) LocalCacheFactory
          .newBoundedLocalCache(builder, /* loader */ null, /* async */ true);
      isWeighted = builder.isWeighted();
    }

    @Override
    public BoundedLocalCache<K, CompletableFuture<V>> cache() {
      return cache;
    }

    @Override
    public ConcurrentMap<K, CompletableFuture<V>> asMap() {
      return (mapView == null) ? (mapView = new AsyncAsMapView<>(this)) : mapView;
    }

    @Override
    public Cache<K, V> synchronous() {
      return (cacheView == null) ? (cacheView = new CacheView<>(this)) : cacheView;
    }

    @Override
    public Policy<K, V> policy() {
      if (policy == null) {
        @SuppressWarnings("unchecked")
        BoundedLocalCache<K, V> castCache = (BoundedLocalCache<K, V>) cache;
        Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
        @SuppressWarnings("unchecked")
        Function<V, V> castTransformer = (Function<V, V>) transformer;
        policy = new BoundedPolicy<>(castCache, castTransformer, isWeighted);
      }
      return policy;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      SerializationProxy<K, V> proxy = makeSerializationProxy(cache, isWeighted);
      if (cache.refreshAfterWrite()) {
        proxy.refreshAfterWriteNanos = cache.refreshAfterWriteNanos();
      }
      proxy.async = true;
      return proxy;
    }
  }

  /* --------------- Async Loading Cache --------------- */

  static final class BoundedLocalAsyncLoadingCache<K, V>
      extends LocalAsyncLoadingCache<K, V> implements Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, CompletableFuture<V>> cache;
    final boolean isWeighted;

    @Nullable ConcurrentMap<K, CompletableFuture<V>> mapView;
    @Nullable Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    BoundedLocalAsyncLoadingCache(Caffeine<K, V> builder, AsyncCacheLoader<? super K, V> loader) {
      super(loader);
      isWeighted = builder.isWeighted();
      cache = (BoundedLocalCache<K, CompletableFuture<V>>) LocalCacheFactory
          .newBoundedLocalCache(builder, new AsyncLoader<>(loader, builder), /* async */ true);
    }

    @Override
    public BoundedLocalCache<K, CompletableFuture<V>> cache() {
      return cache;
    }

    @Override
    public ConcurrentMap<K, CompletableFuture<V>> asMap() {
      return (mapView == null) ? (mapView = new AsyncAsMapView<>(this)) : mapView;
    }

    @Override
    public Policy<K, V> policy() {
      if (policy == null) {
        @SuppressWarnings("unchecked")
        BoundedLocalCache<K, V> castCache = (BoundedLocalCache<K, V>) cache;
        Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
        @SuppressWarnings("unchecked")
        Function<V, V> castTransformer = (Function<V, V>) transformer;
        policy = new BoundedPolicy<>(castCache, castTransformer, isWeighted);
      }
      return policy;
    }

    @SuppressWarnings("UnusedVariable")
    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      SerializationProxy<K, V> proxy = makeSerializationProxy(cache, isWeighted);
      if (cache.refreshAfterWrite()) {
        proxy.refreshAfterWriteNanos = cache.refreshAfterWriteNanos();
      }
      proxy.loader = loader;
      proxy.async = true;
      return proxy;
    }

    static final class AsyncLoader<K, V> implements CacheLoader<K, V> {
      final AsyncCacheLoader<? super K, V> loader;
      final Executor executor;

      AsyncLoader(AsyncCacheLoader<? super K, V> loader, Caffeine<?, ?> builder) {
        this.executor = requireNonNull(builder.getExecutor());
        this.loader = requireNonNull(loader);
      }

      @Override public V load(K key) throws Exception {
        @SuppressWarnings("unchecked")
        V newValue = (V) loader.asyncLoad(key, executor);
        return newValue;
      }
      @Override public V reload(K key, V oldValue) throws Exception {
        @SuppressWarnings("unchecked")
        V newValue = (V) loader.asyncReload(key, oldValue, executor);
        return newValue;
      }
      @Override public CompletableFuture<? extends V> asyncReload(
          K key, V oldValue, Executor executor) throws Exception {
        return loader.asyncReload(key, oldValue, executor);
      }
    }
  }
}

/** The namespace for field padding through inheritance. */
final class BLCHeader {

  abstract static class PadDrainStatus<K, V> extends AbstractMap<K, V> {
    byte p000, p001, p002, p003, p004, p005, p006, p007;
    byte p008, p009, p010, p011, p012, p013, p014, p015;
    byte p016, p017, p018, p019, p020, p021, p022, p023;
    byte p024, p025, p026, p027, p028, p029, p030, p031;
    byte p032, p033, p034, p035, p036, p037, p038, p039;
    byte p040, p041, p042, p043, p044, p045, p046, p047;
    byte p048, p049, p050, p051, p052, p053, p054, p055;
    byte p056, p057, p058, p059, p060, p061, p062, p063;
    byte p064, p065, p066, p067, p068, p069, p070, p071;
    byte p072, p073, p074, p075, p076, p077, p078, p079;
    byte p080, p081, p082, p083, p084, p085, p086, p087;
    byte p088, p089, p090, p091, p092, p093, p094, p095;
    byte p096, p097, p098, p099, p100, p101, p102, p103;
    byte p104, p105, p106, p107, p108, p109, p110, p111;
    byte p112, p113, p114, p115, p116, p117, p118, p119;
  }

  /** Enforces a memory layout to avoid false sharing by padding the drain status. */
  abstract static class DrainStatusRef<K, V> extends PadDrainStatus<K, V> {
    static final VarHandle DRAIN_STATUS;

    /** A drain is not taking place. */
    static final int IDLE = 0;
    /** A drain is required due to a pending write modification. */
    static final int REQUIRED = 1;
    /** A drain is in progress and will transition to idle. */
    static final int PROCESSING_TO_IDLE = 2;
    /** A drain is in progress and will transition to required. */
    static final int PROCESSING_TO_REQUIRED = 3;

    /** The draining status of the buffers. */
    volatile int drainStatus = IDLE;

    /**
     * Returns whether maintenance work is needed.
     *
     * @param delayable if draining the read buffer can be delayed
     */
    boolean shouldDrainBuffers(boolean delayable) {
      switch (drainStatus()) {
        case IDLE:
          return !delayable;
        case REQUIRED:
          return true;
        case PROCESSING_TO_IDLE:
        case PROCESSING_TO_REQUIRED:
          return false;
        default:
          throw new IllegalStateException();
      }
    }

    int drainStatus() {
      return (int) DRAIN_STATUS.get(this);
    }

    void lazySetDrainStatus(int drainStatus) {
      DRAIN_STATUS.setOpaque(this, drainStatus);
    }

    boolean casDrainStatus(int expect, int update) {
      return DRAIN_STATUS.compareAndSet(this, expect, update);
    }

    static {
      try {
        DRAIN_STATUS = MethodHandles.lookup()
            .findVarHandle(DrainStatusRef.class, "drainStatus", int.class);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
