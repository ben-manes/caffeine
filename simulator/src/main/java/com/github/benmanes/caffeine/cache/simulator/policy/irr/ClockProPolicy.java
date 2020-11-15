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
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @author park910113@gmail.com (Chanyoung Park)
 */
public final class ClockProPolicy implements KeyOnlyPolicy {
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;

  // We place all the accessed pages, either hot or cold, into one single list in the order of their
  // accesses. In the list, the pages with small recency are at the list head, and the pages with
  // large recency are at the list tail.
  private Node listHead;

  // Points to the hot page with the largest recency. The position of this hand actually serves as a
  // threshold of being a hot page. Any hot pages swept by the hand turn into cold ones.
  private Node handHot;

  // Points to the last resident cold page (i.e., the farthest one to the list head). Because we
  // always select this cold page for replacement, this is the position where we start to look for a
  // victim page, equivalent to the hand in CLOCK.
  private Node handCold;

  // Points to the last cold page in the test period. This hand is used to terminate the test period
  // of cold pages. The non-resident cold pages swept over by this hand will leave the circular
  // list.
  private Node handTest;

  // Maximum number of resident pages (hot + resident cold)
  private final int maxSize;
  private final int maxNonResSize;

  private int sizeHot;
  private int sizeResCold;
  private int sizeNonResCold;
  private int sizeInTest;
  private int sizeFree;

  // Target number of resident cold pages (adaptive):
  //  - increases when test page gets a hit
  //  - decreases when test page is removed
  private int coldTarget;
  // {min,max}ResColdSize are boundary of coldTarget.
  private int minResColdSize;
  private int maxResColdSize;

  // Enable to print out the internal state
  static final boolean debug = false;

  public ClockProPolicy(Config config) {
    ClockProSettings settings = new ClockProSettings(config);
    this.maxSize = Ints.checkedCast(settings.maximumSize());
    this.maxNonResSize = (int) (maxSize * settings.nonResidentMultiplier());
    this.minResColdSize = (int) (maxSize * settings.percentMinCold());
    if (minResColdSize < settings.lowerBoundCold()) {
      minResColdSize = settings.lowerBoundCold();
    }
    this.maxResColdSize = (int) (maxSize * settings.percentMaxCold());
    if (maxResColdSize > maxSize - minResColdSize) {
      maxResColdSize = maxSize - minResColdSize;
    }
    this.policyStats = new PolicyStats("irr.ClockPro");
    this.data = new Long2ObjectOpenHashMap<>();
    this.coldTarget = minResColdSize;
    this.listHead = this.handHot = this.handCold = this.handTest = null;
    this.sizeFree = maxSize;
    checkState(minResColdSize <= maxResColdSize);
  }

