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

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admitter;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

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
@PolicySpec(name = "sketch.WindowTinyLfu", characteristics = WEIGHTED)
public final class WindowTinyLfuPolicy implements Policy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final boolean weighted;

  private final Node headWindow;
  private final Node headProbation;
  private final Node headProtected;

  private final long maxWindow;
  private final long maximumSize;
  private final long maxProtected;

  private final Admitter admitter;

  private long sizeProtected;
  private long sizeWindow;
  private long sizeData;

  @SuppressWarnings("Varifier")
  public WindowTinyLfuPolicy(double percentMain,
      Set<Characteristic> characteristics, WindowTinyLfuSettings settings) {
    long maximumMain = (long) (settings.maximumSize() * percentMain);
    long maximumWindow = settings.maximumSize() - maximumMain;
    long maximumProtected = (long) (maximumMain * settings.percentMainProtected());
    this(1.0d - percentMain, maximumWindow, maximumProtected, characteristics, settings);
  }

  @SuppressWarnings("Varifier")
  private WindowTinyLfuPolicy(double percentWindow, long maximumWindow, long maximumProtected,
      Set<Characteristic> characteristics, WindowTinyLfuSettings settings) {
    checkArgument(maximumWindow >= 0, "maximum window must not be negative: %s", maximumWindow);
    checkArgument(maximumProtected >= 0,
        "maximum protected must not be negative: %s", maximumProtected);
    checkArgument(maximumProtected <= settings.maximumSize(),
        "maximum protected exceeds maximum size: protected=%s, maximum=%s",
        maximumProtected, settings.maximumSize());
    checkArgument(maximumWindow <= (settings.maximumSize() - maximumProtected),
        "segments exceed maximum size: window=%s, protected=%s, maximum=%s",
        maximumWindow, maximumProtected, settings.maximumSize());
    this.policyStats = new PolicyStats(name() + " (%.0f%%)", 100 * percentWindow);
    this.weighted = characteristics.contains(WEIGHTED);
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.maxProtected = maximumProtected;
    this.maxWindow = maximumWindow;
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();
    this.admitter = Admission.TINYLFU.from(weighted
        ? ConfigFactory.parseMap(ImmutableMap.of("maximum-size", 0)).withFallback(settings.config())
        : settings.config(), policyStats);
  }

  /** Returns a policy whose window and protected maxima are specified independently. */
  public static WindowTinyLfuPolicy withSegmentSizes(long maximumWindow, long maximumProtected,
      Set<Characteristic> characteristics, WindowTinyLfuSettings settings) {
    double percentWindow = maximumWindow / (double) settings.maximumSize();
    return new WindowTinyLfuPolicy(
        percentWindow, maximumWindow, maximumProtected, characteristics, settings);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, Set<Characteristic> characteristics) {
    var settings = new WindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new WindowTinyLfuPolicy(percentMain, characteristics, settings))
        .collect(toUnmodifiableSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent event) {
    @Nullable Node node = data.get(event.key());
    int weight = weighted ? event.weight() : 1;
    policyStats.recordOperation();
    if (node == null) {
      onMiss(event.key(), weight);
      policyStats.recordWeightedMiss(weight);
    } else if (node.status == Status.WINDOW) {
      onWindowHit(node, weight);
      policyStats.recordWeightedHit(weight);
    } else if (node.status == Status.PROBATION) {
      onProbationHit(node, weight);
      policyStats.recordWeightedHit(weight);
    } else if (node.status == Status.PROTECTED) {
      onProtectedHit(node, weight);
      policyStats.recordWeightedHit(weight);
    } else {
      throw new IllegalStateException();
    }
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key, int weight) {
    recordAccess(key);

    var node = new Node(key, weight, Status.WINDOW);
    node.appendToTail(headWindow);
    data.put(key, node);
    sizeWindow += weight;
    sizeData += weight;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowHit(Node node, int weight) {
    recordAccess(node.key);
    updateWeight(node, weight);
    node.moveToTail(headWindow);
    evict();
  }

  /**
   * Promotes the entry to the protected region's MRU position, demoting an entry if necessary. An
   * entry heavier than the protected region's maximum stays at probation's MRU position instead.
   */
  private void onProbationHit(Node node, int weight) {
    recordAccess(node.key);
    updateWeight(node, weight);

    if (node.weight > maxProtected) {
      node.moveToTail(headProbation);
      evict();
      return;
    }

    node.remove();
    node.status = Status.PROTECTED;
    node.appendToTail(headProtected);
    sizeProtected += node.weight;

    demoteProtected();
    evict();
  }

  /** Moves the entry to the protected region's MRU position, demoting entries if it grew. */
  private void onProtectedHit(Node node, int weight) {
    recordAccess(node.key);
    updateWeight(node, weight);
    node.moveToTail(headProtected);
    demoteProtected();
    evict();
  }

  /** Records the access with the admission filter. */
  private void recordAccess(long key) {
    admitter.record(key);
  }

  /** Adjusts the weighted sizes when an access changes the entry's weight. */
  private void updateWeight(Node node, int weight) {
    long delta = (long) weight - node.weight;
    if (node.status == Status.WINDOW) {
      sizeWindow += delta;
    } else if (node.status == Status.PROTECTED) {
      sizeProtected += delta;
    }
    sizeData += delta;
    node.weight = weight;
  }

  /** Demotes the protected region's LRU entries into probation while over its maximum. */
  private void demoteProtected() {
    while (sizeProtected > maxProtected) {
      Node demote = requireNonNull(headProtected.next);
      demote.remove();
      demote.status = Status.PROBATION;
      demote.appendToTail(headProbation);
      sizeProtected -= demote.weight;
    }
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidates and the main space's victims are evaluated and evicted until
   * the cache fits within its capacity.
   */
  private void evict() {
    if (weighted && (sizeData >= (maximumSize >>> 1))) {
      // the entry count keeps moving on a weighted trace, so the sketch is retracked to it
      admitter.ensureCapacity(data.size());
    }
    evictFromMain(evictFromWindow());
  }

  /**
   * Demotes the window's LRU entries into probation while the window is over its maximum,
   * returning the first demoted candidate.
   */
  private @Nullable Node evictFromWindow() {
    @Var @Nullable Node first = null;
    while (sizeWindow > maxWindow) {
      Node candidate = requireNonNull(headWindow.next);
      candidate.remove();
      candidate.status = Status.PROBATION;
      candidate.appendToTail(headProbation);
      sizeWindow -= candidate.weight;
      if (first == null) {
        first = candidate;
      }
    }
    return first;
  }

  /**
   * Evicts while the cache exceeds its capacity, choosing between the demoted candidate and main's
   * eviction victim by their sketched frequency. When the demoted candidates are exhausted the
   * search continues from the admission window, and when the victims are exhausted it escalates
   * through the protected and window regions, so an update cannot wedge the cache over its capacity
   * when the probation region is empty.
   */
  @SuppressWarnings("CheckReturnValue")
  private void evictFromMain(@Var @Nullable Node candidate) {
    @Var boolean searchedWindow = false;
    @Var Node victimHead = headProbation;
    @Var @Nullable Node victim = first(headProbation);
    while (sizeData > maximumSize) {
      // search the admission window for additional candidates
      if ((candidate == null) && !searchedWindow) {
        candidate = first(headWindow);
        searchedWindow = true;
      }

      // try evicting from the protected and window queues
      if ((candidate == null) && (victim == null)) {
        if (victimHead == headProbation) {
          victimHead = headProtected;
          victim = first(headProtected);
          continue;
        } else if (victimHead == headProtected) {
          victimHead = headWindow;
          victim = first(headWindow);
          continue;
        }
        break;
      }

      // evict immediately if only one of the entries is present
      if (victim == null) {
        Node evict = requireNonNull(candidate);
        candidate = successor(candidate);
        evictEntry(evict);
        continue;
      } else if (candidate == null) {
        Node evict = victim;
        victim = successor(victim);
        evictEntry(evict);
        continue;
      }

      // Evict if both selected the same entry
      if (candidate == victim) {
        victim = successor(victim);
        evictEntry(candidate);
        candidate = null;
        continue;
      }

      // evict immediately if the candidate's weight exceeds the maximum
      if (candidate.weight > maximumSize) {
        Node evict = candidate;
        candidate = successor(candidate);
        evictEntry(evict);
        continue;
      }

      // evict the entry with the lowest frequency
      if (admitter.admit(candidate.key, victim.key)) {
        Node evict = victim;
        victim = successor(victim);
        evictEntry(evict);
        candidate = successor(candidate);
      } else {
        Node evict = candidate;
        candidate = successor(candidate);
        evictEntry(evict);
      }
    }
  }

  /** Removes the entry from the cache. */
  private void evictEntry(Node node) {
    if (node.status == Status.WINDOW) {
      sizeWindow -= node.weight;
    } else if (node.status == Status.PROTECTED) {
      sizeProtected -= node.weight;
    }
    sizeData -= node.weight;
    data.remove(node.key);
    node.remove();
    policyStats.recordEviction();
  }

  /** Returns the region's LRU entry, or {@code null} if it is empty. */
  private static @Nullable Node first(Node head) {
    Node next = requireNonNull(head.next);
    return (next == head) ? null : next;
  }

  /** Returns the next entry in the node's region, or {@code null} at the end. */
  private @Nullable Node successor(Node node) {
    Node head = (node.status == Status.WINDOW) ? headWindow
        : (node.status == Status.PROBATION) ? headProbation
        : headProtected;
    Node next = requireNonNull(node.next);
    return (next == head) ? null : next;
  }

  @Override
  public void finished() {
    long weightWindow = data.values().stream()
        .filter(n -> n.status == Status.WINDOW).mapToLong(n -> n.weight).sum();
    long weightProtected = data.values().stream()
        .filter(n -> n.status == Status.PROTECTED).mapToLong(n -> n.weight).sum();
    long weightData = data.values().stream().mapToLong(n -> n.weight).sum();

    checkState(weightWindow == sizeWindow);
    checkState(weightProtected == sizeProtected);
    checkState(weightData == sizeData);

    checkState(sizeData <= maximumSize);
  }

  enum Status {
    WINDOW, PROBATION, PROTECTED
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;

    int weight;
    @Nullable Node prev;
    @Nullable Node next;
    @Nullable Status status;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Integer.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, int weight, Status status) {
      this.status = status;
      this.weight = weight;
      this.key = key;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      requireNonNull(head.prev);
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      requireNonNull(prev);
      requireNonNull(next);

      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
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
