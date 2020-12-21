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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The 2Q algorithm. This algorithm uses a queue for items that are seen once (IN), a queue for
 * items seen multiple times (MAIN), and a non-resident queue for evicted items that are being
 * monitored (OUT). The maximum size of the IN and OUT queues must be tuned with the authors
 * recommending 20% and 50% of the maximum size, respectively.
 * <p>
 * This implementation is based on the pseudo code provided by the authors in their paper
 * <a href="http://www.vldb.org/conf/1994/P439.PDF">2Q: A Low Overhead High Performance Buffer
 * Management Replacement Algorithm</a>. For consistency with other policies, this version places
 * the next item to be removed at the head and most recently added at the tail of the queue.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "two-queue.TwoQueue")
public final class TwoQueuePolicy implements KeyOnlyPolicy {
  static final Node UNLINKED = new Node();

  final Long2ObjectMap<Node> data;
  final PolicyStats policyStats;
  final int maximumSize;

  int sizeIn;
  final int maxIn;
  final Node headIn;

  int sizeOut;
  final int maxOut;
  final Node headOut;

  int sizeMain;
  final Node headMain;

  public TwoQueuePolicy(Config config) {
    TwoQueueSettings settings = new TwoQueueSettings(config);

    this.headIn = new Node();
    this.headOut = new Node();
    this.headMain = new Node();
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.maxIn = (int) (maximumSize * settings.percentIn());
    this.maxOut = (int) (maximumSize * settings.percentOut());
  }

  @Override
  @SuppressWarnings({"PMD.ConfusingTernary", "PMD.SwitchStmtsShouldHaveDefault"})
  public void record(long key) {
    // On accessing a page X :
    //   if X is in Am then
    //     move X to the head of Am
    //   else if (X is in Alout) then
    //     reclaimfor(X)
    //     add X to the head of Am
    //   else if (X is in Alin)
    //     // do nothing
    //   else // X is in no queue
    //     reclaimfor(X)
    //     add X to the head of Alin
    //   end if

    policyStats.recordOperation();
    Node node = data.get(key);
    if (node != null) {
      switch (node.type) {
        case MAIN:
          node.moveToTail(headMain);
          policyStats.recordHit();
          return;
        case OUT:
          node.remove();
          sizeOut--;

          reclaimfor(node);

          node.appendToTail(headMain);
          node.type = QueueType.MAIN;
          sizeMain++;

          policyStats.recordMiss();
          return;
        case IN:
          // do nothing
          policyStats.recordHit();
          return;
      }
    } else {
      node = new Node(key);
      node.type = QueueType.IN;

      reclaimfor(node);
      node.appendToTail(headIn);
      sizeIn++;

      policyStats.recordMiss();
    }
  }

  private void reclaimfor(Node node) {
    // if there are free page slots then
    //   put X into a free page slot
    // else if (size(Alin) > Kin)
    //   page out the tail of Alin, call it Y
    //   add identifier of Y to the head of Alout
    //   if (size(Alout) > Kout)
    //     remove identifier of Z from the tail of Alout
    //   end if
    //   put X into the reclaimed page slot
    // else
    //   page out the tail of Am, call it Y
    //   // do not put it on Alout; it hasn’t been accessed for a while
    //   put X into the reclaimed page slot
    // end if

    if ((sizeMain + sizeIn) < maximumSize) {
      data.put(node.key, node);
    } else if (sizeIn > maxIn) {
      // IN is full, move to OUT
      Node n = headIn.next;
      n.remove();
      sizeIn--;
      n.appendToTail(headOut);
      n.type = QueueType.OUT;
      sizeOut++;

      if (sizeOut > maxOut) {
        // OUT is full, drop oldest
        policyStats.recordEviction();
        Node victim = headOut.next;
        data.remove(victim.key);
        victim.remove();
        sizeOut--;
      }
      data.put(node.key, node);
    } else {
      // OUT has room, evict from MAIN
      policyStats.recordEviction();
      Node victim = headMain.next;
      data.remove(victim.key);
      victim.remove();
      sizeMain--;
      data.put(node.key, node);
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  enum QueueType {
    MAIN,
    IN,
    OUT,
  }

  static final class Node {
    final long key;

    Node prev;
    Node next;
    QueueType type;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key) {
      this.key = key;
      this.prev = UNLINKED;
      this.next = UNLINKED;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Moves the node to the tail. */
    public void moveToTail(Node head) {
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
      checkState(key != Long.MIN_VALUE);

      prev.next = next;
      next.prev = prev;
      prev = next = UNLINKED; // mark as unlinked
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("type", type)
          .toString();
    }
  }

  static final class TwoQueueSettings extends BasicSettings {

    public TwoQueueSettings(Config config) {
      super(config);
    }

    public double percentIn() {
      return config().getDouble("two-queue.percent-in");
    }

    public double percentOut() {
      return config().getDouble("two-queue.percent-out");
    }
  }
}
