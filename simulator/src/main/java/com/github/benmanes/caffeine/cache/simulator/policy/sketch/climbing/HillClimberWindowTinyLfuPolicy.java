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

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.DECREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.INCREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.PROBATION;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.PROTECTED;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.WINDOW;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the size of the admission window is adjusted using the a hill
 * climbing algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.TooManyFields")
public final class HillClimberWindowTinyLfuPolicy implements Policy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final HillClimber climber;
  private final Admittor admittor;
  private final int maximumSize;

  private final Node headWindow;
  private final Node headProbation;
  private final Node headProtected;

  private int maxWindow;
  private int maxProtected;

  private int sizeWindow;
  private int sizeProtected;

  static final boolean debug = false;
  static final boolean trace = false;

  public HillClimberWindowTinyLfuPolicy(HillClimberType strategy, double percentMain,
      HillClimberWindowTinyLfuSettings settings) {
    String name = String.format("sketch.HillClimberWindowTinyLfu (%s %.0f%%)",
        strategy.name().toLowerCase(US), 100 * (1.0 - percentMain));

    this.policyStats = new PolicyStats(name);
    this.admittor = new TinyLfu(settings.config(), policyStats);
    this.climber = strategy.create(settings.config());

    int maxMain = (int) (settings.maximumSize() * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxWindow = settings.maximumSize() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();

    printSegmentSizes();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
    Set<Policy> policies = new HashSet<>();
    for (HillClimberType climber : settings.strategy()) {
      for (double percentMain : settings.percentMain()) {
        policies.add(new HillClimberWindowTinyLfuPolicy(climber, percentMain, settings));
      }
    }
    return policies;
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

    QueueType queue = null;
    if (node == null) {
      onMiss(key);
      policyStats.recordMiss();
    } else {
      queue = node.queue;
      if (queue == WINDOW) {
        onWindowHit(node);
        policyStats.recordHit();
      } else if (queue == PROBATION) {
        onProbationHit(node);
        policyStats.recordHit();
      } else if (queue == PROTECTED) {
        onProtectedHit(node);
        policyStats.recordHit();
      } else {
        throw new IllegalStateException();
      }
    }
    climb(key, queue);
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key) {
    Node node = new Node(key, WINDOW);
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
    node.queue = PROTECTED;
    node.appendToTail(headProtected);

    sizeProtected++;
    demoteProtected();
  }

  private void demoteProtected() {
    if (sizeProtected > maxProtected) {
      Node demote = headProtected.next;
      demote.remove();
      demote.queue = PROBATION;
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
    if (sizeWindow <= maxWindow) {
      return;
    }

    Node candidate = headWindow.next;
    sizeWindow--;

    candidate.remove();
    candidate.queue = PROBATION;
    candidate.appendToTail(headProbation);

    if (data.size() > maximumSize) {
      Node victim = headProbation.next;
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      data.remove(evict.key);
      evict.remove();

      policyStats.recordEviction();
    }
  }

  /** Performs the hill climbing process. */
  private void climb(long key, @Nullable QueueType queue) {
    if (data.size() < maximumSize) {
      return;
    } else if (queue == null) {
      climber.onMiss(key);
    } else {
      climber.onHit(key, queue);
    }

    Adaptation adaptation = climber.adapt(sizeWindow, sizeProtected);
    if (adaptation.type == INCREASE_WINDOW) {
      increaseWindow(adaptation.amount);
    } else if (adaptation.type == DECREASE_WINDOW) {
      decreaseWindow(adaptation.amount);
    }
  }

  private void increaseWindow(int amount) {
    if (maxProtected == 0) {
      return;
    }

    int steps = Math.min(amount, maxProtected);
    for (int i = 0; i < steps; i++) {
      maxWindow++;
      sizeWindow++;
      maxProtected--;

      demoteProtected();
      Node candidate = headProbation.next;
      candidate.remove();
      candidate.queue = WINDOW;
      candidate.appendToTail(headWindow);
    }

    if (trace) {
      System.out.printf("+%,d (%,d -> %,d)%n", steps, maxWindow - steps, maxWindow);
    }
  }

  private void decreaseWindow(int amount) {
    if (maxWindow == 0) {
      return;
    }

    int steps = Math.min(amount, maxWindow);
    for (int i = 0; i < steps; i++) {
      if (amount > 0) {
        maxWindow--;
        sizeWindow--;
        maxProtected++;

        Node candidate = headWindow.next;
        candidate.remove();
        candidate.queue = PROBATION;
        candidate.appendToHead(headProbation);
      }
    }

    if (trace) {
      System.out.printf("-%,d (%,d -> %,d)%n", steps, maxWindow + steps, maxWindow);
    }
  }

  private void printSegmentSizes() {
    if (debug) {
      System.out.printf("maxWindow=%d, maxProtected=%d, percentWindow=%.1f",
          maxWindow, maxProtected, (100.0 * maxWindow) / maximumSize);
    }
  }

  @Override
  public void finished() {
    printSegmentSizes();

    long windowSize = data.values().stream().filter(n -> n.queue == WINDOW).count();
    long probationSize = data.values().stream().filter(n -> n.queue == PROBATION).count();
    long protectedSize = data.values().stream().filter(n -> n.queue == PROTECTED).count();

    checkState(windowSize == sizeWindow);
    checkState(protectedSize == sizeProtected);
    checkState(probationSize == data.size() - windowSize - protectedSize);

    checkState(data.size() <= maximumSize);
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;

    QueueType queue;
    Node prev;
    Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Integer.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, QueueType queue) {
      this.queue = queue;
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
          .add("queue", queue)
          .toString();
    }
  }

  static final class HillClimberWindowTinyLfuSettings extends BasicSettings {
    public HillClimberWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("hill-climber-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-main-protected");
    }
    public Set<HillClimberType> strategy() {
      return config().getStringList("hill-climber-window-tiny-lfu.strategy").stream()
          .map(strategy -> strategy.replace('-', '_').toUpperCase(US))
          .map(HillClimberType::valueOf)
          .collect(toSet());
    }
  }
}
