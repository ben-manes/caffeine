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

import static com.github.benmanes.caffeine.cache.BoundedLocalCache.DrainStatus.IDLE;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.DrainStatus.PROCESSING;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.DrainStatus.REQUIRED;
import static com.github.benmanes.caffeine.cache.Caffeine.requireState;
import static java.util.Collections.unmodifiableMap;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

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
abstract class BoundedLocalCache<K, V> extends AbstractMap<K, V> implements LocalCache<K, V> {

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

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The maximum weighted capacity of the map. */
  static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;

  /** The number of read buffers to use. */
  static final int NUMBER_OF_READ_BUFFERS = 4 * ceilingNextPowerOfTwo(NCPU);

  /** Mask value for indexing into the read buffers. */
  static final int READ_BUFFERS_MASK = NUMBER_OF_READ_BUFFERS - 1;

  /** The maximum number of pending reads per buffer. */
  static final int READ_BUFFER_SIZE = 32;

  /** Mask value for indexing into the read buffer. */
  static final int READ_BUFFER_INDEX_MASK = READ_BUFFER_SIZE - 1;

  /** The maximum number of write operations to perform per amortized drain. */
  static final int WRITE_BUFFER_DRAIN_THRESHOLD = 16;

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  static final Logger logger = Logger.getLogger(BoundedLocalCache.class.getName());

  // The backing data store holding the key-value associations
  final ConcurrentHashMap<Object, Node<K, V>> data;

  // The factory for creating cache entries
  final NodeFactory nodeFactory;

  // The tracer id
  final long id;

  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  // Remaining fields pending migration to codegen
  @GuardedBy("evictionLock")
  private final long[] readBufferReadCount;
  private final AtomicLong[] readBufferWriteCount;
  private final AtomicReference<Node<K, V>>[][] readBuffers;

  private final NonReentrantLock evictionLock;
  private final AtomicReference<DrainStatus> drainStatus;

  /** Creates an instance based on the builder's configuration. */
  @SuppressWarnings({"unchecked", "cast"})
  protected BoundedLocalCache(Caffeine<K, V> builder,
      @Nullable CacheLoader<? super K, V> loader, boolean isAsync) {
    evictionLock = new NonReentrantLock();
    id = tracer().register(builder.name());
    drainStatus = new AtomicReference<DrainStatus>(IDLE);
    data = new ConcurrentHashMap<>(builder.getInitialCapacity());
    nodeFactory = NodeFactory.getFactory(builder.isStrongKeys(), builder.isWeakKeys(),
        builder.isStrongValues(), builder.isWeakValues(), builder.isSoftValues(),
        builder.expiresAfterAccess(), builder.expiresAfterWrite(), builder.refreshes(),
        builder.evicts(), (isAsync && builder.evicts()) || builder.isWeighted());

    if (evicts() || expiresAfterAccess()) {
      readBufferReadCount = new long[NUMBER_OF_READ_BUFFERS];
      readBufferWriteCount = new AtomicLong[NUMBER_OF_READ_BUFFERS];
      readBuffers = new AtomicReference[NUMBER_OF_READ_BUFFERS][READ_BUFFER_SIZE];
      for (int i = 0; i < NUMBER_OF_READ_BUFFERS; i++) {
        readBufferWriteCount[i] = new AtomicLong();
        readBuffers[i] = new AtomicReference[READ_BUFFER_SIZE];
        for (int j = 0; j < READ_BUFFER_SIZE; j++) {
          readBuffers[i][j] = new AtomicReference<Node<K, V>>();
        }
      }
    } else {
      readBuffers = null;
      readBufferReadCount = null;
      readBufferWriteCount = null;
    }
  }

  @GuardedBy("evictionLock")
  protected AccessOrderDeque<Node<K, V>> accessOrderDeque() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock")
  protected WriteOrderDeque<Node<K, V>> writeOrderDeque() {
    throw new UnsupportedOperationException();
  }

  protected ConcurrentLinkedQueue<Runnable> writeQueue() {
    throw new UnsupportedOperationException();
  }

  /** If the page replacement policy buffers writes. */
  protected boolean buffersWrites() {
    return false;
  }

  protected CacheLoader<? super K, V> cacheLoader() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Executor executor() {
    return ForkJoinPool.commonPool();
  }

  @Override
  public Ticker ticker() {
    return DisabledTicker.INSTANCE;
  }

  /* ---------------- Stats Support -------------- */

  @Override
  public StatsCounter statsCounter() {
    return DisabledStatsCounter.INSTANCE;
  }

  @Override
  public boolean isRecordingStats() {
    return false;
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
    executor().execute(() -> {
      try {
        removalListener().onRemoval(new RemovalNotification<K, V>(key, value, cause));
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", t);
      }
    });
  }

  /* ---------------- Reference Support -------------- */

  protected boolean collectKeys() {
    return false;
  }

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

