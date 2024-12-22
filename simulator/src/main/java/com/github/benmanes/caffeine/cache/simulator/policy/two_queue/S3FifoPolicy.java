/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.two_queue;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.LinkedHashSet;
import java.util.function.IntConsumer;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Simple and Scalable caching with Static FIFO queues algorithm. This algorithm uses a
 * probationary FIFO queue to evaluate whether a recent arrival should be admitted into the main
 * region based on a frequency threshold. A rejected candidate is placed into a ghost cache, where a
 * cache miss that hits in the ghost cache will cause the entry to be immediately promoted into the
 * main region. The small and main regions uses an n-bit clock eviction policy.
 * <p>
 * This implementation is based on the code provided by the authors in their
 * <a href="https://github.com/cacheMon/libCacheSim">repository</a> and the pseudo code in their
 * paper <a href="https://dl.acm.org/doi/10.1145/3600006.3613147">FIFO queues are all you need for
 * cache eviction</a>. It does not use any of the paper's proposed adaptations for space saving or
 * concurrency because that may impact the hit rate. An implementor is encouraged to use a more
 * optimal structure than the reference's that is mimicked here.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "two-queue.S3Fifo", characteristics = WEIGHTED)
public final class S3FifoPolicy implements Policy {
  final Long2ObjectMap<Node> dataGhost;
  final Long2ObjectMap<Node> dataSmall;
  final Long2ObjectMap<Node> dataMain;
  final PolicyStats policyStats;
  final Node sentinelGhost;
  final Node sentinelSmall;
  final Node sentinelMain;

  final int moveToMainThreshold;
  final int maxFrequency;
  final long maximumSize;
  final long maxGhost;
  final long maxSmall;
  final long maxMain;

  long sizeGhost;
  long sizeSmall;
  long sizeMain;

  public S3FifoPolicy(Config config) {
    this.dataGhost = new Long2ObjectOpenHashMap<>();
    this.dataSmall = new Long2ObjectOpenHashMap<>();
    this.dataMain = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    this.sentinelGhost = new Node();
    this.sentinelSmall = new Node();
    this.sentinelMain = new Node();

    var settings = new S3FifoSettings(config);
    this.maximumSize = settings.maximumSize();
    this.maxFrequency = settings.maximumFrequency();
    this.moveToMainThreshold = settings.moveToMainThreshold();
    this.maxSmall = (long) (maximumSize * settings.percentSmall());
    this.maxGhost = (long) (maximumSize * settings.percentGhost());
    this.maxMain = maximumSize - maxSmall;
  }

  @Override
  public void record(AccessEvent event) {
    @Var Node node;
    if ((node = dataSmall.get(event.key())) != null) {
      onHit(event, node, change -> sizeSmall += change);
    } else if ((node = dataMain.get(event.key())) != null) {
      onHit(event, node, change -> sizeMain += change);
    } else if (event.weight() > maximumSize) {
      policyStats.recordWeightedMiss(event.weight());
    } else {
      policyStats.recordWeightedMiss(event.weight());
      node = insert(event);
      node.frequency = 0;
    }
    policyStats.recordOperation();
  }

  private void onHit(AccessEvent event, Node node, IntConsumer sizeAdjuster) {
    node.frequency = Math.min(node.frequency + 1, maxFrequency);
    sizeAdjuster.accept(event.weight() - node.weight);
    node.weight = event.weight();

    policyStats.recordWeightedHit(event.weight());
    while ((sizeSmall + sizeMain) > maximumSize) {
      evict();
    }
  }

  private Node insert(AccessEvent event) {
    while ((sizeSmall + sizeMain + event.weight()) > maximumSize) {
      evict();
    }
    var ghost = dataGhost.remove(event.key());
    if (ghost != null) {
      ghost.remove();
      sizeGhost -= ghost.weight;
      return insertMain(event.key(), event.weight());
    } else {
      return insertSmall(event.key(), event.weight());
    }
  }

  @CanIgnoreReturnValue
  private Node insertMain(long key, int weight) {
    var node = new Node(key, weight);
    node.appendAtHead(sentinelMain);
    dataMain.put(key, node);
    sizeMain += node.weight;
    return node;
  }

  private Node insertSmall(long key, int weight) {
    var node = new Node(key, weight);
    node.appendAtHead(sentinelSmall);
    dataSmall.put(key, node);
    sizeSmall += node.weight;
    return node;
  }

  private void insertGhost(long key, int weight) {
    // Bound the number of non-resident entries. While not included in the paper's pseudo code, the
    // author's reference implementation adds a similar constraint to avoid uncontrolled growth.
    if (weight > maxGhost) {
      return;
    }
    while ((sizeGhost + weight) > maxGhost) {
      evictFromGhost();
    }

    var node = new Node(key, weight);
    node.appendAtHead(sentinelGhost);
    dataGhost.put(key, node);
    sizeGhost += node.weight;
  }

  private void evict() {
    if ((sizeSmall >= maxSmall) || dataMain.isEmpty()) {
      evictFromSmall();
    } else {
      evictFromMain();
    }
  }

