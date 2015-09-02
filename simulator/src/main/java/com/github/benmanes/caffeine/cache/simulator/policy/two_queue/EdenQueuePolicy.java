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
package com.github.benmanes.caffeine.cache.simulator.policy.two_queue;

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
 * An adaption of the 2Q algorithm based on the TinyLfu policy. Unlike the original 2Q algorithm,
 * non-resident entries are not retained by instead relying on the TinyLfu's history.
 * <p>
 * A new entry starts in the eden queue and remain there as long as it has high temporal locality.
 * Eventually an entry will slip from the end of the eden queue onto the front of the main queue. If
 * the main queue is already full, then TinyLfu determines whether to evict the newly admitted entry
 * or the victim entry chosen by main queue's policy. The eden queue allows an entry that has a high
 * temporal locality and a low frequency of use to reside the cache until it has poor recency, at
 * which point it is discarded by TinyLfu. Both the eden and main spaces use LRU queues.
 * <p>
 * Scan resistance is achieved by means of the eden queue. Transient data will pass through from the
 * eden queue and not be accepted into the main queue. Responsiveness is maintained by the main
 * queue's LRU and the TinyLfu's reset operation so that expired long term entries fade away.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EdenQueuePolicy implements Policy {
  private final PolicyStats policyStats;
  private final Map<Object, Node> data;
  private final Admittor admittor;
  private final Node headEden;
  private final Node headMain;
  private final int maxEden;
  private final int maxMain;

  private int sizeEden;
  private int sizeMain;

  public EdenQueuePolicy(String name, Config config) {
    EdenQueueSettings settings = new EdenQueueSettings(config);
    this.maxEden = (int) (settings.maximumSize() * settings.percentEden());
    this.maxMain = settings.maximumSize() - maxEden;
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
    Node node = data.get(key);
    admittor.record(key);
    if (node == null) {
      node = new Node(key, Status.EDEN);
      node.appendToTail(headEden);
      data.put(key, node);
      sizeEden++;
      evict();

      policyStats.recordMiss();
    } else if (node.status == Status.EDEN) {
      node.moveToTail(headEden);
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
    private final Object key;

    private Status status;
    private Node prev;
    private Node next;

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

  static final class EdenQueueSettings extends BasicSettings {
    public EdenQueueSettings(Config config) {
      super(config);
    }
    public double percentEden() {
      return config().getDouble("eden-queue.percent-eden");
    }
  }
}