  /* ---------------- Eviction Support -------------- */

  protected boolean evicts() {
    return false;
  }

  protected boolean isWeighted() {
    return false;
  }

  protected @Nonnull Weigher<? super K, ? super V> weigher() {
    return Weigher.singleton();
  }

  /** Returns the maximum weighted size of the cache. */
  protected long maximum() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock") // must write under lock
  protected void lazySetMaximum(long maximum) {
    throw new UnsupportedOperationException();
  }

  /**
   * Sets the maximum weighted size of the cache and eagerly evicts entries until it shrinks to
   * the appropriate size.
   */
  void setMaximum(long maximum) {
    Caffeine.requireArgument(maximum >= 0);
    evictionLock().lock();
    try {
      lazySetMaximum(Math.min(maximum, MAXIMUM_CAPACITY));
      drainBuffers();
      evict();
    } finally {
      evictionLock().unlock();
    }
  }

  /** Returns the combined weight of the values in the cache. */
  long adjustedWeightedSize() {
    return Math.max(0, weightedSize());
  }

  /** Returns the uncorrected combined weight of the values in the cache. */
  protected long weightedSize() {
    throw new UnsupportedOperationException();
  }

  @GuardedBy("evictionLock") // must write under lock
  protected void lazySetWeightedSize(long weightedSize) {
    throw new UnsupportedOperationException();
  }

  DrainStatus drainStatus() {
    return drainStatus.get();
  }

  void lazySetDrainStatus(DrainStatus drainStatus) {
    this.drainStatus.lazySet(drainStatus);
  }

  void compareAndSetDrainStatus(DrainStatus expect, DrainStatus update) {
    drainStatus.compareAndSet(expect, update);
  }

  NonReentrantLock evictionLock() {
    return evictionLock;
  }

  long[] readBufferReadCount() {
    return readBufferReadCount;
  }

  AtomicLong[] readBufferWriteCount() {
    return readBufferWriteCount;
  }

  AtomicReference<Node<K, V>>[][] readBuffers() {
    return readBuffers;
  }

  /** Determines whether the map has exceeded its capacity. */
  @GuardedBy("evictionLock")
  boolean hasOverflowed() {
    return weightedSize() > maximum();
  }

  /**
   * Evicts entries from the map while it exceeds the capacity and appends evicted entries to the
   * notification queue for processing.
   */
  @GuardedBy("evictionLock")
  void evict() {
    if (!evicts()) {
      return;
    }

    // Attempts to evict entries from the map if it exceeds the maximum capacity. If the eviction
    // fails due to a concurrent removal of the victim, that removal may cancel out the addition
    // that triggered this eviction. The victim is eagerly unlinked before the removal task so
    // that if an eviction is still required then a new victim will be chosen for removal.
    Node<K, V> node = accessOrderDeque().peek();
    while (hasOverflowed()) {
      // If weighted values are used, then the pending operations will adjust the size to reflect
      // the correct weight
      if (node == null) {
        return;
      }

      Node<K, V> next = node.getNextInAccessOrder();
      if (node.getWeight() != 0) {
        evict(node, RemovalCause.SIZE);
      }
      node = next;
    }
  }

  @GuardedBy("evictionLock")
  void evict(Node<K, V> node, RemovalCause cause) {
    boolean removed = data.remove(node.getKeyReference(), node);
    K key = node.getKey();

    makeDead(node);
    if (evicts() || expiresAfterAccess()) {
      accessOrderDeque().remove(node);
    }
    if (expiresAfterWrite()) {
      writeOrderDeque().remove(node);
    }

    if (removed) {
      statsCounter().recordEviction();
      if (hasRemovalListener()) {
        // Notify the listener only if the entry was evicted. This must be performed as the last
        // step during eviction to safe guard against the executor rejecting the notification task.
        notifyRemoval(key, node.getValue(), cause);
      }
    }
  }

  @GuardedBy("evictionLock")
  void expire() {
    long now = ticker().read();
    if (expiresAfterAccess()) {
      long expirationTime = now - expiresAfterAccessNanos();
      for (;;) {
        final Node<K, V> node = accessOrderDeque().peekFirst();
        if ((node == null) || (node.getAccessTime() > expirationTime)) {
          break;
        }
        accessOrderDeque().pollFirst();
        if (expiresAfterWrite()) {
          writeOrderDeque().remove(node);
        }
        evict(node, RemovalCause.EXPIRED);
      }
    }
    if (expiresAfterWrite()) {
      long expirationTime = now - expiresAfterWriteNanos();
      for (;;) {
        final Node<K, V> node = writeOrderDeque().peekFirst();
        if ((node == null) || (node.getWriteTime() > expirationTime)) {
          break;
        }
        writeOrderDeque().pollFirst();
        if (evicts() || expiresAfterAccess()) {
          accessOrderDeque().remove(node);
        }
        evict(node, RemovalCause.EXPIRED);
      }
    }
  }

