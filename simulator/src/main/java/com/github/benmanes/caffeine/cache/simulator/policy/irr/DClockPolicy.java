/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.irr;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
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
 * Double Clock algorithm. This algorithm organizes blocks by their reuse distance and partitions
 * the cache into an active and inactive space. A new arrival enters the inactive space and on a
 * subsequent hit is promoted to the active space, which may cause the oldest active entry to be
 * demoted to be inactive. An eviction discards the oldest inactive entry which becomes a
 * shadow (non-resident) node and is stamped with its refault distance. If on a cache miss the
 * shadow entry exists and has a refault distance that is less than the active space's size, then it
 * is optimistically promoted on insert.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://github.com/torvalds/linux/blob/ac3a0c8472969a03c0496ae774b3a29eb26c8d5a/mm/workingset.c">
 * linux/mm/workingset.c</a>. The target size of the active and inactive list is calculated in
 * <a href="https://github.com/torvalds/linux/blob/1590a2e1c681b0991bd42c992cabfd380e0338f2/mm/vmscan.c#L2176-L2204">
 * linux/mm/vmscan.c</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "irr.DClock")
public final class DClockPolicy implements KeyOnlyPolicy {
  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final Node headNonResident;
  final Node headInactive;
  final Node headActive;
  final int maximumSize;
  final int maxActive;

  int inactiveSize;
  int activeSize;

  long activations;
  long evictions;