  private void evictFromSmall() {
    @Var boolean evicted = false;
    while (!evicted && !dataSmall.isEmpty()) {
      var victim = requireNonNull(sentinelSmall.prev);
      policyStats.recordOperation();
      if (victim.frequency > moveToMainThreshold) {
        insertMain(victim.key, victim.weight);
        if (sizeMain > maxMain) {
          evictFromMain();
        }
      } else {
        insertGhost(victim.key, victim.weight);
        evicted = true;
      }
      policyStats.recordEviction();
      dataSmall.remove(victim.key);
      sizeSmall -= victim.weight;
      victim.remove();
    }
  }

  private void evictFromMain() {
    @Var boolean evicted = false;
    while (!evicted && !dataMain.isEmpty()) {
      var victim = requireNonNull(sentinelMain.prev);
      policyStats.recordOperation();
      if (victim.frequency > 0) {
        victim.moveToHead(sentinelMain);
        victim.frequency--;
      } else {
        policyStats.recordEviction();
        dataMain.remove(victim.key);
        sizeMain -= victim.weight;
        victim.remove();
        evicted = true;
      }
    }
  }

  private void evictFromGhost() {
    if (!dataGhost.isEmpty()) {
      var victim = requireNonNull(sentinelGhost.prev);
      dataGhost.remove(victim.key);
      sizeGhost -= victim.weight;
      victim.remove();
    }
  }

  @Override
  public void finished() {
    long actualGhost = dataGhost.values().stream().mapToLong(node -> node.weight).sum();
    checkState(actualGhost == sizeGhost, "Ghost: %s != %s", actualGhost, sizeGhost);
    checkState(sizeGhost <= maxGhost, "Ghost: %s > %s", sizeGhost, maxGhost);
    checkLinks("Ghost", dataGhost, sentinelGhost);

    long actualSmall = dataSmall.values().stream().mapToLong(node -> node.weight).sum();
    checkState(actualSmall == sizeSmall, "Small: %s != %s", actualSmall, sizeSmall);
    checkLinks("Small", dataSmall, sentinelSmall);

    long actualMain = dataMain.values().stream().mapToLong(node -> node.weight).sum();
    checkState(actualMain == sizeMain, "Main: %s != %s", actualMain, sizeMain);
    checkLinks("Main", dataMain, sentinelMain);

    long actualSize = sizeSmall + sizeMain;
    checkState(actualSize <= maximumSize, "Total: %s > %s", actualSize, maximumSize);
  }

  private static void checkLinks(String label, Long2ObjectMap<Node> data, Node sentinel) {
    var forwards = new LinkedHashSet<Node>();
    for (var node = sentinel.next; node != sentinel; node = node.next) {
      requireNonNull(node);
      checkState(node == data.get(node.key), "%s: %s != %s", label, node, data.get(node.key));
      checkState(forwards.add(node), "%s: loop detected %s", label, forwards);
    }
    checkState(forwards.size() == data.size(), "%s (forwards): %s != %s",
        label, forwards.size(), data.size());

    var backwards = new LinkedHashSet<Node>();
    for (var node = sentinel.prev; node != sentinel; node = node.prev) {
      requireNonNull(node);
      checkState(node == data.get(node.key), "%s: %s != %s", label, node, data.get(node.key));
      checkState(backwards.add(node), "%s: loop detected %s", label, backwards);
    }
    checkState(backwards.size() == data.size(), "%s (backwards): %s != %s",
        label, backwards.size(), data.size());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class Node {
    final long key;

    @Nullable Node prev;
    @Nullable Node next;

    int weight;
    int frequency;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key, int weight) {
      this.key = key;
      this.weight = weight;
    }

    /** Appends the node as the head of the list. */
    public void appendAtHead(Node sentinel) {
      checkState(prev == null);
      checkState(next == null);

      Node head = requireNonNull(sentinel.next);
      sentinel.next = this;
      head.prev = this;
      next = head;
      prev = sentinel;
    }

    /** Moves the node to the tail. */
    public void moveToHead(Node sentinel) {
      checkState(prev != null);
      checkState(next != null);

      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = requireNonNull(sentinel.next);
      sentinel.next = this;
      next.prev = this;
      prev = sentinel;
    }

    /** Removes the node from the list. */
    public void remove() {
      checkState(prev != null);
      checkState(next != null);

      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .add("freq", frequency)
          .toString();
    }
  }

  static final class S3FifoSettings extends BasicSettings {
    public S3FifoSettings(Config config) {
      super(config);
    }
    public double percentSmall() {
      return config().getDouble("s3fifo.percent-small");
    }
    public double percentGhost() {
      return config().getDouble("s3fifo.percent-ghost");
    }
    public int moveToMainThreshold() {
      return config().getInt("s3fifo.move-to-main-threshold");
    }
    public int maximumFrequency() {
      return config().getInt("s3fifo.maximum-frequency");
    }
  }
}
