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

import static com.github.benmanes.caffeine.cache.WindowClimber.RESTART_THRESHOLD;
import static com.github.benmanes.caffeine.cache.WindowClimber.RETREAT_COVER;
import static com.github.benmanes.caffeine.cache.WindowClimber.Anchor.RETEST_SETTLE;
import static com.github.benmanes.caffeine.cache.WindowClimber.Anchor.VETO_RETURN_BUDGET;
import static com.github.benmanes.caffeine.cache.WindowClimber.Anchor.VETO_STREAK;
import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_FIRST;
import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_INITIAL;
import static com.github.benmanes.caffeine.cache.WindowClimber.AuditClock.AUDIT_WAIT_MAX;
import static com.github.benmanes.caffeine.cache.WindowClimber.DensityClimber.DENSITY_GAIN;
import static com.github.benmanes.caffeine.cache.WindowClimber.DensityClimber.DENSITY_THRESHOLD;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_BACKOFF_INITIAL;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_BACKOFF_MAX;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_COMMITMENT_DEEP;
import static com.github.benmanes.caffeine.cache.WindowClimber.Ladder.PROBE_CRASH_ESCALATION;
import static com.github.benmanes.caffeine.cache.WindowClimber.Rates.DEVIATION_SEED;
import static com.github.benmanes.caffeine.cache.WindowClimber.Reading.DENSITY_EPSILON;
import static com.github.benmanes.caffeine.cache.WindowClimber.Reading.MAX_STEP_FRACTION;
import static com.github.benmanes.caffeine.cache.WindowClimber.Reading.WINDOW_FLOOR_FRACTION;
import static com.github.benmanes.caffeine.cache.WindowClimber.Step.STEP_DECAY_RATE;
import static com.github.benmanes.caffeine.cache.WindowClimber.Step.STEP_PERCENT;
import static com.github.benmanes.caffeine.cache.WindowClimber.Walk.AUDIT_COMMITMENT;
import static com.github.benmanes.caffeine.cache.WindowClimber.Walk.AUDIT_CONFIRM_STREAK;
import static com.github.benmanes.caffeine.cache.WindowClimber.Walk.AUDIT_CRASH_PERSISTENCE;
import static com.github.benmanes.caffeine.cache.WindowClimber.Walk.PROBE_WALK_BUDGET;
import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The white-box tests for the window climber's tiers and probe machine. The cache applies the
 * returned {@code adjustment} by transferring entries between the regions, so these drive
 * {@link WindowClimber#determineAdjustment} directly with the caller's evolving window size;
 * {@code BoundedLocalCacheTest}'s adapt tests cover the transfer plumbing.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class WindowClimberTest {
  private static final long MAXIMUM = 8192;
  private static final double STRIDE = STEP_PERCENT * MAXIMUM;
  private static final double FLOOR = WINDOW_FLOOR_FRACTION * MAXIMUM;

  /* --------------- Seeding --------------- */

  @Test
  void resized_seedsByTier() {
    var climber = new WindowClimber();
    climber.resized(512);
    assertThat(climber.step.size).isEqualTo(STEP_PERCENT * 512);

    climber.resized(MAXIMUM);
    assertThat(climber.step.size).isEqualTo(-STRIDE);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
    // every (re)size re-arms the cold-start calibration audit
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_FIRST);
  }

  @Test
  void resized_resetsProbeState() {
    var climber = makeClimber();
    injectWalk(climber, /* isAudit= */ false, /* down= */ false,
        /* baseWindow= */ 1024, /* baseHitRate= */ 0.5, /* baseSmoothedRate= */ 0.5).samples = 5;
    climber.refractoryLeft = 5;
    climber.carryOver(100);

    climber.resized(MAXIMUM);
    assertThat(climber.walk).isNull();
    assertThat(climber.adjustment()).isEqualTo(0);
    assertThat(climber.refractoryLeft).isEqualTo(0);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void resized_clearsTheSample() {
    // a resize discards the partial sample: counts earned under the old geometry must not seed
    // the first decision under the new one
    var climber = makeClimber();
    climber.recordHit(/* inWindow= */ true, /* inProbation= */ false);
    climber.recordHit(/* inWindow= */ false, /* inProbation= */ true);
    climber.recordMiss();
    climber.sample.previousHitRate = 0.5;

    climber.resized(MAXIMUM);
    assertThat(climber.sample.hits).isEqualTo(0);
    assertThat(climber.sample.misses).isEqualTo(0);
    assertThat(climber.sample.windowHits).isEqualTo(0);
    assertThat(climber.sample.probationHits).isEqualTo(0);
    assertThat(climber.sample.previousHitRate).isEqualTo(0.0);
  }

  /* --------------- Sampling --------------- */

  @Test
  void samplePeriod_tierBoundary_isTheDensityThreshold() {
    // At the threshold the reactive climber runs on the sketch's period; one entry above it the
    // density climber runs on min(4·maximum, sketch). A 16,400-request feed sits between the
    // two periods, so it decides above the boundary and holds at it. The tier gate, exact.
    var reactive = new WindowClimber();
    reactive.resized(DENSITY_THRESHOLD);
    reactive.sample.hits += 8192;
    reactive.sample.misses += 8208;
    reactive.determineAdjustment(DENSITY_THRESHOLD, /* windowMaximum= */ 41,
        protectedMax(DENSITY_THRESHOLD, 41), /* sketchSampleSize= */ 16_488);
    assertThat(reactive.adjustment()).isEqualTo(0);
    assertThat(reactive.sample.hits).isEqualTo(8192);

    var density = new WindowClimber();
    density.resized(DENSITY_THRESHOLD + 1);
    density.sample.hits += 5000;
    density.sample.misses += 5000;
    density.determineAdjustment(DENSITY_THRESHOLD + 1, /* windowMaximum= */ 41,
        protectedMax(DENSITY_THRESHOLD + 1, 41), /* sketchSampleSize= */ 16_488);
    assertThat(density.adjustment()).isEqualTo(0);

    density.sample.hits += 3192;
    density.sample.misses += 3208;
    density.determineAdjustment(DENSITY_THRESHOLD + 1, /* windowMaximum= */ 41,
        protectedMax(DENSITY_THRESHOLD + 1, 41), /* sketchSampleSize= */ 16_488);
    assertThat(density.adjustment()).isNotEqualTo(0);
    assertThat(density.sample.hits).isEqualTo(0);
  }

  @Test
  void samplePeriod_largeTier_completesExactlyAtFourMaxima() {
    // the density period is 4·maximum when the sketch's sample is larger: one request short
    // holds the carry-over, the exact count decides
    var climber = makeClimber();
    climber.sample.hits += 16_384;
    climber.sample.misses += 16_383;
    climber.determineAdjustment(MAXIMUM, /* windowMaximum= */ 2000,
        protectedMax(MAXIMUM, 2000), /* sketchSampleSize= */ 100_000);
    assertThat(climber.adjustment()).isEqualTo(0);
    assertThat(climber.sample.hits).isEqualTo(16_384);

    climber.sample.misses += 1;
    climber.determineAdjustment(MAXIMUM, /* windowMaximum= */ 2000,
        protectedMax(MAXIMUM, 2000), /* sketchSampleSize= */ 100_000);
    assertThat(climber.adjustment()).isNotEqualTo(0);
    assertThat(climber.sample.hits).isEqualTo(0);
  }

  @Test
  void samplePeriod_hugeMaximum_staysQuiescent() {
    // 4·maximum overflows near Long.MAX_VALUE; the guard saturates the period so the climber
    // idles rather than completing garbage-length samples
    long maximum = Long.MAX_VALUE - 100;
    var climber = new WindowClimber();
    climber.resized(maximum);
    climber.sample.hits += 500_000;
    climber.sample.misses += 500_000;
    climber.determineAdjustment(maximum, /* windowMaximum= */ maximum / 100,
        /* mainProtectedMaximum= */ maximum / 2, /* sketchSampleSize= */ Integer.MAX_VALUE);
    assertThat(climber.adjustment()).isEqualTo(0);
    assertThat(climber.sample.hits).isEqualTo(500_000);
  }

  @Test
  void samplePeriod_smallTier_stretchesAsTheStepDecays() {
    // the slow-adapt tier trades cadence for information: the period grows by the step's decay
    // from its initial magnitude, capped at the ratio limit (a zeroed step on a zero-capacity
    // cache degenerates to ratio one rather than poisoning the arithmetic)
    var climber = new WindowClimber();
    climber.resized(512);
    climber.sample.hits += 500;
    climber.sample.misses += 500;
    climber.determineAdjustment(512, /* windowMaximum= */ 5,
        protectedMax(512, 5), /* sketchSampleSize= */ 1000);
    assertThat(climber.sample.hits).isEqualTo(0);

    climber.step.size = 8.0;
    climber.sample.hits += 2000;
    climber.sample.misses += 1999;
    climber.determineAdjustment(512, /* windowMaximum= */ 5,
        protectedMax(512, 5), /* sketchSampleSize= */ 1000);
    assertThat(climber.sample.hits).isEqualTo(2000);

    climber.sample.misses += 1;
    climber.determineAdjustment(512, /* windowMaximum= */ 5,
        protectedMax(512, 5), /* sketchSampleSize= */ 1000);
    assertThat(climber.sample.hits).isEqualTo(0);

    var empty = new WindowClimber();
    empty.resized(0);
    empty.step.size = 0.0;
    empty.sample.misses += 1000;
    empty.determineAdjustment(0, /* windowMaximum= */ 0,
        /* mainProtectedMaximum= */ 0, /* sketchSampleSize= */ 1000);
    assertThat(empty.sample.misses).isEqualTo(0);
  }

  @Test
  void stepDecayRate_boundary_isTheSlowAdaptThreshold() {
    // 512 decays at the slow rate, 513 at the standard rate. The small-cache tuning's edge
    var slow = new WindowClimber();
    slow.resized(512);
    double slowSeed = slow.step.size;
    reactiveSampleAt(slow, 512, /* hits= */ 500, /* misses= */ 501);
    reactiveSampleAt(slow, 512, /* hits= */ 505, /* misses= */ 496);
    assertThat(slow.step.size).isWithin(1.0e-9).of(0.995 * slowSeed);

    var stock = new WindowClimber();
    stock.resized(513);
    double stockSeed = stock.step.size;
    reactiveSampleAt(stock, 513, /* hits= */ 500, /* misses= */ 501);
    reactiveSampleAt(stock, 513, /* hits= */ 505, /* misses= */ 496);
    assertThat(stock.step.size).isWithin(1.0e-9).of(STEP_DECAY_RATE * stockSeed);
  }

  @Test
  void reactiveStep_flatSampleKeepsDirection_exactRestartReseeds() {
    // a hit rate change of exactly zero keeps the bold driver's direction, and a change of
    // exactly the restart threshold re-anneals the decayed step to its full magnitude
    var climber = new WindowClimber();
    climber.resized(2048);
    double seed = climber.step.size;
    reactiveSampleAt(climber, 2048, /* hits= */ 500, /* misses= */ 501);
    reactiveSampleAt(climber, 2048, /* hits= */ 500, /* misses= */ 501);
    assertThat(climber.adjustment()).isEqualTo((long) seed);
    assertThat(climber.step.size).isWithin(1.0e-9).of(STEP_DECAY_RATE * seed);

    reactiveSampleAt(climber, 2048, /* hits= */ 560, /* misses= */ 441);
    assertThat(climber.adjustment()).isEqualTo((long) (STEP_DECAY_RATE * seed));
    assertThat(climber.step.size).isEqualTo(seed);
  }

  /** Completes one full reactive-tier sample at the given maximum (sketch period 1001). */
  private static void reactiveSampleAt(WindowClimber climber, long maximum, int hits, int misses) {
    for (int i = 0; i < hits; i++) {
      climber.recordHit(/* inWindow= */ false, /* inProbation= */ false);
    }
    for (int i = 0; i < misses; i++) {
      climber.recordMiss();
    }
    climber.determineAdjustment(maximum, /* windowMaximum= */ 20,
        protectedMax(maximum, 20), /* sketchSampleSize= */ 1001);
  }

  /* --------------- Arming --------------- */

  @Test
  void armProbe_windowBlind_probesUp() {
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);

    var walk = walkOf(climber);
    assertThat(walk.down).isFalse();
    assertThat(adjustment).isEqualTo((long) STRIDE);
    assertThat(walk.baseWindow).isEqualTo(1024);
    assertThat(walk.baseHitRate).isEqualTo(0.5);
    assertThat(walk.samples).isEqualTo(1);
  }

  @Test
  void armProbe_mainBlind_neverProbes() {
    // a starved main beside a large window arms nothing, since the equilibrium audit owns that
    // terrain; the sample falls through to the steering law with main priced at its floor
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 7000,
        /* windowHits= */ 500, /* mainHits= */ 0, /* misses= */ 500);

    double windowDensity = 500 / 7000.0;
    double mainFloor = (0.125 * 4) / (MAXIMUM - 7000.0);
    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(
        (long) (Math.log(windowDensity / mainFloor) * DENSITY_GAIN * MAXIMUM));
  }

  @Test
  void armProbe_deadSample_probesAwayFromNearerBound() {
    var wide = makeClimber();
    long down = sample(wide, /* windowMax= */ 5000,
        /* windowHits= */ 0, /* mainHits= */ 0, /* misses= */ 1000);
    assertThat(walkOf(wide).down).isTrue();
    assertThat(down).isEqualTo((long) -STRIDE);

    var narrow = makeClimber();
    long up = sample(narrow, /* windowMax= */ 3000,
        /* windowHits= */ 0, /* mainHits= */ 0, /* misses= */ 1000);
    assertThat(walkOf(narrow).down).isFalse();
    assertThat(up).isEqualTo((long) STRIDE);
  }

  @Test
  void armProbe_starvedLargeRegion_doesNotProbe() {
    // a scan-filled main earning nothing is visible to density; probing would shrink the one
    // region that is working
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 4000,
        /* windowHits= */ 1000, /* mainHits= */ 0, /* misses= */ 0);
    assertThat(climber.walk).isNull();
    assertThat(adjustment).isGreaterThan(0);

    var other = makeClimber();
    long shrink = sample(other, /* windowMax= */ 3000,
        /* windowHits= */ 0, /* mainHits= */ 1000, /* misses= */ 0);
    assertThat(other.walk).isNull();
    assertThat(shrink).isLessThan(0);
  }

  @Test
  void armProbe_refractory_ticksAndHolds() {
    // a refractory sample was just classified as one the density signal cannot be trusted on, so
    // the climber holds; falling through to a steering step was the dead-phase rider's delivery
    // vehicle (a handful of window hits in a blank sample authorized the maximum step)
    var climber = makeClimber();
    climber.refractoryLeft = 3;
    long adjustment = sample(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);

    assertThat(climber.walk).isNull();
    assertThat(climber.refractoryLeft).isEqualTo(2);
    assertThat(adjustment).isEqualTo(0);
  }

  @Test
  void armProbe_refractory_liftsABelowFloorWindow() {
    // the refractory hold still honors the below-floor lift: on a workload whose every sample
    // is blind, a bare hold wedges the initial 1% window under the signal-capable floor for
    // the life of the run
    var climber = makeClimber();
    climber.refractoryLeft = 3;
    long adjustment = sample(climber, /* windowMax= */ 82,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);

    assertThat(climber.walk).isNull();
    assertThat(climber.refractoryLeft).isEqualTo(2);
    assertThat(adjustment).isEqualTo((long) (FLOOR - 82));
  }

  @Test
  void armProbe_refractory_dueClockAuditsRatherThanHolding() {
    // the hold commands nothing and is spent only on blind samples, so a corner that never clears
    // serves its whole backoff standing still while the clock says the position is due to be
    // re-tested. armProbe_refractory_ticksAndHolds and _liftsABelowFloorWindow are the not-due
    // half, where it still holds and still lifts
    var climber = makeClimber();
    climber.refractoryLeft = 3;
    climber.auditClock.stillSamples = AUDIT_WAIT_FIRST;
    long adjustment = sample(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);

    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(climber.refractoryLeft).isEqualTo(3);
    assertThat(adjustment).isEqualTo((long) -STRIDE);
  }

  @Test
  void undoProbe_auditRetreat_leavesTheStarvationRefractoryAlone() {
    // the refractory is the starvation machine's backoff, armed by its own endings; an audit that
    // preempted a hold and crashed hands the corner back with the hold where it was, not re-armed
    // to the whole rung, which deferred the corner's next probe for an audit that was not the
    // probe's doing (a starvation walk's undo still arms it, as the walkStep_ tests pin)
    var climber = makeClimber();
    climber.refractoryLeft = 3;
    climber.auditClock.stillSamples = AUDIT_WAIT_FIRST;
    sample(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);
    assertThat(walkOf(climber).isAudit).isTrue();

    long undo = sample(climber, /* windowMax= */ 512,
        /* windowHits= */ 0, /* mainHits= */ 300, /* misses= */ 700);
    assertThat(climber.walk).isNull();
    assertThat(undo).isEqualTo(1024 - 512);
    assertThat(climber.refractoryLeft).isEqualTo(3);
    assertThat(climber.auditClock.waitSamples).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void armProbe_refractory_dueClockBelowTheFloor_stillClearsIt() {
    // pre-empting the hold must not cost the below-floor lift the hold would have made: the
    // window sits under the floor, so the audit is refused a downward direction and its entry
    // stride carries it clear
    var climber = makeClimber();
    climber.refractoryLeft = 3;
    climber.auditClock.stillSamples = AUDIT_WAIT_FIRST;
    long adjustment = sample(climber, /* windowMax= */ 82,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);

    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(82 + adjustment).isAtLeast((long) FLOOR);
  }

  @Test
  void densityStep_starvedOpposingRegion_boundsTheStep() {
    // a blank main is imputed a density of an eighth of the starvation bar spread over its
    // capacity (the strongest claim the sample can honestly make) rather than the epsilon's
    // ~20 nat balloon,
    // so a handful of window hits cannot authorize the maximum step
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 2049,
        /* windowHits= */ 40, /* mainHits= */ 0, /* misses= */ 960);

    double windowDensity = 40 / 2049.0;
    double mainFloor = (0.125 * 4) / (MAXIMUM - 2049);
    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo((long) (Math.log(windowDensity / mainFloor)
        * DENSITY_GAIN * MAXIMUM));
    assertThat(adjustment).isLessThan((long) (MAX_STEP_FRACTION * MAXIMUM));
  }

  @Test
  void densityStep_commonModeScaling_cancels() {
    // The steering signal's central property: a workload phase that scales both regions'
    // earnings together (the cross-sample hit rate weather) cancels in the density ratio, so
    // two samples with the same split quality at different traffic intensities command the
    // same step. This is what the reactive climber's cross-sample comparison lacks.
    var quiet = makeClimber();
    long small = sample(quiet, /* windowMax= */ 2000,
        /* windowHits= */ 100, /* mainHits= */ 300, /* misses= */ 600);

    var loud = makeClimber();
    long large = sample(loud, /* windowMax= */ 2000,
        /* windowHits= */ 400, /* mainHits= */ 1200, /* misses= */ 2400);

    assertThat(small).isEqualTo(densityStep(/* windowMax= */ 2000,
        /* windowHits= */ 100, /* mainHits= */ 300));
    assertThat(small).isNotEqualTo(0);
    assertThat(large).isEqualTo(small);
  }

  @Test
  void densityStep_starvedInteriorWindow_steersInsteadOfProbing() {
    // a starved region that is not small is not blind: the position is mid-range, the other
    // region is measurable, and the floored steering error walks the window down without the
    // probe machinery (probes are reserved for the corners where nobody can see)
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 3000,
        /* windowHits= */ 0, /* mainHits= */ 600, /* misses= */ 400);

    double windowFloor = (0.125 * 4) / 3000;
    double mainDensity = 600 / (double) (MAXIMUM - 3000);
    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(
        (long) (Math.log(windowFloor / mainDensity) * DENSITY_GAIN * MAXIMUM));
  }

  @Test
  void densityStep_floorCrossingShrink_clampsAtTheFloor() {
    // a sighted shrink command that would cross the floor is clamped to land exactly on it
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 200,
        /* windowHits= */ 4, /* mainHits= */ 900, /* misses= */ 96);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo((long) (FLOOR - 200));
  }

  @Test
  void blindCorner_positionBoundaries_windowQuarterPointOnly() {
    // a starved window is blind only while it holds at most a quarter of the cache, exact at the
    // edge; a starved main is never blind at any window, since the equilibrium audit owns that
    // terrain and the probe it once armed there confirmed onto positions main could not price
    var atEdge = makeClimber();
    sample(atEdge, /* windowMax= */ 2048,
        /* windowHits= */ 0, /* mainHits= */ 600, /* misses= */ 400);
    assertThat(atEdge.walk).isNotNull();

    var pastEdge = makeClimber();
    sample(pastEdge, /* windowMax= */ 2049,
        /* windowHits= */ 0, /* mainHits= */ 600, /* misses= */ 400);
    assertThat(pastEdge.walk).isNull();

    var mainAtCorner = makeClimber();
    sample(mainAtCorner, /* windowMax= */ 6144,
        /* windowHits= */ 600, /* mainHits= */ 0, /* misses= */ 400);
    assertThat(mainAtCorner.walk).isNull();

    var mainPastCorner = makeClimber();
    sample(mainPastCorner, /* windowMax= */ 6600,
        /* windowHits= */ 600, /* mainHits= */ 0, /* misses= */ 400);
    assertThat(mainPastCorner.walk).isNull();
  }

  @Test
  void armProbe_deadSampleDirection_splitsAtTheMidpoint() {
    // a dead sample probes away from the nearer bound, deciding at exactly half the capacity
    var down = makeClimber();
    sample(down, /* windowMax= */ 4096,
        /* windowHits= */ 0, /* mainHits= */ 0, /* misses= */ 1000);
    assertThat(walkOf(down).down).isTrue();

    var up = makeClimber();
    sample(up, /* windowMax= */ 4095,
        /* windowHits= */ 0, /* mainHits= */ 0, /* misses= */ 1000);
    assertThat(walkOf(up).down).isFalse();
  }

  @Test
  void densityStep_balancedShares_holdsFirm() {
    // At the proportional balance point the error is ~zero and the window rests. Flat terrain
    // costs nothing because convergence and holding still are the same mechanism (the reactive
    // climber's restart rule keeps it moving on the same terrain, at a measured 10.5pp cost)
    for (long windowMax : new long[] {1000, 2000, 4000, 6000}) {
      var climber = makeClimber();
      long adjustment = steadySample(climber, windowMax, /* hitRate= */ 0.8);
      assertThat(climber.walk).isNull();
      assertThat(Math.abs(adjustment)).isAtMost(2);
    }
  }

  @Test
  void densityStep_extremeImbalance_saturatesAtMaximumStep() {
    // a sighted region beside a blank one still saturates the step cap: the steering floor
    // prices "earned nothing" low, not infinitely low, and the cap bounds the displacement
    var climber = makeClimber();
    long adjustment = sample(climber, /* windowMax= */ 164,
        /* windowHits= */ 1000, /* mainHits= */ 0, /* misses= */ 0);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo((long) (MAX_STEP_FRACTION * MAXIMUM));
  }

  @Test
  void starvationBar_knifeEdge_probesOnlyBelowTheBar() {
    // The whisper family's exact mechanism: window earnings at the starvation bar are "sighted"
    // (no blind corner, no probe, since the equilibrium audit owns that case), while one hit fewer
    // is starved and arms the probe machinery. The bar's floor is 4 for these short samples.
    var sighted = makeClimber();
    long held = sample(sighted, /* windowMax= */ 164,
        /* windowHits= */ 4, /* mainHits= */ 800, /* misses= */ 196);
    assertThat(sighted.walk).isNull();
    assertThat(held).isAtMost(0);

    var starved = makeClimber();
    long probed = sample(starved, /* windowMax= */ 164,
        /* windowHits= */ 3, /* mainHits= */ 800, /* misses= */ 197);
    assertThat(walkOf(starved).down).isFalse();
    assertThat(probed).isEqualTo((long) STRIDE);
  }

  /* --------------- Guard rail and audits --------------- */

  @Test
  void guardRail_sustainedShortfall_vetoesBackToAnchor() {
    // the anchor forms at a steady, well-earning position; when the density arm walks away and
    // the smoothed rate settles measurably below the anchor's claim, the veto returns there and
    // parks. The climber refuses to remain somewhere worse than a place it has already been
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.anchor.rate).isWithin(1.0e-6).of(0.70);

    @Var long windowMax = 4000;
    @Var boolean vetoed = false;
    for (int i = 0; i < 40; i++) {
      long adjustment = steadySample(climber, windowMax, /* hitRate= */ 0.68);
      if (climber.anchor.returning || climber.anchor.held) {
        vetoed = true;
        assertThat(adjustment).isAtMost(0);
        windowMax += adjustment;
      }
      if (climber.anchor.held && !climber.anchor.returning) {
        break;
      }
    }
    assertThat(vetoed).isTrue();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.isAt(windowMax, WindowClimber.Reading.stableBand(MAXIMUM))).isTrue();

    long held = steadySample(climber, windowMax, /* hitRate= */ 0.68);
    assertThat(held).isEqualTo(0);
  }

  @Test
  void guardRail_onAnchor_rateClaimTracksReality() {
    // standing on the anchor re-syncs its claim to the live measurement, so a workload whose
    // rate genuinely fell does not leave a stale claim that vetoes forever
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    for (int i = 0; i < 40; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.66);
    }
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.anchor.rate).isWithin(0.005).of(0.66);
    assertThat(climber.anchor.held).isFalse();
  }

  @Test
  void guardRail_crashScaleShift_nearAnchor_standsDown() {
    // a crash-scale swing at the anchor's own position is a claim tested and found wrong: the
    // guard discards it and re-learns rather than dragging the window to a stale reference
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.55);
    assertThat(climber.anchor.isPlanted()).isFalse();
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.anchor.shortfallStreak).isEqualTo(0);
  }

  @Test
  void guardRail_crashScaleShift_nearAnchor_reseedsTheGoalMetric() {
    // the swing that discards a claim also invalidates the reference the claim was measured
    // against: an EMA smoothed across the shift is mostly the regime that just ended, and the
    // next sample re-plants the anchor from it, so the layer would carry a claim the workload
    // can no longer produce and no walk could clear at any position
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.20);
    assertThat(climber.anchor.isPlanted()).isFalse();
    assertThat(climber.rates.smoothed).isWithin(1.0e-6).of(0.20);
    assertThat(climber.rates.deviation).isWithin(1.0e-6).of(DEVIATION_SEED);

    // the re-plant on the next sample claims the new regime's rate, not a blend of both
    steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.20);
    assertThat(climber.anchor.window).isEqualTo(5000);
    assertThat(climber.anchor.rate).isWithin(0.01).of(0.20);
  }

  @Test
  void guardRail_crashScaleShift_farFromAnchor_keepsAnchor() {
    // a crash far from the anchor is not evidence against it. That shape is typically the
    // controller's own retreat crossing a band edge, exactly what the veto exists to catch, so
    // neither the claim nor the reference it was measured against is thrown away
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.55);
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.anchor.shortfallStreak).isEqualTo(0);
    assertThat(climber.rates.smoothed).isWithin(1.0e-6).of(0.67);
  }

  @Test
  void auditClock_rateSwings_doNotResetStillness() {
    // stillness is a property of the position, not the rate: a periodic crash-scale swing at a
    // motionless window must not hold the audit clock below its arming wait forever
    var climber = makeClimber();
    @Var boolean armed = false;
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      double hitRate = ((i % 2) == 0) ? 0.70 : 0.60;
      steadySample(climber, /* windowMax= */ 2000, hitRate);
      if (climber.walk != null) {
        armed = true;
        break;
      }
    }
    assertThat(armed).isTrue();
    assertThat(walkOf(climber).isAudit).isTrue();
  }

  @Test
  void auditClock_firstSampleAfterAResize_isStillWhereverItRests() {
    // nothing may move the window before the first sample closes, so that sample is a stillness
    // observation wherever the resize left it; the unrecorded position is not a place to compare
    // against, and comparing it makes the calibration audit's schedule depend on the geometry
    var atSplit = makeClimber();
    steadySample(atSplit, /* windowMax= */ 82, /* hitRate= */ 0.70);
    assertThat(atSplit.auditClock.stillSamples).isEqualTo(1);

    var elsewhere = makeClimber();
    steadySample(elsewhere, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    assertThat(elsewhere.auditClock.stillSamples).isEqualTo(1);
  }

  @Test
  void guardRail_noAnchorSeedWhileProbing() {
    // a probe's transient window paired with an EMA earned elsewhere is a phantom claim; the
    // seed waits until the walk resolves
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    climber.anchor.window = -1;
    climber.rates.smoothed = 0.5;
    // the watched region stays under the adjudication bar, so the walk continues
    long walking = sample(climber, /* windowMax= */ 3000,
        /* windowHits= */ 5, /* mainHits= */ 515, /* misses= */ 480);
    assertThat(walking).isNotEqualTo(0);
    assertThat(climber.walk).isNotNull();
    assertThat(climber.anchor.window).isEqualTo(-1);
  }

  @Test
  void audit_freshPark_shieldLastsItsWholeSchedule() {
    // The shield's LENGTH, not merely its existence. A confirm parks for one initial audit wait,
    // and the park must survive crash-scale weather for exactly that many samples: a shorter
    // shield releases a validated position into the first squall, and one that never ages holds
    // a position the workload has left. The sibling test spends the shield by hand, which pins
    // only that it covers more than one sample.
    var climber = armAudit(/* windowMax= */ 2000);
    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; (climber.walk != null) && (i < PROBE_WALK_BUDGET); i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.74);
    }
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.freshLeft).isEqualTo(AUDIT_WAIT_INITIAL);

    // alternating crash-scale swings would stand the anchor down on every sample but for the
    // shield, so the release lands on the sample that spends its last count
    @Var int releasedAt = -1;
    for (int i = 1; i <= (2 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, walked, /* hitRate= */ ((i % 2) == 0) ? 0.80 : 0.74);
      if (!climber.anchor.held) {
        releasedAt = i;
        break;
      }
    }
    assertThat(releasedAt).isEqualTo(AUDIT_WAIT_INITIAL);
    assertThat(climber.anchor.freshLeft).isEqualTo(0);
  }

  @Test
  void auditClock_restart_makesTheNextAuditWaitOutTheStillnessAgain() {
    // Arming an audit restarts the stillness run. Asserting the wait variable does not pin that:
    // a long-still position carries its whole run into the walk, so an audit that ends quickly
    // would be due again immediately and the position is re-tested continuously rather than on
    // a schedule. Driven at the standard wait, where the run is long enough for the residue to
    // show; the cold-start wait is too short to tell the two apart.
    var climber = makeClimber();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    @Var int armedAt = -1;
    for (int i = 1; i <= (4 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        armedAt = i;
        break;
      }
    }
    assertThat(armedAt).isAtLeast(AUDIT_WAIT_INITIAL);
    // the arm restarts the run and this sample's own tick is the first of the new one
    assertThat(climber.auditClock.stillSamples).isEqualTo(1);

    // a crash-scale drop ends the walk on its first sample, so almost none of the run is spent
    steadySample(climber, /* windowMax= */ 2000 + climber.adjustment(), /* hitRate= */ 0.40);
    assertThat(climber.walk).isNull();

    // the next audit waits out a fresh run rather than inheriting the one the arm consumed
    @Var int rearmedAt = -1;
    for (int i = 1; i <= (4 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.40);
      if (climber.walk != null) {
        rearmedAt = i;
        break;
      }
    }
    assertThat(rearmedAt).isAtLeast(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void audit_armsAtQuietEquilibrium_andBacksOffOnFailure() {
    // a sighted equilibrium is re-tested by the probe machinery: the FIRST audit is the
    // cold-start calibration probe and arms after a short stillness wait (a sighted false
    // equilibrium otherwise pins for the full initial wait that a short trace mostly spends
    // motionless); an unconfirmed audit then retries on the ladder's cadence and doubles its
    // own clock so a settled workload is re-examined ever more rarely
    var climber = makeClimber();
    @Var int armedAt = -1;
    for (int i = 0; i < 80; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        armedAt = i;
        break;
      }
    }
    assertThat(armedAt).isAtLeast(AUDIT_WAIT_FIRST - 1);
    assertThat(armedAt).isLessThan(AUDIT_WAIT_INITIAL - 1);
    var walk = walkOf(climber);
    assertThat(walk.isAudit).isTrue();
    assertThat(walk.down).isTrue();
    assertThat(climber.adjustment()).isEqualTo((long) -STRIDE);

    // The audit is adjudicated by the goal metric, not density: it walks its whole budget while
    // the smoothed rate fails to clear the anchor's margin, then undoes in full with the retry
    // paced by the deepening ladder.
    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; i < (2 * PROBE_WALK_BUDGET); i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.70);
      if ((climber.walk == null) && (climber.undoRemaining == 0)) {
        break;
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(walked).isEqualTo(2000);
    assertThat(climber.auditClock.waitSamples).isEqualTo(climber.audit.rung);
    assertThat(climber.auditClock.waitSamples).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void audit_sustainedImprovement_confirmsAndParks() {
    // an audit that finds a sustained improvement (a streak of raw samples above the frozen
    // reference) keeps the position and parks there: density disagreed with the position by
    // construction, so steering would walk the paid-for escape home; the audit clock owns
    // re-exploration from the parked equilibrium
    var climber = makeClimber();
    for (int i = 0; i < AUDIT_WAIT_INITIAL + 2; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        break;
      }
    }
    assertThat(walkOf(climber).isAudit).isTrue();
    climber.anchor.held = true;

    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; i < PROBE_WALK_BUDGET; i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.74);
      if (climber.walk == null) {
        break;
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(walked).isNotEqualTo(2000);
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_INITIAL);
    assertThat(climber.starvation.rung).isEqualTo(1);

    // the goal-validated position is the guard rail's anchor at once and the park holds it
    assertThat(climber.anchor.window).isEqualTo(walked);
    assertThat(climber.anchor.rate).isWithin(1.0e-9).of(climber.rates.smoothed);
    long held = steadySample(climber, walked, /* hitRate= */ 0.74);
    assertThat(held).isEqualTo(0);
  }

  @Test
  void audit_confirmSample_takesNoSteeringStep() {
    // the confirm's own sample must not fall through to a density step: density disagrees with
    // the validated position by construction, so a parting step would displace the park off the
    // position the walk just paid for, leaving the anchor defending a claim the window does not
    // occupy; the same walk under a skewed region split pins the confirm sample's return at zero
    var climber = armAudit(/* windowMax= */ 2000);
    @Var long walked = 2000 + climber.adjustment();
    @Var long confirmStep = -1;
    for (int i = 0; (climber.walk != null) && (i < (2 * PROBE_WALK_BUDGET)); i++) {
      confirmStep = sample(climber, walked,
          /* windowHits= */ 600, /* mainHits= */ 140, /* misses= */ 260);
      walked += confirmStep;
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(confirmStep).isEqualTo(0);
    assertThat(climber.anchor.window).isEqualTo(walked);
  }

  @Test
  void probeEnding_starvationConfirm_doesNotPark() {
    // a density-agreed confirm needs no park: steering agrees with the position it validated,
    // so the climber stays live (cheap re-probing is load-bearing on phase alternation); nor
    // does it touch the audit clock, which is the audit layer's own state
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.anchor.freshLeft).isEqualTo(0);
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_FIRST);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 5, /* mainHits= */ 900));
  }

  @Test
  void probeEnding_starvationConfirm_leavesTheAuditClock() {
    // A starvation confirm once reset the audit clock to the standard wait, silently spending
    // the cold-start calibration schedule before the first audit ever ran, and 32 quiet
    // samples is a run long enough for ordinary burstiness to keep breaking, which switched
    // the audit layer off permanently (the position jam's easy regime). The schedule survives
    // the confirm and the calibration audit still arms early from the validated landing.
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);
    assertThat(climber.walk).isNull();
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_FIRST);

    @Var int armedAt = -1;
    for (int i = 0; i < AUDIT_WAIT_INITIAL; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        armedAt = i;
        break;
      }
    }
    assertThat(armedAt).isAtLeast(AUDIT_WAIT_FIRST - 1);
    assertThat(armedAt).isLessThan(AUDIT_WAIT_INITIAL - 1);
    assertThat(walkOf(climber).isAudit).isTrue();

    // a deep post-failure schedule survives the confirm unchanged as well
    var deep = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    deep.auditClock.waitSamples = AUDIT_WAIT_MAX;
    sample(deep, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);
    assertThat(deep.walk).isNull();
    assertThat(deep.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_MAX);
  }

  @Test
  void probeEnding_starvationConfirm_leavesTheAuditLadder() {
    // The audit's rung and crash evidence are its own ledger: a starvation confirm resets the
    // starvation ladder so re-probing turns cheap, but must not shallow the deep stride and
    // commitment that a searched equilibrium's next re-test earned.
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    climber.audit.rung = PROBE_BACKOFF_MAX;
    climber.audit.crashStreak = 1;
    sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.audit.crashStreak).isEqualTo(1);
    assertThat(climber.audit.rung).isEqualTo(PROBE_BACKOFF_MAX);
  }

  @Test
  void audit_interruptedImprovement_failsTheWalk() {
    // the confirm is a consecutive-sample streak on RAW rates, not a level test on the smoothed
    // rate: a periodic dip below the reference resets it, so scattered above-reference samples
    // whose average clears the bar cannot confirm (the smoothed form confirmed exactly this
    // shape, which is how workload weather bought false confirms)
    var climber = armAudit(/* windowMax= */ 2000);
    double[] cycle = {0.74, 0.74, 0.74, 0.703};
    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; (climber.walk != null) && (i < (2 * PROBE_WALK_BUDGET)); i++) {
      walked += steadySample(climber, walked, cycle[i % cycle.length]);
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.audit.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void audit_walkThatNeverBeatsItsStart_cannotConfirm() {
    // the confirm's reference is the smoothed rate frozen at arm, and it can be colder than the
    // walk, most sharply for the cold-start calibration audit, where the smoothing still carries
    // the rate the cache earned while filling. A streak against that reference completes on the
    // warm-up ramp alone, so the confirm also requires the walk to have out-earned the sample it
    // started from at least once: here every walk sample clears the reference by a wide margin
    // yet none beats the arming rate, and the walk fails.
    var climber = makeClimber();
    for (int i = 0; i < AUDIT_WAIT_INITIAL + 2; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        break;
      }
    }
    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(rebaseReference(climber, 0.40).baseHitRate).isEqualTo(0.70);

    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; (climber.walk != null) && (i < (2 * PROBE_WALK_BUDGET)); i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.69);
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.audit.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void audit_walkThatMatchesASaturatedStart_stillConfirms() {
    // the gate is inclusive and takes no margin: an arming sample that saturated makes any
    // strictly-greater bar unsatisfiable, which would silently disable every later confirm
    var climber = makeClimber();
    for (int i = 0; i < AUDIT_WAIT_INITIAL + 2; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 1.0);
      if (climber.walk != null) {
        break;
      }
    }
    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(rebaseReference(climber, 0.40).baseHitRate).isEqualTo(1.0);

    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; (climber.walk != null) && (i < PROBE_WALK_BUDGET); i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 1.0);
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
  }

  @Test
  void audit_freshPark_ridesOutCrashWeather() {
    // a just-confirmed park survives crash-scale rate swings for one initial audit wait: on a
    // workload whose per-sample scatter reaches the restart threshold, the stand-down would
    // otherwise release the validated position within a few samples and density re-balloons;
    // after the shield lapses, the stand-down releases as before
    var climber = armAudit(/* windowMax= */ 2000);
    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; (climber.walk != null) && (i < PROBE_WALK_BUDGET); i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.74);
    }
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(walked);

    long held = steadySample(climber, walked, /* hitRate= */ 0.80);
    assertThat(held).isEqualTo(0);
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(walked);

    climber.anchor.freshLeft = 0;
    steadySample(climber, walked, /* hitRate= */ 0.74);
    // released: the park no longer holds (the discarded anchor re-seeds at the freed position
    // in the same sample, so the release is observable through the hold alone), and the shield
    // dies with it
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.anchor.freshLeft).isEqualTo(0);
  }

  @Test
  void guardRail_vetoPark_isNotCrashShielded() {
    // the fresh-park shield belongs to audit confirms alone: a guard-rail veto's park earned no
    // validation walk, so the very next crash-scale swing releases it (a starvation confirm
    // between audits must not leave a stale shield for the veto to inherit. The shield lives
    // and dies with the park)
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    for (int i = 0; i < VETO_STREAK; i++) {
      steadySample(climber, /* windowMax= */ 1500, /* hitRate= */ 0.66);
    }
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.freshLeft).isEqualTo(0);

    steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.60);
    assertThat(climber.anchor.held).isFalse();
  }

  @Test
  void audit_confirmUsesReferenceFrozenAtArm() {
    // a mid-walk move of the smoothed rate, or a re-plant of the anchor, must not move the bar the
    // audit is judged against; the reference freezes when the probe arms
    var climber = makeClimber();
    for (int i = 0; i < AUDIT_WAIT_INITIAL + 2; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        break;
      }
    }
    assertThat(walkOf(climber).isAudit).isTrue();
    climber.rates.smoothed = 0.99;
    climber.anchor.rate = 0.99;

    @Var long walked = 2000 + climber.adjustment();
    @Var boolean confirmed = false;
    for (int i = 0; i < PROBE_WALK_BUDGET; i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.74);
      if (climber.walk == null) {
        confirmed = (climber.starvation.rung == 1);
        break;
      }
    }
    assertThat(confirmed).isTrue();
  }

  @Test
  void audit_afterARegimeShift_confirmsAgainstTheNewRegime() {
    // the stale-claim defect end to end: with the reference smoothed across a crash-scale shift,
    // the re-planted claim keeps most of the old regime's rate, and since the claim only re-syncs
    // while the window stands on it, one steering step away freezes it. Every later audit then
    // fails at every position, escalating the ladder until the layer stops trying
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.20);

    @Var boolean armed = false;
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.20);
      if (climber.walk != null) {
        armed = true;
        break;
      }
    }
    assertThat(armed).isTrue();
    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(walkOf(climber).baseSmoothedRate).isLessThan(0.25);

    // a position the new regime can actually hold clears the bar and is kept
    @Var long walked = 5000 + climber.adjustment();
    for (int i = 0; i < PROBE_WALK_BUDGET; i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.24);
      if (climber.walk == null) {
        break;
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(walked);
  }

  @Test
  void audit_afterAnAwayShift_confirmsAgainstTheNewRegime() {
    // the stale claim's away-anchor case end to end: the window rests off the anchor when the
    // regime shifts, so the swing does not test the claim and the guard keeps it, as it must for
    // the terrain's own collapses at a still window that the rail then recovers from. Measured
    // against that claim, an audit that walked to a far better position would fail at budget; the
    // walk is measured against the rate it left instead, and confirms on the new regime
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    for (int i = 0; i < 4; i++) {
      steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.70);
    }
    steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.20);
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.anchor.rate).isWithin(0.01).of(0.70);

    // the clock is not due at the shift, as on the witness, so the smoothing has re-learned the
    // regime by the time the audit arms
    climber.auditClock.restart();
    @Var boolean armed = false;
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.20);
      if (climber.walk != null) {
        armed = true;
        break;
      }
    }
    assertThat(armed).isTrue();
    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(climber.anchor.rate).isWithin(0.01).of(0.70);
    assertThat(walkOf(climber).baseSmoothedRate).isLessThan(0.25);

    // a position the new regime can actually hold clears the bar and is kept
    @Var long walked = 5000 + climber.adjustment();
    for (int i = 0; i < PROBE_WALK_BUDGET; i++) {
      walked += steadySample(climber, walked, /* hitRate= */ 0.24);
      if (climber.walk == null) {
        break;
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(walked);
  }

  @Test
  void audit_retryAfterConfirm_flooredAtInitialBackoff() {
    // a confirm resets the starvation ladder to 1, but the audit clock is paced by the audit
    // layer's own rung, which never sinks below the initial refractory. A confirm-cheapened
    // starvation ladder cannot leak a 1-2 sample retry cadence into the audit schedule
    var climber = makeClimber();
    climber.starvation.rung = 1;
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
    walk.samples = PROBE_WALK_BUDGET;
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.70);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.audit.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.auditClock.waitSamples).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void audit_directionByPosition_needsRoomToStride() {
    // an audit explores toward the farther rail and refuses a direction with less than one
    // stride of room: a walk sent into a nearer wall clamps on its entry stride, stands still
    // for the rest of its budget, and the information-free expiry is priced as a failed
    // experiment, doubling both the refractory ladder and the audit clock
    var low = armAudit(/* windowMax= */ 300);
    assertThat(walkOf(low).down).isFalse();
    assertThat(low.adjustment()).isEqualTo((long) STRIDE);

    var atTwiceTheFloor = armAudit(/* windowMax= */ 327);
    assertThat(walkOf(atTwiceTheFloor).down).isFalse();

    var interior = armAudit(/* windowMax= */ 328);
    assertThat(walkOf(interior).down).isFalse();

    var lastWithoutRoom = armAudit(/* windowMax= */ (long) (FLOOR + STRIDE));
    assertThat(walkOf(lastWithoutRoom).down).isFalse();

    var firstWithRoom = armAudit(/* windowMax= */ (long) (FLOOR + STRIDE) + 1);
    assertThat(walkOf(firstWithRoom).down).isTrue();

    var high = armAudit(/* windowMax= */ 6500);
    assertThat(walkOf(high).down).isTrue();
    assertThat(high.adjustment()).isEqualTo((long) -STRIDE);
  }

  @Test
  void audit_direction_roomIsMeasuredAgainstTheRungsOwnStride() {
    // the refusal and the walk must price the same move: at the deepest rung a walk strides four
    // times the flat magnitude, so a position with room for the flat stride but not the scaled one
    // has to be refused. Admitting it clamps the entry stride at the floor, and the walk then
    // stands still for its whole budget before an information-free expiry doubles the audit clock
    @SuppressWarnings("Varifier")
    long windowMax = (long) (FLOOR + (3 * STRIDE));

    var deep = armAuditAtRung(windowMax, PROBE_BACKOFF_MAX);
    assertThat(walkOf(deep).down).isFalse();

    var shallow = armAuditAtRung(windowMax, PROBE_BACKOFF_INITIAL);
    assertThat(walkOf(shallow).down).isTrue();
    assertThat(shallow.adjustment()).isEqualTo((long) -STRIDE);
  }

  @Test
  void audit_direction_roomRefusalOverridesAlternation() {
    // near a rail, alternation would point every other audit into the wall; the refusal
    // re-flips it so each audit walks the only informative direction
    var climber = makeClimber();
    @Var int audits = 0;
    for (int i = 0; (i < 300) && (audits < 2); i++) {
      steadySample(climber, /* windowMax= */ 500, /* hitRate= */ 0.70);
      var walk = climber.walk;
      if ((walk != null) && (walk.samples == 1)) {
        audits++;
        assertThat(walk.isAudit).isTrue();
        assertThat(walk.down).isFalse();
      }
    }
    assertThat(audits).isEqualTo(2);
  }

  @Test
  void audit_streakBoundary_confirmsAtExactlyFourConsecutive() {
    // pins AUDIT_CONFIRM_STREAK's exact boundary: an interrupted opening resets the run, four
    // consecutive above-reference samples then complete it, and the confirm fires on that
    // fourth sample, not one earlier and needing no fifth
    var climber = armAudit(/* windowMax= */ 2000);
    double[] rates = {0.703, 0.74, 0.74, 0.74, 0.74};
    @Var long walked = 2000 + climber.adjustment();
    for (int i = 0; i < rates.length; i++) {
      walked += steadySample(climber, walked, rates[i]);
      if (i < (rates.length - 1)) {
        assertThat(climber.walk).isNotNull();
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
  }

  @Test
  void audit_direction_ceilingSideRoomRefusal() {
    // the mirror of the floor-side refusal: at an interior position within one stride of the
    // ceiling guard, alternation's up-turn is refused back downward, so every audit walks the
    // only informative direction
    var climber = makeClimber();
    @Var int audits = 0;
    for (int i = 0; (i < 300) && (audits < 2); i++) {
      steadySample(climber, /* windowMax= */ 5800, /* hitRate= */ 0.70);
      var walk = climber.walk;
      if ((walk != null) && (walk.samples == 1)) {
        audits++;
        assertThat(walk.isAudit).isTrue();
        assertThat(walk.down).isTrue();
      }
    }
    assertThat(audits).isEqualTo(2);
  }

  @Test
  void audit_ceilingDirection_isSticky() {
    // a ceiling-adjacent equilibrium is forced downward on every audit. It never alternates,
    // since the wall above it has nothing to test (the caller pins the position, a rail)
    var climber = makeClimber();
    @Var int audits = 0;
    for (int i = 0; (i < 150) && (audits < 2); i++) {
      steadySample(climber, /* windowMax= */ 6500, /* hitRate= */ 0.70);
      var walk = climber.walk;
      if ((walk != null) && (walk.samples == 1)) {
        audits++;
        assertThat(walk.isAudit).isTrue();
        assertThat(walk.down).isTrue();
      }
    }
    assertThat(audits).isEqualTo(2);
  }

  @Test
  void auditClock_stillnessToleratesTheOrbit() {
    // held equilibria orbit rather than rest, since the transfer geometry and per-sample noise keep
    // the window moving a little, so stillness is a band: an orbit within it accumulates
    // toward the audit, while a wander beyond it on every sample starves the clock
    var orbiting = makeClimber();
    @Var boolean armed = false;
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      long windowMax = ((i % 2) == 0) ? 2000 : 2163;
      steadySample(orbiting, windowMax, /* hitRate= */ 0.70);
      if (orbiting.walk != null) {
        armed = true;
        break;
      }
    }
    assertThat(armed).isTrue();

    var wandering = makeClimber();
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      long windowMax = ((i % 2) == 0) ? 2000 : 2164;
      steadySample(wandering, windowMax, /* hitRate= */ 0.70);
    }
    assertThat(wandering.walk).isNull();
  }

  @Test
  void auditClock_periodicSuperBandMotion_decaysInsteadOfResetting() {
    // the position jam: a workload that moves the window past the band once per wait held a
    // hard-reset clock at zero forever, so the equilibrium was never re-tested. A moving
    // sample must decay the run instead, letting mostly-still stretches accumulate while the
    // every-sample wander above still starves the clock
    var climber = makeClimber();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    @Var boolean armed = false;
    for (int i = 0; i < (3 * AUDIT_WAIT_INITIAL); i++) {
      long windowMax = (((i / 8) % 2) == 0) ? 2000 : 2400;
      steadySample(climber, windowMax, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        armed = true;
        break;
      }
    }
    assertThat(armed).isTrue();
    assertThat(walkOf(climber).isAudit).isTrue();
  }

  @Test
  void auditClock_halfMotionAlternation_neverAccumulates() {
    // the decay's analytic boundary: motion on half the samples is net-zero drift, so the
    // clock must never accumulate to an arm. The periodic-motion pin above sits on the
    // accumulating side of this line, the every-sample wander beyond it (a steeper decay
    // would move the boundary and un-jam nothing, since partial-motion cadences are not
    // realizable by steering; only this net-zero arithmetic keeps the two pins honest)
    var climber = makeClimber();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    for (int i = 0; i < (8 * AUDIT_WAIT_INITIAL); i++) {
      long windowMax = (((i / 2) % 2) == 0) ? 2000 : 2400;
      steadySample(climber, windowMax, /* hitRate= */ 0.70);
      assertThat(climber.walk).isNull();
    }
  }

  @Test
  void audit_interiorDirection_alternatesAcrossAudits() {
    // interior equilibria are searched in both directions across successive audits, so a false
    // equilibrium whose escape lies opposite the first walk is still found
    var climber = makeClimber();
    @Var long windowMax = 2000;
    @Var boolean firstDown = false;
    @Var int audits = 0;
    for (int i = 0; (i < 200) && (audits < 2); i++) {
      var walk = climber.walk;
      if ((walk != null) && walk.isAudit && (walk.samples == 1)) {
        audits++;
        if (audits == 1) {
          firstDown = walk.down;
        } else {
          assertThat(walk.down).isEqualTo(!firstDown);
        }
      }
      long adjustment = steadySample(climber, windowMax, /* hitRate= */ 0.70);
      windowMax = Math.max(164, windowMax + adjustment);
      if ((climber.walk == null) && (climber.undoRemaining == 0) && !climber.anchor.returning) {
        windowMax = 2000;
      }
    }
    assertThat(audits).isEqualTo(2);
  }

  @Test
  void audit_parksFirstAudit_followsTheConfirmedWalk() {
    // A confirm ends a walk on evidence of improvement rather than its exhaustion, so the ground
    // beyond it is the unexplored side: the park's first audit continues that direction, where the
    // alternation would send it back through the ground the walk just covered
    var climber = confirmedAuditPark(/* windowMax= */ 5000, /* baseRate= */ 0.70,
        /* walkedRate= */ 0.74);
    assertThat(climber.anchor.window).isLessThan(5000L);
    armNextAudit(climber, climber.anchor.window, /* hitRate= */ 0.74);
    assertThat(walkOf(climber).down).isTrue();

    // and the audit after that alternates
    var settled = confirmedAuditPark(/* windowMax= */ 5000, /* baseRate= */ 0.70,
        /* walkedRate= */ 0.74);
    long parked = settled.anchor.window;
    armNextAudit(settled, parked, /* hitRate= */ 0.74);
    for (int i = 0; (i < PROBE_WALK_BUDGET) && (settled.walk != null); i++) {
      steadySample(settled, parked, /* hitRate= */ 0.74);
    }
    assertThat(settled.walk).isNull();
    settled.refractoryLeft = 0;
    settled.undoRemaining = 0;
    armNextAudit(settled, parked, /* hitRate= */ 0.74);
    assertThat(walkOf(settled).down).isFalse();
  }

  @Test
  void audit_parksFirstAudit_alternatesWhenTheClaimMoved() {
    // a smoothed rate that moved by a restart threshold while the window stood still says the
    // workload moved, and the walk's direction says nothing about the terrain (the trend-misconfirm
    // family): the alternation stands. So does a park that was stood down before its first audit
    var trended = confirmedAuditPark(/* windowMax= */ 5000, /* baseRate= */ 0.70,
        /* walkedRate= */ 0.74);
    armNextAudit(trended, trended.anchor.window, /* hitRate= */ 0.74 + RESTART_THRESHOLD + 0.01);
    assertThat(walkOf(trended).down).isFalse();

    var released = confirmedAuditPark(/* windowMax= */ 5000, /* baseRate= */ 0.70,
        /* walkedRate= */ 0.74);
    long parked = released.anchor.window;
    released.anchor.release();
    armNextAudit(released, parked, /* hitRate= */ 0.74);
    assertThat(walkOf(released).down).isFalse();
  }

  @Test
  void audit_commitmentDepth_blocksAnImmediateConfirm() {
    // the verdict needs samples along the walk before it says anything, so an audit may not
    // confirm before its committed depth even when every stride clears the frozen reference
    // from the first sample onward
    var climber = armAudit(/* windowMax= */ 2000);
    @Var long walked = 2000 + climber.adjustment();
    for (int depth = 1; depth <= AUDIT_COMMITMENT; depth++) {
      long adjustment = steadySample(climber, walked, /* hitRate= */ 0.78);
      walked = Math.max(164, walked + adjustment);
      if (depth < AUDIT_COMMITMENT) {
        assertThat(climber.walk).isNotNull();
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.starvation.rung).isEqualTo(1);
  }

  @Test
  void audit_confirm_parksAtTheWalksBestSample() {
    // the streak needs four samples above the reference and the walk strides on through all four,
    // so a crest crossed on the way is behind the window by the time the verdict comes in. The
    // confirm is for the position the walk earned most at, and the window returns there: on
    // crestpast the calibration audit crosses the optimum on its first stride and confirms four
    // strides past it, and the position it parks on is what it then defends and re-tests
    var climber = makeClimber();
    injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.40);
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    climber.step.size = STRIDE;

    double[] rates = {0.80, 0.78, 0.77, 0.76, 0.755, 0.75};
    @Var long confirmStep = 0;
    for (int i = 0; i < rates.length; i++) {
      confirmStep = steadySample(climber, /* windowMax= */ 2200 + (200 * i), rates[i]);
    }

    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(2200);
    // the confirm's own sample commands the return rather than standing where the streak ended,
    // and a return within one step arrives on that sample
    assertThat(confirmStep).isEqualTo(2200 - 3200);
    assertThat(climber.anchor.returning).isFalse();
  }

  @Test
  void audit_confirm_onAnImprovingWalk_parksWhereItStopped() {
    // a walk still improving when its streak completes has its best sample last, so the verdict is
    // the position it stands on, and a walk over a flat plateau has a best sample by noise alone,
    // which the verdict's margin refuses: both park where the walk ended and command no return
    double[][] walks = {{0.72, 0.73, 0.74, 0.75, 0.76, 0.80}, {0.75, 0.755, 0.75, 0.752, 0.75, 0.753}};
    for (double[] rates : walks) {
      var climber = makeClimber();
      injectWalk(climber, /* isAudit= */ true, /* down= */ false,
          /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.40);
      climber.rates.smoothed = 0.70;
      climber.sample.previousHitRate = 0.70;
      climber.step.size = STRIDE;

      @Var long confirmStep = 0;
      for (int i = 0; i < rates.length; i++) {
        confirmStep = steadySample(climber, /* windowMax= */ 2200 + (200 * i), rates[i]);
      }

      assertThat(climber.walk).isNull();
      assertThat(climber.anchor.window).isEqualTo(3200);
      assertThat(climber.anchor.returning).isFalse();
      assertThat(confirmStep).isEqualTo(0);
    }
  }

  @Test
  void audit_retreatLandingAtThePark_keepsTheAnchor() {
    // the retreat that ends a park's audit restores the rate the walk had spent, and that
    // recovery is a crash-scale move at the anchor's own position, where a workload shift
    // discards the claim. It is the machine's own move, so the park survives its own re-test
    var climber = armAudit(/* windowMax= */ 2000);
    climber.anchor.held = true;
    climber.anchor.freshLeft = 0;
    climber.anchor.plant(2000, /* claimed= */ 0.70);
    long walked = 2000 + climber.adjustment();

    long undo = steadySample(climber, walked, /* hitRate= */ 0.50);
    assertThat(climber.walk).isNull();
    assertThat(undo).isEqualTo(2000 - walked);
    assertThat(climber.retreatLeft).isEqualTo(RETREAT_COVER);

    // the landing sample: the rate returns to what the park earns, a crash-scale step up
    long held = steadySample(climber, 2000, /* hitRate= */ 0.70);
    assertThat(held).isEqualTo(0);
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.retreatLeft).isEqualTo(RETREAT_COVER - 1);

    // and the cover runs out with it: the next crash-scale move is the workload's again
    steadySample(climber, 2000, /* hitRate= */ 0.70);
    assertThat(climber.retreatLeft).isEqualTo(0);
    steadySample(climber, 2000, /* hitRate= */ 0.30);
    assertThat(climber.anchor.isPlanted()).isFalse();
  }

  @Test
  void audit_deepestRungFailure_doublesTheWaitToItsCap() {
    // failures below the deepest rung retry on the ladder's cadence (the escalating search);
    // only a failure at the deepest rung (a genuinely searched equilibrium) doubles the
    // audit clock, so a settled workload is re-tested ever more rarely, up to the cap
    int[] expected = {2 * PROBE_BACKOFF_MAX, 4 * PROBE_BACKOFF_MAX, AUDIT_WAIT_MAX,
        AUDIT_WAIT_MAX};
    var climber = makeClimber();
    climber.auditClock.waitSamples = PROBE_BACKOFF_MAX;
    for (int expectedWait : expected) {
      var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
          /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
      walk.samples = PROBE_WALK_BUDGET;
      climber.rates.smoothed = 0.70;
      climber.sample.previousHitRate = 0.70;
      climber.audit.rung = PROBE_BACKOFF_MAX;
      steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.70);

      assertThat(climber.walk).isNull();
      assertThat(climber.auditClock.waitSamples).isEqualTo(expectedWait);
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
  }

  @Test
  void audit_deepestRungCrash_retriesOnTheLadderCadence() {
    // a lone crash is priced as a workload shift, not a searched failure, so an audit ending in
    // one keeps the ladder's cadence even at the deepest rung. Doubling the clock there would
    // defer the very re-exploration the shift calls for; a first crash aborts on its first
    // below-bar sample (tolerance is the escalated response, not the default)
    var climber = makeClimber();
    climber.auditClock.waitSamples = 4 * PROBE_BACKOFF_MAX;
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
    walk.samples = PROBE_WALK_BUDGET - 10;
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    climber.audit.rung = PROBE_BACKOFF_MAX;
    steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.60);

    assertThat(climber.walk).isNull();
    assertThat(climber.audit.crashStreak).isEqualTo(1);
    assertThat(climber.audit.rung).isEqualTo(PROBE_BACKOFF_MAX);
    assertThat(climber.auditClock.waitSamples).isEqualTo(PROBE_BACKOFF_MAX);
  }

  @Test
  void audit_deepestRungEscalatedCrash_keepsTheLadderCadence() {
    // A cycle of crashes escalates the audit's own rung like a completed failure, but the crash
    // never takes the failure's clock doubling. Even escalated, a crash is priced as a
    // workload shift, and deferring the retry beyond the ladder's cadence let three exogenous
    // pulses ratchet the clock to a 128-sample stand-down at the window floor. The prior crash
    // also arms tolerance: the retry of a crashing equilibrium rides out two below-bar samples
    // and aborts on the third.
    var climber = makeClimber();
    climber.auditClock.waitSamples = PROBE_BACKOFF_MAX;
    climber.audit.crashStreak = 1;
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
    walk.samples = PROBE_WALK_BUDGET - 10;
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    climber.starvation.rung = PROBE_BACKOFF_INITIAL;
    climber.audit.rung = PROBE_BACKOFF_MAX;
    steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.60);
    assertThat(climber.walk).isNotNull();
    steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.60);
    assertThat(climber.walk).isNotNull();
    steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.60);

    assertThat(climber.walk).isNull();
    assertThat(climber.audit.crashStreak).isEqualTo(2);
    assertThat(climber.audit.rung).isEqualTo(PROBE_BACKOFF_MAX);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
    assertThat(climber.auditClock.waitSamples).isEqualTo(PROBE_BACKOFF_MAX);
  }

  @Test
  void audit_loneCrashTrain_neverDefersBeyondTheLadder() {
    // The pulse train: three separate audits, each killed by an exogenous rate pulse,
    // with nothing but quiet steering between them. The crash streak pairs across the train
    // and deepens the audit rung to its cap, but the wait never takes the failure's doubling
    // (the shared-ladder form ratcheted to a 128-sample stand-down at the window floor) and
    // the starvation ladder never moves.
    var climber = makeClimber();
    int[] expectedRung = {PROBE_BACKOFF_INITIAL, 2 * PROBE_BACKOFF_INITIAL, PROBE_BACKOFF_MAX};
    for (int crash = 0; crash < 3; crash++) {
      var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
          /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
      walk.samples = PROBE_WALK_BUDGET - 10;
      climber.rates.smoothed = 0.70;
      climber.sample.previousHitRate = 0.70;
      int samples = (crash == 0) ? 1 : AUDIT_CRASH_PERSISTENCE;
      for (int i = 0; i < samples; i++) {
        steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.60);
      }
      assertThat(climber.walk).isNull();
      assertThat(climber.audit.crashStreak).isEqualTo(Math.min(crash + 1, PROBE_CRASH_ESCALATION));
      assertThat(climber.audit.rung).isEqualTo(expectedRung[crash]);
      assertThat(climber.auditClock.waitSamples).isAtMost(PROBE_BACKOFF_MAX);
      assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
    }
  }

  @Test
  void audit_toleratedDip_holdsTheWalkDirection() {
    // while the persistence counter is adjudicating a dip, the walk neither crashes nor
    // reverses: the same unbelieved evidence must not drive a reversal through the base into
    // a completed, rung-doubling failure. That conversion re-created the very cost the
    // tolerance removes
    var climber = makeClimber();
    climber.audit.crashStreak = 1;
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
    walk.samples = PROBE_WALK_BUDGET - 10;
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    climber.step.size = -STRIDE;
    long step = steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.55);

    assertThat(climber.walk).isSameInstanceAs(walk);
    assertThat(walk.belowBarStreak).isEqualTo(1);
    assertThat(step).isLessThan(0L);
  }

  @Test
  void audit_lowBaseRate_crashAbortsOnAProportionalDrop() {
    // the abort is a level test against the rate frozen at the arm, so a threshold wider than that
    // rate cannot be satisfied at all and only the budget bounds a walk doing real damage
    var climber = makeClimber();
    injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ 1024, /* baseHitRate= */ 0.04, /* baseSmoothedRate= */ 0.0);
    climber.sample.previousHitRate = 0.04;

    // 0.033 falls past 0.04 - (AUDIT_BAR_FRACTION * 0.04) but not past the negative 0.04 - 5pp
    sample(climber, /* windowMax= */ 2048,
        /* windowHits= */ 20, /* mainHits= */ 13, /* misses= */ 967);

    assertThat(climber.walk).isNull();
    assertThat(climber.audit.crashStreak).isEqualTo(1);
  }

  @Test
  void audit_highBaseRate_crashBarStaysAbsolute() {
    // the proportional floor takes over only where the threshold outruns the rate; a walk whose
    // own rate exceeds it keeps the 5pp depth the pricing graveyard is written against
    var climber = makeClimber();
    injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ 1024, /* baseHitRate= */ 0.60, /* baseSmoothedRate= */ 0.0);
    climber.sample.previousHitRate = 0.60;

    // 0.53 falls past the absolute 5pp but not past the proportional 9pp, so it discriminates:
    // a bar that took the fraction unconditionally would let this walk continue
    sample(climber, /* windowMax= */ 2048,
        /* windowHits= */ 280, /* mainHits= */ 250, /* misses= */ 470);

    assertThat(climber.walk).isNull();
    assertThat(climber.audit.crashStreak).isEqualTo(1);
  }

  @Test
  void audit_noisyLowRate_crashBarStaysOnTheFrozenRate() {
    // the noise term belongs to the reversal alone. Taking it here too would widen the level test
    // on exactly the workloads every dead pricing candidate widened it on, so a drop past the
    // fraction of the arming rate aborts however scattered the workload is
    var climber = makeClimber();
    injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ 1024, /* baseHitRate= */ 0.04, /* baseSmoothedRate= */ 0.0);
    climber.rates.smoothed = 0.04;
    climber.rates.deviation = 0.05;
    climber.sample.previousHitRate = 0.04;

    // 0.033 falls past 0.04 - (AUDIT_BAR_FRACTION * 0.04) but not past the reversal's noise-priced
    // bar, so a crash abort sharing the reversal's pricing would let this walk continue
    sample(climber, /* windowMax= */ 2048,
        /* windowHits= */ 20, /* mainHits= */ 13, /* misses= */ 967);

    assertThat(climber.walk).isNull();
    assertThat(climber.audit.crashStreak).isEqualTo(1);
  }

  @Test
  void audit_walkThroughValley_confirmsPastTheDip() {
    // the moat shape end-to-end: on the retry of a crashing equilibrium, two below-bar samples
    // are tolerated in the committed direction, the far bank resets the persistence counter,
    // and the walk survives to confirm. Under the one-sample abort the far bank was
    // unreachable at any rung, because the abort fired before the walk could cross
    var climber = makeClimber();
    climber.audit.crashStreak = 1;
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.40);
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    climber.step.size = STRIDE;
    steadySample(climber, /* windowMax= */ 2200, /* hitRate= */ 0.55);
    assertThat(climber.walk).isSameInstanceAs(walk);
    steadySample(climber, /* windowMax= */ 2400, /* hitRate= */ 0.56);
    assertThat(climber.walk).isSameInstanceAs(walk);
    assertThat(walk.belowBarStreak).isEqualTo(2);
    steadySample(climber, /* windowMax= */ 2600, /* hitRate= */ 0.80);
    assertThat(walk.belowBarStreak).isEqualTo(0);
    for (int i = 0; (climber.walk != null) && (i < PROBE_WALK_BUDGET); i++) {
      steadySample(climber, /* windowMax= */ 2800, /* hitRate= */ 0.80);
    }

    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.audit.crashStreak).isEqualTo(0);
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_INITIAL);
  }

  @Test
  void audit_budgetExpiry_leavesTheStarvationLedger() {
    // an audit's own endings clear its own crash streak; forgiving the starvation machine's
    // would let an unrelated audit reset the escalation a blind corner had earned
    var climber = makeClimber();
    climber.starvation.crashStreak = 1;
    climber.audit.crashStreak = 1;
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ true,
        /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
    walk.samples = PROBE_WALK_BUDGET;
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    steadySample(climber, /* windowMax= */ 1800, /* hitRate= */ 0.70);

    assertThat(climber.walk).isNull();
    assertThat(climber.audit.crashStreak).isEqualTo(0);
    assertThat(climber.starvation.crashStreak).isEqualTo(1);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void walkStep_reversalThroughBase_leavesTheOtherLayersLedger() {
    // A reversal through the walk's own start is a completed failure, and it retires only the
    // owning layer's crash streak. Clearing both disarms the other layer's crash responses,
    // for audits, AUDIT_CRASH_PERSISTENCE arms at a streak of one, so a starvation probe
    // reversing between two audit crashes would restore the one-sample abort the moat and the
    // pulse train need it not to have.
    for (boolean audit : new boolean[] {false, true}) {
      var climber = makeClimber();
      climber.starvation.crashStreak = 1;
      climber.audit.crashStreak = 1;
      var walk = injectWalk(climber, audit, /* down= */ false,
          /* baseWindow= */ 2000, /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.99);
      walk.samples = 1;
      climber.rates.smoothed = 0.70;
      climber.rates.deviation = 0.001;
      climber.sample.previousHitRate = 0.70;
      climber.step.size = -STRIDE;
      sample(climber, /* windowMax= */ 2000,
          /* windowHits= */ 10, /* mainHits= */ 690, /* misses= */ 300);

      assertThat(climber.walk).isNull();
      assertThat(climber.audit.crashStreak).isEqualTo(audit ? 0 : 1);
      assertThat(climber.starvation.crashStreak).isEqualTo(audit ? 1 : 0);
    }
  }

  @Test
  void walkStep_floorBasedWalk_endsAtBudgetWithAFullUndo() {
    // A walk based at the density tier's resting position cannot end through its own base: the
    // machine rests at the truncated integer one entry below the 2% floor, a reversal's raw
    // landing there is the base itself, which the strict crossing test refuses, and the floor
    // clamp then commits one entry above the base. The budget bound and the full undo are the
    // invariant this pins; weakening either leaves such a walk unbounded.
    var climber = makeClimber();
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ (long) FLOOR, /* baseHitRate= */ 0.06, /* baseSmoothedRate= */ 0.99);
    walk.samples = 1;
    climber.rates.smoothed = 0.06;
    climber.rates.deviation = 0.001;
    climber.sample.previousHitRate = 0.06;
    climber.step.size = STRIDE;

    // improving samples keep the bold driver's heading, a worsening one reverses it
    steadySample(climber, /* windowMax= */ 675, /* hitRate= */ 0.70);
    assertThat(climber.walk).isSameInstanceAs(walk);
    steadySample(climber, /* windowMax= */ 1187, /* hitRate= */ 0.40);
    assertThat(climber.walk).isSameInstanceAs(walk);

    // the reversal proposes the base exactly; the walk survives and rests one entry above it
    long landing = steadySample(climber, /* windowMax= */ 675, /* hitRate= */ 0.60);
    assertThat(climber.walk).isSameInstanceAs(walk);
    assertThat(landing).isEqualTo(-511);

    steadySample(climber, /* windowMax= */ 164, /* hitRate= */ 0.055);
    assertThat(climber.walk).isSameInstanceAs(walk);

    walk.samples = PROBE_WALK_BUDGET;
    long undo = steadySample(climber, /* windowMax= */ 676, /* hitRate= */ 0.70);
    assertThat(undo).isEqualTo(((long) FLOOR) - 676);
    assertThat(climber.walk).isNull();
    assertThat(climber.undoRemaining).isEqualTo(0);
    assertThat(climber.audit.crashStreak).isEqualTo(0);
    assertThat(climber.audit.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.anchor.held).isFalse();
  }

  @Test
  void anchor_ratchetsToClearlyBetterPosition_andReleasesPark() {
    // The memory follows the goal metric upward: standing elsewhere with the smoothed rate a
    // full margin above the anchor's claim moves the anchor here, and a parked climber whose
    // workload found somewhere better is released rather than dragged back. The ratchet is
    // deliberately unhurried: the rate step that signals the improvement also inflates the
    // deviation estimate, so the margin widens before it clears. A fresh step-change is
    // indistinguishable from noise onset until the smoothing settles.
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    assertThat(climber.anchor.window).isEqualTo(2000);
    climber.anchor.held = true;
    // an audit reaching this position first would plant and park it by its own confirm, which the
    // ratchet is not observable through, so the clock is held back for the length of the scenario
    climber.auditClock.stillSamples = 0;

    long parked = steadySample(climber, /* windowMax= */ 4000, /* hitRate= */ 0.74);
    assertThat(parked).isEqualTo(0);
    assertThat(climber.anchor.held).isTrue();

    @Var int ratchetedAt = -1;
    for (int i = 2; i <= 20; i++) {
      steadySample(climber, /* windowMax= */ 4000, /* hitRate= */ 0.74);
      if (climber.anchor.window == 4000) {
        ratchetedAt = i;
        break;
      }
    }
    assertThat(ratchetedAt).isGreaterThan(2);
    assertThat(ratchetedAt).isAtMost(20);
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.anchor.rate).isWithin(1.0e-9).of(climber.rates.smoothed);
  }

  @Test
  void anchor_midUndoDrain_doesNotPlantAtTheRetreatingWindow() {
    // A capped retreat drains across later samples with the walk already ended and no return in
    // flight, so the window passes through the positions the probe was charged for rejecting. An
    // unplanted anchor waits for the retreat to land rather than claiming one of them; the drain
    // is read before the sample's stride, so the sample that empties the ledger is still transit.
    var climber = failingDownProbe();
    sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 200, /* mainHits= */ 500, /* misses= */ 300);
    assertThat(climber.walk).isNull();
    assertThat(climber.undoRemaining).isGreaterThan(0);

    steadySample(climber, /* windowMax= */ 4457, /* hitRate= */ 0.70);
    assertThat(climber.undoRemaining).isGreaterThan(0);
    assertThat(climber.anchor.isPlanted()).isFalse();

    steadySample(climber, /* windowMax= */ 6914, /* hitRate= */ 0.70);
    assertThat(climber.undoRemaining).isEqualTo(0);
    assertThat(climber.anchor.isPlanted()).isFalse();

    // the retreat has landed, so the position is the cache's own and may be claimed
    steadySample(climber, /* windowMax= */ 7000, /* hitRate= */ 0.70);
    assertThat(climber.anchor.window).isEqualTo(7000);
    assertThat(climber.anchor.rate).isWithin(1.0e-9).of(0.70);
  }

  @Test
  void anchor_midUndoDrain_doesNotRatchetOffTheProbeBase() {
    // The ratchet reads the same transit. A parked anchor at the probe's base, holding a claim the
    // smoothed rate now clears by a full margin, would otherwise move to wherever the retreat
    // happened to be and drop its park, leaving the guard rail defending a rejected position.
    var climber = failingDownProbe();
    climber.anchor.plant(/* windowMax= */ 7000, /* claimed= */ 0.40);
    climber.anchor.park(AUDIT_WAIT_INITIAL);
    sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 200, /* mainHits= */ 500, /* misses= */ 300);

    steadySample(climber, /* windowMax= */ 4457, /* hitRate= */ 0.70);
    assertThat(climber.anchor.window).isEqualTo(7000);
    assertThat(climber.anchor.rate).isEqualTo(0.40);
    assertThat(climber.anchor.held).isTrue();

    // the re-sync is the half that does not wait: once the retreat is back inside the anchor's
    // band the stale claim decays into the live measurement, drain still in flight or not
    steadySample(climber, /* windowMax= */ 6914, /* hitRate= */ 0.70);
    assertThat(climber.undoRemaining).isEqualTo(0);
    assertThat(climber.anchor.window).isEqualTo(7000);
    assertThat(climber.anchor.rate).isWithin(1.0e-9).of(0.70);

    long parked = steadySample(climber, /* windowMax= */ 7000, /* hitRate= */ 0.70);
    assertThat(parked).isEqualTo(0);
    assertThat(climber.anchor.window).isEqualTo(7000);
    assertThat(climber.anchor.held).isTrue();
  }

  @Test
  void guardRail_marginTracksNoise_silentWhileWideThenVetoes() {
    // The shortfall margin is cleared of the workload's own hit-rate noise: while the deviation
    // estimate is wide the rail stays silent on a gap it cannot distinguish from weather, and
    // as quiet samples tighten the estimate the same gap becomes actionable. The mechanism
    // that keeps noisy-texture traces from being steered by wrong vetoes. The anchor here is
    // one capped stride away, so the return completes within a single sample and the park
    // (the anchor holding) is the observable outcome.
    var climber = makeClimber();
    climber.anchor.window = 2000;
    climber.anchor.rate = 0.70;
    climber.rates.smoothed = 0.66;
    climber.sample.previousHitRate = 0.66;
    climber.rates.deviation = 0.05;
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;

    @Var int vetoedAt = -1;
    for (int i = 1; i <= 12; i++) {
      steadySample(climber, /* windowMax= */ 4000, /* hitRate= */ 0.66);
      if (climber.anchor.held) {
        vetoedAt = i;
        break;
      }
    }
    assertThat(vetoedAt).isGreaterThan(5);
    assertThat(vetoedAt).isAtMost(12);
    assertThat(climber.anchor.returning).isFalse();
    assertThat(climber.adjustment()).isEqualTo(2000 - 4000);
  }

  @Test
  void guardRail_vetoReturnBudget_settlesWhereItStands() {
    // a return whose strides the cache cannot realize (a rail: commands issued, transfers
    // starved) settles at its budget rather than striding forever; the park then holds the
    // reached position and the audit clock owns further exploration
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;

    @Var int strides = 0;
    for (int i = 0; i < 20; i++) {
      long adjustment = steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.66);
      if (adjustment == -(long) (MAX_STEP_FRACTION * MAXIMUM)) {
        strides++;
      }
      if (climber.anchor.held && !climber.anchor.returning) {
        break;
      }
    }
    assertThat(strides).isEqualTo(VETO_RETURN_BUDGET);
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.returning).isFalse();

    long held = steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.66);
    assertThat(held).isEqualTo(0);
  }

  @Test
  void guardRail_returnArrival_falsifiesAStaleClaim() {
    // arriving proves nothing about the claim that sent the window here: the arrival's own drop
    // can fall under the crash-scale threshold while the claim is still a past regime's. The
    // position is settled and then judged against the claim frozen when the return committed,
    // and one that cannot earn it is stood down exactly as a crash-scale swing would have
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    for (int i = 0; i < VETO_STREAK; i++) {
      steadySample(climber, /* windowMax= */ 1500, /* hitRate= */ 0.66);
    }
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.returning).isFalse();
    assertThat(climber.anchor.retestClaim).isWithin(1.0e-6).of(0.70);

    for (int i = 0; i < RETEST_SETTLE - 1; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.66);
      assertThat(climber.anchor.isPlanted()).isTrue();
    }
    // the on-anchor re-sync has decayed the live claim into the shortfall being tested, which is
    // why the frozen one is the judge
    assertThat(climber.anchor.rate).isWithin(0.005).of(0.66);
    assertThat(climber.anchor.retestClaim).isWithin(1.0e-6).of(0.70);

    steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.66);
    assertThat(climber.anchor.isPlanted()).isFalse();
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.rates.isUnseeded()).isTrue();
  }

  @Test
  void guardRail_returnArrival_keepsAClaimThePositionEarns() {
    // the retest is a test, not a discard: a rate that recovers on the way home leaves the
    // window at a position that does earn the claim, and the park stands
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    for (int i = 0; i < VETO_STREAK; i++) {
      steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.66);
    }
    assertThat(climber.anchor.returning).isTrue();

    // strides home under a recovery no single sample announces as a workload change
    @Var long windowMax = 7500;
    @Var double hitRate = 0.66;
    for (int i = 0; i < 20; i++) {
      hitRate = Math.min(0.76, hitRate + 0.02);
      windowMax += steadySample(climber, windowMax, hitRate);
      if (!climber.anchor.returning) {
        break;
      }
    }
    assertThat(climber.anchor.returning).isFalse();
    assertThat(climber.anchor.isAt(windowMax, WindowClimber.Reading.stableBand(MAXIMUM))).isTrue();
    assertThat(climber.anchor.retestClaim).isWithin(1.0e-6).of(0.70);

    for (int i = 0; i < RETEST_SETTLE; i++) {
      assertThat(steadySample(climber, windowMax, hitRate)).isEqualTo(0);
    }
    assertThat(climber.anchor.retestClaim).isEqualTo(-1);
    assertThat(climber.anchor.isPlanted()).isTrue();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.rates.isUnseeded()).isFalse();
  }

  @Test
  void guardRail_returnShortOfTheAnchor_hasNothingToRetest() {
    // a return that spends its budget without arriving never reached the position its claim
    // describes, so there is no claim to judge and the reached position keeps its park
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    for (int i = 0; i < 20; i++) {
      steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.66);
      if (climber.anchor.held && !climber.anchor.returning) {
        break;
      }
    }
    assertThat(climber.anchor.returning).isFalse();
    assertThat(climber.anchor.isAt(7500, WindowClimber.Reading.stableBand(MAXIMUM))).isFalse();

    for (int i = 0; i <= RETEST_SETTLE; i++) {
      steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.66);
    }
    assertThat(climber.anchor.isPlanted()).isTrue();
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.anchor.retestClaim).isEqualTo(-1);
  }

  @Test
  void guardRail_crashScaleShift_releasesTheParkAndReturn() {
    // a crash-scale swing announces a workload change: any park or in-progress return is
    // released so the climber re-engages with the new regime rather than defending a stale one
    var climber = makeClimber();
    for (int i = 0; i < 30; i++) {
      steadySample(climber, /* windowMax= */ 2000, /* hitRate= */ 0.70);
    }
    climber.anchor.held = true;
    climber.anchor.returning = true;
    climber.anchor.returnLeft = 5;

    steadySample(climber, /* windowMax= */ 5000, /* hitRate= */ 0.55);
    assertThat(climber.anchor.held).isFalse();
    assertThat(climber.anchor.returning).isFalse();
    assertThat(climber.anchor.window).isEqualTo(2000);
  }

  @Test
  void guardRail_vetoReturn_shiftMidReturn_abandonsTheRetest() {
    // a crash-scale swing mid-return interrupts the return, and the retest goes with it, since
    // the frozen claim is judged where the return lands and an interrupted return does not land.
    // A claim left pending survives the stand-down, and a same-sample re-plant puts the window
    // back on the anchor, where the stale claim would judge the new position against a past
    // regime's rate
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_INITIAL;
    climber.anchor.window = 7000; // too far to reach in one stride, so the return is in flight
    for (int i = 0; i < VETO_STREAK; i++) {
      steadySample(climber, /* windowMax= */ 1500, /* hitRate= */ 0.66);
    }
    assertThat(climber.anchor.returning).isTrue();
    assertThat(climber.anchor.retestClaim).isWithin(1.0e-6).of(0.70);

    // mid-return the workload recovers and then jumps crash-scale, far from the anchor; the
    // recovery is seeded directly (an EMA pair only reaches it after a long plateau)
    climber.rates.smoothed = 0.75;
    climber.rates.deviation = 0.001;
    climber.sample.previousHitRate = 0.75;
    long adjustment = sample(climber, /* windowMax= */ 3957,
      /* windowHits= */ 5, /* mainHits= */ 805, /* misses= */ 190);

    assertThat(climber.anchor.retestClaim).isEqualTo(-1); // the interrupted retest is abandoned
    assertThat(climber.anchor.settleLeft).isEqualTo(0);
    assertThat(climber.anchor.returning).isFalse();
    assertThat(climber.anchor.window).isEqualTo(3957); // the swing re-planted at its position
    assertThat(climber.anchor.rate).isWithin(1.0e-6).of(0.762);
    assertThat(adjustment).isNotEqualTo(0); // the sample steered rather than settled the retest
  }

  @Test
  void guardRail_vetoReturn_budgetOut_thenAnchorMoves_abandonsTheRetest() {
    // the same stale claim without any crash-scale swing: a return that spends its budget short
    // of the anchor leaves the retest pending, and a rate that recovered while it strode moves
    // the anchor onto the window on the very next sample. The window is now "at" an anchor the
    // return never reached, so the claim frozen for the old position judges the new one
    var climber = seededShortfall();
    climber.auditClock.waitSamples = AUDIT_WAIT_MAX;
    for (int i = 0; i < 20; i++) {
      steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.66);
      if (climber.anchor.held && !climber.anchor.returning) {
        break;
      }
    }
    assertThat(climber.anchor.returning).isFalse();      // the budget ran out short of the anchor
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.anchor.retestClaim).isWithin(1.0e-6).of(0.70);

    // the workload recovered while the return strode; seeded directly, as an EMA pair reaches it
    // only over a long plateau. No single sample moves a crash-scale step, so nothing stands down
    climber.rates.smoothed = 0.76;
    climber.rates.deviation = 0.001;
    climber.sample.previousHitRate = 0.76;
    steadySample(climber, /* windowMax= */ 7500, /* hitRate= */ 0.76);

    assertThat(climber.anchor.window).isEqualTo(7500);   // the anchor followed the better position
    assertThat(climber.anchor.retestClaim).isEqualTo(-1);
    assertThat(climber.anchor.settleLeft).isEqualTo(0);
  }

  @Test
  void guardRail_blindCorner_outranksTheVeto() {
    // a starved sample cannot honestly adjudicate a shortfall: blindness routes to the probe
    // machinery before the veto may fire, while an identically-placed sighted sample vetoes
    // (and, with the anchor one stride away, completes its return and parks within the call)
    var vetoed = seededShortfall();
    var probed = seededShortfall();
    for (int i = 0; i < 3; i++) {
      steadySample(vetoed, /* windowMax= */ 1500, /* hitRate= */ 0.66);
      steadySample(probed, /* windowMax= */ 1500, /* hitRate= */ 0.66);
    }
    assertThat(vetoed.anchor.held).isFalse();
    assertThat(probed.anchor.held).isFalse();

    steadySample(vetoed, /* windowMax= */ 1500, /* hitRate= */ 0.66);
    assertThat(vetoed.anchor.held).isTrue();
    assertThat(vetoed.adjustment()).isEqualTo(2000 - 1500);

    long adjustment = sample(probed, /* windowMax= */ 1500,
        /* windowHits= */ 0, /* mainHits= */ 660, /* misses= */ 340);
    assertThat(probed.walk).isNotNull();
    assertThat(probed.anchor.held).isFalse();
    assertThat(adjustment).isEqualTo((long) STRIDE);
  }

  /**
   * A climber whose anchor claims 0.70 at 2000 while the live smoothed rate runs 0.66, with the
   * deviation estimate settled so the margin floor binds (the cold-start seed would otherwise
   * hold the margin wide for several samples, the behavior pinned by the margin test).
   */
  private static WindowClimber seededShortfall() {
    var climber = makeClimber();
    climber.anchor.window = 2000;
    climber.anchor.rate = 0.70;
    climber.rates.smoothed = 0.66;
    climber.sample.previousHitRate = 0.66;
    climber.rates.deviation = 0.0;
    return climber;
  }

  /** Runs steady samples at the position until the audit arms, asserting it does. */
  private static WindowClimber armAudit(long windowMax) {
    return armAuditAtRung(windowMax, PROBE_BACKOFF_INITIAL);
  }

  /** Arms an audit at a position with the audit ladder already deepened to the given rung. */
  private static WindowClimber armAuditAtRung(long windowMax, int rung) {
    var climber = makeClimber();
    climber.audit.rung = rung;
    @Var boolean armed = false;
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, windowMax, /* hitRate= */ 0.70);
      if (climber.walk != null) {
        armed = true;
        break;
      }
    }
    assertThat(armed).isTrue();
    assertThat(walkOf(climber).isAudit).isTrue();
    return climber;
  }

  @Test
  void audit_sightedFalseEquilibrium_armsWithoutStarvation() {
    // The whisper scenario end to end at the unit level: a thin above-bar trickle keeps the
    // window "sighted" at the floor, steering's clamp holds it there, no starvation is ever
    // declared, and the positional stillness clock arms an upward audit anyway. This is the
    // coverage the starvation triggers cannot provide.
    var climber = makeClimber();
    @Var int armedAt = -1;
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      long adjustment = sample(climber, /* windowMax= */ 164,
          /* windowHits= */ 5, /* mainHits= */ 595, /* misses= */ 400);
      if (climber.walk != null) {
        armedAt = i;
        assertThat(walkOf(climber).isAudit).isTrue();
        break;
      }
      assertThat(adjustment).isEqualTo(0);
    }
    assertThat(armedAt).isAtLeast(AUDIT_WAIT_FIRST - 1);
    assertThat(walkOf(climber).down).isFalse();
    assertThat(climber.adjustment()).isEqualTo((long) STRIDE);
  }

  /**
   * Completes a thousand-request sample at the given hit rate whose region split is proportional
   * to capacity, so the steering error is ~zero and the position reads as a quiet equilibrium.
   */
  @CanIgnoreReturnValue
  private static long steadySample(WindowClimber climber, long windowMax, double hitRate) {
    long hits = Math.round(1000 * hitRate);
    long windowHits = (hits * windowMax) / MAXIMUM;
    return sample(climber, windowMax, (int) windowHits, (int) (hits - windowHits),
        (int) (1000 - hits));
  }

  /* --------------- The walk --------------- */

  @Test
  void walkStep_strideScalesByRung() {
    long[][] expected = {{16, 1}, {32, 2}, {64, 4}};
    for (long[] rungAndScale : expected) {
      var climber = makeClimber();
      climber.starvation.rung = (int) rungAndScale[0];
      long adjustment = sample(climber, /* windowMax= */ 1024,
          /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);
      assertThat(adjustment).isEqualTo(rungAndScale[1] * (long) STRIDE);
    }
  }

  @Test
  void walkStep_continuation_decaysWhileQuiet() {
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    long adjustment = sample(climber, /* windowMax= */ 1536,
        /* windowHits= */ 5, /* mainHits= */ 495, /* misses= */ 500);

    assertThat(climber.walk).isNotNull();
    assertThat(adjustment).isEqualTo((long) (STEP_DECAY_RATE * STRIDE));
  }

  @Test
  void walkStep_crashScaleSampleDrop_reverses() {
    // a drop past the walk-interior bar (at the cold-start deviation seed the priced bar sits
    // at its 15pp cap) reverses the bold driver, while remaining above the probe's own base so
    // the crash veto does not fire
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    climber.sample.previousHitRate = 0.6;
    long adjustment = sample(climber, /* windowMax= */ 2048,
        /* windowHits= */ 5, /* mainHits= */ 435, /* misses= */ 560);

    assertThat(climber.walk).isNotNull();
    assertThat(adjustment).isEqualTo((long) -STRIDE);
  }

  @Test
  void walkStep_reversalThroughBase_failsAndDoubles() {
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    climber.sample.previousHitRate = 0.6;
    long adjustment = sample(climber, /* windowMax= */ 1280,
        /* windowHits= */ 5, /* mainHits= */ 435, /* misses= */ 560);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(1024 - 1280);
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.refractoryLeft).isEqualTo(climber.starvation.rung);
  }

  @Test
  void walkStep_reversalThroughBase_failsDownward() {
    // the mirror of the up-probe reversal: a down walk whose bar-scale reversal would cross
    // back up through its own start finishes as a completed, failed experiment
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    climber.sample.previousHitRate = 0.6;
    long adjustment = sample(climber, /* windowMax= */ 6900,
        /* windowHits= */ 435, /* mainHits= */ 5, /* misses= */ 560);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(7000 - 6900);
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void walkStep_floorClamp_landsOnFloor() {
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    long adjustment = sample(climber, /* windowMax= */ 400,
        /* windowHits= */ 495, /* mainHits= */ 5, /* misses= */ 500);

    assertThat(climber.walk).isNotNull();
    assertThat(adjustment).isEqualTo((long) (FLOOR - 400));
    assertThat(climber.step.size).isEqualTo(FLOOR - 400);
  }

  @Test
  void walkStep_floorBlocked_restartHoldsDirection() {
    // regression: at the floor the clamp yields a zero step whose sign must stay negative, else
    // a restart-scale improving sample strides upward off positive zero during a down walk
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    long landing = sample(climber, /* windowMax= */ 163,
        /* windowHits= */ 495, /* mainHits= */ 5, /* misses= */ 500);
    assertThat(landing).isEqualTo(0);
    assertThat(climber.step.size).isEqualTo(-0.0);

    long jitter = sample(climber, /* windowMax= */ 163,
        /* windowHits= */ 555, /* mainHits= */ 5, /* misses= */ 440);
    assertThat(jitter).isEqualTo(0);
    assertThat(climber.walk).isNotNull();
    assertThat(climber.step.size).isEqualTo(-0.0);
  }

  @Test
  void walkStep_floorBlocked_crashScaleDropWalksBackOut() {
    // an intended bold-driver reversal: the floor position is hurting, so walk out of it (the
    // elevated sample keeps the follow-on drop bar-scale against it yet above the base veto)
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    long landing = sample(climber, /* windowMax= */ 163,
        /* windowHits= */ 555, /* mainHits= */ 5, /* misses= */ 440);
    assertThat(landing).isEqualTo(0);

    long reversal = sample(climber, /* windowMax= */ 163,
        /* windowHits= */ 395, /* mainHits= */ 5, /* misses= */ 600);
    assertThat(climber.walk).isNotNull();
    assertThat(reversal).isEqualTo((long) STRIDE);
  }

  /* --------------- Endings --------------- */

  @Test
  void probeEnding_crash_undoesWithoutDoubling() {
    // at the cold-start deviation seed the probe's priced bar sits at its 15pp cap, so the
    // collapse must clear that to abort
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    long adjustment = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 340, /* mainHits= */ 0, /* misses= */ 660);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
    assertThat(climber.refractoryLeft).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void probeEnding_subBarDrop_doesNotCrashAStarvationProbe() {
    // the walk-interior bar is priced by the workload's own deviation (seeded wide at cold
    // start, capped at three restart thresholds): a 10pp drop is weather, not a collapse, and
    // the walk continues, while the same drop crash-aborts an audit, whose bar stays absolute
    // (audit_deepestRungCrash_retriesOnTheLadderCadence pins that side)
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    long adjustment = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 400, /* mainHits= */ 0, /* misses= */ 600);

    assertThat(climber.walk).isNotNull();
    assertThat(adjustment).isEqualTo((long) (STEP_DECAY_RATE * -STRIDE));
  }

  @Test
  void probeEnding_probeBarFloorsAtTheRestartThreshold() {
    // once the measured deviation is small the pricing floors at the absolute threshold, so a
    // 6pp drop on a quiet workload still crash-aborts a starvation probe
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    climber.rates.smoothed = 0.5;
    climber.rates.deviation = 0.001;
    long adjustment = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 440, /* mainHits= */ 0, /* misses= */ 560);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void probeEnding_starvationCrash_leavesTheAuditLadder() {
    // the inverse separation: a starvation probe's crash counts on its own ledger and
    // escalates only its own machine. The audit rung, streak, and clock never move
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    climber.rates.smoothed = 0.5;
    climber.rates.deviation = 0.001;
    sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 440, /* mainHits= */ 0, /* misses= */ 560);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.crashStreak).isEqualTo(1);
    assertThat(climber.audit.crashStreak).isEqualTo(0);
    assertThat(climber.audit.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_FIRST);
  }

  @Test
  void probeEnding_probeBarCapsAtThreeRestarts() {
    // the cap is the abort's damage ceiling: on a workload so scattered that three deviations
    // read ~34pp, a 16pp collapse must still crash the walk. Uncapped pricing let walks roam
    // the all-blind families at a measured cost
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    climber.rates.smoothed = 0.5;
    climber.rates.deviation = 0.10;
    long adjustment = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 340, /* mainHits= */ 0, /* misses= */ 660);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);
  }

  @Test
  void probeEnding_probeBarTracksTheLiveDeviation_notTheArmingValue() {
    // The starvation bar prices against the deviation as it stands NOW, not a value frozen when
    // the probe armed, and that is load-bearing rather than an oversight: on a dosed workload the
    // deviation at arm is a pre-dose value, so a frozen bar sits below the weather and the walk
    // crash-aborts inside the very dose the pricing exists to survive (measured 2026-08-02,
    // mixnoise_a10 60.7 -> 56.0 and crashnoise_a12 63.6 -> 62.2, both deterministic). The live
    // reading and PROBE_BAR_CAP are a matched pair. The feedback lifts the bar while the walk is
    // exposed, the cap bounds where that would run away. Here the same 9pp drop crashes the walk
    // at the arming deviation and is tolerated once the deviation has risen under it.
    for (boolean weatherRose : new boolean[] {false, true}) {
      var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
      climber.rates.smoothed = 0.5;
      climber.rates.deviation = weatherRose ? 0.06 : 0.001;
      sample(climber, /* windowMax= */ 5000,
          /* windowHits= */ 410, /* mainHits= */ 0, /* misses= */ 590);

      assertThat(climber.walk != null).isEqualTo(weatherRose);
    }
  }

  @Test
  void audit_walkStep_reversalStaysAbsolute() {
    // an audit's bold driver reverses on the absolute 5pp, not the probes' priced bar: the
    // same 7pp drop that a starvation probe walks through (the deviation prices its bar to
    // the cap) turns an audit's walk around. The audit-side pricing was studied to a fresh
    // holdout and rejected, so this asymmetry is load-bearing
    var climber = makeClimber();
    var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ false,
        /* baseWindow= */ 1024, /* baseHitRate= */ 0.60, /* baseSmoothedRate= */ 0.99);
    walk.samples = PROBE_WALK_BUDGET - 10;
    climber.step.size = STRIDE;
    climber.rates.smoothed = 0.70;
    climber.sample.previousHitRate = 0.70;
    long adjustment = sample(climber, /* windowMax= */ 2560,
        /* windowHits= */ 5, /* mainHits= */ 625, /* misses= */ 370);

    assertThat(climber.walk).isNotNull();
    assertThat(adjustment).isEqualTo((long) -STRIDE);
  }

  @Test
  void audit_walkStep_reversalIsPricedAboveTheNoise() {
    // the reversal is a first-difference test, so it is priced against the larger of the rate
    // frozen at the arm and the workload's scatter. The same 1pp drop turns the walk around on a
    // quiet workload and is no evidence at all on a noisy one, where a rate-only price sits at a
    // fifth of the per-sample noise and a reversal back through the base is charged as a completed
    // failure
    for (boolean noisy : new boolean[] {false, true}) {
      var climber = makeClimber();
      var walk = injectWalk(climber, /* isAudit= */ true, /* down= */ false,
          /* baseWindow= */ 1024, /* baseHitRate= */ 0.04, /* baseSmoothedRate= */ 0.99);
      walk.samples = PROBE_WALK_BUDGET - 10;
      climber.step.size = STRIDE;
      climber.rates.smoothed = 0.05;
      climber.rates.deviation = noisy ? 0.05 : 0.001;
      climber.sample.previousHitRate = 0.05;
      long adjustment = sample(climber, /* windowMax= */ 2560,
          /* windowHits= */ 10, /* mainHits= */ 30, /* misses= */ 960);

      assertThat(climber.walk).isNotNull();
      assertThat(adjustment > 0).isEqualTo(noisy);
    }
  }

  @Test
  void probeEnding_budgetExpiry_undoesAndDoubles() {
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    walkOf(climber).samples = PROBE_WALK_BUDGET;
    long adjustment = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 495, /* mainHits= */ 5, /* misses= */ 500);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.refractoryLeft).isEqualTo(climber.starvation.rung);
  }

  @Test
  void probeEnding_watchedRegionIsMainForDownProbe() {
    // the window earning richly must not adjudicate a down probe; main is the watched region
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 900, /* mainHits= */ 5, /* misses= */ 95);

    assertThat(climber.walk).isNotNull();
    assertThat(adjustment).isEqualTo((long) (STEP_DECAY_RATE * -STRIDE));
  }

  @Test
  void probeEnding_adjudication_confirmsOnDensity() {
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.refractoryLeft).isEqualTo(0);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 5, /* mainHits= */ 900));
  }

  @Test
  void probeEnding_adjudication_wrongSignFailsAndDoubles() {
    // the 5000-entry restore exceeds the 30% maximum step, so the undo honors the cap and the
    // remainder is carried across the following samples before steering resumes
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    double cap = MAX_STEP_FRACTION * MAXIMUM;
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 880, /* mainHits= */ 20, /* misses= */ 100);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo((long) cap);
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);

    long second = sample(climber, /* windowMax= */ 2000 + adjustment,
        /* windowHits= */ 880, /* mainHits= */ 20, /* misses= */ 100);
    assertThat(second).isEqualTo((long) cap);

    long third = sample(climber, /* windowMax= */ 2000 + adjustment + second,
        /* windowHits= */ 880, /* mainHits= */ 20, /* misses= */ 100);
    assertThat(third).isEqualTo(5000 - (2 * (long) (MAX_STEP_FRACTION * MAXIMUM)));
    assertThat(adjustment + second + third).isEqualTo(5000);
    assertThat(climber.undoRemaining).isEqualTo(0);
  }

  @Test
  void probeEnding_secondConsecutiveCrash_escalates() {
    // one crash is priced as a possible workload shift (no doubling); a cycle of them is probe
    // damage, so consecutive crashes escalate like completed failures instead of repeating at a
    // fixed rung forever
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    long first = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 340, /* mainHits= */ 0, /* misses= */ 660);
    assertThat(first).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.crashStreak).isEqualTo(1);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_INITIAL);

    var walk = injectWalk(climber, /* isAudit= */ false, /* down= */ true,
        /* baseWindow= */ 7000, /* baseHitRate= */ 0.5, /* baseSmoothedRate= */ 0.0);
    walk.samples = 1;
    climber.step.size = -STRIDE;
    climber.sample.previousHitRate = 0.5;
    long second = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 340, /* mainHits= */ 0, /* misses= */ 660);
    assertThat(second).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.crashStreak).isEqualTo(2);
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void probeEnding_crashStreak_saturates() {
    // the ledger only distinguishes a lone crash from a run, so the streak holds at the escalation
    // threshold rather than counting on toward a wrap
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    climber.starvation.crashStreak = PROBE_CRASH_ESCALATION;
    long returned = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 340, /* mainHits= */ 0, /* misses= */ 660);
    assertThat(returned).isEqualTo(7000 - 5000);
    assertThat(climber.starvation.crashStreak).isEqualTo(PROBE_CRASH_ESCALATION);
    assertThat(climber.starvation.crashEscalates()).isTrue();
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }


  @Test
  void probeEnding_midRungCommitment_blocksEarlyExit() {
    var climber = makeClimber();
    climber.starvation.rung = 2 * PROBE_BACKOFF_INITIAL;
    long entry = sample(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* misses= */ 500);
    assertThat(climber.walk).isNotNull();
    assertThat(entry).isEqualTo(2 * (long) STRIDE);

    // depth 1 < the middle rung's commitment of 2: adjudication is blocked despite the signal
    long walked = sample(climber, /* windowMax= */ 3072,
        /* windowHits= */ 400, /* mainHits= */ 100, /* misses= */ 500);
    assertThat(climber.walk).isNotNull();
    assertThat(walked).isEqualTo((long) (STEP_DECAY_RATE * 2 * STRIDE));

    // depth 2: the same signal now adjudicates (and confirms; the window is the denser region)
    long confirmed = sample(climber, /* windowMax= */ 3072,
        /* windowHits= */ 400, /* mainHits= */ 100, /* misses= */ 500);
    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(confirmed).isEqualTo(densityStep(
        /* windowMax= */ 3072, /* windowHits= */ 400, /* mainHits= */ 100));
  }

  @Test
  void probeEnding_upProbe_confirmsAgainstFrozenBaseline() {
    // The up-probe verdict prices opportunity cost at main's margin: a thin reuse band whose
    // window density loses to main's protected-core AVERAGE still confirms by out-earning the
    // probation baseline frozen at the probe's start (the trickle family's repair). Density then
    // walks the window home in the same sample, and the ladder prices that reversal as a completed
    // experiment, so the next walk commits deeper (the band past a first-round stride is reached
    // by the deeper rungs, not by the confirm)
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5,
        /* baseProbationDensity= */ 0.002);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.refractoryLeft).isEqualTo(0);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 30, /* mainHits= */ 940));
  }

  @Test
  void probeEnding_upProbe_failsAgainstRicherFrozenBaseline() {
    // The mirror: when the boundary was earning richly at arm time (a frequency floor), the same
    // watched signal fails the verdict and the walk is undone with the ladder doubled.
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5,
        /* baseProbationDensity= */ 0.5);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(1024 - 2000);
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(climber.refractoryLeft).isEqualTo(climber.starvation.rung);
  }

  @Test
  void probeEnding_upProbeVerdict_isFreeOfTheSampleLength() {
    // The up-probe divides a live window density by a probation density frozen at the arm, and a
    // density is a hit count over a capacity: two of them form a ratio only when both count the
    // same span. The same per-request rates over a sample twice as long must reach the same
    // verdict. Nothing pins this on an unweighted cache, whose period is a constant four maxima;
    // a weighted one samples on the sketch's entry-denominated period, which moves with the
    // resident count, so a growing count would otherwise confirm every up-probe it lengthens.
    var brief = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.97,
        /* baseProbationDensity= */ 0.02);
    long briefVerdict = sample(brief, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);

    var extended = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.97,
        /* baseProbationDensity= */ 0.02);
    long extendedVerdict = sample(extended, /* windowMax= */ 2000,
        /* windowHits= */ 60, /* mainHits= */ 1880, /* misses= */ 60);

    // the window earns less per request than the boundary did, so both fail and undo to the base
    assertThat(brief.walk).isNull();
    assertThat(extended.walk).isNull();
    assertThat(briefVerdict).isEqualTo(1024 - 2000);
    assertThat(extendedVerdict).isEqualTo(1024 - 2000);
    assertThat(brief.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(extended.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void armProbe_freezesProbationBaselinePerArm() {
    // The baseline snapshots at each arm, never mid-walk: the walk's own demotions enrich live
    // probation (a demoted protected-hot core earns furiously there), so adjudicating against
    // the live rate is an absorbing false-veto; re-arming re-snapshots, so a cold-start
    // transient baseline heals on the next round.
    var climber = makeClimber();
    long capacity = probationCapacity(1024);
    long entry = sampleWithProbation(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* probationHits= */ 200, /* misses= */ 500);
    assertThat(climber.walk).isNotNull();
    assertThat(entry).isEqualTo((long) STRIDE);
    assertThat(walkOf(climber).baseProbationDensity).isWithin(1.0e-9).of(200.0 / capacity);

    climber.walk = null;
    climber.refractoryLeft = 0;
    long rearm = sampleWithProbation(climber, /* windowMax= */ 1024,
        /* windowHits= */ 0, /* mainHits= */ 500, /* probationHits= */ 10, /* misses= */ 500);
    assertThat(climber.walk).isNotNull();
    assertThat(rearm).isEqualTo((long) STRIDE);
    assertThat(walkOf(climber).baseProbationDensity).isWithin(1.0e-9).of(10.0 / capacity);
  }

  @Test
  void probeEnding_upProbe_zeroBaselineConfirmsOnAnySignal() {
    // A probation region earning nothing at arm time prices the claimed capacity at ~zero, so
    // any watched earnings above the adjudication bar confirm. The opportunity cost of a dead
    // boundary is nothing; the confirm hands to density, which reverses the sparser window, and
    // that reversal deepens the ladder rather than resetting it
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5,
        /* baseProbationDensity= */ 0.0);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 30, /* mainHits= */ 940));
  }

  @Test
  void probeEnding_starvationConfirm_reversedByDensity_escalatesTheLadder() {
    // A confirm the density arm reverses in the same sample keeps nothing: the window is walked
    // home and the corner re-arms, so a reward here restarts the ladder on every cycle of a dither
    // whose first-round stride never reaches the band it is looking for. The reversal is priced as
    // the completed experiment it is, so the next walk commits deeper; the handoff to density and
    // the zero refractory of a confirm are unchanged, and the ladder's cap still holds
    var reversed = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5,
        /* baseProbationDensity= */ 0.002);
    long adjustment = sample(reversed, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);
    assertThat(reversed.walk).isNull();
    assertThat(reversed.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
    assertThat(reversed.refractoryLeft).isEqualTo(0);
    assertThat(reversed.anchor.held).isFalse();
    assertThat(adjustment).isLessThan(0L);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 30, /* mainHits= */ 940));

    // the same verdict where density agrees with the walk keeps the reward
    var agreed = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    sample(agreed, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);
    assertThat(agreed.walk).isNull();
    assertThat(agreed.starvation.rung).isEqualTo(1);
    assertThat(agreed.refractoryLeft).isEqualTo(0);

    // at the deepest rung a reversal cannot deepen the ladder further
    var deepest = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5,
        /* baseProbationDensity= */ 0.002);
    deepest.starvation.rung = PROBE_BACKOFF_MAX;
    walkOf(deepest).samples = PROBE_COMMITMENT_DEEP;
    sample(deepest, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);
    assertThat(deepest.walk).isNull();
    assertThat(deepest.starvation.rung).isEqualTo(PROBE_BACKOFF_MAX);
    assertThat(deepest.refractoryLeft).isEqualTo(0);
  }

  @Test
  void probeEnding_starvationConfirm_repeatOfTheFarthest_escalatesTheLadder() {
    // A kept confirm at or short of the farthest window the ladder's walks have already confirmed
    // re-finds ground the machine found and then lost, so it is priced as a completed experiment
    // rather than rewarded: rewarding it pins the ladder at its first rung on every cycle of a lure
    // that a first-round stride catches and density dismantles when it stops (the absolve family).
    // The handoff to density and the zero refractory are unchanged; a confirm beyond the farthest
    // is new ground and still rewards, moving the memory with it
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    sample(climber, /* windowMax= */ 2000, /* windowHits= */ 500, /* mainHits= */ 400,
        /* misses= */ 100);
    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.starvation.farthest).isEqualTo(2000);
    assertThat(climber.starvation.farthestDown).isFalse();

    reprobeUp(climber, /* baseHitRate= */ 0.5);
    long adjustment = sample(climber, /* windowMax= */ 1800, /* windowHits= */ 500,
        /* mainHits= */ 400, /* misses= */ 100);
    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(2);
    assertThat(climber.refractoryLeft).isEqualTo(0);
    assertThat(climber.starvation.farthest).isEqualTo(2000);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 1800, /* windowHits= */ 500, /* mainHits= */ 400));

    // within the stable band of the farthest is still a repeat
    reprobeUp(climber, /* baseHitRate= */ 0.5);
    sample(climber, /* windowMax= */ 2100, /* windowHits= */ 500, /* mainHits= */ 400,
        /* misses= */ 100);
    assertThat(climber.starvation.rung).isEqualTo(4);
    assertThat(climber.starvation.farthest).isEqualTo(2100);

    reprobeUp(climber, /* baseHitRate= */ 0.5);
    sample(climber, /* windowMax= */ 2600, /* windowHits= */ 500, /* mainHits= */ 400,
        /* misses= */ 100);
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.starvation.farthest).isEqualTo(2600);
  }

  @Test
  void probeEnding_starvationConfirm_theOtherDirection_startsTheMemoryOver() {
    // the memory is per direction: a down-walk's confirm after up-walk confirms is new ground, and
    // farthest for a down-walk is the smallest window
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    climber.starvation.remember(/* down= */ false, 2000);
    sample(climber, /* windowMax= */ 2000, /* windowHits= */ 5, /* mainHits= */ 900,
        /* misses= */ 95);
    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.starvation.farthestDown).isTrue();
    assertThat(climber.starvation.farthest).isEqualTo(2000);

    reprobeDown(climber, /* baseHitRate= */ 0.9);
    sample(climber, /* windowMax= */ 2100, /* windowHits= */ 5, /* mainHits= */ 900,
        /* misses= */ 95);
    assertThat(climber.starvation.rung).isEqualTo(2);
    assertThat(climber.starvation.farthest).isEqualTo(2000);

    reprobeDown(climber, /* baseHitRate= */ 0.9);
    sample(climber, /* windowMax= */ 1500, /* windowHits= */ 5, /* mainHits= */ 900,
        /* misses= */ 95);
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(climber.starvation.farthest).isEqualTo(1500);
  }

  @Test
  void undoProbe_starvationFailOrCrash_forgetsTheFarthest() {
    // a walk that keeps nothing shows the terrain the memory was found on has moved; an audit's
    // endings leave the starvation ladder's memory alone
    var failed = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    sample(failed, /* windowMax= */ 2000, /* windowHits= */ 500, /* mainHits= */ 400,
        /* misses= */ 100);
    assertThat(failed.starvation.farthest).isEqualTo(2000);
    reprobeUp(failed, /* baseHitRate= */ 0.5).samples = PROBE_WALK_BUDGET;
    sample(failed, /* windowMax= */ 1500, /* windowHits= */ 0, /* mainHits= */ 500,
        /* misses= */ 500);
    assertThat(failed.walk).isNull();
    assertThat(failed.starvation.farthest).isEqualTo(-1);

    var crashed = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    sample(crashed, /* windowMax= */ 2000, /* windowHits= */ 500, /* mainHits= */ 400,
        /* misses= */ 100);
    reprobeUp(crashed, /* baseHitRate= */ 0.5);
    sample(crashed, /* windowMax= */ 1500, /* windowHits= */ 0, /* mainHits= */ 100,
        /* misses= */ 900);
    assertThat(crashed.walk).isNull();
    assertThat(crashed.starvation.crashStreak).isEqualTo(1);
    assertThat(crashed.starvation.farthest).isEqualTo(-1);

    var audited = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.5);
    sample(audited, /* windowMax= */ 2000, /* windowHits= */ 500, /* mainHits= */ 400,
        /* misses= */ 100);
    injectWalk(audited, /* isAudit= */ true, /* down= */ true, /* baseWindow= */ 2000,
        /* baseHitRate= */ 0.5, /* baseSmoothedRate= */ 0.5).samples = PROBE_WALK_BUDGET;
    audited.sample.previousHitRate = 0.5;
    sample(audited, /* windowMax= */ 1800, /* windowHits= */ 400, /* mainHits= */ 100,
        /* misses= */ 500);
    assertThat(audited.walk).isNull();
    assertThat(audited.starvation.farthest).isEqualTo(2000);
  }

  @Test
  void probeEnding_deepReversedConfirm_goalConfirmed_parksAsAnAudit() {
    // A starvation walk that took the deepest commitment, whose confirm density reverses, and
    // that satisfies the audit's own confirm test is an audit in all but name: it is kept as a
    // park, since density would walk it home in the same sample, and the sample takes no parting
    // step. The base rate is far below what the walk earns, so the goal metric confirms it
    var climber = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.30,
        /* baseProbationDensity= */ 0.002);
    climber.starvation.rung = PROBE_BACKOFF_MAX;
    var walk = walkOf(climber);
    walk.samples = PROBE_COMMITMENT_DEEP;
    walk.aboveStreak = AUDIT_CONFIRM_STREAK - 1;
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);
    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo(0);
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.freshLeft).isEqualTo(AUDIT_WAIT_INITIAL);
    assertThat(climber.anchor.window).isEqualTo(2000);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_MAX);

    // without the goal metric's streak the same confirm hands back to density, as any reversed
    // confirm does
    var unconfirmed = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.30,
        /* baseProbationDensity= */ 0.002);
    unconfirmed.starvation.rung = PROBE_BACKOFF_MAX;
    walkOf(unconfirmed).samples = PROBE_COMMITMENT_DEEP;
    long steered = sample(unconfirmed, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);
    assertThat(unconfirmed.walk).isNull();
    assertThat(unconfirmed.anchor.held).isFalse();
    assertThat(steered).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 30, /* mainHits= */ 940));

    // and so does a walk short of the deepest commitment, streak or not
    var shallow = probingUp(/* stepSize= */ STRIDE, /* baseHitRate= */ 0.30,
        /* baseProbationDensity= */ 0.002);
    walkOf(shallow).aboveStreak = AUDIT_CONFIRM_STREAK - 1;
    long shallowStep = sample(shallow, /* windowMax= */ 2000,
        /* windowHits= */ 30, /* mainHits= */ 940, /* misses= */ 30);
    assertThat(shallow.walk).isNull();
    assertThat(shallow.anchor.held).isFalse();
    assertThat(shallowStep).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 30, /* mainHits= */ 940));
  }

  @Test
  void audit_walkFromPark_crashScaleMove_keepsThePark() {
    // an audit's walk out of a park is the park's own re-test: the crash-scale move it makes when
    // it walks into a valley is what the crash abort prices, and the undo returns to the park, so
    // it is not read as a workload shift that stands the park down (a shift while no walk is in
    // flight still does, as guardRail_crashScaleShift_releasesTheParkAndReturn pins)
    var climber = armAudit(/* windowMax= */ 2000);
    climber.anchor.held = true;
    climber.anchor.freshLeft = 0;
    long walked = 2000 + climber.adjustment();
    long undo = steadySample(climber, walked, /* hitRate= */ 0.50);
    assertThat(climber.walk).isNull();
    assertThat(undo).isEqualTo(2000 - walked);
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(2000);
  }

  @Test
  void probeEnding_downProbe_ignoresProbationBaseline() {
    // Down-probes keep the average-density verdict, since the window has no marginal substructure,
    // so a poisoned baseline must not affect their adjudication.
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9,
        /* baseProbationDensity= */ 1.0e9);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 5, /* mainHits= */ 900, /* misses= */ 95);

    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(1);
    assertThat(adjustment).isEqualTo(densityStep(
        /* windowMax= */ 2000, /* windowHits= */ 5, /* mainHits= */ 900));
  }

  @Test
  void probeEnding_adjudicationBar_isFourTimesTheStarvationBar() {
    // the watched region may judge at exactly four times the bar; one hit fewer keeps walking
    var judged = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    long verdict = sample(judged, /* windowMax= */ 2000,
        /* windowHits= */ 850, /* mainHits= */ 16, /* misses= */ 134);
    assertThat(judged.walk).isNull();
    assertThat(verdict).isEqualTo((long) (MAX_STEP_FRACTION * MAXIMUM));

    var walking = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    long stride = sample(walking, /* windowMax= */ 2000,
        /* windowHits= */ 850, /* mainHits= */ 15, /* misses= */ 135);
    assertThat(walking.walk).isNotNull();
    assertThat(stride).isEqualTo((long) (STEP_DECAY_RATE * -STRIDE));
  }

  @Test
  void probeEnding_neutralVerdict_fails() {
    // "no better, no worse" must fail: an early design that kept neutral positions let the
    // density arm walk the window home and the probe re-fire, forever. Equal densities give a
    // signed verdict of exactly zero, which does not confirm the probed direction.
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.9);
    long adjustment = sample(climber, /* windowMax= */ 2000,
        /* windowHits= */ 250, /* mainHits= */ 774, /* misses= */ 0);

    assertThat(climber.walk).isNull();
    assertThat(adjustment).isEqualTo((long) (MAX_STEP_FRACTION * MAXIMUM));
    assertThat(climber.undoRemaining).isEqualTo(5000 - (long) (MAX_STEP_FRACTION * MAXIMUM));
    assertThat(climber.starvation.rung).isEqualTo(2 * PROBE_BACKOFF_INITIAL);
  }

  @Test
  void probeEnding_ladderCapBinds() {
    var climber = probingDown(/* stepSize= */ -STRIDE, /* baseHitRate= */ 0.5);
    climber.starvation.rung = PROBE_BACKOFF_MAX;
    walkOf(climber).samples = PROBE_WALK_BUDGET;
    long undo = sample(climber, /* windowMax= */ 5000,
        /* windowHits= */ 495, /* mainHits= */ 5, /* misses= */ 500);

    assertThat(undo).isEqualTo(7000 - 5000);
    assertThat(climber.walk).isNull();
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_MAX);
  }

  /* --------------- Tiers --------------- */

  @Test
  void mediumTier_usesSketchPeriodAndStandardDecay() {
    var climber = new WindowClimber();
    climber.resized(2048);
    double seed = climber.step.size;

    for (int i = 0; i < 500; i++) {
      climber.recordHit(/* inWindow= */ false, /* inProbation= */ false);
      climber.recordMiss();
    }
    climber.determineAdjustment(2048, /* windowMaximum= */ 200,
        protectedMax(2048, 200), /* sketchSampleSize= */ 1001);
    assertThat(climber.adjustment()).isEqualTo(0);
    assertThat(climber.sample.hits).isEqualTo(500);

    climber.recordMiss();
    climber.determineAdjustment(2048, /* windowMaximum= */ 200,
        protectedMax(2048, 200), /* sketchSampleSize= */ 1001);
    assertThat(climber.adjustment()).isEqualTo((long) seed);
    assertThat(climber.sample.hits).isEqualTo(0);

    for (int i = 0; i < 505; i++) {
      climber.recordHit(/* inWindow= */ false, /* inProbation= */ false);
    }
    for (int i = 0; i < 496; i++) {
      climber.recordMiss();
    }
    climber.determineAdjustment(2048, /* windowMaximum= */ 200,
        protectedMax(2048, 200), /* sketchSampleSize= */ 1001);
    assertThat(climber.adjustment()).isEqualTo((long) seed);
    assertThat(climber.step.size).isWithin(1.0e-9).of(STEP_DECAY_RATE * seed);
  }

  @Test
  void mediumTier_reversesOnWorse_andRestartsOnCrashScaleChange() {
    // the reactive climber's two remaining decisions: a worsening sub-crash sample reverses the
    // bold driver, and a crash-scale change re-anneals the decayed step to its full magnitude
    var climber = new WindowClimber();
    climber.resized(2048);
    double seed = climber.step.size;
    assertThat(seed).isEqualTo(-STEP_PERCENT * 2048);

    reactiveSample(climber, /* hits= */ 500, /* misses= */ 501);
    assertThat(climber.adjustment()).isEqualTo((long) seed);

    reactiveSample(climber, /* hits= */ 480, /* misses= */ 521);
    assertThat(climber.adjustment()).isEqualTo((long) -seed);
    assertThat(climber.step.size).isWithin(1.0e-9).of(STEP_DECAY_RATE * -seed);

    reactiveSample(climber, /* hits= */ 590, /* misses= */ 411);
    assertThat(climber.adjustment()).isEqualTo((long) (STEP_DECAY_RATE * -seed));
    assertThat(climber.step.size).isEqualTo(-seed);
  }

  /** Completes one full sample in the medium tier (sketch period 1001). */
  private static void reactiveSample(WindowClimber climber, int hits, int misses) {
    for (int i = 0; i < hits; i++) {
      climber.recordHit(/* inWindow= */ false, /* inProbation= */ false);
    }
    for (int i = 0; i < misses; i++) {
      climber.recordMiss();
    }
    climber.determineAdjustment(2048, /* windowMaximum= */ 200,
        protectedMax(2048, 200), /* sketchSampleSize= */ 1001);
  }

  /* --------------- The review's wall-sit scenario --------------- */

  @Test
  void walk_floorBlockedDownProbe_neverStridesUpAndUndoesOnExpiry() {
    long maximum = 1_048_576;
    var climber = new WindowClimber();
    climber.resized(maximum);
    climber.starvation.rung = PROBE_BACKOFF_MAX;
    double floor = WINDOW_FLOOR_FRACTION * maximum;
    double stride = STEP_PERCENT * maximum;
    double base = 800_000;

    // the walk arms from a dead sample past the midpoint, the one down trigger left now that a
    // starved main beside a large window steers instead of probing
    @Var long windowMax = (long) base;
    long arming = bigSample(climber, maximum, windowMax, /* hits= */ 0);
    double expected = -Math.min(MAX_STEP_FRACTION * maximum, 4 * stride);
    assertThat(arming).isEqualTo((long) expected);
    assertThat(walkOf(climber).down).isTrue();
    windowMax += arming;

    @Var int moving = 1;
    @Var int blocked = 0;
    while (climber.walk != null) {
      int hits = (blocked == 4) ? 540 : 500;  // an improving sub-crash jitter mid-wall
      long adjustment = bigSample(climber, maximum, windowMax, hits);
      if (climber.walk != null) {
        assertThat(adjustment).isAtMost(0);
        if (adjustment == 0) {
          assertThat(windowMax).isAtLeast((long) floor);
          assertThat(windowMax).isAtMost((long) Math.ceil(floor) + 1);
          blocked++;
        } else {
          moving++;
        }
        windowMax += adjustment;
      } else {
        // the restore honors the maximum step; the remainder continues on the following samples
        double cap = MAX_STEP_FRACTION * maximum;
        assertThat(adjustment).isAtMost((long) cap);
        windowMax += adjustment;
        while (climber.undoRemaining != 0) {
          long chunk = bigSample(climber, maximum, windowMax, /* hits= */ 500);
          assertThat(Math.abs(chunk)).isAtMost((long) cap);
          windowMax += chunk;
        }
      }
    }
    assertThat((double) windowMax).isWithin(4.0).of(base);
    assertThat(moving).isAtMost(5);
    assertThat(blocked).isAtLeast(10);
    assertThat(climber.starvation.rung).isEqualTo(PROBE_BACKOFF_MAX);
  }

  @Test
  @SuppressFBWarnings("UM_UNNECESSARY_MATH")
  void walk_floorBlockedAudit_chargesTheClockForEvidenceItNeverGathered() {
    // The sibling above pins the starvation half of a floor-blocked walk. This is the audit half,
    // and it is the costlier one: strides that clamp at the rail still spend a sample apiece and
    // the walk still ends at its budget, and since there is no BLOCKED ending the expiry is
    // priced as a completed failure. At the deepest rung that doubles the wait between audits on
    // evidence the walk never gathered. Pinned as the current contract, so a BLOCKED ending would
    // have to move these numbers deliberately.
    var climber = makeClimber();
    climber.audit.rung = PROBE_BACKOFF_MAX;
    climber.auditClock.waitSamples = AUDIT_WAIT_MAX / 2;
    climber.auditClock.stillSamples = AUDIT_WAIT_MAX;
    climber.rates.smoothed = 0.50;
    climber.rates.deviation = 0.001;
    climber.sample.previousHitRate = 0.50;

    // Both regions earn, so no blind corner pre-empts the clock; the window sits past the upper
    // corner, where the direction rule chooses down and the walk runs into the floor.
    @Var long windowMax = 7000;
    windowMax += sample(climber, windowMax,
        /* windowHits= */ 250, /* mainHits= */ 250, /* misses= */ 500);
    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(walkOf(climber).down).isTrue();

    @Var long lowest = windowMax;
    @Var int blocked = 0;
    while (climber.walk != null) {
      long adjustment = sample(climber, windowMax,
          /* windowHits= */ 250, /* mainHits= */ 250, /* misses= */ 500);
      if ((climber.walk != null) && (adjustment == 0)) {
        blocked++;
      }
      windowMax += adjustment;
      lowest = Math.min(lowest, windowMax);
    }

    // it reached the rail, stood there for most of its budget, and was charged a failure for it
    assertThat(blocked).isAtLeast(PROBE_WALK_BUDGET / 2);
    assertThat(lowest).isAtMost((long) Math.ceil(FLOOR) + 1);
    assertThat(climber.audit.rung).isEqualTo(PROBE_BACKOFF_MAX);
    assertThat(climber.auditClock.waitSamples).isEqualTo(AUDIT_WAIT_MAX);
  }

  /* --------------- Helpers --------------- */

  private static WindowClimber makeClimber() {
    var climber = new WindowClimber();
    climber.resized(MAXIMUM);
    return climber;
  }

  /**
   * The walk in flight, asserting that there is one. Never hold the result across a sample: an
   * ending discards the walk, so a carried local reports the state it died in.
   */
  private static WindowClimber.Walk walkOf(WindowClimber climber) {
    var walk = climber.walk;
    assertThat(walk).isNotNull();
    return walk;
  }

  /**
   * Puts a walk in flight, as an arm would have left it, so a scenario can start from a base the
   * machine would need many samples to reach. The returned walk is the handle for the counters a
   * scenario also wants to pre-set; the probation baseline is the zero a fresh climber carries,
   * since only an up-probe's starvation verdict reads it.
   */
  @CanIgnoreReturnValue
  private static WindowClimber.Walk injectWalk(WindowClimber climber, boolean isAudit,
      boolean down, long baseWindow, double baseHitRate, double baseSmoothedRate) {
    var walk = new WindowClimber.Walk(isAudit ? climber.audit : climber.starvation, isAudit, down,
        baseWindow, /* baseRequestCount= */ 1000, baseHitRate, baseSmoothedRate,
        /* baseProbationDensity= */ 0.0);
    climber.walk = walk;
    return walk;
  }

  /**
   * Re-freezes the live walk's confirm reference to a rate colder than its start, as an arm whose
   * smoothed rate lags a warming sample carries. The bases are final by design, since the
   * frozen-at-arm property is the one the verdict studies keep re-deriving, so this swaps in a
   * copy, carrying the strides already taken.
   */
  @CanIgnoreReturnValue
  private static WindowClimber.Walk rebaseReference(WindowClimber climber, double reference) {
    var armed = walkOf(climber);
    var walk = new WindowClimber.Walk(armed.ladder, armed.isAudit, armed.down, armed.baseWindow,
        armed.baseRequestCount, armed.baseHitRate, reference, armed.baseProbationDensity);
    walk.samples = armed.samples;
    walk.belowBarStreak = armed.belowBarStreak;
    walk.aboveStreak = armed.aboveStreak;
    walk.beatBase = armed.beatBase;
    climber.walk = walk;
    return walk;
  }

  private static WindowClimber probingUp(double stepSize, double baseHitRate) {
    return probingUp(stepSize, baseHitRate, /* baseProbationDensity= */ 0.0);
  }

  /** Puts a fresh up-probe in flight on a climber whose ladder already carries a history. */
  @CanIgnoreReturnValue
  private static WindowClimber.Walk reprobeUp(WindowClimber climber, double baseHitRate) {
    return reprobe(climber, /* down= */ false, /* baseWindow= */ 1024, STRIDE, baseHitRate);
  }

  /** Puts a fresh down-probe in flight on a climber whose ladder already carries a history. */
  @CanIgnoreReturnValue
  private static WindowClimber.Walk reprobeDown(WindowClimber climber, double baseHitRate) {
    return reprobe(climber, /* down= */ true, /* baseWindow= */ 7000, -STRIDE, baseHitRate);
  }

  private static WindowClimber.Walk reprobe(WindowClimber climber, boolean down,
      long baseWindow, double stepSize, double baseHitRate) {
    var walk = injectWalk(climber, /* isAudit= */ false, down, baseWindow, baseHitRate,
        /* baseSmoothedRate= */ 0.0);
    walk.samples = 1;
    climber.step.size = stepSize;
    climber.sample.previousHitRate = baseHitRate;
    climber.refractoryLeft = 0;
    climber.undoRemaining = 0;
    return walk;
  }

  /**
   * Arms an audit at a quiet equilibrium and walks it to a confirmed park at the improved rate,
   * returning the climber parked below the position (the first interior audit explores down).
   */
  private static WindowClimber confirmedAuditPark(
      long windowMax, double baseRate, double walkedRate) {
    var climber = makeClimber();
    for (int i = 0; i < (AUDIT_WAIT_INITIAL + 2); i++) {
      steadySample(climber, windowMax, baseRate);
      if (climber.walk != null) {
        break;
      }
    }
    assertThat(walkOf(climber).isAudit).isTrue();
    assertThat(walkOf(climber).down).isTrue();
    @Var long walked = windowMax + climber.adjustment();
    for (int i = 0; i < PROBE_WALK_BUDGET; i++) {
      walked += steadySample(climber, walked, walkedRate);
      if (climber.walk == null) {
        break;
      }
    }
    assertThat(climber.walk).isNull();
    assertThat(climber.anchor.held).isTrue();
    assertThat(climber.anchor.window).isEqualTo(walked);
    return climber;
  }

  /** Holds the position still at the rate until the next audit arms, asserting it does. */
  private static void armNextAudit(WindowClimber climber, long windowMax, double hitRate) {
    for (int i = 0; i < (2 * AUDIT_WAIT_INITIAL); i++) {
      steadySample(climber, windowMax, hitRate);
      if (climber.walk != null) {
        break;
      }
    }
    assertThat(walkOf(climber).isAudit).isTrue();
  }

  private static WindowClimber probingUp(
      double stepSize, double baseHitRate, double baseProbationDensity) {
    return probing(/* down= */ false, stepSize, baseHitRate,
        /* baseWindow= */ 1024, baseProbationDensity);
  }

  private static WindowClimber probingDown(double stepSize, double baseHitRate) {
    return probingDown(stepSize, baseHitRate, /* baseProbationDensity= */ 0.0);
  }

  private static WindowClimber probingDown(
      double stepSize, double baseHitRate, double baseProbationDensity) {
    return probing(/* down= */ true, stepSize, baseHitRate,
        /* baseWindow= */ 7000, baseProbationDensity);
  }

  /**
   * Arms a down probe whose first sample fails on the density verdict, leaving a retreat longer
   * than one capped stride so that the undo drains across the samples that follow.
   */
  private static WindowClimber failingDownProbe() {
    var climber = makeClimber();
    injectWalk(climber, /* isAudit= */ false, /* down= */ true, /* baseWindow= */ 7000,
        /* baseHitRate= */ 0.70, /* baseSmoothedRate= */ 0.0).samples = 1;
    climber.sample.previousHitRate = 0.70;
    climber.rates.deviation = 0.001;
    climber.rates.smoothed = 0.70;
    climber.step.size = -STRIDE;
    return climber;
  }

  private static WindowClimber probing(boolean down, double stepSize,
      double baseHitRate, long baseWindow, double baseProbationDensity) {
    var climber = makeClimber();
    var walk = new WindowClimber.Walk(climber.starvation, /* isAudit= */ false, down, baseWindow,
        /* baseRequestCount= */ 1000, baseHitRate, /* baseSmoothedRate= */ 0.0,
        baseProbationDensity);
    walk.samples = 1;
    climber.walk = walk;
    climber.step.size = stepSize;
    climber.sample.previousHitRate = baseHitRate;
    return climber;
  }

  /** Completes one thousand-request sample and returns the committed adjustment. */
  @CanIgnoreReturnValue
  private static long sample(WindowClimber climber,
      long windowMax, int windowHits, int mainHits, int misses) {
    return sampleWithProbation(climber, windowMax,
        windowHits, mainHits, /* probationHits= */ 0, misses);
  }

  /** Completes one sample whose main hits include the given probation-attributed share. */
  private static long sampleWithProbation(WindowClimber climber,
      long windowMax, int windowHits, int mainHits, int probationHits, int misses) {
    for (int i = 0; i < windowHits; i++) {
      climber.recordHit(/* inWindow= */ true, /* inProbation= */ false);
    }
    for (int i = 0; i < (mainHits - probationHits); i++) {
      climber.recordHit(/* inWindow= */ false, /* inProbation= */ false);
    }
    for (int i = 0; i < probationHits; i++) {
      climber.recordHit(/* inWindow= */ false, /* inProbation= */ true);
    }
    for (int i = 0; i < misses; i++) {
      climber.recordMiss();
    }
    int requests = windowHits + mainHits + misses;
    climber.determineAdjustment(MAXIMUM, windowMax, protectedMax(MAXIMUM, windowMax),
        /* sketchSampleSize= */ requests);
    return climber.adjustment();
  }

  /** Completes a thousand-request sample with the given window hits (main earns nothing). */
  private static long bigSample(WindowClimber climber, long maximum, long windowMax, int hits) {
    for (int i = 0; i < hits; i++) {
      climber.recordHit(/* inWindow= */ true, /* inProbation= */ false);
    }
    for (int i = 0; i < (1000 - hits); i++) {
      climber.recordMiss();
    }
    climber.determineAdjustment(maximum, windowMax, protectedMax(maximum, windowMax),
        /* sketchSampleSize= */ 1000);
    return climber.adjustment();
  }

  /** The protected region's maximum for the main space left by the window (80% of main). */
  private static long protectedMax(long maximum, long windowMax) {
    return ((maximum - windowMax) * 4) / 5;
  }

  /** The probation capacity implied by {@link #protectedMax}. */
  private static long probationCapacity(long windowMax) {
    return MAXIMUM - windowMax - protectedMax(MAXIMUM, windowMax);
  }

  /** Mirrors the density arm's proportional step for an expected-value computation. */
  private static long densityStep(long windowMax, int windowHits, int mainHits) {
    double windowDensity = windowHits / (double) windowMax;
    double mainDensity = mainHits / (double) (MAXIMUM - windowMax);
    double error = Math.log((windowDensity + DENSITY_EPSILON)
        / (mainDensity + DENSITY_EPSILON));
    double magnitude = Math.min(MAX_STEP_FRACTION * MAXIMUM,
        Math.abs(error) * DENSITY_GAIN * MAXIMUM);
    double step = (error >= 0) ? magnitude : -magnitude;
    double clamped = ((step < 0) && ((windowMax + step) < FLOOR))
        ? Math.min(0.0, FLOOR - windowMax)
        : step;
    return (long) clamped;
  }
}
