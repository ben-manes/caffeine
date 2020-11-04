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

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

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
  final long maximumSize;
  final boolean weighted;
  final Node sentinel;

  long currentSize;

  public LinkedPolicy(Config config, Set<Characteristic> characteristics,
      Admission admission, EvictionPolicy policy) {
    this.policyStats = new PolicyStats(admission.format("linked." + policy.label()));
    this.admittor = admission.from(config, policyStats);
    this.weighted = characteristics.contains(WEIGHTED);

    BasicSettings settings = new BasicSettings(config);
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.sentinel = new Node();
    this.policy = policy;
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config,
      Set<Characteristic> characteristics, EvictionPolicy policy) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new LinkedPolicy(config, characteristics, admission, policy)
    ).collect(toSet());
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent event) {
    final int weight = weighted ? event.weight() : 1;
    final long key = event.key();
    Node old = data.get(key);
    admittor.record(key);
    if (old == null) {
      policyStats.recordWeightedMiss(weight);
      if (weight > maximumSize) {
        policyStats.recordOperation();
        return;
      }
      Node node = new Node(key, weight, sentinel);
      data.put(key, node);
      currentSize += node.weight;
      node.appendToTail();
      evict(node);
    } else {
      policyStats.recordWeightedHit(weight);
      currentSize += (weight - old.weight);
      old.weight = weight;

      policy.onAccess(old, policyStats);
      evict(old);
    }
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    if (currentSize > maximumSize) {
      while (currentSize > maximumSize) {
        Node victim = policy.findVictim(sentinel, policyStats);
        policyStats.recordEviction();

        boolean admit = admittor.admit(candidate.key, victim.key);
        if (admit) {
          evictEntry(victim);
        } else {
          evictEntry(candidate);
        }
      }
    } else {
      policyStats.recordOperation();
    }
  }

  private void evictEntry(Node node) {
    currentSize -= node.weight;
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
    int weight;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, int weight, Node sentinel) {
      this.sentinel = sentinel;
      this.key = key;
      this.weight = weight;
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
          .add("weight", weight)
          .add("marked", marked)
          .toString();
    }
  }
}
