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
package com.github.benmanes.caffeine.cache.simulator.policy.adaptive;

import static com.google.common.base.Preconditions.checkState;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Adaptive Replacement Cache. This algorithm uses a queue for items that are seen once (T1), a
 * queue for items seen multiple times (T2), and non-resident queues for evicted items that are
 * being monitored (B1, B2). The maximum size of the T1 and T2 queues is adjusted dynamically based
 * on the workload patterns and effectiveness of the cache.
 * <p>
 * This implementation is based on the pseudo code provided by the authors in their paper
 * <a href="http://www.cs.cmu.edu/~15-440/READINGS/megiddo-computer2004.pdf">Outperforming LRU with
 * an Adaptive Replacement Cache Algorithm</a> and is further described in their paper,
 * <a href="https://www.usenix.org/event/fast03/tech/full_papers/megiddo/megiddo.pdf">ARC: A
 * Self-Tuning, Low Overhead Replacement Cache</a>.
 * <p>
 * This algorithm is patented by IBM (6996676, 7096321, 7058766, 8612689) and Sun (7469320), making
 * its use in applications ambiguous due to Sun's ZFS providing an implementation under the CDDL.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "adaptive.Arc")
public final class ArcPolicy implements KeyOnlyPolicy {
  // In Cache:
  // - T1: Pages that have been accessed at least once
  // - T2: Pages that have been accessed at least twice
  // Ghost:
  // - B1: Evicted from T1
  // - B2: Evicted from T2
  // Adapt:
  // - Hit in B1 should increase size of T1, drop entry from T2 to B2
  // - Hit in B2 should increase size of T2, drop entry from T1 to B1

  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final int maximumSize;

  private final Node headT1;
  private final Node headT2;
  private final Node headB1;
  private final Node headB2;

  private int sizeT1;
  private int sizeT2;
  private int sizeB1;
  private int sizeB2;
  private int p;

  public ArcPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.policyStats = new PolicyStats(name());
    this.data = new Long2ObjectOpenHashMap<>();
    this.headT1 = new Node();
    this.headT2 = new Node();
    this.headB1 = new Node();
    this.headB2 = new Node();
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(key);
    } else if (node.type == QueueType.B1) {
      onHitB1(node);
    } else if (node.type == QueueType.B2) {
      onHitB2(node);
    } else {
      onHit(node);
    }
  }

  private void onHit(Node node) {
    // x ∈ T1 ∪ T2 (a hit in ARC(c) and DBL(2c)): Move x to the top of T2

    if (node.type == QueueType.T1) {
      sizeT1--;
      sizeT2++;
    }
    node.remove();
    node.type = QueueType.T2;
    node.appendToTail(headT2);
    policyStats.recordHit();
  }

  private void onHitB1(Node node) {
    // x ∈ B1 (a miss in ARC(c), a hit in DBL(2c)):
    // Adapt p = min{ c, p + max{ |B2| / |B1|, 1} }. REPLACE(p).
    // Move x to the top of T2 and place it in the cache.

    p = Math.min(maximumSize, p + Math.max(sizeB2 / sizeB1, 1));
    evict(node);

    sizeT2++;
    sizeB1--;
    node.remove();
    node.type = QueueType.T2;
    node.appendToTail(headT2);
    policyStats.recordMiss();
  }

  private void onHitB2(Node node) {
    // x ∈ B2 (a miss in ARC(c), a hit in DBL(2c)):
    // Adapt p = max{ 0, p – max{ |B1| / |B2|, 1} } . REPLACE(p).
    // Move x to the top of T2 and place it in the cache.

    p = Math.max(0, p - Math.max(sizeB1 / sizeB2, 1));
    evict(node);

    sizeT2++;
    sizeB2--;
    node.remove();
    node.type = QueueType.T2;
    node.appendToTail(headT2);
    policyStats.recordMiss();
  }

  private void onMiss(long key) {
    // x ∈ L1 ∪ L2 (a miss in DBL(2c) and ARC(c)):
    // case (i) |L1| = c:
    //   If |T1| < c then delete the LRU page of B1 and REPLACE(p).
    //   else delete LRU page of T1 and remove it from the cache.
    // case (ii) |L1| < c and |L1| + |L2|≥ c:
    //   if |L1| + |L2|= 2c then delete the LRU page of B2.
    //   REPLACE(p) .
    // Put x at the top of T1 and place it in the cache.

    Node node = new Node(key);
    node.type = QueueType.T1;

    int sizeL1 = (sizeT1 + sizeB1);
    int sizeL2 = (sizeT2 + sizeB2);
    if (sizeL1 == maximumSize) {
      if (sizeT1 < maximumSize) {
        Node victim = headB1.next;
        data.remove(victim.key);
        victim.remove();
        sizeB1--;

        evict(node);
      } else {
        Node victim = headT1.next;
        data.remove(victim.key);
        victim.remove();
        sizeT1--;
      }
    } else if ((sizeL1 < maximumSize) && ((sizeL1 + sizeL2) >= maximumSize)) {
      if ((sizeL1 + sizeL2) >= (2 * maximumSize)) {
        Node victim = headB2.next;
        data.remove(victim.key);
        victim.remove();
        sizeB2--;
      }
      evict(node);
    }

    sizeT1++;
    data.put(key, node);
    node.appendToTail(headT1);

    policyStats.recordMiss();
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    // if (|T1| ≥ 1) and ((x ∈ B2 and |T1| = p) or (|T1| > p))
    //   then move the LRU page of T1 to the top of B1 and remove it from the cache.
    // else move the LRU page in T2 to the top of B2 and remove it from the cache.

    if ((sizeT1 >= 1) && (((candidate.type == QueueType.B2) && (sizeT1 == p)) || (sizeT1 > p))) {
      Node victim = headT1.next;
      victim.remove();
      victim.type = QueueType.B1;
      victim.appendToTail(headB1);
      sizeT1--;
      sizeB1++;
    } else {
      Node victim = headT2.next;
      victim.remove();
      victim.type = QueueType.B2;
      victim.appendToTail(headB2);
      sizeT2--;
      sizeB2++;
    }
    policyStats.recordEviction();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    policyStats.setPercentAdaption((sizeT1 / (double) maximumSize) - 0.5);

    checkState(sizeT1 == data.values().stream().filter(node -> node.type == QueueType.T1).count());
    checkState(sizeT2 == data.values().stream().filter(node -> node.type == QueueType.T2).count());
    checkState(sizeB1 == data.values().stream().filter(node -> node.type == QueueType.B1).count());
    checkState(sizeB2 == data.values().stream().filter(node -> node.type == QueueType.B2).count());
    checkState((sizeT1 + sizeT2) <= maximumSize);
    checkState((sizeB1 + sizeB2) <= maximumSize);
  }

  private enum QueueType {
    T1, B1,
    T2, B2,
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
      type = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("type", type)
          .toString();
    }
  }
}
