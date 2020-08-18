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
package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import java.util.Arrays;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * "Quadruply-segmented LRU. Four queues are maintained at levels 0 to 3. On a cache miss, the item
 * is inserted at the head of queue 0. On a cache hit, the item is moved to the head of the next
 * higher queue (items in queue 3 move to the head of queue 3). Each queue is allocated 1/4 of the
 * total cache size and items are evicted from the tail of a queue to the head of the next lower
 * queue to maintain the size invariants. Items evicted from queue 0 are evicted from the cache."
 *
 * For more details, see <a href="http://www.cs.cornell.edu/~qhuang/papers/sosp_fbanalysis.pdf"An
 * Analysis of Facebook Photo Caching</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class S4LruPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final int maximumSize;
  private final Node[] headQ;
  private final int[] sizeQ;
  private final int levels;

  public S4LruPolicy(Admission admission, Config config) {
    S4LruSettings settings = new S4LruSettings(config);
    this.policyStats = new PolicyStats(admission.format("linked.S4Lru"));
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.admittor = admission.from(config, policyStats);
    this.data = new Long2ObjectOpenHashMap<>();
    this.levels = settings.levels();
    this.headQ = new Node[levels];
    this.sizeQ = new int[levels];

    Arrays.setAll(headQ, Node::sentinel);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new S4LruPolicy(admission, config)
    ).collect(toSet());
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    admittor.record(key);
    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else {
      onHit(node);
      policyStats.recordHit();
    }
  }

  private void onHit(Node node) {
    node.remove();
    sizeQ[node.level]--;
    if (node.level < (levels - 1)) {
      node.level++;
    }

    Node head = headQ[node.level];
    node.appendToTail(head);
    sizeQ[node.level]++;

    adjust();
  }

  private void onMiss(long key) {
    Node node = new Node(key);
    data.put(key, node);
    node.appendToTail(headQ[0]);
    sizeQ[0]++;

    adjust();
    evict(node);
  }

  private void adjust() {
    int maxPerLevel = maximumSize / levels;
    for (int i = levels - 1; i > 0; i--) {
      if (sizeQ[i] > maxPerLevel) {
        Node demote = headQ[i].next;
        demote.remove();
        sizeQ[i]--;

        demote.level = i - 1;
        sizeQ[demote.level]++;
        demote.appendToTail(headQ[demote.level]);
      }
    }
  }

  private void evict(Node candidate) {
    if (data.size() > maximumSize) {
      policyStats.recordEviction();

      Node victim = headQ[0].next;
      boolean admit = admittor.admit(candidate.key, victim.key);
      if (admit) {
        evictEntry(victim);
        sizeQ[0]--;
      } else {
        evictEntry(candidate);
        sizeQ[candidate.level]--;
      }
    }
  }

  private void evictEntry(Node node) {
    data.remove(node.key);
    node.remove();
  }

  @Override
  public void finished() {
    for (int i = 0; i < levels; i++) {
      int level = i;
      long count = data.values().stream().filter(node -> node.level == level).count();
      checkState(count == sizeQ[i]);
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class Node {
    final long key;

    Node prev;
    Node next;
    int level;

    Node(long key) {
      this.key = key;
    }

    static Node sentinel(int level) {
      Node node = new Node(Long.MIN_VALUE);
      node.level = level;
      node.prev = node;
      node.next = node;
      return node;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      checkState(key != Long.MIN_VALUE);

      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("level", level)
          .toString();
    }
  }

  static final class S4LruSettings extends BasicSettings {
    public S4LruSettings(Config config) {
      super(config);
    }
    public int levels() {
      return config().getInt("s4lru.levels");
    }
  }
}
