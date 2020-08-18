/*
 * Copyright 2018 Ben Manes and Ohad Eytan. All Rights Reserved.
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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Hill climber version of FRD
 *
 * @author ohadey@gmail.com (Ohad Eytan)
 */
@SuppressWarnings("PMD.TooManyFields")
public final class HillClimberFrdPolicy implements KeyOnlyPolicy {
  final Long2ObjectOpenHashMap<Node> data;
  final PolicyStats policyStats;
  final Node headFilter;
  final Node headMain;

  int maximumMainResidentSize;
  int maximumFilterSize;
  final int maximumSize;

  int residentSize;
  int residentFilter;
  int residentMain;

  private final int pivot;
  private int sample;
  private final int sampleSize;
  private int hitsInSample;
  private int missesInSample;
  private double previousHitRate;
  private final double tolerance;
  private boolean increaseWindow;

  public HillClimberFrdPolicy(Config config) {
    FrdSettings settings = new FrdSettings(config);
    this.maximumSize = Ints.checkedCast(settings.maximumSize());
    this.maximumMainResidentSize = (int) (maximumSize * settings.percentMain());
    this.maximumFilterSize = maximumSize - maximumMainResidentSize;
    this.policyStats = new PolicyStats("irr.AdaptiveFrd");
    this.data = new Long2ObjectOpenHashMap<>();
    this.headFilter = new Node();
    this.headMain = new Node();

    this.pivot = (int) (0.05 * maximumSize);
    this.sampleSize = 10 * maximumSize;
    this.tolerance = 100d * 0;
  }