  /**
   * Returns all variations of this policy based on the configuration parameters.
   */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new ClockProPolicy(config));
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    validateStatus();
    validateClockStructure();
    if (debug) {
      printClock();
    }
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      node = new Node(key);
      data.put(key, node);
      onMiss(node);
    } else if (node.isResident()) {
      onHit(node);
    } else {
      onMiss(node);
    }
    if (debug) {
      validateStatus();
      validateClockStructure();
    }
  }

  private void onHit(Node node) {
    policyStats.recordHit();
    node.marked = true;
  }

  private void onMiss(Node node) {
    // When the clock is not full, all the accessed blocks are given HOT status until its size
    // reaches maxSize - minResColdSize. After that, COLD_RES_IN_TEST status is given to any blocks
    // that are accessed for the first time.
    policyStats.recordMiss();
    if (sizeFree > minResColdSize) {
      onHotWarmupMiss(node);
    } else if (sizeFree > 0) {
      onColdWarmupMiss(node);
    } else {
      onFullMiss(node);
    }
    organizeHands();
  }

  /** Records a miss when the hot set is not full. */
  private void onHotWarmupMiss(Node node) {
    node.moveToHead(Status.HOT);
  }

  /** Records a miss when the cold set is not full. */
  private void onColdWarmupMiss(Node node) {
    node.moveToHead(Status.COLD_RES_IN_TEST);
  }

  /** Records a miss when the hot and cold set are full. */
  private void onFullMiss(Node node) {
    if (node.status == Status.COLD_NON_RES) {
      onNonResidentFullMiss(node);
    } else if (node.status == Status.OUT_OF_CLOCK) {
      onOutOfClockFullMiss(node);
    } else {
      throw new IllegalStateException();
    }
  }

  private void onOutOfClockFullMiss(Node node) {
    // If the faulted cold page is not in the list, its reuse distance is highly likely to be larger
    // than the recency of hot pages. So the page is still categorized as a cold page and is placed
    // at the list head. The page also initiates its test period.
    evict();
    node.moveToHead(Status.COLD_RES_IN_TEST);
  }

  private void onNonResidentFullMiss(Node node) {
    // If the cold page is in the list, the faulted page turns into a hot page and is placed at the
    // head of the list. We run handHot to turn a hot page with a large recency into a cold page.
    //
    // While not described in the paper, the author's reference implementation does not promote the
    // accessed node when the runHandHot failed to find a non-accessed hot node between the handHot
    // and the accessed cold node.
    evict();
    if (canPromote(node)) {
      node.moveToHead(Status.HOT);
    } else {
      node.moveToHead(Status.COLD_RES_IN_TEST);
    }
  }

  private void evict() {
    policyStats.recordEviction();
    while (sizeFree == 0) {
      runHandCold();
    }
  }

  private boolean canPromote(Node candidate) {
    // To compare reuse distance, candidate must be in the clock. And only the node in its test
    // period can be considered to promote.
    if (!candidate.isInClock() || !candidate.isInTest()) {
      return false;
    }
    // This candidate cold page is accessed during its test period, so we increment coldTarget by 1.
    coldTargetAdjust(+1);
    while (sizeHot >= maxSize - coldTarget) {
      // handHot has passed the candidate and terminates its test period. Reject the promotion.
      if (!candidate.isInTest()) {
        return false;
      }
      // Failed to demote a hot node. Reject the promotion.
      if (!runHandHot(candidate)) {
        return false;
      }
    }
    return true;
  }

  private void runHandCold() {
    // runHandCold is used to search for a resident cold page for replacement.
    checkState(handCold.isResidentCold());

    if (handCold.marked) {
      // If its bit is set and it is in its test period, we turn the cold page into a hot page,
      // and ask HAND for its actions, because an access during the test period indicates a
      // competitively small reuse distance. If its bit is set but it is not in its test period,
      // there are no status change as well as HAND actions. Its reference bit is reset, and we
      // move it to the list head.
      if (handCold.isInTest()) {
        if (canPromote(handCold)) {
          handCold.moveToHead(Status.HOT);
        } else {
          handCold.moveToHead(Status.COLD_RES_IN_TEST);
        }
      } else {
        handCold.moveToHead(Status.COLD_RES_IN_TEST);
      }
    } else {
      // If the reference bit of the cold page currently pointed to by handCold is unset, we replace
      // the cold page for a free space. If the replaced cold page is in its test period, then it
      // will remain in the list as a non-resident cold page until it runs out of its test period.
      // If the replaced cold page is not in its test period, we move it out of the clock.
      if (handCold.isInTest()) {
        handCold.setStatus(Status.COLD_NON_RES);
        handCold = handCold.prev;
      } else {
        handCold.removeFromClock();
      }
      // We keep track the number of non-resident cold pages. Once the number exceeds the limit, we
      // terminate the test period of the cold page pointed to by handTest.
      while (sizeNonResCold > maxNonResSize) {
        runHandTest();
      }
    }
    // search for next cold page for consideration
    nextHandCold();
  }

  // runHandHot demotes a hot node between the handHot and trigger node. If the demotion was
  // successful it returns true, otherwise it returns false.
  private boolean runHandHot(Node trigger) {
    // What triggers the movement of handHot is that a cold page (== argument "trigger") is found to
    // have been accessed in its test period and thus turns into a hot page, which "maybe"
    // accordingly turns the hot page with the largest recency into a cold page.
    checkState(handHot.isHot());
    checkState(trigger.isInTest());

    boolean demoted = false;
    while (handHot != trigger) {
      if (handHot.isHot()) {
        // If the reference bit of the hot page pointed to by handHot is unset, we can simply change
        // its status and then move the hand forward. However, if the bit is set, which indicates
        // the page has been re-accessed, we spare this page, reset its reference bit and keep it as
        // a hot page. This is because the actual access time of the hot page could be earlier than
        // the cold page. Then we move the hand forward and do the same on the hot pages with their
        // bits set until the hand encounters a hot page with a reference bit of zero. Then the hot
        // page turns into a cold page.
        if (handHot.marked) {
          handHot.moveToHead(Status.HOT);
        } else {
          handHot.moveToHead(Status.COLD_RES);
          demoted = true;
          break;
        }
      } else {
        // Whenever the hand encounters a cold page, it will terminate the page’s test period. The
        // hand will also remove the cold page from the clock if it is non-resident (the most
        // probable case). It actually does the work on the cold page on behalf of handTest.
        handHot = handHot.prev;
        terminateTestPeriod(handHot.next);
      }
    }
    // Finally the hand stops at a hot page.
    nextHandHot();
    return demoted;
  }

  private void runHandTest() {
    checkState(handTest.isInTest());
    terminateTestPeriod(handTest);
    nextHandTest();
  }

  private void terminateTestPeriod(Node node) {
    if (!node.isInTest()) {
      return;
    }
    // We terminate the test period of the cold page, and also remove it from the clock if it is a
    // non-resident page. Because the cold page has used up its test period without a re-access and
    // has no chance to turn into a hot page with its next access.
    if (node.isResidentCold()) {
      node.setStatus(Status.COLD_RES);
    } else {
      node.removeFromClock();
    }
    // If a cold page is accessed during its test period, we increment coldTarget by 1. If a cold
    // page passes its test period without a re-access, we decrement coldTarget by 1. Note the
    // aforementioned cold pages include resident and non-resident cold pages.
    coldTargetAdjust(node.marked ? +1 : -1);
  }

  // Make handCold points to the resident cold page with the largest recency.
  private void nextHandCold() {
    if (sizeResCold > 0) {
      if (handCold == null) {
        handCold = listHead.prev;
      }
      while (!handCold.isResidentCold()) {
        handCold = handCold.prev;
      }
    } else {
      handCold = null;
    }
  }

  // Make handHot points to the hot page with the largest recency.
  private void nextHandHot() {
    if (sizeHot > 0) {
      if (handHot == null) {
        handHot = listHead.prev;
      }
      while (handHot.isCold()) {
        // Terminate test period of encountered cold pages.
        handHot = handHot.prev;
        terminateTestPeriod(handHot.next);
      }
      // handHot may have passed the handTest and terminates the test period so check the handTest.
      nextHandTest();
    } else {
      handHot = null;
    }
  }

  // Make handTest points to the cold page in its test period with the largest recency.
  private void nextHandTest() {
    if (sizeInTest > 0) {
      if (handTest == null) {
        handTest = (handHot == null ? listHead.prev : handHot);
      }
      while (!handTest.isInTest()) {
        handTest = handTest.prev;
      }
    } else {
      handTest = null;
    }
  }

  private void organizeHands() {
    nextHandCold();
    nextHandHot();
    nextHandTest();
  }

  private void coldTargetAdjust(int n) {
    coldTarget += n;
    if (coldTarget < minResColdSize) {
      coldTarget = minResColdSize;
    } else if (coldTarget > maxResColdSize) {
      coldTarget = maxResColdSize;
    }
  }

  private void validateClockStructure() {
    checkState(listHead != null);

    if (handHot == null) {
      checkState(sizeHot == 0);
    } else {
      checkState(handHot.isHot());
      for (Node n = listHead.prev; n != handHot; n = n.prev) {
        checkState(!n.isInTest());
        checkState(n != handTest);
      }
    }

    if (handCold == null) {
      checkState(sizeResCold == 0);
    } else {
      checkState(handCold.isResidentCold());
      for (Node n = listHead.prev; n != handCold; n = n.prev) {
        checkState(!n.isResidentCold());
      }
    }

    if (handTest == null) {
      checkState(sizeInTest == 0);
    } else {
      checkState(handTest.isInTest());
      for (Node n = listHead.prev; n != handTest; n = n.prev) {
        checkState(n.isResident());
        checkState(!n.isInTest());
      }
    }
  }

  private void validateStatus() {
    checkState(listHead != null);

    int sizeHot;
    int sizeResCold;
    int sizeNonResCold;
    int sizeInTest;
    sizeHot = sizeInTest = sizeResCold = sizeNonResCold = 0;

    Node node = listHead;
    do {
      if (node == null) {
        break;
      }
      checkState(node.isInClock());
      if (node.isHot()) {
        sizeHot++;
      }
      if (node.isResidentCold()) {
        sizeResCold++;
      }
      if (!node.isResident()) {
        sizeNonResCold++;
      }
      if (node.isInTest()) {
        sizeInTest++;
      }
      node = node.next;
    } while (node != listHead);

    checkState(sizeHot == this.sizeHot);
    checkState(sizeNonResCold == this.sizeNonResCold);
    checkState(sizeInTest == this.sizeInTest);
    checkState(sizeResCold == this.sizeResCold);
    checkState(sizeHot + sizeResCold == maxSize - sizeFree);
    checkState(sizeResCold + sizeFree >= minResColdSize);
    checkState(sizeResCold <= maxResColdSize);
    checkState(sizeNonResCold <= maxNonResSize);
  }

  /** Prints out the internal state of the policy. */
  private void printClock() {
    System.out.println("** CLOCK-Pro list HEAD (small recency) **");
    System.out.println(listHead.toString());
    for (Node n = listHead.next; n != listHead; n = n.next) {
      System.out.println(n.toString());
    }
    System.out.println("** CLOCK-Pro list TAIL (large recency) **");
  }

  // +----- Status ------+- Resident -+- In Test -+
  // |               HOT |       TRUE |     FALSE |
  // |          COLD_RES |       TRUE |     FALSE |
  // |  COLD_RES_IN_TEST |       TRUE |      TRUE |
  // |      COLD_NON_RES |      FALSE |      TRUE |
  // |      OUT_OF_CLOCK |      FALSE |     FALSE |
  // +-------------------+------------+-----------+
  enum Status {
    HOT, COLD_RES, COLD_RES_IN_TEST, COLD_NON_RES, OUT_OF_CLOCK,
  }

  final class Node {
    final long key;

    Status status;
    Node prev;
    Node next;

    boolean marked;

    public Node(long key) {
      this.key = key;
      prev = next = this;
      status = Status.OUT_OF_CLOCK;
    }

    public void moveToHead(Status status) {
      if (isInClock()) {
        removeFromClock();
      }
      if (listHead == null) {
        next = prev = this;
      } else {
        next = listHead;
        prev = listHead.prev;
        listHead.prev.next = this;
        listHead.prev = this;
      }
      setStatus(status);
      listHead = this;
    }

    public void removeFromClock() {
      if (this == listHead) {
        listHead = listHead.next;
      }
      if (this == handCold) {
        handCold = handCold.prev;
      }
      if (this == handHot) {
        handHot = handHot.prev;
      }
      if (this == handTest) {
        handTest = handTest.prev;
      }
      prev.next = next;
      next.prev = prev;
      prev = next = this;
      setStatus(Status.OUT_OF_CLOCK);
      marked = false;
    }

    public void setStatus(Status status) {
      if (this.isResident()) { sizeFree++; }
      if (this.isInTest()) { sizeInTest--; }
      if (this.isResidentCold()) { sizeResCold--; }
      if (this.status == Status.COLD_NON_RES) { sizeNonResCold--; }
      if (this.status == Status.HOT) { sizeHot--; }
      this.status = status;
      if (this.isResident()) { sizeFree--; }
      if (this.isInTest()) { sizeInTest++; }
      if (this.isResidentCold()) { sizeResCold++; }
      if (this.status == Status.COLD_NON_RES) { sizeNonResCold++; }
      if (this.status == Status.HOT) { sizeHot++; }
    }

    boolean isInTest() {
      return status == Status.COLD_RES_IN_TEST || status == Status.COLD_NON_RES;
    }
    boolean isResident() {
      return isResidentCold() || status == Status.HOT;
    }
    boolean isResidentCold() {
      return status == Status.COLD_RES || status == Status.COLD_RES_IN_TEST;
    }
    boolean isCold() {
      return isResidentCold() || status == Status.COLD_NON_RES;
    }
    boolean isHot() {
      return status == Status.HOT;
    }
    boolean isInClock() {
      return status != Status.OUT_OF_CLOCK;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("marked", marked)
          .add("type", status)
          .toString());
      if (this == handHot) {
        sb.append(" <--[ HAND_HOT ]");
      }
      if (this == handCold) {
        sb.append(" <--[ HAND_COLD ]");
      }
      if (this == handTest) {
        sb.append(" <--[ HAND_TEST ]");
      }
      return sb.toString();
    }
  }

  static final class ClockProSettings extends BasicSettings {
    public ClockProSettings(Config config) {
      super(config);
    }
    public int lowerBoundCold() {
      return config().getInt("clockpro.lower-bound-resident-cold");
    }
    public double percentMinCold() {
      return config().getDouble("clockpro.percent-min-resident-cold");
    }
    public double percentMaxCold() {
      return config().getDouble("clockpro.percent-max-resident-cold");
    }
    public double nonResidentMultiplier() {
      return config().getDouble("clockpro.non-resident-multiplier");
    }
  }
}
