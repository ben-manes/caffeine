/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The SIEVE algorithm. This algorithm modifies the classic Clock policy (aka Second Chance) to
 * decouple the clock hand from the insertion point, so that recent arrival are more likely to be
 * evicted early (as potential one-hit wonders).
 * <p>
 * This implementation is based on the code provided by the authors at
 * <a href="https://cachemon.github.io/SIEVE-website/blog/2023/12/17/sieve-is-simpler-than-lru/">
 * SIEVE is simpler than LRU</a> and described by the paper
 * <a href="https://yazhuozhang.com/assets/pdf/nsdi24-sieve.pdf">SIEVE: an Efficient Turn-Key
 * Eviction Algorithm for Web Caches</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "linked.Sieve", characteristics = WEIGHTED)
public final class SievePolicy implements Policy {
  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final long maximumSize;

  @Nullable Node head;
  @Nullable Node tail;
  @Nullable Node hand;

  long size;

  public SievePolicy(Config config) {
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    var settings = new BasicSettings(config);
    this.maximumSize = settings.maximumSize();
  }

  @Override
  public void record(AccessEvent event) {
    policyStats.recordOperation();
    Node node = data.get(event.key());
    if (node == null) {
      onMiss(event);
    } else {
      onHit(event, node);
    }
  }

  private void onHit(AccessEvent event, Node node) {
    policyStats.recordWeightedHit(event.weight());
    size += (event.weight() - node.weight);
    node.weight = event.weight();
    node.visited = true;

    while (size > maximumSize) {
      evict();
    }
  }

  private void onMiss(AccessEvent event) {
    if (event.weight() > maximumSize) {
      policyStats.recordWeightedMiss(event.weight());
      return;
    }
    while ((size + event.weight()) >= maximumSize) {
      evict();
    }
    policyStats.recordWeightedMiss(event.weight());
    var node = new Node(event.key(), event.weight());
    data.put(event.key(), node);
    size += event.weight();
    addToHead(node);
  }

  private void evict() {
    @Var var victim = (hand == null) ? tail : hand;
    while ((victim != null) && victim.visited) {
      victim.visited = false;
      victim = (victim.prev == null) ? tail : victim.prev;
      policyStats.recordOperation();
    }
    if (victim != null) {
      policyStats.recordEviction();
      data.remove(victim.key);
      size -= victim.weight;
      hand = victim.prev;
      remove(victim);
    }
  }

  private void addToHead(Node node) {
    checkState(node.prev == null);
    checkState(node.next == null);

    node.next = head;
    if (head != null) {
      head.prev = node;
    }
    head = node;
    if (tail == null) {
      tail = node;
    }
  }

  private void remove(Node node) {
    if (node.prev != null) {
      node.prev.next = node.next;
    } else {
      head = node.next;
    }
    if (node.next != null) {
      node.next.prev = node.prev;
    } else {
      tail = node.prev;
    }
  }

  @Override
  public void finished() {
    checkState(size <= maximumSize, "%s > %s", size, maximumSize);
    long weightedSize = data.values().stream().mapToLong(node -> node.weight).sum();
    checkState(weightedSize == size, "%s != %s", weightedSize, size);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class Node {
    final long key;

    @Nullable Node prev;
    @Nullable Node next;

    int weight;
    boolean visited;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key, int weight) {
      this.key = key;
      this.weight = weight;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .add("visited", visited)
          .toString();
    }
  }
}
