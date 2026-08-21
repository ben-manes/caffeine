/*
 * Copyright 2018 Ohad Eytan. All Rights Reserved.
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A MinSim version for W-TinyLFU.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://www.usenix.org/system/files/conference/atc17/atc17-waldspurger.pdf">Cache
 * Modeling and Optimization using Miniature Simulation</a>.
 *
 * @author ohadey@gmail.com (Ohad Eytan)
 */
public final class MiniSimClimber implements HillClimber {
  private static final HashFunction hasher = Hashing.murmur3_32_fixed(0x7f3a2142);

  private final WindowTinyLfuPolicy[] minis;
  private final long[] targetWindows;
  private final long[] prevMisses;
  private final int samplingRate;
  private final int period;

  private int sample;
  private boolean filled;

  public MiniSimClimber(double percentMain, Config config) {
    var settings = new MiniSimSettings(config);
    checkState(!CLAIRVOYANT.name().equalsIgnoreCase(settings.tinyLfu().sketch()),
        "minisim records only a sampled subset of the accesses, so it cannot use the "
            + "clairvoyant sketch");
    int cacheSize = Math.toIntExact(settings.maximumSize());
    samplingRate = Math.max(1, (cacheSize / 1000) > 100 ? 1000 : (cacheSize / 100));
    int miniSize = cacheSize / samplingRate;
    var simulationSettings = new WindowTinyLfuSettings(ConfigFactory
        .parseString("maximum-size = " + miniSize)
        .withFallback(config));
    this.period = settings.minisimPeriod();
    var targets = targets(cacheSize, miniSize, percentMain, settings.percentMainProtected());
    this.minis = new WindowTinyLfuPolicy[targets.size()];
    this.targetWindows = new long[targets.size()];
    this.prevMisses = new long[minis.length];

    for (int i = 0; i < minis.length; i++) {
      var target = targets.get(i);
      var miniature = target.miniature();
      minis[i] = WindowTinyLfuPolicy.withSegmentSizes(
          miniature.maximumWindow(), miniature.maximumProtected(), Set.of(), simulationSettings);
      targetWindows[i] = target.full().maximumWindow();
    }
  }

  @Override
  public void onHit(long key, QueueType queue, boolean isFull) {
    onAccess(key, isFull);
  }

  @Override
  public void onMiss(long key, boolean isFull) {
    onAccess(key, isFull);
  }

  private void onAccess(long key, boolean isFull) {
    // The epoch clock starts when the host fills; earlier requests only warm the miniatures.
    if (isFull) {
      if (!filled) {
        filled = true;
        for (int i = 0; i < minis.length; i++) {
          prevMisses[i] = minis[i].stats().missCount();
        }
      }
      sample++;
    }

    if (Math.floorMod(hasher.hashLong(key).asInt(), samplingRate) < 1) {
      var event = AccessEvent.forKey(key);
      for (WindowTinyLfuPolicy policy : minis) {
        policy.record(event);
      }
    }
  }

  @Override
  public Adaptation adapt(double windowSize,
      double probationSize, double protectedSize, boolean isFull) {
    if (!isFull || (sample <= period)) {
      return Adaptation.hold();
    }

    long[] periodMisses = new long[minis.length];
    for (int i = 0; i < minis.length; i++) {
      periodMisses[i] = minis[i].stats().missCount() - prevMisses[i];
      prevMisses[i] = minis[i].stats().missCount();
    }
    @Var int minIndex = 0;
    @Var boolean uniform = true;
    for (int i = 1; i < periodMisses.length; i++) {
      uniform &= (periodMisses[i] == periodMisses[0]);
      if (periodMisses[i] < periodMisses[minIndex]) {
        minIndex = i;
      }
    }

    sample = 0;
    if (uniform) {
      // A uniform miss vector, such as a period with no sampled requests, discriminates nothing.
      return Adaptation.hold();
    }
    return adaptToward(targetWindows[minIndex], windowSize);
  }

  @SuppressFBWarnings("FE_FLOATING_POINT_EQUALITY")
  static Adaptation adaptToward(long targetWindow, double windowSize) {
    checkState(windowSize == Math.rint(windowSize), "Window size must be integral: %s", windowSize);
    return Adaptation.adaptBy((double) (targetWindow - (long) windowSize));
  }

