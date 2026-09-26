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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sim;

import static com.github.benmanes.caffeine.cache.simulator.admission.Admission.CLAIRVOYANT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

/**
 * A MiniSim variant that follows the miniature with the fewest recent misses. Each window target is
 * simulated by a miniature cache fed a hash sample of the requests (all of them when the cache is
 * no larger than the miniature), each target's misses are discounted over a horizon, and the window
 * moves to a target once its discounted misses fall below the current target's.
 * <p>
 * The miniatures observe every target's hit rate, which the adaptive window can only infer from
 * the one split it runs. That makes this climber a reference for what an online policy that sees
 * the counterfactual can earn, rather than a candidate for the library, as its cost grows with the
 * number of targets and the cache size.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MiniSimLeaderClimber implements HillClimber {
  /** The trace's sample period as a multiple of the maximum, which is the density climber's. */
  static final int SAMPLE_MULTIPLIER = 4;

  private final WindowTinyLfuPolicy[] minis;
  private final @Nullable PrintStream trace;
  private final long[] targetWindows;
  private final long[] sampleMisses;
  private final long samplePeriod;
  private final int samplingRate;
  private final double[] misses;
  private final double discount;
  private long unfilledMisses;
  private long sampleRequests;
  private final int halfFull;
  private long firstSample;

  private boolean pending;
  private boolean filled;
  private long requests;
  private int leader;

  public MiniSimLeaderClimber(double percentMain, Config config) {
    this(percentMain, config, System.err);
  }

  MiniSimLeaderClimber(double percentMain, Config config, PrintStream traceOut) {
    var settings = new MiniSimLeaderSettings(config);
    int cacheSize = Math.toIntExact(settings.maximumSize());
    samplingRate = samplingRate(cacheSize, settings.miniatureSize());
    int miniSize = cacheSize / samplingRate;
    var targets = MiniSimClimber.targets(cacheSize, miniSize, percentMain,
        settings.percentMainProtected(), settings.percentWindows());
    targetWindows = targets.stream().mapToLong(target -> target.full().maximumWindow()).toArray();
    minis = MiniSimClimber.miniatures(targets, miniSize, config);
    discount = 1.0 - (samplingRate / (settings.horizon() * cacheSize));
    samplePeriod = SAMPLE_MULTIPLIER * (long) cacheSize;
    trace = settings.traceWindows() ? traceOut : null;
    sampleMisses = new long[minis.length];
    misses = new double[minis.length];
    firstSample = Long.MAX_VALUE;
    halfFull = cacheSize >>> 1;

    checkState(!CLAIRVOYANT.name().equalsIgnoreCase(settings.tinyLfu().sketch()),
        "minisim-leader records only a sampled subset of the accesses, so it cannot use the "
            + "clairvoyant sketch");
  }

  /** Returns MiniSim's sampling rate, lowered so that each miniature has the minimum size. */
  static int samplingRate(int cacheSize, int miniatureSize) {
    return Math.clamp(cacheSize / Math.max(1, miniatureSize),
        1, MiniSimClimber.samplingRate(cacheSize));
  }

  @Override
  public void onHit(long key, QueueType queue, boolean isFull) {
    onAccess(key, isFull);
  }

  @Override
  public void onMiss(long key, boolean isFull) {
    // The density climber's clock starts when an insertion makes the cache half full
    if (!isFull && (++unfilledMisses == halfFull)) {
      firstSample = samplePeriod * Math.ceilDiv(requests + 1, samplePeriod);
    }
    onAccess(key, isFull);
  }

  private void onAccess(long key, boolean isFull) {
    requests++;
    filled |= isFull;
    if (MiniSimClimber.isSampled(key, samplingRate)) {
      replay(key);
    }
    if ((requests % samplePeriod) == 0) {
      closeSample();
    }
  }

  /** Replays a sampled request into every miniature, scoring them once the cache is full. */
  private void replay(long key) {
    var event = AccessEvent.forKey(key);
    sampleRequests++;
    for (int i = 0; i < minis.length; i++) {
      long before = minis[i].stats().missCount();
      minis[i].record(event);
      long missed = minis[i].stats().missCount() - before;
      sampleMisses[i] += missed;
      if (filled) {
        misses[i] = (discount * misses[i]) + missed;
      }
    }
    if (filled) {
      follow();
    }
  }

  /** Traces each target's misses over the sample once the density climber's clock has started. */
  private void closeSample() {
    if ((trace != null) && (requests >= firstSample)) {
      var targets = new StringJoiner(",");
      for (int i = 0; i < minis.length; i++) {
        targets.add(targetWindows[i] + ":" + sampleMisses[i]);
      }
      trace.printf(US, "ghost s=%d win=%d n=%d misses=%s%n",
          (requests - firstSample) / samplePeriod, targetWindows[leader], sampleRequests, targets);
    }
    Arrays.fill(sampleMisses, 0);
    sampleRequests = 0;
  }

  /** Selects the target with the fewest discounted misses, keeping the current one on a tie. */
  private void follow() {
    @Var int best = leader;
    for (int i = 0; i < misses.length; i++) {
      if (misses[i] < misses[best]) {
        best = i;
      }
    }
    if (best != leader) {
      leader = best;
      pending = true;
    }
  }

  @Override
  public Adaptation adapt(double windowSize,
      double probationSize, double protectedSize, boolean isFull) {
    if (!pending) {
      return Adaptation.hold();
    }
    pending = false;
    return MiniSimClimber.adaptToward(targetWindows[leader], windowSize);
  }

  static final class MiniSimLeaderSettings extends BasicSettings {
    public MiniSimLeaderSettings(Config config) {
      super(config);
    }
    public List<Integer> percentWindows() {
      return config().getIntList("hill-climber-window-tiny-lfu.minisim-leader.percent-windows");
    }
    public double percentMainProtected() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-main-protected");
    }
    public double horizon() {
      double horizon = config().getDouble("hill-climber-window-tiny-lfu.minisim-leader.horizon");
      checkArgument(horizon > 0, "The horizon must be positive: %s", horizon);
      return horizon;
    }
    public int miniatureSize() {
      return config().getInt("hill-climber-window-tiny-lfu.minisim-leader.miniature-size");
    }
    public boolean traceWindows() {
      return config().getBoolean("hill-climber-window-tiny-lfu.minisim-leader.trace");
    }
  }
}
