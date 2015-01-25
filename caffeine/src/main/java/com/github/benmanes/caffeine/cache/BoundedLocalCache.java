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
import java.util.HashMap;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.cache.References.InternalReference;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * A hash table supporting full concurrency of retrievals, adjustable expected concurrency for
 * updates, and a maximum capacity to bound the map by. This implementation differs from
 * {@link ConcurrentHashMap} in that it maintains a page replacement algorithm that is used to evict
 * an entry when the map has exceeded its capacity. Unlike the <tt>Java Collections Framework</tt>,
 * this map does not have a publicly visible constructor and instances are created through a
 * {@link Caffeine}.
 * <p>
 * An entry is evicted from the map when the <tt>weighted capacity</tt> exceeds its
 * <tt>maximum weighted capacity</tt> threshold. A {@link Weigher} determines how many units of
 * capacity that an entry consumes. The default weigher assigns each value a weight of <tt>1</tt> to
 * bound the map by the total number of key-value pairs. A map that holds collections may choose to
 * weigh values by the number of elements in the collection and bound the map by the total number of
 * elements that it contains. A change to a value that modifies its weight requires that an update
 * operation is performed on the map.
 * <p>
 * An {@link RemovalListener} may be supplied for notification when an entry is evicted from the
 * map. This listener is invoked on a caller's thread and will not block other threads from
 * operating on the map. An implementation should be aware that the caller's thread will not expect
 * long execution times or failures as a side effect of the listener being notified. Execution
 * safety and a fast turn around time can be achieved by performing the operation asynchronously,
 * such as by submitting a task to an {@link java.util.concurrent.ExecutorService}.
 * <p>
 * The <tt>concurrency level</tt> determines the number of threads that can concurrently modify the
 * table. Using a significantly higher or lower value than needed can waste space or lead to thread
 * contention, but an estimate within an order of magnitude of the ideal value does not usually have
 * a noticeable impact. Because placement in hash tables is essentially random, the actual
 * concurrency will vary.
 * <p>
 * This class and its views and iterators implement all of the <em>optional</em> methods of the
 * {@link Map} and {@link Iterator} interfaces.
 * <p>
 * Like {@link java.util.Hashtable} but unlike {@link HashMap}, this class does <em>not</em> allow
 * <tt>null</tt> to be used as a key or value. Unlike {@link java.util.LinkedHashMap}, this class
 * does <em>not</em> provide predictable iteration order. A snapshot of the keys and entries may be
 * obtained in ascending and descending order of retention.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
@ThreadSafe
final class BoundedLocalCache<K, V> extends AbstractMap<K, V> implements LocalCache<K, V> {

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
   * buffers are drained at the first opportunity after a write or when the read buffer exceeds a
   * threshold size. The reads are recorded in a lossy buffer, allowing the reordering operations to
   * be discarded if the draining process cannot keep up. Due to the concurrent nature of the read
   * and write operations a strict policy ordering is not possible, but is observably strict when
   * single threaded.
   *
   * Due to a lack of a strict ordering guarantee, a task can be executed out-of-order, such as a
   * removal followed by its addition. The state of the entry is encoded within the value's weight.
   *
   * Alive: The entry is in both the hash-table and the page replacement policy. This is represented
   * by a zero or positive weight.
   *
   * Retired: The entry is not in the hash-table and is pending removal from the page replacement
   * policy. This is represented by a negative weight, where the hole of a retired entry with zero
   * weight has no harmful impact on the policy behavior.
   *
   * Dead: The entry is not in the hash-table and is not in the page replacement policy. This is
   * represented by a weight of Integer.MIN_VALUE.
   *
   * The Least Recently Used page replacement algorithm was chosen due to its simplicity, high hit
   * rate, and ability to be implemented with O(1) time complexity.
   */

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The maximum weighted capacity of the map. */
  static final long MAXIMUM_CAPACITY = Long.MAX_VALUE - Integer.MAX_VALUE;

  /** The number of read buffers to use. */
  static final int NUMBER_OF_READ_BUFFERS = 4 * ceilingNextPowerOfTwo(NCPU);

  /** Mask value for indexing into the read buffers. */
  static final int READ_BUFFERS_MASK = NUMBER_OF_READ_BUFFERS - 1;

  /** The number of pending read operations before attempting to drain. */
  static final int READ_BUFFER_THRESHOLD = 32;

