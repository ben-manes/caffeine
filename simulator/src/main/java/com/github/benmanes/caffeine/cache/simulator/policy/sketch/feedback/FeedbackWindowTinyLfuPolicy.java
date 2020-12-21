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
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.membership.Membership;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the size of the admission window is adjusted based on the
 * workload. If a candidate is rejected multiple times within a sample period then the window is
 * increased. The window is decreased on an eviction if no adjustments have been made for at least
 * two periods. This allows the policy to dynamically decide whether it should favor recency (larger
 * window) or frequency (smaller window) based on the workload's characteristics. If the workload
 * changes then the policy will adapt to the new environment.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "sketch.FeedbackWindowTinyLfu")
public final class FeedbackWindowTinyLfuPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final TinyLfu admittor;
  private final int maximumSize;

  private final Node headWindow;
  private final Node headProbation;
  private final Node headProtected;

  private int maxWindow;
  private int maxProtected;

  private int sizeWindow;
  private int sizeProtected;

  private int pivot;
  private final int maxPivot;
  private final int pivotIncrement;
  private final int pivotDecrement;

  private int sample;
  private int sampled;
  private int adjusted;
  private final int sampleSize;

  private final Membership feedback;

  boolean debug;
  boolean trace;

  public FeedbackWindowTinyLfuPolicy(double percentMain, FeedbackWindowTinyLfuSettings settings) {
    this.policyStats = new PolicyStats(name() + " (%.0f%%)", 100 * (1.0d - percentMain));
    this.admittor = new TinyLfu(settings.config(), policyStats);
    this.maximumSize = Ints.checkedCast(settings.maximumSize());

    int maxMain = (int) (maximumSize * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxWindow = Math.min(settings.maximumWindowSize(), maximumSize - maxMain);
    this.data = new Long2ObjectOpenHashMap<>();
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();

    pivot = (int) (settings.percentPivot() * maxWindow);
    maxPivot = Math.min(settings.maximumWindowSize(), maxProtected);
    sampleSize = Math.min(settings.maximumSampleSize(), maximumSize);
    feedback = settings.membership().filter().create(settings.filterConfig(sampleSize));

    checkState(settings.pivotIncrement() > 0, "Must increase by at least 1");
    checkState(settings.pivotDecrement() > 0, "Must decrease by at least 1");
    pivotIncrement = settings.pivotIncrement();
    pivotDecrement = settings.pivotDecrement();

    printSegmentSizes();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    FeedbackWindowTinyLfuSettings settings = new FeedbackWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new FeedbackWindowTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
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
    } else if (node.status == Status.WINDOW) {
      onWindowHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.PROBATION) {
      onProbationHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.PROTECTED) {
      onProtectedHit(node);
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key) {
    Node node = new Node(key, Status.WINDOW);
    node.appendToTail(headWindow);
    data.put(key, node);
    sizeWindow++;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowHit(Node node) {
    node.moveToTail(headWindow);
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onProbationHit(Node node) {
    node.remove();
    node.status = Status.PROTECTED;
    node.appendToTail(headProtected);

    sizeProtected++;
    demoteProtected();
  }

  private void demoteProtected() {
    if (sizeProtected > maxProtected) {
      Node demote = headProtected.next;
      demote.remove();
      demote.status = Status.PROBATION;
      demote.appendToTail(headProbation);
      sizeProtected--;
    }
  }

  /** Moves the entry to the MRU position. */
  private void onProtectedHit(Node node) {
    admittor.record(node.key);
    node.moveToTail(headProtected);
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    if (sizeWindow <= maxWindow) {
      return;
    }

    Node candidate = headWindow.next;
    sizeWindow--;

    candidate.remove();
    candidate.status = Status.PROBATION;
    candidate.appendToTail(headProbation);

    if (data.size() > maximumSize) {
      Node evict;
      Node victim = headProbation.next;
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
      adjusted = sampled;

      // Increase admission window
      if (pivot < maxPivot) {
        pivot++;

        maxWindow++;
        sizeWindow++;
        maxProtected--;

        demoteProtected();
        candidate.remove();
        candidate.status = Status.WINDOW;
        candidate.appendToTail(headWindow);

        int increments = Math.min(pivotIncrement - 1, maxPivot - pivot);
        for (int i = 0; i < increments; i++) {
          if (pivot < maxPivot) {
            pivot++;

            maxWindow++;
            sizeWindow++;
            maxProtected--;

            demoteProtected();
            candidate = headProbation.next.next;
            candidate.remove();
            candidate.status = Status.WINDOW;
            candidate.appendToTail(headWindow);
          }
        }

        if (trace) {
          System.out.println("↑" + maxWindow);
        }
      }
      return true;
    } else if (sampled > (adjusted + 1)) {
      adjusted = sampled;

      // Decrease admission window
      boolean decremented = false;
      for (int i = 0; i < pivotDecrement; i++) {
        if (pivot > 0) {
          pivot--;

          maxWindow--;
          sizeWindow--;
          maxProtected++;
          decremented = true;

          candidate = headWindow.next;
          candidate.remove();
          candidate.status = Status.PROBATION;
          candidate.appendToHead(headProbation);
        }
      }

      if (trace && decremented) {
        System.out.println("↓" + maxWindow);
      }
    }
    return false;
  }

  void printSegmentSizes() {
    if (debug) {
      System.out.printf("maxWindow=%d, maxProtected=%d, percentWindow=%.1f%n",
          maxWindow, maxProtected, (double) (100 * maxWindow) / maximumSize);
    }
  }

  @Override
  public void finished() {
    printSegmentSizes();

    long windowSize = data.values().stream().filter(n -> n.status == Status.WINDOW).count();
    long probationSize = data.values().stream().filter(n -> n.status == Status.PROBATION).count();
    long protectedSize = data.values().stream().filter(n -> n.status == Status.PROTECTED).count();

    checkState(windowSize == sizeWindow, "%s != %s", windowSize, sizeWindow);
    checkState(protectedSize == sizeProtected);
    checkState(probationSize == data.size() - windowSize - protectedSize);

    checkState(data.size() <= maximumSize, data.size());
  }

  enum Status {
    WINDOW, PROBATION, PROTECTED
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;

    Status status;
    Node prev;
    Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Integer.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, Status status) {
      this.status = status;
      this.key = key;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
    }

    /** Appends the node to the tail of the list. */
    public void appendToHead(Node head) {
      Node first = head.next;
      head.next = this;
      first.prev = this;
      prev = head;
      next = first;
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
          .add("status", status)
          .toString();
    }
  }

  static final class FeedbackWindowTinyLfuSettings extends BasicSettings {
    public FeedbackWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("feedback-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("feedback-window-tiny-lfu.percent-main-protected");
    }
    public double percentPivot() {
      return config().getDouble("feedback-window-tiny-lfu.percent-pivot");
    }
    public int pivotIncrement() {
      return config().getInt("feedback-window-tiny-lfu.pivot-increment");
    }
    public int pivotDecrement() {
      return config().getInt("feedback-window-tiny-lfu.pivot-decrement");
    }
    public int maximumWindowSize() {
      return config().getInt("feedback-window-tiny-lfu.maximum-window-size");
    }
    public int maximumSampleSize() {
      return config().getInt("feedback-window-tiny-lfu.maximum-sample-size");
    }
    public double adaptiveFpp() {
      return config().getDouble("feedback-window-tiny-lfu.adaptive-fpp");
    }
    public Config filterConfig(int sampleSize) {
      Map<String, Object> properties = ImmutableMap.of(
          "membership.fpp", adaptiveFpp(),
          "maximum-size", sampleSize);
      return ConfigFactory.parseMap(properties).withFallback(config());
    }
  }
}
