#!/usr/bin/env python3
"""Wire the adversarial-round instrumentation into a worktree, by anchored snippet.

This replaces `experiment-harness.patch`. A unified diff encodes *the source it was cut from*:
it carries javadoc as context, so an unrelated comment edit breaks it, and both failure modes are
silent — `git apply` refuses every hunk for that file, leaving a build with no instrumentation
that still prints plausible numbers, while a fuzzy `patch` lands the rest and reverts whatever the
stale context encodes. That happened three times on 2026-08-04, twice reverting a landed fix
inside `densityClimb` and once leaving an ablation arm that measured the shipped machine.

A snippet encodes *where the instrumentation attaches*. Every anchor below is **code**, never a
comment, so doc edits cannot move it; a missing anchor names itself and stops. Applying is
idempotent, so re-running after a rebase is safe.

    harness.py apply  <worktree>     # wire it in (idempotent)
    harness.py verify <worktree>     # assert every edit is present
    harness.py strip  <worktree>     # remove it again

After EDITING an existing edit's replacement text, reset the worktree's touched files before
re-applying: the idempotency test looks for the new replacement, misses the old one still wired
in the tree, and injects a duplicate (a doubled FLAGS block fails compilation on every flag).
NEVER commit the result: it prints to stderr and carries variant knobs, and ErrorProne flags the
`SystemOut` by design.
"""
import sys, os

W = "caffeine/src/main/java/com/github/benmanes/caffeine/cache/WindowClimber.java"
B = "caffeine/src/main/java/com/github/benmanes/caffeine/cache/BoundedLocalCache.java"
P = ("simulator/src/main/java/com/github/benmanes/caffeine/cache/simulator"
     "/policy/sketch/WindowTinyLfuPolicy.java")

MARGINAL_FIELDS = '''  // MEASUREMENT HARNESS (worktree-only): region-attributed hits plus the window's LRU-tail
  // band, so the average-value and marginal-value steering errors can both be evaluated at a
  // STATIC window. The band is the delta*W nodes nearest the eviction end, maintained by a
  // midpoint marker in O(1) amortized (MySQL/InnoDB's trick).
  static final boolean MEASURE = Boolean.getBoolean("caffeine.marginal.measure");
  static final double TAIL_FRACTION =
      Double.parseDouble(System.getProperty("caffeine.marginal.tail", "0.2"));
  private @Nullable Node windowMark;
  private long windowMarked;
  private long windowNodes;
  private long hitsWindow;
  private long hitsWindowTail;
  private long hitsProbation;
  private long hitsProtected;

'''

MARGINAL_HELPERS = '''  /** Drops the node out of the marginal band, carrying the marker back when it was the marker. */
  private void leaveWindowTail(Node node) {
    if (!node.inWindowTail) {
      return;
    }
    if (windowMark == node) {
      windowMark = (windowMarked > 1) ? node.prev : null;
    }
    node.inWindowTail = false;
    windowMarked--;
  }

  /** Restores the marginal band to the delta*W nodes nearest the eviction end. */
  private void rebalanceWindowTail() {
    var target = (long) Math.ceil(TAIL_FRACTION * windowNodes);
    while (windowMarked < target) {
      Node next = (windowMark == null) ? headWindow.next : windowMark.next;
      if ((next == null) || (next == headWindow)) {
        break;
      }
      next.inWindowTail = true;
      windowMark = next;
      windowMarked++;
    }
    while ((windowMarked > target) && (windowMark != null)) {
      Node prev = windowMark.prev;
      windowMark.inWindowTail = false;
      windowMarked--;
      windowMark = (windowMarked == 0) ? null : prev;
    }
  }
'''

