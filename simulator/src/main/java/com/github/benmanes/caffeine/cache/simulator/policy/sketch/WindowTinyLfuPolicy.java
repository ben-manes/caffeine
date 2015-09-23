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

import java.util.HashMap;
import java.util.Map;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

/**
 * An adaption of the TinyLfu policy that adds a temporal admission window. This window allows the
 * policy to have a high hit rate when entries exhibit a high temporal / low frequency pattern.
 * <p>
 * A new entry starts in the eden queue and remains there as long as it has high temporal locality.
 * Eventually an entry will slip from the end of the eden queue onto the front of the main queue. If
 * the main queue is already full, then a historic frequency filter determines whether to evict the
 * newly admitted entry or the victim entry chosen by main queue's policy. This process ensures that
 * the entries in the main queue have both a high recency and frequency. Both the eden and main
 * spaces use LRU queues.
 * <p>
 * Scan resistance is achieved by means of the eden queue. Transient data will pass through from the
 * eden queue and not be accepted into the main queue. Responsiveness is maintained by the main
 * queue's LRU and the TinyLfu's reset operation so that expired long term entries fade away.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class WindowTinyLfuPolicy implements Policy {
  private final PolicyStats policyStats;
  private final int recencyMoveDistance;
  private final Map<Object, Node> data;
  private final Admittor admittor;
  private final Node headEden;
  private final Node headMain;
  private final int maxEden;
  private final int maxMain;

  private int sizeEden;
  private int sizeMain;
  private int mainRecencyCounter;

  public WindowTinyLfuPolicy(String name, Config config) {
    WindowTinyLfuSettings settings = new WindowTinyLfuSettings(config);
    this.maxMain = (int) (settings.maximumSize() * settings.percentMain());
    this.maxEden = settings.maximumSize() - maxMain;
    this.recencyMoveDistance = (int) (maxMain * settings.percentFastPath());
    this.policyStats = new PolicyStats(name);
    this.admittor = new TinyLfu(config);
    this.data = new HashMap<>();
    this.headEden = new Node();
    this.headMain = new Node();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(Comparable<Object> key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      admittor.record(key);

      node = new Node(key, Status.EDEN);
      node.appendToTail(headEden);
      data.put(key, node);
      sizeEden++;
      evict();

      policyStats.recordMiss();
    } else if (node.status == Status.EDEN) {
      admittor.record(key);
      node.moveToTail(headEden);
      policyStats.recordHit();
    } else if (node.status == Status.MAIN) {
      // Fast path skips the hottest entries, useful for concurrent caches
      if (node.recencyMove <= (mainRecencyCounter - recencyMoveDistance)) {
        node.moveToTail(headMain);
        admittor.record(key);
      }
      policyStats.recordHit();
    } else {
      throw new IllegalStateException();
    }
  }

  /** Evicts if the map exceeds the maximum capacity. */
  private void evict() {
    if (sizeEden <= maxEden) {
      return;
    }

    Node candidate = headEden.next;
    candidate.remove();
    sizeEden--;

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

    if (candidate.isInQueue()) {
      candidate.recencyMove = ++mainRecencyCounter;
    }
  }

  @Override
  public void finished() {
    checkState(data.values().stream().filter(n -> n.status == Status.EDEN).count() == sizeEden);
    checkState(sizeEden + sizeMain == data.size());
    checkState(data.size() <= maxEden + maxMain);
  }

  enum Status {
    EDEN, MAIN
  }

  /** A node on the double-linked list. */
  static final class Node {
    final Object key;

    int recencyMove;
    Status status;
    Node prev;
    Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.prev = this;
      this.next = this;
      this.key = null;
    }

    /** Creates a new, unlinked node. */
    public Node(Object key, Status status) {
      this.status = status;
      this.key = key;
    }

    public boolean isInQueue() {
      return next != null;
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

  static final class WindowTinyLfuSettings extends BasicSettings {
    public WindowTinyLfuSettings(Config config) {
      super(config);
    }
    public double percentMain() {
      return config().getDouble("window-tiny-lfu.percent-main");
    }
    public double percentFastPath() {
      return config().getDouble("window-tiny-lfu.percent-fast-path");
    }
  }
}
