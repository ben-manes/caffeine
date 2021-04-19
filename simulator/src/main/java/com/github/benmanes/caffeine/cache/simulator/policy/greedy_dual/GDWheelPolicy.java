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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Greedy Dual Wheel (GD-Wheel) algorithm.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://conglongli.github.io/paper/gdwheel-eurosys2015.pdf">GD-Wheel: A Cost-Aware
 * ReplacementPolicy for Key-Value Stores</a>. This a cost-aware cache policy, which takes into
 * account the access times (i.e. the cost of recomputing or fetching the data). This is also a
 * weighted policy, meaning it supports non-uniform entry sizes.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "greedy-dual.GDWheel", characteristics = WEIGHTED)
public final class GDWheelPolicy implements Policy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Sentinel[][] wheel;
  private final long maximumSize;
  private final int[] clockHand;
  private final double[] cost;

  private int size;

  public GDWheelPolicy(Config config) {
    var settings = new GDWheelSettings(config);
    this.maximumSize = settings.maximumSize();
    this.policyStats = new PolicyStats(name());
    this.data = new Long2ObjectOpenHashMap<>();
    this.cost = new double[settings.numberOfWheels()];
    this.clockHand = new int[settings.numberOfWheels()];
    this.wheel = new Sentinel[settings.numberOfWheels()][settings.numberOfQueues()];

    for (int i = 0; i < settings.numberOfWheels(); i++) {
      for (int j = 0; j < settings.numberOfQueues(); j++) {
        wheel[i][j] = new Sentinel(i, j);
      }
      cost[i] = Math.pow(settings.numberOfQueues(), i);
    }
  }

  @Override
  public void record(AccessEvent event) {
    Node node = data.get(event.key());
    policyStats.recordOperation();
    if (node == null) {
      policyStats.recordWeightedMiss(event.weight());
      node = new Node(event.key());
      onMiss(event, node);
    } else {
      policyStats.recordWeightedHit(event.weight());
      onHit(event, node);
    }
  }

  private void onMiss(AccessEvent event, Node node) {
    evict(event);
    add(event, node);
  }

  private void evict(AccessEvent event) {
    // while there is not enough room in memory for p
    while ((size + event.weight()) > maximumSize) {
      // C[1] ← index of next non-empty queue in level 1 Cost Wheel
      int hand = findNextQueue();
      if (hand >= 0) {
        // Evict q at the tail of the C[1]th queue in level 1 Cost Wheel
        var victim = wheel[0][hand].prev;
        policyStats.recordEviction();
        remove(victim);
      } else {
        // if C[1] has advanced a whole round back to 1, call migration(2)
        migrate(1);
      }
    }
  }

  /** Returns the index to the next non-empty queue, or -1 if all are empty. */
  private int findNextQueue() {
    for (int i = 0; i < wheel[0].length; i++) {
      int index = (clockHand[0] + i) % wheel[0].length;
      if (!wheel[0][index].isEmpty()) {
        return index;
      }
    }
    return -1;
  }

  private void migrate(int level) {
    // C[idx] ← (C[idx] + 1) mod NQ
    int hand = (clockHand[level] + 1) % wheel[level].length;
    clockHand[level] = hand;

    // if C[idx] has advanced a whole round back to 1, call migration(idx+1)
    if ((hand == 0) && (level < wheel.length)) {
      migrate(level + 1);
    }

    // For each object p in the C[idx]th queue in the level idx Cost Wheel
    for (int i = 0; i < wheel[level].length; i++) {
      var sentinel = wheel[level][hand];
      if (!sentinel.isEmpty()) {
        // Remove p
        var node = sentinel.next;
        node.remove();

        // Cost Remainder ← c(p) mod NQ^(idx-1)
        double remainder = (node.cost % cost[level]);

        // Q ← (round(Cost Remainder / NQ^(idx-2)) + C[idx-1]) mod NQ
        int index = (int) ((Math.round(remainder / cost[level - 1]) + hand) % wheel[level].length);

        // Insert p to the head of Qth queue in the level (idx−1) Cost Wheel
        wheel[level - 1][index].appendToHead(node);
      }
    }
  }

  private void onHit(AccessEvent event, Node node) {
    remove(node);
    evict(event);
    add(event, node);
  }

  private void remove(Node node) {
    data.remove(node.key);
    size -= node.weight;
    node.remove();
  }

  private void add(AccessEvent event, Node node) {
    // W ← max { i | 0 < i ≤ NW and round(c(p) / NQ^(i-1)) > 0 }
    int wheelHand = 0;
    int wheelIndex = 0;
    long relativeCost = 0;
    for (int i = 0; i < wheel.length; i++) {
      long penalty = Math.round(event.missPenalty() / cost[i]);
      if (penalty > 0) {
        wheelHand = clockHand[i];
        relativeCost = penalty;
        wheelIndex = i;
      }
    }

    // Q ← (round( c(p) / NQ^(W-1) ) + C[W]) mod NQ
    int queueIndex = (int) ((relativeCost + wheelHand) % wheel.length);
    var sentinel = wheel[wheelIndex][queueIndex];

    // Insert p to the head of Qth queue in the level W Cost Wheel
    node.cost = event.missPenalty();
    node.weight = event.weight();
    sentinel.appendToHead(node);
    data.put(event.key(), node);
    size += event.weight();
  }

  @Override
  public void finished() {
    int expectedSize = data.values().stream().mapToInt(node -> node.weight).sum();
    checkState(data.size() <= maximumSize, "%s > %s", data.size(), maximumSize);
    checkState(size == expectedSize, "%s != %s", size, expectedSize);

    int nodes = 0;
    for (var costWheel : wheel) {
      for (var sentinel : costWheel) {
        Node next = sentinel.next;
        while (next != sentinel) {
          next = next.next;
          nodes++;
        }
      }
    }
    checkState(nodes == data.size(), "%s != %s", size, expectedSize);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  private static final class Sentinel extends Node {
    final int wheelIndex;
    final int queueIndex;

    public Sentinel(int wheelIndex, int queueIndex) {
      super(Long.MIN_VALUE);
      this.wheelIndex = wheelIndex;
      this.queueIndex = queueIndex;
      prev = next = this;
    }

    /** Returns if the queue is empty. */
    public boolean isEmpty() {
      return (next == this);
    }

    /** Appends the node to the head of the list. */
    public void appendToHead(Node node) {
      node.prev = this;
      node.next = next;
      next.prev = node;
      next = node;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("wheelIndex", wheelIndex)
          .add("queueIndex", queueIndex)
          .toString();
    }
  }

  private static class Node {
    final long key;

    double cost;
    int weight;

    Node prev;
    Node next;

    public Node(long key) {
      this.key = key;
    }

    /** Removes the node from the list. */
    public void remove() {
      checkState(!(this instanceof Sentinel));
      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("cost", cost)
          .add("weight", weight)
          .toString();
    }
  }

  private static final class GDWheelSettings extends BasicSettings {
    public GDWheelSettings(Config config) {
      super(config);
    }
    public int numberOfWheels() {
      return config().getInt("gd-wheel.wheels");
    }
    public int numberOfQueues() {
      return config().getInt("gd-wheel.queues");
    }
  }
}