FLAGS = '''
  // EXPERIMENT HARNESS (worktree-only, wired in by the climber-gate skill's harness.py, never
  // committed): a per-sample stderr trace plus system-property variants for A/B ablation.
  static final boolean DEBUG = Boolean.getBoolean("caffeine.climber.debug");
  static final String VARIANT = System.getProperty("caffeine.climber.variant", "hybrid");
  /** reactive: force the hit-rate tier at every size. */
  static final boolean REACTIVE_TIER = VARIANT.equals("reactive");
  /** density: force the density tier at every size (the reactive arm's mirror). */
  static final boolean DENSITY_TIER = VARIANT.equals("density");
  /** react4x: the hit-rate law on the density tier's shorter sample period. */
  static final boolean REACT_4X = VARIANT.equals("react4x");
  /** dens10x: the density law on the hit-rate tier's longer sample period. */
  static final boolean DENS_10X = VARIANT.equals("dens10x");
  /** noaudit: the density tier with the equilibrium-audit layer removed. */
  static final boolean AUDITS = !VARIANT.equals("noaudit");
  /** nocal: the audit layer without the cold-start calibration probe. */
  static final int WAIT_FIRST = VARIANT.equals("nocal")
      ? AuditClock.AUDIT_WAIT_INITIAL : AuditClock.AUDIT_WAIT_FIRST;
  /** hardreset: the pre-round-3 clock, where a moving sample zeroes the stillness run. */
  static final boolean HARDRESET = VARIANT.equals("hardreset") || VARIANT.equals("prefix");
  /** starvwrite: the pre-round-3 schedule, where a starvation confirm writes the audit wait. */
  static final boolean STARVWRITE = VARIANT.equals("starvwrite") || VARIANT.equals("prefix");
  /** absprobe: the pre-adv3 absolute walk-interior bar for starvation probes. */
  static final boolean ABSPROBE = VARIANT.equals("absprobe");
  /** precrash: the pre-crash-semantics machine, with one shared ladder and no crash tolerance. */
  static final boolean PRECRASH = VARIANT.equals("precrash");
  /** flatroom: the pre-2026-08-03 room rule, measuring the flat magnitude instead of the walk's. */
  static final boolean FLATROOM = VARIANT.equals("flatroom");
  /** parkbound: an audit's park ends with its shield instead of holding until the next audit. */
  static final boolean PARKBOUND = VARIANT.equals("parkbound");
  /**
   * staleclaim: the pre-2026-08-03 stand-down, which left the goal metric smoothing across a
   * regime shift, so the claim re-planted from it was mostly the old regime's rate.
   */
  static final boolean STALECLAIM = VARIANT.equals("staleclaim");
  /**
   * noreturncover: a veto return's landing and settle samples are judged for a workload shift
   * ahead of the retest, as before the return cover landed (the census C2 arm's inverse).
   */
  static final boolean NORETURNCOVER = VARIANT.equals("noreturncover");
  /**
   * nowidecover: the retreat's cover is scoped to a held park, as it was inside `isParkTest`
   * before the return cover landed, so `noreturncover`'s price splits into the return half and
   * the widening's.
   */
  static final boolean NOWIDECOVER = VARIANT.equals("nowidecover");
  /** pricedshift: the stand-down's trigger priced off the rate's scatter, clamped to [1x, 3x]. */
  static final boolean PRICEDSHIFT = VARIANT.equals("pricedshift");
  /*
   * The §8 item 4 latency-face candidate: a discarding stand-down restarts the audit schedule.
   * One site (the detected shift in densityClimb; rearmboth adds the retest's discard), gated
   * and shaped by the arm.
   */
  /** rearm: the clock's wait drops to the calibration wait; stillness and ladder untouched. */
  static final boolean REARM = VARIANT.startsWith("rearm");
  /** rearmheld / rearmheld2 / rearmheldcold: only where the discarded anchor was a held park. */
  static final boolean REARMHELD = VARIANT.equals("rearmheld") || VARIANT.equals("rearmheld2")
      || VARIANT.equals("rearmheldcold");
  /** rearmreset / rearmboth / rearmheld: the clock restarts as a resize does (auditClock.reset). */
  static final boolean REARMRESET = VARIANT.equals("rearmreset") || VARIANT.equals("rearmboth")
      || VARIANT.equals("rearmheld");
  /** rearmstill / rearmheld2: the wait drops and the stillness run starts over, nothing else. */
  static final boolean REARMSTILL = VARIANT.equals("rearmstill") || VARIANT.equals("rearmheld2");
  /** rearmcold and every reset form: the audit ladder restarts at its first rung as well. */
  static final boolean REARMCOLD = REARM && !VARIANT.equals("rearm") && !VARIANT.equals("rearmstill");
  /** rearmboth: the retest's discard restarts the schedule as the detected shift's does. */
  static final boolean REARMBOTH = VARIANT.equals("rearmboth");
  /*
   * MECHANISM ABLATIONS (the /climber-minimize set). The arms above restore an older machine to
   * price a landed change; these remove one algorithmic step from the current one to price the
   * step itself. Each is a single disable at the step's own site, so an arm that reads
   * bit-identical to ship across the battery is a step that never changes an outcome.
   */
  /**
   * cornerprobe: restores the upper-corner starvation probe deleted 2026-08-21, so the deletion
   * stays priceable. Dead samples arm probes in both forms.
   */
  static final boolean CORNERPROBE = VARIANT.equals("cornerprobe");
  /** nostarve: no blind corner ever arms a starvation probe. */
  static final boolean NOSTARVE = VARIANT.equals("nostarve");
  /** noladder: a completed experiment never deepens its rung, so every retry is first-round. */
  static final boolean NOLADDER = VARIANT.equals("noladder");
  /** noscale: deep rungs walk at the flat stride instead of 2x/4x. */
  static final boolean NOSCALE = VARIANT.equals("noscale");
  /** nocommit: the stray exit may fire at any depth, so deep rungs buy no committed walk. */
  static final boolean NOCOMMIT = VARIANT.equals("nocommit");
  /** norepeat: a confirm that only re-finds ground already confirmed is rewarded, not escalated. */
  static final boolean NOREPEAT = VARIANT.equals("norepeat");
  /** nowedge: a confirm the density arm reverses is rewarded, as it was before 2026-08-16. */
  static final boolean NOWEDGE = VARIANT.equals("nowedge");
  /** nofollow: a park's first audit always alternates instead of following the confirmed walk. */
  static final boolean NOFOLLOW = VARIANT.equals("nofollow");
  /** noshield: a fresh park is never shielded from crash-scale weather. */
  static final boolean NOSHIELD = VARIANT.equals("noshield");
  /** noveto: the guard rail never returns the window to the anchor. */
  static final boolean NOVETO = VARIANT.equals("noveto");
  /** noretest: a return's arrival never re-tests the claim that sent it, as before 2026-08-19. */
  static final boolean NORETEST = VARIANT.equals("noretest");
  /** nofreeze: an up-probe is judged against live probation, not the density frozen at the arm. */
  static final boolean NOFREEZE = VARIANT.equals("nofreeze");
  /*
   * FIRING COUNTS. `-Dcaffeine.climber.counts` dumps, at exit, how often each step's own site
   * was reached and true under whatever arm is running. This is what separates a step that is
   * DEAD (its branch never executes, so removing it cannot change behavior) from one that is
   * merely INERT on the cells measured. Only the first is safe to delete on evidence alone.
   */
  static final boolean COUNTS = Boolean.getBoolean("caffeine.climber.counts");
  static final String[] STEP_NAMES = {"corner", "starve", "ladder", "scale", "commit",
      "repeat", "wedge", "follow", "shield", "veto", "freeze", "retest"};
  static final int CORNER = 0, STARVE = 1, LADDER = 2, SCALE = 3, COMMIT = 4, REPEAT = 5,
      WEDGE = 6, FOLLOW = 7, SHIELD = 8, VETO = 9, FREEZE = 10, RETEST = 11;
  static final java.util.concurrent.atomic.AtomicLongArray FIRED =
      new java.util.concurrent.atomic.AtomicLongArray(STEP_NAMES.length);

  static {
    if (COUNTS) {
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        var sb = new StringBuilder("STEPFIRE");
        for (int i = 0; i < STEP_NAMES.length; i++) {
          sb.append(' ').append(STEP_NAMES[i]).append('=').append(FIRED.get(i));
        }
        System.err.println(sb);
      }));
    }
  }

  /** Records that a step's site was reached and true; returns true so it can sit in a condition. */
  @CanIgnoreReturnValue
  static boolean fired(int step) {
    FIRED.incrementAndGet(step);
    return true;
  }

  /**
   * auditbar: the audit's crash-bar fraction of the rate frozen at arm; 0 restores the pre-fix
   * absolute bar, where nothing floors the level test.
   */
  static final double AUDIT_BAR = Double.parseDouble(
      System.getProperty("caffeine.climber.auditbar", Double.toString(Walk.AUDIT_BAR_FRACTION)));
  String dbgMode = "";
  long dbgSample;

  /** Whether the density tier applies, honoring the harness tier override. */
  static boolean isDense(long maximum) {
    return !REACTIVE_TIER && !REACT_4X
        && (DENSITY_TIER || DENS_10X || DensityClimber.appliesTo(maximum));
  }

  /**
   * Whether the density tier's sample period applies. The shipped gate moves the law and the
   * cadence together, so neither can be attributed without splitting them; react4x/dens10x are
   * the two cross arms of that 2x2.
   */
  static boolean isDensePeriod(long maximum) {
    return REACT_4X || (!DENS_10X && isDense(maximum));
  }
'''

