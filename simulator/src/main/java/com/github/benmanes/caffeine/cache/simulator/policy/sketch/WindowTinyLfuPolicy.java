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
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * An adaption of the TinyLfu policy that adds a temporal admission window. This window allows the
 * policy to have a high hit rate when entries exhibit a high temporal / low frequency pattern.
 * <p>
 * A new entry starts in the window and remains there as long as it has high temporal locality.
 * Eventually an entry will slip from the end of the window onto the front of the main queue. If the
 * main queue is already full, then a historic frequency filter determines whether to evict the
 * newly admitted entry or the victim entry chosen by main queue's policy. This process ensures that
 * the entries in the main queue have both a high recency and frequency. The window space uses LRU
 * and the main uses Segmented LRU.
 * <p>
 * Scan resistance is achieved by means of the window. Transient data will pass through from the
 * window and not be accepted into the main queue. Responsiveness is maintained by the main queue's
 * LRU and the TinyLfu's reset operation so that expired long term entries fade away.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "sketch.WindowTinyLfu")
public final class WindowTinyLfuPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final int maximumSize;

  private final Node headWindow;
  private final Node headProbation;
  private final Node headProtected;

  private final int maxWindow;
  private final int maxProtected;

  private int sizeWindow;
  private int sizeProtected;

  public WindowTinyLfuPolicy(double percentMain, WindowTinyLfuSettings settings) {
    this.policyStats = new PolicyStats(name() + " (%.0f%%)", 100 * (1.0d - percentMain));
    this.admittor = new TinyLfu(settings.config(), policyStats);
    this.maximumSize = Ints.checkedCast(settings.maximumSize());

    int maxMain = (int) (maximumSize * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.data = new Long2ObjectOpenHashMap<>();
    this.maxWindow = maximumSize - maxMain;
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    WindowTinyLfuSettings settings = new WindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new WindowTinyLfuPolicy(percentMain, settings))
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
    admittor.record(key);

    Node node = new Node(key, Status.WINDOW);
    node.appendToTail(headWindow);
    data.put(key, node);
    sizeWindow++;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowHit(Node node) {
    admittor.record(node.key);
    node.moveToTail(headWindow);
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onProbationHit(Node node) {
    admittor.record(node.key);

    node.remove();
    node.status = Status.PROTECTED;
    node.appendToTail(headProtected);

    sizeProtected++;
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
      Node victim = headProbation.next;
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();

      policyStats.recordEviction();
    }
  }

  @Override
  public void finished() {
    long windowSize = data.values().stream().filter(n -> n.status == Status.WINDOW).count();
    long probationSize = data.values().stream().filter(n -> n.status == Status.PROBATION).count();
    long protectedSize = data.values().stream().filter(n -> n.status == Status.PROTECTED).count();

    checkState(windowSize == sizeWindow);
    checkState(protectedSize == sizeProtected);
    checkState(probationSize == data.size() - windowSize - protectedSize);

    checkState(data.size() <= maximumSize);
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

  public static final class WindowTinyLfuSettings extends BasicSettings {
    public WindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("window-tiny-lfu.percent-main-protected");
    }
  }
}
