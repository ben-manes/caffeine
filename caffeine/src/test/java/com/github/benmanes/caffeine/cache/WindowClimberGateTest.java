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
package com.github.benmanes.caffeine.cache;

import static com.google.common.truth.Truth.assertWithMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.errorprone.annotations.Var;

/**
 * Behavioral pins for the window climber on adversarial workload shapes, a fast deterministic
 * subset of the {@code /climber-gate} battery. Each cell drives a real cache single-threaded with
 * a seeded synthetic stream and asserts a hit-rate bar sized to separate the healthy machine from
 * a named failure mode, with several points of margin for the admission tiebreak's noise. The
 * full battery, its sentinels, and the real-trace corpus remain the merge-time gate; these pins
 * exist so a regression in the properties adversarial review kept breaking (audit-clock
 * liveness, escape from constructed traps, probe adjudication) fails a build instead of waiting
 * for the next manual run.
 * <p>
 * Deliberately not {@code @CacheSpec}-parameterized: the subject is one configuration (an
 * unweighted bounded cache above the density-tier threshold) under fixed workloads.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class WindowClimberGateTest {
  private static final int MAXIMUM = 8_192;

  /**
   * The whisper trap: a hot core lives in main, a reuse band at distance 3,000 is window-only,
   * and a whisper of distance-100 pairs keeps the window earning just above the starvation bar so
   * no blind-corner probe arms. Pure density clamps the window at the floor forever (~11 points
   * below achievable); the equilibrium audit is what escapes. Guards audit-clock liveness and the
   * sighted-false-equilibrium escape.
   */
  @Test
  void whisper() {
    double hitRate = run(new Workload(/* seed= */ 20_260_726, /* requests= */ 4_000_000,
        /* hotKeys= */ 3_000, /* hotRate= */ 0.631, /* bandRate= */ 0.1434,
        /* bandDistance= */ 3_000, /* whisperRate= */ 0.0038, /* whisperDistance= */ 100));
    // healthy ~66.8 (spread under 0.1); the floor pin scores ~55
    assertWithMessage("whisper trap escape").that(hitRate).isGreaterThan(62.0);
  }

  /**
   * The whisper base at a constant, louder whisper dose: the position-jam family's dose-matched
   * control. The healthy machine is deterministic here while a steering or stillness-clock
   * regression lands well below. Guards density steering health and the stillness clock under
   * ordinary (unjammed) traffic.
   */
  @Test
  void positionJamControl() {
    double hitRate = run(new Workload(/* seed= */ 20_260_801, /* requests= */ 4_000_000,
        /* hotKeys= */ 3_000, /* hotRate= */ 0.631, /* bandRate= */ 0.1434,
        /* bandDistance= */ 3_000, /* whisperRate= */ 0.015_75, /* whisperDistance= */ 100));
    // healthy ~66.9 (spread under 0.1); a jammed or missteered machine scores ~56
    assertWithMessage("position-jam control").that(hitRate).isGreaterThan(62.0);
  }

  /**
   * Demoflood: the hot core saturates the protected region, so an up-walk's demotions enrich live
   * probation and a probe verdict judged against the live rate is an absorbing false veto. The
   * frozen-at-arm baseline escapes. Guards the probe machine's adjudication.
   */
  @Test
  void demoflood() {
    double hitRate = run(new Workload(/* seed= */ 7, /* requests= */ 2_621_440,
        /* hotKeys= */ 6_160, /* hotRate= */ 0.70, /* bandRate= */ 0.20,
        /* bandDistance= */ 2_867, /* whisperRate= */ 0.0, /* whisperDistance= */ 0));
    // healthy ~71.3 (spread under 0.2); the live-baseline false veto scores ~62-64
    assertWithMessage("demoflood adjudication").that(hitRate).isGreaterThan(67.0);
  }

  /** Drives the workload through a real cache, single-threaded, and returns the hit rate. */
  private static double run(Workload workload) {
    Cache<Long, Boolean> cache = Caffeine.newBuilder()
        .executor(Runnable::run)
        .initialCapacity(MAXIMUM)
        .maximumSize(MAXIMUM)
        .build();
    @Var long hits = 0;
    for (long i = 0; i < workload.requests; i++) {
      long key = workload.next(i);
      if (cache.getIfPresent(key) == null) {
        cache.put(key, Boolean.TRUE);
      } else {
        hits++;
      }
    }
    return (100.0 * hits) / workload.requests;
  }

  /**
   * A seeded stream mixing a uniformly-hot core, a reuse band whose pairs re-arrive at a fixed
   * distance, an optional whisper of short-distance pairs, and one-shot noise; scheduled
   * re-touches take priority at their position, colliding entries probing forward.
   */
  private static final class Workload {
    final Random random;
    final long requests;
    final int hotKeys;
    final double hotRate;
    final double bandRate;
    final int bandDistance;
    final double whisperRate;
    final int whisperDistance;
    final Map<Long, Long> pending;
    long bandId;
    long whisperId;
    long noiseId;

    Workload(int seed, long requests, int hotKeys, double hotRate, double bandRate,
        int bandDistance, double whisperRate, int whisperDistance) {
      this.random = new Random(seed);
      this.requests = requests;
      this.hotKeys = hotKeys;
      this.hotRate = hotRate;
      this.bandRate = bandRate;
      this.bandDistance = bandDistance;
      this.whisperRate = whisperRate;
      this.whisperDistance = whisperDistance;
      this.pending = new HashMap<>();
    }

    long next(long position) {
      Long due = pending.remove(position);
      if (due != null) {
        return due;
      }
      double draw = random.nextDouble();
      if (draw < hotRate) {
        return 1_000_000L + random.nextInt(hotKeys);
      } else if (draw < (hotRate + bandRate)) {
        long key = 5_000_000 + bandId;
        bandId++;
        schedule(position + bandDistance, key);
        return key;
      } else if (draw < (hotRate + bandRate + whisperRate)) {
        long key = 3_000_000 + whisperId;
        whisperId++;
        schedule(position + whisperDistance, key);
        return key;
      }
      long key = 9_000_000 + noiseId;
      noiseId++;
      return key;
    }

    void schedule(long position, long key) {
      @Var long slot = position;
      while (pending.containsKey(slot)) {
        slot++;
      }
      pending.put(slot, key);
    }
  }
}