  /** The maximum number of read operations to perform per amortized drain. */
  static final int READ_BUFFER_DRAIN_THRESHOLD = 2 * READ_BUFFER_THRESHOLD;

  /** The maximum number of pending reads per buffer. */
  static final int READ_BUFFER_SIZE = 2 * READ_BUFFER_DRAIN_THRESHOLD;

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

  // How long after the last access to an entry the map will retain that entry
  volatile long expireAfterAccessNanos;
  @GuardedBy("evictionLock")
  final AccessOrderDeque<Node<K, V>> accessOrderDeque;

  // How long after the last write to an entry the map will retain that entry
  volatile long expireAfterWriteNanos;
  @GuardedBy("evictionLock")
  final WriteOrderDeque<Node<K, V>> writeOrderDeque;

  // How long after the last write an entry becomes a candidate for refresh
  final long refreshNanos;
  final @Nullable CacheLoader<? super K, V> loader;

  // These fields provide support for reference-based eviction
  final ReferenceQueue<K> keyReferenceQueue;
  final ReferenceQueue<V> valueReferenceQueue;

  // These fields provide support to bound the map by a maximum capacity
  final Weigher<? super K, ? super V> weigher;
  @GuardedBy("evictionLock") // must write under lock
  final AtomicLong weightedSize;
  @GuardedBy("evictionLock") // must write under lock
  final AtomicLong maximumWeightedSize;

  final Queue<Runnable> writeBuffer;
  final NonReentrantLock evictionLock;
  final AtomicReference<DrainStatus> drainStatus;

  @GuardedBy("evictionLock")
  final long[] readBufferReadCount;
  final AtomicLong[] readBufferWriteCount;
  final AtomicLong[] readBufferDrainAtWriteCount;
  final AtomicReference<Node<K, V>>[][] readBuffers;

  // These fields provide support for notifying a listener.
  final RemovalListener<K, V> removalListener;
  final Executor executor;

  // These fields provide support for recording stats
  final Ticker ticker;
  final boolean isRecordingStats;
  final StatsCounter statsCounter;

  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  /**
   * Creates an instance based on the builder's configuration.
   */
  @SuppressWarnings({"unchecked", "cast"})
  private BoundedLocalCache(Caffeine<K, V> builder,
      @Nullable CacheLoader<? super K, V> loader, boolean isAsync) {
    // The data store and its maximum capacity
    data = new ConcurrentHashMap<>(builder.getInitialCapacity());
    this.loader = loader;

    // The expiration support
    refreshNanos = builder.getRefreshNanos();
    expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
    expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
    writeOrderDeque = new WriteOrderDeque<Node<K, V>>();
    accessOrderDeque = new AccessOrderDeque<Node<K, V>>();

    boolean evicts = (builder.getMaximumWeight() != Caffeine.UNSET_INT);
    maximumWeightedSize = evicts
        ? new AtomicLong(Math.min(builder.getMaximumWeight(), MAXIMUM_CAPACITY))
        : null;
    readBufferReadCount = new long[NUMBER_OF_READ_BUFFERS];
    readBufferWriteCount = new AtomicLong[NUMBER_OF_READ_BUFFERS];
    readBufferDrainAtWriteCount = new AtomicLong[NUMBER_OF_READ_BUFFERS];
    readBuffers = new AtomicReference[NUMBER_OF_READ_BUFFERS][READ_BUFFER_SIZE];
    for (int i = 0; i < NUMBER_OF_READ_BUFFERS; i++) {
      readBufferWriteCount[i] = new AtomicLong();
      readBufferDrainAtWriteCount[i] = new AtomicLong();
      readBuffers[i] = new AtomicReference[READ_BUFFER_SIZE];
      for (int j = 0; j < READ_BUFFER_SIZE; j++) {
        readBuffers[i][j] = new AtomicReference<Node<K, V>>();
      }
    }
    writeBuffer = new ConcurrentLinkedQueue<Runnable>();

    // The eviction support
    evictionLock = new NonReentrantLock();
    weigher = builder.getWeigher(isAsync);
    weightedSize = new AtomicLong();
    drainStatus = new AtomicReference<DrainStatus>(IDLE);

    // The reference eviction support
    keyReferenceQueue = new ReferenceQueue<K>();
    valueReferenceQueue = new ReferenceQueue<V>();

    // The notification queue and listener
    removalListener = builder.getRemovalListener(isAsync);
    executor = builder.getExecutor();

    // The statistics
    statsCounter = builder.getStatsCounterSupplier().get();
    isRecordingStats = builder.isRecordingStats();
    ticker = builder.getTicker();

    nodeFactory = NodeFactory.getFactory(builder.isStrongKeys(), builder.isWeakKeys(),
        builder.isStrongValues(), builder.isWeakValues(), builder.isSoftValues(),
        expiresAfterAccess(), refreshes() || expiresAfterWrite(), true, true/*evicts(), builder.isWeighted()*/);
  }

