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
package com.github.benmanes.caffeine.cache.simulator.policy.adaptive;

import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * CAR with Temporal filtering policy. This algorithm differs from CAR by maintaining a temporal
 * locality window such that pages that are re-requested within the window are of short-term utility
 * and pages that are re-requested outside the window are of long-term utility. The temporal
 * locality window is an adaptable parameter of the algorithm.
 * <p>
 * This implementation is based on the pseudo code provided by the authors in their paper <a href=
 * "https://www.usenix.org/legacy/publications/library/proceedings/fast04/tech/full_papers/bansal/bansal.pdf">
 * CAR: Clock with Adaptive Replacement</a> and is further described in their paper,
 * <p>
 * This algorithm is patented by IBM (6996676, 7096321, 7058766, 8612689).
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CartPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final int maximumSize;

  private final Node headT1;
  private final Node headT2;
  private final Node headB1;
  private final Node headB2;

  private int sizeT1;
  private int sizeT2;
  private int sizeB1;
  private int sizeB2;
  private int sizeS;
  private int sizeL;
  private int p;
  private int q;

  public CartPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.policyStats = new PolicyStats("adaptive.Cart");
    this.data = new Long2ObjectOpenHashMap<>();
    this.headT1 = new Node();
    this.headT2 = new Node();
    this.headB1 = new Node();
    this.headB2 = new Node();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new CartPolicy(config));
  }

  @Override
  public void record(long key) {
    Node node = data.get(key);
    if (isHit(node)) {
      policyStats.recordHit();
      onHit(node);
    } else {
      policyStats.recordMiss();
      onMiss(key, node);
    }
  }

  private static boolean isHit(Node node) {
    // if (x is in T1 ∪ T2) then cache hit
    return (node != null) && ((node.type == QueueType.T1) || (node.type == QueueType.T2));
  }

  private void onHit(Node node) {
    // Set the page reference bit for x
    node.marked = true;
    policyStats.recordOperation();
  }

  private void onMiss(long key, Node node) {
    // if (|T1| + |T2| = c) then
    //   /* cache full, replace a page from cache */
    //   replace()
    //   /* history replacement */
    //   if ((x  ̸∈ B1 ∪ B2) and (|B1| + |B2| = c + 1) and
    //      ((|B1| > max{0, q}) or (B2 is empty))) then
    //     Remove the bottom page in B1 from the history.
    //   else if ((x  ̸∈ B1 ∪ B2) and (|B1| + |B2| = c + 1)) then
    //     Remove the bottom page in B2 from the history.
    //
    // /* history miss */
    // if (x is not in B1 ∪ B2) then
    //   Insert x at the tail of T1.
    //   Reset the page reference bit of x
    //   Set filter bit of x to "S"
    //   nS = nS + 1 /* history hit */
    // else if (x is in B1) then
    //   Adapt: Increase the target size for the list T1 as: p = min{p + max{1, nS / |B1|}, c}
    //   Move x to the tail of T1
    //   Reset the page reference bit of x
    //   Set nL = nL + 1.
    //   Set type of x to "L".
    // /* history hit */
    // else /* x must be in B2 */
    //   Adapt: Decrease the target size for the list T1 as: p = max{p − max{1, nL / |B2|}, 0}
    //   Move x to the tail of T1
    //   Reset the page reference bit of x
    //   Set nL = nL + 1
    //   if (|T2|+|B2|+|T1|−nS ≥ c) then
    //     Set target q = min(q + 1, 2c − |T1|)
    policyStats.recordOperation();

    if ((sizeT1 + sizeT2) == maximumSize) {
      demote();
      if (!isGhost(node) && ((sizeB1 + sizeB2) == (maximumSize + 1))) {
        if (sizeB1 > Math.max(0, q) || (sizeB2 == 0)) {
          // Remove the bottom page in B1 from the history
          Node victim = headB1.next;
          data.remove(victim.key);
          victim.remove();
          sizeB1--;
        } else {
          // Remove the bottom page in B2 from the history
          Node victim = headB2.next;
          data.remove(victim.key);
          victim.remove();
          sizeB2--;
        }
      }
    }
    if (!isGhost(node)) {
      // Insert x at the tail of T1
      checkState(node == null);
      node = new Node(key);
      node.appendToTail(headT1);
      node.type = QueueType.T1;
      data.put(key, node);
      sizeT1++;

      // Set filter bit of x to "S"
      // nS = nS + 1 /* history hit */
      node.filter = FilterType.ShortTerm;
      sizeS++;
    } else if (node.type == QueueType.B1) {
      // Adapt: Increase the target size for the list T1 as: p = min{p + max{1, nS / |B1|}, c}
      p = Math.min(p + Math.max(1, sizeS / sizeB1), maximumSize);

      // Move x to the tail of T1
      node.remove();
      sizeB1--;
      node.appendToTail(headT1);
      node.type = QueueType.T1;
      sizeT1++;

      // Reset the page reference bit of x
      // Set nL = nL + 1.
      // Set type of x to "L".
      node.filter = FilterType.LongTerm;
      node.marked = false;
      sizeL++;
    } else if (node.type == QueueType.B2) {
      // Adapt: Decrease the target size for the list T1 as: p = max{p − max{1, nL / |B2|}, 0}
      p = Math.max(p - Math.max(1, sizeL / sizeB2), 0);

      // Move x to the tail of T1
      node.remove();
      sizeB2--;
      node.appendToTail(headT1);
      node.type = QueueType.T1;
      sizeT1++;

      // Reset the page reference bit of x
      // Set nL = nL + 1
      node.filter = FilterType.LongTerm;
      node.marked = false;
      sizeL++;

      // if (|T2|+|B2|+|T1|−nS ≥ c) then
      //   Set target q = min(q + 1, 2c − |T1|)
      if (sizeT2 + sizeB2 + sizeT1 - sizeS >= maximumSize) {
        q = Math.min(q + 1, (2 * maximumSize) - sizeT1);
      }
    } else {
      throw new IllegalStateException();
    }
  }

  private static boolean isGhost(Node node) {
    return (node != null) && ((node.type == QueueType.B1) || (node.type == QueueType.B2));
  }

  private void demote() {
    // while (the page reference bit of the head page in T2 is 1)) then
    //   Move the head page in T2 to tail position in T1
    //   Reset the page reference bit
    //   if (|T2|+|B2|+|T1|−nS ≥ c) then
    //     Set target q = min(q + 1, 2c − |T1|)
    //
    // /* The following while loop should stop, if T1 is empty */
    // while ((the filter bit of the head page in T1 is "L")
    //     or (the page reference bit of the head page in T1 is set))
    //   if ((the page reference bit of the head page in T1 is set)
    //     Move the head page in T1 to tail position in T1
    //     Reset the page reference bit
    //     if ((|T1| ≥ min(p + 1, |B1|)) and (the filter bit of the moved page is "S")) then
    //       set type of x to "L"
    //       nS = nS − 1
    //       nL = nL + 1
    //   else
    //     Move the head page in T1 to tail position in T2
    //     Reset the page reference bit
    //     Set q = max(q − 1, c − |T1|)
    //
    // if (|T1| >= max(1, p)) then
    //   Demote the head page in T1 and make it the MRU page in B1
    //   nS = nS − 1
    // else
    //   Demote the head page in T2 and make it the MRU page in B2
    //   nL = nL − 1

    policyStats.recordEviction();

    while (headT2.next.marked) {
      policyStats.recordOperation();
      Node demoted = headT2.next;
      demoted.marked = false;
      demoted.remove();
      sizeT2--;
      demoted.appendToTail(headT1);
      demoted.type = QueueType.T1;
      sizeT1++;

      if (sizeT2 + sizeB2 + sizeT1 - sizeS >= maximumSize) {
        q = Math.min(q + 1, (2 * maximumSize) - sizeT1);
      }
    }

    while ((headT1.next.filter == FilterType.LongTerm) || headT1.next.marked) {
      policyStats.recordOperation();
      Node node = headT1.next;
      if (node.marked) {
        node.moveToTail(headT1);
        node.marked = false;

        if ((sizeT1 >= Math.max(p + 1, sizeB1)) && (node.filter == FilterType.ShortTerm)) {
          node.filter = FilterType.LongTerm;
          sizeS--;
          sizeL++;
        }
      } else {
        node.remove();
        node.type = QueueType.T2;
        node.appendToTail(headT2);
        sizeT1--;
        sizeT2++;

        q = Math.max(q - 1, maximumSize - sizeT1);
      }
    }

    if (sizeT1 >= Math.max(1, p)) {
      Node demoted = headT1.next;
      demoted.remove();
      demoted.type = QueueType.B1;
      demoted.appendToTail(headB1);
      sizeT1--;
      sizeB1++;
      sizeS--;
    } else {
      Node demoted = headT2.next;
      demoted.remove();
      demoted.type = QueueType.B2;
      demoted.appendToTail(headB2);
      sizeT2--;
      sizeB2++;
      sizeL--;
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    checkState(sizeT1 == data.values().stream().filter(node -> node.type == QueueType.T1).count());
    checkState(sizeT2 == data.values().stream().filter(node -> node.type == QueueType.T2).count());
    checkState(sizeB1 == data.values().stream().filter(node -> node.type == QueueType.B1).count());
    checkState(sizeB2 == data.values().stream().filter(node -> node.type == QueueType.B2).count());
    checkState((sizeT1 + sizeT2) <= maximumSize);
    checkState((sizeB1 + sizeB2) <= maximumSize);
  }

  private enum QueueType {
    T1, B1,
    T2, B2,
  }

  private enum FilterType {
    ShortTerm,
    LongTerm,
  }

  static final class Node {
    final long key;

    Node prev;
    Node next;
    QueueType type;
    FilterType filter;

    boolean marked;

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
}
