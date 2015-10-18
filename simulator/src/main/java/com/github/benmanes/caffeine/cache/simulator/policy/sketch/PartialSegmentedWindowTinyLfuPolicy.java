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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import static com.google.common.base.Preconditions.checkState;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the main space implements {@link SegmentedLruPolicy}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PartialSegmentedWindowTinyLfuPolicy implements Policy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final int recencyMoveDistance;
  private final Admittor admittor;
  private final int maximumSize;

  private final Node headEden;
  private final Node headProbation;
  private final Node headProtected;

  private final int maxEden;
  private final int maxProtected;

  private int sizeEden;
  private int sizeProtected;
  private int mainRecencyCounter;

  public PartialSegmentedWindowTinyLfuPolicy(String name, Config config) {
    PartialSegmentedWindowTinyLfuSettings settings = new PartialSegmentedWindowTinyLfuSettings(config);
    int maxMain = (int) (settings.maximumSize() * settings.percentMain());
    this.recencyMoveDistance = (int) (maxMain * settings.percentFastPath());
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxEden = settings.maximumSize() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.policyStats = new PolicyStats(name);
    this.admittor = new TinyLfu(config);
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headEden = new Node();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else if (node.status == Status.EDEN) {
      onEdenHit(node);
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
    admittor.record(key);

    Node node = new Node(key, Status.EDEN);
    node.appendToTail(headEden);
    data.put(key, node);
    sizeEden++;
    evict();
  }

  /** Moves the entry to the LRU position in the admission window. */
  private void onEdenHit(Node node) {
    admittor.record(node.key);
    node.moveToTail(headEden);
  }

  /** Promotes the entry to the protected region's LRU position, demoting an entry if necessary. */
  private void onProbationHit(Node node) {
    admittor.record(node.key);

    node.remove();
    node.status = Status.PROTECTED;
    node.appendToTail(headProtected);
    node.recencyMove = ++mainRecencyCounter;

    sizeProtected++;
    if (sizeProtected > maxProtected) {
      Node demote = headProtected.next;
      demote.remove();
      demote.status = Status.PROBATION;
      demote.appendToTail(headProbation);
      sizeProtected--;
    }
  }

  /** Moves the entry to the LRU position, if it falls outside of the fast-path threshold. */
  private void onProtectedHit(Node node) {
    // Fast path skips the hottest entries, useful for concurrent caches
    if (node.recencyMove <= (mainRecencyCounter - recencyMoveDistance)) {
      admittor.record(node.key);
      node.moveToTail(headProtected);
      node.recencyMove = ++mainRecencyCounter;
    }
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

  @Override
  public void finished() {
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

    int recencyMove;
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
          .add("move", recencyMove)
          .toString();
    }
  }

  static final class PartialSegmentedWindowTinyLfuSettings extends BasicSettings {
    public PartialSegmentedWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public double percentMain() {
      return config().getDouble("partial-segmented-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("partial-segmented-window-tiny-lfu.percent-main-protected");
    }
    public double percentFastPath() {
      return config().getDouble("partial-segmented-window-tiny-lfu.percent-fast-path");
    }
  }
}
