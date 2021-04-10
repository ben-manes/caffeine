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

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * "Segmented LRU is based on the observation that objects with at least two accesses are much more
 * popular than those with only one access during a short interval. In Segmented LRU, cache space is
 * partitioned into two segments: probationary segment and protected segment.
 * <p>
 * New objects (with only one access) are first faulted into the probationary segment, whereas
 * objects with two or more accesses are kept in the protected segment. When a probationary object
 * gets one more reference, it will change to the protected segment. When the whole cache space
 * becomes full, the least recently used object in the probationary segment will first be replaced.
 * The protected segment is finite in size. When it gets full, the overflowed will be re-cached in
 * probationary segment. Since objects in protected segment have to go a longer way before being
 * evicted, popular object or an object with more accesses tends to be kept in cache for longer
 * time." from <a href="
 * http://www.is.kyusan-u.ac.jp/~chengk/pub/papers/compsac00_A07-07.pdf">LRU-SP: A Size-Adjusted and
 * Popularity-Aware LRU Replacement Algorithm for Web Caching</a>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "linked.SegmentedLru")
public final class SegmentedLruPolicy implements KeyOnlyPolicy {
  static final Node UNLINKED = new Node();

  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final Node headProtected;
  final Node headProbation;
  final Admittor admittor;
  final int maxProtected;
  final int maximumSize;

  int sizeProtected;

  public SegmentedLruPolicy(Admission admission, Config config) {
    this.policyStats = new PolicyStats(admission.format(name()));
    this.admittor = admission.from(config, policyStats);

    SegmentedLruSettings settings = new SegmentedLruSettings(config);
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.maxProtected = (int) (maximumSize * settings.percentProtected());
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new SegmentedLruPolicy(admission, config)
    ).collect(toSet());
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    admittor.record(key);
    if (node == null) {
      onMiss(key);
    } else {
      onHit(node);
    }
  }

  private void onHit(Node node) {
    if (node.type == QueueType.PROTECTED) {
      node.moveToTail(headProtected);
    } else {
      sizeProtected++;
      if (sizeProtected > maxProtected) {
        Node demote = headProtected.next;
        demote.remove();
        demote.type = QueueType.PROBATION;
        demote.appendToTail(headProbation);
        sizeProtected--;
      }
      node.remove();
      node.type = QueueType.PROTECTED;
      node.appendToTail(headProtected);
    }
    policyStats.recordHit();
  }

  private void onMiss(long key) {
    Node node = new Node(key);
    data.put(key, node);
    policyStats.recordMiss();
    node.appendToTail(headProbation);
    node.type = QueueType.PROBATION;
    evict(node);
  }

  private void evict(Node candidate) {
    if (data.size() > maximumSize) {
      Node victim = (maxProtected == 0)
          ? headProtected.next // degrade to LRU
          : headProbation.next;
      policyStats.recordEviction();

      boolean admit = admittor.admit(candidate.key, victim.key);
      if (admit) {
        evictEntry(victim);
      } else {
        evictEntry(candidate);
      }
    }
  }

  private void evictEntry(Node node) {
    data.remove(node.key);
    node.remove();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  enum QueueType {
    PROTECTED,
    PROBATION,
  }

  static final class Node {
    final long key;

    Node prev;
    Node next;
    QueueType type;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key) {
      this.key = key;
      this.prev = UNLINKED;
      this.next = UNLINKED;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Moves the node to the tail. */
    public void moveToTail(Node head) {
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = head;
      prev = head.prev;
      head.prev = this;
      prev.next = this;
    }

    /** Removes the node from the list. */
    public void remove() {
      checkState(key != Long.MIN_VALUE);

      prev.next = next;
      next.prev = prev;
      prev = next = UNLINKED; // mark as unlinked
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("type", type)
          .toString();
    }
  }

  static final class SegmentedLruSettings extends BasicSettings {

    public SegmentedLruSettings(Config config) {
      super(config);
    }

    public double percentProtected() {
      return config().getDouble("segmented-lru.percent-protected");
    }
  }
}
