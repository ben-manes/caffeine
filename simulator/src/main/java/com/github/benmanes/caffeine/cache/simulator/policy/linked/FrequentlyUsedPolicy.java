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

import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Least/Most Frequency Used in O(1) time as described in <a href="http://dhruvbird.com/lfu.pdf"> An
 * O(1) algorithm for implementing the LFU cache eviction scheme</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class FrequentlyUsedPolicy implements KeyOnlyPolicy {
  final PolicyStats policyStats;
  final Long2ObjectMap<Node> data;
  final EvictionPolicy policy;
  final FrequencyNode freq0;
  final Admittor admittor;
  final int maximumSize;

  public FrequentlyUsedPolicy(Admission admission, EvictionPolicy policy, Config config) {
    var settings = new BasicSettings(config);
    this.policyStats = new PolicyStats(admission.format(policy.label()));
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.admittor = admission.from(config, policyStats);
    this.data = new Long2ObjectOpenHashMap<>();
    this.policy = requireNonNull(policy);
    this.freq0 = new FrequencyNode();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, EvictionPolicy policy) {
    var settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new FrequentlyUsedPolicy(admission, policy, config)
    ).collect(toUnmodifiableSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
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

  /** Moves the entry to the next higher frequency list, creating it if necessary. */
  private void onHit(Node node) {
    policyStats.recordHit();

    int newCount = node.freq.count + 1;
    var next = requireNonNull(node.freq.next);
    FrequencyNode freqN = (next.count == newCount) ? next : new FrequencyNode(newCount, node.freq);
    node.remove();
    if (node.freq.isEmpty()) {
      node.freq.remove();
    }
    node.freq = freqN;
    node.append();
  }

  /** Adds the entry, creating an initial frequency list of 1 if necessary, and evicts if needed. */
  private void onMiss(long key) {
    var next = requireNonNull(freq0.next);
    FrequencyNode freq1 = (next.count == 1) ? next : new FrequencyNode(1, freq0);
    var node = new Node(freq1, key);
    policyStats.recordMiss();
    data.put(key, node);
    node.append();
    evict(node);
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    if (data.size() > maximumSize) {
      Node victim = nextVictim(candidate);
      boolean admit = admittor.admit(candidate.key, victim.key);
      if (admit) {
        evictEntry(victim);
      } else {
        evictEntry(candidate);
      }
      policyStats.recordEviction();
    }
  }

  /**
   * Returns the next victim, excluding the newly added candidate. This exclusion is required so
   * that a candidate has a fair chance to be used, rather than always rejected due to existing
   * entries having a high frequency from the distant past.
   */
  Node nextVictim(Node candidate) {
    if (policy == EvictionPolicy.MFU) {
      // highest, never the candidate
      var prev = requireNonNull(freq0.prev);
      return requireNonNull(prev.nextNode.next);
    }

    // find the lowest that is not the candidate
    @Var Node victim = requireNonNull(freq0.next).nextNode.next;
    if (victim == candidate) {
      victim = (victim.next == victim.prev)
          ? requireNonNull(victim.freq.next).nextNode.next
          : victim.next;
    }
    return requireNonNull(victim);
  }

  /** Removes the entry. */
  private void evictEntry(Node node) {
    data.remove(node.key);
    node.remove();
    if (node.freq.isEmpty()) {
      node.freq.remove();
    }
  }

  public enum EvictionPolicy {
    LFU, MFU;

    public String label() {
      return "linked." + StringUtils.capitalize(name().toLowerCase(US));
    }
  }

  /** A frequency count and associated chain of cache entries. */
  static final class FrequencyNode {
    final int count;
    final Node nextNode;

    @Nullable FrequencyNode prev;
    @Nullable FrequencyNode next;

    public FrequencyNode() {
      nextNode = new Node(this);
      this.prev = this;
      this.next = this;
      this.count = 0;
    }

    public FrequencyNode(int count, FrequencyNode prev) {
      requireNonNull(prev.next);
      nextNode = new Node(this);
      this.prev = prev;
      this.next = prev.next;
      prev.next = this;
      next.prev = this;
      this.count = count;
    }

    public boolean isEmpty() {
      return (nextNode == nextNode.next);
    }

    /** Removes the node from the list. */
    public void remove() {
      requireNonNull(prev);
      requireNonNull(next);

      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("count", count)
          .toString();
    }
  }

  /** A cache entry on the frequency node's chain. */
  static final class Node {
    final long key;

    FrequencyNode freq;
    @Nullable Node prev;
    @Nullable Node next;

    public Node(FrequencyNode freq) {
      this.key = Long.MIN_VALUE;
      this.freq = freq;
      this.prev = this;
      this.next = this;
    }

    public Node(FrequencyNode freq, long key) {
      this.next = null;
      this.prev = null;
      this.freq = freq;
      this.key = key;
    }

    /** Appends the node to the tail of the list. */
    public void append() {
      prev = requireNonNull(freq.nextNode.prev);
      next = requireNonNull(freq.nextNode);
      prev.next = this;
      next.prev = this;
    }

    /** Removes the node from the list. */
    public void remove() {
      requireNonNull(prev);
      requireNonNull(next);

      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("freq", freq)
          .toString();
    }
  }
}
