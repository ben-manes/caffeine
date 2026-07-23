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
package com.github.benmanes.caffeine.cache.simulator.policy.adaptive;

import static com.google.common.base.Preconditions.checkState;

import java.util.LinkedHashMap;
import java.util.NavigableSet;
import java.util.Random;
import java.util.SequencedMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

/**
 * Cacheus is a machine-learned adaptive caching algorithm that runs two experts in parallel and
 * uses regret minimization to pick between their eviction proposals. The experts used here are the
 * paper's recommended ones: SR-LRU (a scan-resistant LRU variant) and CR-LFU (a churn-resistant LFU
 * variant where ties on frequency are broken by evicting the most-recently-used entry). The cache
 * is partitioned into a probationary list ({@code q}, the paper's SR) and a protected list
 * ({@code s}, the paper's R). Newly-inserted items enter q; they are promoted to s on a hit. Items
 * demoted from s when it overflows are flagged so that a subsequent hit on a demoted item shrinks
 * q's target size, and a history hit on a never-reused item grows it. Each expert maintains an
 * LRU-ordered history of evicted entries; the weight of an expert is decreased on a history hit
 * (it was wrong to evict the entry). A hill climber adapts the learning rate at each
 * {@code N}-request window using the gradient of the recent hit rate.
 * <p>
 * This implementation matches the reference at
 * <a href="https://github.com/sylab/cacheus">sylab/cacheus</a>, accompanying the paper
 * <a href="https://www.usenix.org/conference/fast21/presentation/rodriguez">Learning Cache
 * Replacement with CACHEUS</a> (Rodriguez et al., FAST '21). The reference contains several
 * specifics not in the paper: an LFU heap covering both partitions, an initial probationary
 * target of {@code max(1, 0.01 * N)}, a deterministic initial learning rate of
 * {@code sqrt(2 ln 2 / N)}, weight clamping to {@code [0.01, 0.99]}, and a two-counter
 * (zero / negative hit-rate change) reset path for the learning rate.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "adaptive.Cacheus")
public final class CacheusPolicy implements KeyOnlyPolicy {
  private static final double W_CLAMP_HIGH = 0.99;
  private static final double W_CLAMP_LOW = 0.01;
  private static final double HIRS_RATIO = 0.01;
  private static final double LR_MIN = 0.001;
  private static final double LR_MAX = 1.0;
  private static final int RESET_THRESHOLD = 10;

  private final PolicyStats policyStats;
  private final int maximumSize;
  private final int historySize;

  // Resident partitions: s = protected (paper R); q = probationary (paper SR)
  private final SequencedMap<Long, Node> s;
  private final SequencedMap<Long, Node> q;

  // Single LFU view over the resident set, ordered (freq ASC, time DESC, key ASC)
  private final NavigableSet<Node> lfu;

  // Histories of items evicted by the LRU and LFU experts
  private final SequencedMap<Long, Node> lruHist;
  private final SequencedMap<Long, Node> lfuHist;

  // Expert weights for the regret-minimisation step
  private double wLru;
  private double wLfu;

  // Targets for s and q; sLimit + qLimit == maximumSize is maintained
  private int sLimit;
  private int qLimit;
  private int demCount; // demoted items currently resident in q
  private int norCount; // "new" items currently in the LRU history

  // Hill climber state for the learning rate.
  private final double learningRateReset;
  private double learningRateCurr;
  private double learningRatePrev;
  private double learningRate;
  private double hitRatePrev;
  private int windowHits;
  private int zeroCount;
  private int negaCount;

  private final Random random;
  private long time;

  public CacheusPolicy(Config config) {
    var settings = new BasicSettings(config);
    this.maximumSize = Math.toIntExact(settings.maximumSize());
    this.policyStats = new PolicyStats(name());

    this.s = new LinkedHashMap<>();
    this.q = new LinkedHashMap<>();
    this.lruHist = new LinkedHashMap<>();
    this.lfuHist = new LinkedHashMap<>();
    this.lfu = new TreeSet<>(CacheusPolicy::compareLfu);

    this.historySize = maximumSize / 2;
    this.qLimit = Math.max(1, (int) Math.round(HIRS_RATIO * maximumSize));
    this.sLimit = maximumSize - qLimit;
    checkState(sLimit >= 1, "maximum size %s is too small to divide between its lists", maximumSize);

    this.wLru = 0.5;
    this.wLfu = 0.5;

    this.learningRate = Math.sqrt((2.0 * Math.log(2.0)) / maximumSize);
    this.learningRateReset = Math.clamp(learningRate, LR_MIN, LR_MAX);
    this.learningRateCurr = learningRate;
    this.learningRatePrev = 0.0;

    this.random = new Random(settings.randomSeed());
  }

  /** CR-LFU ordering: lowest freq first; among ties, the most recently used is evicted. */
  private static int compareLfu(Node a, Node b) {
    int byFreq = Integer.compare(a.freq, b.freq);
    if (byFreq != 0) {
      return byFreq;
    }
    int byTime = Long.compare(b.time, a.time);
    return (byTime == 0) ? Long.compare(a.key, b.key) : byTime;
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    time++;
    updateLearningRate();

    @Var @Nullable Node node;
    if ((node = s.get(key)) != null) {
      hitInS(node);
      policyStats.recordHit();
      windowHits++;
    } else if ((node = q.get(key)) != null) {
      hitInQ(node);
      policyStats.recordHit();
      windowHits++;
    } else if ((node = lruHist.get(key)) != null) {
      hitInLruHist(node);
      policyStats.recordMiss();
    } else if ((node = lfuHist.get(key)) != null) {
      hitInLfuHist(node);
      policyStats.recordMiss();
    } else {
      miss(key);
      policyStats.recordMiss();
    }
  }

  private void hitInS(Node node) {
    // Re-position in the LFU heap before mutating ordering fields
    lfu.remove(node);
    node.time = time;
    node.freq++;
    lfu.add(node);

    moveToMru(s, node);
  }

  private void hitInQ(Node node) {
    // The reference does not reset isNew here (see the TODO at the top of its Cacheus_Entry).
    // Matching that for fidelity; it has a small impact on how often a re-hit q item later
    // counts as "new" in the LRU history's norCount tally.
    lfu.remove(node);
    node.time = time;
    node.freq++;
    lfu.add(node);

    if (node.isDemoted) {
      adjustSize(/* hitInQ= */ true);
      node.isDemoted = false;
      demCount--;
    }
    q.remove(node.key);

    // Promote q hit to s, demoting s's LRU if s is at its target.
    if (s.size() >= sLimit) {
      Node demoted = pollLru(s);
      demoted.isDemoted = true;
      demCount++;
      q.put(demoted.key, demoted);
    }
    s.put(node.key, node);
  }

  private void hitInLruHist(Node entry) {
    lruHist.remove(entry.key);
    if (entry.isNew) {
      norCount--;
      entry.isNew = false;
      adjustSize(/* hitInQ= */ false);
    }
    adjustWeights(-1.0, 0.0);

    if ((s.size() + q.size()) >= maximumSize) {
      evict();
    }
    addToS(entry.key, entry.freq, /* isNew= */ false);
    limitStack();
  }

  private void hitInLfuHist(Node entry) {
    lfuHist.remove(entry.key);
    adjustWeights(0.0, -1.0);

    if ((s.size() + q.size()) >= maximumSize) {
      evict();
    }
    addToS(entry.key, entry.freq, /* isNew= */ false);
    limitStack();
  }

  private void miss(long key) {
    if ((s.size() < sLimit) && q.isEmpty()) {
      addToS(key, /* freq= */ 1, /* isNew= */ false);
    } else if (((s.size() + q.size()) < maximumSize) && (q.size() < qLimit)) {
      addToQ(key, /* freq= */ 1, /* isNew= */ false);
    } else {
      if ((s.size() + q.size()) >= maximumSize) {
        evict();
      }
      addToQ(key, /* freq= */ 1, /* isNew= */ true);
      limitStack();
    }
  }

  private void addToS(long key, int freq, boolean isNew) {
    var node = new Node(key, freq, time, isNew);
    s.put(key, node);
    lfu.add(node);
  }

  private void addToQ(long key, int freq, boolean isNew) {
    var node = new Node(key, freq, time, isNew);
    q.put(key, node);
    lfu.add(node);
  }

  /** Demotes from {@code s} into {@code q} until {@code s} is below its target. */
  private void limitStack() {
    while (s.size() >= sLimit) {
      Node demoted = pollLru(s);
      demoted.isDemoted = true;
      demCount++;
      q.put(demoted.key, demoted);
    }
  }

  /**
   * Chooses a victim using both experts and removes it from the cache. Records the eviction in the
   * chosen expert's history (or in neither, if the experts agreed).
   */
  private void evict() {
    Node lruCandidate = q.firstEntry().getValue();
    Node lfuCandidate = lfu.first();

    // Consume the random draw unconditionally so the RNG sequence matches the reference,
    // which calls getChoice() before checking whether the experts agree
    @Var int policy = (random.nextDouble() < wLru) ? 0 : 1;
    Node evicted;
    if (lruCandidate == lfuCandidate) {
      evicted = lruCandidate;
      policy = -1;
    } else if (policy == 0) {
      evicted = lruCandidate;
      q.remove(evicted.key);
    } else {
      evicted = lfuCandidate;
      if (s.remove(evicted.key) == null) {
        q.remove(evicted.key);
      }
    }

    if (evicted.isDemoted) {
      demCount--;
      evicted.isDemoted = false;
    }
    if (policy == -1) {
      q.remove(evicted.key);
    }
    lfu.remove(evicted);
    addToHistory(evicted, policy);
    policyStats.recordEviction();
  }

  private void addToHistory(Node node, int policy) {
    SequencedMap<Long, Node> hist;
    boolean isLruHist;
    if (policy == 0) {
      hist = lruHist;
      isLruHist = true;
      if (node.isNew) {
        norCount++;
      }
    } else if (policy == 1) {
      hist = lfuHist;
      isLruHist = false;
    } else {
      return; // Both experts agreed; no penalty marker recorded
    }

    if (hist.size() == historySize) {
      Node oldest = hist.pollFirstEntry().getValue();
      if (isLruHist && oldest.isNew) {
        norCount--;
      }
    }
    hist.put(node.key, node);
  }

  /** Multiplicative-weights update with normalisation and clamping. */
  private void adjustWeights(double rewardLru, double rewardLfu) {
    wLru *= Math.exp(learningRate * rewardLru);
    wLfu *= Math.exp(learningRate * rewardLfu);
    double sum = wLru + wLfu;
    wLru /= sum;
    wLfu /= sum;
    if (wLru >= W_CLAMP_HIGH) {
      wLru = W_CLAMP_HIGH;
      wLfu = W_CLAMP_LOW;
    } else if (wLfu >= W_CLAMP_HIGH) {
      wLfu = W_CLAMP_HIGH;
      wLru = W_CLAMP_LOW;
    }
  }

  /** Grows or shrinks the q (SR) target. */
  private void adjustSize(boolean hitInQ) {
    if (hitInQ) {
      // Demoted item was reused — q was too generous, shift capacity to s
      int dem = Math.max(1, demCount);
      int delta = Math.max(1, (int) Math.round((double) norCount / dem));
      sLimit = Math.min(maximumSize - 1, sLimit + delta);
      qLimit = maximumSize - sLimit;
    } else {
      // New item was hit in history — q was too small, give it more room
      int nor = Math.max(1, norCount);
      int delta = Math.max(1, (int) Math.round((double) demCount / nor));
      qLimit = Math.min(maximumSize - 1, qLimit + delta);
      sLimit = maximumSize - qLimit;
    }
  }

  /**
   * Stochastic-gradient hill climber over the learning rate, run at every {@code N}-request
   * boundary. Mirrors the reference: a deterministic move when sign(δλ · δHR) is non-zero, else a
   * counter-gated reset or random nudge.
   */
  private void updateLearningRate() {
    if ((time % maximumSize) != 0) {
      return;
    }

    double deltaLr = round3(learningRateCurr) - round3(learningRatePrev);
    double hitRateCurr = round3(windowHits / (double) maximumSize);
    double hitRateDiff = round3(hitRateCurr - hitRatePrev);
    double product = deltaLr * hitRateDiff;
    double delta = (int) Math.signum(product);

    if (delta > 0) {
      learningRate = Math.min(learningRate + Math.abs(learningRate * deltaLr), LR_MAX);
      negaCount = 0;
      zeroCount = 0;
    } else if (delta < 0) {
      learningRate = Math.max(learningRate - Math.abs(learningRate * deltaLr), LR_MIN);
      negaCount = 0;
      zeroCount = 0;
    } else if (hitRateDiff <= 0) {
      if ((hitRateCurr <= 0) && (hitRateDiff == 0)) {
        zeroCount++;
      }
      if (hitRateDiff < 0) {
        negaCount++;
        zeroCount++;
      }
      if (zeroCount >= RESET_THRESHOLD) {
        learningRate = learningRateReset;
        zeroCount = 0;
      } else if (hitRateDiff < 0) {
        if (negaCount >= RESET_THRESHOLD) {
          learningRate = learningRateReset;
          negaCount = 0;
        } else {
          nudgeLearningRate();
        }
      }
    }

    learningRatePrev = learningRateCurr;
    learningRateCurr = learningRate;
    hitRatePrev = hitRateCurr;
    windowHits = 0;
  }

  /** A small random walk used when the gradient is flat but the hit rate has degraded. */
  private void nudgeLearningRate() {
    if (learningRate >= LR_MAX) {
      learningRate = 0.9;
    } else if (learningRate <= LR_MIN) {
      learningRate = 0.005;
    } else if (random.nextBoolean()) {
      learningRate = Math.min(learningRate * 1.25, LR_MAX);
    } else {
      learningRate = Math.max(learningRate * 0.75, LR_MIN);
    }
  }

  private static double round3(double x) {
    return Math.round(x * 1000.0) / 1000.0;
  }

  private static Node pollLru(SequencedMap<Long, Node> deque) {
    return deque.pollFirstEntry().getValue();
  }

  private static void moveToMru(SequencedMap<Long, Node> deque, Node node) {
    deque.remove(node.key);
    deque.putLast(node.key, node);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    checkState((s.size() + q.size()) <= maximumSize,
        "resident size %s exceeds maximum %s", s.size() + q.size(), maximumSize);
    checkState(lfu.size() == (s.size() + q.size()),
        "lfu %s != resident %s", lfu.size(), s.size() + q.size());
    checkState(lruHist.size() <= historySize, "lruHist %s > %s", lruHist.size(), historySize);
    checkState(lfuHist.size() <= historySize, "lfuHist %s > %s", lfuHist.size(), historySize);
  }

  static final class Node {
    final long key;

    int freq;
    long time;
    boolean isNew;
    boolean isDemoted;

    Node(long key, int freq, long time, boolean isNew) {
      this.key = key;
      this.freq = freq;
      this.time = time;
      this.isNew = isNew;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("freq", freq)
          .add("time", time)
          .add("isNew", isNew)
          .add("isDemoted", isDemoted)
          .toString();
    }
  }
}
