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
package com.github.benmanes.caffeine.cache.simulator.policy.victim;

import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

/**
 * A cache that uses an LRU primary cache for temporal locality, and a FIFO+TinyLfu victim cache for
 * longer term storage. The primary cache evicts to the least recently used to the victim, which may
 * reject the candidate based on an estimated usage frequency. A miss in the primary cache is a hit
 * when it can be resurrected from the victim cache. An entry is removed when the victim cache
 * evicts.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class VictimLruPolicy implements Policy {
  private final Map<Object, Node> victimData;
  private final PolicyStats policyStats;
  private final Admittor admittor;
  private final LruMap mainData;
  private final Node sentinel;
  private final int maxVictim;
  private final int maxMain;

  public VictimLruPolicy(String name, Config config) {
    VictimSettings settings = new VictimSettings(config);
    this.maxVictim = (int) (settings.maximumSize() * settings.percentVictim());
    this.maxMain = settings.maximumSize() - maxVictim;
    this.policyStats = new PolicyStats(name);
    this.admittor = new TinyLfu(config);
    this.victimData = new HashMap<>();
    this.mainData = new LruMap();
    this.sentinel = new Node();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(Comparable<Object> key) {
    Node node = mainData.get(key);
    admittor.record(key);
    if (node == null) {
      node = victimData.remove(key);
      if (node == null) {
        node = new Node(key, sentinel);
        mainData.put(key, node);
        policyStats.recordMiss();
      } else {
        node.remove();
        mainData.put(key, node);
        policyStats.recordHit();
      }
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public void finished() {
    checkState(mainData.size() <= maxMain);
    checkState(victimData.size() <= maxVictim);
  }

  /** A node on the double-linked list. */
  static final class Node {
    private final Node sentinel;
    private final Object key;

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
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .toString();
    }
  }

  /** An LRU cache that evicts to the victim cache. */
  final class LruMap extends LinkedHashMap<Object, Node> {
    private static final long serialVersionUID = 1L;

    LruMap() {
      super(maxMain, 0.75f, true);
    }

    @Override protected boolean removeEldestEntry(Map.Entry<Object, Node> eldest) {
      if (size() > maxMain) {
        victimData.put(eldest.getKey(), eldest.getValue());
        eldest.getValue().appendToTail();
        evict(eldest.getValue());
        return true;
      }
      return false;
    }

    /** Evicts while the victim map exceeds the maximum capacity. */
    private void evict(Node candidate) {
      if (victimData.size() > maxVictim) {
        Node victim = sentinel.next;
        if (victim == sentinel) {
          throw new IllegalStateException();
        } else {
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
      victimData.remove(node.key);
      node.remove();
    }
  }

  static final class VictimSettings extends BasicSettings {
    public VictimSettings(Config config) {
      super(config);
    }
    public double percentVictim() {
      return config().getDouble("victim-lru.percent-victim");
    }
  }
}
