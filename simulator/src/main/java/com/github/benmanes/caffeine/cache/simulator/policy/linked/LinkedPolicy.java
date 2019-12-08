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
package com.github.benmanes.caffeine.cache.simulator.policy.linked;

import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.checkerframework.checker.units.qual.K;

/**
 * A cache that uses a linked list, in either insertion or access order, to implement simple
 * page replacement algorithms.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LinkedPolicy implements Policy {
  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final EvictionPolicy policy;
  final Admittor admittor;
  final int maximumSize;
  final Node sentinel;

  public LinkedPolicy(Admission admission, EvictionPolicy policy, Config config) {
    BasicSettings settings = new BasicSettings(config);
    this.policyStats = new PolicyStats(admission.format("linked." + policy.label()),settings.traceCharacteristics());
    this.admittor = admission.from(config, policyStats);
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.sentinel = new Node();
    this.policy = policy;
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, EvictionPolicy policy) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new LinkedPolicy(admission, policy, config)
    ).collect(toSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent entry) {
    long key = entry.getKey();
    Node old = data.get(key);
    admittor.record(entry);
    if (old == null) {
      Node node = new Node(entry, sentinel);
      policyStats.recordMiss(entry);
      data.put(key, node);
      node.appendToTail();
      evict(node);
    } else {
      policyStats.recordHit(entry);
      policy.onAccess(old, policyStats);
    }
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    if (data.size() > maximumSize) {
      Node victim = policy.findVictim(sentinel, policyStats);
      policyStats.recordEviction();

      boolean admit = admittor.admit(candidate.entry, victim.entry);
      if (admit) {
        evictEntry(victim);
      } else {
        evictEntry(candidate);
      }
    } else {
      policyStats.recordOperation();
    }
  }

  private void evictEntry(Node node) {
    data.remove(node.key);
    node.remove();
  }

  /** The replacement policy. */
  public enum EvictionPolicy {

    /** Evicts entries based on insertion order. */
    FIFO {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        // do nothing
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        policyStats.recordOperation();
        return sentinel.next;
      }
    },

    /**
     * Evicts entries based on insertion order, but gives an entry a "second chance" if it has been
     * requested recently.
     */
    CLOCK {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        node.marked = true;
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        for (;;) {
          policyStats.recordOperation();
          Node node = sentinel.next;
          if (node.marked) {
            node.moveToTail();
            node.marked = false;
          } else {
            return node;
          }
        }
      }
    },

    /** Evicts entries based on how recently they are used, with the most recent evicted first. */
    MRU {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        node.moveToTail();
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        policyStats.recordOperation();
        // Skip over the added entry
        return sentinel.prev.prev;
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU {
      @Override void onAccess(Node node, PolicyStats policyStats) {
        policyStats.recordOperation();
        node.moveToTail();
      }
      @Override Node findVictim(Node sentinel, PolicyStats policyStats) {
        policyStats.recordOperation();
        return sentinel.next;
      }
    };

    public String label() {
      return StringUtils.capitalize(name().toLowerCase(US));
    }

    /** Performs any operations required by the policy after a node was successfully retrieved. */
    abstract void onAccess(Node node, PolicyStats policyStats);

    /** Returns the victim entry to evict. */
    abstract Node findVictim(Node sentinel, PolicyStats policyStats);
  }

  /** A node on the double-linked list. */
  static final class Node {
    final Node sentinel;

    boolean marked;
    Node prev;
    Node next;
    long key;
    AccessEvent entry;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.entry = null;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(AccessEvent entry, Node sentinel) {
      this.sentinel = sentinel;
      this.key = entry.getKey();
      this.entry = entry;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail() {
      Node tail = sentinel.prev;
      sentinel.prev = this;
      tail.next = this;
      next = sentinel;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      prev = next = null;
      key = Long.MIN_VALUE;
    }

    /** Moves the node to the tail. */
    public void moveToTail() {
      requireNonNull(key);

      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel;
      prev = sentinel.prev;
      sentinel.prev = this;
      prev.next = this;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("marked", marked)
          .toString();
    }
  }

  @Override
  public Set<Characteristics> getCharacteristicsSet() {
    return ImmutableSet.of(Characteristics.KEY);
  }
}
