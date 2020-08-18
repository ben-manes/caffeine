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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.segment;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the window and main spaces implement
 * {@link SegmentedLruPolicy}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.TooManyFields")
public final class FullySegmentedWindowTinyLfuPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final int maximumSize;

  private final Node headWindowProbation;
  private final Node headWindowProtected;
  private final Node headMainProbation;
  private final Node headMainProtected;

  private final int maxWindow;
  private final int maxWindowProtected;
  private final int maxMainProtected;

  private int sizeWindow;
  private int sizeWindowProtected;
  private int sizeMainProtected;

  public FullySegmentedWindowTinyLfuPolicy(
      double percentMain, FullySegmentedWindowTinyLfuSettings settings) {
    this.policyStats = new PolicyStats(
        "sketch.FullySegmentedWindowTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    int maxMain = (int) (maximumSize * percentMain);
    this.maxWindow = maximumSize - maxMain;
    this.maxMainProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxWindowProtected = (int) (maxWindow * settings.percentWindowProtected());
    this.admittor = new TinyLfu(settings.config(), policyStats);
    this.data = new Long2ObjectOpenHashMap<>();
    this.headWindowProbation = new Node();
    this.headWindowProtected = new Node();
    this.headMainProbation = new Node();
    this.headMainProtected = new Node();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    FullySegmentedWindowTinyLfuSettings settings = new FullySegmentedWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new FullySegmentedWindowTinyLfuPolicy(percentMain, settings))
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

    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else if (node.status == Status.WINDOW_PROBATION) {
      onWindowProbationHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.WINDOW_PROTECTED) {
      onWindowProtectedHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.MAIN_PROBATION) {
      onMainProbationHit(node);
      policyStats.recordHit();
    } else if (node.status == Status.MAIN_PROTECTED) {
      onMainProtectedHit(node);
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key) {
    Node node = new Node(key, Status.WINDOW_PROBATION);
    node.appendToTail(headWindowProbation);
    data.put(key, node);
    sizeWindow++;
    evict();
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onWindowProbationHit(Node node) {
    node.remove();
    node.status = Status.WINDOW_PROTECTED;
    node.appendToTail(headWindowProtected);

    sizeWindowProtected++;
    if (sizeWindowProtected > maxWindowProtected) {
      Node demote = headWindowProtected.next;
      demote.remove();
      demote.status = Status.WINDOW_PROBATION;
      demote.appendToTail(headWindowProbation);
      sizeWindowProtected--;
    }
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowProtectedHit(Node node) {
    node.moveToTail(headWindowProtected);
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onMainProbationHit(Node node) {
    node.remove();
    node.status = Status.MAIN_PROTECTED;
    node.appendToTail(headMainProtected);

    sizeMainProtected++;
    if (sizeMainProtected > maxMainProtected) {
      Node demote = headMainProtected.next;
      demote.remove();
      demote.status = Status.MAIN_PROBATION;
      demote.appendToTail(headMainProbation);
      sizeMainProtected--;
    }
  }

  /** Moves the entry to the MRU position, if it falls outside of the fast-path threshold. */
  private void onMainProtectedHit(Node node) {
    node.moveToTail(headMainProtected);
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    if (sizeWindow <= maxWindow) {
      return;
    }

    Node candidate = headWindowProbation.next;

    sizeWindow--;
    candidate.remove();
    candidate.status = Status.MAIN_PROBATION;
    candidate.appendToTail(headMainProbation);

    if (data.size() > maximumSize) {
      Node victim = headMainProbation.next;
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();

      policyStats.recordEviction();
    }
  }

  @Override
  public void finished() {
    long windowProbationSize = data.values().stream()
        .filter(n -> n.status == Status.WINDOW_PROBATION).count();
    long windowProtectedSize = data.values().stream()
        .filter(n -> n.status == Status.WINDOW_PROTECTED).count();
    long mainProtectedSize = data.values().stream()
        .filter(n -> n.status == Status.MAIN_PROTECTED).count();

    checkState(sizeWindow <= maxWindow);
    checkState(windowProtectedSize == sizeWindowProtected);
    checkState(sizeWindow == windowProbationSize + sizeWindowProtected);

    checkState(mainProtectedSize == sizeMainProtected);
    checkState(data.size() <= maximumSize);
  }

  enum Status {
    WINDOW_PROBATION, WINDOW_PROTECTED,
    MAIN_PROBATION, MAIN_PROTECTED
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

  static final class FullySegmentedWindowTinyLfuSettings extends BasicSettings {
    public FullySegmentedWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("fully-segmented-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("fully-segmented-window-tiny-lfu.percent-main-protected");
    }
    public double percentWindowProtected() {
      return config().getDouble("fully-segmented-window-tiny-lfu.percent-window-protected");
    }
  }
}
