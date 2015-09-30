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

import static com.github.benmanes.caffeine.cache.Caffeine.requireState;
import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnegative;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.github.benmanes.caffeine.cache.LinkedDeque.PeekingIterator;
import com.github.benmanes.caffeine.cache.References.InternalReference;
import com.github.benmanes.caffeine.cache.stats.DisabledStatsCounter;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.benmanes.caffeine.cache.tracing.Tracer;
import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * An in-memory cache implementation that supports full concurrency of retrievals, a high expected
 * concurrency for updates, and multiple ways to bound the cache.
 * <p>
 * This class is abstract and code generated subclasses provide the complete implementation for a
 * particular configuration. This is to ensure that only the fields and execution paths necessary
 * for a given configuration are used.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
@ThreadSafe
abstract class BoundedLocalCache<K, V> extends BLCHeader.DrainStatusRef<K, V>
    implements LocalCache<K, V> {

  /*
   * This class performs a best-effort bounding of a ConcurrentHashMap using a page-replacement
   * algorithm to determine which entries to evict when the capacity is exceeded.
   *
   * The page replacement algorithm's data structures are kept eventually consistent with the map.
   * An update to the map and recording of reads may not be immediately reflected on the algorithm's
   * data structures. These structures are guarded by a lock and operations are applied in batches
   * to avoid lock contention. The penalty of applying the batches is spread across threads so that
   * the amortized cost is slightly higher than performing just the ConcurrentHashMap operation.
   *
   * A memento of the reads and writes that were performed on the map are recorded in buffers. These
   * buffers are drained at the first opportunity after a write or when a read buffer is full. The
   * reads are offered in a buffer that will reject additions if contented on or if it is full and
   * a draining process is required. Due to the concurrent nature of the read and write operations a
   * strict policy ordering is not possible, but is observably strict when single threaded.
   *
   * Due to a lack of a strict ordering guarantee, a task can be executed out-of-order, such as a
   * removal followed by its addition. The state of the entry is encoded using the key field to
   * avoid additional memory. An entry is "alive" if it is in both the hash table and the page
   * replacement policy. It is "retired" if it is not in the hash table and is pending removal from
   * the page replacement policy. Finally an entry transitions to the "dead" state when it is not in
   * the hash table nor the page replacement policy. Both the retired and dead states are
   * represented by a sentinel key that should not be used for map lookups.
   *
   * The maximum size policy is implemented using the Least Recently Used page replacement algorithm
   * due to its simplicity, high hit rate, and ability to be implemented with O(1) time complexity.
   * The expiration policy is implemented with O(1) time complexity by sharing the access-order
   * queue (with the LRU policy) for a time-to-idle setting and using a write-order queue for a
   * time-to-live policy.
   */

  static final Logger logger = Logger.getLogger(BoundedLocalCache.class.getName());

  /** The maximum weighted capacity of the map. */
  static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;
  /** The percent of the maximum weighted capacity dedicated to the main space. */
  static final double PERCENT_MAIN = 0.99f;

  final ConcurrentHashMap<Object, Node<K, V>> data;
  final Consumer<Node<K, V>> accessPolicy;
  final Buffer<Node<K, V>> readBuffer;
  final Runnable drainBuffersTask;
  final CacheWriter<K, V> writer;
  final NodeFactory nodeFactory;
  final Weigher<K, V> weigher;
  final Lock evictionLock;
  final boolean isAsync;
  final long id;

  // The collection views
  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  /** Creates an instance based on the builder's configuration. */
  protected BoundedLocalCache(Caffeine<K, V> builder, boolean isAsync) {
    this.isAsync = isAsync;
    writer = builder.getCacheWriter();
    weigher = builder.getWeigher(isAsync);
    id = tracer().register(builder.name());
    data = new ConcurrentHashMap<>(builder.getInitialCapacity());
    evictionLock = (builder.getExecutor() instanceof ForkJoinPool)
        ? new NonReentrantLock()
        : new ReentrantLock();
    nodeFactory = NodeFactory.getFactory(builder.isStrongKeys(), builder.isWeakKeys(),
        builder.isStrongValues(), builder.isWeakValues(), builder.isSoftValues(),
        builder.expiresAfterAccess(), builder.expiresAfterWrite(), builder.refreshes(),
        builder.evicts(), (isAsync && builder.evicts()) || builder.isWeighted());
    readBuffer = evicts() || collectKeys() || collectValues() || expiresAfterAccess()
        ? new BoundedBuffer<>()
        : Buffer.disabled();
    accessPolicy = (evicts() || expiresAfterAccess()) ? this::onAccess : e -> {};
    drainBuffersTask = this::cleanUp;

    if (evicts()) {
      setMaximum(builder.getMaximumWeight());
    }
  }

  /* ---------------- Shared -------------- */

  /** Returns if the node's value is currently being computed, asynchronously. */
  final boolean isComputingAsync(Node<?, ?> node) {
    return isAsync && !Async.isReady((CompletableFuture<?>) node.getValue());
  }

  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderEdenDeque() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderMainDeque() {
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

  protected Queue<Runnable> writeQueue() {
    throw new UnsupportedOperationException();
  }

  protected CacheLoader<? super K, V> cacheLoader() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Executor executor() {
    return ForkJoinPool.commonPool();
  }

  /* ---------------- Stats Support -------------- */

  @Override
  public boolean isRecordingStats() {
    return false;
  }

  @Override
  public StatsCounter statsCounter() {
    return DisabledStatsCounter.INSTANCE;
  }

  @Override
  public Ticker statsTicker() {
    return Ticker.disabledTicker();
  }

  /* ---------------- Removal Listener Support -------------- */

  @Override
  public RemovalListener<K, V> removalListener() {
    return null;
  }

  /** Returns whether this cache notifies when an entry is removed. */
  protected boolean hasRemovalListener() {
    return false;
  }

  /** Asynchronously sends a removal notification to the listener. */
  void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    requireState(hasRemovalListener(), "Notification should be guarded with a check");
    try {
      executor().execute(() -> {
        try {
          removalListener().onRemoval(new RemovalNotification<K, V>(key, value, cause));
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown by removal listener", t);
        }
      });
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Exception thrown when submitting removal listener", t);
    }
  }

  /* ---------------- Reference Support -------------- */

  /** Returns if the keys are weak reference garbage collected. */
  protected boolean collectKeys() {
    return false;
  }

  /** Returns if the values are weak or soft reference garbage collected. */
  protected boolean collectValues() {
    return false;
  }

  @Nullable
  protected ReferenceQueue<K> keyReferenceQueue() {
    return null;
  }

  @Nullable
  protected ReferenceQueue<V> valueReferenceQueue() {
    return null;
  }

  /* ---------------- Expiration Support -------------- */

  /** Returns if the cache expires entries after an access time threshold. */
  protected boolean expiresAfterAccess() {
    return false;
  }

  /** How long after the last access to an entry the map will retain that entry. */
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

  /** How long after the last write to an entry the map will retain that entry. */
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

  /** How long after the last write an entry becomes a candidate for refresh. */
  protected long refreshAfterWriteNanos() {
    throw new UnsupportedOperationException();
  }

  protected void setRefreshAfterWriteNanos(long refreshAfterWriteNanos) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Ticker expirationTicker() {
    return Ticker.disabledTicker();
  }

  /* ---------------- Eviction Support -------------- */

  /** Returns if the cache evicts entries due to a maximum size or weight threshold. */
  protected boolean evicts() {
    return false;
  }

  protected FrequencySketch<K> frequencySketch() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the maximum distance that an entry can move from the least-recently-used position in
   * the Main queue before recording its access is required. An entry falling within the interval is
   * eligible for a fast-path read by not updating its access history.
   */
  @Nonnegative
  protected int moveDistance() {
    return 0;
  }

  @GuardedBy("evictionLock")
  protected void setMoveDistance(@Nonnegative int distance) {
    throw new UnsupportedOperationException();
  }

  /***
   * Returns the movement count in the Main queue. The returned value equals the count held by the
   * least-recently-used entry in the queue.
   */
  @Nonnegative
  protected int moveCount() {
    return 0;
  }

  @GuardedBy("evictionLock")
  protected void setMoveCount(@Nonnegative int count) {
    throw new UnsupportedOperationException();
  }

  /** Returns the maximum weighted size of the main space. */
  protected long maximum() {
    throw new UnsupportedOperationException();
  }

  /** Returns the maximum weighted size of the eden space. */
  protected long edenMaximum() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock") // must write under lock
  protected void lazySetMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock") // must write under lock
  protected void lazySetEdenMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum weighted size of the cache. The caller may need to perform a maintenance cycle
   * to eagerly evicts entries until the cache shrinks to the appropriate size.
   */
  @GuardedBy("evictionLock")
  void setMaximum(long maximum) {
    Caffeine.requireArgument(maximum >= 0);

    long max = Math.min(maximum, MAXIMUM_CAPACITY);
    long eden = max - (long) (max * PERCENT_MAIN);

    lazySetMaximum(max);
    lazySetEdenMaximum(eden);
  }

  /** Returns the combined weight of the values in the cache. */
  long adjustedWeightedSize() {
    return Math.max(0, weightedSize());
  }

  /** Returns the uncorrected combined weight of the values in the cache. */
  protected long weightedSize() {
    throw new UnsupportedOperationException();
  }

  /** Returns the uncorrected combined weight of the values in the eden space. */
  protected long edenWeightedSize() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock") // must write under lock
  protected void lazySetWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock") // must write under lock
  protected void lazySetEdenWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  /** Returns the incremented move count. */
  @GuardedBy("evictionLock")
  int incrementAndGetMoveCount() {
    int next = Math.max(1, moveCount() + 1);
    setMoveCount(next);
    return next;
  }

  /**
   * Attempts to evict entries from the map if it exceeds the maximum capacity. If the eviction
   * fails due to a concurrent removal of the victim, that removal may cancel out the addition that
   * triggered this eviction. The victim is eagerly unlinked before the removal task so that if an
   * eviction is still required then a new victim will be chosen for removal.
   */
  @GuardedBy("evictionLock")
  void evictEntries() {
    if (!evicts()) {
      return;
    }
    evictFromEden();
    evictFromMain();
  }

  @GuardedBy("evictionLock")
  void evictFromEden() {
    Node<K, V> node = accessOrderEdenDeque().peek();
    while (edenWeightedSize() > edenMaximum()) {

      // If weighted values are used, then the pending operations will adjust the size to reflect
      // the correct weight
      if (node == null) {
        return;
      }

      Node<K, V> next = node.getNextInAccessOrder();
      if (node.getWeight() != 0) {
        node.setMoveCount(incrementAndGetMoveCount());
        accessOrderEdenDeque().remove(node);
        accessOrderMainDeque().add(node);

        int weight = node.getWeight();
        lazySetEdenWeightedSize(edenWeightedSize() - weight);
      }
      node = next;
    }
  }

  @GuardedBy("evictionLock")
  void evictFromMain() {
    while (weightedSize() > maximum()) {
      Node<K, V> victim = accessOrderMainDeque().peekFirst();
      Node<K, V> candidate = accessOrderMainDeque().peekLast();

      // If weighted values are used, then the pending operations will adjust the size to reflect
      // the correct weight
      if (victim == null) {
        return;
      }

      K victimKey = victim.getKey();
      K candidateKey = candidate.getKey();
      if (victimKey == null) {
        evictEntry(victim, RemovalCause.COLLECTED, 0L);
      } else if (candidateKey == null) {
        evictEntry(candidate, RemovalCause.COLLECTED, 0L);
      } else {
        int victimFreq = frequencySketch().frequency(victimKey);
        int candidateFreq = frequencySketch().frequency(candidateKey);
        Node<K, V> evict = (candidateFreq > victimFreq) ? victim : candidate;
        evictEntry(evict, RemovalCause.SIZE, 0L);
      }
    }
  }

  @GuardedBy("evictionLock")
  void expireEntries() {
    long now = expirationTicker().read();
    expireAfterAccessEntries(now);
    expireAfterWriteEntries(now);
  }

  @GuardedBy("evictionLock")
  void expireAfterAccessEntries(long now) {
    if (expiresAfterAccess()) {
      long expirationTime = now - expiresAfterAccessNanos();
      for (;;) {
        final Node<K, V> node = accessOrderEdenDeque().peekFirst();
        if ((node == null) || (node.getAccessTime() > expirationTime)) {
          break;
        }
        evictEntry(node, RemovalCause.EXPIRED, now);
      }
      if (evicts()) {
        for (;;) {
          final Node<K, V> node = accessOrderMainDeque().peekFirst();
          if ((node == null) || (node.getAccessTime() > expirationTime)) {
            break;
          }
          evictEntry(node, RemovalCause.EXPIRED, now);
        }
      }
    }
  }

  @GuardedBy("evictionLock")
  void expireAfterWriteEntries(long now) {
    if (expiresAfterWrite()) {
      long expirationTime = now - expiresAfterWriteNanos();
      for (;;) {
        final Node<K, V> node = writeOrderDeque().peekFirst();
        if ((node == null) || (node.getWriteTime() > expirationTime)) {
          break;
        }
        evictEntry(node, RemovalCause.EXPIRED, now);
      }
    }
  }

  /** Returns if the entry has expired. */
  boolean hasExpired(Node<K, V> node, long now) {
    if (isComputingAsync(node)) {
      return false;
    }
    return (expiresAfterAccess() && (now - node.getAccessTime() >= expiresAfterAccessNanos()))
        || (expiresAfterWrite() && (now - node.getWriteTime() >= expiresAfterWriteNanos()));
  }

  /**
   * Attempts to evict the entry based on the given removal cause. A removal due to expiration may
   * be ignored if the entry was since updated and is no longer eligible for eviction.
   *
   * @param node the entry to evict
   * @param cause the reason to evict
   * @param now the current time, used only if expiring
   */
  @GuardedBy("evictionLock")
  void evictEntry(Node<K, V> node, RemovalCause cause, long now) {
    K key = node.getKey();
    V value = node.getValue();
    boolean[] removed = new boolean[1];
    boolean[] resurrect = new boolean[1];
    RemovalCause actualCause = (key == null) || (value == null) ? RemovalCause.COLLECTED : cause;

    data.computeIfPresent(node.getKeyReference(), (k, n) -> {
      if (n != node) {
        return n;
      }
      if (actualCause == RemovalCause.EXPIRED) {
        boolean expired = false;
        if (expiresAfterAccess()) {
          long expirationTime = now - expiresAfterAccessNanos();
          expired |= n.getAccessTime() <= expirationTime;
        }
        if (expiresAfterWrite()) {
          long expirationTime = now - expiresAfterWriteNanos();
          expired |= n.getWriteTime() <= expirationTime;
        }
        if (!expired) {
          resurrect[0] = true;
          return n;
        }
      }
      writer.delete(key, value, actualCause);
      removed[0] = true;
      return null;
    });

    if (resurrect[0]) {
      // The entry is no longer expired and will be reordered in the next maintenance cycle
      return;
    }

    makeDead(node);
    if (node.isEden() && (evicts() || expiresAfterAccess())) {
      accessOrderEdenDeque().remove(node);
    } else if (evicts()) {
      accessOrderMainDeque().remove(node);
    }
    if (expiresAfterWrite()) {
      writeOrderDeque().remove(node);
    }

    if (removed[0]) {
      statsCounter().recordEviction();
      if (hasRemovalListener()) {
        // Notify the listener only if the entry was evicted. This must be performed as the last
        // step during eviction to safe guard against the executor rejecting the notification task.
        notifyRemoval(key, value, actualCause);
      }
    }
  }

  /**
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   * @param now the current expiration time, in nanoseconds
   * @param recordHit if the hit count should be incremented
   */
  void afterRead(Node<K, V> node, long now, boolean recordHit) {
    if (recordHit) {
      statsCounter().recordHits(1);
    }
    node.setAccessTime(now);

    boolean delayable = (readBuffer.offer(node) != Buffer.FULL);
    if (shouldDrainBuffers(delayable)) {
      scheduleDrainBuffers();
    }
    refreshIfNeeded(node, now);
  }

  /**
   * Asynchronously refreshes the entry if eligible.
   *
   * @param node the entry in the cache to refresh
   * @param now the current time, in nanoseconds
   */
  void refreshIfNeeded(Node<K, V> node, long now) {
    if (!refreshAfterWrite()) {
      return;
    }
    long writeTime = node.getWriteTime();
    if (((now - writeTime) > refreshAfterWriteNanos()) && node.casWriteTime(writeTime, now)) {
      try {
        executor().execute(() -> {
          K key = node.getKey();
          if ((key != null) && node.isAlive()) {
            try {
              computeIfPresent(key, cacheLoader()::reload);
            } catch (Throwable t) {
              logger.log(Level.WARNING, "Exception thrown during reload", t);
            }
          }
        });
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Exception thrown when submitting refresh task", t);
      }
    }
  }

  /**
   * Performs the post-processing work required after a write.
   *
   * @param node the node that was written to
   * @param task the pending operation to be applied
   * @param now the current expiration time, in nanoseconds
   */
  void afterWrite(@Nullable Node<K, V> node, Runnable task, long now) {
    if (node != null) {
      node.setAccessTime(now);
      node.setWriteTime(now);
    }
    if (buffersWrites()) {
      writeQueue().add(task);
    }
    lazySetDrainStatus(REQUIRED);
    scheduleDrainBuffers();
  }

  /**
   * Attempts to schedule an asynchronous task to apply the pending operations to the page
   * replacement policy. If the executor rejects the task then it is run directly.
   */
  void scheduleDrainBuffers() {
    if (evictionLock.tryLock()) {
      try {
        lazySetDrainStatus(PROCESSING);
        executor().execute(drainBuffersTask);
      } catch (Throwable t) {
        cleanUp();
        logger.log(Level.WARNING, "Exception thrown when submitting maintenance task", t);
      } finally {
        evictionLock.unlock();
      }
    }
  }

  @Override
  public void cleanUp() {
    evictionLock.lock();
    try {
      lazySetDrainStatus(PROCESSING);
      maintenance();
    } finally {
      casDrainStatus(PROCESSING, IDLE);
      evictionLock.unlock();
    }
  }

  /**
   * Performs the pending maintenance work. The read buffer, write buffer, and reference queues are
   * drained, followed by expiration, and size-based eviction.
   */
  @GuardedBy("evictionLock")
  void maintenance() {
    drainReadBuffer();
    drainWriteBuffer();
    drainKeyReferences();
    drainValueReferences();

    expireEntries();
    evictEntries();
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

  /** Drains the read buffers */
  @GuardedBy("evictionLock")
  void drainReadBuffer() {
    readBuffer.drainTo(accessPolicy);
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
      if (node.isEden()) {
        reorder(accessOrderEdenDeque(), node);
      } else {
        reorder(accessOrderMainDeque(), node);
        node.setMoveCount(incrementAndGetMoveCount());
      }
    } else if (expiresAfterAccess()) {
      reorder(accessOrderEdenDeque(), node);
    }
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
    Runnable task;
    while ((task = writeQueue().poll()) != null) {
      task.run();
    }
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
        if (node.isEden()) {
          lazySetEdenWeightedSize(edenWeightedSize() - node.getWeight());
        }
        lazySetWeightedSize(weightedSize() - node.getWeight());
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
    public void run() {
      if (evicts()) {
        lazySetWeightedSize(weightedSize() + weight);
        lazySetEdenWeightedSize(edenWeightedSize() + weight);
        long size = (weigher == Weigher.singleton()) ? adjustedWeightedSize() : data.mappingCount();
        frequencySketch().ensureCapacity(size);
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
        if (evicts() || expiresAfterAccess()) {
          accessOrderEdenDeque().add(node);
        }
      }

      // Ensure that in-flight async computation cannot expire
      if (isComputingAsync(node)) {
        node.setAccessTime(Long.MAX_VALUE);
        node.setWriteTime(Long.MAX_VALUE);
        ((CompletableFuture<?>) node.getValue()).thenRun(() -> {
          long now = expirationTicker().read();
          node.setAccessTime(now);
          node.setWriteTime(now);
        });
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
      if (node.isEden() && (evicts() || expiresAfterAccess())) {
        accessOrderEdenDeque().remove(node);
      } else if (evicts()) {
        accessOrderMainDeque().remove(node);
      }
      if (expiresAfterWrite()) {
        writeOrderDeque().remove(node);
      }
      makeDead(node);
    }
  }

  /** Updates the weighted size and evicts an entry on overflow. */
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
        if (node.isEden()) {
          lazySetEdenWeightedSize(edenWeightedSize() + weightDifference);
        }
        lazySetWeightedSize(weightedSize() + weightDifference);
      }
      if (evicts() || expiresAfterAccess()) {
        onAccess(node);
      }
      if (expiresAfterWrite()) {
        reorder(writeOrderDeque(), node);
      }
    }
  }

  /* ---------------- Concurrent Map Support -------------- */

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public int size() {
    return data.size();
  }

  /**
   * Returns the number of mappings. The value returned is an estimate; the actual count may differ
   * if there are concurrent insertions or removals.
   *
   * @return the number of mappings
   */
  @Override
  public long estimatedSize() {
    return data.mappingCount();
  }

  @Override
  public void clear() {
    long now = expirationTicker().read();

    evictionLock.lock();
    try {
      // Apply all pending writes
      Runnable task;
      while (buffersWrites() && (task = writeQueue().poll()) != null) {
        task.run();
      }

      // Discard all entries
      if (evicts() || expiresAfterAccess()) {
        removeNodes(accessOrderEdenDeque(), now);
      }
      if (evicts()) {
        removeNodes(accessOrderMainDeque(), now);
      }
      if (expiresAfterWrite()) {
        removeNodes(writeOrderDeque(), now);
      }
      data.values().forEach(node -> removeNode(node, now));

      // Discard all pending reads
      readBuffer.drainTo(e -> {});
    } finally {
      evictionLock.unlock();
    }
  }

  @GuardedBy("evictionLock")
  void removeNodes(LinkedDeque<Node<K, V>> deque, long now) {
    Node<K, V> node;
    while ((node = deque.peek()) != null) {
      removeNode(node, now);
      deque.poll();
    }
  }

  @GuardedBy("evictionLock")
  void removeNode(Node<K, V> node, long now) {
    K key = node.getKey();
    V value = node.getValue();
    boolean[] removed = new boolean[1];
    RemovalCause cause;
    if ((key == null) || (value == null)) {
      cause = RemovalCause.COLLECTED;
    } else if (hasExpired(node, now)) {
      cause = RemovalCause.EXPIRED;
    } else {
      cause = RemovalCause.EXPLICIT;
    }

    data.computeIfPresent(node.getKeyReference(), (k, n) -> {
      if (n == node) {
        writer.delete(key, value, cause);
        removed[0] = true;
        return null;
      }
      return n;
    });

    if (removed[0]) {
      if (cause == RemovalCause.EXPLICIT) {
        tracer().recordDelete(id, node.getKeyReference());
      }
      if (hasRemovalListener()) {
        notifyRemoval(key, value, cause);
      }
    }

    makeDead(node);
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
  public V get(Object key) {
    return getIfPresent(key, false);
  }

  @Override
  public V getIfPresent(Object key, boolean recordStats) {
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    tracer().recordRead(id, key);
    if (node == null) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      return null;
    }
    long now = expirationTicker().read();
    if (hasExpired(node, now)) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      scheduleDrainBuffers();
      return null;
    }
    afterRead(node, now, recordStats);
    return node.getValue();
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int misses = 0;
    long now = expirationTicker().read();
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
      tracer().recordRead(id, key);

      if ((node == null) || hasExpired(node, now)) {
        misses++;
        continue;
      }
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      V value = node.getValue();

      if (value != null) {
        result.put(castKey, value);
        // TODO(ben): batch reads to call tryLock once
        afterRead(node, now, true);
      }
    }
    statsCounter().recordMisses(misses);
    return Collections.unmodifiableMap(result);
  }

  @Override
  public V put(K key, V value) {
    return put(key, value, true, false);
  }

  @Override
  public V put(K key, V value, boolean notifyWriter) {
    return put(key, value, notifyWriter, false);
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return put(key, value, true, true);
  }

  /**
   * Adds a node to the list and the data store. If an existing node is found, then its value is
   * updated if allowed.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param notifyWriter if the writer should be notified for an inserted or updated entry
   * @param onlyIfAbsent a write is performed only if the key is not already associated with a value
   * @return the prior value in the data store or null if no mapping was found
   */
  V put(K key, V value, boolean notifyWriter, boolean onlyIfAbsent) {
    requireNonNull(key);
    requireNonNull(value);

    Node<K, V> node = null;
    long now = expirationTicker().read();
    int weight = weigher.weigh(key, value);
    tracer().recordWrite(id, key, weight);

    for (;;) {
      Node<K, V> prior = data.get(nodeFactory.newLookupKey(key));
      if (prior == null) {
        if (node == null) {
          node = nodeFactory.newNode(key, keyReferenceQueue(),
              value, valueReferenceQueue(), weight, now);
        }
        Node<K, V> computed = node;
        prior = data.computeIfAbsent(node.getKeyReference(), k -> {
          if (notifyWriter) {
            writer.write(key, value);
          }
          return computed;
        });
        if (prior == node) {
          afterWrite(node, new AddTask(node, weight), now);
          return null;
        }
      }

      V oldValue;
      int oldWeight;
      boolean expired = false;
      boolean mayUpdate = true;
      synchronized (prior) {
        if (!prior.isAlive()) {
          continue;
        }
        oldValue = prior.getValue();
        oldWeight = prior.getWeight();
        if (oldValue == null) {
          writer.delete(key, null, RemovalCause.COLLECTED);
        } else if (hasExpired(prior, now)) {
          writer.delete(key, oldValue, RemovalCause.EXPIRED);
          expired = true;
        } else if (onlyIfAbsent) {
          mayUpdate = false;
        }

        if (notifyWriter && (expired || (mayUpdate && (value != oldValue)))) {
          writer.write(key, value);
        }
        if (mayUpdate) {
          prior.setValue(value, valueReferenceQueue());
          prior.setWeight(weight);
        }
      }

      if (hasRemovalListener()) {
        if (expired) {
          notifyRemoval(key, oldValue, RemovalCause.EXPIRED);
        } else if (oldValue == null) {
          notifyRemoval(key, oldValue, RemovalCause.COLLECTED);
        } else if (mayUpdate && (value != oldValue)) {
          notifyRemoval(key, oldValue, RemovalCause.REPLACED);
        }
      }

      int weightedDifference = mayUpdate ? (weight - oldWeight) : 0;
      if ((oldValue == null) || (weightedDifference != 0) || expired
          || (!onlyIfAbsent && (oldValue != null) && expiresAfterWrite())) {
        afterWrite(prior, new UpdateTask(prior, weightedDifference), now);
      } else {
        afterRead(prior, now, false);
      }

      return expired ? null : oldValue;
    }
  }

  @Override
  public V remove(Object key) {
    @SuppressWarnings("unchecked")
    K castKey = (K) key;
    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V>[] node = new Node[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    long now = expirationTicker().read();
    RemovalCause[] cause = new RemovalCause[1];

    data.computeIfPresent(nodeFactory.newLookupKey(key), (k, n) -> {
      synchronized (n) {
        oldValue[0] = n.getValue();
        if (oldValue[0] == null) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now)) {
          cause[0] = RemovalCause.EXPIRED;
        } else {
          cause[0] = RemovalCause.EXPLICIT;
        }
        writer.delete(castKey, oldValue[0], cause[0]);
        n.retire();
      }
      node[0] = n;
      return null;
    });

    tracer().recordDelete(id, key);
    if (cause[0] != null) {
      afterWrite(node[0], new RemovalTask(node[0]), now);
      if (hasRemovalListener()) {
        notifyRemoval(castKey, oldValue[0], cause[0]);
      }
    }
    return (cause[0] == RemovalCause.EXPLICIT) ? oldValue[0] : null;
  }

  @Override
  public boolean remove(Object key, Object value) {
    requireNonNull(key);
    tracer().recordDelete(id, key);
    Object keyRef = nodeFactory.newLookupKey(key);
    if ((data.get(keyRef) == null) || (value == null)) {
      return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Node<K, V> removed[] = new Node[1];
    @SuppressWarnings("unchecked")
    K[] oldKey = (K[]) new Object[1];
    @SuppressWarnings("unchecked")
    V[] oldValue = (V[]) new Object[1];
    RemovalCause[] cause = new RemovalCause[1];

    long now = expirationTicker().read();
    data.computeIfPresent(keyRef, (kR, node) -> {
      synchronized (node) {
        oldKey[0] = node.getKey();
        oldValue[0] = node.getValue();
        if (oldKey[0] == null) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(node, now)) {
          cause[0] = RemovalCause.EXPIRED;
        } else if (node.containsValue(value)) {
          cause[0] = RemovalCause.EXPLICIT;
        } else {
          return node;
        }
        writer.delete(oldKey[0], oldValue[0], cause[0]);
        removed[0] = node;
        node.retire();
        return null;
      }
    });

    if (removed[0] == null) {
      return false;
    } else if (hasRemovalListener()) {
      notifyRemoval(oldKey[0], oldValue[0], cause[0]);
    }
    afterWrite(removed[0], new RemovalTask(removed[0]), now);
    return (cause[0] == RemovalCause.EXPLICIT);
  }

  @Override
  public V replace(K key, V value) {
    requireNonNull(key);
    requireNonNull(value);

    final int weight = weigher.weigh(key, value);
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    tracer().recordWrite(id, key, weight);
    if (node == null) {
      return null;
    }

    K oldKey;
    V oldValue;
    int oldWeight;
    long now = expirationTicker().read();
    synchronized (node) {
      if (!node.isAlive()) {
        return null;
      }
      oldKey = node.getKey();
      oldValue = node.getValue();
      oldWeight = node.getWeight();
      if ((oldKey == null) || (oldValue == null) || hasExpired(node, now)) {
        return null;
      }

      if (value != oldValue) {
        writer.write(key, value);
      }
      node.setValue(value, valueReferenceQueue());
      node.setWeight(weight);
    }

    int weightedDifference = (weight - oldWeight);
    if (expiresAfterWrite() || (weightedDifference != 0)) {
      afterWrite(node, new UpdateTask(node, weightedDifference), now);
    } else {
      node.setWriteTime(now);
      afterRead(node, now, false);
    }

    if (hasRemovalListener() && (value != oldValue)) {
      notifyRemoval(oldKey, oldValue, RemovalCause.REPLACED);
    }
    return oldValue;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(key);
    requireNonNull(oldValue);
    requireNonNull(newValue);

    final int weight = weigher.weigh(key, newValue);
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    tracer().recordWrite(id, key, weight);
    if (node == null) {
      return false;
    }

    K oldKey;
    V prevValue;
    int oldWeight;
    long now = expirationTicker().read();
    synchronized (node) {
      if (!node.isAlive()) {
        return false;
      }

      oldKey = node.getKey();
      prevValue = node.getValue();
      oldWeight = node.getWeight();
      if ((oldKey == null) || (prevValue == null) || hasExpired(node, now)
          || !node.containsValue(oldValue)) {
        return false;
      }

      if (newValue != prevValue) {
        writer.write(key, newValue);
      }
      node.setValue(newValue, valueReferenceQueue());
      node.setWeight(weight);
    }

    int weightedDifference = (weight - oldWeight);
    if (expiresAfterWrite() || (weightedDifference != 0)) {
      afterWrite(node, new UpdateTask(node, weightedDifference), now);
    } else {
      node.setWriteTime(now);
      afterRead(node, now, false);
    }

    if (hasRemovalListener() && (oldValue != newValue)) {
      notifyRemoval(oldKey, prevValue, RemovalCause.REPLACED);
    }
    return true;
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean isAsync) {
    requireNonNull(key);
    requireNonNull(mappingFunction);
    long now = expirationTicker().read();

    // An optimistic fast path to avoid unnecessary locking
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node != null) {
      V value = node.getValue();
      if ((value != null) && !hasExpired(node, now)) {
        afterRead(node, now, true);
        return value;
      }
    }
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    return doComputeIfAbsent(key, keyRef, mappingFunction, isAsync, now);
  }

  /** Returns the current value from a computeIfAbsent invocation. */
  V doComputeIfAbsent(K key, Object keyRef, Function<? super K, ? extends V> mappingFunction,
      boolean isAsync, long now) {
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
        newValue[0] = statsAware(mappingFunction, isAsync).apply(key);
        if (newValue[0] == null) {
          return null;
        }
        weight[1] = weigher.weigh(key, newValue[0]);
        return nodeFactory.newNode(key, keyReferenceQueue(),
            newValue[0], valueReferenceQueue(), weight[1], now);
      }

      synchronized (n) {
        nodeKey[0] = n.getKey();
        weight[0] = n.getWeight();
        oldValue[0] = n.getValue();
        if ((nodeKey == null) || (oldValue[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now)) {
          cause[0] = RemovalCause.EXPIRED;
          n.setAccessTime(now);
          n.setWriteTime(now);
        } else {
          return n;
        }

        newValue[0] = statsAware(mappingFunction, isAsync).apply(key);
        writer.delete(nodeKey[0], oldValue[0], cause[0]);
        if (newValue[0] == null) {
          removed[0] = n;
          n.retire();
          return null;
        }
        weight[1] = weigher.weigh(key, newValue[0]);
        n.setValue(newValue[0], valueReferenceQueue());
        n.setWeight(weight[1]);
        return n;
      }
    });

    if (node == null) {
      afterWrite(null, new RemovalTask(removed[0]), now);
      return null;
    }
    if (cause[0] != null) {
      if (hasRemovalListener()) {
        notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
      }
      statsCounter().recordEviction();
    }
    if (newValue[0] == null) {
      tracer().recordRead(id, key);
      afterRead(node, now, true);
      return oldValue[0];
    }
    if ((oldValue[0] == null) && (cause[0] == null)) {
      afterWrite(node, new AddTask(node, weight[1]), now);
    } else {
      int weightedDifference = (weight[1] - weight[0]);
      afterWrite(node, new UpdateTask(node, weightedDifference), now);
    }
    if (Tracer.isEnabled()) {
      tracer().recordWrite(id, key, weigher.weigh(nodeKey[0], newValue[0]));
    }

    return newValue[0];
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    // A optimistic fast path to avoid unnecessary locking
    Object keyRef = nodeFactory.newLookupKey(key);
    Node<K, V> node = data.get(keyRef);
    long now;
    if ((node == null) || (node.getValue() == null)
        || hasExpired(node, (now = expirationTicker().read()))) {
      scheduleDrainBuffers();
      return null;
    }

    boolean computeIfAbsent = false;
    BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
        statsAware(remappingFunction, false, false);
    return remap(key, keyRef, statsAwareRemappingFunction, now, computeIfAbsent);
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss, boolean isAsync) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    long now = expirationTicker().read();
    boolean computeIfAbsent = true;
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    BiFunction<? super K, ? super V, ? extends V> statsAwareRemappingFunction =
        statsAware(remappingFunction, recordMiss, isAsync);
    return remap(key, keyRef, statsAwareRemappingFunction, now, computeIfAbsent);
  }

  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(value);
    requireNonNull(remappingFunction);

    boolean computeIfAbsent = true;
    long now = expirationTicker().read();
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    BiFunction<? super K, ? super V, ? extends V> mergeFunction = (k, oldValue) ->
        (oldValue == null) ? value : statsAware(remappingFunction).apply(oldValue, value);
    return remap(key, keyRef, mergeFunction, now, computeIfAbsent);
  }

  /**
   * Attempts to compute a mapping for the specified key and its current mapped value (or
   * {@code null} if there is no current mapping).
   * <p>
   * An entry that has expired or been reference collected is evicted and the computation continues
   * as if the entry had not been present. This method does not pre-screen and does not wrap the
   * remappingFuntion to be statistics aware.
   *
   * @param key key with which the specified value is to be associated
   * @param remappingFunction the function to compute a value
   * @param now the current time, according to the ticker
   * @param computeIfAbsent if an absent entry can be computed
   * @return the new value associated with the specified key, or null if none
   */
  V remap(K key, Object keyRef, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      long now, boolean computeIfAbsent) {
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
        weight[1] = weigher.weigh(key, newValue[0]);
        tracer().recordWrite(id, key, weight[1]);
        return nodeFactory.newNode(keyRef, newValue[0],
            valueReferenceQueue(), weight[1], now);
      }

      synchronized (n) {
        nodeKey[0] = n.getKey();
        oldValue[0] = n.getValue();
        if ((nodeKey == null) || (oldValue[0] == null)) {
          cause[0] = RemovalCause.COLLECTED;
        } else if (hasExpired(n, now)) {
          cause[0] = RemovalCause.EXPIRED;
        }
        if (cause[0] != null) {
          writer.delete(nodeKey[0], oldValue[0], cause[0]);
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
          }
          removed[0] = n;
          n.retire();
          return null;
        }

        weight[0] = n.getWeight();
        weight[1] = weigher.weigh(key, newValue[0]);
        n.setValue(newValue[0], valueReferenceQueue());
        n.setWeight(weight[1]);
        n.setWriteTime(now);
        n.setAccessTime(now);
        if ((cause[0] == null) && (newValue[0] != oldValue)) {
          cause[0] = RemovalCause.REPLACED;
        }
        return n;
      }
    });

    if (cause[0] != null) {
      if (cause[0].wasEvicted()) {
        statsCounter().recordEviction();
      }
      if (hasRemovalListener()) {
        notifyRemoval(nodeKey[0], oldValue[0], cause[0]);
      }
    }

    if (removed[0] != null) {
      afterWrite(removed[0], new RemovalTask(removed[0]), now);
    }

    if (newValue[0] == null) {
      if (oldValue[0] != null) {
        tracer().recordDelete(id, nodeKey[0]);
      }
    } else if ((oldValue[0] == null) && (cause[0] == null)) {
      afterWrite(node, new AddTask(node, weight[1]), now);
      tracer().recordWrite(id, key, weight[1]);
    } else {
      int weightedDifference = weight[1] - weight[0];
      if (expiresAfterWrite() || (weightedDifference != 0)) {
        afterWrite(node, new UpdateTask(node, weightedDifference), now);
      } else {
        afterRead(node, now, false);
      }
      tracer().recordWrite(id, nodeKey[0], weight[1]);
    }

    return newValue[0];
  }

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySet()) : ks;
  }

  @Override
  public Collection<V> values() {
    final Collection<V> vs = values;
    return (vs == null) ? (values = new Values()) : vs;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    final Set<Entry<K, V>> es = entrySet;
    return (es == null) ? (entrySet = new EntrySet()) : es;
  }

  /**
   * Returns an unmodifiable snapshot map ordered in eviction order, either ascending or descending.
   * Beware that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param ascending the iteration order
   * @return an unmodifiable snapshot in a specified order
   */
  @SuppressWarnings("GuardedByChecker")
  Map<K, V> evictionOrder(int limit, Function<V, V> transformer, boolean ascending) {
    Comparator<Node<K, V>> comparator = (n1, n2) -> Integer.compare(
        frequencySketch().frequency(n1.getKey()), frequencySketch().frequency(n2.getKey()));
    comparator = ascending ? comparator : comparator.reversed();
    PeekingIterator<Node<K, V>> first =
        ascending ? accessOrderEdenDeque().iterator() : accessOrderEdenDeque().descendingIterator();
    PeekingIterator<Node<K, V>> second =
        ascending ? accessOrderMainDeque().iterator() : accessOrderMainDeque().descendingIterator();
    return orderBy(limit, transformer, comparator, first, second);
  }

  /**
   * Returns an unmodifiable snapshot map ordered in access expiration order, either ascending or
   * descending. Beware that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param ascending the iteration order
   * @return an unmodifiable snapshot in a specified order
   */
  @SuppressWarnings("GuardedByChecker")
  Map<K, V> expireAfterAcessOrder(int limit, Function<V, V> transformer, boolean ascending) {
    Comparator<Node<K, V>> comparator = (n1, n2) ->
        Long.compare(n1.getAccessTime(), n2.getAccessTime());
    comparator = ascending ? comparator : comparator.reversed();
    Function<LinkedDeque<Node<K, V>>, PeekingIterator<Node<K, V>>> ordering =
        deque -> ascending ? deque.iterator() : deque.descendingIterator();
    PeekingIterator<Node<K, V>> first = ordering.apply(accessOrderEdenDeque());
    PeekingIterator<Node<K, V>> second =
        evicts() ? ordering.apply(accessOrderMainDeque()) : PeekingIterator.empty();
    return orderBy(limit, transformer, comparator, first, second);
  }

  /**
   * Returns an unmodifiable snapshot map ordered in write expiration order, either ascending or
   * descending. Beware that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param ascending the iteration order
   * @return an unmodifiable snapshot in a specified order
   */
  @SuppressWarnings("GuardedByChecker")
  Map<K, V> expireAfterWriteOrder(int limit, Function<V, V> transformer, boolean ascending) {
    Comparator<Node<K, V>> comparator = (n1, n2) ->
        Long.compare(n1.getAccessTime(), n2.getAccessTime());
    comparator = ascending ? comparator : comparator.reversed();
    PeekingIterator<Node<K, V>> deque =
        ascending ? writeOrderDeque().iterator() : writeOrderDeque().descendingIterator();
    return orderBy(limit, transformer, comparator, deque, PeekingIterator.empty());
  }

  /**
   * Returns an unmodifiable snapshot map ordered by merging the two iterators and selecting which
   * element to consume first by the comparator. Beware that obtaining the mappings is <em>NOT</em>
   * a constant-time operation.
   *
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @param comparator a function that chooses an element
   * @param first the first ordered iterator
   * @param second the second ordered iterator
   * @return an unmodifiable snapshot in a specified order
   */
  Map<K, V> orderBy(int limit, Function<V, V> transformer, Comparator<Node<K, V>> comparator,
      PeekingIterator<Node<K, V>> first, PeekingIterator<Node<K, V>> second) {
    Caffeine.requireArgument(limit >= 0);
    evictionLock.lock();
    try {
      maintenance();

      int initialCapacity = (weigher == Weigher.singleton())
          ? Math.min(limit, evicts() ? (int) adjustedWeightedSize() : size())
          : 16;
      Map<K, V> map = new LinkedHashMap<K, V>(initialCapacity);
      Consumer<Node<K, V>> consumer = node -> {
        K key = node.getKey();
        V value = node.getValue();
        if ((key != null) && (value != null) && node.isAlive()) {
          map.put(key, transformer.apply(value));
        }
      };
      Node<K, V> node1, node2;
      while ((map.size() < limit) && ((node1 = first.peek()) != null)
          && ((node2 = second.peek()) != null)) {
        if (comparator.compare(node1, node2) <= 0) {
          first.next();
          consumer.accept(node1);
        } else {
          second.next();
          consumer.accept(node2);
        }
      }
      first.asStream().limit(Math.max(0, limit - map.size())).forEach(consumer);
      second.asStream().limit(Math.max(0, limit - map.size())).forEach(consumer);
      return Collections.unmodifiableMap(map);
    } finally {
      evictionLock.unlock();
    }
  }

  /** An adapter to safely externalize the keys. */
  final class KeySet extends AbstractSet<K> {
    final BoundedLocalCache<K, V> map = BoundedLocalCache.this;

    @Override
    public int size() {
      return map.size();
    }

    @Override
    public void clear() {
      map.clear();
    }

    @Override
    public Iterator<K> iterator() {
      return new KeyIterator();
    }

    @Override
    public boolean contains(Object obj) {
      return containsKey(obj);
    }

    @Override
    public boolean remove(Object obj) {
      return (map.remove(obj) != null);
    }

    @Override
    public Object[] toArray() {
      if (collectKeys()) {
        List<Object> keys = new ArrayList<>(size());
        for (Object key : this) {
          keys.add(key);
        }
        return keys.toArray();
      }
      return map.data.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      if (collectKeys()) {
        List<Object> keys = new ArrayList<>(size());
        for (Object key : this) {
          keys.add(key);
        }
        return keys.toArray(array);
      }
      return map.data.keySet().toArray(array);
    }
  }

  /** An adapter to safely externalize the key iterator. */
  final class KeyIterator implements Iterator<K> {
    final Iterator<Entry<K, V>> iterator = entrySet().iterator();
    K current;

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      K castedKey = iterator.next().getKey();
      current = castedKey;
      return current;
    }

    @Override
    public void remove() {
      Caffeine.requireState(current != null);
      BoundedLocalCache.this.remove(current);
      current = null;
    }
  }

  /** An adapter to safely externalize the values. */
  final class Values extends AbstractCollection<V> {

    @Override
    public int size() {
      return BoundedLocalCache.this.size();
    }

    @Override
    public void clear() {
      BoundedLocalCache.this.clear();
    }

    @Override
    public Iterator<V> iterator() {
      return new ValueIterator();
    }

    @Override
    public boolean contains(Object o) {
      return containsValue(o);
    }
  }

  /** An adapter to safely externalize the value iterator. */
  final class ValueIterator implements Iterator<V> {
    final Iterator<Entry<K, V>> iterator = entrySet().iterator();

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      return iterator.next().getValue();
    }

    @Override
    public void remove() {
      iterator.remove();
    }
  }

  /** An adapter to safely externalize the entries. */
  final class EntrySet extends AbstractSet<Entry<K, V>> {
    final BoundedLocalCache<K, V> map = BoundedLocalCache.this;

    @Override
    public int size() {
      return map.size();
    }

    @Override
    public void clear() {
      map.clear();
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new EntryIterator();
    }

    @Override
    public boolean contains(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      Node<K, V> node = map.data.get(nodeFactory.newLookupKey(entry.getKey()));
      return (node != null) && Objects.equals(node.getValue(), entry.getValue());
    }

    @Override
    public boolean remove(Object obj) {
      if (!(obj instanceof Entry<?, ?>)) {
        return false;
      }
      Entry<?, ?> entry = (Entry<?, ?>) obj;
      return map.remove(entry.getKey(), entry.getValue());
    }
  }

  /** An adapter to safely externalize the entry iterator. */
  final class EntryIterator implements Iterator<Entry<K, V>> {
    final Iterator<Node<K, V>> iterator = data.values().iterator();
    final long now = expirationTicker().read();

    K key;
    V value;
    K removalKey;
    Node<K, V> next;

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
          if (hasExpired(next, now) || (key == null) || (value == null) || !next.isAlive()) {
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

    @Override
    public Entry<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      Entry<K, V> entry = new WriteThroughEntry<>(BoundedLocalCache.this, key, value);
      removalKey = key;
      value = null;
      next = null;
      key = null;
      return entry;
    }

    @Override
    public void remove() {
      Caffeine.requireState(removalKey != null);
      BoundedLocalCache.this.remove(removalKey);
      removalKey = null;
    }
  }

  /** Creates a serialization proxy based on the common configuration shared by all cache types. */
  static <K, V> SerializationProxy<K, V> makeSerializationProxy(
      BoundedLocalCache<?, ?> cache, boolean isWeighted) {
    SerializationProxy<K, V> proxy = new SerializationProxy<>();
    proxy.weakKeys = cache.collectKeys();
    proxy.weakValues = cache.nodeFactory.weakValues();
    proxy.softValues = cache.nodeFactory.softValues();
    proxy.isRecordingStats = cache.isRecordingStats();
    proxy.removalListener = cache.removalListener();
    proxy.ticker = cache.expirationTicker();
    proxy.writer = cache.writer;
    if (cache.expiresAfterAccess()) {
      proxy.expiresAfterAccessNanos = cache.expiresAfterAccessNanos();
    }
    if (cache.expiresAfterWrite()) {
      proxy.expiresAfterWriteNanos = cache.expiresAfterWriteNanos();
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

  /* ---------------- Manual Cache -------------- */

  static class BoundedLocalManualCache<K, V> implements
      LocalManualCache<BoundedLocalCache<K, V>, K, V>, Serializable {
    private static final long serialVersionUID = 1;

    final BoundedLocalCache<K, V> cache;
    final boolean isWeighted;
    Policy<K, V> policy;

    BoundedLocalManualCache(Caffeine<K, V> builder) {
      this(builder, null);
    }

    BoundedLocalManualCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      cache = LocalCacheFactory.newBoundedLocalCache(builder, loader, false);
      isWeighted = builder.isWeighted();
    }

    @Override
    public BoundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public Policy<K, V> policy() {
      return (policy == null)
          ? (policy = new BoundedPolicy<K, V>(cache, Function.identity(), isWeighted))
          : policy;
    }

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

    Optional<Eviction<K, V>> eviction;
    Optional<Expiration<K, V>> refreshes;
    Optional<Expiration<K, V>> afterWrite;
    Optional<Expiration<K, V>> afterAccess;

    BoundedPolicy(BoundedLocalCache<K, V> cache, Function<V, V> transformer, boolean isWeighted) {
      this.transformer = transformer;
      this.isWeighted = isWeighted;
      this.cache = cache;
    }

    @Override public Optional<Eviction<K, V>> eviction() {
      return cache.evicts()
          ? (eviction == null) ? (eviction = Optional.of(new BoundedEviction())) : eviction
          : Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> expireAfterAccess() {
      if (!(cache.expiresAfterAccess())) {
        return Optional.empty();
      }
      return (afterAccess == null)
          ? (afterAccess = Optional.of(new BoundedExpireAfterAccess()))
          : afterAccess;
    }
    @Override public Optional<Expiration<K, V>> expireAfterWrite() {
      if (!(cache.expiresAfterWrite())) {
        return Optional.empty();
      }
      return (afterWrite == null)
          ? (afterWrite = Optional.of(new BoundedExpireAfterWrite()))
          : afterWrite;
    }
    @Override
    public Optional<Expiration<K, V>> refreshAfterWrite() {
      if (!(cache.refreshAfterWrite())) {
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
      @Override public OptionalLong weightedSize() {
        if (cache.evicts() && isWeighted()) {
          cache.evictionLock.lock();
          try {
            return OptionalLong.of(cache.adjustedWeightedSize());
          } finally {
            cache.evictionLock.unlock();
          }
        }
        return OptionalLong.empty();
      }
      @Override public long getMaximum() {
        return cache.maximum();
      }
      @Override public void setMaximum(long maximum) {
        cache.evictionLock.lock();
        try {
          cache.setMaximum(maximum);
          cache.maintenance();
        } finally {
          cache.evictionLock.unlock();
        }
      }
      @Override public Map<K, V> coldest(int limit) {
        return cache.evictionOrder(limit, transformer, true);
      }
      @Override public Map<K, V> hottest(int limit) {
        return cache.evictionOrder(limit, transformer, false);
      }
    }

    final class BoundedExpireAfterAccess implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
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
        Caffeine.requireArgument(duration >= 0);
        cache.setExpiresAfterAccessNanos(unit.toNanos(duration));
        cache.executor().execute(cache.drainBuffersTask);
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.expireAfterAcessOrder(limit, transformer, true);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.expireAfterAcessOrder(limit, transformer, false);
      }
    }

    final class BoundedExpireAfterWrite implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
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
        Caffeine.requireArgument(duration >= 0);
        cache.setExpiresAfterWriteNanos(unit.toNanos(duration));
        cache.executor().execute(cache.drainBuffersTask);
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.expireAfterWriteOrder(limit, transformer, true);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.expireAfterWriteOrder(limit, transformer, false);
      }
    }

    final class BoundedRefreshAfterWrite implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.expirationTicker().read() - node.getWriteTime();
        return (age > cache.refreshAfterWriteNanos())
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.refreshAfterWriteNanos(), TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        Caffeine.requireArgument(duration >= 0);
        cache.setRefreshAfterWriteNanos(unit.toNanos(duration));
        cache.executor().execute(cache.drainBuffersTask);
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.expiresAfterWrite()
            ? expireAfterWrite().get().oldest(limit)
            : sortedByWriteTime(limit, true);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.expiresAfterWrite()
            ? expireAfterWrite().get().youngest(limit)
            : sortedByWriteTime(limit, false);
      }

      private Map<K, V> sortedByWriteTime(int limit, boolean ascending) {
        Caffeine.requireArgument(limit >= 0);
        Iterator<Node<K, V>> iterator = cache.data.values().stream().sorted((a, b) -> {
          int comparison = Long.compare(a.getWriteTime(), b.getWriteTime());
          return ascending ? comparison : -comparison;
        }).iterator();
        int initialCapacity = (cache.weigher == Weigher.singleton())
            ? Math.min(limit, cache.evicts() ? (int) cache.adjustedWeightedSize() : cache.size())
            : 16;
        Map<K, V> map = new LinkedHashMap<>(initialCapacity);
        while (iterator.hasNext() && (limit > map.size())) {
          Node<K, V> node = iterator.next();
          K key = node.getKey();
          V value = transformer.apply(node.getValue());
          if ((key != null) && (value != null) && node.isAlive()) {
            map.put(key, value);
          }
        }
        return Collections.unmodifiableMap(map);
      }
    }
  }

  /* ---------------- Loading Cache -------------- */

  static final class BoundedLocalLoadingCache<K, V> extends BoundedLocalManualCache<K, V>
      implements LocalLoadingCache<BoundedLocalCache<K, V>, K, V> {
    private static final long serialVersionUID = 1;

    final boolean hasBulkLoader;

    BoundedLocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder, loader);
      requireNonNull(loader);
      this.hasBulkLoader = hasLoadAll(loader);
    }

    @Override
    public CacheLoader<? super K, V> cacheLoader() {
      return cache.cacheLoader();
    }

    @Override
    public boolean hasBulkLoader() {
      return hasBulkLoader;
    }

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
      proxy.loader = cache.cacheLoader();
      return proxy;
    }
  }

  /* ---------------- Async Loading Cache -------------- */

  static final class BoundedLocalAsyncLoadingCache<K, V>
      extends LocalAsyncLoadingCache<BoundedLocalCache<K, CompletableFuture<V>>, K, V>
      implements Serializable {
    private static final long serialVersionUID = 1;

    final boolean isWeighted;
    transient Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    BoundedLocalAsyncLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(LocalCacheFactory.newBoundedLocalCache((Caffeine<K, CompletableFuture<V>>) builder,
          asyncLoader(loader, builder), true), loader);
      isWeighted = builder.isWeighted();
    }

    private static <K, V> CacheLoader<? super K, CompletableFuture<V>> asyncLoader(
        CacheLoader<? super K, V> loader, Caffeine<?, ?> builder) {
      Executor executor = builder.getExecutor();
      return key -> loader.asyncLoad(key, executor);
    }

    @Override
    protected Policy<K, V> policy() {
      if (policy == null) {
        @SuppressWarnings("unchecked")
        BoundedLocalCache<K, V> castedCache = (BoundedLocalCache<K, V>) cache;
        Function<CompletableFuture<V>, V> transformer = Async::getIfReady;
        @SuppressWarnings("unchecked")
        Function<V, V> castedTransformer = (Function<V, V>) transformer;
        policy = new BoundedPolicy<K, V>(castedCache, castedTransformer, isWeighted);
      }
      return policy;
    }

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
  }
}

