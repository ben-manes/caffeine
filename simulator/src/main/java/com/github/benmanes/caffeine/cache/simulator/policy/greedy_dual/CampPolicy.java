/*
 * Copyright 2021 Omri Himelbrand. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.Enums;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * CAMP algorithm.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://dl.acm.org/doi/10.1145/2663165.2663317"> CAMP: A Cost Adaptive Multi-Queue
 * Eviction Policy for Key-Value Stores</a>. It is a cost-aware cache policy, which takes into
 * account the access times (i.e. the cost of recomputing or fetching the data) and is also a
 * weighted policy, meaning it supports non-uniform entry sizes. This implementation is based on the
 * code provided by the authors in their <a href="https://github.com/scdblab/CAMP">repository</a>.
 * <p>
 * CAMP approximates Greedy-Dual-Size where each entry has a priority {@code H = L + c}; {@code L}
 * is a non-decreasing inflation value and {@code c} is the rounded cost-to-size ratio. Entries that
 * share a rounded ratio are held in a per-ratio LRU queue, and a priority queue orders those queues
 * by the priority of their head (least-recently-used) entry so the global minimum can be evicted.
 * The cost-to-size ratio is scaled by the largest observed size before rounding so that ratios
 * below one retain their relative magnitude, and {@code L} advances to the new minimum priority on
 * each eviction.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
@PolicySpec(name = "greedy-dual.Camp", characteristics = WEIGHTED)
public final class CampPolicy implements Policy {
  private final Int2ObjectMap<Sentinel> sentinelMapping;
  private final NavigableSet<Sentinel> priorityQueue;
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final long maximumSize;
  private final int precision;
  private final int bitMask;

  private long minPriority; // L, the non-decreasing inflation value
  private int maxSize;      // the largest observed weight, used to scale the cost-to-size ratio
  private long clock;       // access counter, supplies each entry's LRU recency
  private long size;

  public CampPolicy(Config config) {
    var settings = new CampSettings(config);
    this.precision = settings.precision();
    this.maximumSize = settings.maximumSize();
    this.policyStats = new PolicyStats(name());
    this.data = new Long2ObjectOpenHashMap<>();
    this.sentinelMapping = new Int2ObjectOpenHashMap<>();
    this.bitMask = Integer.MAX_VALUE >> (Integer.SIZE - 1 - precision);
    this.priorityQueue = new TreeSet<>(settings.tieBreak().comparator());
    this.maxSize = 1;
  }

  @Override
  public void record(AccessEvent event) {
    @Nullable Node node = data.get(event.key());
    maxSize = Math.max(maxSize, event.weight());
    if (node == null) {
      policyStats.recordWeightedMiss(event.weight());
      onMiss(event);
    } else {
      policyStats.recordWeightedHit(event.weight());
      size += (event.weight() - node.weight);
      node.weight = event.weight();
      onHit(node);
      if (size > maximumSize) {
        evict();
      }
    }
  }

  /** Re-evaluates the entry's queue and priority, as the inflation or max size may have changed. */
  private void onHit(Node node) {
    unlink(node);
    insert(node);
  }

  private void onMiss(AccessEvent event) {
    if (event.weight() > maximumSize) {
      policyStats.recordEviction();
      return;
    }

    size += event.weight();
    while (size > maximumSize) {
      evict();
    }

    var node = new Node(event.key(), event.weight());
    node.penalty = event.isPenaltyAware() ? event.missPenalty() : 1;
    insert(node);
    data.put(node.key, node);
  }

  /** Assigns the entry a priority of {@code L + c} and appends it to the tail of its LRU queue. */
  private void insert(Node node) {
    node.cost = roundedCost(node);
    node.priority = minPriority + node.cost;
    node.order = ++clock;

    @Var var sentinel = sentinelMapping.get(node.cost);
    if (sentinel == null) {
      sentinel = new Sentinel(node.cost);
      sentinel.priority = node.priority;
      sentinel.order = node.order;

      sentinelMapping.put(node.cost, sentinel);
      priorityQueue.add(sentinel);
    }
    node.sentinel = sentinel;
    sentinel.appendToTail(node);
  }

  /** Removes the entry from its LRU queue, advancing the queue's heap priority if it was the head. */
  private void unlink(Node node) {
    var sentinel = requireNonNull(node.sentinel);
    boolean wasHead = (sentinel.next == node);
    node.remove();

    if (sentinel.isEmpty()) {
      priorityQueue.remove(sentinel);
      sentinelMapping.remove(sentinel.cost);
    } else if (wasHead) {
      // The head defines the queue's heap key, so reinsert it under the new head
      priorityQueue.remove(sentinel);
      linkHead(sentinel);
      priorityQueue.add(sentinel);
    }
  }

  /** Adopts the current head's priority and recency as the queue's heap key. */
  private static void linkHead(Sentinel sentinel) {
    var head = requireNonNull(sentinel.next);
    sentinel.priority = head.priority;
    sentinel.order = head.order;
  }

  /**
   * Converts the cost-to-size ratio to a rounded integer. The ratio is first scaled by the largest
   * observed size so that ratios below one retain their relative order of magnitude, and is then
   * rounded to the configured precision by preserving only its most significant bits.
   */
  @SuppressWarnings("Varifier")
  private int roundedCost(Node node) {
    // Scale by the largest observed size (equivalently, divide the ratio by 1/maxSize, its smallest
    // possible value) before the integer truncation so sub-unit ratios are not collapsed to zero
    int cost = (int) (((double) maxSize / node.weight) * node.penalty);

    // Given a number x, let b be the order of its highest non-zero bit. To round x to precision p,
    // zero out the b − p lower order bits or, in other words, preserve only the p most significant
    // bits starting with b. If b ≤ p, then x is not rounded. With regular rounding, too much
    // information is kept for large values and too little information is kept for small values.
    // Since we don’t know the range of values a priori, we don’t know how to select p to balance
    // the two extremes. Therefore, we prefer the amount of rounding to be proportional to the size
    // of the value itself.
    int msbIndex = Integer.SIZE - Integer.numberOfLeadingZeros(cost);
    int roundMask = (msbIndex <= precision)
        ? Integer.MAX_VALUE
        : bitMask << (msbIndex - precision);
    return (cost & roundMask);
  }

  private void evict() {
    var sentinel = priorityQueue.first();
    var victim = requireNonNull(sentinel.next);
    data.remove(victim.key);
    size -= victim.weight;

    priorityQueue.remove(sentinel);
    victim.remove();
    if (sentinel.isEmpty()) {
      sentinelMapping.remove(sentinel.cost);
    } else {
      linkHead(sentinel);
      priorityQueue.add(sentinel);
    }

    // L advances to the smallest priority remaining in the cache
    if (!priorityQueue.isEmpty()) {
      minPriority = priorityQueue.first().priority;
    }
    policyStats.recordEviction();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    checkState(size <= maximumSize, "%s > %s", size, maximumSize);

    long weightedSize = data.values().stream().mapToLong(node -> node.weight).sum();
    checkState(weightedSize == size, "%s != %s", weightedSize, size);

    checkState(priorityQueue.size() == sentinelMapping.size());
    checkState(priorityQueue.containsAll(sentinelMapping.values()));
  }

  static final class Sentinel extends Node {
    public Sentinel(int cost) {
      super(Long.MIN_VALUE);
      this.cost = cost;
      prev = next = this;
    }

    /** Returns if the queue is empty. */
    public boolean isEmpty() {
      return (next == this);
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node node) {
      var tail = requireNonNull(prev);
      prev = node;
      tail.next = node;
      node.next = this;
      node.prev = tail;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("cost", cost)
          .add("priority", priority)
          .toString();
    }
  }

  enum TieBreaker {
    LRU, COST;

    Comparator<Sentinel> comparator() {
      var comparator = Comparator.comparingLong((Sentinel s) -> s.priority);
      return switch (this) {
        case LRU -> comparator.thenComparingLong(s -> s.order);
        case COST -> comparator.thenComparingInt(s -> s.cost);
      };
    }
  }

  static class Node {
    final long key;

    @Nullable Sentinel sentinel;
    @Nullable Node prev;
    @Nullable Node next;

    int weight;
    int cost;
    long priority;
    long order;
    double penalty;

    public Node(long key) {
      this.key = key;
    }

    public Node(long key, int weight) {
      this.weight = weight;
      this.key = key;
    }

    /** Removes the node from the list. */
    public void remove() {
      checkState(!(this instanceof Sentinel));
      requireNonNull(prev);
      requireNonNull(next);

      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .toString();
    }
  }

  static final class CampSettings extends BasicSettings {
    public CampSettings(Config config) {
      super(config);
    }
    public int precision() {
      return config().getInt("camp.precision");
    }
    public TieBreaker tieBreak() {
      var tieBreaker = config().getString("camp.tie-break");
      return Enums.getIfPresent(TieBreaker.class, tieBreaker.toUpperCase(US)).toJavaUtil()
          .orElseThrow(() -> new IllegalArgumentException("Unknown tie breaker: " + tieBreaker));
    }
  }
}