TRACE = '''    if (DEBUG && dense) {
      long wh = sample.windowHits;
      System.err.printf("climb max=%d win=%d hr=%.4f s=%d mode=%s adj=%d wh=%d mh=%d ph=%d"
          + " stable=%d auditWait=%d rung=%d left=%d arung=%d acs=%d pcs=%d undo=%d"
          + " anchorW=%d anchorR=%.4f ema=%.4f dev=%.4f hold=%d fresh=%d shortfall=%d ret=%d"
          + " auditbar=%.4f wbase=%.4f wbar=%.4f%n",
          maximum, windowMaximum, hitRate, dbgSample++, dbgMode, adjustment, wh,
          sample.hits - wh, sample.probationHits, auditClock.stillSamples,
          auditClock.waitSamples, starvation.rung, refractoryLeft, audit.rung,
          audit.crashStreak, starvation.crashStreak, undoRemaining, anchor.window, anchor.rate,
          rates.smoothed, rates.deviation, anchor.held ? 1 : 0, anchor.freshLeft,
          anchor.shortfallStreak, anchor.returning ? 1 : 0, AUDIT_BAR,
          (walk == null) ? -1.0 : walk.baseHitRate,
          (walk == null) ? -1.0 : walk.reversalBar(rates));
      dbgMode = "-";
    }
'''

SEED = '''  /** EXPERIMENT HARNESS: a seeded admission tiebreak, or null for the shipped TLR draw. */
  static final java.util.@Nullable Random ADMIT_RANDOM =
      (System.getProperty("caffeine.climber.seed") == null) ? null
          : new java.util.Random(Long.getLong("caffeine.climber.seed", 0L));
'''

STARTWIN = '''
  /**
   * EXPERIMENT HARNESS: the window's initial share of the maximum, or a negative value for the
   * shipped 1%. It plants the window at a hostile position so the climber's recovery to a
   * frequency-optimal window can be measured on a trace whose optimum the default start already
   * sits at.
   */
  static final double START_WINDOW =
      Double.parseDouble(System.getProperty("caffeine.climber.startwin", "-1"));
  /**
   * Whether the plant re-splits main 80/20, as a `setMaximum` resize does. The default holds
   * probation at its shipped capacity, which is the geometry the climber itself produces
   * (`increaseWindow` conserves window and protected), so the cell differs from a default start by
   * window position alone.
   */
  static final boolean START_RESPLIT =
      System.getProperty("caffeine.climber.startsplit", "climber").equals("resize");
'''

