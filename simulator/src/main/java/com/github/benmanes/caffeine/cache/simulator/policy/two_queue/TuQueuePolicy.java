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
package com.github.benmanes.caffeine.cache.simulator.policy.two_queue;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * An adaption of the 2Q algorithm used by OpenBSD and memcached. Unlike the original 2Q algorithm,
 * non-resident entries are not retained in the TU-Q policy. For details see OpenBSD description at
 * <a href="https://www.tedunangst.com/flak/post/2Q-buffer-cache-algorithm">2Q buffer cache
 * algorithm</a> and memcached's at <a href="https://github.com/memcached/memcached/pull/97">[Work
 * In Progress] LRU rework</a>.
 * <p>
 * "We retain the key three working set distinction. In the OpenBSD code, they are named hot, cold,
 * and warm, and each is an LRU queue. New buffers start hot. They stay that way as long as they
 * remain on the hot queue. Eventually, a buffer will slip from the end of the hot queue onto the
 * front of the cold queue. (We preserve the data, not just the address.) When a new buffer is
 * needed, we recycle one from the tail of the cold queue. The oldest and coldest. If, on the other
 * hand, we have a cache hit on a cold buffer, it turns into a warm buffer and goes to the front of
 * the warm queue. Then as the warm queue lengthens, buffers start slipping from the end onto the
 * cold queue. Both the hot and warm queues are capped at one third of memory each to ensure
 * balance.
 * <p>
 * Scan resistance is achieved by means of the warm queue. Transient data will pass from hot queue
 * to cold queue and be recycled. Responsiveness is maintained by making the warm queue LRU so that
 * expired long term set buffers fade away."
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "two-queue.TuQueue")
public final class TuQueuePolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final int maximumSize;

  private int sizeHot;
  private final int maxHot;
  private final Node headHot;

  private int sizeWarm;
  private final int maxWarm;
  private final Node headWarm;

  private int sizeCold;
  private final Node headCold;

  @SuppressWarnings("this-escape")
  public TuQueuePolicy(Config config) {
    var settings = new TuQueueSettings(config);

    this.headHot = new Node();
    this.headWarm = new Node();
    this.headCold = new Node();
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.maxHot = (int) (maximumSize * settings.percentHot());
    this.maxWarm = (int) (maximumSize * settings.percentWarm());
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      policyStats.recordMiss();
      onMiss(key);
    } else {
      policyStats.recordHit();
      onHit(node);
    }
  }

  private void onHit(Node node) {
    requireNonNull(node.type);
    switch (node.type) {
      case HOT -> node.moveToTail(headHot);
      case WARM -> node.moveToTail(headWarm);
      case COLD -> {
        // If we have a cache hit on a cold buffer, it turns into a warm buffer and goes to the
        // front of the warm queue. Then as the warm queue lengthens, buffers start slipping from
        // the end onto the cold queue.
        node.remove();
        sizeCold--;

        node.type = QueueType.WARM;
        node.appendToTail(headWarm);
        sizeWarm++;

        if (sizeWarm > maxWarm) {
          Node demoted = requireNonNull(headWarm.next);
          demoted.remove();
          sizeWarm--;
          demoted.type = QueueType.COLD;
          demoted.appendToTail(headCold);
          sizeCold++;
        }
      }
    }
  }

  /** Adds the entry to the cache as HOT, overflowing to the COLD queue, and evicts if necessary. */
  private void onMiss(long key) {
    var node = new Node(key);
    node.type = QueueType.HOT;
    node.appendToTail(headHot);
    data.put(key, node);
    sizeHot++;

    if (sizeHot > maxHot) {
      Node demoted = requireNonNull(headHot.next);
      demoted.remove();
      sizeHot--;
      demoted.appendToTail(headCold);
      demoted.type = QueueType.COLD;
      sizeCold++;
      evict();
    }
  }

  private void evict() {
    if (data.size() > maximumSize) {
      Node victim = requireNonNull(headCold.next);
      data.remove(victim.key);
      victim.remove();
      sizeCold--;

      policyStats.recordEviction();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    checkState(sizeHot + sizeWarm + sizeCold == data.size());
  }

  enum QueueType {
    HOT,
    WARM,
    COLD,
  }

  static final class Node {
    final long key;

    @Nullable Node prev;
    @Nullable Node next;
    @Nullable QueueType type;

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
      Node tail = requireNonNull(head.prev);
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Moves the node to the tail. */
    public void moveToTail(Node head) {
      requireNonNull(prev);
      requireNonNull(next);

      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = head;
      prev = requireNonNull(head.prev);
      head.prev = this;
      prev.next = this;
    }

    /** Removes the node from the list. */
    public void remove() {
      requireNonNull(prev);
      requireNonNull(next);

      prev.next = next;
      next.prev = prev;
      prev = next = null;
      type = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("type", type)
          .toString();
    }
  }

  static final class TuQueueSettings extends BasicSettings {
    public TuQueueSettings(Config config) {
      super(config);
    }
    public double percentHot() {
      double percentHot = config().getDouble("tu-queue.percent-hot");
      checkState(percentHot < 1.0);
      return percentHot;
    }
    public double percentWarm() {
      double percentWarm = config().getDouble("tu-queue.percent-warm");
      checkState(percentWarm < 1.0);
      return percentWarm;
    }
  }
}