  boolean hasExpired(Node<K, V> node, long now) {
    return (expiresAfterAccess() && (now - node.getAccessTime() >= expiresAfterAccessNanos()))
        || (expiresAfterWrite() && (now - node.getWriteTime() >= expiresAfterWriteNanos()));
  }

  /**
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   * @param recordHit if the hit count should be incremented
   */
  void afterRead(Node<K, V> node, boolean recordHit) {
    if (recordHit) {
      statsCounter().recordHits(1);
    }
    long now = ticker().read();
    node.setAccessTime(now);
    if (evicts() || expiresAfterAccess()) {
      final int bufferIndex = readBufferIndex();
      final boolean delayable = recordRead(bufferIndex, node);
      drainOnReadIfNeeded(bufferIndex, delayable);
    }

    if (refreshAfterWrite()) {
      long writeTime = node.getWriteTime();
      if (((now - writeTime) > refreshAfterWriteNanos()) && node.casWriteTime(writeTime, now)) {
        executor().execute(() -> {
          K key = node.getKey();
          if (key != null) {
            try {
              computeIfPresent(key, cacheLoader()::reload);
            } catch (Throwable t) {
              logger.log(Level.WARNING, "Exception thrown during reload", t);
            }
          }
        });
      }
    }
  }

  /**
   * Returns the index to the read buffer to record into. Uses a one-step FNV-1a hash code
   * (http://www.isthe.com/chongo/tech/comp/fnv) based on the current thread's id. These hash codes
   * have more uniform distribution properties with respect to small moduli (here 1-31) than do
   * other simple hashing functions.
   */
  static int readBufferIndex() {
    int id = (int) Thread.currentThread().getId();
    return ((id ^ 0x811c9dc5) * 0x01000193) & READ_BUFFERS_MASK;
  }

  /**
   * Records a read in the buffer and return its write count.
   *
   * @param bufferIndex the index to the chosen read buffer
   * @param node the entry in the page replacement policy
   * @return if draining the read buffer can be delayed
   */
  boolean recordRead(int bufferIndex, Node<K, V> node) {
    final AtomicLong counter = readBufferWriteCount()[bufferIndex];
    final long writeCount = counter.get();

    final int index = (int) (writeCount & READ_BUFFER_INDEX_MASK);
    AtomicReference<Node<K, V>> slot = readBuffers()[bufferIndex][index];
    if (slot.get() != null) {
      // FIXME(ben): The slot may be already taken due to either the buffer being full or concurrent
      // readers. The contention is exasperated by lazy writing to the counter so a stale index may
      // be chosen. This may cause premature drains by not detecting the distinctions. When the
      // buffers are dynamically sized (see Striped64) this ignorance will be more acceptable.
      return false;
    } else if (slot.compareAndSet(null, node)) {
      counter.lazySet(writeCount + 1);
    }
    return true;
  }

  /**
   * Attempts to drain the buffers if it is determined to be needed when post-processing a read.
   *
   * @param delayable if draining the read buffer can be delayed
   * @param writeCount the number of writes on the chosen read buffer
   */
  void drainOnReadIfNeeded(int bufferIndex, boolean delayable) {
    final DrainStatus status = drainStatus();
    if (status.shouldDrainBuffers(delayable)) {
      tryToDrainBuffers();
    }
  }

  /**
   * Performs the post-processing work required after a write.
   *
   * @param node the node that was written to
   * @param task the pending operation to be applied
   */
  void afterWrite(@Nullable Node<K, V> node, Runnable task) {
    if (node != null) {
      final long now = ticker().read();
      node.setAccessTime(now);
      node.setWriteTime(now);
    }
    if (buffersWrites()) {
      writeQueue().add(task);
    }
    lazySetDrainStatus(REQUIRED);
    tryToDrainBuffers();
  }

  /**
   * Attempts to acquire the eviction lock and apply the pending operations, up to the amortized
   * threshold, to the page replacement policy.
   */
  void tryToDrainBuffers() {
    if (evictionLock().tryLock()) {
      try {
        lazySetDrainStatus(PROCESSING);
        drainBuffers();
      } finally {
        compareAndSetDrainStatus(PROCESSING, IDLE);
        evictionLock().unlock();
      }
    }
  }

  /** Drains the read and write buffers up to an amortized threshold. */
  @GuardedBy("evictionLock")
  void drainBuffers() {
    drainReadBuffers();
    drainWriteBuffer();
    expire();

    drainKeyReferences();
    drainValueReferences();
  }

  /** Drains the weak key references queue. */
  void drainKeyReferences() {
    if (!collectKeys()) {
      return;
    }
    Reference<? extends K> keyRef;
    while ((keyRef = keyReferenceQueue().poll()) != null) {
      Node<K, V> node = data.get(keyRef);
      if (node != null) {
        evict(node, RemovalCause.COLLECTED);
      }
    }
  }