  /**
   * Returns all variations of this policy based on the configuration parameters.
   */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new HillClimberFrdPolicy(config));
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    adapt();

    Node node = data.get(key);
    if (node == null) {
      node = new Node(key);
      data.put(key, node);
      onMiss(node);
    } else if (node.status == Status.FILTER) {
      onFilterHit(node);
    } else if (node.status == Status.MAIN) {
      onMainHit(node);
    } else if (node.status == Status.NON_RESIDENT) {
      if (residentMain < maximumMainResidentSize) {
        checkState(residentFilter > maximumFilterSize);
        adaptFilterToMain(node);
      } else {
        onNonResidentHit(node);
      }
    } else {
      throw new IllegalStateException();
    }
  }

  private void adapt() {
    if (sample >= sampleSize) {
      double hitRate = (100d * hitsInSample) / (hitsInSample + missesInSample);
      if (!Double.isNaN(hitRate) && !Double.isInfinite(hitRate) && (previousHitRate != 0.0)) {
        if (hitRate < (previousHitRate + tolerance)) {
          increaseWindow = !increaseWindow;
        }
        if (increaseWindow) {
          maximumFilterSize += pivot;
        } else {
          maximumFilterSize -= pivot;
        }
        if (maximumFilterSize <= 0) {
          maximumFilterSize = 1;
        }
        if (maximumFilterSize >= maximumSize) {
          maximumFilterSize = maximumSize - 1;
        }
        maximumMainResidentSize = maximumSize - maximumFilterSize;
      }
      previousHitRate = hitRate;

      missesInSample = 0;
      hitsInSample = 0;
      sample = 0;
    }
  }

  private void onMiss(Node node) {
    /**
     * Initially, both the filter and reuse distance stacks are filled with newly arrived blocks
     * from the reuse distance stack to the filter stack
     */
    policyStats.recordMiss();
    missesInSample++;
    sample++;

    if (residentSize < maximumMainResidentSize) {
      onMainWarmupMiss(node);
      residentSize++;
      residentMain++;
    } else if (residentSize < maximumSize) {
      onFilterWarmupMiss(node);
      residentSize++;
      residentFilter++;
    } else if (residentFilter < maximumFilterSize) {
      adaptMainToFilter(node);
    } else {
      onFullMiss(node);
    }
  }

  private void adaptMainToFilter(Node node) {
    /**
     * Cache miss and history miss with adaptation:
     * Evict from main stack. Then, insert to filter stack
     */
    policyStats.recordEviction();

    pruneStack();
    Node victim = headMain.prevMain;
    victim.removeFrom(StackType.MAIN);
    data.remove(victim.key);
    pruneStack();
    residentMain--;

    node.moveToTop(StackType.FILTER);
    node.moveToTop(StackType.MAIN);
    node.status = Status.FILTER;
    residentFilter++;
  }

  private void adaptFilterToMain(Node node) {
    /**
     * Cache miss and history hit with adaptation:
     * Evict from filter stack. Then insert to main stack
     */
    policyStats.recordEviction();
    policyStats.recordMiss();
    missesInSample++;
    sample++;

    Node victim = headFilter.prevFilter;
    victim.removeFrom(StackType.FILTER);
    if (victim.isInMain) {
      victim.status = Status.NON_RESIDENT;
    } else {
      data.remove(victim.key);
    }
    residentFilter--;

    node.moveToTop(StackType.MAIN);
    node.status = Status.MAIN;
    data.put(node.key, node);
    residentMain++;
  }

  private void onMainWarmupMiss(Node node) {
    node.moveToTop(StackType.MAIN);
    node.status = Status.MAIN;
  }

  private void onFilterWarmupMiss(Node node) {
    node.moveToTop(StackType.FILTER);
    node.status = Status.FILTER;
  }

  private void onFullMiss(Node node) {
    /**
     * Cache miss and history miss: Evict the oldest block in the filter stack. Then, insert the
     * missed block into the filter stack and generate a history block for the missed block. In
     * addition, insert the history block into the reuse distance stack. No eviction occurs in the
     * reuse distance stack because the history block contains only metadata.
     */
    policyStats.recordEviction();

    Node victim = headFilter.prevFilter;
    victim.removeFrom(StackType.FILTER);
    if (victim.isInMain) {
      victim.status = Status.NON_RESIDENT;
    } else {
      data.remove(victim.key);
    }

    node.moveToTop(StackType.FILTER);
    node.moveToTop(StackType.MAIN);
    node.status = Status.FILTER;
  }

  private void onFilterHit(Node node) {
    /**
     * Cache hit in the filter stack: Move the corresponding block to the MRU position of the filter
     * stack. The associated history block should be updated to maintain reuse distance order (i.e.,
     * move its history block in the reuse distance stack to the MRU position of the reuse distance
     * stack).
     */
    policyStats.recordHit();
    hitsInSample++;
    sample++;

    node.moveToTop(StackType.FILTER);
    node.moveToTop(StackType.MAIN);
  }

  private void onMainHit(Node node) {
    /**
     * Cache hit in the reuse distance stack: Move the corresponding block to the MRU position of
     * the reuse distance stack. If the corresponding block is in the LRU position of the reuse
     * distance stack (i.e., the oldest resident block), the history blocks between the LRU position
     * and the 2nd oldest resident block are removed. Otherwise, no history block removing occurs.
     */
    policyStats.recordHit();
    hitsInSample++;
    sample++;

    boolean wasBottom = (headMain.prevMain == node);
    node.moveToTop(StackType.MAIN);
    if (wasBottom) {
      pruneStack();
    }
  }

  private void pruneStack() {
    for (;;) {
      Node bottom = headMain.prevMain;
      if ((bottom == headMain) || (bottom.status == Status.MAIN)) {
        break;
      } else if (bottom.status == Status.FILTER) {
        policyStats.recordOperation();
        bottom.removeFrom(StackType.MAIN);
      } else if (bottom.status == Status.NON_RESIDENT) {
        policyStats.recordOperation();
        bottom.removeFrom(StackType.MAIN);
        data.remove(bottom.key);
      }
    }
  }

  private void onNonResidentHit(Node node) {
    /**
     * Cache miss but history hit: Remove all history blocks between the 2nd oldest and the oldest
     * resident blocks. Next, evict the oldest resident block from the reuse distance stack. Then,
     * move the history hit block to the MRU position in the reuse distance stack and change it to a
     * resident block. No insertion or eviction occurs in the filter stack.
     */
    policyStats.recordEviction();
    policyStats.recordMiss();
    missesInSample++;
    sample++;

    pruneStack();
    Node victim = headMain.prevMain;
    victim.removeFrom(StackType.MAIN);
    data.remove(victim.key);
    pruneStack();

    node.moveToTop(StackType.MAIN);
    node.status = Status.MAIN;
    data.put(node.key, node);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    long filterSize = data.values().stream().filter(node -> node.status == Status.FILTER).count();
    long mainSize = data.values().stream().filter(node -> node.status == Status.MAIN).count();

    checkState((filterSize + mainSize) <= maximumSize);
    checkState(residentFilter == filterSize);
    checkState(residentMain == mainSize);
  }

  enum Status {
    NON_RESIDENT, FILTER, MAIN,
  }

  enum StackType {
    FILTER, // holds all of the resident filter blocks
    MAIN,   // holds all of the resident and non-resident blocks
  }

  final class Node {
    final long key;

    Status status;

    Node prevFilter;
    Node nextFilter;
    Node prevMain;
    Node nextMain;

    boolean isInFilter;
    boolean isInMain;

    Node() {
      key = Long.MIN_VALUE;
      prevMain = nextMain = this;
      prevFilter = nextFilter = this;
    }

    Node(long key) {
      this.key = key;
    }

    public boolean isInStack(StackType stackType) {
      checkState(key != Long.MIN_VALUE);

      if (stackType == StackType.FILTER) {
        return isInFilter;
      } else if (stackType == StackType.MAIN) {
        return isInMain;
      }
      throw new IllegalArgumentException();
    }

    public void moveToTop(StackType stackType) {
      if (isInStack(stackType)) {
        removeFrom(stackType);
      }

      if (stackType == StackType.FILTER) {
        Node next = headFilter.nextFilter;
        headFilter.nextFilter = this;
        next.prevFilter = this;
        this.nextFilter = next;
        this.prevFilter = headFilter;
        isInFilter = true;
      } else if (stackType == StackType.MAIN) {
        Node next = headMain.nextMain;
        headMain.nextMain = this;
        next.prevMain = this;
        this.nextMain = next;
        this.prevMain = headMain;
        isInMain = true;
      } else {
        throw new IllegalArgumentException();
      }
    }

    public void removeFrom(StackType stackType) {
      checkState(isInStack(stackType));

      if (stackType == StackType.FILTER) {
        prevFilter.nextFilter = nextFilter;
        nextFilter.prevFilter = prevFilter;
        prevFilter = nextFilter = null;
        isInFilter = false;
      } else if (stackType == StackType.MAIN) {
        prevMain.nextMain = nextMain;
        nextMain.prevMain = prevMain;
        prevMain = nextMain = null;
        isInMain = false;
      } else {
        throw new IllegalArgumentException();
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("type", status)
          .toString();
    }
  }

  static final class FrdSettings extends BasicSettings {
    public FrdSettings(Config config) {
      super(config);
    }

    public double percentMain() {
      return config().getDouble("frd.percent-main");
    }
  }
}