# (name, file, anchor, replacement) — every anchor is CODE, so javadoc edits cannot break it.
EDITS = [
    ("flags", W,
     "  static final double RESTART_THRESHOLD = 0.05d;\n",
     "  static final double RESTART_THRESHOLD = 0.05d;\n" + FLAGS),

    ("tier-override", W,
     "    boolean dense = DensityClimber.appliesTo(maximum);\n",
     "    boolean dense = isDense(maximum);\n"),

    ("trace", W,
     "      auditClock.tick(windowMaximum, Reading.stableBand(maximum));\n    }\n    sample.close(hitRate);\n",
     "      auditClock.tick(windowMaximum, Reading.stableBand(maximum));\n    }\n" + TRACE
     + "    sample.close(hitRate);\n"),

    ("ablate-corner", W,
     "      return isDeadSample() || (windowStarved && (windowMax <= (maximum >>> 2)));\n",
     "      if (NOSTARVE) {\n        return false;\n      }\n"
     "      boolean blind = isDeadSample() || (windowStarved && (windowMax <= (maximum >>> 2)))\n"
     "          || (CORNERPROBE && mainStarved && (windowMax >= upperCorner()) && fired(CORNER));\n"
     "      return blind && fired(STARVE);\n"),

    ("cornerprobe-down", W,
     "      return isDeadSample() && (windowMax >= (maximum >>> 1));\n",
     "      return (CORNERPROBE && mainStarved && (windowMax >= upperCorner()))\n"
     "          || (isDeadSample() && (windowMax >= (maximum >>> 1)));\n"),


    ("ablate-ladder", W,
     "    void escalate() {\n      rung = Math.min(PROBE_BACKOFF_MAX, 2 * rung);\n",
     "    void escalate() {\n      fired(LADDER);\n"
     "      rung = NOLADDER ? rung : Math.min(PROBE_BACKOFF_MAX, 2 * rung);\n"),

    ("ablate-scale", W,
     "      return (rung >= PROBE_BACKOFF_MAX)\n          ? PROBE_STRIDE_SCALE_DEEP\n",
     "      if ((rung >= (2 * PROBE_BACKOFF_INITIAL)) && !NOSCALE) {\n        fired(SCALE);\n      }\n"
     "      return NOSCALE ? 1 : (rung >= PROBE_BACKOFF_MAX)\n          ? PROBE_STRIDE_SCALE_DEEP\n"),

    ("ablate-commit", W,
     "      return (rung >= PROBE_BACKOFF_MAX)\n          ? PROBE_COMMITMENT_DEEP\n",
     "      if ((rung >= (2 * PROBE_BACKOFF_INITIAL)) && !NOCOMMIT) {\n        fired(COMMIT);\n      }\n"
     "      return NOCOMMIT ? 0 : (rung >= PROBE_BACKOFF_MAX)\n          ? PROBE_COMMITMENT_DEEP\n"),

    ("ablate-repeat", W,
     "    boolean isRepeat(boolean down, long window, long band) {\n      if ((farthest < 0) || (down != farthestDown)) {\n",
     "    boolean isRepeat(boolean down, long window, long band) {\n      if (NOREPEAT || (farthest < 0) || (down != farthestDown)) {\n"),

    ("count-repeat", W,
     "      return down ? (window >= (farthest - band)) : (window <= (farthest + band));\n",
     "      boolean repeat = down ? (window >= (farthest - band)) : (window <= (farthest + band));\n"
     "      return repeat && fired(REPEAT);\n"),

    ("ablate-wedge", W,
     "        if (walk.isReversedBy(reading)\n",
     "        if ((!NOWEDGE && walk.isReversedBy(reading) && fired(WEDGE))\n"),

    ("ablate-follow", W,
     "      if (!Double.isNaN(settledRate)) {\n        if (!parked || (Math.abs(rate - settledRate) >= RESTART_THRESHOLD)) {\n",
     "      if (!Double.isNaN(settledRate)) {\n"
     "        if (!NOFOLLOW && parked && (Math.abs(rate - settledRate) < RESTART_THRESHOLD)) {\n"
     "          fired(FOLLOW);\n        }\n"
     "        if (NOFOLLOW || !parked || (Math.abs(rate - settledRate) >= RESTART_THRESHOLD)) {\n"),

    ("ablate-shield", W,
     "    void park(int shield) {\n      freshLeft = shield;\n",
     "    void park(int shield) {\n      if ((shield > 0) && !NOSHIELD) {\n        fired(SHIELD);\n      }\n"
     "      freshLeft = NOSHIELD ? 0 : shield;\n"),

    ("ablate-veto", W,
     "        if (shortfallStreak >= VETO_STREAK) {\n",
     "        if (!NOVETO && (shortfallStreak >= VETO_STREAK) && fired(VETO)) {\n"),

    ("ablate-retest", W,
     "      return rates.smoothed < (claimed - rates.vetoMargin());\n",
     "      return !NORETEST && (rates.smoothed < (claimed - rates.vetoMargin())) && fired(RETEST);\n"),

    ("ablate-freeze", W,
     "      double baseline = baseProbationDensity\n          * ((double) r.requestCount / Math.max(1L, baseRequestCount));\n",
     "      fired(FREEZE);\n"
     "      double baseline = (NOFREEZE ? r.probationDensity : baseProbationDensity)\n"
     "          * (NOFREEZE ? 1.0 : ((double) r.requestCount / Math.max(1L, baseRequestCount)));\n"),

    ("tier-override-period", W,
     "    return DensityClimber.appliesTo(maximum)\n        ? density.samplePeriod(maximum, sketchSampleSize)\n",
     "    return isDensePeriod(maximum)\n        ? density.samplePeriod(maximum, sketchSampleSize)\n"),

    ("staleclaim", W,
     "    if (isWorkloadShift(reading) && anchor.standDown(reading)) {\n",
     "    if (isWorkloadShift(reading) && anchor.standDown(reading) && !STALECLAIM) {\n"),

    ("mode-walking", W,
     "      if (ending == ProbeEnding.WALKING) {\n        return walkStep(walk, /* entry= */ false, reading);\n",
     "      if (ending == ProbeEnding.WALKING) {\n"
     "        dbgMode = (walk.isAudit ? \"auditWalk\" : \"walk\") + walk.aboveStreak;\n"
     "        return walkStep(walk, /* entry= */ false, reading);\n"),

    ("mode-undo", W,
     "      } else if (ending != ProbeEnding.CONFIRMED) {\n        return undoProbe(walk, ending, reading);\n",
     "      } else if (ending != ProbeEnding.CONFIRMED) {\n"
     "        dbgMode = (walk.isAudit ? \"audit\" : \"\")\n"
     "            + ((ending == ProbeEnding.CRASHED) ? \"Crash\" : \"Fail\");\n"
     "        return undoProbe(walk, ending, reading);\n"),

    ("mode-auditconfirm", W,
     "      } else if (keepConfirmedPosition(walk, reading)) {\n"
     "        return anchor.returning ? strideHome(reading) : 0.0;\n",
     "      } else if (keepConfirmedPosition(walk, reading)) {\n"
     "        dbgMode = anchor.returning ? \"AUDITCONFIRM+home\" : \"AUDITCONFIRM\";\n"
     "        return anchor.returning ? strideHome(reading) : 0.0;\n"),

    ("mode-confirm-steer", W,
     "      return density.steer(reading.steeringError(), reading);\n    } else if (hasPendingUndo()) {\n      return undoStride(reading);\n    } else if (anchor.returning) {\n      return strideHome(reading);\n    } else if (anchor.isRetestDue(reading)) {\n      retestReturn(reading);\n      return 0.0;\n    } else if (reading.hasBlindCorner()) {\n",
     "      dbgMode = \"CONFIRM+steer\";\n      return density.steer(reading.steeringError(), reading);\n"
     "    } else if (hasPendingUndo()) {\n      dbgMode = \"undo\";\n      return undoStride(reading);\n"
     "    } else if (anchor.returning) {\n      dbgMode = \"vetoRet\";\n      return strideHome(reading);\n"
     "    } else if (anchor.isRetestDue(reading)) {\n      dbgMode = \"retest\";\n      retestReturn(reading);\n      return 0.0;\n"
     "    } else if (reading.hasBlindCorner()) {\n      dbgMode = isBackingOff() ? \"hold\" : \"ARM\";\n"),

    ("mode-veto-audit-park", W,
     "    } else if (anchor.vetoTriggered(reading, rates)) {\n      return strideHome(reading);\n    } else if (auditClock.isDue()) {\n      return armEquilibriumAudit(reading);\n    } else if (anchor.held) {\n",
     "    } else if (anchor.vetoTriggered(reading, rates)) {\n      dbgMode = \"VETO\";\n      return strideHome(reading);\n"
     "    } else if (AUDITS && auditClock.isDue()) {\n      dbgMode = \"AUDIT\";\n      return armEquilibriumAudit(reading);\n"
     "    } else if (anchor.held) {\n      dbgMode = \"park\";\n"),

    ("mode-steer", W,
     "    return density.steer(reading.steeringError(), reading);\n  }\n",
     "    dbgMode = \"steer\";\n    return density.steer(reading.steeringError(), reading);\n  }\n"),

    ("parkbound", W,
     "      anchor.ageShield();\n    }\n",
     "      anchor.ageShield();\n      if (PARKBOUND && (anchor.freshLeft <= 0)) {\n"
     "        anchor.release();\n      }\n    }\n"),

    ("pricedshift", W,
     "  private boolean isWorkloadShift(Reading reading) {\n"
     "    return (Math.abs(sample.hitRateChange(reading.hitRate)) >= RESTART_THRESHOLD)\n"
     "        && !isShielded() && !isParkTest() && !isReturnTest();\n  }\n",
     "  private boolean isWorkloadShift(Reading reading) {\n"
     "    double threshold = PRICEDSHIFT\n"
     "        ? Math.min(3 * RESTART_THRESHOLD, Math.max(RESTART_THRESHOLD, rates.noiseBand()))\n"
     "        : RESTART_THRESHOLD;\n"
     "    return (Math.abs(sample.hitRateChange(reading.hitRate)) >= threshold)\n"
     "        && !isShielded() && !isParkTest() && !isReturnTest();\n  }\n"),

    ("noreturncover", W,
     "  private boolean isReturnTest() {\n"
     "    return (retreatLeft > 0) || ((anchor.retestClaim >= 0) && !anchor.returning);\n  }\n",
     "  private boolean isReturnTest() {\n"
     "    return !NORETURNCOVER\n"
     "        && (((retreatLeft > 0) && (!NOWIDECOVER || anchor.held))\n"
     "            || ((anchor.retestClaim >= 0) && !anchor.returning));\n  }\n"),

    # the discarding stand-down's site (the isWorkloadShift one; the retest's discard in
    # retestReturn is deliberately not wired): the §8 item 4 latency-face candidate
    ("rearm", W,
     "      rates.reset();\n    }\n    updateRateReferences(reading);\n",
     "      rates.reset();\n"
     "      if (REARM && (!REARMHELD || parkedAtShift)) {\n"
     "        if (REARMRESET) {\n          auditClock.reset();\n"
     "        } else {\n          auditClock.waitSamples = AuditClock.AUDIT_WAIT_FIRST;\n"
     "          if (REARMSTILL) {\n            auditClock.stillSamples = 0;\n          }\n        }\n"
     "        if (REARMCOLD) {\n          audit.reset();\n        }\n      }\n"
     "    }\n    updateRateReferences(reading);\n"),

    # the hold is released by the stand-down, so the held-park gate reads it before
    ("rearm-held", W,
     "    ageRetreatCover();\n\n    var reading = new Reading(",
     "    ageRetreatCover();\n    boolean parkedAtShift = anchor.held;\n\n    var reading = new Reading("),

    ("rearm-retest", W,
     "      rates.reset();\n    }\n  }\n",
     "      rates.reset();\n"
     "      if (REARMBOTH) {\n        auditClock.reset();\n        audit.reset();\n      }\n"
     "    }\n  }\n"),


    ("noaudit-holdoraudit", W,
     "    return auditClock.isDue() ? armEquilibriumAudit(reading) : holdInRefractory(reading);\n",
     "    return (AUDITS && auditClock.isDue())\n"
     "        ? armEquilibriumAudit(reading) : holdInRefractory(reading);\n"),

    ("mode-armprobe", W,
     "    var armed = armProbe(reading, reading.shouldProbeDown(), /* isAudit= */ false);\n",
     "    var armed = armProbe(reading, reading.shouldProbeDown(), /* isAudit= */ false);\n"
     "    dbgMode = \"ARM\" + (armed.down ? \"dn\" : \"up\");\n"),

    ("mode-armaudit", W,
     "    auditClock.restart();\n",
     "    auditClock.restart();\n    dbgMode = \"AUDIT\" + (armed.down ? \"dn\" : \"up\");\n"),

    ("precrash-ladder", W,
     "    var ladder = isAudit ? audit : starvation;\n",
     "    var ladder = (isAudit && !PRECRASH) ? audit : starvation;\n"),

    ("precrash-stride", W,
     "    } else if (walk.isAudit && (walk.belowBarStreak > 0)) {\n",
     "    } else if (walk.isAudit && !PRECRASH && (walk.belowBarStreak > 0)) {\n"),

    ("starvwrite", W,
     "        refractoryLeft = 0;\n        return ProbeEnding.CONFIRMED;\n",
     "        refractoryLeft = 0;\n"
     "        if (STARVWRITE) {\n          auditClock.waitSamples = AuditClock.AUDIT_WAIT_INITIAL;\n        }\n"
     "        return ProbeEnding.CONFIRMED;\n"),

    ("precrash-reschedule", W,
     "      auditClock.reschedule(failed, crashed, audit.rung);\n",
     "      // precrash: the pre-fix schedule off the shared ladder, where a streak-escalated crash\n"
     "      // reached the failure-doubling branch through the shared `failed` flag\n"
     "      auditClock.reschedule(PRECRASH ? failed : (failed && !crashed),\n"
     "          /* crashed= */ false, walk.ladder.rung);\n"),

    ("auditbar", W,
     "      return isAudit\n          ? Math.min(RESTART_THRESHOLD, AUDIT_BAR_FRACTION * baseHitRate)\n          : Math.min(PROBE_BAR_CAP * RESTART_THRESHOLD,\n",
     "      return isAudit\n          ? ((AUDIT_BAR <= 0) ? RESTART_THRESHOLD\n"
     "              : Math.min(RESTART_THRESHOLD, AUDIT_BAR * baseHitRate))\n"
     "          : ABSPROBE ? RESTART_THRESHOLD\n          : Math.min(PROBE_BAR_CAP * RESTART_THRESHOLD,\n"),

    ("precrash-tolerance", W,
     "      boolean tolerant = isAudit && ladder.hasCrashed();\n",
     "      boolean tolerant = isAudit && !PRECRASH && ladder.hasCrashed();\n"),

    ("hardreset", W,
     "      stillSamples = samePlace ? (stillSamples + 1) : Math.max(0, stillSamples - 1);\n",
     "      stillSamples = samePlace ? (stillSamples + 1)\n"
     "          : (HARDRESET ? 0 : Math.max(0, stillSamples - 1));\n"),

    ("flatroom", W,
     "    boolean chooseDirection(Reading r, double stride, double rate, boolean parked) {\n",
     "    boolean chooseDirection(Reading r, @Var double stride, double rate, boolean parked) {\n"),

    ("flatroom-body", W,
     "      double room = down ? (r.windowMax - r.floor) : (r.upperCorner() - r.windowMax);\n",
     "      if (FLATROOM) {\n        stride = r.restartMagnitude();\n      }\n"
     "      double room = down ? (r.windowMax - r.floor) : (r.upperCorner() - r.windowMax);\n"),

    ("nocal", W,
     "      waitSamples = AUDIT_WAIT_FIRST;\n      settledRate = Double.NaN;\n"
     "      stillSamples = 0;\n      lastWindow = -1;\n",
     "      waitSamples = WAIT_FIRST;\n      settledRate = Double.NaN;\n"
     "      stillSamples = 0;\n      lastWindow = -1;\n"),

    # anchored on the preceding CODE line so the block lands before the javadoc, not between
    # the javadoc and the field it documents
    ("admit-seed", B,
     "  static final double PERCENT_MAIN_PROTECTED = 0.80d;\n",
     "  static final double PERCENT_MAIN_PROTECTED = 0.80d;\n\n" + SEED),

    ("startwin-flags", B,
     "  static final double PERCENT_MAIN = 0.99d;\n",
     "  static final double PERCENT_MAIN = 0.99d;\n" + STARTWIN),

    ("startwin-plant", B,
     "    long window = max - (long) (PERCENT_MAIN * max);\n"
     "    long mainProtected = (long) (PERCENT_MAIN_PROTECTED * (max - window));\n",
     "    // EXPERIMENT HARNESS: -Dcaffeine.climber.startwin=<frac> plants the window away from\n"
     "    // the shipped 1%, holding probation's capacity unless startsplit=resize\n"
     "    long defaultWindow = max - (long) (PERCENT_MAIN * max);\n"
     "    long window = (START_WINDOW < 0) ? defaultWindow : (long) (START_WINDOW * max);\n"
     "    long probation = (max - defaultWindow)\n"
     "        - (long) (PERCENT_MAIN_PROTECTED * (max - defaultWindow));\n"
     "    long mainProtected = ((START_WINDOW < 0) || START_RESPLIT)\n"
     "        ? (long) (PERCENT_MAIN_PROTECTED * (max - window))\n"
     "        : Math.max(0L, (max - window) - probation);\n"),

    ("admit-draw", B,
     "      int random = ThreadLocalRandom.current().nextInt();\n",
     "      // EXPERIMENT HARNESS: -Dcaffeine.climber.seed=<n> replaces the HashDoS draw with a\n"
     "      // seeded stream. That draw is the simulator's only live nondeterminism, so seeding it\n"
     "      // makes a lottery cell reproduce bit for bit and a basin be attributed, not counted.\n"
     "      int random = (ADMIT_RANDOM == null)\n"
     "          ? ThreadLocalRandom.current().nextInt()\n"
     "          : ADMIT_RANDOM.nextInt();\n"),

    # marginal-value anatomy: region-attributed hits at a STATIC window, read by marginal.py.
    # Was a unified diff (marginal-anatomy.patch) until it was moved here for the same reason
    # the rest of the instrumentation was.
    ("marginal-fields", P,
     "  private final Admitter admitter;\n",
     MARGINAL_FIELDS + "  private final Admitter admitter;\n"),

    ("marginal-onmiss", P,
     "    sizeWindow += weight;\n    sizeData += weight;\n    evict();\n",
     "    sizeWindow += weight;\n    sizeData += weight;\n"
     "    windowNodes++;\n    rebalanceWindowTail();\n    evict();\n"),

    # anchored past the closing brace so the helpers land between the methods, not between the
    # next method's javadoc and its signature
    ("marginal-windowhit", P,
     "    updateWeight(node, weight);\n    node.moveToTail(headWindow);\n    evict();\n  }\n",
     "    updateWeight(node, weight);\n"
     "    hitsWindow++;\n    if (node.inWindowTail) {\n      hitsWindowTail++;\n    }\n"
     "    leaveWindowTail(node);\n    node.moveToTail(headWindow);\n    rebalanceWindowTail();\n"
     "    evict();\n  }\n\n" + MARGINAL_HELPERS),

    ("marginal-probationhit", P,
     "    updateWeight(node, weight);\n\n    if (node.weight > maxProtected) {\n",
     "    updateWeight(node, weight);\n    hitsProbation++;\n\n    if (node.weight > maxProtected) {\n"),

    ("marginal-protectedhit", P,
     "    updateWeight(node, weight);\n    node.moveToTail(headProtected);\n",
     "    updateWeight(node, weight);\n    hitsProtected++;\n"
     "    node.moveToTail(headProtected);\n"),

    ("marginal-evictwindow", P,
     "      Node candidate = requireNonNull(headWindow.next);\n"
     "      candidate.remove();\n      candidate.status = Status.PROBATION;\n"
     "      candidate.appendToTail(headProbation);\n      sizeWindow -= candidate.weight;\n"
     "      if (first == null) {\n",
     "      Node candidate = requireNonNull(headWindow.next);\n"
     "      leaveWindowTail(candidate);\n      windowNodes--;\n"
     "      candidate.remove();\n      candidate.status = Status.PROBATION;\n"
     "      candidate.appendToTail(headProbation);\n      sizeWindow -= candidate.weight;\n"
     "      rebalanceWindowTail();\n      if (first == null) {\n"),

    ("marginal-evictentry", P,
     "    if (node.status == Status.WINDOW) {\n      sizeWindow -= node.weight;\n"
     "    } else if (node.status == Status.PROTECTED) {\n"
     "      sizeProtected -= node.weight;\n    }\n"
     "    sizeData -= node.weight;\n    data.remove(node.key);\n    node.remove();\n"
     "    policyStats.recordEviction();\n",
     "    if (node.status == Status.WINDOW) {\n      sizeWindow -= node.weight;\n"
     "      leaveWindowTail(node);\n      windowNodes--;\n"
     "    } else if (node.status == Status.PROTECTED) {\n"
     "      sizeProtected -= node.weight;\n    }\n"
     "    sizeData -= node.weight;\n    data.remove(node.key);\n    node.remove();\n"
     "    if (node.status == Status.WINDOW) {\n      rebalanceWindowTail();\n    }\n"
     "    policyStats.recordEviction();\n"),

    ("marginal-report", P,
     "    checkState(sizeData <= maximumSize);\n",
     "    checkState(sizeData <= maximumSize);\n"
     "    if (MEASURE) {\n"
     "      var capWindow = maxWindow;\n"
     "      var capMain = maximumSize - maxWindow;\n"
     "      var capProbation = capMain - maxProtected;\n"
     "      var capTail = Math.max(1L, (long) Math.ceil(TAIL_FRACTION * maxWindow));\n"
     "      System.err.printf(\"MARGINAL winpct=%.6f capacity=%d capW=%d capM=%d capP=%d capT=%d\"\n"
     "          + \" hw=%d ht=%d hp=%d hq=%d%n\",\n"
     "          ((double) maxWindow) / maximumSize, maximumSize, capWindow, capMain, capProbation,\n"
     "          capTail, hitsWindow, hitsWindowTail, hitsProbation, hitsProtected);\n"
     "    }\n"),

    ("marginal-node-field", P,
     "    int weight;\n    @Nullable Node prev;\n",
     "    int weight;\n    boolean inWindowTail;\n    @Nullable Node prev;\n"),

]


