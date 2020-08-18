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
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the window and main spaces implement LRU. This simpler version
 * comes at the cost of not capturing recency as effectively as Segmented LRU does.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LruWindowTinyLfuPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final Node headWindow;
  private final Node headMain;
  private final int maxWindow;
  private final int maxMain;

  private int sizeWindow;
  private int sizeMain;

  public LruWindowTinyLfuPolicy(double percentMain, LruWindowTinyLfuSettings settings) {
    this.policyStats = new PolicyStats(
        "sketch.LruWindowTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    int maximumSize = Ints.checkedCast(settings.maximumSize());
    this.admittor = new TinyLfu(settings.config(), policyStats);
    this.maxMain = (int) (maximumSize * percentMain);
    this.data = new Long2ObjectOpenHashMap<>();
    this.maxWindow = maximumSize - maxMain;
    this.headWindow = new Node();
    this.headMain = new Node();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    LruWindowTinyLfuSettings settings = new LruWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new LruWindowTinyLfuPolicy(percentMain, settings))
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
      node = new Node(key, Status.WINDOW);
      node.appendToTail(headWindow);
      data.put(key, node);
      sizeWindow++;
      evict();

      policyStats.recordMiss();
    } else if (node.status == Status.WINDOW) {
      node.moveToTail(headWindow);
      policyStats.recordHit();
    } else if (node.status == Status.MAIN) {
      node.moveToTail(headMain);
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  /** Evicts if the map exceeds the maximum capacity. */
  private void evict() {
    if (sizeWindow <= maxWindow) {
      return;
    }

    Node candidate = headWindow.next;
    candidate.remove();
    sizeWindow--;

    candidate.appendToTail(headMain);
    candidate.status = Status.MAIN;
    sizeMain++;

    if (sizeMain > maxMain) {
      Node victim = headMain.next;
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();
      sizeMain--;

      policyStats.recordEviction();
    }
  }

  @Override
  public void finished() {
    checkState(data.values().stream().filter(n -> n.status == Status.WINDOW).count() == sizeWindow);
    checkState(sizeWindow + sizeMain == data.size());
    checkState(data.size() <= maxWindow + maxMain);
  }

  enum Status {
    WINDOW, MAIN
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
          .toString();
    }
  }

  static final class LruWindowTinyLfuSettings extends BasicSettings {
    public LruWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("lru-window-tiny-lfu.percent-main");
    }
  }
}
