/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * LIRS2 algorithm, an enhancement of LIRS that replaces the locality measure with the sum of a
 * block's two most-recent reuse distances. Each block keeps two slots in the LIRS2 stack: at any
 * point one plays instance-1 (representing the most-recent access) and the other plays instance-2
 * (the second-most-recent). On each access, the slots swap roles by flipping a single flag.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://dl.acm.org/doi/10.1145/3456727.3463772">LIRS2: An Improved LIRS Replacement
 * Algorithm</a>. A reference C++ implementation is available at
 * <a href="https://github.com/zhongch4g/LIRS2">github.com/zhongch4g/LIRS2</a>.
 * <p>
 * This port departs from the reference in two places, each chosen for memory or readability
 * without measurably affecting hit rates:
 * <ul>
 *   <li>Non-resident shadows are bounded by {@code lirs2.non-resident-multiplier} so memory cannot
 *   grow with unique-keys-seen; the reference keeps every shadow for the policy's lifetime.
 *   <li>The CoRe queue always holds the block's current instance-1 slot. The reference uses
 *   instance-1 for fresh HIR misses but instance-2 (the demoted Rmax2 slot) on demotion from LIR;
 *   the unified convention lets {@link Block#instanceInQ()} just return {@link Block#instanceOne()}.
 * </ul>
 * As in Zhong's implementation, a correlated reference — the same key accessed twice in a row — is
 * collapsed; LIRS2's role swap is not idempotent, so the repeat is scored as a hit, not replayed.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "irr.Lirs2")
@SuppressWarnings("IdentifierName")
public final class Lirs2Policy implements KeyOnlyPolicy {
  final Long2ObjectMap<Block> data;
  final PolicyStats policyStats;
  final Slot headS;
  final Slot headQ;
  final Block headNR;

  final int maximumHotSize;
  final int maximumNonResidentSize;
  final int maximumSize;
  final int maximumStackLength;

  // Rmax1: the LIR slot currently playing instance-1 at the bottom of the in-S region
  // Rmax2: the LIR slot currently playing instance-2 at the bottom of the in-S region
  @Nullable Slot rmax1;
  @Nullable Slot rmax2;

  // Cursor stays near the bottom of the in-S region to keep pruning amortized to O(1)
  @Nullable Slot cursor;

  int sizeQ;
  int sizeNR;
  int sizeHot;
  int residentSize;
  // Counts slots considered "in stack S"; i.e., physically in the list and above the Rmax markers
  // This is the threshold input to {@link #pruneStack}
  int stackLength;

  // Tracks the previous key to collapse correlated references (the same key twice in a row).
  // LIRS2's role swap is not idempotent, so a repeat is scored as a hit rather than replayed.
  // LirsPolicy needs no such guard: its transition is idempotent on a re-access.
  long lastKey = Long.MIN_VALUE;
  boolean hasLastKey;

  public Lirs2Policy(Config config) {
    this.headS = new Slot(null);
    this.headQ = new Slot(null);
    this.headNR = new Block(Long.MIN_VALUE);
    var settings = new Lirs2Settings(config);
    this.data = new Long2ObjectOpenHashMap<>();
    this.policyStats = new PolicyStats(name());
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.maximumHotSize = (int) (maximumSize * settings.percentHot());
    this.maximumStackLength = maximumSize * settings.stackLengthMultiplier();
    this.maximumNonResidentSize = (int) (maximumSize * settings.nonResidentMultiplier());
  }

  @Override
  public void record(long key) {
    if (hasLastKey && (key == lastKey)) {
      // Correlated reference: count it as a hit (a non-miss in the denominator) without replaying
      // the non-idempotent role swap, as the reference implementation's read-loop does.
      policyStats.recordOperation();
      policyStats.recordHit();
      return;
    }
    lastKey = key;
    hasLastKey = true;

    @Var @Nullable Block block = data.get(key);
    policyStats.recordOperation();
    if (block == null) {
      block = new Block(key);
      data.put(key, block);
      onMiss(block);
    } else if (block.status == Status.HIR_NON_RESIDENT) {
      block.removeFromNR();
      onAccess(block, /* wasResident= */ false);
    } else {
      onAccess(block, /* wasResident= */ true);
    }
  }

  /** Records the first-ever access of a block, seeding both of its slots. */
  private void onMiss(Block block) {
    policyStats.recordMiss();

    if (sizeHot < maximumHotSize) {
      block.status = Status.LIR;
      sizeHot++;
    } else {
      if (residentSize >= maximumSize) {
        evict();
      }
      block.status = Status.HIR_RESIDENT;
    }

    // Seed the two slots. slotA initially plays instance-1; slotB plays instance-2 as a
    // placeholder until the block's second real access fills in a true second-most-recent reuse
    // event. See {@link Slot#placeholder}.
    Slot ins1 = block.slotA;
    Slot ins2 = block.slotB;

    ins1.whichInstance1 = true;
    ins1.recency1 = true;
    ins1.recency2 = true;
    ins1.placeholder = false;

    ins2.whichInstance1 = false;
    ins2.recency1 = true;
    ins2.recency2 = true;
    ins2.placeholder = true;

    ins2.moveToTop(StackType.S);
    if (rmax2 == null) {
      // The very first slot inserted as instance-2 becomes the Rmax2 boundary marker.
      ins2.recency1 = false;
      rmax2 = ins2;
      cursor = ins2;
    }
    stackLength++;

    ins1.moveToTop(StackType.S);
    if (rmax1 == null) {
      rmax1 = ins1;
    }
    stackLength++;

    if (block.status == Status.HIR_RESIDENT) {
      ins1.moveToTop(StackType.Q);
    }
    residentSize++;
    pruneStack();
  }

  /** Records a subsequent access of a block (hit on a resident HIR/LIR, or a refault). */
  @SuppressWarnings("PMD.LocalVariableNamingConventions")
  private void onAccess(Block block, boolean wasResident) {
    if (wasResident) {
      policyStats.recordHit();
      if (block.status == Status.HIR_RESIDENT) {
        block.instanceInQ().removeFrom(StackType.Q);
      }
    } else {
      policyStats.recordMiss();
      if (residentSize >= maximumSize) {
        evict();
      }
      residentSize++;
    }

    // Locate the two slots by their current roles. The "old" labels are what they were before this
    // access; we use them to read the locality measure before the role swap takes effect.
    Slot oldIns1 = block.instanceOne();
    Slot oldIns2 = block.instanceTwo();

    boolean oldIns2_recency2 = oldIns2.recency2;
    boolean oldIns2_placeholder = oldIns2.placeholder;

    // Relabel both slots before any Rmax walk so that findNextRmax sees the new role assignments.
    // This matches Chen Zhong's order (ins1.which_instance = 2, then ins2.which_instance = 1, then
    // findLastLirLru) and lets the walk identify the prior instance-1 — now playing instance-2 —
    // as the next Rmax2 candidate when both slots belong to the same LIR block.
    oldIns1.whichInstance1 = false;
    oldIns2.whichInstance1 = true;

    if (oldIns2 == rmax2) {
      rmax2 = findNextRmax(/* instance1= */ false);
    } else if (oldIns2.recency2) {
      stackLength--;
    }
    oldIns2.placeholder = false;

    if (cursor == oldIns2) {
      cursor = oldIns2.prevS;
    }
    oldIns2.moveToTop(StackType.S);

    if (oldIns1 == rmax1) {
      rmax1 = findNextRmax(/* instance1= */ true);
    }
    stackLength++;

    if ((block.status != Status.LIR) && oldIns2_recency2 && !oldIns2_placeholder) {
      promoteToHot(block);
    } else if (block.status == Status.HIR_RESIDENT) {
      oldIns2.moveToTop(StackType.Q);
    } else if (block.status == Status.HIR_NON_RESIDENT) {
      block.status = Status.HIR_RESIDENT;
      oldIns2.moveToTop(StackType.Q);
    }

    oldIns2.recency1 = true;
    oldIns2.recency2 = true;

    pruneStack();
  }

  /**
   * Promotes the accessed block to LIR (hot) and demotes the block at the Rmax2 boundary to make
   * room. The demoted block's instance-2 slot — the marker itself — leaves the in-S region, and
   * its instance-1 slot enters the CoRe queue.
   */
  private void promoteToHot(Block block) {
    Slot demotedRmax2 = requireNonNull(rmax2);
    Block demoted = requireNonNull(demotedRmax2.block);
    checkState(demoted.status == Status.LIR);

    block.status = Status.LIR;
    demoted.status = Status.HIR_RESIDENT;
    demoted.instanceOne().moveToTop(StackType.Q);
    demotedRmax2.recency2 = false;

    if ((rmax1 != null) && (rmax1.block != null) && (rmax1.block.status != Status.LIR)) {
      rmax1 = findNextRmax(/* instance1= */ true);
    }
    rmax2 = findNextRmax(/* instance1= */ false);
  }

  /**
   * Walks the stack backward from the current marker through non-matching slots to find the next
   * LIR-block slot in the requested role. Sets recency to false on slots we walk past so they
   * exit the in-S region as the marker advances. Updates {@link #cursor} when advancing Rmax2.
   */
  private @Nullable Slot findNextRmax(boolean instance1) {
    @Var Slot cur = instance1 ? rmax1 : rmax2;
    if (cur == null) {
      return null;
    }
    while (true) {
      if (instance1) {
        cur.recency1 = false;
      } else {
        cur.recency2 = false;
        stackLength--;
      }
      Slot prev = cur.prevS;
      if ((prev == null) || (prev == headS)) {
        return null;
      }
      cur = prev;
      Block owner = cur.block;
      if ((owner != null) && (owner.status == Status.LIR)
          && (cur.whichInstance1 == instance1)) {
        if (!instance1) {
          cursor = cur;
        }
        return cur;
      }
    }
  }

  /** Evicts the bottom of the CoRe queue to non-resident status; the block's slots stay in S. */
  private void evict() {
    Slot victimSlot = requireNonNull(headQ.prevQ);
    Block victim = requireNonNull(victimSlot.block);
    checkState(victim.status == Status.HIR_RESIDENT);

    policyStats.recordEviction();
    victimSlot.removeFrom(StackType.Q);
    victim.status = Status.HIR_NON_RESIDENT;
    victim.moveToNRHead();
    residentSize--;
  }

  /**
   * Trims the LIRS2 stack using the reference's amortized-O(1) cursor walk: starting at the cursor,
   * walk upward through LIR slots looking for an HIR-block slot. When found, clear its recency
   * flags and splice it just below Rmax2 so future walks pass over it. Then bound the non-resident
   * shadow set by forgetting the oldest blocks when {@link #sizeNR} exceeds its limit. This is a
   * deviation from the reference, which keeps shadows for the policy's lifetime.
   */
  private void pruneStack() {
    while ((stackLength > maximumStackLength) && (cursor != null)) {
      @Var Slot scan = cursor;
      while ((scan != null) && (scan.block != null)
          && (scan.block.status == Status.LIR)) {
        scan = scan.prevS;
      }
      if ((scan == null) || (scan == headS)) {
        break;
      }
      cursor = scan.prevS;
      scan.recency1 = false;
      scan.recency2 = false;
      scan.removeFrom(StackType.S);
      scan.insertAfter(requireNonNull(rmax2));
      stackLength--;
    }
    while (sizeNR > maximumNonResidentSize) {
      forgetBlock(requireNonNull(headNR.prevNR));
    }
  }

  /** Removes a non-resident block's tracking entirely (slots, NR entry, and data map record). */
  private void forgetBlock(Block block) {
    block.removeFromNR();
    forgetSlot(block.slotA);
    forgetSlot(block.slotB);
    data.remove(block.key);
    policyStats.recordOperation();
  }

  private void forgetSlot(Slot slot) {
    if (slot == rmax1) {
      rmax1 = findNextRmax(/* instance1= */ true);
    }
    if (slot == rmax2) {
      rmax2 = findNextRmax(/* instance1= */ false);
    } else if (slot.recency2) {
      stackLength--;
    }
    if (cursor == slot) {
      cursor = slot.prevS;
    }
    if (slot.inS) {
      slot.removeFrom(StackType.S);
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    long resident = data.values().stream()
        .filter(block -> block.status != Status.HIR_NON_RESIDENT)
        .count();
    checkState(resident == residentSize,
        "resident: expected %s but was %s", residentSize, resident);
    checkState(sizeHot <= maximumHotSize);
    checkState(residentSize <= maximumSize);
    checkState(sizeNR <= maximumNonResidentSize);
  }

  enum Status {
    LIR,
    HIR_RESIDENT,
    HIR_NON_RESIDENT
  }

  enum StackType {
    S,
    Q
  }

  /** A block represents a unique key, with two slots used as LIRS2 stack entries. */
  final class Block {
    final long key;
    final Slot slotA;
    final Slot slotB;

    @Nullable Status status;
    @Nullable Block prevNR;
    @Nullable Block nextNR;
    boolean inNR;

    Block(long key) {
      this.key = key;
      this.slotA = new Slot(this);
      this.slotB = new Slot(this);
      if (key == Long.MIN_VALUE) {
        prevNR = nextNR = this;
      }
    }

    /** Moves this block to the head of the NR queue. */
    void moveToNRHead() {
      if (inNR) {
        removeFromNR();
      }
      Block next = requireNonNull(headNR.nextNR);
      headNR.nextNR = this;
      next.prevNR = this;
      this.nextNR = next;
      this.prevNR = headNR;
      inNR = true;
      sizeNR++;
    }

    void removeFromNR() {
      checkState(inNR);
      requireNonNull(prevNR);
      requireNonNull(nextNR);
      prevNR.nextNR = nextNR;
      nextNR.prevNR = prevNR;
      prevNR = nextNR = null;
      inNR = false;
      sizeNR--;
    }

    Slot instanceOne() {
      return slotA.whichInstance1 ? slotA : slotB;
    }

    Slot instanceTwo() {
      return slotA.whichInstance1 ? slotB : slotA;
    }

    /** Returns the slot currently in stack Q for this HIR_RESIDENT block. */
    Slot instanceInQ() {
      Slot ins1 = instanceOne();
      checkState(ins1.inQ);
      return ins1;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("status", status)
          .toString();
    }
  }

  /** A stack slot representing one access role of a block. */
  final class Slot {
    final @Nullable Block block;

    @Nullable Slot prevS;
    @Nullable Slot nextS;
    @Nullable Slot prevQ;
    @Nullable Slot nextQ;

    boolean inS;
    boolean inQ;

    // If this slot currently plays the instance-1 role for its block
    boolean whichInstance1;
    // If an instance-2 slot whose block has only been accessed once. The paper creates instance 2
    // lazily on a block's second access, but we pre-allocate it as a placeholder so that Rmax2 can
    // be set at the bottom of S at first miss and tracked incrementally without an expensive list
    // walk. The placeholder flag clears on the first real second-access event, which is also the
    // first opportunity for the block to be hot-promoted.
    boolean placeholder;
    // recency1/recency2: this slot is at or above the Rmax1/Rmax2 boundary in stack S
    boolean recency1;
    boolean recency2;

    Slot(@Nullable Block block) {
      this.block = block;
      if (block == null) {
        prevS = nextS = this;
        prevQ = nextQ = this;
      }
    }

    /** Inserts (or relocates) this slot at the top of the requested stack. */
    void moveToTop(StackType stackType) {
      if (isIn(stackType)) {
        removeFrom(stackType);
      }
      switch (stackType) {
        case S -> {
          Slot head = headS;
          Slot next = requireNonNull(head.nextS);
          head.nextS = this;
          next.prevS = this;
          this.nextS = next;
          this.prevS = head;
          inS = true;
        }
        case Q -> {
          Slot head = headQ;
          Slot next = requireNonNull(head.nextQ);
          head.nextQ = this;
          next.prevQ = this;
          this.nextQ = next;
          this.prevQ = head;
          inQ = true;
          sizeQ++;
        }
      }
    }

    /** Splices this slot into the S list immediately after the given anchor (i.e., below it). */
    void insertAfter(Slot anchor) {
      checkState(!inS);
      Slot below = requireNonNull(anchor.nextS);
      anchor.nextS = this;
      below.prevS = this;
      this.prevS = anchor;
      this.nextS = below;
      inS = true;
    }

    void removeFrom(StackType stackType) {
      checkState(isIn(stackType));
      switch (stackType) {
        case S -> {
          requireNonNull(prevS);
          requireNonNull(nextS);
          prevS.nextS = nextS;
          nextS.prevS = prevS;
          prevS = nextS = null;
          inS = false;
        }
        case Q -> {
          requireNonNull(prevQ);
          requireNonNull(nextQ);
          prevQ.nextQ = nextQ;
          nextQ.prevQ = prevQ;
          prevQ = nextQ = null;
          inQ = false;
          sizeQ--;
        }
      }
    }

    boolean isIn(StackType stackType) {
      return switch (stackType) {
        case S -> inS;
        case Q -> inQ;
      };
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("block", (block == null) ? "sentinel" : block.key)
          .add("whichInstance1", whichInstance1)
          .add("recency1", recency1)
          .add("recency2", recency2)
          .toString();
    }
  }

  static final class Lirs2Settings extends BasicSettings {
    public Lirs2Settings(Config config) {
      super(config);
    }
    public double percentHot() {
      return config().getDouble("lirs2.percent-hot");
    }
    public double nonResidentMultiplier() {
      return config().getDouble("lirs2.non-resident-multiplier");
    }
    public int stackLengthMultiplier() {
      return config().getInt("lirs2.stack-length-multiplier");
    }
  }
}