  /** Drains the weak / soft value references queue. */
  void drainValueReferences() {
    if (!collectValues()) {
      return;
    }
    Reference<? extends V> valueRef;
    while ((valueRef = valueReferenceQueue().poll()) != null) {
      @SuppressWarnings("unchecked")
      InternalReference<V> ref = (InternalReference<V>) valueRef;
      Node<K, V> node = data.get(ref.getKeyReference());
      if (node != null) {
        evict(node, RemovalCause.COLLECTED);
      }
    }
  }

  /** Drains the read buffers, each up to an amortized threshold. */
  @GuardedBy("evictionLock")
  void drainReadBuffers() {
    if (!evicts() && !expiresAfterAccess()) {
      return;
    }
    final int start = (int) Thread.currentThread().getId();
    final int end = start + NUMBER_OF_READ_BUFFERS;
    for (int i = start; i < end; i++) {
      drainReadBuffer(i & READ_BUFFERS_MASK);
    }
  }

  /** Drains the read buffer up to an amortized threshold. */
  @GuardedBy("evictionLock")
  void drainReadBuffer(int bufferIndex) {
    AtomicReference<Node<K, V>>[] buffer = readBuffers()[bufferIndex];
    for (int i = 0; i < READ_BUFFER_SIZE; i++) {
      final int index = (int) (readBufferReadCount()[bufferIndex] & READ_BUFFER_INDEX_MASK);
      final AtomicReference<Node<K, V>> slot = buffer[index];
      final Node<K, V> node = slot.get();
      if (node == null) {
        break;
      }
      slot.lazySet(null);
      reorder(accessOrderDeque(), node);
      readBufferReadCount()[bufferIndex]++;
    }
  }

  /** Updates the node's location in the page replacement policy. */
  @GuardedBy("evictionLock")
  static <K, V> void reorder(LinkedDeque<Node<K, V>> deque, Node<K, V> node) {
    // An entry may be scheduled for reordering despite having been removed. This can occur when the
    // entry was concurrently read while a writer was removing it. If the entry is no longer linked
    // then it does not need to be processed.
    if (deque.contains(node)) {
      deque.moveToBack(node);
    }
  }

  /** Drains the read buffer up to an amortized threshold. */
  @GuardedBy("evictionLock")
  void drainWriteBuffer() {
    if (!buffersWrites()) {
      return;
    }
    for (int i = 0; i < WRITE_BUFFER_DRAIN_THRESHOLD; i++) {
      final Runnable task = writeQueue().poll();
      if (task == null) {
        break;
      }
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
      }

      // ignore out-of-order write operations
      if (node.isAlive()) {
        if (expiresAfterWrite()) {
          writeOrderDeque().add(node);
        }
        if (evicts() || expiresAfterAccess()) {
          accessOrderDeque().add(node);
        }
        evict();
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
      if (evicts() || expiresAfterAccess()) {
        accessOrderDeque().remove(node);
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
        lazySetWeightedSize(weightedSize() + weightDifference);
      }
      if (evicts() || expiresAfterAccess()) {
        reorder(accessOrderDeque(), node);
      }
      if (expiresAfterWrite()) {
        reorder(writeOrderDeque(), node);
      }
      evict();
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
    evictionLock().lock();
    try {
      // Apply all pending writes
      Runnable task;
      while (buffersWrites() && (task = writeQueue().poll()) != null) {
        task.run();
      }

      // Discard all entries
      if (evicts() || expiresAfterAccess()) {
        Node<K, V> node;
        while ((node = accessOrderDeque().poll()) != null) {
          if (data.remove(node.getKeyReference(), node) && hasRemovalListener()) {
            K key = node.getKey();
            V value = node.getValue();
            if ((key == null) || (value == null)) {
              notifyRemoval(key, value, RemovalCause.COLLECTED);
            } else {
              notifyRemoval(key, value, RemovalCause.EXPLICIT);
            }
            tracer().recordDelete(id, node.getKeyReference());
          }
          makeDead(node);
        }

        // Discard all pending reads
        for (AtomicReference<Node<K, V>>[] buffer : readBuffers()) {
          for (AtomicReference<Node<K, V>> slot : buffer) {
            slot.lazySet(null);
          }
        }
      }

      if (expiresAfterWrite()) {
        Node<K, V> node;
        while ((node = writeOrderDeque().poll()) != null) {
          if (data.remove(node.getKeyReference(), node) && hasRemovalListener()) {
            K key = node.getKey();
            V value = node.getValue();
            if ((key == null) || (value == null)) {
              notifyRemoval(key, value, RemovalCause.COLLECTED);
            } else {
              notifyRemoval(key, value, RemovalCause.EXPLICIT);
            }
            tracer().recordDelete(id, node.getKeyReference());
          }
          makeDead(node);
        }
      }

      for (Entry<Object, Node<K, V>> entry : data.entrySet()) {
        Node<K, V> node = entry.getValue();
        if (data.remove(node.getKeyReference(), node) && hasRemovalListener()) {
          K key = node.getKey();
          V value = node.getValue();
          if ((key == null) || (value == null)) {
            notifyRemoval(key, value, RemovalCause.COLLECTED);
          } else {
            notifyRemoval(key, value, RemovalCause.EXPLICIT);
          }
          tracer().recordDelete(id, node.getKeyReference());
        }
        makeDead(node);
      }
    } finally {
      evictionLock().unlock();
    }
  }

  @Override
  public boolean containsKey(Object key) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    return (node != null) && (!hasExpired(node, ticker().read()));
  }

