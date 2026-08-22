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

import static com.github.benmanes.caffeine.cache.WindowClimber.Rates.VETO_MARGIN_MIN;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * A hill climber that adapts the size of the admission window to balance the cache's recency and
 * frequency regions.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class WindowClimber {

  /*
   * This class determines how to adapt the size of W-TinyLfu's admission window, which balances the
   * cache between recency (the window, admitting every new arrival) and frequency (the main space,
   * guarded by the TinyLfu filter). The best split is workload dependent, so it is climbed online
   * using the cache's own behavior as feedback.
   *
   * Small caches climb reactively on the hit rate by keeping the direction while a sample's hit
   * rate matches or beats the previous one's, reverse otherwise, and decay the step size towards
   * convergence.
   *
   * Larger caches take advantage of their stronger signals to compare the hit density (hits per
   * unit of capacity) of the two regions within a single sample and step proportionally, shifting
   * capacity towards the region earning more per unit. That signal is immune to workload phases but
   * blind when a region is too starved to measure, so at those corners the climber probes. The
   * probe temporarily walks the window by the hit rate and keeps the new position only on a clear
   * density verdict for the probed direction (an up-probe is priced against main's marginal
   * density frozen at the probe's start), retreating and backing off otherwise. A goal-metric
   * layer polices what density cannot judge: the last well-performing position is remembered as
   * an anchor, defended by the guard rail's veto and re-tested by scheduled audits.
   *
   * [1] The Adaptive Window, From the Ground Up
   * https://htmlpreview.github.io/?https://github.com/ben-manes/caffeine/blob/master/.claude/docs/adaptive-window.html
   */

  /** The difference in hit rates that reads as a workload change; the climber restarts at it. */
  static final double RESTART_THRESHOLD = 0.05d;
  /** The samples a retreat's cover spans: the stride commanded now, and the sample it lands on. */
  static final int RETREAT_COVER = 2;

  final ReactiveClimber reactive;
  final DensityClimber density;
  final AuditClock auditClock;
  final Ladder starvation;
  final Anchor anchor;
  final Sample sample;
  final Ladder audit;
  final Rates rates;
  final Step step;

  @Nullable Walk walk;

  long undoRemaining;
  int refractoryLeft;
  int retreatLeft;
  long adjustment;

  public WindowClimber() {
    step = new Step();
    rates = new Rates();
    audit = new Ladder();
    anchor = new Anchor();
    sample = new Sample();
    starvation = new Ladder();
    auditClock = new AuditClock();
    density = new DensityClimber(step);
    reactive = new ReactiveClimber(step);
  }

  /** Resets the state and seeds the step size when the cache's maximum size is changed. */
  public void resized(long maximum) {
    refractoryLeft = 0;
    undoRemaining = 0;
    retreatLeft = 0;
    adjustment = 0;
    walk = null;

    audit.reset();
    rates.reset();
    sample.reset();
    anchor.reset();
    auditClock.reset();
    starvation.reset();
    step.reset(maximum);
  }

  /** Discards the partial sample, as a resize does. */
  public void resetSample() {
    sample.reset();
  }

  /** Records a cache miss. */
  public void recordMiss() {
    sample.recordMiss();
  }

  /** Records a cache hit on an entry residing in the window or main space. */
  public void recordHit(boolean inWindow, boolean inProbation) {
    sample.recordHit(inWindow, inProbation);
  }

  /** Returns a positive value to grow the admission window and a negative one to shrink it. */
  public long adjustment() {
    return adjustment;
  }

  /** Retains the portion of the adjustment that could not be applied. */
  public void carryOver(long remaining) {
    adjustment = remaining;
  }

  /** Calculates the amount to adapt the window by. */
  public void determineAdjustment(long maximum, long windowMaximum,
      long mainProtectedMaximum, int sketchSampleSize) {
    long requestCount = sample.requestCount();
    if (requestCount < samplePeriod(maximum, sketchSampleSize)) {
      return;
    }
    double hitRate = sample.hitRate();
    boolean dense = DensityClimber.appliesTo(maximum);
    double amount = dense
        ? densityClimb(hitRate, requestCount, maximum, windowMaximum, mainProtectedMaximum)
        : reactive.climb(sample.hitRateChange(hitRate), maximum);
    adjustment = (long) amount;
    if (dense) {
      auditClock.tick(windowMaximum, Reading.stableBand(maximum));
    }
    sample.close(hitRate);
  }

  /** Returns the number of requests in an adaptation sample. */
  private long samplePeriod(long maximum, int sketchSampleSize) {
    return DensityClimber.appliesTo(maximum)
        ? density.samplePeriod(maximum, sketchSampleSize)
        : reactive.samplePeriod(maximum, sketchSampleSize);
  }

  /* --------------- Density Climb --------------- */

  /**
   * Returns the new step size, from the density law or from the mitigations for exception states
   * that its signal cannot judge for itself.
   */
  private double densityClimb(double hitRate, long requestCount,
      long maximum, long windowMax, long mainProtectedMax) {
    ageParkShield();
    ageRetreatCover();

    var reading = new Reading(hitRate, requestCount, maximum, windowMax, mainProtectedMax,
        sample.windowHits, sample.hits - sample.windowHits, sample.probationHits);
    if (isWorkloadShift(reading) && anchor.standDown(reading)) {
      // The regime that earned the discarded claim also produced the reference it was measured
      // against, so the goal metric re-learns from here rather than smoothing across the shift
      rates.reset();
    }
    updateRateReferences(reading);

    var walk = this.walk;
    if (walk != null) {
      var ending = probeEnding(walk, reading);
      if (ending == ProbeEnding.WALKING) {
        return walkStep(walk, /* entry= */ false, reading);
      } else if (ending != ProbeEnding.CONFIRMED) {
        return undoProbe(walk, ending, reading);
      } else if (keepConfirmedPosition(walk, reading)) {
        return anchor.returning ? strideHome(reading) : 0.0;
      }
      // a starvation confirm hands control back to the density arm within this same sample
      return density.steer(reading.steeringError(), reading);
    } else if (hasPendingUndo()) {
      return undoStride(reading);
    } else if (anchor.returning) {
      return strideHome(reading);
    } else if (anchor.isRetestDue(reading)) {
      retestReturn(reading);
      return 0.0;
    } else if (reading.hasBlindCorner()) {
      return isBackingOff() ? holdOrAudit(reading) : armStarvationProbe(reading);
    } else if (anchor.vetoTriggered(reading, rates)) {
      return strideHome(reading);
    } else if (auditClock.isDue()) {
      return armEquilibriumAudit(reading);
    } else if (anchor.held) {
      // density already showed it steers away from a measurably better position here, so it stays
      // suppressed and the audit clock owns exploration
      return 0.0;
    }
    return density.steer(reading.steeringError(), reading);
  }

  /** Ages a freshly parked confirm's shield by one sample; a walk neither spends nor ages it. */
  private void ageParkShield() {
    if (isShielded()) {
      anchor.ageShield();
    }
  }

  /**
   * Ages a retreat's cover by one sample. Every stride of the retreat re-arms it, so it runs out
   * on the sample after the last one, which is the sample the window lands on.
   */
  private void ageRetreatCover() {
    if (retreatLeft > 0) {
      retreatLeft--;
    }
  }

  /**
   * Whether a freshly parked confirm is riding out weather this sample. A walk stands outside the
   * shield, neither spending it nor covered by it, so a shift during a walk armed from the park
   * stands the park down and takes the shield with it.
   */
  private boolean isShielded() {
    return (walk == null) && anchor.isShielded();
  }

  /**
   * Whether this sample's rate move announces a workload change. The machine's own re-tests do
   * not stand the anchor down: an audit's walk out of a park and the retreat that ends it, and a
   * veto's return until its retest has judged the claim, since the crash-scale moves those
   * produce are the machine's and the retest or the audit clock prices the ending.
   */
  private boolean isWorkloadShift(Reading reading) {
    return (Math.abs(sample.hitRateChange(reading.hitRate)) >= RESTART_THRESHOLD)
        && !isShielded() && !isParkTest() && !isReturnTest();
  }

  /**
   * Whether a retreat's cover is running or a veto's return has landed with its claim still to
   * be judged: the landing sample and the settle samples the retest spends, whose recovery is
   * the return's own and is judged by the retest rather than read as a shift.
   */
  private boolean isReturnTest() {
    return (retreatLeft > 0) || ((anchor.retestClaim >= 0) && !anchor.returning);
  }

  /** Whether a held park's own audit is walking out of it this sample. */
  private boolean isParkTest() {
    return anchor.held && (walk != null) && walk.isAudit;
  }

  /**
   * Maintains the goal-metric references: the smoothed rate pair, then the anchor judged against
   * it. An unseeded sample only seeds the rates, since there is nothing yet to smooth towards.
   */
  private void updateRateReferences(Reading reading) {
    if (rates.isUnseeded()) {
      rates.seed(reading.hitRate);
    } else {
      rates.update(reading.hitRate);
      anchor.track(reading, rates, /* probing= */ isProbing());
    }
  }

  /**
   * Whether a probe cycle is in progress. It spans the walk and the retreat that undoes it, since
   * a capped return drains across later samples with the walk already ended.
   */
  private boolean isProbing() {
    return (walk != null) || hasPendingUndo();
  }

  /* --------------- Exceptional Scenarios --------------- */

  /** Whether a capped return to a probe's start is still draining across later samples. */
  private boolean hasPendingUndo() {
    return undoRemaining != 0;
  }

  /**
   * Returns the next stride of a multi-sample restore toward the probe's base. The ledger is kept
   * in entries and charged with the command as published, since the cache truncates each command
   * and a ledger charged with the fractional stride would close short of the base.
   */
  private double undoStride(Reading reading) {
    @SuppressWarnings("LongDoubleConversion")
    double stride = reading.cappedStride(undoRemaining);
    undoRemaining -= (long) stride;
    retreatLeft = RETREAT_COVER;
    return step.commit(stride);
  }

  /** Returns a capped stride of a return towards the anchor. */
  private double strideHome(Reading reading) {
    return step.commit(anchor.strideHome(reading));
  }

  /**
   * Settles a completed return on the anchor and stands its claim down if the position no longer
   * earns it. Arriving is not evidence for the claim that sent the window here: the arrival's own
   * drop can fall under the crash-scale threshold while the claim is still a past regime's.
   */
  private void retestReturn(Reading reading) {
    if (anchor.retestFails(rates) && anchor.standDown(reading)) {
      // the regime that earned the discarded claim also produced the reference it was measured
      // against, so the goal metric re-learns from here
      rates.reset();
    }
  }

  /** Whether the starvation machine is still serving the refractory the last undo imposed. */
  private boolean isBackingOff() {
    return refractoryLeft > 0;
  }

  /**
   * Returns the refractory hold's command or an audit's entry stride when the clock came due during
   * the backoff. A hold moves nothing, so a blind corner that never clears would otherwise stand
   * still through the whole backoff with a re-test already owed.
   */
  private double holdOrAudit(Reading reading) {
    return auditClock.isDue() ? armEquilibriumAudit(reading) : holdInRefractory(reading);
  }

  /**
   * Returns the command for a refractory sample, which moves the window only to lift it off a
   * sub-floor position. Density cannot be trusted here, so the climber holds rather than falling
   * through to a steering step, where a handful of window hits in an otherwise blank sample would
   * authorize the maximum step. A hold is not recorded as a step, so the stride a walk continues
   * from stays the last one its driver took.
   */
  private double holdInRefractory(Reading reading) {
    refractoryLeft--;
    return reading.atLeastFloor(0.0);
  }

  /** Returns the entry stride of a walk armed out of a blind corner. */
  private double armStarvationProbe(Reading reading) {
    var armed = armProbe(reading, reading.shouldProbeDown(), /* isAudit= */ false);
    return walkStep(armed, /* entry= */ true, reading);
  }

  /**
   * Returns the entry stride of a walk that re-tests a long-held, sighted equilibrium with the
   * machine's full exit discipline. The starvation triggers fire only at the corners, so nothing
   * else re-examines an interior equilibrium.
   */
  private double armEquilibriumAudit(Reading reading) {
    var armed = armProbe(reading, auditClock.chooseDirection(
        reading, audit.stride(reading), rates.smoothed, anchor.held), /* isAudit= */ true);
    auditClock.restart();
    return walkStep(armed, /* entry= */ true, reading);
  }

  /**
   * Keeps the position a walk validated, returning whether the climber parks on this sample. The
   * position becomes the anchor at once so the guard rail can defend what the walk paid for, and
   * the window returns to it when the verdict is for ground the walk has already passed. An
   * audit's confirm parks as well, since density disagreed with this position by construction and
   * would dismantle it, and so does a starvation confirm that density reverses after the deepest
   * commitment when the goal metric confirms it, which is an audit in all but name; any other
   * starvation confirm hands back to density, whose disagreement the ladder has already priced.
   */
  private boolean keepConfirmedPosition(Walk walk, Reading reading) {
    boolean park = walk.isAudit || walk.isAuditGrade(reading);
    long position = walk.verdictWindow(reading);
    anchor.plant(position, rates.smoothed);
    if (park) {
      anchor.park(AuditClock.AUDIT_WAIT_INITIAL);
      if (position != reading.windowMax) {
        anchor.beginReturn();
      }
    } else {
      anchor.release();
    }
    return park;
  }

  /* --------------- Probe Walk --------------- */

  /**
   * Returns a probe armed for this sample, whose entry stride the caller takes. The caller gates
   * and directs it: a large region earning nothing is visible to density and must not arm one, or a
   * scan-filled main beside a small window earning everything would shrink the one region that is
   * working. The walk is measured against the smoothed rate of the position it leaves rather than
   * the anchor's claim. Whether that position is measurably worse than the anchor is the guard
   * rail's question, and a claim the workload can no longer produce would be a bar no walk could
   * clear.
   */
  private Walk armProbe(Reading reading, boolean down, boolean isAudit) {
    var ladder = isAudit ? audit : starvation;
    walk = new Walk(ladder, isAudit, down, reading.windowMax,
        reading.requestCount, reading.hitRate, rates.smoothed, reading.probationDensity);
    return walk;
  }

  /**
   * Returns a bold-driver stride of the probe's walk. A reversal that would cross back through the
   * probe's own start found nothing and finishes as a failed experiment rather than walking out the
   * other side.
   */
  private double walkStep(Walk walk, boolean entry, Reading reading) {
    @Var double stride = nextStride(walk, entry, reading);
    if (walk.crossesBase(reading.windowMax + stride)) {
      endWalk();
      walk.ladder.crashStreak = 0;
      return undoProbe(walk, ProbeEnding.FAILED, reading);
    } else if ((stride < 0) && ((reading.windowMax + stride) < reading.floor)) {
      stride = reading.flooredDescent();
    }
    walk.samples++;
    return step.commit(stride);
  }

  /**
   * Returns the stride this sample takes. The seed on entry, a decayed stride while a below-bar dip
   * is still being adjudicated by the persistence counter, and otherwise the bold driver. Letting
   * an unbelieved dip drive a reversal turns cheap crashes into rung-doubling failures through the
   * walk's own base.
   */
  private double nextStride(Walk walk, boolean entry, Reading reading) {
    double magnitude = walk.ladder.stride(reading);
    if (entry) {
      return walk.direction() * magnitude;
    } else if (walk.isAudit && (walk.belowBarStreak > 0)) {
      double held = Step.decayed(step.size);
      return Step.isFrozen(held) ? step.atMinimum() : held;
    }
    double bar = walk.reversalBar(rates);
    double hitRateChange = sample.hitRateChange(reading.hitRate);
    @Var double stride = step.heading(/* forward= */ hitRateChange > -bar);
    stride = (Math.abs(hitRateChange) >= bar)
        ? Math.copySign(magnitude, stride)
        : Step.decayed(stride);
    return Step.isFrozen(stride) ? (walk.direction() * magnitude) : stride;
  }

  /** Returns how the probe's walk ends. */
  private ProbeEnding probeEnding(Walk walk, Reading reading) {
    boolean belowBar = (reading.hitRate <= (walk.baseHitRate - walk.crashBar(rates)));
    walk.belowBarStreak = belowBar ? (walk.belowBarStreak + 1) : 0;
    if (walk.shouldCrashAbort(belowBar)) {
      // Probe damage and an exogenous shift are indistinguishable here, so the refractory re-arms
      // without doubling rather than mispricing a phase as a failed experiment. That holds for one
      // crash, not a cycle. A shift moves the rate once while a damaging probe moves it on every
      // arm, so consecutive crashes escalate the walk's own ladder like completed failures.
      endWalk();
      walk.ladder.crash();
      return ProbeEnding.CRASHED;
    }
    boolean above = (reading.hitRate > (walk.baseSmoothedRate + VETO_MARGIN_MIN));
    walk.aboveStreak = above ? (walk.aboveStreak + 1) : 0;
    walk.rememberBest(above, reading);
    walk.beatBase |= (reading.hitRate >= walk.baseHitRate);
    return walk.isAudit ? auditEnding(walk) : starvationEnding(walk, reading);
  }

  /**
   * Returns how an audit's walk ends. The goal metric adjudicates it, not density, as density holds
   * this equilibrium and would veto every walk away from it which is the bias the audit exists to
   * re-test.
   */
  private ProbeEnding auditEnding(Walk walk) {
    if (walk.isConfirmed()) {
      endWalk();
      walk.ladder.reset();
      refractoryLeft = 0;
      starvation.reward();
      auditClock.settle(walk.down, rates.smoothed);
      return ProbeEnding.CONFIRMED;
    } else if (walk.isBudgetSpent()) {
      endWalk();
      walk.ladder.crashStreak = 0;
      return ProbeEnding.FAILED;
    }
    return ProbeEnding.WALKING;
  }

  /**
   * Returns how a starvation probe's walk ends. A density verdict for the probed direction keeps
   * the position; any other verdict must fail the probe, or else density walks the window home
   * and the probe simply refires. A confirm that the density arm reverses in the same sample keeps
   * nothing either, so it deepens the ladder as a failure does, and so does a confirm at or short
   * of the farthest window the ladder's walks have already confirmed: that ground was found and
   * lost, and a walk that only finds it again has not earned the reward, which would pin the
   * ladder at its first rung on every cycle. The reward belongs to a kept position on new ground;
   * resetting the ladder otherwise would restart it on every cycle of a dither that stops short
   * of the band.
   */
  private ProbeEnding starvationEnding(Walk walk, Reading reading) {
    if (walk.canAdjudicate(reading, starvation.commitmentDepth())) {
      endWalk();
      walk.ladder.crashStreak = 0;
      if ((walk.verdictSignal(reading) * walk.direction()) > 0.0) {
        if (walk.isReversedBy(reading)
            || walk.ladder.isRepeat(walk.down, reading.windowMax, reading.band)) {
          walk.ladder.escalate();
        } else {
          walk.ladder.reward();
        }
        walk.ladder.remember(walk.down, reading.windowMax);
        refractoryLeft = 0;
        return ProbeEnding.CONFIRMED;
      }
      return ProbeEnding.FAILED;
    } else if (walk.isBudgetSpent()) {
      endWalk();
      walk.ladder.crashStreak = 0;
      return ProbeEnding.FAILED;
    }
    return ProbeEnding.WALKING;
  }

  /**
   * Returns the stride back to where the probe started, pricing the ending on the owning layer's
   * ladder. A walk arrives here crashed or failed, and a crash is priced as a failure once its run
   * escalates. The refractory is the starvation machine's own backoff, armed by its own endings;
   * an audit's retreat leaves whatever hold is running to run out. A starvation walk that keeps
   * nothing also shows that the terrain its ladder remembers has moved, so the memory goes.
   */
  private double undoProbe(Walk walk, ProbeEnding ending, Reading reading) {
    boolean crashed = (ending == ProbeEnding.CRASHED);
    boolean failed = !crashed || walk.ladder.crashEscalates();
    if (failed) {
      walk.ladder.escalate();
    }
    if (walk.isAudit) {
      auditClock.reschedule(failed, crashed, audit.rung);
    } else {
      refractoryLeft = starvation.rung;
      starvation.forget();
    }
    retreatLeft = RETREAT_COVER;
    return returnToBase(walk, reading);
  }

  /** Returns the stride back to the walk's starting window. */
  private double returnToBase(Walk walk, Reading reading) {
    long amount = (walk.baseWindow - reading.windowMax);
    @SuppressWarnings("LongDoubleConversion")
    double stride = reading.cappedStride(amount);
    undoRemaining = (amount - (long) stride);
    return step.commit(stride);
  }

  /** Ends the walk. */
  private void endWalk() {
    walk = null;
  }

  /** The counters for the sample in progress and the cross-sample memory. */
  static final class Sample {
    double previousHitRate;
    long probationHits;
    long windowHits;
    long misses;
    long hits;

    /**
     * Records a cache hit on an entry. Zero-weight entries must be excluded at the call site, since
     * they earn hits while occupying no capacity and break the hits-earned-by-capacity invariant
     * the region densities divide by.
     */
    void recordHit(boolean inWindow, boolean inProbation) {
      hits++;
      if (inWindow) {
        windowHits++;
      } else if (inProbation) {
        probationHits++;
      }
    }

    /**
     * Records a cache miss. Zero-weight entries must be included, since an insert's demand is real,
     * so the sampled rate can sit below the user-visible one.
     */
    void recordMiss() {
      misses++;
    }

    /** Returns the requests seen so far. */
    long requestCount() {
      return hits + misses;
    }

    /** Returns the ratio of requests which were hits. */
    double hitRate() {
      return (double) hits / requestCount();
    }

    /** Returns how far this sample's rate moved from the one before it. */
    double hitRateChange(double hitRate) {
      return hitRate - previousHitRate;
    }

    /** Closes the sample at the rate it earned, which becomes the next one's reference. */
    void close(double hitRate) {
      previousHitRate = hitRate;
      probationHits = 0;
      windowHits = 0;
      misses = 0;
      hits = 0;
    }

    /** Discards the sample and cross-sample memory. */
    void reset() {
      close(0.0);
    }
  }

  /** The sample's derived view of the two regions (densities, starvation bar, and geometry). */
  static final class Reading {
    /** The band within which two positions count as the same, as a fraction of the maximum. */
    static final double STABLE_BAND_FRACTION = 0.02d;
    /** The density law's lower bound on the window, as a fraction of the maximum. */
    static final double WINDOW_FLOOR_FRACTION = 0.02d;
    /** The density climber's largest single step, as a fraction of the maximum. */
    static final double MAX_STEP_FRACTION = 0.30d;
    /** The smoothing constant that keeps a starved region's density ratio defined. */
    static final double DENSITY_EPSILON = 1e-9d;
    /** The floor on the starvation bar, for samples too short for the proportional bar to bind. */
    static final long MIN_STARVATION_BAR = 4L;
    /** The right-shift of the sample's request count that sets the starvation bar. */
    static final int MIN_SIGNAL_SHIFT = 10;
    /** The fraction of the starvation bar imputed to a blank region in the steering error. */
    static final double STEERING_FLOOR_FRACTION = 0.125d;

    final double probationDensity;
    final double windowDensity;
    final double mainDensity;
    final double hitRate;
    final double floor;

    final boolean windowStarved;
    final boolean mainStarved;

    final long requestCount;
    final long windowHits;
    final long windowMax;
    final long mainHits;
    final long maximum;
    final long band;
    final long bar;

    Reading(double hitRate, long requestCount, long maximum, long windowMax,
        long mainProtectedMax, long windowHits, long mainHits, long probationHits) {
      this.bar = Math.max(MIN_STARVATION_BAR, requestCount >> MIN_SIGNAL_SHIFT);
      this.mainDensity = mainHits / (double) Math.max(1L, maximum - windowMax);
      this.windowDensity = windowHits / (double) Math.max(1L, windowMax);
      this.probationDensity = probationHits / (double) Math.max(
          1L, maximum - windowMax - mainProtectedMax);
      this.floor = WINDOW_FLOOR_FRACTION * maximum;
      this.windowStarved = (windowHits < bar);
      this.mainStarved = (mainHits < bar);
      this.requestCount = requestCount;
      this.band = stableBand(maximum);
      this.windowHits = windowHits;
      this.windowMax = windowMax;
      this.mainHits = mainHits;
      this.maximum = maximum;
      this.hitRate = hitRate;
    }

    /** Returns the band, in entries, within which a position counts as the same place. */
    static long stableBand(long maximum) {
      return Math.max(1L, (long) (STABLE_BAND_FRACTION * maximum));
    }

    /** Returns the smallest window that leaves main at most a quarter of the cache. */
    long upperCorner() {
      return maximum - (maximum >>> 2);
    }

    /** Returns the largest single move any command may make at this size. */
    double maxStep() {
      return MAX_STEP_FRACTION * maximum;
    }

    /** Returns a stride towards a target this far away, capped at the maximum step. */
    double cappedStride(double amount) {
      double cap = maxStep();
      return (Math.abs(amount) > cap) ? Math.copySign(cap, amount) : amount;
    }

    /** Returns the stride a restart seeds at, for this sample's geometry. */
    double restartMagnitude() {
      return Step.restartMagnitude(maximum);
    }

    /** Returns a down move stopped at the floor; the negative zero keeps the move headed down. */
    double flooredDescent() {
      return Math.min(-0.0d, floor - windowMax);
    }

    /** Returns the stride, raised to lift a sub-floor window back to the signal-capable floor. */
    double atLeastFloor(double stride) {
      return (windowMax < floor) ? Math.max(stride, floor - windowMax) : stride;
    }

    /** Returns whether the whole sample earned too little for either region to measure itself. */
    boolean isDeadSample() {
      return windowStarved && mainStarved;
    }

    /**
     * Returns whether the density signal is blind here. A starved main beside a large window is
     * not a blind corner: the equilibrium audit owns that terrain.
     */
    boolean hasBlindCorner() {
      return isDeadSample() || (windowStarved && (windowMax <= (maximum >>> 2)));
    }

    /** Returns the direction a starvation probe walks out of this corner. */
    boolean shouldProbeDown() {
      return isDeadSample() && (windowMax >= (maximum >>> 1));
    }

    /** Returns the raw density ratio the probe verdicts adjudicate against. */
    double error() {
      return Math.log((windowDensity + DENSITY_EPSILON) / (mainDensity + DENSITY_EPSILON));
    }

    /** Returns the steering form of the density error. */
    double steeringError() {
      double windowFloor = (STEERING_FLOOR_FRACTION * bar) / Math.max(1L, windowMax);
      double mainFloor = (STEERING_FLOOR_FRACTION * bar) / Math.max(1L, maximum - windowMax);
      return Math.log(Math.max(windowDensity, windowFloor) / Math.max(mainDensity, mainFloor));
    }
  }

  /**
   * The hill climber's step size: how far one adjustment moves the window, signed by direction. It
   * decays towards convergence as the climber settles, and re-seeds at restart magnitude when the
   * workload changes.
   */
  static final class Step {
    /** Lower bound on the initial step size so that small caches have an opportunity to adapt. */
    static final double MIN_INITIAL_STEP = 2.0d;
    /** The percent of the total size to adapt the window by. */
    static final double STEP_PERCENT = 0.0625d;
    /** The rate to decrease the step size to adapt by. */
    static final double STEP_DECAY_RATE = 0.98d;

    double size;

    /** Returns the stride a restart seeds at. */
    static double restartMagnitude(long maximum) {
      return Math.max(STEP_PERCENT * maximum, MIN_INITIAL_STEP);
    }

    /** Returns the step walked one decay towards convergence. */
    static double decayed(double step) {
      return STEP_DECAY_RATE * step;
    }

    /**
     * Whether a step has shrunk too small to move the window, so its driver must re-seed. Only a
     * step clamped at the window floor arrives here as decay cannot reach it in the density tier.
     */
    static boolean isFrozen(double step) {
      return Math.abs(step) < MIN_INITIAL_STEP;
    }

    /** Returns the last step, repeated or reversed. */
    double heading(boolean forward) {
      return forward ? size : -size;
    }

    /** Returns the step's size, ignoring direction. */
    double magnitude() {
      return Math.abs(size);
    }

    /** Returns the smallest step that still moves the window, headed the way the last one was. */
    double atMinimum() {
      return Math.copySign(MIN_INITIAL_STEP, size);
    }

    /** Returns the step this sample commands, recording it as the last one taken. */
    @CanIgnoreReturnValue
    double commit(double step) {
      size = step;
      return step;
    }

    /**
     * Seeds the opening command when the cache's maximum size is changed. The slow-adapt regime's
     * window is only a few entries, so it opens by growing; every larger cache opens by shrinking.
     */
    void reset(long maximum) {
      double magnitude = restartMagnitude(maximum);
      size = ReactiveClimber.isSlowAdapting(maximum) ? magnitude : -magnitude;
    }
  }

  /**
   * The hit-rate control law, used at or below {@link DensityClimber#DENSITY_THRESHOLD}: continue
   * in the direction that matched or beat the last sample's rate, reverse otherwise, decay
   * the step towards convergence, and re-seed at restart magnitude when the rate moves enough to
   * call the workload changed. No starved corner can blind it, so it needs none of the probe
   * machinery, but it cannot separate the window's own contribution from a workload phase.
   * <p>
   * At or below {@link #SLOW_ADAPT_THRESHOLD} the window is only a few integer entries and one
   * sample is too noisy to trust, so the law opens by growing, stretches its sample period as the
   * step decays, and decays that step more slowly.
   */
  static final class ReactiveClimber {
    /**
     * The size at/below which the climber adapts slowly and deliberately, because the window is
     * only a few integer entries and a single sample is too noisy to trust.
     */
    static final long SLOW_ADAPT_THRESHOLD = 512L;
    /** Maximum factor by which the sample period may grow in the slow-adapt regime. */
    static final double SLOW_ADAPT_RATIO_CAP = 4.0d;
    /** The decay rate in the slow-adapt regime to keep the step large enough for restarts. */
    static final double SLOW_ADAPT_DECAY_RATE = 0.995d;

    final Step step;

    ReactiveClimber(Step step) {
      this.step = step;
    }

    /** Whether this maximum falls in the slow-adapt regime. */
    static boolean isSlowAdapting(long maximum) {
      return maximum <= SLOW_ADAPT_THRESHOLD;
    }

    /** Returns the step the window moves by. */
    double climb(double hitRateChange, long maximum) {
      double amount = step.heading(/* forward= */ hitRateChange >= 0);
      step.commit((Math.abs(hitRateChange) >= RESTART_THRESHOLD)
          ? Math.copySign(Step.restartMagnitude(maximum), amount)
          : (decayRate(maximum) * amount));
      return amount;
    }

    /** Returns the number of requests this climber samples before it steps. */
    @SuppressWarnings("MathClampDouble")
    long samplePeriod(long maximum, int sketchSampleSize) {
      if (!isSlowAdapting(maximum)) {
        return sketchSampleSize;
      }
      double initialStep = Step.STEP_PERCENT * maximum;
      double magnitude = Math.max(step.magnitude(), initialStep / SLOW_ADAPT_RATIO_CAP);
      if (magnitude == 0.0) {
        return sketchSampleSize;
      }
      double ratio = Math.max(1.0, Math.min(SLOW_ADAPT_RATIO_CAP, initialStep / magnitude));
      return (ratio == 1.0) ? sketchSampleSize : (long) (sketchSampleSize * ratio);
    }

    /** Returns the step decay rate. */
    private static double decayRate(long maximum) {
      return isSlowAdapting(maximum) ? SLOW_ADAPT_DECAY_RATE : Step.STEP_DECAY_RATE;
    }
  }

  /**
   * The hit-density control law, used above {@link #DENSITY_THRESHOLD}: compare the two regions'
   * hits per unit of capacity within one sample and move capacity towards whichever earns more. The
   * step is proportional to the signed error, so a starved window takes a large step and a balanced
   * one settles, and being computed inside one sample it is immune to the cross-sample swings the
   * reactive law cannot see past.
   * <p>
   * The signal is resident-only, so a region earning roughly nothing is a state this law cannot
   * read and must never be trusted to hold position in. The probe machine, the audit layer and the
   * anchor that guard its blind and false equilibria belong to the supervisor.
   */
  static final class DensityClimber {
    /** The cache's maximum size above which this climber adjusts the window's size. */
    static final long DENSITY_THRESHOLD = 4096L;
    /** The step per unit of log density-ratio error, as a fraction of the maximum. */
    static final double DENSITY_GAIN = 0.03d;
    /**
     * The sample period, as a multiple of the maximum size, kept small enough to react promptly
     * while large enough so that brief, noisy density estimates near a small converged window do
     * not jitter it off a frequency-friendly optimum.
     */
    static final long SAMPLE_MULTIPLIER = 4L;

    final Step step;

    DensityClimber(Step step) {
      this.step = step;
    }

    /**
     * Whether this maximum's regions are large enough to measure separately within one sample.
     * <p>
     * The density signal, being resident-only, is both unreliable and prone to pinning at an
     * extreme when a region is small, and its within-sample gains only exceed the hit-rate
     * climber's above this size (they are roughly neutral below it), so it is scoped here to keep
     * the hit-rate climber's robustness at smaller sizes while adding density's wins for large
     * caches.
     */
    static boolean appliesTo(long maximum) {
      return maximum > DENSITY_THRESHOLD;
    }

    /** Returns a proportional step by the density error, clamped to the signal-capable floor. */
    @SuppressWarnings("MathClampDouble")
    double steer(double error, Reading r) {
      double magnitude = Math.min(r.maxStep(), Math.abs(error) * DENSITY_GAIN * r.maximum);
      double stride = (error >= 0) ? magnitude : -magnitude;
      double clamped = ((stride < 0) && ((r.windowMax + stride) < r.floor))
          ? r.flooredDescent()
          : stride;
      return step.commit(r.atLeastFloor(clamped));
    }

    /** Returns the number of requests this climber samples before it steps. */
    long samplePeriod(long maximum, int sketchSampleSize) {
      @Var long period = SAMPLE_MULTIPLIER * maximum;
      if ((period / SAMPLE_MULTIPLIER) != maximum) {
        period = Long.MAX_VALUE;
      }
      return Math.min(period, sketchSampleSize);
    }
  }

  /**
   * A walk in flight: bounded, adjudicated motion out of a blind or audited position. The bases are
   * frozen at the arm and are what the endings judge against, the counters are the sequential
   * detectors those endings read, and the ladder is the retry ledger of the layer that armed it,
   * the only one its ending may deepen.
   */
  static final class Walk {
    /*
     * Exit bars. A walk has two interior exits and they test different statistics, so they are
     * priced separately. The crash abort is a level test, the hit rate against the rate frozen at
     * the arm, measuring the damage the walk has done; the reversal is a first-difference test,
     * this sample against the last, measuring whether the previous stride hurt.
     *
     * A starvation probe prices both against the workload's own scatter (three deviations, floored
     * at RESTART_THRESHOLD, capped at PROBE_BAR_CAP times that floor), because a fixed threshold
     * sits below real per-sample noise and ordinary weather then aborts the walk that is a blind
     * corner's only exit. The deviation is read live rather than frozen at the arm, since the
     * walk's own transient lifts the bar exactly while the walk is exposed to the weather.
     *
     * An audit's crash abort keeps the absolute threshold; pricing it against the scatter lets
     * audits confirm and park far more often, which over-commits the window to positions density
     * disagrees with. Being a level test it is unsatisfiable where the whole rate is smaller than
     * the threshold, and only the budget would bound the walk, so AUDIT_BAR_FRACTION of the
     * starting rate takes over there, which binds only where that rate is under a third. At a rate
     * of exactly zero the bar is zero too and the walk aborts on its first still-dead sample, which
     * is intended: a region earning nothing has no damage to measure, and the crash defers the
     * retry.
     *
     * An audit's reversal takes the same fraction of the larger of that rate and the scatter, so a
     * difference test is never priced below the noise it must survive. Priced on the rate alone it
     * is a hair trigger wherever a workload is noisy relative to what it earns, and a reversal
     * back through the walk's own base is charged as a completed failure, which doubles the
     * layer's ladder and its wait. The absolute cap is load-bearing: it keeps the noise term from
     * becoming the widening that the crash abort must never take.
     *
     * Abort timing. A starvation probe aborts on its first below-bar sample. Only the retry of an
     * equilibrium that already crashed an audit is tolerant, aborting only once
     * AUDIT_CRASH_PERSISTENCE consecutive samples sit below the bar: an audit leaves a sighted
     * equilibrium and may have to cross terrain, where a one-sample abort makes any valley deeper
     * than the bar unreachable at every rung, and lone exogenous pulses ratchet separate audits
     * into a crash streak. Tolerance is spent in time, never in the bar's depth, or walks travel
     * and park at the extremes.
     *
     * Confirm. An audit confirms on AUDIT_CONFIRM_STREAK consecutive raw samples above a reference
     * frozen at the arm, the smoothed rate of the position the walk leaves, taken at
     * AUDIT_COMMITMENT depth. The streak is not deviation-priced the way the rail's margin is: the
     * deviation is workload-scale while an audit resolves the window's few-percent contribution,
     * so a priced bar never fires. The two want opposite pricings and must not share one, since a
     * false confirm self-heals at the next audit while a false veto churns the anchor. The streak
     * alone is not sufficient: its reference is absolute and can be colder than the walk, so a
     * confirm also requires beatBase. That test is inclusive because a saturating arming sample
     * makes any strictly-greater bar unsatisfiable.
     *
     * Verdict. A starvation probe is adjudicated by density once the watched region earns
     * PROBE_EXIT_BAR_MULTIPLE times the starvation bar. An up-probe is priced against main's margin
     * (the probation density frozen at the arm) rather than its average: growing the window takes
     * capacity whose squeeze demotes into probation, so probation is what a grow taxes, while
     * main's average is dominated by the protected core and vetoes winning positions. The freeze
     * matters because the walk's own demotions enrich live probation into a permanent veto. A
     * down-probe keeps the average test, having no marginal substructure to price against.
     *
     * Budget. PROBE_WALK_BUDGET bounds every walk, because neither exit can be relied on to end
     * one: stray hits scale with region size and must not end a walk early, and a hit-rate veto is
     * blind to damage under its own bar. It comfortably exceeds the longest confirmed escape, a
     * corner-to-corner traverse of ~13 decaying steps.
     */

    /** The samples a walk may take without confirmation before it is a failed experiment. */
    static final int PROBE_WALK_BUDGET = 16;
    /** The ceiling on a starvation probe's walk-interior bar, as a multiple of the threshold. */
    static final double PROBE_BAR_CAP = 3.0d;
    /** The multiple of the starvation bar at which the watched region may adjudicate a probe. */
    static final long PROBE_EXIT_BAR_MULTIPLE = 4L;
    /** The consecutive below-bar samples at which a tolerant audit's walk crash-aborts. */
    static final int AUDIT_CRASH_PERSISTENCE = 3;
    /** The walk samples an audit commits before it may be adjudicated. */
    static final int AUDIT_COMMITMENT = 5;
    /** The consecutive raw samples above the frozen reference that confirm an audit. */
    static final int AUDIT_CONFIRM_STREAK = 4;
    /**
     * The fraction an audit's walk-interior bars are priced at. It caps the crash abort against the
     * rate frozen at the arm, and prices the reversal against the larger of that rate and the
     * scatter, so the two exits share a fraction without sharing a bar. Sharing one constant is
     * deliberate: the level is derived rather than tuned and sits near a measured cliff, so it must
     * not become two independently adjustable bars.
     */
    static final double AUDIT_BAR_FRACTION = 0.15d;

    final boolean down;
    final Ladder ladder;
    final boolean isAudit;
    final long baseWindow;
    final double baseHitRate;
    final double baseSmoothedRate;
    final long baseRequestCount;
    final double baseProbationDensity;

    int samples;
    int aboveStreak;
    int belowBarStreak;

    long bestWindow;
    double bestRate;
    boolean beatBase;

    Walk(Ladder ladder, boolean isAudit, boolean down, long baseWindow, long baseRequestCount,
        double baseHitRate, double baseSmoothedRate, double baseProbationDensity) {
      this.baseProbationDensity = baseProbationDensity;
      this.baseSmoothedRate = baseSmoothedRate;
      this.baseRequestCount = baseRequestCount;
      this.baseHitRate = baseHitRate;
      this.baseWindow = baseWindow;
      this.isAudit = isAudit;
      this.ladder = ladder;
      this.bestWindow = -1;
      this.bestRate = -1.0;
      this.down = down;
    }

    /**
     * Remembers the best sample of the run that may confirm this walk. A broken run starts the
     * memory over, so the verdict is always for a position the confirming run itself stood on.
     * Ties are kept by the later sample, leaving a walk that finds no strictly better position
     * than the one it ends on to park where it ends.
     */
    void rememberBest(boolean above, Reading r) {
      if (!above) {
        bestWindow = -1;
        bestRate = -1.0;
      } else if (r.hitRate >= bestRate) {
        bestWindow = r.windowMax;
        bestRate = r.hitRate;
      }
    }

    /**
     * Returns the position a confirm is for: the best sample of the confirming run rather than the
     * one the run completed on. The streak needs four samples above the reference and the walk
     * strides on through all four, so a crest it crosses early is left behind by the time the
     * verdict comes in. The margin is the one the streak itself clears, since a walk over a flat
     * plateau has a best sample by noise alone and parking on it is a coin flip. A starvation
     * probe is adjudicated by density on the current sample and takes that position.
     */
    long verdictWindow(Reading r) {
      boolean better = isAudit && (bestWindow >= 0)
          && (bestRate > (r.hitRate + VETO_MARGIN_MIN));
      return better ? bestWindow : r.windowMax;
    }

    /** Returns the sign of the walk's direction. */
    double direction() {
      return down ? -1.0 : 1.0;
    }

    /** Whether the walk has spent its sample budget without ever reaching a verdict. */
    boolean isBudgetSpent() {
      return samples >= PROBE_WALK_BUDGET;
    }

    /** Whether this stride would carry the walk back across the window it started from. */
    boolean crossesBase(double position) {
      return down ? (position > baseWindow) : (position < baseWindow);
    }

    /** Returns the drop below the rate frozen at the arm that crash-aborts this walk. */
    @SuppressWarnings("MathClampDouble")
    double crashBar(Rates rates) {
      return isAudit
          ? Math.min(RESTART_THRESHOLD, AUDIT_BAR_FRACTION * baseHitRate)
          : Math.min(PROBE_BAR_CAP * RESTART_THRESHOLD,
              Math.max(RESTART_THRESHOLD, rates.noiseBand()));
    }

    /** Returns the sample-to-sample drop that reverses this walk's bold driver. */
    double reversalBar(Rates rates) {
      return isAudit
          ? Math.min(RESTART_THRESHOLD,
              AUDIT_BAR_FRACTION * Math.max(baseHitRate, rates.noiseBand()))
          : crashBar(rates);
    }

    /**
     * Whether the watched region earns enough for a starvation probe to be judged at the committed
     * depth. The caller passes the starvation ladder's depth unconditionally, since an audit
     * returns on the goal metric before this is reached.
     */
    boolean canAdjudicate(Reading r, int commitment) {
      long watched = down ? r.mainHits : r.windowHits;
      return (watched >= (PROBE_EXIT_BAR_MULTIPLE * r.bar)) && (samples >= commitment);
    }

    /** Returns the signal a completed probe is adjudicated by. */
    double verdictSignal(Reading r) {
      if (down) {
        return r.error();
      }
      double baseline = baseProbationDensity
          * ((double) r.requestCount / Math.max(1L, baseRequestCount));
      return Math.log((r.windowDensity + Reading.DENSITY_EPSILON)
          / (baseline + Reading.DENSITY_EPSILON));
    }

    /** Whether a below-bar sample ends the walk now; only a crashed audit's retry is tolerant. */
    boolean shouldCrashAbort(boolean belowBar) {
      boolean tolerant = isAudit && ladder.hasCrashed();
      return belowBar && (!tolerant || (belowBarStreak >= AUDIT_CRASH_PERSISTENCE));
    }

    /**
     * Whether an audit's verdict has come in: the streak, at committed depth, by a walk that also
     * matched or beat its own starting sample at least once.
     */
    boolean isConfirmed() {
      return (samples >= AUDIT_COMMITMENT) && (aboveStreak >= AUDIT_CONFIRM_STREAK) && beatBase;
    }

    /**
     * Whether the density arm's command on this sample opposes the walk, so that a confirmed
     * position is walked home in the same sample rather than kept.
     */
    boolean isReversedBy(Reading r) {
      return (r.steeringError() * direction()) < 0.0;
    }

    /**
     * Whether a starvation walk's confirm is one an audit would have reached: it took the deepest
     * commitment, the goal metric confirms it, and density would walk it home, so keeping it means
     * parking it.
     */
    boolean isAuditGrade(Reading r) {
      return (samples >= Ladder.PROBE_COMMITMENT_DEEP) && isReversedBy(r) && isConfirmed();
    }
  }

  /**
   * A layer's retry ledger: the refractory rung that a completed experiment deepens when it keeps
   * nothing (a failure, a confirm the density arm reverses, or a confirm that only re-finds ground
   * already confirmed and lost), the run of consecutive crash endings after which a crash stops
   * being priced as an exogenous workload shift, and the farthest window the layer's walks have
   * confirmed. The starvation machine and the audit layer own one each, and an ending may only
   * deepen the ledger of the layer that produced it; sharing one lets rate pulses irrelevant to
   * the window drive the other layer to its deepest rung.
   */
  static final class Ladder {
    /** The initial period after a failed probe, in samples. */
    static final int PROBE_BACKOFF_INITIAL = 16;
    /** The longest period between probes after repeated failures. */
    static final int PROBE_BACKOFF_MAX = 64;
    /** The consecutive crash endings at which a probe's crashes stop being priced as exogenous. */
    static final int PROBE_CRASH_ESCALATION = 2;
    /** The walk's stride multiple at the middle refractory rung (one doubling). */
    static final double PROBE_STRIDE_SCALE_MID = 2.0d;
    /** The walk's stride multiple at the deepest refractory rung. */
    static final double PROBE_STRIDE_SCALE_DEEP = 4.0d;
    /** The walk samples committed before the stray exit may fire, at the middle refractory rung. */
    static final int PROBE_COMMITMENT_MID = 2;
    /** The walk samples committed before the stray exit may fire, at the deepest rung. */
    static final int PROBE_COMMITMENT_DEEP = 10;

    boolean farthestDown;
    int crashStreak;
    long farthest;
    int rung;

    Ladder() {
      reset();
    }

    /** Restores the ledger to its opening state, as a resize or a confirmed audit does. */
    void reset() {
      rung = PROBE_BACKOFF_INITIAL;
      crashStreak = 0;
      forget();
    }

    /** Forgets the farthest confirmed window, as a walk that keeps nothing does. */
    void forget() {
      farthest = -1;
    }

    /**
     * Whether a confirm here is at or short of the farthest window a walk in the same direction
     * has already confirmed, so it re-finds ground the machine has since lost.
     */
    boolean isRepeat(boolean down, long window, long band) {
      if ((farthest < 0) || (down != farthestDown)) {
        return false;
      }
      return down ? (window >= (farthest - band)) : (window <= (farthest + band));
    }

    /** Records a confirmed window that lies beyond the farthest, or in the other direction. */
    void remember(boolean down, long window) {
      boolean farther = (farthest < 0) || (down != farthestDown)
          || (down ? (window < farthest) : (window > farthest));
      if (farther) {
        farthestDown = down;
        farthest = window;
      }
    }

    /** Deepens the rung, as a completed experiment that keeps nothing does. */
    void escalate() {
      rung = Math.min(PROBE_BACKOFF_MAX, 2 * rung);
    }

    /** Records a crash ending; the run saturates once it prices like a completed failure. */
    void crash() {
      crashStreak = Math.min(PROBE_CRASH_ESCALATION, crashStreak + 1);
    }

    /** Rewards a kept confirm: the crash run is forgiven and the next arm is nearly free. */
    void reward() {
      crashStreak = 0;
      rung = 1;
    }

    /**
     * Returns the walk samples a probe must take before stray hits may end it. Stray and
     * transferred hits reach the earnings bar where the small window is genuinely correct, so
     * first-round probes exit cheaply; deeper rungs commit the next walk past that stray zone.
     */
    int commitmentDepth() {
      return (rung >= PROBE_BACKOFF_MAX)
          ? PROBE_COMMITMENT_DEEP
          : (rung >= (2 * PROBE_BACKOFF_INITIAL)) ? PROBE_COMMITMENT_MID : 0;
    }

    /**
     * Returns the full-size stride a walk armed from this rung takes, capped at the maximum step.
     * The audit's room rule and the walk must measure the same move, or a direction is admitted
     * whose entry stride clamps at the wall.
     */
    double stride(Reading r) {
      return Math.min(r.maxStep(), strideScale() * r.restartMagnitude());
    }

    /**
     * Returns how much wider than a flat stride this rung's walk strides. Deep rungs bought a
     * committed walk permission to pass the stray zone but not speed, leaving wide stray walls
     * absorbing.
     */
    private double strideScale() {
      return (rung >= PROBE_BACKOFF_MAX)
          ? PROBE_STRIDE_SCALE_DEEP
          : (rung >= (2 * PROBE_BACKOFF_INITIAL)) ? PROBE_STRIDE_SCALE_MID : 1;
    }

    /** Whether this layer's last ending was a crash, which arms its escalated response. */
    boolean hasCrashed() {
      return crashStreak >= 1;
    }

    /** Whether this crash continues a run long enough to price like a completed failure. */
    boolean crashEscalates() {
      return crashStreak >= PROBE_CRASH_ESCALATION;
    }
  }

  /**
   * The audit layer's schedule: how long the window has held still, how much stillness the next
   * audit waits for, and which way that audit will explore. What must be still is the position,
   * never the rate. A wall held by large commands that the transfer geometry discards is exactly an
   * equilibrium worth auditing and a periodic rate swing would otherwise suppress audits forever.
   */
  static final class AuditClock {
    /** The quiet samples at a sighted equilibrium before an audit probe re-tests it. */
    static final int AUDIT_WAIT_INITIAL = 32;
    /**
     * The stillness samples before the first audit after a (re)size; a cold-start calibration
     * probe. A sighted false equilibrium pins from the very first sample, so waiting the standard
     * clock leaves a short trace motionless before the machine may test it at all and every later
     * wait uses the standard clock. A starvation confirm must not touch this schedule or the
     * calibration is spent before it runs. The cost is one early misconfirm window on a steadily
     * rising workload, bounded by the park's shield and the audit that follows it.
     */
    static final int AUDIT_WAIT_FIRST = 4;
    /** The longest wait between audits, reached by doubling on completed deepest-rung failures. */
    static final int AUDIT_WAIT_MAX = 512;

    int stillSamples;
    int waitSamples;
    long lastWindow;
    /** Whether the next audit explores downward first; the audit after it takes the other side. */
    boolean down = true;
    /** The smoothed rate at the last confirm, until the park's first audit has read it. */
    double settledRate;

    AuditClock() {
      reset();
    }

    /**
     * Restores the clock to its opening state, as a resize does. The direction is left standing: it
     * alternates across audits for coverage, and a resize has no opinion about the next one. The
     * last window is negative until one has closed.
     */
    void reset() {
      waitSamples = AUDIT_WAIT_FIRST;
      settledRate = Double.NaN;
      stillSamples = 0;
      lastWindow = -1;
    }

    /** Starts the stillness run over, as an arming audit does. */
    void restart() {
      stillSamples = 0;
    }

    /**
     * Restores the standard wait between audits and points the next audit along the confirmed
     * walk, keeping the rate the walk confirmed at, as a confirmed audit does. A confirm ends a
     * walk on evidence of improvement rather than its exhaustion, so the ground beyond it is the
     * unexplored side.
     */
    void settle(boolean down, double rate) {
      waitSamples = AUDIT_WAIT_INITIAL;
      settledRate = rate;
      this.down = down;
    }

    /**
     * Advances the clock by one sample. A moving sample decays the run rather than zeroing it: a
     * hard reset lets one super-band move per wait suppress audits forever, and a density imbalance
     * of only about two between the regions commands such a step. The first sample of a run is
     * still by construction, as nothing may move the window before that sample closes.
     */
    void tick(long windowMax, long band) {
      boolean samePlace = (lastWindow < 0) || (Math.abs(windowMax - lastWindow) <= band);
      stillSamples = samePlace ? (stillSamples + 1) : Math.max(0, stillSamples - 1);
      lastWindow = windowMax;
    }

    /** Whether the position has been still long enough for the clock to re-test it. */
    boolean isDue() {
      return stillSamples >= waitSamples;
    }

    /**
     * Sets when the next audit may arm, from the audit ladder's rung. An unconfirmed audit retries
     * on that cadence while the rung still deepens. Only a completed failure at the deepest rung
     * doubles the clock. A crash keeps the cadence at any rung, since it is priced as a workload
     * shift and deferring would starve the re-exploration.
     */
    void reschedule(boolean failed, boolean crashed, int rung) {
      waitSamples = (failed && !crashed && (rung >= Ladder.PROBE_BACKOFF_MAX))
          ? Math.min(AUDIT_WAIT_MAX, 2 * Math.max(waitSamples, rung))
          : Math.max(Ladder.PROBE_BACKOFF_INITIAL, rung);
    }

    /**
     * Returns the direction the next audit explores: the side it was pointed at when that has a
     * stride of room, otherwise the other; the audit after it takes the opposite side for
     * coverage. A park's first audit follows the confirmed walk only while the park stands and
     * the smoothed rate has held within a restart threshold since the confirm; a rate that moved
     * that much with the window still says the workload moved, and the walk's direction says
     * nothing about the terrain. A direction with less than one stride of room is refused, since
     * the walk would clamp at the wall and burn its whole budget producing no evidence. The
     * stride is the one the arming ladder's rung will actually take, not the flat restart
     * magnitude.
     */
    boolean chooseDirection(Reading r, double stride, double rate, boolean parked) {
      if (!Double.isNaN(settledRate)) {
        if (!parked || (Math.abs(rate - settledRate) >= RESTART_THRESHOLD)) {
          down = !down;
        }
        settledRate = Double.NaN;
      }
      if (r.windowMax <= (long) (2 * r.floor)) {
        return false;
      } else if (r.windowMax >= r.upperCorner()) {
        return true;
      }
      double room = down ? (r.windowMax - r.floor) : (r.upperCorner() - r.windowMax);
      if (room < stride) {
        down = !down;
      }
      boolean chosen = down;
      down = !chosen;
      return chosen;
    }
  }

  /**
   * The goal metric's memory and its defense: the last operating point the cache is known to have
   * done well at, the park that holds the window there, and the veto that returns it. Density alone
   * cannot supply this since its rest point is a share-matching allocation rather than the hit-rate
   * optimum. The anchor does not steer; it refuses to remain measurably worse than somewhere the
   * cache has already been.
   * <p>
   * A shield lives and dies with the park it protects ({@link #park}/{@link #hold}/{@link #release}
   * are its only writers), a park defends only a planted anchor ({@link #discard} takes the hold
   * and the retest with it), and a return implies its park and the retest that judges it.
   */
  static final class Anchor {
    /** The consecutive shortfall samples that sustain a guard-rail veto. */
    static final int VETO_STREAK = 4;
    /** The samples a veto's return may take before it settles where it stands. */
    static final int VETO_RETURN_BUDGET = 8;
    /** The samples a returned window settles on the anchor before its claim is re-tested. */
    static final int RETEST_SETTLE = 2;

    int shortfallStreak;
    double retestClaim;
    boolean returning;
    int returnLeft;
    int settleLeft;
    int freshLeft;
    boolean held;
    long window;
    double rate;

    Anchor() {
      reset();
    }

    /** Restores the layer to its opening state. */
    void reset() {
      shortfallStreak = 0;
      returnLeft = 0;
      endReturn();
      discard();
    }

    /**
     * Forgets the position. A discarded anchor cannot be defended and has no claim left to prove,
     * so the hold and any pending retest go with it.
     */
    void discard() {
      release();
      endRetest();
      window = -1;
    }

    /** Whether a position is remembered at all. */
    boolean isPlanted() {
      return window >= 0;
    }

    /** Whether the window stands on the anchor, within the band a held equilibrium orbits in. */
    boolean isAt(long windowMax, long band) {
      return isPlanted() && (Math.abs(windowMax - window) <= band);
    }

    /** Whether the window stands measurably off a planted anchor. */
    boolean isAwayFrom(long windowMax, long band) {
      return isPlanted() && !isAt(windowMax, band);
    }

    /**
     * Remembers this position and its claim, as a validated walk or a clear improvement does. A
     * pending retest goes with the move: its claim was frozen for the position the return set out
     * for, and this is no longer that position.
     */
    void plant(long windowMax, double claimed) {
      window = windowMax;
      rate = claimed;
      endRetest();
    }

    /** Re-syncs the claim to the live measurement, so a stale claim decays into reality. */
    void resync(double claimed) {
      rate = claimed;
    }

    /**
     * Follows the live measurement by one sample: re-sync the claim while standing on the anchor,
     * or move the anchor to a measurably better position. Planting waits for a settled sample,
     * since a transient window paired with a rate earned elsewhere is a phantom claim. The re-sync
     * does not wait: the window stands on the anchor, and later on-anchor samples decay a walk's
     * transient blend into reality.
     */
    void track(Reading r, Rates rates, boolean probing) {
      boolean settled = !probing && !returning;
      if (!isPlanted()) {
        if (settled) {
          plant(r.windowMax, rates.smoothed);
        }
      } else if (isAt(r.windowMax, r.band)) {
        resync(rates.smoothed);
      } else if (settled && (rates.smoothed > (rate + rates.vetoMargin()))) {
        plant(r.windowMax, rates.smoothed);
        release();
      }
    }

    /**
     * Holds the window here and shields the hold from crash-scale weather, as an audit's confirm
     * does. Only a confirm arms a shield; it is spent only while the park it protects stands.
     */
    void park(int shield) {
      freshLeft = shield;
      held = true;
    }

    /**
     * Holds the window without arming a shield, as the guard rail's veto does. A rail veto is not a
     * fresh claim about the position, so it neither earns a shield nor spends one.
     */
    void hold() {
      held = true;
    }

    /** Releases the hold and the shield. */
    void release() {
      freshLeft = 0;
      held = false;
    }

    /** Whether a freshly parked confirm is still riding out crash-scale weather. */
    boolean isShielded() {
      return held && (freshLeft > 0);
    }

    /** Spends one sample of the shield. */
    void ageShield() {
      freshLeft--;
    }

    /**
     * Returns whether the guard rail vetoes, which a sustained, noise-cleared shortfall against
     * the anchor's rate does; the veto sends the window back there.
     */
    boolean vetoTriggered(Reading r, Rates rates) {
      if (isAwayFrom(r.windowMax, r.band) && (rates.smoothed < (rate - rates.vetoMargin()))) {
        shortfallStreak++;
        if (shortfallStreak >= VETO_STREAK) {
          shortfallStreak = 0;
          beginReturn();
          return true;
        }
      } else {
        shortfallStreak = 0;
      }
      return false;
    }

    /**
     * Stands the layer down after a crash-scale swing, returning whether the claim was discarded.
     * A claim tested at its own position and found wrong is discarded, but a crash far from the
     * anchor is typically the controller's own retreat crossing a band edge, so the reference
     * survives there. The audit clock is untouched either way: stillness is a property of the
     * position, not of the rate.
     */
    boolean standDown(Reading r) {
      boolean discarded = isAt(r.windowMax, r.band);
      if (discarded) {
        discard();
      }
      release();
      endReturn();
      shortfallStreak = 0;
      return discarded;
    }

    /**
     * Begins a veto's return: the hold is armed, the strides back are budgeted, and the claim the
     * return sets out for is frozen for the retest on arrival.
     */
    void beginReturn() {
      returnLeft = VETO_RETURN_BUDGET;
      settleLeft = RETEST_SETTLE;
      retestClaim = rate;
      returning = true;
      hold();
    }

    /**
     * Returns a capped stride of a veto's return towards the anchor, spending one budgeted sample.
     * The return ends when the stride arrives or the budget runs out, settling where it stands.
     */
    double strideHome(Reading r) {
      returnLeft--;
      double remaining = (window - r.windowMax);
      if ((Math.abs(remaining) <= r.maxStep()) || (returnLeft <= 0)) {
        endReturn();
      }
      return r.cappedStride(remaining);
    }

    /** Ends the return, wherever it reached. */
    void endReturn() {
      returning = false;
    }

    /**
     * Whether a return has ended on the anchor with the claim it set out for still to be judged. A
     * return that settled short of the anchor never reached the position its claim describes.
     */
    boolean isRetestDue(Reading r) {
      if ((retestClaim < 0) || returning) {
        return false;
      } else if (!isAt(r.windowMax, r.band)) {
        endRetest();
        return false;
      }
      return true;
    }

    /**
     * Spends one settle sample and, on the last, returns whether the position fell short of the
     * claim that brought the window here. The claim is the one frozen at the return's start: the
     * on-anchor re-sync decays the live claim into the very shortfall being tested.
     */
    boolean retestFails(Rates rates) {
      if (--settleLeft > 0) {
        return false;
      }
      double claimed = retestClaim;
      endRetest();
      return rates.smoothed < (claimed - rates.vetoMargin());
    }

    /** Ends the retest, judged or abandoned. */
    void endRetest() {
      retestClaim = -1;
      settleLeft = 0;
    }
  }

  /**
   * The goal metric's view of the workload: a smoothed sample hit rate and the smoothed mean
   * absolute deviation around it. The deviation prices a claim against the workload's own scatter
   * rather than a fixed number; the guard rail's shortfall margin and the starvation probe's
   * walk-interior bar are both three deviations wide. The audit's confirming streak is deliberately
   * not priced this way.
   */
  static final class Rates {
    /** The floor on the guard rail's shortfall margin, in absolute hit rate. */
    static final double VETO_MARGIN_MIN = 0.01d;
    /** The smoothing constant for the goal-metric references (~5 sample memory). */
    static final double RATE_SMOOTHING = 0.2d;
    /** The seed for the hit-rate deviation estimate, wide so a cold cache cannot veto early. */
    static final double DEVIATION_SEED = 0.05d;
    /** The shortfall margin as a multiple of the smoothed hit-rate deviation. */
    static final double VETO_MARGIN_SCALE = 3.0d;

    double deviation;
    double smoothed;

    Rates() {
      reset();
    }

    /** Restores the references to their opening state. */
    void reset() {
      smoothed = Double.NaN;
      deviation = DEVIATION_SEED;
    }

    /** Whether no sample has been folded in yet, so there is nothing to smooth towards. */
    boolean isUnseeded() {
      return Double.isNaN(smoothed);
    }

    /** Starts the smoothed rate at this sample. */
    void seed(double hitRate) {
      smoothed = hitRate;
    }

    /** Folds one sample in. The deviation updates against the pre-update mean, as an EMA pair. */
    void update(double hitRate) {
      deviation += RATE_SMOOTHING * (Math.abs(hitRate - smoothed) - deviation);
      smoothed += RATE_SMOOTHING * (hitRate - smoothed);
    }

    /**
     * Returns the width of the workload's own per-sample scatter, as the priced bars measure it.
     * Read live rather than frozen at a walk's arm; see the walk note for why.
     */
    double noiseBand() {
      return VETO_MARGIN_SCALE * deviation;
    }

    /** Returns the guard rail's shortfall margin, floored so a quiet workload still has one. */
    double vetoMargin() {
      return Math.max(VETO_MARGIN_MIN, noiseBand());
    }
  }

  /** How a probe's walk ends. */
  private enum ProbeEnding {
    /** The hit rate collapsed below the probe's start: undo without escalating the ladder. */
    CRASHED,
    /** The walk validated its position: keep it and make probes cheap again. */
    CONFIRMED,
    /** A completed, failed experiment: undo and double the refractory ladder. */
    FAILED,
    /** No ending fired: take the next bold-driver stride. */
    WALKING
  }
}
