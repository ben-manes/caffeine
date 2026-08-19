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

import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_FIRST;
import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_INITIAL;
import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_MAX;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_BACKOFF_INITIAL;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_BACKOFF_MAX;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_CRASH_ESCALATION;
import static com.github.benmanes.caffeine.cache.WindowClimber.RETREAT_COVER;
import static com.github.benmanes.caffeine.cache.WindowClimber.Reading.MAX_STEP_FRACTION;
import static com.github.benmanes.caffeine.cache.WindowClimber.Step.MIN_INITIAL_STEP;
import static com.github.benmanes.caffeine.cache.WindowClimber.Walk.AUDIT_CRASH_PERSISTENCE;
import static com.github.benmanes.caffeine.cache.WindowClimber.Walk.PROBE_WALK_BUDGET;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The window climber's state-machine invariants, asserted from the climber alone.
 * <p>
 * Both oracles run this: the fuzzer drives the climber directly, and {@link LocalCacheSubject}
 * validates it through the cache after every operation. They are one definition because the copies
 * had already drifted apart in both directions.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class ClimberInvariants {

  private ClimberInvariants() {}

  /** Asserts every invariant that holds of the climber at rest between samples. */
  static void assertInvariants(WindowClimber climber, long maximum) {
    assertWithMessage("counters are non-negative")
        .that(climber.sample.hits).isAtLeast(0);
    assertWithMessage("counters are non-negative")
        .that(climber.sample.misses).isAtLeast(0);
    assertWithMessage("region hits cannot exceed the sample's hits")
        .that(climber.sample.windowHits + climber.sample.probationHits)
        .isAtMost(climber.sample.hits);

    assertWithMessage("the starvation rung stays within its schedule")
        .that(climber.starvation.rung).isAtLeast(1);
    assertWithMessage("the starvation rung stays within its schedule")
        .that(climber.starvation.rung).isAtMost(PROBE_BACKOFF_MAX);
    assertWithMessage("the countdown is non-negative")
        .that(climber.refractoryLeft).isAtLeast(0);
    assertWithMessage("a retreat's cover stays within its span")
        .that(climber.retreatLeft).isAtLeast(0);
    assertWithMessage("a retreat's cover stays within its span")
        .that(climber.retreatLeft).isAtMost(RETREAT_COVER);
    assertWithMessage("the refractory countdown is bounded by its own rung")
        .that(climber.refractoryLeft).isAtMost(climber.starvation.rung);

    var walk = climber.walk;
    if (walk != null) {
      assertWithMessage("a walk is bounded by its budget")
          .that(walk.samples).isAtLeast(0);
      assertWithMessage("a walk is bounded by its budget")
          .that(walk.samples).isAtMost(PROBE_WALK_BUDGET);
      assertWithMessage("a below-bar run is adjudicated at the audit persistence")
          .that(walk.belowBarStreak).isAtLeast(0);
      // the streak is incremented before the abort test, and the abort ends the walk, so a walk
      // that is still visible has not yet reached the persistence
      assertWithMessage("a below-bar run is adjudicated at the audit persistence")
          .that(walk.belowBarStreak).isAtMost(AUDIT_CRASH_PERSISTENCE - 1);
      assertWithMessage("a walk's ledger is the one its own layer owns")
          .that(walk.ladder).isSameInstanceAs(walk.isAudit ? climber.audit : climber.starvation);
      assertWithMessage("a walk's best sample is a window it stood on, or unset")
          .that(walk.bestWindow).isAtLeast(-1L);
      assertWithMessage("a walk's best sample is a window it stood on, or unset")
          .that(walk.bestWindow).isAtMost(maximum);
      assertWithMessage("a walk's best sample carries the rate that position earned")
          .that((walk.bestWindow < 0) == (walk.bestRate < 0.0)).isTrue();
      assertWithMessage("a walk's best rate is a rate")
          .that(walk.bestRate).isAtMost(1.0);
      assertWithMessage("the frozen probation baseline is a density")
          .that(walk.baseProbationDensity).isAtLeast(0.0);
      assertWithMessage("the frozen probation baseline is a density")
          .that(Double.isFinite(walk.baseProbationDensity)).isTrue();
      // it re-expresses that baseline at the live sample's length, so it divides
      assertWithMessage("the frozen sample length is positive")
          .that(walk.baseRequestCount).isGreaterThan(0L);
    }

    assertWithMessage("a ladder's memory is a window or unset")
        .that(climber.starvation.farthest).isAtLeast(-1L);
    assertWithMessage("a ladder's memory is a window or unset")
        .that(climber.starvation.farthest).isAtMost(maximum);
    // the audit layer's endings are adjudicated by the goal metric and never consult the memory
    assertWithMessage("the audit ladder keeps no memory")
        .that(climber.audit.farthest).isEqualTo(-1L);

    assertWithMessage("the audit rung stays within its schedule")
        .that(climber.audit.rung).isAtLeast(PROBE_BACKOFF_INITIAL);
    assertWithMessage("the audit rung stays within its schedule")
        .that(climber.audit.rung).isAtMost(PROBE_BACKOFF_MAX);
    assertWithMessage("each ledger's crash streak is non-negative")
        .that(climber.audit.crashStreak).isAtLeast(0);
    assertWithMessage("each ledger's crash streak is non-negative")
        .that(climber.starvation.crashStreak).isAtLeast(0);
    assertWithMessage("each ledger's crash streak saturates once it escalates")
        .that(climber.audit.crashStreak).isAtMost(PROBE_CRASH_ESCALATION);
    assertWithMessage("each ledger's crash streak saturates once it escalates")
        .that(climber.starvation.crashStreak).isAtMost(PROBE_CRASH_ESCALATION);
    // the schedule's floor is the cold-start calibration seed; every retry after the first
    // audit is floored at the initial refractory by undoProbe
    assertWithMessage("the audit wait stays within its schedule")
        .that(climber.auditClock.waitSamples).isAtLeast(AUDIT_WAIT_FIRST);
    assertWithMessage("the audit wait stays within its schedule")
        .that(climber.auditClock.waitSamples).isAtMost(AUDIT_WAIT_MAX);
    // The clock may outrun the ladder only once the ladder is exhausted: `undoProbe` reaches the
    // doubling branch only at the deepest audit rung, and the two places that lower the rung (an
    // audit confirm, a resize) reset the wait in the same breath. This is the pulse-train ratchet
    // as an invariant. That defect drove the wait to 128 while the rung was still climbing.
    assertWithMessage("the audit clock outruns its ladder only at the deepest rung")
        .that((climber.auditClock.waitSamples <= PROBE_BACKOFF_MAX)
            || (climber.audit.rung == PROBE_BACKOFF_MAX)).isTrue();
    assertWithMessage("the stillness count is non-negative")
        .that(climber.auditClock.stillSamples).isAtLeast(0);
    if (!Double.isNaN(climber.auditClock.settledRate)) {
      assertWithMessage("the settled rate is a rate")
          .that(climber.auditClock.settledRate).isAtLeast(0.0);
      assertWithMessage("the settled rate is a rate")
          .that(climber.auditClock.settledRate).isAtMost(1.0);
    }

    assertWithMessage("the fresh-park shield lives and dies with the park")
        .that((climber.anchor.freshLeft == 0) || climber.anchor.held).isTrue();
    assertWithMessage("the fresh-park shield stays within its schedule")
        .that(climber.anchor.freshLeft).isAtLeast(0);
    assertWithMessage("the fresh-park shield stays within its schedule")
        .that(climber.anchor.freshLeft).isAtMost(AUDIT_WAIT_INITIAL);
    assertWithMessage("the veto return budget is non-negative")
        .that(climber.anchor.returnLeft).isAtLeast(0);
    assertWithMessage("a walk and a veto return are mutually exclusive")
        .that((walk != null) && climber.anchor.returning).isFalse();
    assertWithMessage("an in-progress return implies the park that follows it")
        .that(!climber.anchor.returning || climber.anchor.held).isTrue();
    assertWithMessage("a walk holds no undo remainder")
        .that((walk == null) || (climber.undoRemaining == 0)).isTrue();
    assertWithMessage("a park defends a planted anchor")
        .that(!climber.anchor.held || climber.anchor.isPlanted()).isTrue();

    // the cap is a double the climber never truncates, so recomputing it in entries here needs one
    // of headroom. The bound is stricter than the adjudicated reality: a negative policyWeight
    // inflates the transfer loop's quota and carries the excess back, a sanctioned transient that
    // no subject-validated test drives.
    long bound = Math.max((long) MIN_INITIAL_STEP, (long) (MAX_STEP_FRACTION * maximum)) + 1;
    assertWithMessage("the adjustment honors the maximum step")
        .that(Math.abs(climber.adjustment())).isAtMost(bound);
    assertWithMessage("the step size honors the maximum step")
        .that(Math.abs(climber.step.size)).isAtMost((double) bound);
    assertWithMessage("the step size is finite")
        .that(Double.isFinite(climber.step.size)).isTrue();

    assertWithMessage("the deviation estimate is non-negative and finite")
        .that(climber.rates.deviation).isAtLeast(0.0);
    assertWithMessage("the deviation estimate is non-negative and finite")
        .that(Double.isFinite(climber.rates.deviation)).isTrue();
    if (!Double.isNaN(climber.rates.smoothed)) {
      assertWithMessage("the smoothed rate is a rate")
          .that(climber.rates.smoothed).isAtLeast(0.0);
      assertWithMessage("the smoothed rate is a rate")
          .that(climber.rates.smoothed).isAtMost(1.0);
    }
    if (climber.anchor.isPlanted()) {
      assertWithMessage("the anchor's claim is a rate")
          .that(climber.anchor.rate).isAtLeast(0.0);
      assertWithMessage("the anchor's claim is a rate")
          .that(climber.anchor.rate).isAtMost(1.0);
    }
  }
}
