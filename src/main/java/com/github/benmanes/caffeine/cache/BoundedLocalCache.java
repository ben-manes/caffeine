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
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

import com.github.benmanes.caffeine.SingleConsumerQueue;
import com.github.benmanes.caffeine.atomic.PaddedAtomicLong;
import com.github.benmanes.caffeine.atomic.PaddedAtomicReference;
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
  static final int NUMBER_OF_READ_BUFFERS = ceilingNextPowerOfTwo(NCPU);

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

  // The backing data store holding the key-value associations
  final ConcurrentHashMap<K, Node<K, V>> data;

  // These fields provide support to bound the map by a maximum capacity
  @GuardedBy("evictionLock")
  final long[] readBufferReadCount;
  @GuardedBy("evictionLock")
  final LinkedDeque<Node<K, V>> evictionDeque;

  @GuardedBy("evictionLock") // must write under lock
  final PaddedAtomicLong weightedSize;
  @GuardedBy("evictionLock") // must write under lock
  final PaddedAtomicLong capacity;

  final Lock evictionLock;
  final Queue<Runnable> writeBuffer;
  final PaddedAtomicLong[] readBufferWriteCount;
  final PaddedAtomicLong[] readBufferDrainAtWriteCount;
  final PaddedAtomicReference<Node<K, V>>[][] readBuffers;

  final PaddedAtomicReference<DrainStatus> drainStatus;
  final Weigher<? super K, ? super V> weigher;

  // These fields provide support for notifying a listener.
  final RemovalListener<K, V> removalListener;
  final Executor executor;

  final StatsCounter statsCounter;
  final boolean isRecordingStats;
  final Ticker ticker;

  transient Set<K> keySet;
  transient Collection<V> values;
  transient Set<Entry<K, V>> entrySet;

  /**
   * Creates an instance based on the builder's configuration.
   */
  @SuppressWarnings({"unchecked", "cast"})
  private BoundedLocalCache(Caffeine<K, V> builder) {
    // The data store and its maximum capacity
    data = new ConcurrentHashMap<K, Node<K, V>>(builder.getInitialCapacity());
    capacity = new PaddedAtomicLong(Math.min(builder.getMaximumWeight(), MAXIMUM_CAPACITY));

    // The eviction support
    evictionLock = new Sync();
    weigher = builder.getWeigher();
    weightedSize = new PaddedAtomicLong();
    evictionDeque = new LinkedDeque<Node<K, V>>();
    writeBuffer = new SingleConsumerQueue<Runnable>();
    drainStatus = new PaddedAtomicReference<DrainStatus>(IDLE);

    readBufferReadCount = new long[NUMBER_OF_READ_BUFFERS];
    readBufferWriteCount = new PaddedAtomicLong[NUMBER_OF_READ_BUFFERS];
    readBufferDrainAtWriteCount = new PaddedAtomicLong[NUMBER_OF_READ_BUFFERS];
    readBuffers = new PaddedAtomicReference[NUMBER_OF_READ_BUFFERS][READ_BUFFER_SIZE];
    for (int i = 0; i < NUMBER_OF_READ_BUFFERS; i++) {
      readBufferWriteCount[i] = new PaddedAtomicLong();
      readBufferDrainAtWriteCount[i] = new PaddedAtomicLong();
      readBuffers[i] = new PaddedAtomicReference[READ_BUFFER_SIZE];
      for (int j = 0; j < READ_BUFFER_SIZE; j++) {
        readBuffers[i][j] = new PaddedAtomicReference<Node<K, V>>();
      }
    }

    // The notification queue and listener
    removalListener = builder.getRemovalListener();
    executor = builder.getExecutor();

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
    executor.execute(() -> removalListener.onRemoval(
        new RemovalNotification<K, V>(key, value, cause)));
  }

  /* ---------------- Eviction Support -------------- */

  /**
   * Retrieves the maximum weighted capacity of the map.
   *
   * @return the maximum weighted capacity
   */
  public long capacity() {
    return capacity.get();
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
      this.capacity.lazySet(Math.min(capacity, MAXIMUM_CAPACITY));
      drainBuffers();
      evict();
    } finally {
      evictionLock.unlock();
    }
  }

  /** Determines whether the map has exceeded its capacity. */
  @GuardedBy("evictionLock")
  boolean hasOverflowed() {
    return weightedSize.get() > capacity.get();
  }

  /**
   * Evicts entries from the map while it exceeds the capacity and appends
   * evicted entries to the notification queue for processing.
   */
  @GuardedBy("evictionLock")
  void evict() {
    // Attempts to evict entries from the map if it exceeds the maximum
    // capacity. If the eviction fails due to a concurrent removal of the
    // victim, that removal may cancel out the addition that triggered this
    // eviction. The victim is eagerly unlinked before the removal task so
    // that if an eviction is still required then a new victim will be chosen
    // for removal.
    while (hasOverflowed()) {
      final Node<K, V> node = evictionDeque.poll();

      // If weighted values are used, then the pending operations will adjust
      // the size to reflect the correct weight
      if (node == null) {
        return;
      }

      // Notify the listener only if the entry was evicted
      if (data.remove(node.key, node) && hasRemovalListener()) {
        notifyRemoval(node.key, node.getValue(), RemovalCause.SIZE);
      }

      makeDead(node);
    }
  }

  /**
   * Performs the post-processing work required after a read.
   *
   * @param node the entry in the page replacement policy
   */
  void afterRead(Node<K, V> node) {
    statsCounter.recordHits(1);
    final int bufferIndex = readBufferIndex();
    final long writeCount = recordRead(bufferIndex, node);
    drainOnReadIfNeeded(bufferIndex, writeCount);
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
  void afterWrite(Runnable task) {
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
  }

  /** Drains the read buffers, each up to an amortized threshold. */
  @GuardedBy("evictionLock")
  void drainReadBuffers() {
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
      final PaddedAtomicReference<Node<K, V>> slot = readBuffers[bufferIndex][index];
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
    if (evictionDeque.contains(node)) {
      evictionDeque.moveToBack(node);
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
      final WeightedValue<V> retired = new WeightedValue<V>(expect.value, -expect.weight);
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
      final WeightedValue<V> retired = new WeightedValue<V>(current.value, -current.weight);
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
      WeightedValue<V> dead = new WeightedValue<V>(current.value, 0);
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
        evictionDeque.add(node);
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
      evictionDeque.remove(node);
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
      Node<K, V> node;
      while ((node = evictionDeque.poll()) != null) {
        data.remove(node.key, node);
        if (hasRemovalListener()) {
          notifyRemoval(node.key, node.getValue(), RemovalCause.EXPLICIT);
        }
        makeDead(node);
      }

      // Discard all pending reads
      for (AtomicReference<Node<K, V>>[] buffer : readBuffers) {
        for (AtomicReference<Node<K, V>> slot : buffer) {
          slot.lazySet(null);
        }
      }

      // Apply all pending writes
      Runnable task;
      while ((task = writeBuffer.poll()) != null) {
        task.run();
      }
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  public boolean containsKey(Object key) {
    return data.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    requireNonNull(value);

    for (Node<K, V> node : data.values()) {
      if (node.getValue().equals(value)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public V get(Object key) {
    final Node<K, V> node = data.get(key);
    if (node == null) {
      statsCounter.recordMisses(1);
      return null;
    }
    afterRead(node);
    return node.getValue();
  }

  // TODO(ben): JavaDoc
  public Map<K, V> getAllPresent(Iterable<?> keys) {
    int misses = 0;
    Map<K, V> result = new LinkedHashMap<>();
    for (Object key : keys) {
      final Node<K, V> node = data.get(key);
      if (node == null) {
        misses++;
        continue;
      }
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      V value = node.getValue();
      result.put(castKey, value);

      // TODO(ben): batch reads to call tryLock once
      afterRead(node);
    }
    statsCounter.recordMisses(misses);
    return Collections.unmodifiableMap(result);
  }

  /**
   * Returns the value to which the specified key is mapped, or {@code null}
   * if this map contains no mapping for the key. This method differs from
   * {@link #get(Object)} in that it does not record the operation with the
   * page replacement policy.
   *
   * @param key the key whose associated value is to be returned
   * @return the value to which the specified key is mapped, or
   *     {@code null} if this map contains no mapping for the key
   * @throws NullPointerException if the specified key is null
   */
  public V getQuietly(Object key) {
    final Node<K, V> node = data.get(key);
    return (node == null) ? null : node.getValue();
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

    final int weight = weigher.weigh(key, value);
    final WeightedValue<V> weightedValue = new WeightedValue<V>(value, weight);
    final Node<K, V> node = new Node<K, V>(key, weightedValue);

    for (;;) {
      final Node<K, V> prior = data.putIfAbsent(node.key, node);
      if (prior == null) {
        afterWrite(new AddTask(node, weight));
        return null;
      } else if (onlyIfAbsent) {
        afterRead(prior);
        return prior.getValue();
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
      if (weightedDifference == 0) {
        afterRead(prior);
      } else {
        afterWrite(new UpdateTask(prior, weightedDifference));
      }
      if (hasRemovalListener()) {
        notifyRemoval(key, oldWeightedValue.value, RemovalCause.REPLACED);
      }
      return oldWeightedValue.value;
    }
  }

  @Override
  public V remove(Object key) {
    final Node<K, V> node = data.remove(key);
    if (node == null) {
      return null;
    }

    WeightedValue<V> retired = makeRetired(node);
    afterWrite(new RemovalTask(node));

    if (hasRemovalListener() && (retired != null)) {
      @SuppressWarnings("unchecked")
      K castKey = (K) key;
      notifyRemoval(castKey, retired.value, RemovalCause.EXPLICIT);
    }
    return node.getValue();
  }

  @Override
  public boolean remove(Object key, Object value) {
    final Node<K, V> node = data.get(key);
    if ((node == null) || (value == null)) {
      return false;
    }

    WeightedValue<V> weightedValue = node.get();
    for (;;) {
      if (weightedValue.contains(value)) {
        if (tryToRetire(node, weightedValue)) {
          if (data.remove(key, node)) {
            if (hasRemovalListener()) {
              @SuppressWarnings("unchecked")
              K castKey = (K) key;
              notifyRemoval(castKey, node.getValue(), RemovalCause.EXPLICIT);
            }
            afterWrite(new RemovalTask(node));
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
    final WeightedValue<V> weightedValue = new WeightedValue<V>(value, weight);

    final Node<K, V> node = data.get(key);
    if (node == null) {
      return null;
    }
    WeightedValue<V> oldWeightedValue;
    synchronized (node) {
      oldWeightedValue = node.get();
      if (!oldWeightedValue.isAlive()) {
        return null;
      }
      node.lazySet(weightedValue);
    }
    final int weightedDifference = weight - oldWeightedValue.weight;
    if (weightedDifference == 0) {
      afterRead(node);
    } else {
      afterWrite(new UpdateTask(node, weightedDifference));
    }
    if (hasRemovalListener()) {
      notifyRemoval(key, oldWeightedValue.value, RemovalCause.REPLACED);
    }
    return oldWeightedValue.value;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNonNull(key);
    requireNonNull(oldValue);
    requireNonNull(newValue);

    final int weight = weigher.weigh(key, newValue);
    final WeightedValue<V> newWeightedValue = new WeightedValue<V>(newValue, weight);

    final Node<K, V> node = data.get(key);
    if (node == null) {
      return false;
    }
    WeightedValue<V> oldWeightedValue;
    synchronized (node) {
      oldWeightedValue = node.get();
      if (!oldWeightedValue.isAlive() || !oldWeightedValue.contains(oldValue)) {
        return false;
      }
      node.lazySet(newWeightedValue);
    }
    final int weightedDifference = weight - oldWeightedValue.weight;
    if (weightedDifference == 0) {
      afterRead(node);
    } else {
      afterWrite(new UpdateTask(node, weightedDifference));
    }
    if (hasRemovalListener()) {
      notifyRemoval(key, oldWeightedValue.value, RemovalCause.REPLACED);
    }
    return true;
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    // optimistic fast path due to computeIfAbsent always locking
    Node<K, V> node = data.get(key);
    if (node != null) {
      afterRead(node);
      return node.getValue();
    }

    @SuppressWarnings("unchecked")
    WeightedValue<V>[] weightedValue = new WeightedValue[1];
    node = data.computeIfAbsent(key, k -> {
      V value;
      value = statsAware(mappingFunction).apply(k);
      if (value == null) {
        return null;
      }
      int weight = weigher.weigh(key, value);
      weightedValue[0] = new WeightedValue<V>(value, weight);
      return new Node<K, V>(key, weightedValue[0]);
    });
    if (node == null) {
      return null;
    }
    if (weightedValue[0] == null) {
      afterRead(node);
      return node.getValue();
    } else {
      afterWrite(new AddTask(node, weightedValue[0].weight));
      return weightedValue[0].value;
    }
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    // optimistic fast path due to computeIfAbsent always locking
    if (!data.containsKey(key)) {
      return null;
    }

    @SuppressWarnings("unchecked")
    WeightedValue<V>[] weightedValue = new WeightedValue[1];
    Runnable[] task = new Runnable[1];
    Node<K, V> node = data.computeIfPresent(key, (k, prior) -> {
      synchronized (prior) {
        WeightedValue<V> oldWeightedValue = prior.get();
        V newValue = statsAware(remappingFunction).apply(k, oldWeightedValue.value);
        if (newValue == null) {
          makeRetired(prior);
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(prior.key, prior.getValue(), RemovalCause.EXPLICIT);
          }
          return null;
        }
        int weight = weigher.weigh(key, newValue);
        WeightedValue<V> newWeightedValue = new WeightedValue<V>(newValue, weight);
        prior.lazySet(newWeightedValue);
        weightedValue[0] = newWeightedValue;
        final int weightedDifference = weight - oldWeightedValue.weight;
        if (weightedDifference != 0) {
          task[0] = new UpdateTask(prior, weightedDifference);
        }
        if (hasRemovalListener()) {
          notifyRemoval(prior.key, prior.getValue(), RemovalCause.REPLACED);
        }
        return prior;
      }
    });
    if (task[0] == null) {
      afterRead(node);
    } else {
      afterWrite(task[0]);
    }
    return (weightedValue[0] == null) ? null : weightedValue[0].value;
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    requireNonNull(remappingFunction);

    @SuppressWarnings("unchecked")
    V[] newValue = (V[]) new Object[1];
    Runnable[] task = new Runnable[2];
    data.compute(key, (k, prior) -> {
      if (prior == null) {
        newValue[0] = statsAware(remappingFunction).apply(k, null);
        if (newValue[0] == null) {
          return null;
        }
        final int weight = weigher.weigh(key, newValue[0]);
        final WeightedValue<V> weightedValue = new WeightedValue<V>(newValue[0], weight);
        final Node<K, V> newNode = new Node<K, V>(key, weightedValue);
        task[0] = new AddTask(newNode, weight);
        return newNode;
      }
      synchronized (prior) {
        WeightedValue<V> oldWeightedValue = prior.get();
        V oldValue;
        if (oldWeightedValue.isAlive()) {
          oldValue = oldWeightedValue.value;
        } else {
          // conditionally removed won, but we got the entry lock first
          // so help out and pretend like we are inserting a fresh entry
          task[1] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldWeightedValue.value, RemovalCause.EXPLICIT);
          }
          oldValue = null;
        }
        newValue[0] = statsAware(remappingFunction).apply(k, oldValue);
        if ((newValue[0] == null) && (oldValue != null)) {
          task[0] = new RemovalTask(prior);
          if (hasRemovalListener()) {
            notifyRemoval(key, oldWeightedValue.value, RemovalCause.EXPLICIT);
          }
          return null;
        }
        final int weight = weigher.weigh(key, newValue[0]);
        final WeightedValue<V> weightedValue = new WeightedValue<V>(newValue[0], weight);
        Node<K, V> newNode;
        if (task[1] == null) {
          newNode = prior;
          prior.lazySet(weightedValue);
          final int weightedDifference = weight - oldWeightedValue.weight;
          if (weightedDifference != 0) {
            task[0] = new UpdateTask(prior, weightedDifference);
          }
          if (hasRemovalListener()) {
            notifyRemoval(key, oldWeightedValue.value, RemovalCause.REPLACED);
          }
        } else {
          newNode = new Node<>(key, weightedValue);
          task[0] = new AddTask(newNode, weight);
        }
        return prior;
      }
    });
    if (task[0] != null) {
      afterWrite(task[0]);
    }
    if (task[1] != null) {
      afterWrite(task[1]);
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

    final int weight = weigher.weigh(key, value);
    final WeightedValue<V> weightedValue = new WeightedValue<V>(value, weight);
    final Node<K, V> node = new Node<K, V>(key, weightedValue);
    data.merge(key, node, (k, prior) -> {
      synchronized (prior) {
        WeightedValue<V> oldWeightedValue = prior.get();
        if (!oldWeightedValue.isAlive()) {
          // conditionally removed won, but we got the entry lock first
          // so help out and pretend like we are inserting a fresh entry
          // ...
        }
        remappingFunction.apply(oldWeightedValue.value, value);
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

  /** Decorates the remapping function to record statistics if enabled. */
  <T, U, R> BiFunction<? super T, ? super U, ? extends R> statsAware(
      BiFunction<? super T, ? super U, ? extends R> remappingFunction) {
    if (!isRecordingStats) {
      return remappingFunction;
    }
    return (t, u) -> {
      R result;
      if (u == null) {
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
          ? evictionDeque.iterator()
          : evictionDeque.descendingIterator();
      while (iterator.hasNext() && (limit > keys.size())) {
        keys.add(iterator.next().key);
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
          ? evictionDeque.iterator()
          : evictionDeque.descendingIterator();
      while (iterator.hasNext() && (limit > map.size())) {
        Node<K, V> node = iterator.next();
        map.put(node.key, node.getValue());
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
    final V value;

    WeightedValue(V value, int weight) {
      this.weight = weight;
      this.value = value;
    }

    boolean contains(Object o) {
      return (o == value) || value.equals(o);
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
      implements Linked<Node<K, V>> {
    final K key;
    @GuardedBy("evictionLock")
    Node<K, V> prev;
    @GuardedBy("evictionLock")
    Node<K, V> next;

    /** Creates a new, unlinked node. */
    Node(K key, WeightedValue<V> weightedValue) {
      super(weightedValue);
      this.key = key;
    }

    @Override
    @GuardedBy("evictionLock")
    public Node<K, V> getPrevious() {
      return prev;
    }

    @Override
    @GuardedBy("evictionLock")
    public void setPrevious(Node<K, V> prev) {
      this.prev = prev;
    }

    @Override
    @GuardedBy("evictionLock")
    public Node<K, V> getNext() {
      return next;
    }

    @Override
    @GuardedBy("evictionLock")
    public void setNext(Node<K, V> next) {
      this.next = next;
    }

    /** Retrieves the value held by the current <tt>WeightedValue</tt>. */
    V getValue() {
      return get().value;
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
      return map.data.keySet().toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
      return map.data.keySet().toArray(array);
    }
  }

  /** An adapter to safely externalize the key iterator. */
  final class KeyIterator implements Iterator<K> {
    final Iterator<K> iterator = data.keySet().iterator();
    K current;

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public K next() {
      current = iterator.next();
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
    final Iterator<Node<K, V>> iterator = data.values().iterator();
    Node<K, V> current;

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public V next() {
      current = iterator.next();
      return current.getValue();
    }

    @Override
    public void remove() {
      Caffeine.requireState(current != null);
      BoundedLocalCache.this.remove(current.key);
      current = null;
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
      Node<K, V> node = map.data.get(entry.getKey());
      return (node != null) && (node.getValue().equals(entry.getValue()));
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
    Node<K, V> current;

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Entry<K, V> next() {
      current = iterator.next();
      return new WriteThroughEntry(current);
    }

    @Override
    public void remove() {
      Caffeine.requireState(current != null);
      BoundedLocalCache.this.remove(current.key);
      current = null;
    }
  }

  /** An entry that allows updates to write through to the map. */
  final class WriteThroughEntry extends SimpleEntry<K, V> {
    static final long serialVersionUID = 1;

    WriteThroughEntry(Node<K, V> node) {
      super(node.key, node.getValue());
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
      capacity = map.capacity.get();
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
      return cache.get(key);
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
    public long size() {
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

    @Override
    public Advanced<K, V> advanced() {
      return (advanced == null) ? (advanced = new BoundedAdvanced()) : advanced;
    }

    final class BoundedAdvanced implements Advanced<K, V> {
      @Override public Optional<Eviction<K, V>> eviction() {
        return Optional.empty();
      }
      @Override public Optional<Expiration<K, V>> expireAfterRead() {
        return Optional.empty();
      }
      @Override public Optional<Expiration<K, V>> expireAfterWrite() {
        return Optional.empty();
      }
    }
  }

  /* ---------------- Loading Cache -------------- */

  static final class LocalLoadingCache<K, V> extends LocalManualCache<K, V>
      implements LoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    final CacheLoader<? super K, V> loader;

    LocalLoadingCache(Caffeine<K, V> builder, CacheLoader<? super K, V> loader) {
      super(builder);
      this.loader = loader;
    }

    @Override
    public V get(K key) {
      return cache.computeIfAbsent(key, loader::load);
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      Map<K, V> result = new HashMap<K, V>();
      for (K key : keys) {
        requireNonNull(key);
        V value = cache.computeIfAbsent(key, loader::load);
        if (value != null) {
          result.put(key, value);
        }
      }
      return Collections.unmodifiableMap(result);
    }

    @Override
    public void refresh(K key) {
      requireNonNull(key);
      cache.executor.execute(() -> {
        try {
          cache.compute(key, loader::refresh);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during refresh", t);
        }
      });
    }
  }
}