def check_edits():
    """Every replacement must be distinguishable from every other, per file.

    Presence of `repl` is the idempotency test, the verify test and the strip target, so two edits
    whose replacements are equal — or where one contains the other — make the second invisible:
    apply skips it as already wired, verify passes, and the instrumentation is silently partial
    while still printing plausible numbers. A sibling harness hit exactly that (two edits inserting
    the same counter line at different anchors) and lost every protected-region hit. Today's 36
    edits are clean; this keeps a future one from reintroducing it.
    """
    bad = []
    for i, (n1, r1, _, p1) in enumerate(EDITS):
        for n2, r2, _, p2 in EDITS[i + 1:]:
            if r1 == r2 and (p1 == p2 or p1 in p2 or p2 in p1):
                bad.append(f"{n1} <-> {n2}: replacements are not distinguishable in {r1}")
    if bad:
        print("harness: EDITS table is unsafe", file=sys.stderr)
        for b in bad:
            print(f"  {b}", file=sys.stderr)
        sys.exit(2)


def run(mode, wt):
    check_edits()
    problems, applied = [], 0
    for name, rel, anchor, repl in EDITS:
        path = os.path.join(wt, rel)
        if not os.path.exists(path):
            problems.append(f"{name}: missing file {rel}")
            continue
        src = open(path).read()
        if mode == "verify":
            if repl not in src:
                problems.append(f"{name}: NOT WIRED IN")
            continue
        if mode == "strip":
            if repl in src:
                open(path, "w").write(src.replace(repl, anchor, 1)); applied += 1
            continue
        if repl in src:                       # idempotent
            applied += 1
            continue
        n = src.count(anchor)
        if n != 1:
            problems.append(f"{name}: anchor found {n} times, expected 1 — the source moved")
            continue
        open(path, "w").write(src.replace(anchor, repl, 1)); applied += 1

    verb = {"apply": "wired", "verify": "present", "strip": "removed"}[mode]
    if problems:
        print(f"harness {mode}: FAILED ({len(problems)} of {len(EDITS)})", file=sys.stderr)
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        return 1
    print(f"harness {mode}: ok — {applied if mode != 'verify' else len(EDITS)}/{len(EDITS)} {verb}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("apply", "verify", "strip"):
        sys.exit(__doc__)
    sys.exit(run(sys.argv[1], sys.argv[2]))
