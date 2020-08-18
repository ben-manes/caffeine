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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.segment;

import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Random;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the window and main spaces implements random eviction.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RandomWindowTinyLfuPolicy implements KeyOnlyPolicy {
  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final Admittor admittor;
  final int maximumSize;
  final Random random;
  final Node[] window;
  final Node[] main;

  int windowSize;
  int mainSize;

  public RandomWindowTinyLfuPolicy(double percentMain, RandomWindowTinyLfuSettings settings) {
    policyStats = new PolicyStats(
        "sketch.RandomWindowTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    maximumSize = Ints.checkedCast(settings.maximumSize());
    admittor = new TinyLfu(settings.config(), policyStats);
    random = new Random(settings.randomSeed());
    data = new Long2ObjectOpenHashMap<>();

    int maxMain = (int) (maximumSize * percentMain);
    window = new Node[maximumSize - maxMain + 1];
    main = new Node[maxMain + 1];
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    RandomWindowTinyLfuSettings settings = new RandomWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new RandomWindowTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(long key) {
    Node node = data.get(key);
    admittor.record(key);
    if (node == null) {
      node = new Node(key, windowSize);
      policyStats.recordOperation();
      policyStats.recordMiss();
      window[node.index] = node;
      data.put(key, node);
      windowSize++;
      evict();
    } else {
      policyStats.recordOperation();
      policyStats.recordHit();
    }
  }

  /** Evicts if the map exceeds the maximum capacity. */
  private void evict() {
    if (windowSize <= (window.length - 1)) {
      return;
    }

    Node candidate = window[random.nextInt(window.length)];
    removeFromTable(window, candidate);
    windowSize--;

    main[mainSize] = candidate;
    candidate.index = mainSize;
    mainSize++;

    if (data.size() > maximumSize) {
      Node victim = main[random.nextInt(main.length)];
      Node evict = admittor.admit(candidate.key, victim.key) ? victim : candidate;
      removeFromTable(main, evict);
      data.remove(evict.key);
      mainSize--;

      policyStats.recordEviction();
    }
  }

  /** Removes the node from the table and adds the index to the free list. */
  private void removeFromTable(Node[] table, Node node) {
    int last = table.length - 1;
    table[node.index] = table[last];
    table[node.index].index = node.index;
    table[last] = null;
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;
    int index;

    /** Creates a new node. */
    public Node(long key, int index) {
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

  static final class RandomWindowTinyLfuSettings extends BasicSettings {
    public RandomWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("random-window-tiny-lfu.percent-main");
    }
  }
}
