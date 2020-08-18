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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.Arrays;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectSortedMap;

/**
 * The MultiQueue algorithm. This algorithm organizes entries into queues that represent a frequency
 * range. When an entry is accessed, it may be promoted to the next higher queue and, regardless, is
 * reordered to the least-recently-used position in the queue it resides in. A non-resident queue
 * retains evicted items that are being monitored (OUT) to allow entries to retain their historic
 * frequency and be eagerly promoted.
 * <p>
 * This policy is designed for second-level caches where a hit in this cache was a miss at the first
 * level. Thus the first-level cache captures most of the recency information and the second-level
 * cache access is dominated by usage frequency.
 * <p>
 * This implementation is based on the pseudo code provided by the authors in their paper
 * <a href="https://www.usenix.org/legacy/event/usenix01/full_papers/zhou/zhou.pdf">The Multi-Queue
 * Replacement Algorithm for Second Level. Buffer Caches</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MultiQueuePolicy implements KeyOnlyPolicy {
  private final Long2ObjectSortedMap<Node> out;
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final long[] threshold;
  private final int maximumSize;
  private final long lifetime;
  private final Node[] headQ;
  private final int maxOut;

  private long currentTime;

  public MultiQueuePolicy(Config config) {
    MultiQueueSettings settings = new MultiQueueSettings(config);
    maximumSize = Ints.checkedCast(settings.maximumSize());
    policyStats = new PolicyStats("linked.MultiQueue");
    threshold = new long[settings.numberOfQueues()];
    headQ = new Node[settings.numberOfQueues()];
    out = new Long2ObjectLinkedOpenHashMap<>();
    data = new Long2ObjectOpenHashMap<>();
    lifetime = settings.lifetime();

    Arrays.setAll(headQ, Node::sentinel);
    Arrays.setAll(threshold, i -> 1L << i);
    maxOut = (int) (maximumSize * settings.percentOut());
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new MultiQueuePolicy(config));
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      policyStats.recordMiss();
      node = out.remove(key);
      if (node == null) {
        node = new Node(key);
      }
      data.put(key, node);
      if (data.size() > maximumSize) {
        policyStats.recordEviction();
        evict();
      }
    } else {
      policyStats.recordHit();
      node.remove();
    }
    node.reference++;
    node.queueIndex = queueIndexFor(node);
    node.appendToTail(headQ[node.queueIndex]);
    node.expireTime = currentTime + lifetime;
    adjust();
  }

  private void adjust() {
    currentTime++;
    for (int i = 1; i < headQ.length; i++) {
      Node node = headQ[i].next;
      if (node.next.expireTime < currentTime) {
        node.remove();
        node.queueIndex = (i - 1);
        node.appendToTail(headQ[node.queueIndex]);
        node.expireTime = currentTime + lifetime;
      }
    }
  }

  private int queueIndexFor(Node node) {
    for (int i = threshold.length - 1; i >= 0; i--) {
      if (node.reference >= threshold[i]) {
        return i;
      }
    }
    throw new IllegalStateException();
  }

  private void evict() {
    Node victim = null;
    for (Node head : headQ) {
      if (head.next != head) {
        victim = head.next;
        break;
      }
    }
    if (victim == null) {
      return;
    }

    victim.remove();
    data.remove(victim.key);
    out.put(victim.key, victim);
    if (out.size() > maxOut) {
      out.remove(out.firstLongKey());
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
    int reference;
    int queueIndex;
    long expireTime;

    Node(long key) {
      this.key = key;
    }

    static Node sentinel(int queueIndex) {
      Node node = new Node(Long.MIN_VALUE);
      node.expireTime = Long.MAX_VALUE;
      node.queueIndex = queueIndex;
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

      queueIndex = -1;
      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("references", reference)
          .toString();
    }
  }

  static final class MultiQueueSettings extends BasicSettings {
    public MultiQueueSettings(Config config) {
      super(config);
    }
    public int lifetime() {
      return config().getInt("multi-queue.lifetime");
    }
    public int numberOfQueues() {
      int queues = config().getInt("multi-queue.num-queues");
      checkArgument(queues > 0, "Must have one or more queues");
      checkArgument(queues <= 62, "May not have more than 62 queues");
      return queues;
    }
    public double percentOut() {
      return config().getDouble("multi-queue.percent-out");
    }
  }
}