  /** Returns whether this cache notifies when an entry is removed. */
  boolean hasRemovalListener() {
    return (removalListener != null);
  }

  /** Asynchronously sends a removal notification to the listener. */
  void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
    requireNonNull(removalListener, "Notification should be guarded with a check");
    executor.execute(() -> {
      try {
        removalListener.onRemoval(new RemovalNotification<K, V>(key, value, cause));
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown by removal listener", t);
      }
    });
  }

  boolean collectKeys() {
    return nodeFactory.weakKeys();
  }

  boolean collectValues() {
    return !nodeFactory.strongValues();
  }

  boolean collects() {
    return collectKeys() || collectValues();
  }

  /* ---------------- Expiration Support -------------- */

  boolean expiresAfterWrite() {
    return expireAfterWriteNanos > 0;
  }

  boolean expiresAfterAccess() {
    return expireAfterAccessNanos > 0;
  }

  boolean refreshes() {
    return refreshNanos > 0;
  }

  /* ---------------- Eviction Support -------------- */

  boolean evicts() {
    return (maximumWeightedSize != null);
  }

  /**
   * Retrieves the maximum weighted capacity of the map.
   *
   * @return the maximum weighted capacity
   */
  public long capacity() {
    return maximumWeightedSize.get();
  }

  /**
   * Sets the maximum weighted capacity of the map and eagerly evicts entries until it shrinks to
   * the appropriate size.
   *
   * @param capacity the maximum weighted capacity of the map
   * @throws IllegalArgumentException if the capacity is negative
   */
  public void setCapacity(long capacity) {
    Caffeine.requireArgument(capacity >= 0);
    evictionLock.lock();
    try {
      this.maximumWeightedSize.lazySet(Math.min(capacity, MAXIMUM_CAPACITY));
      drainBuffers();
      evict();
    } finally {
      evictionLock.unlock();
    }
  }

  /** Determines whether the map has exceeded its capacity. */
  @GuardedBy("evictionLock")
  boolean hasOverflowed() {
    return weightedSize.get() > maximumWeightedSize.get();
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
    Node<K, V> node = accessOrderDeque.peek();
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
    makeDead(node);

    // Notify the listener only if the entry was evicted
    if (data.remove(node.getKeyReference(), node)) {
      if (hasRemovalListener()) {
        notifyRemoval(node.getKey(), node.getValue(), cause);
      }
      statsCounter.recordEviction();
    }

    accessOrderDeque.remove(node);
    writeOrderDeque.remove(node);
  }

  @GuardedBy("evictionLock")
  void expire() {
    long now = ticker.read();
    if (expiresAfterAccess()) {
      long expirationTime = now - expireAfterAccessNanos;
      for (;;) {
        final Node<K, V> node = accessOrderDeque.peekFirst();
        if ((node == null) || (node.getAccessTime() > expirationTime)) {
          break;
        }
        accessOrderDeque.pollFirst();
        writeOrderDeque.remove(node);
        evict(node, RemovalCause.EXPIRED);
      }
    }
    if (expiresAfterWrite()) {
      long expirationTime = now - expireAfterWriteNanos;
      for (;;) {
        final Node<K, V> node = writeOrderDeque.peekFirst();
        if ((node == null) || (node.getWriteTime() > expirationTime)) {
          break;
        }
        writeOrderDeque.pollFirst();
        accessOrderDeque.remove(node);
        evict(node, RemovalCause.EXPIRED);
      }
    }
  }

  boolean hasExpired(Node<K, V> node, long now) {
    return (expiresAfterAccess() && (now - node.getAccessTime() >= expireAfterAccessNanos))
        || (expiresAfterWrite() && (now - node.getWriteTime() >= expireAfterWriteNanos));
  }

  /**
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   * @param recordHit if the hit count should be incremented
   */
  void afterRead(Node<K, V> node, boolean recordHit) {
    if (recordHit) {
      statsCounter.recordHits(1);
    }
    long now = ticker.read();
    node.setAccessTime(now);
    if (evicts() || expiresAfterAccess()) {
      final int bufferIndex = readBufferIndex();
      final long writeCount = recordRead(bufferIndex, node);
      drainOnReadIfNeeded(bufferIndex, writeCount);
    }

    if (refreshes() && ((now - node.getWriteTime()) > refreshNanos)) {
      // FIXME: make atomic and avoid race
      node.setWriteTime(now);
      executor.execute(() -> {
        K key = node.getKey();
        if (key != null) {
          try {
            computeIfPresent(key, (k, oldValue) -> loader.reload(key, oldValue));
          } catch (Throwable t) {
            logger.log(Level.WARNING, "Exception thrown during reload", t);
          }
        }
      });
    }
  }

  /** Returns the index to the read buffer to record into. */
  static int readBufferIndex() {
    // A buffer is chosen by the thread's id so that tasks are distributed in a pseudo evenly
    // manner. This helps avoid hot entries causing contention due to other threads trying to
    // append to the same buffer.
    return ((int) Thread.currentThread().getId()) & READ_BUFFERS_MASK;
  }

  /**
   * Records a read in the buffer and return its write count.
   *
   * @param bufferIndex the index to the chosen read buffer
   * @param node the entry in the page replacement policy
   * @return the number of writes on the chosen read buffer
   */
  long recordRead(int bufferIndex, Node<K, V> node) {
    // The location in the buffer is chosen in a racy fashion as the increment is not atomic with
    // the insertion. This means that concurrent reads can overlap and overwrite one another,
    // resulting in a lossy buffer.
    final AtomicLong counter = readBufferWriteCount[bufferIndex];
    final long writeCount = counter.get();
    counter.lazySet(writeCount + 1);

    final int index = (int) (writeCount & READ_BUFFER_INDEX_MASK);
    readBuffers[bufferIndex][index].lazySet(node);

    return writeCount;
  }

  /**
   * Attempts to drain the buffers if it is determined to be needed when post-processing a read.
   *
   * @param bufferIndex the index to the chosen read buffer
   * @param writeCount the number of writes on the chosen read buffer
   */
  void drainOnReadIfNeeded(int bufferIndex, long writeCount) {
    final long pending = (writeCount - readBufferDrainAtWriteCount[bufferIndex].get());
    final boolean delayable = (pending < READ_BUFFER_THRESHOLD);
    final DrainStatus status = drainStatus.get();
    if (status.shouldDrainBuffers(delayable)) {
      tryToDrainBuffers();
    }
  }

  /**
   * Performs the post-processing work required after a write.
   *
   * @param task the pending operation to be applied
   */
  void afterWrite(Node<K, V> node, Runnable task) {
    if (node != null) {
      final long now = ticker.read();
      node.setAccessTime(now);
      node.setWriteTime(now);
    }

    writeBuffer.add(task);
    drainStatus.lazySet(REQUIRED);
    tryToDrainBuffers();
  }

  /**
   * Attempts to acquire the eviction lock and apply the pending operations, up to the amortized
   * threshold, to the page replacement policy.
   */
  void tryToDrainBuffers() {
    if (evictionLock.tryLock()) {
      try {
        drainStatus.lazySet(PROCESSING);
        drainBuffers();
      } finally {
        drainStatus.compareAndSet(PROCESSING, IDLE);
        evictionLock.unlock();
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

  void drainKeyReferences() {
    Reference<? extends K> keyRef;
    while ((keyRef = keyReferenceQueue.poll()) != null) {
      Node<K, V> node = data.get(keyRef);
      if (node != null) {
        evict(node, RemovalCause.COLLECTED);
      }
    }
  }

  void drainValueReferences() {
    Reference<? extends V> valueRef;
    while ((valueRef = valueReferenceQueue.poll()) != null) {
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
    final long writeCount = readBufferWriteCount[bufferIndex].get();
    for (int i = 0; i < READ_BUFFER_DRAIN_THRESHOLD; i++) {
      final int index = (int) (readBufferReadCount[bufferIndex] & READ_BUFFER_INDEX_MASK);
      final AtomicReference<Node<K, V>> slot = readBuffers[bufferIndex][index];
      final Node<K, V> node = slot.get();
      if (node == null) {
        break;
      }

      slot.lazySet(null);
      applyRead(node);
      readBufferReadCount[bufferIndex]++;
    }
    readBufferDrainAtWriteCount[bufferIndex].lazySet(writeCount);
  }

  /** Updates the node's location in the page replacement policy. */
  @GuardedBy("evictionLock")
  void applyRead(Node<K, V> node) {
    // An entry may be scheduled for reordering despite having been removed. This can occur when the
    // entry was concurrently read while a writer was removing it. If the entry is no longer linked
    // then it does not need to be processed.
    if (accessOrderDeque.contains(node)) {
      accessOrderDeque.moveToBack(node);
    }
  }

  /** Updates the node's location in the expiration policy. */
  @GuardedBy("evictionLock")
  void applyWrite(Node<K, V> node) {
    // An entry may be scheduled for reordering despite having been removed. This can occur when the
    // entry was concurrently read while a writer was removing it. If the entry is no longer linked
    // then it does not need to be processed.
    if (writeOrderDeque.contains(node)) {
      writeOrderDeque.moveToBack(node);
    }
  }

  /** Drains the read buffer up to an amortized threshold. */
  @GuardedBy("evictionLock")
  void drainWriteBuffer() {
    for (int i = 0; i < WRITE_BUFFER_DRAIN_THRESHOLD; i++) {
      final Runnable task = writeBuffer.poll();
      if (task == null) {
        break;
      }
      task.run();
    }
  }

  /**
   * Atomically transitions the node from the <tt>alive</tt> state to the <tt>retired</tt> state, if
   * a valid transition.
   *
   * @param node the entry in the page replacement policy
   * @return the retired weighted value if the transition was successful or null otherwise
   */
  @Nullable V makeRetired(Node<K, V> node) {
    synchronized (node) {
      if (!node.isAlive()) {
        return null;
      }
      node.setWeight(-node.getWeight());
      return node.getValue();
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
      weightedSize.lazySet(weightedSize.get() - Math.abs(node.getWeight()));
      node.setWeight(Integer.MIN_VALUE);
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
      weightedSize.lazySet(weightedSize.get() + weight);

      // ignore out-of-order write operations
      if (node.isAlive()) {
        node.setWriteTime(ticker.read());
        if (expiresAfterWrite()) {
          writeOrderDeque.add(node);
        }
        if (evicts() || expiresAfterAccess()) {
          accessOrderDeque.add(node);
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
      if (expiresAfterWrite()) {
        writeOrderDeque.remove(node);
      }
      if (evicts() || expiresAfterAccess()) {
        accessOrderDeque.remove(node);
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
      weightedSize.lazySet(weightedSize.get() + weightDifference);
      applyWrite(node);
      applyRead(node);
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

  /**
   * Returns the weighted size of this map.
   *
   * @return the combined weight of the values in this map
   */
  public long weightedSize() {
    return Math.max(0, weightedSize.get());
  }

  @Override
  public void clear() {
    evictionLock.lock();
    try {
      // Discard all entries
      if (evicts() || expiresAfterAccess()) {
        Node<K, V> node;
        while ((node = accessOrderDeque.poll()) != null) {
          if (data.remove(node.getKeyReference(), node) && hasRemovalListener()) {
            K key = node.getKey();
            V value = node.getValue();
            if ((key == null) || (value == null)) {
              notifyRemoval(key, value, RemovalCause.COLLECTED);
            } else {
              notifyRemoval(key, value, RemovalCause.EXPLICIT);
            }
          }
          makeDead(node);
        }

        // Discard all pending reads
        for (AtomicReference<Node<K, V>>[] buffer : readBuffers) {
          for (AtomicReference<Node<K, V>> slot : buffer) {
            slot.lazySet(null);
          }
        }
      }

      // Apply all pending writes
      Runnable task;
      while ((task = writeBuffer.poll()) != null) {
        task.run();
      }

      if (expiresAfterWrite()) {
        Node<K, V> node;
        while ((node = writeOrderDeque.poll()) != null) {
          if (data.remove(node.getKeyReference(), node) && hasRemovalListener()) {
            K key = node.getKey();
            V value = node.getValue();
            if ((key == null) || (value == null)) {
              notifyRemoval(key, value, RemovalCause.COLLECTED);
            } else {
              notifyRemoval(key, value, RemovalCause.EXPLICIT);
            }
          }
          makeDead(node);
        }
      }

      if (collects()) {
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
          }
          makeDead(node);
        }
      }
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  public boolean containsKey(Object key) {
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    return (node != null) && (!hasExpired(node, ticker.read()));
  }

  @Override
  public boolean containsValue(Object value) {
    requireNonNull(value);

    long now = ticker.read();
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
    if (node == null) {
      if (recordStats) {
        statsCounter.recordMisses(1);
      }
      return null;
    } else if (hasExpired(node, ticker.read())) {
      if (recordStats) {
        statsCounter.recordMisses(1);
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
    long now = ticker.read();
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
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
    statsCounter.recordMisses(misses);
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

    final long now = ticker.read();
    final int weight = weigher.weigh(key, value);
    final Node<K, V> node = nodeFactory.newNode(
        key, keyReferenceQueue, value, valueReferenceQueue, weight, now);

    for (;;) {
      final Node<K, V> prior = data.putIfAbsent(node.getKeyReference(), node);
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
        prior.setValue(value, valueReferenceQueue);
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
    if (node == null) {
      return null;
    }

    V oldValue = makeRetired(node);
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
    if ((node == null) || (value == null)) {
      return false;
    }
    V oldValue;
    synchronized (node) {
      oldValue = node.getValue();
      if (node.isAlive() && node.containsValue(value)) {
        node.setWeight(-node.getWeight()); // retire
      } else {
        return false;
      }
    }
    data.remove(keyRef, node);
    if (hasRemovalListener()) {
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

    final int weight = weigher.weigh(key, value);
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
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
      node.setValue(value, valueReferenceQueue);
      node.setWeight(weight);
    }
    final int weightedDifference = (weight - oldWeight);
    if (weightedDifference == 0) {
      node.setWriteTime(ticker.read());
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

    final int weight = weigher.weigh(key, newValue);
    final Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if (node == null) {
      return false;
    }
    int oldWeight;
    synchronized (node) {
      if (!node.isAlive() || !node.containsValue(oldValue)) {
        return false;
      }
      oldWeight = node.getWeight();
      node.setValue(newValue, valueReferenceQueue);
      node.setWeight(weight);
    }
    final int weightedDifference = (weight - oldWeight);
    if (weightedDifference == 0) {
      node.setWriteTime(ticker.read());
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

    // optimistic fast path due to computeIfAbsent always locking is leveraged expiration check

    long now = ticker.read();
    Node<K, V> node = data.get(nodeFactory.newLookupKey(key));
    if ((node != null)) {
      if (hasExpired(node, now)) {
        if (data.remove(node.getKeyReference(), node)) {
          afterWrite(node, new RemovalTask(node));
          if (hasRemovalListener()) {
            notifyRemoval(key, node.getValue(), RemovalCause.EXPIRED);
          }
          statsCounter.recordEviction();
        }
      } else {
        afterRead(node, true);
        return node.getValue();
      }
    }

    int[] weight = new int[1];
    @SuppressWarnings("unchecked")
    V[] value = (V[]) new Object[1];
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue);
    node = data.computeIfAbsent(keyRef, k -> {
      value[0] = statsAware(mappingFunction, isAsync).apply(key);
      if (value[0] == null) {
        return null;
      }
      weight[0] = weigher.weigh(key, value[0]);
      return nodeFactory.newNode(key, keyReferenceQueue,
          value[0], valueReferenceQueue, weight[0], now);
    });
    if (node == null) {
      return null;
    }
    if (value[0] == null) {
      afterRead(node, true);
      return node.getValue();
    } else {
      afterWrite(node, new AddTask(node, weight[0]));
      return value[0];
    }
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
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
          makeRetired(prior);
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldValue, RemovalCause.EXPLICIT);
          }
          return null;
        }
        prior.setValue(keyRef, newValue[0], valueReferenceQueue);
        int oldWeight = prior.getWeight();
        int newWeight = weigher.weigh(key, newValue[0]);
        prior.setWeight(newWeight);

        final int weightedDifference = newWeight - oldWeight;
        if (weightedDifference != 0) {
          task[0] = new UpdateTask(prior, weightedDifference);
        }
        if (hasRemovalListener() && (newValue[0] != oldValue)) {
          notifyRemoval(key, oldValue, RemovalCause.REPLACED);
        }
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
    Object keyRef = nodeFactory.newReferenceKey(key, keyReferenceQueue);
    Runnable[] task = new Runnable[2];
    Node<K, V> node = data.compute(keyRef, (k, prior) -> {
      if (prior == null) {
        newValue[0] = statsAware(remappingFunction, recordMiss, isAsync).apply(key, null);
        if (newValue[0] == null) {
          return null;
        }
        final long now = ticker.read();
        final int weight = weigher.weigh(key, newValue[0]);
        final Node<K, V> newNode = nodeFactory.newNode(
            keyRef, newValue[0], valueReferenceQueue, weight, now);
        task[0] = new AddTask(newNode, weight);
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
          return null;
        }
        final int oldWeight = prior.getWeight();
        final int newWeight = weigher.weigh(key, newValue[0]);
        if (task[1] == null) {
          prior.setWeight(newWeight);
          prior.setValue(newValue[0], valueReferenceQueue);
          final int weightedDifference = newWeight - oldWeight;
          if (weightedDifference != 0) {
            task[0] = new UpdateTask(prior, weightedDifference);
          }
          if (hasRemovalListener() && (newValue[0] != oldValue)) {
            notifyRemoval(key, oldValue, RemovalCause.REPLACED);
          }
          return prior;
        } else {
          final long now = ticker.read();
          Node<K, V> newNode = nodeFactory.newNode(
              keyRef, newValue[0], valueReferenceQueue, newWeight, now);
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
    evictionLock.lock();
    try {
      drainBuffers();
    } finally {
      evictionLock.unlock();
    }
  }

  void asyncCleanup() {
    executor.execute(this::cleanUp);
  }

  @Override
  public RemovalListener<K, V> removalListener() {
    return removalListener;
  }

  @Override
  public StatsCounter statsCounter() {
    return statsCounter;
  }

  @Override
  public boolean isRecordingStats() {
    return isRecordingStats;
  }

  @Override
  public Ticker ticker() {
    return ticker;
  }

  @Override
  public Executor executor() {
    return executor;
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
    evictionLock.lock();
    try {
      drainBuffers();

      final int initialCapacity = (weigher == Weigher.singleton())
          ? Math.min(limit, (int) weightedSize())
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
      evictionLock.unlock();
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
        List<Object> keys = new ArrayList<>(data.size());
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
        List<Object> keys = new ArrayList<>(data.size());
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
    public boolean add(Entry<K, V> entry) {
      return (map.putIfAbsent(entry.getKey(), entry.getValue()) == null);
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
    final long now = ticker.read();
    Node<K, V> current;
    Node<K, V> next;

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }
      for (;;) {
        if (iterator.hasNext()) {
          next = iterator.next();
          if (hasExpired(next, now)) {
            next = null;
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
      current = next;
      next = null;
      return new WriteThroughEntry<>(BoundedLocalCache.this,
          current.getKey(), current.getValue());
    }

    @Override
    public void remove() {
      Caffeine.requireState(current != null);
      K key = current.getKey();
      if (key != null) {
        BoundedLocalCache.this.remove(key);
      }
      current = null;
    }
  }

  /** Creates a serialization proxy based on the common configuration shared by all cache types. */
  static <K, V> SerializationProxy<K, V> makeSerializationProxy(BoundedLocalCache<?, ?> cache) {
    SerializationProxy<K, V> proxy = new SerializationProxy<>();
    proxy.weakKeys = cache.nodeFactory.weakKeys();
    proxy.weakValues = cache.nodeFactory.weakValues();
    proxy.softValues = cache.nodeFactory.softValues();
    proxy.expireAfterAccessNanos = cache.expireAfterAccessNanos;
    proxy.expireAfterWriteNanos = cache.expireAfterWriteNanos;
    proxy.isRecordingStats = cache.isRecordingStats;
    proxy.removalListener = cache.removalListener;
    proxy.ticker = cache.ticker;
    if (cache.evicts()) {
      if (cache.weigher == Weigher.singleton()) {
        proxy.maximumSize = cache.capacity();
      } else {
        proxy.weigher = cache.weigher;
        proxy.maximumWeight = cache.capacity();
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
      cache = new BoundedLocalCache<>(builder, loader, false);
      isWeighted = (builder.weigher != null);
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
      return makeSerializationProxy(cache);
    }
  }

  static final class BoundedPolicy<K, V> implements Policy<K, V> {
    final BoundedLocalCache<K, V> cache;
    final Function<V, V> transformer;
    final boolean isWeighted;

    Optional<Eviction<K, V>> eviction;
    Optional<Expiration<K, V>> afterWrite;
    Optional<Expiration<K, V>> afterAccess;

    BoundedPolicy(BoundedLocalCache<K, V> cache, Function<V, V> transformer, boolean isWeighted) {
      this.cache = cache;
      this.isWeighted = isWeighted;
      this.transformer = transformer;
    }

    @Override public Optional<Eviction<K, V>> eviction() {
      return cache.evicts()
          ? (eviction == null) ? (eviction = Optional.of(new BoundedEviction())) : eviction
          : Optional.empty();
    }
    @Override public Optional<Expiration<K, V>> expireAfterAccess() {
      if (!(cache.expireAfterAccessNanos >= 0)) {
        return Optional.empty();
      }
      return (afterAccess == null)
          ? (afterAccess = Optional.of(new BoundedExpireAfterAccess()))
          : afterAccess;
    }
    @Override public Optional<Expiration<K, V>> expireAfterWrite() {
      if (!(cache.expireAfterWriteNanos >= 0)) {
        return Optional.empty();
      }
      return (afterWrite == null)
          ? (afterWrite = Optional.of(new BoundedExpireAfterWrite()))
          : afterWrite;
    }

    final class BoundedEviction implements Eviction<K, V> {
      @Override public boolean isWeighted() {
        return isWeighted;
      }
      @Override public OptionalLong weightedSize() {
        return isWeighted() ? OptionalLong.of(cache.weightedSize()) : OptionalLong.empty();
      }
      @Override public long getMaximumSize() {
        return cache.capacity();
      }
      @Override public void setMaximumSize(long maximumSize) {
        cache.setCapacity(maximumSize);
      }
      @Override public Map<K, V> coldest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque, transformer, true, limit);
      }
      @Override public Map<K, V> hottest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque, transformer, false, limit);
      }
    }

    final class BoundedExpireAfterAccess implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.ticker.read() - node.getAccessTime();
        return (age > cache.expireAfterAccessNanos)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.expireAfterAccessNanos, TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        Caffeine.requireArgument(duration >= 0);
        cache.expireAfterAccessNanos = unit.toNanos(duration);
        cache.asyncCleanup();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque, transformer, true, limit);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.orderedMap(cache.accessOrderDeque, transformer, false, limit);
      }
    }

    final class BoundedExpireAfterWrite implements Expiration<K, V> {
      @Override public OptionalLong ageOf(K key, TimeUnit unit) {
        Object keyRef = cache.nodeFactory.newLookupKey(key);
        Node<?, ?> node = cache.data.get(keyRef);
        if (node == null) {
          return OptionalLong.empty();
        }
        long age = cache.ticker.read() - node.getWriteTime();
        return (age > cache.expireAfterWriteNanos)
            ? OptionalLong.empty()
            : OptionalLong.of(unit.convert(age, TimeUnit.NANOSECONDS));
      }
      @Override public long getExpiresAfter(TimeUnit unit) {
        return unit.convert(cache.expireAfterWriteNanos, TimeUnit.NANOSECONDS);
      }
      @Override public void setExpiresAfter(long duration, TimeUnit unit) {
        Caffeine.requireArgument(duration >= 0);
        cache.expireAfterWriteNanos = unit.toNanos(duration);
        cache.asyncCleanup();
      }
      @Override public Map<K, V> oldest(int limit) {
        return cache.orderedMap(cache.writeOrderDeque, transformer, true, limit);
      }
      @Override public Map<K, V> youngest(int limit) {
        return cache.orderedMap(cache.writeOrderDeque, transformer, false, limit);
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
    public CacheLoader<? super K, V> loader() {
      return cache().loader;
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
      proxy.loader = cache.loader;
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
      super(new BoundedLocalCache<>((Caffeine<K, CompletableFuture<V>>) builder,
          key -> loader.asyncLoad(key, builder.getExecutor()), true), loader);
      this.isWeighted = builder.isWeighted();
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
      SerializationProxy<K, V> proxy = makeSerializationProxy(cache);
      proxy.loader = loader;
      proxy.async = true;
      return proxy;
    }
  }
}
