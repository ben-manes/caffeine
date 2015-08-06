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

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

/**
 * A cache that uses a linked list, in either insertion or access order, to implement simple
 * page replacement algorithms.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LinkedPolicy implements Policy {
  private final PolicyStats policyStats;
  private final Map<Object, Node> data;
  private final EvictionPolicy policy;
  private final Admittor admittor;
  private final int maximumSize;
  private final Node sentinel;

  public LinkedPolicy(String name, Admittor admittor, Config config, EvictionPolicy policy) {
    BasicSettings settings = new BasicSettings(config);
    this.maximumSize = settings.maximumSize();
    this.policyStats = new PolicyStats(name);
    this.data = new HashMap<>();
    this.sentinel = new Node();
    this.admittor = admittor;
    this.policy = policy;
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(Comparable<Object> key) {
    Node old = data.get(key);
    admittor.record(key);
    if (old == null) {
      Node node = new Node(key, sentinel);
      policyStats.recordMiss();
      data.put(key, node);
      node.appendToTail();
      evict(node);
    } else {
      policyStats.recordHit();
      policy.onAccess(old);
    }
  }

  /** Evicts while the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    if (data.size() > maximumSize) {
      Node victim = policy.findVictim(sentinel);
      policyStats.recordEviction();

      boolean admit = admittor.admit(candidate.key, victim.key);
      if (admit) {
        evictEntry(victim);
      } else {
        evictEntry(candidate);
      }
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
      @Override void onAccess(Node node) {
        // do nothing
      }
      @Override Node findVictim(Node setinel) {
        return setinel.next;
      }
    },

    /**
     * Evicts entries based on insertion order, but gives an entry a "second chance" if it has been
     * requested recently.
     */
    CLOCK {
      @Override void onAccess(Node node) {
        node.marked = true;
      }
      @Override Node findVictim(Node setinel) {
        for (;;) {
          Node node = setinel.next;
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
      @Override void onAccess(Node node) {
        node.moveToTail();
      }
      @Override Node findVictim(Node setinel) {
        // Skip over the added entry
        return setinel.prev.prev;
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU {
      @Override void onAccess(Node node) {
        node.moveToTail();
      }
      @Override Node findVictim(Node setinel) {
        return setinel.next;
      }
    };

    /** Performs any operations required by the policy after a node was successfully retrieved. */
    abstract void onAccess(Node node);

    /** Returns the victim entry to evict. */
    abstract Node findVictim(Node sentinel);
  }

  /** A node on the double-linked list. */
  static final class Node {
    private final Node sentinel;

    private boolean marked;
    private Object key;
    private Node prev;
    private Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.sentinel = this;
      this.prev = this;
      this.next = this;
      this.key = null;
    }

    /** Creates a new, unlinked node. */
    public Node(Object key, Node sentinel) {
      this.sentinel = sentinel;
      this.key = key;
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
      key = null;
    }

    /** Moves the node to the head. */
    public void moveToHead() {
      requireNonNull(key);

      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel.next;
      prev = sentinel;
      sentinel.next = this;
      next.prev = this;
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
}
