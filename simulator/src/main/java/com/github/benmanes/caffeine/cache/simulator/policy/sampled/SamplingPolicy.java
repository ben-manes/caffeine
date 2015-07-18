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
package com.github.benmanes.caffeine.cache.simulator.policy.sampled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

/**
 * A cache that uses a sampled array of entries to implement simple page replacement algorithms.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SamplingPolicy implements Policy {
  private final PolicyStats policyStats;
  private final Map<Object, Node> data;
  private final EvictionPolicy policy;
  private final Sample sampleStrategy;
  private final Deque<Integer> free;
  private final Admittor admittor;
  private final int sampleSize;
  private final Node[] table;

  public SamplingPolicy(String name, Admittor admittor, Config config, EvictionPolicy policy) {
    SamplingSettings settings = new SamplingSettings(config);
    this.sampleStrategy = settings.sampleStrategy();
    this.policyStats = new PolicyStats(name);
    this.sampleSize = settings.sampleSize();
    this.data = new HashMap<>();
    this.admittor = admittor;
    this.policy = policy;

    int overflowSize = settings.maximumSize() + 1;
    this.free = new ArrayDeque<Integer>(overflowSize);
    this.table = new Node[overflowSize];
    for (int i = 0; i < overflowSize; i++) {
      free.add(i);
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(Object key) {
    Node node = data.get(key);
    admittor.record(key);
    if (node == null) {
      node = new Node(key, free.pop());
      policyStats.recordMiss();
      table[node.index] = node;
      data.put(key, node);
      evict(node);
    } else {
      node.accessTime = System.nanoTime();
      policyStats.recordHit();
      node.frequency++;
    }
  }

  /** Evicts if the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    if (free.isEmpty()) {
      List<Node> sample = (policy == EvictionPolicy.RANDOM)
          ? Arrays.asList(table)
          : sampleStrategy.sample(table, sampleSize);
      Node victim = policy.select(sample);
      policyStats.recordEviction();

      if (admittor.admit(candidate.key, victim.key)) {
        removeFromTable(victim);
        data.remove(victim.key);
      } else {
        removeFromTable(candidate);
        data.remove(candidate.key);
      }
    }
  }

  /** Removes the node from the table and adds the index to the free list. */
  private void removeFromTable(Node node) {
    int last = (table.length - free.size()) - 1;
    table[node.index] = table[last];
    table[node.index].index = node.index;
    table[last] = null;
    free.push(last);
  }

  /** The algorithms to choose a random sample with. */
  public enum Sample {
    GUESS {
      @Override public <E> List<E> sample(E[] elements, int sampleSize) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<E> sample = new ArrayList<E>(sampleSize);
        for (int i = 0; i < sampleSize; i++) {
          int index = random.nextInt(elements.length);
          sample.add(elements[index]);
        }
        return sample;
      }
    },
    RESERVOIR {
      @Override public <E> List<E> sample(E[] elements, int sampleSize) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<E> sample = new ArrayList<>(sampleSize);
        int count = 0;
        for (E e : elements) {
          count++;
          if (sample.size() <= sampleSize) {
            sample.add(e);
          } else {
            int index = random.nextInt(count);
            if (index < sampleSize) {
              sample.set(index, e);
            }
          }
        }
        return sample;
      }
    },
    SHUFFLE {
      @Override public <E> List<E> sample(E[] elements, int sampleSize) {
        List<E> sample = new ArrayList<>(Arrays.asList(elements));
        Collections.shuffle(sample, ThreadLocalRandom.current());
        return sample.subList(0, sampleSize);
      }
    };

    abstract <E> List<E> sample(E[] elements, int sampleSize);
  }

  /** The replacement policy. */
  public enum EvictionPolicy {

    /** Evicts entries based on insertion order. */
    FIFO {
      @Override Node select(List<Node> sample) {
        return sample.stream().min((first, second) ->
            Long.compare(first.insertionTime, second.insertionTime)).get();
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU {
      @Override Node select(List<Node> sample) {
        return sample.stream().min((first, second) ->
            Long.compare(first.accessTime, second.accessTime)).get();
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    MRU {
      @Override Node select(List<Node> sample) {
        return sample.stream().max((first, second) ->
            Long.compare(first.accessTime, second.accessTime)).get();
      }
    },

    /**
     * Evicts entries based on how frequently they are used, with the least frequent evicted first.
     */
    LFU {
      @Override Node select(List<Node> sample) {
        return sample.stream().min((first, second) ->
            Long.compare(first.frequency, second.frequency)).get();
      }
    },

    /**
     * Evicts entries based on how frequently they are used, with the most frequent evicted first.
     */
    MFU {
      @Override Node select(List<Node> sample) {
        return sample.stream().max((first, second) ->
            Long.compare(first.frequency, second.frequency)).get();
      }
    },

    /** Evicts a random entry. */
    RANDOM {
      @Override Node select(List<Node> sample) {
        int victim = ThreadLocalRandom.current().nextInt(sample.size());
        return sample.get(victim);
      }
    };

    /** Determines which node to evict. */
    abstract Node select(List<Node> sample);
  }

  /** A node on the double-linked list. */
  static final class Node {
    private final Object key;

    private long insertionTime;
    private long accessTime;
    private int frequency;
    private int index;

    /** Creates a new node. */
    public Node(Object key, int index) {
      this.insertionTime = System.nanoTime();
      this.accessTime = insertionTime;
      this.index = index;
      this.key = key;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("index", index)
          .toString();
    }
  }
}
