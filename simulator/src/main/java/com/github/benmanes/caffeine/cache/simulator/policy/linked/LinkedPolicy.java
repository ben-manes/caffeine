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
    while (data.size() > maximumSize) {
      Node victim = sentinel.next;
      if (victim == sentinel) {
        throw new IllegalStateException();
      } else if (policy.onEvict(victim)) {
        policyStats.recordEviction();

        boolean admit = admittor.admit(candidate.key, victim.key);
        if (admit) {
          evictEntry(victim);
        } else {
          evictEntry(candidate);
        }
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
      @Override boolean onEvict(Node node) {
        return true;
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
      @Override boolean onEvict(Node node) {
        if (node.marked) {
          node.moveToTail();
          node.marked = false;
          return false;
        }
        return true;
      }
    },

    /** Evicts entries based on how recently they are used, with the most recent evicted first. */
    MRU {
      @Override void onAccess(Node node) {
        node.moveToHead();
      }
      @Override boolean onEvict(Node node) {
        return true;
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU {
      @Override void onAccess(Node node) {
        node.moveToTail();
      }
      @Override boolean onEvict(Node node) {
        return true;
      }
    };

    /** Performs any operations required by the policy after a node was successfully retrieved. */
    abstract void onAccess(Node node);

    /** Determines whether to evict the node at the head of the list. */
    abstract boolean onEvict(Node node);
  }

  /** A node on the double-linked list. */
  static final class Node {
    private static final Node UNLINKED = new Node();

    private final Node sentinel;
    private final Object key;

    private boolean marked;
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
      this.next = UNLINKED;
      this.prev = UNLINKED;
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
      next = UNLINKED; // mark as unlinked
    }

    /** Moves the node to the head. */
    public void moveToHead() {
      if (isHead() || isUnlinked()) {
        return;
      }
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel.next; // ordered for isHead()
      prev = sentinel;
      sentinel.next = this;
      next.prev = this;
    }

    /** Moves the node to the tail. */
    public void moveToTail() {
      if (isTail() || isUnlinked()) {
        return;
      }
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel; // ordered for isTail()
      prev = sentinel.prev;
      sentinel.prev = this;
      prev.next = this;
    }

    /** Checks whether the node is linked on the list chain. */
    public boolean isUnlinked() {
      return (next == UNLINKED);
    }

    /** Checks whether the node is the first linked on the list chain. */
    public boolean isHead() {
      return (prev == sentinel);
    }

    /** Checks whether the node is the last linked on the list chain. */
    public boolean isTail() {
      return (next == sentinel);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key'", key)
          .add("marked", marked)
          .toString();
    }
  }
}