  public DClockPolicy(DClockSettings settings, double percentActive) {
    this.policyStats = new PolicyStats(name() + " (active: %d%%)", (int) (100 * percentActive));
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.maxActive = (int) (percentActive * maximumSize);
    this.data = new Long2ObjectOpenHashMap<>();
    this.headNonResident = new Node();
    this.headInactive = new Node();
    this.headActive = new Node();

    checkArgument(maxActive != maximumSize, "Must allocate some space for the inactive region");
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    DClockSettings settings = new DClockSettings(config);
    return settings.percentActive().stream()
        .map(percentActive -> new DClockPolicy(settings, percentActive))
        .collect(toSet());
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(key);
    } else if (node.status == Status.NON_RESIDENT) {
      onNonResidentHit(node);
    } else if (node.status == Status.INACTIVE) {
      onInactiveHit(node);
    } else if (node.status == Status.ACTIVE) {
      onActiveHit(node);
    } else {
      throw new IllegalStateException();
    }
  }

  private void onMiss(long key) {
    // When a page is accessed for the first time, it is added to the head of the inactive list,
    // slides every existing inactive page towards the tail by one slot, and pushes the current
    // tail page out of memory.
    Node node = new Node(key, Status.INACTIVE);
    node.appendToHead(headInactive);
    policyStats.recordMiss();
    data.put(key, node);
    inactiveSize++;
    evict();
  }

  private void onInactiveHit(Node node) {
    // When a page is accessed for the second time, it is promoted to the active list, shrinking the
    // inactive list by one slot.  This also slides all inactive pages that were faulted into the
    // cache more recently than the activated page towards the tail of the inactive list.
    policyStats.recordHit();
    activate(node);
  }

  private void activate(Node node) {
    activeSize++;
    if (activeSize > maxActive) {
      Node demote = headActive.next;
      inactiveSize++;
      demote.remove();
      demote.status = Status.INACTIVE;
      demote.appendToHead(headInactive);
      activeSize--;
    }

    if (node.status == Status.INACTIVE) {
      inactiveSize--;
    } else {
      checkState(node.status == Status.NON_RESIDENT);
    }
    node.remove();
    node.status = Status.ACTIVE;
    node.appendToHead(headActive);

    activations++;
  }

  private void onActiveHit(Node node) {
    node.moveToHead(headActive);
    policyStats.recordHit();
  }

  private void onNonResidentHit(Node node) {
    // So when a refault distance of (R - E) is observed and there are at least (R - E) active
    // pages, the refaulting page is activated optimistically in the hope that (R - E) active pages
    // are actually used less frequently than the refaulting page - or even not used at all anymore.
    if (refaultDistance(node) <= activeSize) {
      activate(node);
    } else {
      node.remove();
      inactiveSize++;
      node.status = Status.INACTIVE;
      node.moveToHead(headInactive);
    }

    policyStats.recordMiss();
    evict();
  }

  private void evict() {
    // When a page is finally evicted from memory, the number of inactive pages accessed while the
    // page was in cache is at least the number of page slots on the inactive list.
    int residentSize = (inactiveSize + activeSize);

    if (residentSize > maximumSize) {
      Node victim = headInactive.prev;
      policyStats.recordEviction();

      evictions++;
      inactiveSize--;
      victim.remove();
      victim.status = Status.NON_RESIDENT;
      victim.appendToHead(headNonResident);
      victim.nonResidentAge = currentNonResidentAge();
    }
    prune();
  }

  private void prune() {
    // Approximate a reasonable limit for the nodes containing shadow entries. We don't need to keep
    // more shadow entries than possible pages on the active list, since refault distances bigger
    // than that are dismissed.
    int nonResidentSize = data.size() - maximumSize;
    if (nonResidentSize > maxActive) {
      Node node = headNonResident.prev;
      data.remove(node.key);
      node.remove();
    }
  }

  private long refaultDistance(Node node) {
    // In addition, measuring the sum of evictions and activations (E) at the time of a page's
    // eviction, and comparing it to another reading (R) at the time the page faults back into
    // memory tells the minimum number of accesses while the page was not cached. This is called
    // the refault distance.
    return Math.abs(currentNonResidentAge() - node.nonResidentAge);
  }

  private long currentNonResidentAge() {
    // The sum of evictions and activations between any two points in time indicate the minimum
    // number of inactive pages accessed in between.
    return evictions + activations;
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    int active = (int) data.values().stream()
        .filter(node -> node.status == Status.ACTIVE)
        .count();
    int inactive = (int) data.values().stream()
        .filter(node -> node.status == Status.INACTIVE)
        .count();
    int nonResident = (int) data.values().stream()
        .filter(node -> node.status == Status.NON_RESIDENT)
        .count();

    checkState(active == activeSize,
        "Active: expected %s but was %s", activeSize, active);
    checkState(inactive == inactiveSize,
        "Inactive: expected %s but was %s", inactiveSize, inactive);
    checkState(nonResident <= maxActive,
        "NonResident: expected %s less than %s", nonResident, maxActive);
    checkState(data.size() <= (maximumSize + maxActive));
    checkState((inactive + active) <= maximumSize);
  }

  enum Status {
    ACTIVE,
    INACTIVE,
    NON_RESIDENT,
  }

  static final class Node {
    final long key;

    long nonResidentAge;
    Status status;
    Node prev;
    Node next;

    public Node() {
      this.key = Integer.MIN_VALUE;
      prev = next = this;
    }

    public Node(long key, Status status) {
      this.status = status;
      this.key = key;
    }

    /** Appends the node to the head of the list. */
    public void appendToHead(Node head) {
      Node first = head.next;
      first.prev = this;
      head.next = this;
      next = first;
      prev = head;
    }

    /** Moves the node to the head. */
    public void moveToHead(Node head) {
      // unlink
      if (prev != null) {
        prev.next = next;
        next.prev = prev;
      }

      // link
      prev = head;
      next = head.next;
      prev.next = this;
      next.prev = this;
    }

    /** Removes the node from the list. */
    public void remove() {
      checkState(key != Long.MIN_VALUE);

      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("status", status)
          .toString();
    }
  }

  static final class DClockSettings extends BasicSettings {
    public DClockSettings(Config config) {
      super(config);
    }
    public List<Double> percentActive() {
      return config().getDoubleList("dclock.percent-active");
    }
  }
}
