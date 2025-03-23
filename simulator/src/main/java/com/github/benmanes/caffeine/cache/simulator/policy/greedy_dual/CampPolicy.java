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
import static java.util.Objects.requireNonNull;

import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * CAMP algorithm.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.466.393&rep=rep1&type=pdf">
 * CAMP: A Cost Adaptive Multi-Queue Eviction Policy for Key-Value Stores</a>. This is a cost-aware
 * cache policy, which takes into account the access times (i.e. the cost of recomputing or fetching
 * the data). This is also a weighted policy, meaning it supports non-uniform entry sizes.
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

  private long requestCount;
  private int size;

  public CampPolicy(Config config) {
    var settings = new CampSettings(config);

    this.requestCount = 0;
    this.priorityQueue = new TreeSet<>();
    this.precision = settings.precision();
    this.maximumSize = settings.maximumSize();
    this.policyStats = new PolicyStats(name());
    this.data = new Long2ObjectOpenHashMap<>();
    this.sentinelMapping = new Int2ObjectOpenHashMap<>();
    this.bitMask = Integer.MAX_VALUE >> (Integer.SIZE - 1 - precision);
  }

  @Override
  public void record(AccessEvent event) {
    var node = data.get(event.key());
    requestCount++;
    if (node == null) {
      policyStats.recordWeightedMiss(event.weight());
      onMiss(event);
    } else {
      policyStats.recordWeightedHit(event.weight());
      onHit(node);
      size += (event.weight() - node.weight);
      node.weight = event.weight();
      if (size > maximumSize) {
        evict();
      }
    }
  }

  private void onHit(Node node) {
    node.moveToTail();
    if (priorityQueue.size() > 1) {
      var sentinel = sentinelMapping.get(node.cost);
      checkState(priorityQueue.remove(sentinel), "cost %s not found in priority queue", sentinel);
      sentinel.priority = priorityQueue.first().priority + sentinel.cost;
      sentinel.lastRequest = requestCount; // break ties in priority queue
      priorityQueue.add(sentinel);
    }
  }

  @SuppressWarnings("Varifier")
  private int roundedCost(AccessEvent event) {
    // Given a number x, let b be the order of its highest non-zero bit. To round x to precision p,
    // zero out the b − p lower order bits or, in other words, preserve only the p most significant
    // bits starting with b. If b ≤ p, then x is not rounded. With regular rounding, too much
    // information is kept for large values and too little information is kept for small values.
    // Since we don’t know the range of values a priori, we don’t know how to select p to balance
    // the two extremes. Therefore, we prefer the amount of rounding to be proportional to the size
    // of the value itself.

    double penalty = event.isPenaltyAware() ? event.missPenalty() : 1;
    int cost = (int) (penalty / event.weight());

    // find first "on" bit and mask for rounding
    int msbIndex = Integer.SIZE - Integer.numberOfLeadingZeros(cost);
    int roundMask = (msbIndex <= precision)
        ? Integer.MAX_VALUE
        : bitMask << (msbIndex - precision);
    return (cost & roundMask);
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

    int roundCost = roundedCost(event);
    int priority = priorityQueue.isEmpty()
        ? roundCost
        : priorityQueue.first().priority + roundCost;
    var sentinel = sentinelMapping.computeIfAbsent(roundCost, cost -> {
      // Add a new LRU list for the rounded cost
      var head = new Sentinel(cost);
      head.lastRequest = requestCount;
      head.priority = priority;
      priorityQueue.add(head);
      return head;
    });

    // cost of entry might be used later to find sentinel in case of hit
    var node = new Node(event.key(), event.weight(), sentinel);
    node.cost = roundCost;
    sentinel.appendToTail(node);
    data.put(node.key, node);
  }

  private void evict() {
    var sentinel = priorityQueue.first();
    var victim = requireNonNull(sentinel.next);
    data.remove(victim.key);
    size -= victim.weight;
    victim.remove();

    if (sentinel.isEmpty()) {
      sentinelMapping.remove(sentinel.cost);
      priorityQueue.remove(sentinel);
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

  private static final class Sentinel extends Node implements Comparable<Sentinel> {
    long lastRequest;
    int priority;

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
    public int compareTo(Sentinel sentinel) {
      if (priority != sentinel.priority) {
        return Integer.compare(priority, sentinel.priority);
      }
      return Long.signum(sentinel.lastRequest - lastRequest);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return (o instanceof Sentinel node)
          && (priority == node.priority)
          && (cost == node.cost);
    }

    @Override
    public int hashCode() {
      return Objects.hash(cost, priority);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("cost", cost)
          .add("priority", priority)
          .toString();
    }
  }

  private static class Node {
    final long key;

    @Nullable Sentinel sentinel;
    @Nullable Node prev;
    @Nullable Node next;

    int weight;
    int cost;

    public Node(long key) {
      this.key = key;
    }

    public Node(long key, int weight, Sentinel sentinel) {
      this.sentinel = sentinel;
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

    /** Moves the node to the tail. */
    public void moveToTail() {
      requireNonNull(prev);
      requireNonNull(next);

      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = requireNonNull(sentinel);
      prev = requireNonNull(sentinel.prev);
      sentinel.prev = this;
      prev.next = this;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .toString();
    }
  }

  private static final class CampSettings extends BasicSettings {
    public CampSettings(Config config) {
      super(config);
    }
    public int precision() {
      return config().getInt("camp.precision");
    }
  }
}
