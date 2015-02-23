/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.BoundedLocalCache.MAXIMUM_CAPACITY;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.NUMBER_OF_READ_BUFFERS;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.READ_BUFFER_SIZE;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.DrainStatus.IDLE;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.DrainStatus;
import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * The fields used by the algorithms that deciding which entry to evict. The implementation may take
 * into account the size, access and write times, reference strength, and usage history.
 * <p>
 * The consolidation of fields and methods to mutate them is to aid the adoption of code generation.
 * The configuration determines which fields are necessary, allowing generated implementations to
 * not retain additional memory due to unused fields. Once mature, this class should be replaced
 * with subclasses of {@link BoundedLocalCache} that performs the same task. This will remove
 * needing a container object for the fields and allow further optimizations of the cleanup process.
 * <p>
 * In addition to page replacement fields, optional features that are disabled can have their fields
 * removed as well. This includes the weigher, cache loader, ticker, stats, executor, and removal
 * listener.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class PageReplacement<K, V> {
  // These fields provide support to bound the map by a maximum capacity
  @GuardedBy("evictionLock") // must write under lock
  private final AtomicLong weightedSize;
  @GuardedBy("evictionLock") // must write under lock
  private final AtomicLong maximumWeightedSize;

  @GuardedBy("evictionLock")
  private final long[] readBufferReadCount;
  private final AtomicLong[] readBufferWriteCount;
  private final AtomicLong[] readBufferDrainAtWriteCount;
  private final AtomicReference<Node<K, V>>[][] readBuffers;

  private final Queue<Runnable> writeBuffer;
  private final NonReentrantLock evictionLock;
  private final AtomicReference<DrainStatus> drainStatus;

  // How long after the last access to an entry the map will retain that entry
  private volatile long expireAfterAccessNanos;

  // How long after the last write to an entry the map will retain that entry
  private volatile long expireAfterWriteNanos;

  // How long after the last write an entry becomes a candidate for refresh
  private volatile long refreshAfterWriteNanos;

  @GuardedBy("evictionLock")
  private final AccessOrderDeque<Node<K, V>> accessOrderDeque;

  @GuardedBy("evictionLock")
  private final WriteOrderDeque<Node<K, V>> writeOrderDeque;

  @SuppressWarnings({"unchecked", "cast"})
  PageReplacement(Caffeine<K, V> builder, boolean isAsync) {
    weightedSize = new AtomicLong();
    maximumWeightedSize = builder.evicts()
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

    // The eviction support
    evictionLock = new NonReentrantLock();
    writeBuffer = new ConcurrentLinkedQueue<Runnable>();
    drainStatus = new AtomicReference<DrainStatus>(IDLE);

    accessOrderDeque = new AccessOrderDeque<Node<K, V>>();
    writeOrderDeque = new WriteOrderDeque<Node<K, V>>();

    // The expiration support
    expireAfterAccessNanos = builder.getExpireAfterAccessNanos();
    expireAfterWriteNanos = builder.getExpireAfterWriteNanos();
    refreshAfterWriteNanos = builder.getRefreshNanos();
  }

  LinkedDeque<Node<K, V>> getAccessOrderDeque() {
    return accessOrderDeque;
  }

  LinkedDeque<Node<K, V>> getWriteOrderDeque() {
    return writeOrderDeque;
  }

  Queue<Runnable> writeBuffer() {
    return writeBuffer;
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

  AtomicLong[] readBufferDrainAtWriteCount() {
    return readBufferDrainAtWriteCount;
  }

  AtomicReference<Node<K, V>>[][] readBuffers() {
    return readBuffers;
  }

  /* ---------------- Eviction Support -------------- */

  @Nullable AtomicLong maximumWeightedSize() {
    return maximumWeightedSize;
  }

  long weightedSize() {
    return weightedSize.get();
  }

  void lazySetWeightedSize(long weightedSize) {
    this.weightedSize.lazySet(weightedSize);
  }

  /* ---------------- Expiration Support -------------- */

  long getExpireAfterAccessNanos() {
    return expireAfterAccessNanos;
  }

  void setExpireAfterAccessNanos(long expireAfterAccessNanos) {
    this.expireAfterAccessNanos = expireAfterAccessNanos;
  }

  long expireAfterWriteNanos() {
    return expireAfterWriteNanos;
  }

  void setExpireAfterWriteNanos(long expireAfterWriteNanos) {
    this.expireAfterWriteNanos = expireAfterWriteNanos;
  }

  long refreshAfterWriteNanos() {
    return refreshAfterWriteNanos;
  }

  void setRefreshAfterWriteNanos(long refreshAfterWriteNanos) {
    this.refreshAfterWriteNanos = refreshAfterWriteNanos;
  }
}