/** The namespace for field padding through inheritance. */
final class BLCHeader {

  static abstract class PadDrainStatus<K, V> extends AbstractMap<K, V> {
    long p00, p01, p02, p03, p04, p05, p06, p07;
    long p10, p11, p12, p13, p14, p15, p16, p17;
  }

  /** Enforces a memory layout to avoid false sharing by padding the drain status. */
  static abstract class DrainStatusRef<K, V> extends PadDrainStatus<K, V> {
    static final long DRAIN_STATUS_OFFSET =
        UnsafeAccess.objectFieldOffset(DrainStatusRef.class, "drainStatus");

    /** A drain is not taking place. */
    static final int IDLE = 0;
    /** A drain is required due to a pending write modification. */
    static final int REQUIRED = 1;
    /** A drain is in progress. */
    static final int PROCESSING = 2;

    /** The draining status of the buffers. */
    volatile int drainStatus = IDLE;

    /**
     * Returns whether maintenance work is needed.
     *
     * @param delayable if draining the read buffer can be delayed
     */
    boolean shouldDrainBuffers(boolean delayable) {
      switch (drainStatus) {
        case IDLE:
          return !delayable;
        case REQUIRED:
          return true;
        case PROCESSING:
          return false;
        default:
          throw new IllegalStateException();
      }
    }

    void lazySetDrainStatus(int drainStatus) {
      UnsafeAccess.UNSAFE.putOrderedInt(this, DRAIN_STATUS_OFFSET, drainStatus);
    }

    boolean casDrainStatus(int expect, int update) {
      return UnsafeAccess.UNSAFE.compareAndSwapInt(this, DRAIN_STATUS_OFFSET, expect, update);
    }
  }
}
