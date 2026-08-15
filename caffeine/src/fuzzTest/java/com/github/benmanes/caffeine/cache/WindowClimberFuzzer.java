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

import static com.github.benmanes.caffeine.cache.ClimberInvariants.assertInvariants;
import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_INITIAL;
import static com.google.common.truth.Truth.assertWithMessage;

import org.jspecify.annotations.Nullable;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.errorprone.annotations.Var;

/**
 * A fuzzer that exercises the {@link WindowClimber} state machine with random sample sequences,
 * window positions, region geometries, and resizes. The caller-side realism is deliberate: the
 * cache applies a commanded adjustment through capped transfers, so the fuzzer applies each
 * adjustment partially (or not at all, a rail) and teleports the window within its legal range,
 * exactly the drift the climber must tolerate. The oracle checks the state-machine invariants
 * of {@link ClimberInvariants}, which the cache tests assert through the subject, after every
 * decision, plus the deferral-ownership rules, which compare a sample against the one before it
 * and so have no state-local mirror there.
 */
final class WindowClimberFuzzer {

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  @SuppressWarnings("MathClampLong")
  void climb(FuzzedDataProvider data) {
    @Var long maximum = pickMaximum(data);
    var climber = new WindowClimber();
    climber.resized(maximum);
    assertInvariants(climber, maximum);

    @Var long windowMax = maximum / 100;
    @Var long carryOver = climber.adjustment();
    int operations = data.consumeInt(0, 200);
    for (int i = 0; i < operations; i++) {
      if (data.consumeInt(0, 7) == 0) {
        // an occasional user resize, sometimes crossing tiers, resets the machine wholesale
        maximum = pickMaximum(data);
        climber.resized(maximum);
        windowMax = Math.min(windowMax, maximum);
        carryOver = climber.adjustment();
        assertInvariants(climber, maximum);
        continue;
      }

      // teleport within the legal range occasionally: transfers lag commands, and expiration or
      // weight changes move the realized position independently of the climber's requests
      if (data.consumeInt(0, 3) == 0) {
        windowMax = data.consumeLong(0, maximum);
      }

      // discard the partial sample occasionally, as the uninitialized-sketch path does
      if (data.consumeInt(0, 15) == 0) {
        climber.resetSample();
      }

      int requests = data.consumeInt(0, 5_000_000);
      int hits = data.consumeInt(0, requests);
      int windowHits = data.consumeInt(0, hits);
      int probationHits = data.consumeInt(0, hits - windowHits);
      record(climber, hits, windowHits, probationHits, requests - hits);
      long pending = climber.sample.hits + climber.sample.misses;

      var probing = climber.walk;
      int starvationRung = climber.starvation.rung;
      int auditRung = climber.audit.rung;
      int auditWait = climber.auditClock.waitSamples;

      long mainProtected = data.consumeLong(0, Math.max(0, maximum - windowMax));
      int sketchSample = data.consumeInt(4, 1_000_000);
      climber.determineAdjustment(maximum, windowMax, mainProtected, sketchSample);
      assertInvariants(climber, maximum);
      checkDeferralOwnership(climber, probing, starvationRung, auditRung, auditWait);

      // a sample completes when the accumulated counts crossed the period and were consumed,
      // detected from the counters, since a zero-fed call can complete an accumulated sample
      boolean completed = (pending > 0)
          && (climber.sample.hits == 0) && (climber.sample.misses == 0);
      if (!completed) {
        // a sub-period sample must preserve the pending adjustment (the multi-cycle carry-over
        // that keeps a capped transfer draining until a fresh sample overwrites it)
        assertWithMessage("A sub-period sample must not disturb the carry-over")
            .that(climber.adjustment()).isEqualTo(carryOver);
      }
      carryOver = climber.adjustment();

      // apply the command partially, as the transfer throttle would, and stay in legal range.
      // The percentage is split and the move is measured against the room it has, since at the
      // top tier the product and the sum both exceed a long and would snap the window to a rail
      long adjustment = climber.adjustment();
      int percent = data.consumeInt(0, 100);
      long applied = ((adjustment / 100) * percent) + (((adjustment % 100) * percent) / 100);
      windowMax = (applied >= 0)
          ? (windowMax + Math.min(applied, maximum - windowMax))
          : (windowMax + Math.max(applied, -windowMax));
    }
  }

  /**
   * A deferral is denominated in samples and indexed by the deferring layer's own completed
   * experiments. A layer's ladder and schedule may therefore grow only on a sample that ended
   * that layer's own walk, and the audit clock may pass its ladder only on a walk that reached a
   * verdict rather than crashing out.
   */
  private static void checkDeferralOwnership(WindowClimber climber,
      WindowClimber.@Nullable Walk probing, int starvationRung, int auditRung, int auditWait) {
    boolean endedAudit = (probing != null) && (climber.walk != probing) && probing.isAudit;
    boolean endedStarvation = (probing != null) && (climber.walk != probing) && !probing.isAudit;

    assertWithMessage("the starvation rung deepens only on a starvation ending")
        .that((climber.starvation.rung <= starvationRung) || endedStarvation).isTrue();
    assertWithMessage("the audit rung deepens only on an audit ending")
        .that((climber.audit.rung <= auditRung) || endedAudit).isTrue();
    assertWithMessage("the audit clock defers only on an audit ending")
        .that((climber.auditClock.waitSamples <= auditWait) || endedAudit).isTrue();
    assertWithMessage("the audit clock passes its ladder only on a verdict, never a crash")
        .that((climber.auditClock.waitSamples <= auditWait)
            || (climber.auditClock.waitSamples
                <= Math.max(AUDIT_WAIT_INITIAL, climber.audit.rung))
            || (climber.audit.crashStreak == 0)).isTrue();
  }

  /** Returns a maximum size, weighted toward the tier boundaries and including the extremes. */
  @SuppressWarnings("MathClampLong")
  private static long pickMaximum(FuzzedDataProvider data) {
    switch (data.consumeInt(0, 4)) {
      case 0:
        return data.consumeLong(0, 512);                    // the slow-adapt tier
      case 1:
        return data.consumeLong(513, 4096);                 // the stock reactive tier
      case 2:
        return data.consumeLong(4097, 65_536);              // the density tier
      case 3:
        return data.consumeLong(65_537, 1L << 40);          // large weighted maxima
      default:
        return Long.MAX_VALUE - data.consumeLong(0, 1024);  // the overflow guard's territory
    }
  }

  /** Feeds a sample's counts, through the recording api when small enough to loop cheaply. */
  private static void record(WindowClimber climber,
      int hits, int windowHits, int probationHits, int misses) {
    if ((hits + misses) <= 4096) {
      for (int i = 0; i < windowHits; i++) {
        climber.recordHit(/* inWindow= */ true, /* inProbation= */ false);
      }
      for (int i = 0; i < probationHits; i++) {
        climber.recordHit(/* inWindow= */ false, /* inProbation= */ true);
      }
      for (int i = 0; i < (hits - windowHits - probationHits); i++) {
        climber.recordHit(/* inWindow= */ false, /* inProbation= */ false);
      }
      for (int i = 0; i < misses; i++) {
        climber.recordMiss();
      }
    } else {
      climber.sample.hits += hits;
      climber.sample.windowHits += windowHits;
      climber.sample.probationHits += probationHits;
      climber.sample.misses += misses;
    }
  }
}
