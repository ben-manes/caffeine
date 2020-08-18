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
package com.github.benmanes.caffeine.cache.simulator.policy.irr;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The ClockPro algorithm. This algorithm differs from LIRS by replacing the LRU stacks with Clock
 * (Second Chance) policy. This allows cache hits to be performed concurrently at the cost of a
 * global lock on a miss and a worst case O(n) eviction when the queue is scanned.
 * <p>
 * ClockPro uses three hands that scan the queue. The hot hand points to the largest recency, the
 * cold hand to the cold entry furthest from the hot hand, and the test hand to the last cold entry
 * in the test period. This policy is adaptive by adjusting the percentage of hot and cold entries
 * that may reside in the cache. It uses non-resident (ghost) entries to retain additional history,
 * which are removed during the test hand's scan. The algorithm is explained by the authors in
 * <a href="http://www.ece.eng.wayne.edu/~sjiang/pubs/papers/jiang05_CLOCK-Pro.pdf">CLOCK-Pro: An
 * Effective Improvement of the CLOCK Replacement</a> and
 * <a href="http://www.slideshare.net/huliang64/clockpro">Clock-Pro: An Effective Replacement in OS
 * Kernel</a>.
 * <p>
 * This implementation is based on
 * <a href="https://bitbucket.org/SamiLehtinen/pyclockpro">PyClockPro</a> by Sami Lehtinen,
 * available under the MIT license.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ClockProPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;

  // Points to the hot page with the largest recency. The position of this hand actually serves as a
  // threshold of being a hot page. Any hot pages swept by the hand turn into cold ones. For the
  // convenience of the presentation, we call the page pointed to by HAND as the tail of the list,
  // and the page immediately after the tail page in the clockwise direction as the head of the
  // list.
  private Node handHot;

  // Points to the last resident cold page (i.e., the farthest one to the list head). Because we
  // always select this cold page for replacement, this is the position where we start to look for a
  // victim page, equivalent to the hand in CLOCK.
  private Node handCold;

  // Points to the last cold page in the test period. This hand is used to terminate the test period
  // of cold pages. The non-resident cold pages swept over by this hand will leave the circular
  // list.
  private Node handTest;

  // Maximum number or resident pages (cold + hot)
  private final int maximumSize;

  // Maximum number of cold pages (adaptive):
  //  - increases when test page gets a hit
  //  - decreases when test page is removed
  private int maximumColdSize;

  private int sizeHot;
  private int sizeCold;
  private int sizeTest;

  public ClockProPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    maximumSize = Ints.checkedCast(settings.maximumSize());
    policyStats = new PolicyStats("irr.ClockPro");
    data = new Long2ObjectOpenHashMap<>();
    maximumColdSize = maximumSize;

    // All the hands move in the clockwise direction
    handHot = handCold = handTest = null;
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new ClockProPolicy(config));
  }

  @Override
  public void record(long key) {
    Node node = data.get(key);

    if (node == null) {
      onMiss(key);
    } else if (node.status == Status.TEST) {
      onNonResidentHit(node);
    } else {
      onHit(node);
    }
  }

  private void onMiss(long key) {
    policyStats.recordOperation();
    policyStats.recordMiss();

    Node node = new Node(key);
    data.put(key, node);
    add(node);
    sizeCold++;

    checkState((sizeHot + sizeCold) <= maximumSize);
  }

  private void onNonResidentHit(Node node) {
    policyStats.recordOperation();
    policyStats.recordMiss();

    if (maximumColdSize < maximumSize) {
      maximumColdSize++;
    }
    delete(node);
    sizeTest--;
    checkState(sizeTest >= 0);

    node.status = Status.HOT;
    add(node);
    sizeHot++;

    checkState((sizeHot + sizeCold) <= maximumSize);
  }

  private void onHit(Node node) {
    policyStats.recordOperation();
    policyStats.recordHit();
    node.marked = true;
  }

  /** Add meta data after hand hot, evict data if required, and update hands accordingly. */
  private void add(Node node) {
    evict();

    if (handHot == null) {
      handHot = handCold = handTest = node;
      node.next = node.prev = node;
    } else {
      node.prev = handHot;
      node.next = handHot.next;
      handHot.next.prev = node;
      handHot.next = node;
    }
    if (handCold == handHot) {
      handCold = node.next;
    }
    if (handTest == handHot) {
      handTest = node.next;
    }
    handHot = node.next;
  }

  /** Delete meta data data, update hands accordingly. */
  private void delete(Node node) {
    if (handHot == node) {
      handHot = node.next;
    }
    if (handCold == node) {
      handCold = node.next;
    }
    if (handTest == node) {
      handTest = node.next;
    }
    node.remove();
  }

  /** Evict pages from cache using the cold hand. */
  private void evict() {
    // Now let us summarize how these hands coordinate their operations on the clock to resolve a
    // page fault. When there is a page fault, the faulted page must be a cold page. We first run
    // handCold for a free space. If the faulted cold page is not in the list, its reuse distance is
    // highly likely to be larger than the recency of hot pages. So the page is still categorized
    // as a cold page and is placed at the list head. The page also initiates its test period. If
    // the number of cold pages is larger than the threshold (maxCold + max), we run handTest. If
    // the cold page is in the list , the faulted page turns into a hot page and is placed at the
    // head of the list. We run handHot to turn a hot page with a large recency into a cold page.

    while (maximumSize <= (sizeHot + sizeCold)) {
      policyStats.recordOperation();
      scanCold();
    }
  }

  /** Moves the hot hand forward. */
  private void scanHot() {
    // As mentioned above, what triggers the movement of handHot is that a cold page is found to
    // have been accessed in its test period and thus turns into a hot page, which maybe accordingly
    // turns the hot page with the largest recency into a cold page. If the reference bit of the hot
    // page pointed to by handHot is unset, we can simply change its status and then move the hand
    // forward. However, if the bit is set, which indicates the page has been re-accessed, we
    // spare this page, reset its reference bit and keep it as a hot page. This is because the
    // actual access time of the hot page could be earlier than the cold page. Then we move the hand
    // forward and do the same on the hot pages with their bits set until the hand encounters a hot
    // page with a reference bit of zero. Then the hot page turns into a cold page. Note that moving
    // handHot forward is equivalent to leaving the page it moves by at the list head. Whenever
    // the hand encounters a cold page, it will terminate the page’s test period. The hand will also
    // remove the cold page from the clock if it is non-resident (the most probable case). It
    // actually does the work on the cold page on behalf of hand handTest. Finally the hand stops at
    // a hot page.

    if (handHot == handTest) {
      scanTest();
    }
    if (handHot.status == Status.HOT) {
      if (handHot.marked) {
        handHot.marked = false;
      } else {
        handHot.status = Status.COLD;
        sizeCold++;
        sizeHot--;
      }
    }
    // Move the hand forward
    handHot = handHot.next;
  }

  private void scanCold() {
    // The handCold is used to search for a resident cold page for replacement. If the reference
    // bit of the cold page currently pointed to by the handCold is unset, we replace the cold page
    // for a free space. The replaced cold page will remain in the list as a non-resident cold page
    // until it runs out of its test period, if it is in its test period. If not, we move it out of
    // the clock. However, if its bit is set and it is in its test period, we turn the cold page
    // into a hot page, and ask handHot for its actions, because an access during the test period
    // indicates a competitively small reuse distance. If its bit is set but it is not in its test
    // period, there are no status change as well as handHot actions. In both of the cases, its
    // reference bit is reset, and we move it to the list head. The hand will keep moving until it
    // encounters a cold page eligible for replacement, and stops at the next resident cold page.

    if (handCold.status == Status.COLD) {
      if (handCold.marked) {
        handCold.status = Status.HOT;
        handCold.marked = false;
        sizeCold--;
        sizeHot++;
      } else {
        policyStats.recordEviction();
        handCold.status = Status.TEST;
        sizeCold--;
        sizeTest++;
        while (maximumSize < sizeTest) {
          policyStats.recordOperation();
          scanTest();
        }
      }
      checkState(sizeCold >= 0);
    }
    // Move the hand forward
    handCold = handCold.next;
    while ((maximumSize - maximumColdSize) < sizeHot) {
      policyStats.recordOperation();
      scanHot();
    }
  }

  private void scanTest() {
    // We keep track of the number of non-resident cold pages. Once the number exceeds the memory
    // size in the number of pages, we terminate the test period of the cold page pointed to by
    // handTest. We also remove it from the clock if it is a non-resident page. Because the cold
    // page has used up its test period without a re-access and has no chance to turn into a hot
    // page with its next access. handTest then moves forward and stops at the next cold page.

    if (handTest == handCold) {
      scanCold();
    }
    if (handTest.status == Status.TEST) {
      requireNonNull(data.remove(handTest.key));
      delete(handTest);
      sizeTest--;
      if (maximumColdSize > 1) {
        maximumColdSize--;
      }
    }
    // Move the hand forward
    handTest = handTest.next;
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    checkState(sizeHot + sizeCold + sizeTest == data.size());
    checkState(sizeHot + sizeCold <= maximumSize);
    checkState(maximumColdSize <= maximumSize);
  }

  enum Status {
    HOT, COLD, TEST,
  }

  private static final class Node {
    final long key;
    boolean marked;
    Status status;

    Node prev;
    Node next;

    public Node(long key) {
      status = Status.COLD;
      this.key = key;
    }

    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      prev = next = null;
    }
  }
}