  static List<Target> targets(
      int cacheSize, int miniSize, double percentMain, double percentMainProtected) {
    var fullInitial = initialSegments(cacheSize, percentMain, percentMainProtected);
    var miniInitial = initialSegments(miniSize, percentMain, percentMainProtected);
    var targetsByMiniature = new LinkedHashMap<SegmentSizes, Target>();
    var incumbent = scaleTargetSegments(
        cacheSize, miniSize, fullInitial, miniInitial.maximumProbation());
    targetsByMiniature.put(incumbent, new Target(fullInitial, incumbent));
    for (int percentWindow = 0; percentWindow <= 80; percentWindow++) {
      var full = targetSegments(cacheSize, percentWindow, fullInitial.maximumProbation());
      var miniature = scaleTargetSegments(
          cacheSize, miniSize, full, miniInitial.maximumProbation());
      // Prefer the live coordinate when scaling collapses it with a neighboring target.
      targetsByMiniature.putIfAbsent(miniature, new Target(full, miniature));
    }
    return List.copyOf(targetsByMiniature.values());
  }

  @SuppressWarnings("Varifier")
  static SegmentSizes initialSegments(
      long maximumSize, double percentMain, double percentMainProtected) {
    checkArgument(maximumSize > 0, "Maximum size must be positive: %s", maximumSize);
    checkArgument((percentMain >= 0.0) && (percentMain <= 1.0),
        "Main percentage must be between zero and one: %s", percentMain);
    checkArgument((percentMainProtected >= 0.0) && (percentMainProtected <= 1.0),
        "Protected percentage must be between zero and one: %s", percentMainProtected);
    long maximumMain = (long) (maximumSize * percentMain);
    long maximumWindow = maximumSize - maximumMain;
    long maximumProtected = (long) (maximumMain * percentMainProtected);
    long maximumProbation = maximumSize - maximumWindow - maximumProtected;
    return new SegmentSizes(maximumWindow, maximumProtected, maximumProbation);
  }

  static SegmentSizes targetSegments(
      long maximumSize, int percentWindow, long maximumProbation) {
    checkArgument((percentWindow >= 0) && (percentWindow <= 80),
        "Window percentage must be between zero and eighty: %s", percentWindow);
    checkArgument((maximumProbation >= 0) && (maximumProbation <= maximumSize),
        "Probation maximum must fit in the cache: probation=%s, maximum=%s",
        maximumProbation, maximumSize);
    long requestedWindow = Math.multiplyExact(maximumSize, percentWindow) / 100;
    long maximumWindow = Math.min(requestedWindow, maximumSize - maximumProbation);
    long maximumProtected = maximumSize - maximumWindow - maximumProbation;
    return new SegmentSizes(maximumWindow, maximumProtected, maximumProbation);
  }

  static SegmentSizes scaleTargetSegments(
      long cacheSize, long miniSize, SegmentSizes full, long maximumProbation) {
    checkArgument(full.maximumSize() == cacheSize,
        "Full segments must sum to the cache size: segments=%s, maximum=%s", full, cacheSize);
    checkArgument((maximumProbation >= 0) && (maximumProbation <= miniSize),
        "Probation maximum must fit in the miniature: probation=%s, maximum=%s",
        maximumProbation, miniSize);
    long scaledWindow = Math.multiplyExact(full.maximumWindow(), miniSize) / cacheSize;
    long maximumWindow = Math.min(scaledWindow, miniSize - maximumProbation);
    long maximumProtected = miniSize - maximumWindow - maximumProbation;
    return new SegmentSizes(maximumWindow, maximumProtected, maximumProbation);
  }

  record Target(SegmentSizes full, SegmentSizes miniature) {}

  record SegmentSizes(long maximumWindow, long maximumProtected, long maximumProbation) {
    SegmentSizes {
      checkArgument(maximumWindow >= 0, "Maximum window must not be negative: %s", maximumWindow);
      checkArgument(maximumProtected >= 0,
          "Maximum protected must not be negative: %s", maximumProtected);
      checkArgument(maximumProbation >= 0,
          "Maximum probation must not be negative: %s", maximumProbation);
    }

    long maximumSize() {
      return Math.addExact(Math.addExact(maximumWindow, maximumProtected), maximumProbation);
    }
  }

  static final class MiniSimSettings extends BasicSettings {
    public MiniSimSettings(Config config) {
      super(config);
    }
    public int minisimPeriod() {
      return config().getInt("hill-climber-window-tiny-lfu.minisim.period");
    }
    public double percentMainProtected() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-main-protected");
    }
  }
}
