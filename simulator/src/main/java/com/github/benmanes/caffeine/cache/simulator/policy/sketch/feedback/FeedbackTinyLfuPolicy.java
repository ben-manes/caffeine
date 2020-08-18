/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.feedback;

import static com.google.common.base.Preconditions.checkState;

import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The TinyLfu + Lru algorithm where arrival is given more emphasis based on success of previous
 * predictions. If a candidate is rejected multiple times within a sample period then a new arrival
 * is given a higher frequency gain. The gain is decreased on an eviction if no adjustments have
 * been made recently. This allows the policy to dynamically decide whether it should favor recency
 * or frequency based on the workload's characteristics. If the workload changes then the policy
 * will adapt to the new environment.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class FeedbackTinyLfuPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final TinyLfu admittor;
  private final int maximumSize;
  private final Node head;

  private int gain;
  private final int maxGain;

  private int sample;
  private int sampled;
  private int adjusted;
  private final int sampleSize;
  private final Membership feedback;

  boolean debug;

  public FeedbackTinyLfuPolicy(Config config) {
    FeedbackTinyLfuSettings settings = new FeedbackTinyLfuSettings(config);
    this.policyStats = new PolicyStats("sketch.FeedbackTinyLfu");
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.admittor = new TinyLfu(settings.config(), policyStats);
    this.data = new Long2ObjectOpenHashMap<>();
    this.head = new Node();

    maxGain = Math.min(15, settings.maximumInsertionGain());
    sampleSize = Math.min(settings.maximumSampleSize(), maximumSize);
    feedback = settings.membership().filter().create(settings.filterConfig(sampleSize));
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new FeedbackTinyLfuPolicy(config));
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(long key) {
    if ((sample % sampleSize) == 0) {
      sampled++;
    }
    if (sample % (sampleSize / 2) == 0) {
      feedback.clear();
    }
    sample++;

    admittor.record(key);
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else {
      onHit(node);
      policyStats.recordHit();
    }
  }

  /** Adds the entry, evicting if necessary. */
  private void onMiss(long key) {
    for (int i = 0; i < gain; i++) {
      admittor.record(key);
    }

    Node node = new Node(key);
    node.appendToTail(head);
    data.put(key, node);
    evict(node);
  }

  /** Moves the entry to the MRU position. */
  private void onHit(Node node) {
    node.moveToTail(head);
  }

  /**
   * If the size exceeds the maximum, then the candidate and victim are evaluated and one is
   * evicted.
   */
  private void evict(Node candidate) {
    if (data.size() > maximumSize) {
      Node evict;
      Node victim = head.next;
      if (admittor.admit(candidate.key, victim.key)) {
        evict = victim;
      } else if (adapt(candidate)) {
        evict = victim;
      } else {
        evict = candidate;
        feedback.put(candidate.key);
      }
      data.remove(evict.key);
      evict.remove();

      policyStats.recordEviction();
    }
  }

  private boolean adapt(Node candidate) {
    if (adjusted == sampled) {
      // Already adjusted this period
      return false;
    }

    if (feedback.mightContain(candidate.key)) {
      if (sampled >= (adjusted + gain)) {
        adjusted = sampled;

        // Increase arrival emphasis
        if (gain < maxGain) {
          gain++;
        }
      }
      return true;
    } else if (sampled > (adjusted + gain + 1)) {
      adjusted = sampled;

      // Decrease arrival emphasis
      if (gain > 0) {
        gain--;
      }
    }
    return false;
  }

  @Override
  public void finished() {
    if (debug) {
      System.out.println("recency gain = " + gain);
    }
    checkState(data.size() <= maximumSize, data.size());
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;

    Node prev;
    Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Integer.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key) {
      this.key = key;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
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
      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .toString();
    }
  }

  static final class FeedbackTinyLfuSettings extends BasicSettings {
    public FeedbackTinyLfuSettings(Config config) {
      super(config);
    }
    public int maximumInsertionGain() {
      return config().getInt("feedback-tiny-lfu.maximum-insertion-gain");
    }
    public int maximumSampleSize() {
      return config().getInt("feedback-tiny-lfu.maximum-sample-size");
    }
    public double adaptiveFpp() {
      return config().getDouble("feedback-tiny-lfu.adaptive-fpp");
    }
    public Config filterConfig(int sampleSize) {
      Map<String, Object> properties = ImmutableMap.of(
          "membership.fpp", adaptiveFpp(),
          "maximum-size", sampleSize);
      return ConfigFactory.parseMap(properties).withFallback(config());
    }
  }
}
