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

import static com.google.common.base.Preconditions.checkState;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Quick Demotion - Lazy Promotion algorithm. This algorithm uses a probationary FIFO queue to
 * evaluate whether a recent arrival should be admitted into the main region based on a frequency
 * threshold. A rejected candidate is placed into a ghost cache, where a cache miss that hits in the
 * ghost cache will cause the entry to be immediately promoted into the main region. This admission
 * scheme is referred to as "quick demotion" by the authors. The main region uses an n-bit clock
 * eviction policy, which is referred to as "lazy promotion" by the authors.
 * <p>
 * This implementation is based on the code provided by the authors in their
 * <a href="https://github.com/Thesys-lab/HotOS23-QD-LP">repository</a> and described by the paper
 * <a href="https://jasony.me/publication/hotos23-qdlp.pdf">FIFO can be Better than LRU: the Power
 * of Lazy Promotion and Quick Demotion</a> and the accompanying
 * <a href="https://jasony.me/slides/hotos23-qdlp.pdf">slides</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "two-queue.Qdlp")
public final class QdlpPolicy implements KeyOnlyPolicy {
  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final Node headGhost;
  final Node headFifo;
  final Node headMain;

  final int mainMaximumEntryFrequency;
  final int moveToMainThreshold;
  final int maximumSize;
  final int maxGhost;
  final int maxFifo;

  int sizeGhost;
  int sizeFifo;
  int sizeMain;

  public QdlpPolicy(Config config) {
    QdlpSettings settings = new QdlpSettings(config);
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    this.headGhost = new Node();
    this.headFifo = new Node();
    this.headMain = new Node();

    this.moveToMainThreshold = settings.moveToMainThreshold();
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.maxFifo = (int) (maximumSize * settings.percentFifo());
    this.maxGhost = (int) (maximumSize * settings.percentGhost());
    this.mainMaximumEntryFrequency = settings.mainMaximumEntryFrequency();
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(key);
    } else if (node.type == QueueType.GHOST) {
      onGhostHit(node);
    } else {
      onHit(node);
    }
  }

  private void onHit(Node node) {
    if (node.type == QueueType.FIFO) {
      node.frequency++;
    } else if (node.type == QueueType.MAIN) {
      node.frequency = Math.min(node.frequency + 1, mainMaximumEntryFrequency);
    }
    policyStats.recordHit();
  }

  private void onGhostHit(Node node) {
    policyStats.recordMiss();
    node.remove();
    sizeGhost--;

    node.appendToTail(headMain);
    node.type = QueueType.MAIN;
    sizeMain++;
    evict();
  }

  private void onMiss(long key) {
    Node node = new Node(key);
    node.appendToTail(headFifo);
    node.type = QueueType.FIFO;
    policyStats.recordMiss();
    data.put(key, node);
    sizeFifo++;
    evict();

    if (sizeFifo > maxFifo) {
      Node promoted = headFifo.next;
      promoted.remove();
      sizeFifo--;

      promoted.appendToTail(headMain);
      promoted.type = QueueType.MAIN;
      sizeMain++;
    }
  }

  private void evict() {
    if ((sizeFifo + sizeMain) <= maximumSize) {
      return;
    }
    policyStats.recordEviction();

    if ((maxFifo == 0) || (sizeFifo == 0)) {
      evictFromMain();
      return;
    }

    Node candidate = headFifo.next;
    int freq = candidate.frequency;
    candidate.frequency = 0;
    candidate.remove();
    sizeFifo--;

    if (freq >= moveToMainThreshold) {
      evictFromMain();
      candidate.appendToTail(headMain);
      candidate.type = QueueType.MAIN;
      sizeMain++;
    } else {
      candidate.appendToTail(headGhost);
      candidate.type = QueueType.GHOST;
      candidate.frequency = 0;
      sizeGhost++;

      if (sizeGhost > maxGhost) {
        var ghost = headGhost.next;
        data.remove(ghost.key);
        ghost.remove();
        sizeGhost--;
      }
    }
  }

  private void evictFromMain() {
    for (;;) {
      Node victim = headMain.next;
      if (victim.frequency == 0) {
        data.remove(victim.key);
        victim.remove();
        sizeMain--;
        break;
      }
      victim.frequency--;
      victim.moveToTail(headMain);
    }
  }

  @Override
  public void finished() {
    int maximum = (maximumSize + maxGhost);
    checkState(data.size() <= maximum, "%s > %s", data.size(), maximum);

    long ghosts = data.values().stream().filter(node -> node.type == QueueType.GHOST).count();
    checkState(ghosts == sizeGhost, "ghosts: %s != %s", ghosts, sizeGhost);
    checkState(ghosts <= maxGhost, "ghosts: %s > %s", ghosts, maxGhost);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  enum QueueType {
    FIFO,
    MAIN,
    GHOST,
  }

  static final class Node {
    final long key;

    Node prev;
    Node next;
    QueueType type;
    int frequency;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key) {
      this.key = key;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      checkState(prev == null);
      checkState(next == null);

      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Moves the node to the tail. */
    public void moveToTail(Node head) {
      checkState(prev != null);
      checkState(next != null);

      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = head;
      prev = head.prev;
      head.prev = this;
      prev.next = this;
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
          .add("type", type)
          .toString();
    }
  }

  static final class QdlpSettings extends BasicSettings {
    public QdlpSettings(Config config) {
      super(config);
    }
    public double percentFifo() {
      return config().getDouble("qdlp.percent-fifo");
    }
    public double percentGhost() {
      return config().getDouble("qdlp.percent-ghost");
    }
    public int moveToMainThreshold() {
      return config().getInt("qdlp.move-to-main-threshold");
    }
    public int mainMaximumEntryFrequency() {
      return config().getInt("qdlp.main-clock-maximum-frequency");
    }
  }
}
