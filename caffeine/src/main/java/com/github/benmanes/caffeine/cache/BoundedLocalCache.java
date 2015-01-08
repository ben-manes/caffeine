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
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.caffeine.atomic.PaddedAtomicLong;
import com.github.benmanes.caffeine.atomic.PaddedAtomicReference;
import com.github.benmanes.caffeine.cache.Caffeine.Strength;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * A hash table supporting full concurrency of retrievals, adjustable expected
 * concurrency for updates, and a maximum capacity to bound the map by. This
 * implementation differs from {@link ConcurrentHashMap} in that it maintains a
 * page replacement algorithm that is used to evict an entry when the map has
 * exceeded its capacity. Unlike the <tt>Java Collections Framework</tt>, this
 * map does not have a publicly visible constructor and instances are created
 * through a {@link Caffeine}.
 * <p>
 * An entry is evicted from the map when the <tt>weighted capacity</tt> exceeds
 * its <tt>maximum weighted capacity</tt> threshold. A {@link Weigher}
 * determines how many units of capacity that an entry consumes. The default
 * weigher assigns each value a weight of <tt>1</tt> to bound the map by the
 * total number of key-value pairs. A map that holds collections may choose to
 * weigh values by the number of elements in the collection and bound the map
 * by the total number of elements that it contains. A change to a value that
 * modifies its weight requires that an update operation is performed on the
 * map.
 * <p>
 * An {@link RemovalListener} may be supplied for notification when an entry
 * is evicted from the map. This listener is invoked on a caller's thread and
 * will not block other threads from operating on the map. An implementation
 * should be aware that the caller's thread will not expect long execution
 * times or failures as a side effect of the listener being notified. Execution
 * safety and a fast turn around time can be achieved by performing the
 * operation asynchronously, such as by submitting a task to an
 * {@link java.util.concurrent.ExecutorService}.
 * <p>
 * The <tt>concurrency level</tt> determines the number of threads that can
 * concurrently modify the table. Using a significantly higher or lower value
 * than needed can waste space or lead to thread contention, but an estimate
 * within an order of magnitude of the ideal value does not usually have a
 * noticeable impact. Because placement in hash tables is essentially random,
 * the actual concurrency will vary.
 * <p>
 * This class and its views and iterators implement all of the
 * <em>optional</em> methods of the {@link Map} and {@link Iterator}
 * interfaces.
 * <p>
 * Like {@link java.util.Hashtable} but unlike {@link HashMap}, this class
 * does <em>not</em> allow <tt>null</tt> to be used as a key or value. Unlike
 * {@link java.util.LinkedHashMap}, this class does <em>not</em> provide
 * predictable iteration order. A snapshot of the keys and entries may be
 * obtained in ascending and descending order of retention.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 */
