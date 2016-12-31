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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the size of the admission window is adjusted using a simple
 * hill climbing algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.TooManyFields")
public final class HillClimberWindowTinyLfuPolicy implements Policy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final int maximumSize;

  private final Node headEden;
  private final Node headProbation;
  private final Node headProtected;

  private int maxEden;
  private int maxProtected;

  private int sizeEden;
  private int sizeProtected;

  private final int pivot;

  private int sample;
  private final int sampleSize;

  private int hitsInSample;
  private int missesInSample;
  private double previousHitRate;

  private final double tolerance;
  private boolean increaseWindow;

  static final boolean debug = false;
  static final boolean trace = false;

  public HillClimberWindowTinyLfuPolicy(double percentMain,
      HillClimberWindowTinyLfuPolicySettings settings) {
    String name = String.format("sketch.HillClimberWindowTinyLfu (%.0f%%)",
        100 * (1.0d - percentMain));
    this.policyStats = new PolicyStats(name);
    this.admittor = new TinyLfu(settings.config(), policyStats);

    int maxMain = (int) (settings.maximumSize() * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxEden = settings.maximumSize() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headEden = new Node();

    this.pivot = Math.min(settings.maximumPivot(),
        Math.max(1, (int) (settings.percentPivot() * maximumSize)));
    this.tolerance = 100d * settings.tolerance();
    this.sampleSize = settings.sampleSize();

    printSegmentSizes();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    HillClimberWindowTinyLfuPolicySettings settings =
        new HillClimberWindowTinyLfuPolicySettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new HillClimberWindowTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
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
    adjustSample();

    if (node == null) {
      onMiss(key);
      missesInSample++;
      policyStats.recordMiss();
    } else if (node.status == Status.EDEN) {
      hitsInSample++;
      onEdenHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.PROBATION) {
      hitsInSample++;
      onProbationHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.PROTECTED) {
      hitsInSample++;
      onProtectedHit(node);
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  @SuppressWarnings("PMD.EmptyIfStmt")
  private void adjustSample() {
    boolean reset = false;

    if (data.size() < maximumSize) {
      reset = true;
    } else if (sample >= sampleSize) {
      reset = true;
      double hitRate = (100d * hitsInSample) / (hitsInSample + missesInSample);

      if (!Double.isNaN(hitRate) && !Double.isInfinite(hitRate) && (previousHitRate != 0.0)) {
        adapt(hitRate);
      }
      previousHitRate = hitRate;
    }

    if (reset) {
      missesInSample = 0;
      hitsInSample = 0;
      sample = 0;
    }
    sample++;
  }

  private void adapt(double hitRate) {
    if (hitRate < (previousHitRate + tolerance)) {
      increaseWindow = !increaseWindow;
    }

    if (increaseWindow) {
      increaseWindow();
    } else {
      decreaseWindow();
    }
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key) {
    Node node = new Node(key, Status.EDEN);
    node.appendToTail(headEden);
    data.put(key, node);
    sizeEden++;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onEdenHit(Node node) {
    node.moveToTail(headEden);
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

  /** Moves the entry to the MRU position, if it falls outside of the fast-path threshold. */
  private void onProtectedHit(Node node) {
    node.moveToTail(headProtected);
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    if (sizeEden <= maxEden) {
      return;
    }

    Node candidate = headEden.next;
    sizeEden--;

    candidate.remove();
    candidate.status = Status.PROBATION;
    candidate.appendToTail(headProbation);

    if (data.size() > maximumSize) {
      Node victim = headProbation.next;
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();

      policyStats.recordEviction();
    }
  }

  private void increaseWindow() {
    if (maxProtected == 0) {
      return;
    }

    int steps = Math.min(pivot, maxProtected);
    for (int i = 0; i < steps; i++) {
      maxEden++;
      sizeEden++;
      maxProtected--;

      demoteProtected();
      Node candidate = headProbation.next.next;
      candidate.remove();
      candidate.status = Status.EDEN;
      candidate.appendToTail(headEden);
    }

    if (trace) {
      System.out.printf("+%,d (%,d -> %,d)%n", steps, maxEden - steps, maxEden);
    }
  }

  private void decreaseWindow() {
    if (maxEden == 0) {
      return;
    }

    int steps = Math.min(pivot, maxEden);
    for (int i = 0; i < steps; i++) {
      if (pivot > 0) {
        maxEden--;
        sizeEden--;
        maxProtected++;

        Node candidate = headEden.next;
        candidate.remove();
        candidate.status = Status.PROBATION;
        candidate.appendToHead(headProbation);
      }
    }

    if (trace) {
      System.out.printf("-%,d (%,d -> %,d)%n", steps, maxEden + steps, maxEden);
    }
  }

  private void printSegmentSizes() {
    if (debug) {
      System.out.printf("maxEden=%d, maxProtected=%d, percentEden=%.1f, pivot=%,d%n",
          maxEden, maxProtected, (double) (100 * maxEden) / maximumSize, pivot);
    }
  }

  @Override
  public void finished() {
    printSegmentSizes();

    long edenSize = data.values().stream().filter(n -> n.status == Status.EDEN).count();
    long probationSize = data.values().stream().filter(n -> n.status == Status.PROBATION).count();
    long protectedSize = data.values().stream().filter(n -> n.status == Status.PROTECTED).count();

    checkState(edenSize == sizeEden);
    checkState(protectedSize == sizeProtected);
    checkState(probationSize == data.size() - edenSize - protectedSize);

    checkState(data.size() <= maximumSize);
  }

  enum Status {
    EDEN, PROBATION, PROTECTED
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

  static final class HillClimberWindowTinyLfuPolicySettings extends BasicSettings {
    public HillClimberWindowTinyLfuPolicySettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("hill-climber-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-main-protected");
    }
    public double percentPivot() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-pivot");
    }
    public int maximumPivot() {
      return config().getInt("hill-climber-window-tiny-lfu.maximum-pivot-size");
    }
    public int sampleSize() {
      return config().getInt("hill-climber-window-tiny-lfu.sample-size");
    }
    public double tolerance() {
      return config().getInt("hill-climber-window-tiny-lfu.tolerance");
    }
  }
}
