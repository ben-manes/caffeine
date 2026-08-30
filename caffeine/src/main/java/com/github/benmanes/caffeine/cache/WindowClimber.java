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
   * W-TinyLFU divides capacity between an admission window, which favors recency, and a main
   * region, where TinyLFU filters new arrivals by frequency. The best split depends on the
   * workload, so the climber adjusts it using the cache's hit statistics.
   *
   * Small caches compare hit rates across samples to choose a direction. Larger caches have enough
   * hits to compare the regions' hit densities (hits per unit of capacity) within a single sample,
   * reducing sensitivity to workload changes between samples.
   *
   * Density alone cannot identify a good split: it measures only resident entries, and a region
   * with few hits provides little information. The density tier uses probes to explore these
   * starved regions and periodic audits to test whether a different split improves the hit rate. An
   * anchor records a well-performing position to return to if later adjustments reduce the hit
   * rate.
   *
   * [1] The Adaptive Window, From the Ground Up
   * https://htmlpreview.github.io/?https://github.com/ben-manes/caffeine/blob/master/wiki/adaptive-window.html
   */

  /** The change in hit rate large enough to trigger adaptation to a new workload. */
  static final double RESTART_THRESHOLD = 0.05d;

  final Sample sample;
  final Step step;

  long adjustment;
  Climber tier;

  /** Creates a climber whose tier is initialized by {@link #resized}. */
  @SuppressWarnings("NullAway.Init")
  public WindowClimber() {
    sample = new Sample();
    step = new Step();
  }

  /** Selects the tier and resets adaptation state for the new maximum size. */
  public void resized(long maximum) {
    tier = DensityClimber.appliesTo(maximum)
        ? new DensityClimber(step)
        : new ReactiveClimber(step);
    step.reset(maximum);
    sample.reset();
    adjustment = 0;
  }

  /** Discards the current sample and the previous hit rate. */
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

  /** Updates the adjustment when a full sample is available, retaining any carry-over otherwise. */
  public void determineAdjustment(long maximum, long windowMaximum,
      long mainProtectedMaximum, int sketchSampleSize) {
    if (sample.requestCount() >= tier.samplePeriod(maximum, sketchSampleSize)) {
      adjustment = tier.climb(sample, maximum, windowMaximum, mainProtectedMaximum);
      sample.close();
    }
  }

  /** A strategy for adjusting the admission window from sampled cache statistics. */
  interface Climber {

    /** Returns the number of requests in an adaptation sample. */
    long samplePeriod(long maximum, int sketchSampleSize);

    /** Returns the amount to adapt the window by. */
    long climb(Sample sample, long maximum, long windowMax, long mainProtectedMax);
  }

  /**
   * A hill climber that adjusts the window according to changes in hit rate. It reverses direction
   * when the hit rate falls and reduces the step size as it converges. Large changes in hit rate
   * restore the initial step size so it can adapt to a new workload.
   * <p>
   * Small caches use this strategy because their per-region hit counts are too low for reliable
   * density estimates. The smallest caches use longer samples and slower step decay to reduce
   * sensitivity to noise.
   */
  static final class ReactiveClimber implements Climber {
    /** The maximum size at which adaptation uses longer samples and slower step decay. */
    static final long SLOW_ADAPT_THRESHOLD = 512L;
    /** Maximum factor by which the sample period may grow in the slow-adapt regime. */
    static final double SLOW_ADAPT_RATIO_CAP = 4.0d;
    /** The slower decay rate used to preserve useful steps in very small caches. */
    static final double SLOW_ADAPT_DECAY_RATE = 0.995d;

    final Step step;

    ReactiveClimber(Step step) {
      this.step = step;
    }

    @Override
    @SuppressWarnings("MathClampDouble")
    public long samplePeriod(long maximum, int sketchSampleSize) {
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

    @Override
    public long climb(Sample sample, long maximum, long windowMax, long mainProtectedMax) {
      double hitRateChange = sample.hitRateChange();
      double amount = step.heading(/* forward= */ hitRateChange >= 0);
      step.commit((Math.abs(hitRateChange) >= RESTART_THRESHOLD)
          ? Math.copySign(Step.restartMagnitude(maximum), amount)
          : (decayRate(maximum) * amount));
      return (long) amount;
    }

    /** Returns whether this maximum falls in the slow-adapt regime. */
    static boolean isSlowAdapting(long maximum) {
      return maximum <= SLOW_ADAPT_THRESHOLD;
    }

    /** Returns the step decay rate. */
    private static double decayRate(long maximum) {
      return isSlowAdapting(maximum) ? SLOW_ADAPT_DECAY_RATE : Step.STEP_DECAY_RATE;
    }
  }

  /**
   * A hill climber that shifts capacity toward the region with more hits per unit of capacity.
   * Comparing regions within the same sample reduces sensitivity to workload changes between
   * samples.
   * <p>
   * Hit density describes only resident entries and can favor a split with a poor overall hit rate.
   * Probes explore regions with too few hits to measure, while audits test whether moving away from
   * the current split improves the hit rate. An anchor records a known good position for recovery
   * after an unproductive adjustment.
   */
  static final class DensityClimber implements Climber {
    /** The cache size threshold between reactive and density feedback. */
    static final long DENSITY_THRESHOLD = 4096L;
    /** A longer sample period stabilize density estimates at the cost of slower adaptation. */
    static final long SAMPLE_MULTIPLIER = 4L;
    /** The step per unit of log density-ratio error, as a fraction of the maximum. */
    static final double DENSITY_GAIN = 0.03d;
    /** The samples covered by a retreat stride, including its arrival sample. */
    static final int RETREAT_COVER = 2;

    final AuditClock auditClock;
    final Ladder starvation;
    final Anchor anchor;
    final Ladder audit;
    final Rates rates;
    final Step step;

    @Nullable Walk walk;

    long undoRemaining;
    int refractoryLeft;
    int retreatLeft;

    DensityClimber(Step step) {
      this.auditClock = new AuditClock();
      this.starvation = new Ladder();
      this.anchor = new Anchor();
      this.audit = new Ladder();
      this.rates = new Rates();
      this.step = step;
    }

    /** Returns whether the cache is large enough to use density feedback. */
    static boolean appliesTo(long maximum) {
      return maximum > DENSITY_THRESHOLD;
    }

    @Override
    public long samplePeriod(long maximum, int sketchSampleSize) {
      @Var long period = SAMPLE_MULTIPLIER * maximum;
      if ((period / SAMPLE_MULTIPLIER) != maximum) {
        period = Long.MAX_VALUE;
      }
      return Math.min(period, sketchSampleSize);
    }

    @Override
    public long climb(Sample sample, long maximum, long windowMax, long mainProtectedMax) {
      double amount = route(new Reading(sample, maximum, windowMax, mainProtectedMax));
      auditClock.tick(windowMax, Reading.stableBand(maximum));
      return (long) amount;
    }

    /** Returns the next adjustment, giving probes and recovery priority over density steering. */
    private double route(Reading reading) {
      ageParkShield();
      ageRetreatCover();

      if (isWorkloadShift(reading) && anchor.standDown(reading)) {
        // Discard the old workload's rate history along with its anchor
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
        // A starvation confirm resumes density steering in the same sample
        return steer(reading.steeringError(), reading);
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
        // Density would move away from this better position. Wait for an audit to explore again
        return 0.0;
      }
      return steer(reading.steeringError(), reading);
    }

    /** Ages the protection against workload shifts while parked, pausing during a walk. */
    private void ageParkShield() {
      if (isShielded()) {
        anchor.ageShield();
      }
    }

    /**
     * Ages the protection against rate changes caused by a retreat. Each stride renews it through
     * the arrival sample.
     */
    private void ageRetreatCover() {
      if (retreatLeft > 0) {
        retreatLeft--;
      }
    }

    /**
     * Returns whether a recently confirmed position is protected from workload shifts. Walks
     * suspend this protection so a shift can still invalidate the position during exploration.
     */
    private boolean isShielded() {
      return (walk == null) && anchor.isShielded();
    }

    /**
     * Returns whether the hit-rate change indicates a new workload. Audits of a parked position
     * and returns can cause large rate changes themselves, so their results are judged separately.
     */
    private boolean isWorkloadShift(Reading reading) {
      return (Math.abs(reading.hitRateChange) >= RESTART_THRESHOLD)
          && !isShielded() && !isParkTest() && !isReturnTest();
    }

    /** Returns whether a retreat or anchor retest is suppressing workload-change detection. */
    private boolean isReturnTest() {
      return (retreatLeft > 0) || ((anchor.retestClaim >= 0) && !anchor.returning);
    }

    /** Returns whether an audit is testing a parked position. */
    private boolean isParkTest() {
      return anchor.held && (walk != null) && walk.isAudit;
    }

    /** Updates the smoothed hit rate and anchor, using the first sample to initialize the rates. */
    private void updateRateReferences(Reading reading) {
      if (rates.isUnseeded()) {
        rates.seed(reading.hitRate);
      } else {
        rates.update(reading.hitRate);
        anchor.track(reading, rates, /* probing= */ isProbing());
      }
    }

    /**
     * Returns whether a probe or its retreat is in progress. A capped retreat may continue across
     * several samples after the walk ends.
     */
    private boolean isProbing() {
      return (walk != null) || hasPendingUndo();
    }

    /* --------------- Exceptional Scenarios --------------- */

    /** Returns whether part of a probe's retreat remains to be applied. */
    private boolean hasPendingUndo() {
      return undoRemaining != 0;
    }

    /**
     * Returns the next stride toward the probe's starting position. Account for the truncated
     * command, since subtracting fractional strides would leave the retreat short of its target.
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
     * Discards the anchor after a return if its old hit rate is no longer achievable. A return can
     * reach an obsolete anchor without a large enough rate change to trigger workload detection.
     */
    private void retestReturn(Reading reading) {
      if (anchor.retestFails(rates) && anchor.standDown(reading)) {
        // Subsequent anchors must use rate measurements from the new workload
        rates.reset();
      }
    }

    /** Returns whether starvation probes are waiting after a retreat. */
    private boolean isBackingOff() {
      return refractoryLeft > 0;
    }

    /**
     * Starts a due audit or holds the window during backoff. Starvation backoff must not delay
     * audits, since a persistently starved window would otherwise remain untested.
     */
    private double holdOrAudit(Reading reading) {
      return auditClock.isDue() ? armEquilibriumAudit(reading) : holdInRefractory(reading);
    }

    /**
     * Holds the window during backoff, except to raise it to the minimum size. Sparse hits can
     * produce an extreme density ratio, so steering on this sample would defeat the backoff.
     * Leave the previous step intact for the next walk.
     */
    private double holdInRefractory(Reading reading) {
      refractoryLeft--;
      return reading.atLeastFloor(0.0);
    }

    /** Starts a starvation probe and returns its first step. */
    private double armStarvationProbe(Reading reading) {
      var armed = armProbe(reading, reading.shouldProbeDown(), /* isAudit= */ false);
      return walkStep(armed, /* entry= */ true, reading);
    }

    /**
     * Starts an audit and returns its first step. Audits explore stable positions where density
     * steering and starvation probes would otherwise make no progress.
     */
    private double armEquilibriumAudit(Reading reading) {
      var armed = armProbe(reading, auditClock.chooseDirection(
          reading, audit.stride(reading), rates.smoothed, anchor.held), /* isAudit= */ true);
      auditClock.restart();
      return walkStep(armed, /* entry= */ true, reading);
    }

    /**
     * Records a confirmed position and returns whether the window should remain there. Audit
     * confirmations suspend density steering, which could undo the improvement. Starvation probes
     * normally resume steering; {@link Walk#isAuditGrade} identifies those with enough evidence
     * to park as well.
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
     * Starts a probe from the current position and its smoothed hit rate. The anchor's rate may
     * describe another position or an old workload, so using it could reject real improvements.
     */
    private Walk armProbe(Reading reading, boolean down, boolean isAudit) {
      var ladder = isAudit ? audit : starvation;
      walk = new Walk(ladder, isAudit, down, reading.windowMax,
          reading.requestCount, reading.hitRate, rates.smoothed, reading.probationDensity);
      return walk;
    }

    /**
     * Returns the next step of the probe. Crossing back through its starting position ends the
     * probe as a failure; continuing would explore in the opposite direction.
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
     * Returns the next probe step. While an audit waits to distinguish a crash from noise, keep
     * its direction and decay the step. Reversing during that wait could cross the starting
     * position and turn a temporary dip into a completed failure.
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
      double hitRateChange = reading.hitRateChange;
      @Var double stride = step.heading(/* forward= */ hitRateChange > -bar);
      stride = (Math.abs(hitRateChange) >= bar)
          ? Math.copySign(magnitude, stride)
          : Step.decayed(stride);
      return Step.isFrozen(stride) ? (walk.direction() * magnitude) : stride;
    }

    /** Checks for a crash, then evaluates the probe's progress. */
    private ProbeEnding probeEnding(Walk walk, Reading reading) {
      boolean belowBar = (reading.hitRate <= (walk.baseHitRate - walk.crashBar(rates)));
      walk.belowBarStreak = belowBar ? (walk.belowBarStreak + 1) : 0;
      if (walk.shouldCrashAbort(belowBar)) {
        // A workload change can resemble probe damage. Retry the first crash without increasing
        // backoff; repeated crashes suggest the probe itself is harmful
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
     * Evaluates an audit by hit rate. Density already favors the original split, so using it to
     * judge the audit would reject the alternatives the audit is meant to test.
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
     * Evaluates a starvation probe by density. A confirmation only resets the retry ladder if it
     * makes new progress that steering will keep. Reversed or repeated confirmations escalate the
     * ladder so later probes can explore beyond the same short excursion.
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
     * Begins the retreat from a failed or aborted walk and updates its retry schedule. Only
     * starvation probes reset the starvation backoff; an audit's retreat leaves an existing wait
     * intact.
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

    /** Returns a proportional density adjustment, respecting the step cap and minimum window. */
    @SuppressWarnings("MathClampDouble")
    double steer(double error, Reading r) {
      double magnitude = Math.min(r.maxStep(), Math.abs(error) * DENSITY_GAIN * r.maximum);
      double stride = (error >= 0) ? magnitude : -magnitude;
      double clamped = ((stride < 0) && ((r.windowMax + stride) < r.floor))
          ? r.flooredDescent()
          : stride;
      return step.commit(r.atLeastFloor(clamped));
    }
  }

  /** The current sample's hit and miss counts, with the previous sample's hit rate. */
  static final class Sample {
    double previousHitRate;
    long probationHits;
    long windowHits;
    long misses;
    long hits;

    /**
     * Records a cache hit. Callers must exclude zero-weight entries, whose hits would inflate
     * density without consuming capacity.
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
     * Records a cache miss, including zero-weight entries. Their misses still represent demand,
     * so excluding their hits can make the sampled hit rate lower than the user-visible rate.
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

    /** Returns the change in hit rate since the previous sample. */
    double hitRateChange() {
      return hitRate() - previousHitRate;
    }

    /** Clears the sample, retaining its hit rate for the next comparison. */
    void close() {
      close(hitRate());
    }

    /** Discards the sample and cross-sample memory. */
    void reset() {
      close(0.0);
    }

    /** Clears the counters and sets the reference hit rate. */
    private void close(double hitRate) {
      previousHitRate = hitRate;
      probationHits = 0;
      windowHits = 0;
      misses = 0;
      hits = 0;
    }
  }

  /** The measurements and region bounds used for one density adjustment. */
  static final class Reading {
    /** The minimum hit count used for steering, as a fraction of the starvation threshold. */
    static final double STEERING_FLOOR_FRACTION = 0.125d;
    /** The band within which two positions count as the same, as a fraction of the maximum. */
    static final double STABLE_BAND_FRACTION = 0.02d;
    /** The density law's lower bound on the window, as a fraction of the maximum. */
    static final double WINDOW_FLOOR_FRACTION = 0.02d;
    /** The density climber's largest single step, as a fraction of the maximum. */
    static final double MAX_STEP_FRACTION = 0.30d;
    /** The smoothing constant that keeps a starved region's density ratio defined. */
    static final double DENSITY_EPSILON = 1e-9d;
    /** The minimum hit count needed to measure a region, even for short samples. */
    static final long MIN_STARVATION_BAR = 4L;
    /** The right-shift of the sample's request count that sets the starvation bar. */
    static final int MIN_SIGNAL_SHIFT = 10;

    final double probationDensity;
    final double hitRateChange;
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

    Reading(Sample sample, long maximum, long windowMax, long mainProtectedMax) {
      this.mainHits = (sample.hits - sample.windowHits);
      this.bar = Math.max(MIN_STARVATION_BAR, sample.requestCount() >> MIN_SIGNAL_SHIFT);
      this.mainDensity = mainHits / (double) Math.max(1L, maximum - windowMax);
      this.windowDensity = sample.windowHits / (double) Math.max(1L, windowMax);
      this.probationDensity = sample.probationHits / (double) Math.max(
          1L, maximum - windowMax - mainProtectedMax);
      this.windowStarved = (sample.windowHits < bar);
      this.hitRateChange = sample.hitRateChange();
      this.floor = WINDOW_FLOOR_FRACTION * maximum;
      this.requestCount = sample.requestCount();
      this.mainStarved = (mainHits < bar);
      this.windowHits = sample.windowHits;
      this.hitRate = sample.hitRate();
      this.band = stableBand(maximum);
      this.windowMax = windowMax;
      this.maximum = maximum;
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

    /** Returns the initial step size for this cache. */
    double restartMagnitude() {
      return Step.restartMagnitude(maximum);
    }

    /** Returns a descent limited by the floor, preserving negative zero to retain its direction. */
    double flooredDescent() {
      return Math.min(-0.0d, floor - windowMax);
    }

    /** Increases the stride if needed to raise an undersized window to the floor. */
    double atLeastFloor(double stride) {
      return (windowMax < floor) ? Math.max(stride, floor - windowMax) : stride;
    }

    /** Returns whether neither region has enough hits for a reliable density estimate. */
    boolean isDeadSample() {
      return windowStarved && mainStarved;
    }

    /**
     * Returns whether sparse hits require a starvation probe. A starved main beside a large
     * window is left to equilibrium audits; probing it could shrink a productive window.
     */
    boolean hasBlindCorner() {
      return isDeadSample() || (windowStarved && (windowMax <= (maximum >>> 2)));
    }

    /** Returns whether the probe should shrink the window to move away from the nearer bound. */
    boolean shouldProbeDown() {
      return isDeadSample() && (windowMax >= (maximum >>> 1));
    }

    /** Returns the log density ratio used by probe verdicts. */
    double error() {
      return Math.log((windowDensity + DENSITY_EPSILON) / (mainDensity + DENSITY_EPSILON));
    }

    /** Returns the log density ratio with minimum hit counts to limit sparse-sample steps. */
    double steeringError() {
      double windowFloor = (STEERING_FLOOR_FRACTION * bar) / Math.max(1L, windowMax);
      double mainFloor = (STEERING_FLOOR_FRACTION * bar) / Math.max(1L, maximum - windowMax);
      return Math.log(Math.max(windowDensity, windowFloor) / Math.max(mainDensity, mainFloor));
    }
  }

  /** The signed adjustment used to continue, reverse, or decay the climber's next step. */
  static final class Step {
    /** Lower bound on the initial step size so that small caches have an opportunity to adapt. */
    static final double MIN_INITIAL_STEP = 2.0d;
    /** The initial step size as a fraction of the cache's maximum size. */
    static final double STEP_PERCENT = 0.0625d;
    /** The multiplier used to reduce the step size toward convergence. */
    static final double STEP_DECAY_RATE = 0.98d;

    double size;

    /**
     * Resets the step for the new maximum. Very small caches start by growing because their
     * initial window contains only a few entries; larger caches start by shrinking.
     */
    void reset(long maximum) {
      double magnitude = restartMagnitude(maximum);
      size = ReactiveClimber.isSlowAdapting(maximum) ? magnitude : -magnitude;
    }

    /** Records and returns the step for this sample. */
    @CanIgnoreReturnValue
    double commit(double step) {
      size = step;
      return step;
    }

    /** Returns the last step, repeated or reversed. */
    double heading(boolean forward) {
      return forward ? size : -size;
    }

    /** Returns the step's size, ignoring direction. */
    double magnitude() {
      return Math.abs(size);
    }

    /** Returns the minimum step size in the current direction. */
    double atMinimum() {
      return Math.copySign(MIN_INITIAL_STEP, size);
    }

    /** Returns the initial step size for the given maximum. */
    static double restartMagnitude(long maximum) {
      return Math.max(STEP_PERCENT * maximum, MIN_INITIAL_STEP);
    }

    /** Returns the step reduced by one decay factor. */
    static double decayed(double step) {
      return STEP_DECAY_RATE * step;
    }

    /**
     * Returns whether the step needs to be restarted. In the density tier, the floor clamp can
     * reduce a step this far, but decay alone cannot do so within a walk's budget.
     */
    static boolean isFrozen(double step) {
      return Math.abs(step) < MIN_INITIAL_STEP;
    }
  }

  /** A bounded sequence of window adjustments used by a starvation probe or equilibrium audit. */
  static final class Walk {

    /*
     * The crash and reversal thresholds test different losses: the drop from the starting hit rate,
     * and the drop since the previous sample. A starvation probe scales both thresholds with the
     * workload's deviation, subject to a floor and cap. The deviation stays live so the walk can
     * tolerate its own transient effects; freezing it at the start aborts useful walks.
     *
     * An audit's crash threshold stays absolute. Widening it with the deviation allows too many
     * audits to confirm and hold positions that density would reject. At low hit rates the
     * threshold is capped at a fraction of the starting rate, since an absolute threshold could
     * exceed the entire rate and never detect a loss.
     *
     * The audit's reversal threshold also accounts for deviation to avoid reversing on ordinary
     * sample noise. The absolute cap prevents excessive tolerance on noisy workloads. A reversal
     * through the starting window counts as a failed walk and increases the retry wait.
     *
     * Starvation probes abort on the first sample below the crash threshold. An audit retry after a
     * crash requires consecutive such samples, allowing it to cross a temporary dip. Extending this
     * duration tolerates brief losses without raising the allowed drop.
     *
     * Audit confirmation requires a streak of raw samples above the starting smoothed rate, after a
     * minimum number of steps. Scaling this margin by deviation, as the guard rail does, would
     * obscure the window's small contribution to the total rate. A false confirmation can be
     * corrected by the next audit; a false veto repeatedly returns to the anchor. The walk must
     * also match its starting raw rate at least once, since that sample may already exceed the
     * smoothed reference. Equality is allowed so a perfect starting rate can still be confirmed.
     *
     * A starvation probe compares densities once the watched region has enough hits. An up-probe
     * uses the initial probation density: growing the window displaces probation entries, while
     * main's average overvalues that capacity by including the protected core. The baseline is
     * frozen because the walk's own demotions inflate live probation density and can prevent any
     * confirmation. A down-probe uses average densities; the window has no comparable subdivision.
     *
     * Every walk has a sample budget. Neither the density verdict nor the hit-rate thresholds
     * guarantee termination, and a walk may continue making small losses below those thresholds.
     */

    /**
     * The scale shared by the audit's crash and reversal thresholds. The crash threshold uses the
     * starting rate; the reversal threshold uses the larger of that rate and the noise band.
     */
    static final double AUDIT_BAR_FRACTION = 0.15d;
    /** The consecutive below-threshold samples required to abort an audit retry after a crash. */
    static final int AUDIT_CRASH_PERSISTENCE = 3;
    /** The consecutive raw samples above the frozen reference that confirm an audit. */
    static final int AUDIT_CONFIRM_STREAK = 4;
    /** The minimum samples before an audit can confirm. */
    static final int AUDIT_COMMITMENT = 5;
    /** The hit count required for a density verdict, as a multiple of the starvation threshold. */
    static final long PROBE_EXIT_BAR_MULTIPLE = 4L;
    /** The starvation probe's maximum crash threshold, in multiples of the restart threshold. */
    static final double PROBE_BAR_CAP = 3.0d;
    /** The maximum samples before an unconfirmed walk fails. */
    static final int PROBE_WALK_BUDGET = 16;

    final double baseProbationDensity;
    final double baseSmoothedRate;
    final long baseRequestCount;
    final double baseHitRate;
    final long baseWindow;

    final boolean isAudit;
    final Ladder ladder;
    final boolean down;

    int belowBarStreak;
    int aboveStreak;
    int samples;

    boolean beatBase;
    double bestRate;
    long bestWindow;

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
     * Records the best position within the current confirmation streak. A broken streak clears the
     * record, and ties favor the later position to avoid unnecessary backtracking.
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
     * Returns the confirmed position. An audit may have passed a better position while collecting
     * its confirmation streak, so it returns there if the difference exceeds the confirmation
     * margin. A starvation probe evaluates the current sample and keeps its position.
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

    /** Returns whether the walk has exhausted its sample budget. */
    boolean isBudgetSpent() {
      return samples >= PROBE_WALK_BUDGET;
    }

    /** Returns whether the proposed position crosses back through the starting window. */
    boolean crossesBase(double position) {
      return down ? (position > baseWindow) : (position < baseWindow);
    }

    /** Returns the drop from the starting hit rate that makes a sample count as a crash. */
    @SuppressWarnings("MathClampDouble")
    double crashBar(Rates rates) {
      return isAudit
          ? Math.min(RESTART_THRESHOLD, AUDIT_BAR_FRACTION * baseHitRate)
          : Math.min(PROBE_BAR_CAP * RESTART_THRESHOLD,
              Math.max(RESTART_THRESHOLD, rates.noiseBand()));
    }

    /** Returns the drop since the previous sample that reverses the walk's step. */
    double reversalBar(Rates rates) {
      return isAudit
          ? Math.min(RESTART_THRESHOLD,
              AUDIT_BAR_FRACTION * Math.max(baseHitRate, rates.noiseBand()))
          : crashBar(rates);
    }

    /**
     * Returns whether a starvation probe has enough hits and has reached the required depth for
     * a density verdict.
     */
    boolean canAdjudicate(Reading r, int commitment) {
      long watched = down ? r.mainHits : r.windowHits;
      return (watched >= (PROBE_EXIT_BAR_MULTIPLE * r.bar)) && (samples >= commitment);
    }

    /** Returns the density comparison, scaling the frozen baseline to the current sample length. */
    double verdictSignal(Reading r) {
      if (down) {
        return r.error();
      }
      double baseline = baseProbationDensity
          * ((double) r.requestCount / Math.max(1L, baseRequestCount));
      return Math.log((r.windowDensity + Reading.DENSITY_EPSILON)
          / (baseline + Reading.DENSITY_EPSILON));
    }

    /** Returns whether to abort on this loss, allowing an audit retry to tolerate brief dips. */
    boolean shouldCrashAbort(boolean belowBar) {
      boolean tolerant = isAudit && ladder.hasCrashed();
      return belowBar && (!tolerant || (belowBarStreak >= AUDIT_CRASH_PERSISTENCE));
    }

    /** Returns whether the walk meets the audit's depth, streak, and starting-rate tests. */
    boolean isConfirmed() {
      return (samples >= AUDIT_COMMITMENT) && (aboveStreak >= AUDIT_CONFIRM_STREAK) && beatBase;
    }

    /** Returns whether density steering would immediately move back from the confirmed position. */
    boolean isReversedBy(Reading r) {
      return (r.steeringError() * direction()) < 0.0;
    }

    /**
     * Returns whether a deep starvation walk meets the audit's confirmation tests but would be
     * reversed by density steering. This position must be held to retain the improvement.
     */
    boolean isAuditGrade(Reading r) {
      return (samples >= Ladder.PROBE_COMMITMENT_DEEP) && isReversedBy(r) && isConfirmed();
    }
  }

  /**
   * Retry state for starvation probes or audits. Repeated unsuccessful walks increase both the wait
   * and the extent of exploration. Each layer has its own ladder so failures in one do not delay
   * exploration by the other.
   */
  static final class Ladder {
    /** The stride multiplier after one backoff doubling. */
    static final double PROBE_STRIDE_SCALE_MID = 2.0d;
    /** The stride multiplier at the maximum backoff. */
    static final double PROBE_STRIDE_SCALE_DEEP = 4.0d;
    /** The minimum samples before a density verdict after one backoff doubling. */
    static final int PROBE_COMMITMENT_MID = 2;
    /** The minimum samples before a density verdict at the maximum backoff. */
    static final int PROBE_COMMITMENT_DEEP = 10;
    /** The consecutive crashes before a retry escalates as it would after a failed walk. */
    static final int PROBE_CRASH_ESCALATION = 2;
    /** The initial backoff, in samples, before escalation. */
    static final int PROBE_BACKOFF_INITIAL = 16;
    /** The maximum backoff rung, in samples. */
    static final int PROBE_BACKOFF_MAX = 64;

    boolean farthestDown;
    int crashStreak;
    long farthest;
    int rung;

    Ladder() {
      reset();
    }

    /** Restores the initial backoff and clears the walk history. */
    void reset() {
      rung = PROBE_BACKOFF_INITIAL;
      crashStreak = 0;
      forget();
    }

    /** Clears the farthest confirmed position. */
    void forget() {
      farthest = -1;
    }

    /**
     * Returns whether this confirmation makes no further progress than an earlier walk in the same
     * direction. Repeatedly recovering a lost position should not reset the backoff.
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

    /** Doubles the backoff, up to its maximum. */
    void escalate() {
      rung = Math.min(PROBE_BACKOFF_MAX, 2 * rung);
    }

    /** Records a consecutive crash, saturating at the escalation threshold. */
    void crash() {
      crashStreak = Math.min(PROBE_CRASH_ESCALATION, crashStreak + 1);
    }

    /** Resets the crash streak and shortens the next retry after a successful walk. */
    void reward() {
      crashStreak = 0;
      rung = 1;
    }

    /**
     * Returns the minimum samples before a density verdict. Early retries can stop cheaply, while
     * deeper retries continue past incidental hits that would otherwise end exploration too soon.
     */
    int commitmentDepth() {
      return (rung >= PROBE_BACKOFF_MAX)
          ? PROBE_COMMITMENT_DEEP
          : (rung >= (2 * PROBE_BACKOFF_INITIAL)) ? PROBE_COMMITMENT_MID : 0;
    }

    /**
     * Returns the starting stride for this rung. The audit's direction check uses this same
     * distance so it does not choose a direction with too little room for the first step.
     */
    double stride(Reading r) {
      return Math.min(r.maxStep(), strideScale() * r.restartMagnitude());
    }

    /**
     * Returns the stride multiplier. Deeper retries need larger steps as well as more samples to
     * reach beyond broad regions of incidental hits.
     */
    private double strideScale() {
      return (rung >= PROBE_BACKOFF_MAX)
          ? PROBE_STRIDE_SCALE_DEEP
          : (rung >= (2 * PROBE_BACKOFF_INITIAL)) ? PROBE_STRIDE_SCALE_MID : 1;
    }

    /** Returns whether this layer's last walk crashed. */
    boolean hasCrashed() {
      return crashStreak >= 1;
    }

    /** Returns whether consecutive crashes require the same backoff as a failed walk. */
    boolean crashEscalates() {
      return crashStreak >= PROBE_CRASH_ESCALATION;
    }
  }

  /**
   * The timing and direction of equilibrium audits. Stillness is measured by the window's actual
   * position, since changing hit rates or adjustments blocked by a boundary can conceal a stable
   * allocation that needs testing.
   */
  static final class AuditClock {
    /** The stillness samples before a routine equilibrium audit. */
    static final int AUDIT_WAIT_INITIAL = 32;
    /** The shorter wait for the first audit after a resize. */
    static final int AUDIT_WAIT_FIRST = 4;
    /** The longest wait between audits. */
    static final int AUDIT_WAIT_MAX = 512;

    double settledRate;
    int stillSamples;
    int waitSamples;
    long lastWindow;
    boolean down;

    AuditClock() {
      down = true;
      reset();
    }

    /** Restores the initial schedule without changing the preferred direction. */
    void reset() {
      waitSamples = AUDIT_WAIT_FIRST;
      settledRate = Double.NaN;
      stillSamples = 0;
      lastWindow = -1;
    }

    /** Restarts the stillness count when an audit begins. */
    void restart() {
      stillSamples = 0;
    }

    /**
     * Restores the standard wait and continues in the confirmed walk's direction. Confirmation
     * stopped the walk while it was improving, so the next audit tests for further improvement.
     */
    void settle(boolean down, double rate) {
      waitSamples = AUDIT_WAIT_INITIAL;
      settledRate = rate;
      this.down = down;
    }

    /**
     * Updates the stillness count. A moving sample decrements the count rather than resetting it,
     * so occasional movement cannot suppress audits indefinitely. The first sample counts as still
     * because no adjustment precedes it.
     */
    void tick(long windowMax, long band) {
      boolean samePlace = (lastWindow < 0) || (Math.abs(windowMax - lastWindow) <= band);
      stillSamples = samePlace ? (stillSamples + 1) : Math.max(0, stillSamples - 1);
      lastWindow = windowMax;
    }

    /** Returns whether the position has been still long enough for an audit. */
    boolean isDue() {
      return stillSamples >= waitSamples;
    }

    /**
     * Sets the next wait from the audit's backoff. A completed failure at the deepest rung doubles
     * the wait. A crash uses the rung directly, since a workload shift calls for another audit
     * without that extra delay.
     */
    void reschedule(boolean failed, boolean crashed, int rung) {
      waitSamples = (failed && !crashed && (rung >= Ladder.PROBE_BACKOFF_MAX))
          ? Math.min(AUDIT_WAIT_MAX, 2 * Math.max(waitSamples, rung))
          : Math.max(Ladder.PROBE_BACKOFF_INITIAL, rung);
    }

    /**
     * Returns an audit direction with room for the first stride. The first audit after a
     * confirmation continues the walk only while the position is held and its smoothed rate remains
     * close to the confirmed rate. Otherwise, exploration alternates directions. A corner-forced
     * direction leaves the alternation unchanged to avoid retracing it later.
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
   * A reference position and hit rate used to reject sustained regressions. It can hold a confirmed
   * position or return to it after a loss. This supplements density steering, whose equilibrium
   * need not maximize the hit rate.
   */
  static final class Anchor {

    /*
     * A confirmed position has a grace period for large hit-rate changes. That period belongs to
     * its hold and ends when the hold is released. A veto also holds the window, but does not renew
     * the grace period because returning to an old position is not new evidence for it.
     *
     * A return rechecks the reference rate on arrival. The retest freezes the rate at departure and
     * is valid only for that position; discarding or replacing the anchor cancels it.
     */

    /** The maximum samples allowed for a return to the anchor. */
    static final int VETO_RETURN_BUDGET = 8;
    /** The settling samples at the anchor before its reference rate is retested. */
    static final int RETEST_SETTLE = 2;
    /** The consecutive shortfall samples that sustain a guard-rail veto. */
    static final int VETO_STREAK = 4;

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

    /** Clears the reference position and any pending return. */
    void reset() {
      shortfallStreak = 0;
      returnLeft = 0;
      endReturn();
      discard();
    }

    /** Discards the reference position, releasing its hold and cancelling any pending retest. */
    void discard() {
      release();
      endRetest();
      window = -1;
    }

    /** Returns whether a reference position has been recorded. */
    boolean isPlanted() {
      return window >= 0;
    }

    /** Returns whether the window is within the tolerance band of the anchor. */
    boolean isAt(long windowMax, long band) {
      return isPlanted() && (Math.abs(windowMax - window) <= band);
    }

    /** Returns whether the window is outside the tolerance band of an existing anchor. */
    boolean isAwayFrom(long windowMax, long band) {
      return isPlanted() && !isAt(windowMax, band);
    }

    /**
     * Records a position and its reference rate. Any pending retest is cancelled because its frozen
     * rate belongs to the previous anchor, even if the new anchor is at the current window.
     */
    void plant(long windowMax, double claimed) {
      window = windowMax;
      rate = claimed;
      endRetest();
    }

    /** Refreshes the reference rate from a measurement at the anchor. */
    void resync(double claimed) {
      rate = claimed;
    }

    /**
     * Refreshes the rate at the anchor or records a better position. A new position must be settled
     * so its reference rate does not come from a walk or return still in progress. Refreshing at
     * the anchor is always allowed; later samples gradually remove any transient measurements.
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

    /** Starts a hold with a grace period for large hit-rate changes after confirmation. */
    void park(int shield) {
      freshLeft = shield;
      held = true;
    }

    /** Starts a guard-rail hold without changing the remaining grace period. */
    void hold() {
      held = true;
    }

    /** Releases the hold and ends its grace period. */
    void release() {
      freshLeft = 0;
      held = false;
    }

    /** Returns whether a confirmed position is still held within its grace period. */
    boolean isShielded() {
      return held && (freshLeft > 0);
    }

    /** Consumes one sample of the grace period. */
    void ageShield() {
      freshLeft--;
    }

    /** Begins a return and reports a veto after a sustained shortfall exceeds the noise margin. */
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
     * Releases the hold and stops any return, reporting whether the anchor was discarded. Only
     * a change at the anchor invalidates its rate; a change elsewhere may result from the
     * controller's own movement.
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

    /** Begins a bounded return to the anchor and freezes its rate for the retest on arrival. */
    void beginReturn() {
      returnLeft = VETO_RETURN_BUDGET;
      settleLeft = RETEST_SETTLE;
      retestClaim = rate;
      returning = true;
      hold();
    }

    /**
     * Returns a capped step toward the anchor. The return ends when the step reaches the anchor or
     * uses the last budgeted sample.
     */
    double strideHome(Reading r) {
      returnLeft--;
      double remaining = (window - r.windowMax);
      if ((Math.abs(remaining) <= r.maxStep()) || (returnLeft <= 0)) {
        endReturn();
      }
      return r.cappedStride(remaining);
    }

    /** Ends the return, including one that exhausted its budget before arrival. */
    void endReturn() {
      returning = false;
    }

    /**
     * Returns whether the return has ended at the anchor with a retest pending. An incomplete
     * return cannot test the reference rate and cancels the retest.
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
     * Returns whether the settled rate falls short of the reference frozen at departure. Using the
     * live reference would hide the shortfall as measurements at the anchor refresh it.
     */
    boolean retestFails(Rates rates) {
      if (--settleLeft > 0) {
        return false;
      }
      double claimed = retestClaim;
      endRetest();
      return rates.smoothed < (claimed - rates.vetoMargin());
    }

    /** Clears a completed or cancelled retest. */
    void endRetest() {
      retestClaim = -1;
      settleLeft = 0;
    }
  }

  /**
   * A smoothed hit rate and mean absolute deviation. The deviation determines the noise margin for
   * guard-rail vetoes and starvation probes; audit confirmation uses a fixed margin instead.
   */
  static final class Rates {
    /** The shortfall margin as a multiple of the smoothed hit-rate deviation. */
    static final double VETO_MARGIN_SCALE = 3.0d;
    /** The floor on the guard rail's shortfall margin, in absolute hit rate. */
    static final double VETO_MARGIN_MIN = 0.01d;
    /** The initial deviation estimate, allowing for cold-start variability. */
    static final double DEVIATION_SEED = 0.05d;
    /** The smoothing constant for the goal-metric references (~5 sample memory). */
    static final double RATE_SMOOTHING = 0.2d;

    double deviation;
    double smoothed;

    Rates() {
      reset();
    }

    /** Clears the smoothed rate and restores the initial deviation. */
    void reset() {
      smoothed = Double.NaN;
      deviation = DEVIATION_SEED;
    }

    /** Returns whether the initial hit rate has yet to be recorded. */
    boolean isUnseeded() {
      return Double.isNaN(smoothed);
    }

    /** Starts the smoothed rate at this sample. */
    void seed(double hitRate) {
      smoothed = hitRate;
    }

    /** Updates both estimates, measuring deviation from the previous smoothed rate. */
    void update(double hitRate) {
      deviation += RATE_SMOOTHING * (Math.abs(hitRate - smoothed) - deviation);
      smoothed += RATE_SMOOTHING * (hitRate - smoothed);
    }

    /**
     * Returns a noise margin of three current deviations. Walks use the live estimate to tolerate
     * their own transient effects; see the implementation notes in {@link Walk}.
     */
    double noiseBand() {
      return VETO_MARGIN_SCALE * deviation;
    }

    /** Returns the guard rail's noise margin, with a minimum for quiet workloads. */
    double vetoMargin() {
      return Math.max(VETO_MARGIN_MIN, noiseBand());
    }
  }

  /** The outcome of a walk sample. */
  private enum ProbeEnding {
    /** The walk continues with another step. */
    WALKING,
    /** The walk's confirmation criteria were met. */
    CONFIRMED,
    /** A hit-rate loss requires returning to the starting position. */
    CRASHED,
    /** The walk failed to confirm an improvement and requires a longer backoff. */
    FAILED,
  }
}