@ThreadSafe
final class BoundedLocalCache<K, V> extends AbstractMap<K, V>
    implements ConcurrentMap<K, V>, Serializable {

  /*
   * This class performs a best-effort bounding of a ConcurrentHashMap using a
   * page-replacement algorithm to determine which entries to evict when the
   * capacity is exceeded.
   *
   * The page replacement algorithm's data structures are kept eventually
   * consistent with the map. An update to the map and recording of reads may
   * not be immediately reflected on the algorithm's data structures. These
   * structures are guarded by a lock and operations are applied in batches to
   * avoid lock contention. The penalty of applying the batches is spread across
   * threads so that the amortized cost is slightly higher than performing just
   * the ConcurrentHashMap operation.
   *
   * A memento of the reads and writes that were performed on the map are
   * recorded in buffers. These buffers are drained at the first opportunity
   * after a write or when the read buffer exceeds a threshold size. The reads
   * are recorded in a lossy buffer, allowing the reordering operations to be
   * discarded if the draining process cannot keep up. Due to the concurrent
   * nature of the read and write operations a strict policy ordering is not
   * possible, but is observably strict when single threaded.
   *
   * Due to a lack of a strict ordering guarantee, a task can be executed
   * out-of-order, such as a removal followed by its addition. The state of the
   * entry is encoded within the value's weight.
   *
   * Alive: The entry is in both the hash-table and the page replacement policy.
   * This is represented by a positive weight.
   *
   * Retired: The entry is not in the hash-table and is pending removal from the
   * page replacement policy. This is represented by a negative weight.
   *
   * Dead: The entry is not in the hash-table and is not in the page replacement
   * policy. This is represented by a weight of zero.
   *
   * The Least Recently Used page replacement algorithm was chosen due to its
   * simplicity, high hit rate, and ability to be implemented with O(1) time
   * complexity.
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

  // How long after the last access to an entry the map will retain that entry
  volatile long expireAfterAccessNanos;
  @GuardedBy("evictionLock")
  final AccessOrderDeque<Node<K, V>> accessOrderDeque;

  // How long after the last write to an entry the map will retain that entry
  volatile long expireAfterWriteNanos;
  @GuardedBy("evictionLock")
  final WriteOrderDeque<Node<K, V>> writeOrderDeque;

  // These fields provide support for reference-based eviction
  final ReferenceStrategy keyStrategy;
  final ReferenceStrategy valueStrategy;
  final ReferenceQueue<K> keyReferenceQueue;
  final ReferenceQueue<V> valueReferenceQueue;

  // These fields provide support to bound the map by a maximum capacity
  final Weigher<? super K, ? super V> weigher;
  @GuardedBy("evictionLock") // must write under lock
  final PaddedAtomicLong weightedSize;
  @GuardedBy("evictionLock") // must write under lock
  final PaddedAtomicLong maximumWeightedSize;

  final Lock evictionLock;
  final Queue<Runnable> writeBuffer;
  final PaddedAtomicReference<DrainStatus> drainStatus;

  @GuardedBy("evictionLock")
  final long[] readBufferReadCount;
  final PaddedAtomicLong[] readBufferWriteCount;
  final PaddedAtomicLong[] readBufferDrainAtWriteCount;
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
  private BoundedLocalCache(Caffeine<K, V> builder) {
    // The data store and its maximum capacity
    data = new ConcurrentHashMap<>(builder.getInitialCapacity());

    // The expiration support
    expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
    expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
    writeOrderDeque = new WriteOrderDeque<Node<K, V>>();
    accessOrderDeque = new AccessOrderDeque<Node<K, V>>();

    boolean evicts = (builder.getMaximumWeight() != Caffeine.UNSET_INT);
    maximumWeightedSize = evicts
        ? new PaddedAtomicLong(Math.min(builder.getMaximumWeight(), MAXIMUM_CAPACITY))
        : null;
    readBufferReadCount = new long[NUMBER_OF_READ_BUFFERS];
    readBufferWriteCount = new PaddedAtomicLong[NUMBER_OF_READ_BUFFERS];
    readBufferDrainAtWriteCount = new PaddedAtomicLong[NUMBER_OF_READ_BUFFERS];
    readBuffers = new AtomicReference[NUMBER_OF_READ_BUFFERS][READ_BUFFER_SIZE];
    for (int i = 0; i < NUMBER_OF_READ_BUFFERS; i++) {
      readBufferWriteCount[i] = new PaddedAtomicLong();
      readBufferDrainAtWriteCount[i] = new PaddedAtomicLong();
      readBuffers[i] = new AtomicReference[READ_BUFFER_SIZE];
      for (int j = 0; j < READ_BUFFER_SIZE; j++) {
        readBuffers[i][j] = new AtomicReference<Node<K, V>>();
      }
    }
    writeBuffer = new ConcurrentLinkedQueue<Runnable>();

    // The eviction support
    evictionLock = new Sync();
    weigher = builder.getWeigher();
    weightedSize = new PaddedAtomicLong();
    drainStatus = new PaddedAtomicReference<DrainStatus>(IDLE);

    // The reference eviction support
    keyStrategy = ReferenceStrategy.forStength(builder.getKeyStrength());
    valueStrategy = ReferenceStrategy.forStength(builder.getValueStrength());
    keyReferenceQueue = new ReferenceQueue<K>();
    valueReferenceQueue = new ReferenceQueue<V>();

    // The notification queue and listener
    removalListener = builder.getRemovalListener();
    executor = builder.getExecutor();

    // The statistics
    statsCounter = builder.getStatsCounterSupplier().get();
    isRecordingStats = builder.isRecordingStats();
    ticker = builder.getTicker();
  }

  /** Returns whether this cache notifies when an entry is removed. */
  boolean hasRemovalListener() {
    return (removalListener != null);
  }

  /** Asynchronously sends a removal notification to the listener. */
  protected void notifyRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
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
    return (keyStrategy != ReferenceStrategy.STRONG);
  }

  boolean collectValues() {
    return (valueStrategy != ReferenceStrategy.STRONG);
  }

  boolean collects() {
    return collectKeys() || collectValues();
  }

  /* ---------------- Expiration Support -------------- */

  boolean expires() {
    return expiresAfterWrite() || expiresAfterAccess();
  }

  boolean expiresAfterWrite() {
    return expireAfterWriteNanos > 0;
  }

  boolean expiresAfterAccess() {
    return expireAfterAccessNanos > 0;
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
   * Sets the maximum weighted capacity of the map and eagerly evicts entries
   * until it shrinks to the appropriate size.
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
   * Evicts entries from the map while it exceeds the capacity and appends
   * evicted entries to the notification queue for processing.
   */
  @GuardedBy("evictionLock")
  void evict() {
    if (!evicts()) {
      return;
    }

    // Attempts to evict entries from the map if it exceeds the maximum
    // capacity. If the eviction fails due to a concurrent removal of the
    // victim, that removal may cancel out the addition that triggered this
    // eviction. The victim is eagerly unlinked before the removal task so
    // that if an eviction is still required then a new victim will be chosen
    // for removal.
    while (hasOverflowed()) {
      final Node<K, V> node = accessOrderDeque.poll();

      // If weighted values are used, then the pending operations will adjust
      // the size to reflect the correct weight
      if (node == null) {
        return;
      }

      evict(node, RemovalCause.SIZE);
    }
  }

  @GuardedBy("evictionLock")
  void evict(Node<K, V> node, RemovalCause cause) {
    makeDead(node);

    // Notify the listener only if the entry was evicted
    if (data.remove(node.keyRef, node)) {
      if (hasRemovalListener()) {
        notifyRemoval(node.getKey(keyStrategy), node.getValue(valueStrategy), cause);
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
    node.setAccessTime(ticker.read());
    if (evicts() || expiresAfterAccess()) {
      final int bufferIndex = readBufferIndex();
      final long writeCount = recordRead(bufferIndex, node);
      drainOnReadIfNeeded(bufferIndex, writeCount);
    }
  }

  /** Returns the index to the read buffer to record into. */
  static int readBufferIndex() {
    // A buffer is chosen by the thread's id so that tasks are distributed in a
    // pseudo evenly manner. This helps avoid hot entries causing contention
    // due to other threads trying to append to the same buffer.
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
    // The location in the buffer is chosen in a racy fashion as the increment
    // is not atomic with the insertion. This means that concurrent reads can
    // overlap and overwrite one another, resulting in a lossy buffer.
    final PaddedAtomicLong counter = readBufferWriteCount[bufferIndex];
    final long writeCount = counter.get();
    counter.lazySet(writeCount + 1);

    final int index = (int) (writeCount & READ_BUFFER_INDEX_MASK);
    readBuffers[bufferIndex][index].lazySet(node);

    return writeCount;
  }

  /**
   * Attempts to drain the buffers if it is determined to be needed when
   * post-processing a read.
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
   * Attempts to acquire the eviction lock and apply the pending operations, up
   * to the amortized threshold, to the page replacement policy.
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
    // An entry may be scheduled for reordering despite having been removed.
    // This can occur when the entry was concurrently read while a writer was
    // removing it. If the entry is no longer linked then it does not need to
    // be processed.
    if (accessOrderDeque.contains(node)) {
      accessOrderDeque.moveToBack(node);
    }
  }

  /** Updates the node's location in the expiration policy. */
  @GuardedBy("evictionLock")
  void applyWrite(Node<K, V> node) {
    // An entry may be scheduled for reordering despite having been removed.
    // This can occur when the entry was concurrently read while a writer was
    // removing it. If the entry is no longer linked then it does not need to
    // be processed.
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
   * Attempts to transition the node from the <tt>alive</tt> state to the
   * <tt>retired</tt> state.
   *
   * @param node the entry in the page replacement policy
   * @param expect the expected weighted value
   * @return if successful
   */
  boolean tryToRetire(Node<K, V> node, WeightedValue<V> expect) {
    if (expect.isAlive()) {
      final WeightedValue<V> retired = new WeightedValue<V>(
          expect.getValueRef(), -expect.weight);
      synchronized (node) {
        if (node.get() == expect) {
          node.lazySet(retired);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Atomically transitions the node from the <tt>alive</tt> state to the
   * <tt>retired</tt> state, if a valid transition.
   *
   * @param node the entry in the page replacement policy
   * @return the retired weighted value if the transition was successful or null otherwise
   */
  @Nullable WeightedValue<V> makeRetired(Node<K, V> node) {
    synchronized (node) {
      final WeightedValue<V> current = node.get();
      if (!current.isAlive()) {
        return null;
      }
      final WeightedValue<V> retired = new WeightedValue<V>(
          current.getValueRef(), -current.weight);
      node.lazySet(retired);
      return retired;
    }
  }

  /**
   * Atomically transitions the node to the <tt>dead</tt> state and decrements
   * the <tt>weightedSize</tt>.
   *
   * @param node the entry in the page replacement policy
   */
  @GuardedBy("evictionLock")
  void makeDead(Node<K, V> node) {
    synchronized (node) {
      WeightedValue<V> current = node.get();
      WeightedValue<V> dead = new WeightedValue<V>(current.getValueRef(), 0);
      if (node.compareAndSet(current, dead)) {
        weightedSize.lazySet(weightedSize.get() - Math.abs(current.weight));
        return;
      }
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
      if (node.get().isAlive()) {
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
  public long mappingCount() {
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
          // FIXME(ben): Handle case when key is null (weak reference)
          if (data.remove(node.keyRef, node) && hasRemovalListener()) {
            K key = node.getKey(keyStrategy);
            notifyRemoval(key, node.getValue(valueStrategy), RemovalCause.EXPLICIT);
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
          if (data.remove(node.keyRef, node) && hasRemovalListener()) {
            K key = node.getKey(keyStrategy);
            notifyRemoval(key, node.getValue(valueStrategy), RemovalCause.EXPLICIT);
          }
          makeDead(node);
        }
      }

      if (collects()) {
        for (Entry<Object, Node<K, V>> entry : data.entrySet()) {
          Node<K, V> node = entry.getValue();
          if (data.remove(node.keyRef, node) && hasRemovalListener()) {
            K key = node.getKey(keyStrategy);
            notifyRemoval(key, node.getValue(valueStrategy), RemovalCause.EXPLICIT);
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
    Node<K, V> node = data.get(key);
    return (node != null) && (!hasExpired(node, ticker.read()));
  }

  @Override
  public boolean containsValue(Object value) {
    requireNonNull(value);

    long now = ticker.read();
    for (Node<K, V> node : data.values()) {
      if (value.equals(node.getValue(valueStrategy)) && !hasExpired(node, now)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public V get(Object key) {
    return getIfPresent(key, false);
  }

  public V getIfPresent(Object key, boolean recordStats) {
    final Node<K, V> node = data.get(keyStrategy.getKeyRef(key));
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
    return node.getValue(valueStrategy);
  }

  // TODO(ben): JavaDoc
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int misses = 0;
    long now = ticker.read();
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      final Node<K, V> node = data.get(keyStrategy.getKeyRef(key));
      if ((node == null) || hasExpired(node, now)) {
        misses++;
        continue;
      }
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      V value = node.getValue(valueStrategy);
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
   * Adds a node to the list and the data store. If an existing node is found,
   * then its value is updated if allowed.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param onlyIfAbsent a write is performed only if the key is not already
   *     associated with a value
   * @return the prior value in the data store or null if no mapping was found
   */
  V put(K key, V value, boolean onlyIfAbsent) {
    requireNonNull(key);
    requireNonNull(value);

    final long now = ticker.read();
    final int weight = weigher.weigh(key, value);
    final Object keyRef = keyStrategy.referenceKey(key, keyReferenceQueue);
    final Object valueRef = valueStrategy.referenceValue(keyRef, value, valueReferenceQueue);
    final WeightedValue<V> weightedValue = new WeightedValue<V>(valueRef, weight);
    final Node<K, V> node = new Node<K, V>(keyRef, weightedValue, now);

    for (;;) {
      final Node<K, V> prior = data.putIfAbsent(keyRef, node);
      if (prior == null) {
        afterWrite(node, new AddTask(node, weight));
        return null;
      } else if (onlyIfAbsent) {
        afterRead(prior, false);
        return prior.getValue(valueStrategy);
      }
      WeightedValue<V> oldWeightedValue;
      synchronized (prior) {
        oldWeightedValue = prior.get();
        if (!oldWeightedValue.isAlive()) {
          continue;
        }
        prior.lazySet(weightedValue);
      }

      final int weightedDifference = weight - oldWeightedValue.weight;
      if (!expiresAfterWrite() && (weightedDifference == 0)) {
        afterRead(prior, false);
      } else {
        afterWrite(prior, new UpdateTask(prior, weightedDifference));
      }
      if (hasRemovalListener()) {
        notifyRemoval(key, oldWeightedValue.getValue(valueStrategy), RemovalCause.REPLACED);
      }
      return oldWeightedValue.getValue(valueStrategy);
    }
  }

  @Override
  public V remove(Object key) {
    final Node<K, V> node = data.remove(keyStrategy.getKeyRef(key));
    if (node == null) {
      return null;
    }

    WeightedValue<V> retired = makeRetired(node);
    afterWrite(node, new RemovalTask(node));

    if (hasRemovalListener() && (retired != null)) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      notifyRemoval(castKey, retired.getValue(valueStrategy), RemovalCause.EXPLICIT);
    }
    return node.getValue(valueStrategy);
  }

  @Override
  public boolean remove(Object key, Object value) {
    Object keyRef = keyStrategy.getKeyRef(key);
    final Node<K, V> node = data.get(keyRef);
    if ((node == null) || (value == null)) {
      return false;
    }

    WeightedValue<V> weightedValue = node.get();
    for (;;) {
      if (weightedValue.contains(value, valueStrategy)) {
        if (tryToRetire(node, weightedValue)) {
          if (data.remove(keyRef, node)) {
            if (hasRemovalListener()) {
              @SuppressWarnings("unchecked")
              K castKey = (K) key;
              notifyRemoval(castKey, node.getValue(valueStrategy), RemovalCause.EXPLICIT);
            }
            afterWrite(node, new RemovalTask(node));
            return true;
          }
        } else {
          weightedValue = node.get();
          if (weightedValue.isAlive()) {
            // retry as an intermediate update may have replaced the value with
            // an equal instance that has a different reference identity
            continue;
          }
        }
      }
      return false;
    }
  }

  @Override
  public V replace(K key, V value) {
    requireNonNull(key);
    requireNonNull(value);

    final int weight = weigher.weigh(key, value);
    final Node<K, V> node = data.get(keyStrategy.getKeyRef(key));
    if (node == null) {
      return null;
    }
    WeightedValue<V> oldWeightedValue;
    synchronized (node) {
      oldWeightedValue = node.get();
      if (!oldWeightedValue.isAlive()) {
        return null;
      }
      final WeightedValue<V> weightedValue = new WeightedValue<V>(
          valueStrategy.referenceValue(node.keyRef, value, valueReferenceQueue), weight);
      node.lazySet(weightedValue);
    }
    final int weightedDifference = weight - oldWeightedValue.weight;
    if (weightedDifference == 0) {
      node.setWriteTime(ticker.read());
      afterRead(node, false);
    } else {
      afterWrite(node, new UpdateTask(node, weightedDifference));
    }
    if (hasRemovalListener()) {
      notifyRemoval(key, oldWeightedValue.getValue(valueStrategy), RemovalCause.REPLACED);
    }
    return oldWeightedValue.getValue(valueStrategy);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(key);
    requireNonNull(oldValue);
    requireNonNull(newValue);

    final int weight = weigher.weigh(key, newValue);

    final Node<K, V> node = data.get(keyStrategy.getKeyRef(key));
    if (node == null) {
      return false;
    }
    WeightedValue<V> oldWeightedValue;
    synchronized (node) {
      oldWeightedValue = node.get();
      if (!oldWeightedValue.isAlive() || !oldWeightedValue.contains(oldValue, valueStrategy)) {
        return false;
      }
      final WeightedValue<V> newWeightedValue = new WeightedValue<V>(
          valueStrategy.referenceValue(node.keyRef, newValue, valueReferenceQueue), weight);
      node.lazySet(newWeightedValue);
    }
    final int weightedDifference = weight - oldWeightedValue.weight;
    if (weightedDifference == 0) {
      node.setWriteTime(ticker.read());
      afterRead(node, false);
    } else {
      afterWrite(node, new UpdateTask(node, weightedDifference));
    }
    if (hasRemovalListener()) {
      notifyRemoval(key, oldWeightedValue.getValue(valueStrategy), RemovalCause.REPLACED);
    }
    return true;
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    requireNonNull(key);
    requireNonNull(mappingFunction);

    // optimistic fast path due to computeIfAbsent always locking is leveraged expiration check

    long now = ticker.read();
    Node<K, V> node = data.get(keyStrategy.getKeyRef(key));
    if ((node != null)) {
      if (hasExpired(node, now)) {
        if (data.remove(node.keyRef, node)) {
          afterWrite(node, new RemovalTask(node));
          if (hasRemovalListener()) {
            notifyRemoval(key, node.getValue(valueStrategy), RemovalCause.EXPIRED);
          }
        }
      } else {
        afterRead(node, true);
        return node.getValue(valueStrategy);
      }
    }

    @SuppressWarnings("unchecked")
    WeightedValue<V>[] weightedValue = new WeightedValue[1];
    Object keyRef = keyStrategy.referenceKey(key, keyReferenceQueue);
    node = data.computeIfAbsent(keyRef, k -> {
      V value;
      value = statsAware(mappingFunction).apply(key);
      if (value == null) {
        return null;
      }
      int weight = weigher.weigh(key, value);
      Object valueRef = valueStrategy.referenceValue(keyRef, value, valueReferenceQueue);
      weightedValue[0] = new WeightedValue<V>(valueRef, weight);
      return new Node<K, V>(keyRef, weightedValue[0], now);
    });
    if (node == null) {
      return null;
    }
    if (weightedValue[0] == null) {
      afterRead(node, true);
      return node.getValue(valueStrategy);
    } else {
      afterWrite(node, new AddTask(node, weightedValue[0].weight));
      return weightedValue[0].getValue(valueStrategy);
    }
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    Object ref = keyStrategy.getKeyRef(key);
    if (!data.containsKey(ref)) {
      return null;
    }

    @SuppressWarnings("unchecked")
    WeightedValue<V>[] weightedValue = new WeightedValue[1];
    Runnable[] task = new Runnable[1];
    Node<K, V> node = data.computeIfPresent(ref, (keyRef, prior) -> {
      statsCounter.recordHits(1);
      synchronized (prior) {
        WeightedValue<V> oldWeightedValue = prior.get();
        V newValue = statsAware(remappingFunction).apply(
            key, oldWeightedValue.getValue(valueStrategy));
        if (newValue == null) {
          makeRetired(prior);
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, prior.getValue(valueStrategy), RemovalCause.EXPLICIT);
          }
          return null;
        }
        int weight = weigher.weigh(key, newValue);
        Object newValueRef = valueStrategy.referenceValue(keyRef, newValue, valueReferenceQueue);
        WeightedValue<V> newWeightedValue = new WeightedValue<V>(newValueRef, weight);
        prior.lazySet(newWeightedValue);
        weightedValue[0] = newWeightedValue;
        final int weightedDifference = weight - oldWeightedValue.weight;
        if (weightedDifference != 0) {
          task[0] = new UpdateTask(prior, weightedDifference);
        }
        if (hasRemovalListener()) {
          notifyRemoval(key, prior.getValue(valueStrategy), RemovalCause.REPLACED);
        }
        return prior;
      }
    });
    if (task[0] == null) {
      afterRead(node, false);
    } else {
      afterWrite(node, task[0]);
    }
    return (weightedValue[0] == null) ? null : weightedValue[0].getValue(valueStrategy);
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    return compute(key, remappingFunction, false);
  }

  V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
      boolean recordMiss) {
    requireNonNull(key);
    requireNonNull(remappingFunction);

    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    Object keyRef = keyStrategy.referenceKey(key, keyReferenceQueue);
    Runnable[] task = new Runnable[2];
    Node<K, V> node = data.compute(keyRef, (k, prior) -> {
      if (prior == null) {
        newValue[0] = statsAware(remappingFunction, recordMiss).apply(key, null);
        if (newValue[0] == null) {
          return null;
        }
        final long now = ticker.read();
        final int weight = weigher.weigh(key, newValue[0]);
        final WeightedValue<V> weightedValue = new WeightedValue<V>(
            valueStrategy.referenceValue(keyRef, newValue[0], valueReferenceQueue), weight);
        final Node<K, V> newNode = new Node<K, V>(keyRef, weightedValue, now);
        task[0] = new AddTask(newNode, weight);
        return newNode;
      }
      synchronized (prior) {
        WeightedValue<V> oldWeightedValue = prior.get();
        V oldValue;
        if (oldWeightedValue.isAlive()) {
          oldValue = oldWeightedValue.getValue(valueStrategy);
        } else {
          // conditionally removed won, but we got the entry lock first
          // so help out and pretend like we are inserting a fresh entry
          task[1] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldWeightedValue.getValue(valueStrategy), RemovalCause.EXPLICIT);
          }
          oldValue = null;
        }
        newValue[0] = statsAware(remappingFunction, recordMiss).apply(key, oldValue);
        if ((newValue[0] == null) && (oldValue != null)) {
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldWeightedValue.getValue(valueStrategy), RemovalCause.EXPLICIT);
          }
          return null;
        }
        final int weight = weigher.weigh(key, newValue[0]);
        final WeightedValue<V> weightedValue = new WeightedValue<V>(
            valueStrategy.referenceValue(prior.keyRef, newValue[0], valueReferenceQueue), weight);
        Node<K, V> newNode;
        if (task[1] == null) {
          newNode = prior;
          prior.lazySet(weightedValue);
          final int weightedDifference = weight - oldWeightedValue.weight;
          if (weightedDifference != 0) {
            task[0] = new UpdateTask(prior, weightedDifference);
          }
          if (hasRemovalListener()) {
            notifyRemoval(key, oldWeightedValue.getValue(valueStrategy), RemovalCause.REPLACED);
          }
        } else {
          final long now = ticker.read();
          newNode = new Node<>(key, weightedValue, now);
          task[0] = new AddTask(newNode, weight);
        }
        return prior;
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

  /**
   * V oldValue = map.get(key);
   * V newValue = (oldValue == null) ? value :
   *              remappingFunction.apply(oldValue, value);
   * if (newValue == null)
   *     map.remove(key);
   * else
   *     map.put(key, newValue);
   */
  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    requireNonNull(key);
    requireNonNull(value);
    requireNonNull(remappingFunction);

    if (true) {
      return super.merge(key, value, statsAware(remappingFunction));
    }

    final long now = ticker.read();
    final int weight = weigher.weigh(key, value);
    final WeightedValue<V> weightedValue = new WeightedValue<V>(value, weight);
    final Object keyRef = keyStrategy.referenceKey(key, keyReferenceQueue);
    final Node<K, V> node = new Node<K, V>(keyRef, weightedValue, now);
    data.merge(key, node, (k, prior) -> {
      synchronized (prior) {
        WeightedValue<V> oldWeightedValue = prior.get();
        if (!oldWeightedValue.isAlive()) {
          // conditionally removed won, but we got the entry lock first
          // so help out and pretend like we are inserting a fresh entry
          // ...
        }
        remappingFunction.apply(oldWeightedValue.getValue(valueStrategy), value);
      }
      return null;
    });

    return null;
  }

  /** Decorates the remapping function to record statistics if enabled. */
  Function<? super K, ? extends V> statsAware(Function<? super K, ? extends V> mappingFunction) {
    if (!isRecordingStats) {
      return mappingFunction;
    }
    return key -> {
      V value;
      statsCounter.recordMisses(1);
      long startTime = ticker.read();
      try {
        value = mappingFunction.apply(key);
      } catch (RuntimeException | Error e) {
        statsCounter.recordLoadFailure(ticker.read() - startTime);
        throw e;
      }
      long loadTime = ticker.read() - startTime;
      if (value == null) {
        statsCounter.recordLoadFailure(loadTime);
      } else {
        statsCounter.recordLoadSuccess(loadTime);
      }
      return value;
    };
  }

  <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction) {
    return statsAware(remappingFunction, true);
  }

  /** Decorates the remapping function to record statistics if enabled. */
  <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction, boolean recordMiss) {
    if (!isRecordingStats) {
      return remappingFunction;
    }
    return (t, u) -> {
      R result;
      if ((u == null) && recordMiss) {
        statsCounter.recordMisses(1);
      }
      long startTime = ticker.read();
      try {
        result = remappingFunction.apply(t, u);
      } catch (RuntimeException | Error e) {
        statsCounter.recordLoadFailure(ticker.read() - startTime);
        throw e;
      }
      long loadTime = ticker.read() - startTime;
      if (result == null) {
        statsCounter.recordLoadFailure(loadTime);
      } else {
        statsCounter.recordLoadSuccess(loadTime);
      }
      return result;
    };
  }

  @Override
  public Set<K> keySet() {
    final Set<K> ks = keySet;
    return (ks == null) ? (keySet = new KeySet()) : ks;
  }

  /**
   * Returns a unmodifiable snapshot {@link Set} view of the keys contained in
   * this map. The set's iterator returns the keys whose order of iteration is
   * the ascending order in which its entries are considered eligible for
   * retention, from the least-likely to be retained to the most-likely.
   * <p>
   * Beware that, unlike in {@link #keySet()}, obtaining the set is <em>NOT</em>
   * a constant-time operation. Because of the asynchronous nature of the page
   * replacement policy, determining the retention ordering requires a traversal
   * of the keys.
   *
   * @return an ascending snapshot view of the keys in this map
   */
  public Set<K> ascendingKeySet() {
    return ascendingKeySetWithLimit(Integer.MAX_VALUE);
  }

  /**
   * Returns an unmodifiable snapshot {@link Set} view of the keys contained in
   * this map. The set's iterator returns the keys whose order of iteration is
   * the ascending order in which its entries are considered eligible for
   * retention, from the least-likely to be retained to the most-likely.
   * <p>
   * Beware that, unlike in {@link #keySet()}, obtaining the set is <em>NOT</em>
   * a constant-time operation. Because of the asynchronous nature of the page
   * replacement policy, determining the retention ordering requires a traversal
   * of the keys.
   *
   * @param limit the maximum size of the returned set
   * @return a ascending snapshot view of the keys in this map
   * @throws IllegalArgumentException if the limit is negative
   */
  public Set<K> ascendingKeySetWithLimit(int limit) {
    return orderedKeySet(true, limit);
  }

  /**
   * Returns an unmodifiable snapshot {@link Set} view of the keys contained in
   * this map. The set's iterator returns the keys whose order of iteration is
   * the descending order in which its entries are considered eligible for
   * retention, from the most-likely to be retained to the least-likely.
   * <p>
   * Beware that, unlike in {@link #keySet()}, obtaining the set is <em>NOT</em>
   * a constant-time operation. Because of the asynchronous nature of the page
   * replacement policy, determining the retention ordering requires a traversal
   * of the keys.
   *
   * @return a descending snapshot view of the keys in this map
   */
  public Set<K> descendingKeySet() {
    return descendingKeySetWithLimit(Integer.MAX_VALUE);
  }

  /**
   * Returns an unmodifiable snapshot {@link Set} view of the keys contained in
   * this map. The set's iterator returns the keys whose order of iteration is
   * the descending order in which its entries are considered eligible for
   * retention, from the most-likely to be retained to the least-likely.
   * <p>
   * Beware that, unlike in {@link #keySet()}, obtaining the set is <em>NOT</em>
   * a constant-time operation. Because of the asynchronous nature of the page
   * replacement policy, determining the retention ordering requires a traversal
   * of the keys.
   *
   * @param limit the maximum size of the returned set
   * @return a descending snapshot view of the keys in this map
   * @throws IllegalArgumentException if the limit is negative
   */
  public Set<K> descendingKeySetWithLimit(int limit) {
    return orderedKeySet(false, limit);
  }

  Set<K> orderedKeySet(boolean ascending, int limit) {
    Caffeine.requireArgument(limit >= 0);
    evictionLock.lock();
    try {
      drainBuffers();

      final int initialCapacity = (weigher == Weigher.singleton())
          ? Math.min(limit, (int) weightedSize())
          : 16;
      final Set<K> keys = new LinkedHashSet<K>(initialCapacity);
      final Iterator<Node<K, V>> iterator = ascending
          ? accessOrderDeque.iterator()
          : accessOrderDeque.descendingIterator();
      while (iterator.hasNext() && (limit > keys.size())) {
        K key = iterator.next().getKey(keyStrategy);
        if (key != null) {
          keys.add(key);
        }
      }
      return unmodifiableSet(keys);
    } finally {
      evictionLock.unlock();
    }
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
   * Returns an unmodifiable snapshot {@link Map} view of the mappings contained
   * in this map. The map's collections return the mappings whose order of
   * iteration is the ascending order in which its entries are considered
   * eligible for retention, from the least-likely to be retained to the
   * most-likely.
   * <p>
   * Beware that obtaining the mappings is <em>NOT</em> a constant-time
   * operation. Because of the asynchronous nature of the page replacement
   * policy, determining the retention ordering requires a traversal of the
   * entries.
   *
   * @return a ascending snapshot view of this map
   */
  public Map<K, V> ascendingMap() {
    return ascendingMapWithLimit(Integer.MAX_VALUE);
  }

  /**
   * Returns an unmodifiable snapshot {@link Map} view of the mappings contained
   * in this map. The map's collections return the mappings whose order of
   * iteration is the ascending order in which its entries are considered
   * eligible for retention, from the least-likely to be retained to the
   * most-likely.
   * <p>
   * Beware that obtaining the mappings is <em>NOT</em> a constant-time
   * operation. Because of the asynchronous nature of the page replacement
   * policy, determining the retention ordering requires a traversal of the
   * entries.
   *
   * @param limit the maximum size of the returned map
   * @return a ascending snapshot view of this map
   * @throws IllegalArgumentException if the limit is negative
   */
  public Map<K, V> ascendingMapWithLimit(int limit) {
    return orderedMap(true, limit);
  }

  /**
   * Returns an unmodifiable snapshot {@link Map} view of the mappings contained
   * in this map. The map's collections return the mappings whose order of
   * iteration is the descending order in which its entries are considered
   * eligible for retention, from the most-likely to be retained to the
   * least-likely.
   * <p>
   * Beware that obtaining the mappings is <em>NOT</em> a constant-time
   * operation. Because of the asynchronous nature of the page replacement
   * policy, determining the retention ordering requires a traversal of the
   * entries.
   *
   * @return a descending snapshot view of this map
   */
  public Map<K, V> descendingMap() {
    return descendingMapWithLimit(Integer.MAX_VALUE);
  }

  /**
   * Returns an unmodifiable snapshot {@link Map} view of the mappings contained
   * in this map. The map's collections return the mappings whose order of
   * iteration is the descending order in which its entries are considered
   * eligible for retention, from the most-likely to be retained to the
   * least-likely.
   * <p>
   * Beware that obtaining the mappings is <em>NOT</em> a constant-time
   * operation. Because of the asynchronous nature of the page replacement
   * policy, determining the retention ordering requires a traversal of the
   * entries.
   *
   * @param limit the maximum size of the returned map
   * @return a descending snapshot view of this map
   * @throws IllegalArgumentException if the limit is negative
   */
  public Map<K, V> descendingMapWithLimit(int limit) {
    return orderedMap(false, limit);
  }

  Map<K, V> orderedMap(boolean ascending, int limit) {
    Caffeine.requireArgument(limit >= 0);
    evictionLock.lock();
    try {
      drainBuffers();

      final int initialCapacity = (weigher == Weigher.singleton())
          ? Math.min(limit, (int) weightedSize())
          : 16;
      final Map<K, V> map = new LinkedHashMap<K, V>(initialCapacity);
      final Iterator<Node<K, V>> iterator = ascending
          ? accessOrderDeque.iterator()
          : accessOrderDeque.descendingIterator();
      while (iterator.hasNext() && (limit > map.size())) {
        Node<K, V> node = iterator.next();
        K key = node.getKey(keyStrategy);
        V value = node.getValue(valueStrategy);
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

  /** A value, its weight, and the entry's status. */
  @Immutable
  static final class WeightedValue<V> {
    final int weight;
    final Object valueRef;

    WeightedValue(Object value, int weight) {
      this.weight = weight;
      this.valueRef = value;
    }

    boolean contains(Object o, ReferenceStrategy valueStrategy) {
      return (o == getValue(valueStrategy)) || getValue(valueStrategy).equals(o);
    }

    Object getValueRef() {
      return valueRef;
    }

    V getValue(ReferenceStrategy valueStrategy) {
      @SuppressWarnings("unchecked")
      V value = (V) valueStrategy.dereferenceValue(valueRef);
      return value;
    }

    /**
     * If the entry is available in the hash-table and page replacement policy.
     */
    boolean isAlive() {
      return weight > 0;
    }

    /**
     * If the entry was removed from the hash-table and is awaiting removal from
     * the page replacement policy.
     */
    boolean isRetired() {
      return weight < 0;
    }

    /**
     * If the entry was removed from the hash-table and the page replacement
     * policy.
     */
    boolean isDead() {
      return weight == 0;
    }
  }

  /**
   * A node contains the key, the weighted value, and the linkage pointers on
   * the page-replacement algorithm's data structures.
   */
  @SuppressWarnings("serial")
  static final class Node<K, V> extends AtomicReference<WeightedValue<V>>
      implements AccessOrder<Node<K, V>>, WriteOrder<Node<K, V>> {
    volatile long accessTime;
    @GuardedBy("evictionLock")
    Node<K, V> prevAccessOrder;
    @GuardedBy("evictionLock")
    Node<K, V> nextAccessOrder;

    volatile long writeTime;
    @GuardedBy("evictionLock")
    Node<K, V> prevWriteOrder;
    @GuardedBy("evictionLock")
    Node<K, V> nextWriteOrder;

    final Object keyRef;

    /** Creates a new, unlinked node. */
    Node(Object keyRef, WeightedValue<V> weightedValue, long now) {
      super(weightedValue);
      this.accessTime = now;
      this.writeTime = now;
      this.keyRef = keyRef;
    }

    K getKey(ReferenceStrategy keyStrategy) {
      return keyStrategy.dereferenceKey(keyRef);
    }

    /** Retrieves the value held by the current <tt>WeightedValue</tt>. */
    V getValue(ReferenceStrategy valueStrategy) {
      return get().getValue(valueStrategy);
    }

    /* ---------------- Access order -------------- */

    long getAccessTime() {
      return accessTime;
    }

    /** Sets the access time in nanoseconds. */
    void setAccessTime(long time) {
      accessTime = time;
    }

    @Override
    @GuardedBy("evictionLock")
    public Node<K, V> getPreviousInAccessOrder() {
      return prevAccessOrder;
    }

    @Override
    @GuardedBy("evictionLock")
    public void setPreviousInAccessOrder(Node<K, V> prev) {
      this.prevAccessOrder = prev;
    }

    @Override
    @GuardedBy("evictionLock")
    public Node<K, V> getNextInAccessOrder() {
      return nextAccessOrder;
    }

    @Override
    @GuardedBy("evictionLock")
    public void setNextInAccessOrder(Node<K, V> next) {
      this.nextAccessOrder = next;
    }

    /* ---------------- Write order -------------- */

    long getWriteTime() {
      return writeTime;
    }

    /** Sets the write time in nanoseconds. */
    void setWriteTime(long time) {
      writeTime = time;
    }

    @Override
    @GuardedBy("evictionLock")
    public Node<K, V> getPreviousInWriteOrder() {
      return prevWriteOrder;
    }

    @Override
    @GuardedBy("evictionLock")
    public void setPreviousInWriteOrder(Node<K, V> prev) {
      this.prevWriteOrder = prev;
    }

    @Override
    @GuardedBy("evictionLock")
    public Node<K, V> getNextInWriteOrder() {
      return nextWriteOrder;
    }

    @Override
    @GuardedBy("evictionLock")
    public void setNextInWriteOrder(Node<K, V> next) {
      this.nextWriteOrder = next;
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
    Node<K, V> next;
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
      Node<K, V> node = map.data.get(keyStrategy.getKeyRef(entry.getKey()));
      return (node != null) && (node.getValue(valueStrategy).equals(entry.getValue()));
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
      return new WriteThroughEntry(current, valueStrategy);
    }

    @Override
    public void remove() {
      Caffeine.requireState(current != null);
      K key = current.getKey(keyStrategy);
      if (key != null) {
        BoundedLocalCache.this.remove(key);
      }
      current = null;
    }
  }

  /** An entry that allows updates to write through to the map. */
  final class WriteThroughEntry extends SimpleEntry<K, V> {
    static final long serialVersionUID = 1;

    WriteThroughEntry(Node<K, V> node, ReferenceStrategy valueStrategy) {
      super(node.getKey(keyStrategy), node.getValue(valueStrategy));
    }

    @Override
    public V setValue(V value) {
      put(getKey(), value);
      return super.setValue(value);
    }

    Object writeReplace() {
      return new SimpleEntry<K, V>(this);
    }
  }

  /** A weigher that enforces that the weight falls within a valid range. */
  static final class BoundedEntryWeigher<K, V> implements Weigher<K, V>, Serializable {
    static final long serialVersionUID = 1;
    final Weigher<? super K, ? super V> weigher;

    BoundedEntryWeigher(Weigher<? super K, ? super V> weigher) {
      requireNonNull(weigher);
      this.weigher = weigher;
    }

    @Override
    public int weigh(K key, V value) {
      int weight = weigher.weigh(key, value);
      Caffeine.requireArgument(weight >= 1);
      return weight;
    }

    Object writeReplace() {
      return weigher;
    }
  }

  enum ReferenceStrategy {
    STRONG {
      @Override <K> Object referenceKey(K key, ReferenceQueue<K> queue) {
        return key;
      }
      @Override <V> Object referenceValue(Object keyReference, V value, ReferenceQueue<V> queue) {
        return value;
      }
      @Override <K> K dereferenceKey(Object referent) {
        @SuppressWarnings("unchecked")
        K castedKey = (K) referent;
        return castedKey;
      }
      @Override <V> V dereferenceValue(Object referent) {
        @SuppressWarnings("unchecked")
        V castedValue = (V) referent;
        return castedValue;
      }
      @Override <K> Object getKeyRef(K key) {
        return key;
      }
    },
    WEAK {
      @Override <K> Object referenceKey(K key, ReferenceQueue<K> queue) {
        return new WeakKeyReference<K>(key, queue);
      }
      @Override <V> Object referenceValue(Object keyReference, V value, ReferenceQueue<V> queue) {
        return new WeakValueReference<V>(keyReference, value, queue);
      }
      @Override <K> K dereferenceKey(Object referent) {
        @SuppressWarnings("unchecked")
        WeakKeyReference<K> ref = (WeakKeyReference<K>) referent;
        return ref.get();
      }
      @Override <V> V dereferenceValue(Object referent) {
        @SuppressWarnings("unchecked")
        WeakValueReference<V> ref = (WeakValueReference<V>) referent;
        return (ref == null) ? null : ref.get();
      }
      @Override <K> Object getKeyRef(K key) {
        return new Ref<K>(key);
      }
    },
    SOFT {
      @Override <K> Object referenceKey(K key, ReferenceQueue<K> queue) {
        throw new UnsupportedOperationException();
      }
      @Override <V> Object referenceValue(Object keyReference, V value, ReferenceQueue<V> queue) {
        return new SoftValueReference<V>(keyReference, value, queue);
      }
      @Override <K> K dereferenceKey(Object referent) {
        throw new UnsupportedOperationException();
      }
      @Override <V> V dereferenceValue(Object referent) {
        @SuppressWarnings("unchecked")
        SoftValueReference<V> ref = (SoftValueReference<V>) referent;
        return (ref == null) ? null : ref.get();
      }
      @Override <K> Object getKeyRef(K key) {
        throw new UnsupportedOperationException();
      }
    };

    abstract <K> Object referenceKey(K key, ReferenceQueue<K> queue);

    abstract <V> Object referenceValue(Object keyReference, V value, ReferenceQueue<V> queue);

    abstract <K> K dereferenceKey(Object reference);

    abstract <V> V dereferenceValue(Object object);

    abstract <K> Object getKeyRef(K key);

    static ReferenceStrategy forStength(Strength strength) {
      switch (strength) {
        case STRONG:
          return ReferenceStrategy.STRONG;
        case WEAK:
          return ReferenceStrategy.WEAK;
        case SOFT:
          return ReferenceStrategy.SOFT;
        default:
          throw new IllegalArgumentException();
      }
    }
  }

  static final class Ref<E> implements InternalReference<E> {
    final E e;

    Ref(E e) {
      this.e = requireNonNull(e);
    }
    @Override public E get() {
      return e;
    }
    @Override public Object getKeyReference() {
      return this;
    }
    @Override public int hashCode() {
      return System.identityHashCode(e);
    }
    @Override public boolean equals(Object object) {
      return object.equals(this);
    }
  }

  interface InternalReference<E> {

    E get();

    Object getKeyReference();

    default boolean referenceEquals(Reference<?> reference, Object object) {
      if (object == reference) {
        return true;
      } else if (object instanceof InternalReference) {
        Object referent = ((InternalReference<?>) object).get();
        return (referent != null) && (referent == reference.get());
      } else if (object instanceof Ref<?>) {
        return ((Ref<?>) object).get() == reference.get();
      }
      return false;
    }
  }

  static final class WeakKeyReference<K> extends WeakReference<K> implements InternalReference<K> {
    final int hashCode;

    public WeakKeyReference(K key, ReferenceQueue<K> queue) {
      super(key, queue);
      hashCode = System.identityHashCode(key);
    }
    @Override public Object getKeyReference() {
      return this;
    }
    @Override public int hashCode() {
      return hashCode;
    }
    @Override public boolean equals(Object object) {
      return referenceEquals(this, object);
    }
  }

  static final class WeakValueReference<V>
      extends WeakReference<V> implements InternalReference<V> {
    final Object keyReference;

    public WeakValueReference(Object keyReference, V value, ReferenceQueue<V> queue) {
      super(value, queue);
      this.keyReference = keyReference;
    }
    @Override public Object getKeyReference() {
      return keyReference;
    }
    @Override public boolean equals(Object object) {
      return referenceEquals(this, object);
    }
  }

  static final class SoftValueReference<V>
      extends SoftReference<V> implements InternalReference<V> {
    final Object keyReference;

    public SoftValueReference(Object keyReference, V value, ReferenceQueue<V> queue) {
      super(value, queue);
      this.keyReference = keyReference;
    }
    @Override public Object getKeyReference() {
      return keyReference;
    } @Override public boolean equals(Object object) {
      return referenceEquals(this, object);
    }
  }

  /** A non-fair lock using AQS state to represent if the lock is held. */
  static final class Sync extends AbstractQueuedSynchronizer implements Lock, Serializable {
    static final long serialVersionUID = 1L;
    static final int UNLOCKED = 0;
    static final int LOCKED = 1;

    @Override
    public void lock() {
      acquire(LOCKED);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      acquireInterruptibly(LOCKED);
    }

    @Override
    public boolean tryLock() {
      return tryAcquire(LOCKED);
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
      return tryAcquireNanos(1, unit.toNanos(time));
    }

    @Override
    public void unlock() {
      release(1);
    }

    @Override
    public Condition newCondition() {
      return new ConditionObject();
    }

    @Override
    protected boolean tryAcquire(int acquires) {
      if (compareAndSetState(UNLOCKED, LOCKED)) {
        setExclusiveOwnerThread(Thread.currentThread());
        return true;
      } else if (Thread.currentThread() == getExclusiveOwnerThread()) {
        throw new IllegalMonitorStateException();
      }
      return false;
    }

    @Override
    protected boolean tryRelease(int releases) {
      if (Thread.currentThread() != getExclusiveOwnerThread()) {
        throw new IllegalMonitorStateException();
      }
      setExclusiveOwnerThread(null);
      setState(UNLOCKED);
      return true;
    }

    @Override
    protected boolean isHeldExclusively() {
      return isLocked() && (getExclusiveOwnerThread() == Thread.currentThread());
    }

    public final boolean isLocked() {
      return getState() == LOCKED;
    }
  }

  /* ---------------- Serialization Support -------------- */

  static final long serialVersionUID = 1;

  Object writeReplace() {
    return new SerializationProxy<K, V>(this);
  }

  private void readObject(ObjectInputStream stream) throws InvalidObjectException {
    throw new InvalidObjectException("Proxy required");
  }

  /**
   * A proxy that is serialized instead of the map. The page-replacement
   * algorithm's data structures are not serialized so the deserialized
   * instance contains only the entries. This is acceptable as caches hold
   * transient data that is recomputable and serialization would tend to be
   * used as a fast warm-up process.
   */
  static final class SerializationProxy<K, V> implements Serializable {
    final Weigher<? super K, ? super V> weigher;
    final RemovalListener<K, V> removalListener;
    final Map<K, V> data;
    final long capacity;

    SerializationProxy(BoundedLocalCache<K, V> map) {
      removalListener = map.removalListener;
      data = new HashMap<K, V>(map);
      capacity = map.maximumWeightedSize.get();
      weigher = map.weigher;
    }

    Object readResolve() {
      Caffeine<K, V> builder = Caffeine.newBuilder()
          .removalListener(removalListener)
          .maximumWeight(capacity)
          .weigher(weigher);
      BoundedLocalCache<K, V> map = new BoundedLocalCache<>(builder);
      map.putAll(data);
      return map;
    }

    static final long serialVersionUID = 1;
  }

  /* ---------------- Manual Cache -------------- */

  static class LocalManualCache<K, V> implements Cache<K, V> {
    final BoundedLocalCache<K, V> cache;
    transient Advanced<K, V> advanced;

    LocalManualCache(Caffeine<K, V> builder) {
      this.cache = new BoundedLocalCache<>(builder);
    }

    @Override
    public V getIfPresent(Object key) {
      return cache.getIfPresent(key, true);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return cache.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      return cache.getAllPresent(keys);
    }

    @Override
    public void put(K key, V value) {
      cache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      cache.putAll(map);
    }

    @Override
    public void invalidate(Object key) {
      cache.remove(key);
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      for (Object key : keys) {
        cache.remove(key);
      }
    }

    @Override
    public void invalidateAll() {
      cache.clear();
    }

    @Override
    public long estimatedSize() {
      return cache.mappingCount();
    }

    @Override
    public CacheStats stats() {
      return cache.statsCounter.snapshot();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return cache;
    }

    @Override
    public void cleanUp() {
      final Lock evictionLock = cache.evictionLock;
      evictionLock.lock();
      try {
        cache.drainBuffers();
      } finally {
        evictionLock.unlock();
      }
    }

    void asyncCleanup() {
      cache.executor.execute(this::cleanUp);
    }

    @Override
    public Advanced<K, V> advanced() {
      return (advanced == null) ? (advanced = new BoundedAdvanced()) : advanced;
    }

    final class BoundedAdvanced implements Advanced<K, V> {
      final Optional<Eviction<K, V>> eviction = Optional.of(new BoundedEviction());
      final Optional<Expiration<K, V>> afterWrite = Optional.of(new BoundedExpireAfterWrite());
      final Optional<Expiration<K, V>> afterAccess = Optional.of(new BoundedExpireAfterAccess());

      @Override public Optional<Eviction<K, V>> eviction() {
        return cache.evicts() ? eviction : Optional.empty();
      }
      @Override public Optional<Expiration<K, V>> expireAfterAccess() {
        return cache.expiresAfterAccess() ? afterAccess : Optional.empty();
      }
      @Override public Optional<Expiration<K, V>> expireAfterWrite() {
        return cache.expiresAfterWrite() ? afterWrite : Optional.empty();
      }

      final class BoundedEviction implements Eviction<K, V> {
        @Override public boolean isWeighted() {
          return (cache.weigher != Weigher.singleton());
        }
        @Override public Optional<Long> weightedSize() {
          return isWeighted() ? Optional.of(cache.weightedSize()) : Optional.empty();
        }
        @Override public long getMaximumSize() {
          return cache.capacity();
        }
        @Override public void setMaximumSize(long maximumSize) {
          cache.setCapacity(maximumSize);
        }
        @Override public Map<K, V> coldest(int limit) {
          return cache.ascendingMapWithLimit(limit);
        }
        @Override public Map<K, V> hottest(int limit) {
          return cache.descendingMapWithLimit(limit);
        }
      }

      final class BoundedExpireAfterAccess implements Expiration<K, V> {
        @Override public Optional<Long> ageOf(K key, TimeUnit unit) {
          Object keyRef = cache.keyStrategy.getKeyRef(key);
          Node<K, V> node = cache.data.get(keyRef);
          if (node == null) {
            return Optional.empty();
          }
          long age = cache.ticker.read() - node.getAccessTime();
          return (age > cache.expireAfterAccessNanos)
              ? Optional.empty()
              : Optional.of(unit.convert(age, TimeUnit.NANOSECONDS));
        }
        @Override public long getExpiresAfter(TimeUnit unit) {
          return unit.convert(cache.expireAfterAccessNanos, TimeUnit.NANOSECONDS);
        }
        @Override public void setExpiresAfter(long duration, TimeUnit unit) {
          Caffeine.requireArgument(duration >= 0);
          cache.expireAfterAccessNanos = unit.toNanos(duration);
          asyncCleanup();
        }
        @Override public Map<K, V> oldest(int limit) {
          return cache.ascendingMapWithLimit(limit);
        }
        @Override public Map<K, V> youngest(int limit) {
          return cache.descendingMapWithLimit(limit);
        }
      }

      final class BoundedExpireAfterWrite implements Expiration<K, V> {
        @Override public Optional<Long> ageOf(K key, TimeUnit unit) {
          Object keyRef = cache.keyStrategy.getKeyRef(key);
          Node<K, V> node = cache.data.get(keyRef);
          if (node == null) {
            return Optional.empty();
          }
          long age = cache.ticker.read() - node.getWriteTime();
          return (age > cache.expireAfterWriteNanos)
              ? Optional.empty()
              : Optional.of(unit.convert(age, TimeUnit.NANOSECONDS));
        }
        @Override public long getExpiresAfter(TimeUnit unit) {
          return unit.convert(cache.expireAfterWriteNanos, TimeUnit.NANOSECONDS);
        }
        @Override public void setExpiresAfter(long duration, TimeUnit unit) {
          Caffeine.requireArgument(duration >= 0);
          cache.expireAfterWriteNanos = unit.toNanos(duration);
          asyncCleanup();
        }
        @Override public Map<K, V> oldest(int limit) {
          throw new UnsupportedOperationException("TODO");
        }
        @Override public Map<K, V> youngest(int limit) {
          throw new UnsupportedOperationException("TODO");
        }
      }
    }
  }

  /* ---------------- Loading Cache -------------- */

  static final class LocalLoadingCache<K, V> extends LocalManualCache<K, V>
      implements LoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    final CacheLoader<? super K, V> loader;
    final boolean hasBulkLoader;

    LocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder);
      this.loader = loader;
      this.hasBulkLoader = hasLoadAll(loader);
    }

    private static boolean hasLoadAll(CacheLoader<?, ?> loader) {
      try {
        return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
      } catch (NoSuchMethodException | SecurityException e) {
        logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
        return false;
      }
    }

    @Override
    public V get(K key) {
      return cache.computeIfAbsent(key, loader::load);
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      Map<K, V> result = new HashMap<K, V>();
      List<K> keysToLoad = new ArrayList<>();
      for (K key : keys) {
        Node<K, V> node = cache.data.get(cache.keyStrategy.getKeyRef(key));
        V value = (node == null) ? null : node.getValue(cache.valueStrategy);
        if (value == null) {
          keysToLoad.add(key);
        } else {
          cache.afterRead(node, true); // TODO(ben): batch
          result.put(key, value);
        }
      }
      if (keysToLoad.isEmpty()) {
        return result;
      }
      bulkLoad(keysToLoad, result);
      return Collections.unmodifiableMap(result);
    }

    private void bulkLoad(List<K> keysToLoad, Map<K, V> result) {
      cache.statsCounter.recordMisses(keysToLoad.size());

      if (!hasBulkLoader) {
        for (K key : keysToLoad) {
          V value = cache.compute(key, (k, v) -> loader.load(key), false);
          result.put(key, value);
        }
        return;
      }

      boolean success = false;
      long startTime = cache.ticker.read();
      try {
        @SuppressWarnings("unchecked")
        Map<K, V> loaded = (Map<K, V>) loader.loadAll(keysToLoad);
        cache.putAll(loaded);
        for (K key : keysToLoad) {
          V value = loaded.get(key);
          if (value != null) {
            result.put(key, value);
          }
        }
        success = true;
      } finally {
        long loadTime = cache.ticker.read() - startTime;
        if (success) {
          cache.statsCounter.recordLoadSuccess(loadTime);
        } else {
          cache.statsCounter.recordLoadFailure(loadTime);
        }
      }
    }

    @Override
    public void refresh(K key) {
      requireNonNull(key);
      cache.executor.execute(() -> {
        try {
          cache.compute(key, loader::reload, false);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during refresh", t);
        }
      });
    }
  }
}