  @Override
  public boolean containsValue(Object value) {
    requireNonNull(value);

    long now = ticker().read();
    for (Node<K, V> node : data.values()) {
      if (node.containsValue(value) && !hasExpired(node, now)) {
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
    } else if (hasExpired(node, ticker().read())) {
      if (recordStats) {
        statsCounter().recordMisses(1);
      }
      tryToDrainBuffers();
      return null;
    }
    afterRead(node, recordStats);
    return node.getValue();
  }

  @Override
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int misses = 0;
    long now = ticker().read();
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
      result.put(castKey, value);

      // TODO(ben): batch reads to call tryLock once
      afterRead(node, true);
    }
    statsCounter().recordMisses(misses);
    return Collections.unmodifiableMap(result);
  }

  @Override
  public V put(K key, V value) {
    return put(key, value, false);
  }

  @Override
  public V putIfAbsent(K key, V value) {
    return put(key, value, true);
  }

  /**
   * Adds a node to the list and the data store. If an existing node is found, then its value is
   * updated if allowed.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param onlyIfAbsent a write is performed only if the key is not already associated with a value
   * @return the prior value in the data store or null if no mapping was found
   */
  V put(K key, V value, boolean onlyIfAbsent) {
    requireNonNull(key);
    requireNonNull(value);

    final long now = ticker().read();
    final int weight = weigher().weigh(key, value);
    final Node<K, V> node = nodeFactory.newNode(key, keyReferenceQueue(),
        value, valueReferenceQueue(), weight, now);

    for (;;) {
      final Node<K, V> prior = data.putIfAbsent(node.getKeyReference(), node);
      tracer().recordWrite(id, key, weight);
      if (prior == null) {
        afterWrite(node, new AddTask(node, weight));
        return null;
      } else if (onlyIfAbsent) {
        afterRead(prior, false);
        return prior.getValue();
      }
      V oldValue;
      int oldWeight;
      synchronized (prior) {
        if (!prior.isAlive()) {
          continue;
        }
        oldValue = prior.getValue();
        oldWeight = prior.getWeight();
        prior.setValue(value, valueReferenceQueue());
        prior.setWeight(weight);
      }

      final int weightedDifference = weight - oldWeight;
      if (!expiresAfterWrite() && (weightedDifference == 0)) {
        afterRead(prior, false);
      } else {
        afterWrite(prior, new UpdateTask(prior, weightedDifference));
      }
      if (hasRemovalListener() && (value != oldValue)) {
        notifyRemoval(key, value, RemovalCause.REPLACED);
      }
      return oldValue;
    }
  }

  @Override
  public V remove(Object key) {
    final Node<K, V> node = data.remove(nodeFactory.newLookupKey(key));
    tracer().recordDelete(id, key);
    if (node == null) {
      return null;
    }

    V oldValue = node.makeRetired();
    if (oldValue != null) {
      afterWrite(node, new RemovalTask(node));
      if (hasRemovalListener()) {
        @SuppressWarnings("unchecked")
        K castKey = (K) key;
        notifyRemoval(castKey, oldValue, RemovalCause.EXPLICIT);
      }
    }
    return oldValue;
  }

  @Override
  public boolean remove(Object key, Object value) {
    Object keyRef = nodeFactory.newLookupKey(key);
    final Node<K, V> node = data.get(keyRef);
    tracer().recordDelete(id, key);
    if ((node == null) || (value == null)) {
      return false;
    }
    V oldValue;
    synchronized (node) {
      oldValue = node.getValue();
      if (node.isAlive() && node.containsValue(value)) {
        node.retire();
      } else {
        return false;
      }
    }
    if (data.remove(keyRef, node) && hasRemovalListener()) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      notifyRemoval(castKey, oldValue, RemovalCause.EXPLICIT);
    }
    afterWrite(node, new RemovalTask(node));
    return true;
  }

  @Override
  public V replace(K key, V value) {
    requireNonNull(key);
    requireNonNull(value);

    final int weight = weigher().weigh(key, value);
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    tracer().recordWrite(id, key, weight);
    if (node == null) {
      return null;
    }
    V oldValue;
    int oldWeight;
    synchronized (node) {
      if (!node.isAlive()) {
        return null;
      }
      oldWeight = node.getWeight();
      oldValue = node.getValue();
      node.setValue(value, valueReferenceQueue());
      node.setWeight(weight);
    }
    final int weightedDifference = (weight - oldWeight);
    if (weightedDifference == 0) {
      node.setWriteTime(ticker().read());
      afterRead(node, false);
    } else {
      afterWrite(node, new UpdateTask(node, weightedDifference));
    }
    if (hasRemovalListener() && (value != oldValue)) {
      notifyRemoval(key, value, RemovalCause.REPLACED);
    }
    return oldValue;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(key);
    requireNonNull(oldValue);
    requireNonNull(newValue);

    final int weight = weigher().weigh(key, newValue);
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    tracer().recordWrite(id, key, weight);
    if (node == null) {
      return false;
    }
    int oldWeight;
    synchronized (node) {
      if (!node.isAlive() || !node.containsValue(oldValue)) {
        return false;
      }
      oldWeight = node.getWeight();
      node.setValue(newValue, valueReferenceQueue());
      node.setWeight(weight);
    }
    final int weightedDifference = (weight - oldWeight);
    if (weightedDifference == 0) {
      node.setWriteTime(ticker().read());
      afterRead(node, false);
    } else {
      afterWrite(node, new UpdateTask(node, weightedDifference));
    }
    if (hasRemovalListener() && (oldValue != newValue)) {
      notifyRemoval(key, oldValue, RemovalCause.REPLACED);
    }
    return true;
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
      boolean isAsync) {
    requireNonNull(key);
    requireNonNull(mappingFunction);

    // An optimistic fast path due to computeIfAbsent always locking. This is leveraged to evict
    // if the entry is present but has expired.
    long now = ticker().read();
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if ((node != null)) {
      if (hasExpired(node, now)) {
        if (data.remove(node.getKeyReference(), node)) {
          afterWrite(node, new RemovalTask(node));
          if (hasRemovalListener()) {
            notifyRemoval(key, node.getValue(), RemovalCause.EXPIRED);
          }
          statsCounter().recordEviction();
        }
      } else {
        afterRead(node, true);
        V value = node.getValue();
        if (Tracer.isEnabled() && (value != null)) {
          tracer().recordWrite(id, key, weigher().weigh(key, value));
        }
        return value;
      }
    }

    int[] weight = new int[1];
    @SuppressWarnings("unchecked")
    V[] value = (V[]) new Object[1];
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    node = data.computeIfAbsent(keyRef, k -> {
      value[0] = statsAware(mappingFunction, isAsync).apply(key);
      if (value[0] == null) {
        return null;
      }
      weight[0] = weigher().weigh(key, value[0]);
      return nodeFactory.newNode(key, keyReferenceQueue(),
          value[0], valueReferenceQueue(), weight[0], now);
    });
    if (node == null) {
      return null;
    }
    V val;
    if (value[0] == null) {
      val = node.getValue();
      afterRead(node, true);
    } else {
      val = value[0];
      afterWrite(node, new AddTask(node, weight[0]));
    }
    if (Tracer.isEnabled() && (val != null)) {
      tracer().recordWrite(id, key, weigher().weigh(key, val));
    }
    return val;
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // An optimistic fast path due to computeIfAbsent always locking
    Object ref = nodeFactory.newLookupKey(key);
    if (!data.containsKey(ref)) {
      return null;
    }

    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    Runnable[] task = new Runnable[1];
    Node<K, V> node = data.computeIfPresent(ref, (keyRef, prior) -> {
      synchronized (prior) {
        V oldValue = prior.getValue();
        newValue[0] = statsAware(remappingFunction, false, false).apply(key, oldValue);
        if (newValue[0] == null) {
          prior.makeRetired();
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldValue, RemovalCause.EXPLICIT);
          }
          return null;
        }
        prior.setValue(newValue[0], valueReferenceQueue());
        int oldWeight = prior.getWeight();
        int newWeight = weigher().weigh(key, newValue[0]);
        prior.setWeight(newWeight);

        final int weightedDifference = newWeight - oldWeight;
        if (weightedDifference != 0) {
          task[0] = new UpdateTask(prior, weightedDifference);
        }
        if (hasRemovalListener() && (newValue[0] != oldValue)) {
          notifyRemoval(key, oldValue, RemovalCause.REPLACED);
        }
        tracer().recordWrite(id, key, newWeight);
        return prior;
      }
    });
    if (task[0] == null) {
      afterRead(node, false);
    } else {
      afterWrite(node, task[0]);
    }
    return newValue[0];
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss, boolean isAsync) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue());
    Runnable[] task = new Runnable[2];
    Node<K, V> node = data.compute(keyRef, (k, prior) -> {
      if (prior == null) {
        newValue[0] = statsAware(remappingFunction, recordMiss, isAsync).apply(key, null);
        if (newValue[0] == null) {
          tracer().recordDelete(id, key);
          return null;
        }
        final long now = ticker().read();
        final int weight = weigher().weigh(key, newValue[0]);
        final Node<K, V> newNode = nodeFactory.newNode(
            keyRef, newValue[0], valueReferenceQueue(), weight, now);
        task[0] = new AddTask(newNode, weight);
        tracer().recordWrite(id, key, weight);
        return newNode;
      }
      synchronized (prior) {
        V oldValue = null;
        if (prior.isAlive()) {
          oldValue = prior.getValue();
        } else {
          // conditionally removed won, but we got the entry lock first
          // so help out and pretend like we are inserting a fresh entry
          task[1] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            V value = prior.getValue();
            if (value == null) {
              notifyRemoval(key, value, RemovalCause.COLLECTED);
            } else {
              notifyRemoval(key, value, RemovalCause.EXPLICIT);
            }
          }
        }
        newValue[0] = statsAware(remappingFunction, recordMiss, isAsync).apply(key, oldValue);
        if ((newValue[0] == null) && (oldValue != null)) {
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldValue, RemovalCause.EXPLICIT);
          }
          tracer().recordDelete(id, key);
          return null;
        }
        final int oldWeight = prior.getWeight();
        final int newWeight = weigher().weigh(key, newValue[0]);
        if (task[1] == null) {
          prior.setWeight(newWeight);
          prior.setValue(newValue[0], valueReferenceQueue());
          final int weightedDifference = newWeight - oldWeight;
          if (weightedDifference != 0) {
            task[0] = new UpdateTask(prior, weightedDifference);
          }
          if (hasRemovalListener() && (newValue[0] != oldValue)) {
            notifyRemoval(key, oldValue, RemovalCause.REPLACED);
          }
          tracer().recordWrite(id, key, newWeight);
          return prior;
        } else {
          final long now = ticker().read();
          Node<K, V> newNode = nodeFactory.newNode(
              keyRef, newValue[0], valueReferenceQueue(), newWeight, now);
          task[0] = new AddTask(newNode, newWeight);
          return newNode;
        }
      }
    });
    if (task[0] != null) {
      afterWrite(node, task[0]);
    }
    if (task[1] != null) {
      afterWrite(node, task[1]);
    }
    return newValue[0];
  }

  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(value);
    requireNonNull(remappingFunction);

    // FIXME(ben): Flush out an atomic implementation instead of using the default version
    return super.merge(key, value, statsAware(remappingFunction));
  }

  @Override
  public void cleanUp() {
    evictionLock().lock();
    try {
      drainBuffers();
    } finally {
      evictionLock().unlock();
    }
  }

  void asyncCleanup() {
    executor().execute(this::cleanUp);
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

  Map<K, V> orderedMap(LinkedDeque<Node<K, V>> deque, Function<V, V> transformer,
      boolean ascending, int limit) {
    Caffeine.requireArgument(limit >= 0);
    evictionLock().lock();
    try {
      drainBuffers();

      final int initialCapacity = (weigher() == Weigher.singleton())
          ? Math.min(limit, evicts() ? (int) adjustedWeightedSize() : size())
          : 16;
      final Map<K, V> map = new LinkedHashMap<K, V>(initialCapacity);
      final Iterator<Node<K, V>> iterator = ascending
          ? deque.iterator()
          : deque.descendingIterator();
      while (iterator.hasNext() && (limit > map.size())) {
        Node<K, V> node = iterator.next();
        K key = node.getKey();
        V value = transformer.apply(node.getValue());
        if ((key != null) && (value != null)) {
          map.put(key, value);
        }
      }
      return unmodifiableMap(map);
    } finally {
      evictionLock().unlock();
    }
  }

  /** The draining status of the buffers. */
  enum DrainStatus {

    /** A drain is not taking place. */
    IDLE {
      @Override boolean shouldDrainBuffers(boolean delayable) {
        return !delayable;
      }
    },

    /** A drain is required due to a pending write modification. */
    REQUIRED {
      @Override boolean shouldDrainBuffers(boolean delayable) {
        return true;
      }
    },

    /** A drain is in progress. */
    PROCESSING {
      @Override boolean shouldDrainBuffers(boolean delayable) {
        return false;
      }
    };

    /**
     * Determines whether the buffers should be drained.
     *
     * @param delayable if a drain should be delayed until required
     * @return if a drain should be attempted
     */
    abstract boolean shouldDrainBuffers(boolean delayable);
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
    final long now = ticker().read();

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
          if (hasExpired(next, now) || (key == null) || (value == null)) {
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
  static <K, V> SerializationProxy<K, V> makeSerializationProxy(BoundedLocalCache<?, ?> cache) {
    SerializationProxy<K, V> proxy = new SerializationProxy<>();
    proxy.weakKeys = cache.collectKeys();
    proxy.weakValues = cache.nodeFactory.weakValues();
    proxy.softValues = cache.nodeFactory.softValues();
    proxy.isRecordingStats = cache.isRecordingStats();
    proxy.removalListener = cache.removalListener();
    proxy.ticker = cache.ticker();
    if (cache.expiresAfterAccess()) {
      proxy.expiresAfterAccessNanos = cache.expiresAfterAccessNanos();
    }
    if (cache.expiresAfterWrite()) {
      proxy.expiresAfterWriteNanos = cache.expiresAfterWriteNanos();
    }
    if (cache.evicts()) {
      if (cache.weigher() == Weigher.singleton()) {
        proxy.maximumSize = cache.maximum();
      } else {
        proxy.weigher = cache.weigher();
        proxy.maximumWeight = cache.maximum();
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
      isWeighted = (builder.weigher != null);
    }

    @Override
    public BoundedLocalCache<K, V> cache() {
      return cache;
    }

    @Override
    public Policy<K, V> policy() {
      return (policy == null)
          ? (policy = new BoundedPolicy<K, V>(cache, Function.identity()))
          : policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      return makeSerializationProxy(cache);
    }
  }

  static final class BoundedPolicy<K, V> implements Policy<K, V> {
    final BoundedLocalCache<K, V> cache;
    final Function<V, V> transformer;

    Optional<Eviction<K, V>> eviction;
    Optional<Expiration<K, V>> refreshes;
    Optional<Expiration<K, V>> afterWrite;
    Optional<Expiration<K, V>> afterAccess;

    BoundedPolicy(BoundedLocalCache<K, V> cache, Function<V, V> transformer) {
      this.cache = cache;
      this.transformer = transformer;
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
        return cache.isWeighted();
      }
      @Override public OptionalLong weightedSize() {
        if (cache.evicts() && isWeighted()) {
          return OptionalLong.of(cache.adjustedWeightedSize());
        }
        return OptionalLong.empty();
      }
      @Override public long getMaximum() {
        return cache.maximum();
      }
      @Override public void setMaximum(long maximumSize) {
        cache.setMaximum(maximumSize);
      }
      @Override public Map<K, V> coldest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque(), transformer, true, limit);
      }
      @Override public Map<K, V> hottest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque(), transformer, false, limit);
      }
    }

    final class BoundedExpireAfterAccess implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.ticker().read() - node.getAccessTime();
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
        cache.asyncCleanup();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque(), transformer, true, limit);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque(), transformer, false, limit);
      }
    }

    final class BoundedExpireAfterWrite implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.ticker().read() - node.getWriteTime();
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
        cache.asyncCleanup();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.orderedMap(cache.writeOrderDeque(), transformer, true, limit);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.orderedMap(cache.writeOrderDeque(), transformer, false, limit);
      }
    }

    final class BoundedRefreshAfterWrite implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.ticker().read() - node.getWriteTime();
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
        cache.asyncCleanup();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.expiresAfterWrite()
            ? expireAfterWrite().get().oldest(limit)
            : sortedByWriteTime(true, limit);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.expiresAfterWrite()
            ? expireAfterWrite().get().youngest(limit)
            : sortedByWriteTime(false, limit);
      }

      private Map<K, V> sortedByWriteTime(boolean ascending, int limit) {
        Caffeine.requireArgument(limit >= 0);
        final int initialCapacity = (cache.weigher() == Weigher.singleton())
            ? Math.min(limit, cache.evicts() ? (int) cache.adjustedWeightedSize() : cache.size())
            : 16;
        final Map<K, V> map = new LinkedHashMap<>(initialCapacity);
        Iterator<Node<K, V>> iterator = cache.data.values().stream().sorted((a, b) -> {
              int comparison = Long.compare(a.getWriteTime(), b.getWriteTime());
              return ascending ? comparison : -comparison;
            }).iterator();
        while (iterator.hasNext() && (limit > map.size())) {
          Node<K, V> node = iterator.next();
          K key = node.getKey();
          V value = transformer.apply(node.getValue());
          if ((key != null) && (value != null)) {
            map.put(key, value);
          }
        }
        return unmodifiableMap(map);
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

    transient Policy<K, V> policy;

    @SuppressWarnings("unchecked")
    BoundedLocalAsyncLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(LocalCacheFactory.newBoundedLocalCache((Caffeine<K, CompletableFuture<V>>) builder,
          asyncLoader(loader, builder), true), loader);
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
        policy = new BoundedPolicy<K, V>(castedCache, castedTransformer);
      }
      return policy;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
      throw new InvalidObjectException("Proxy required");
    }

    Object writeReplace() {
      SerializationProxy<K, V> proxy = makeSerializationProxy(cache);
      if (cache.refreshAfterWrite()) {
        proxy.refreshAfterWriteNanos = cache.refreshAfterWriteNanos();
      }
      proxy.loader = loader;
      proxy.async = true;
      return proxy;
    }
  }
}
