---
name: audit-adaptivity
description: Audit the adaptive window hill-climber and region-resize logic for implementation defects (not algorithm quality)
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the adaptive W-TinyLFU control loop and the admission-window / main-region
resize logic for IMPLEMENTATION defects. This is the one eviction subsystem not in
`/audit-subsystem-safety`'s scope, and it was recently rebuilt — the climber now lives in the
package-private `WindowClimber` class (reached through the generated `climber` field), with a new
density-signal + probe-machine large tier (>4096) — so it has elevated bug density.

## Scope boundary — implementation correctness, NOT algorithm quality

The adaptation policy itself is at its tuned frontier. Out of scope: convergence
rate, hit-rate, oscillation-as-a-design-tradeoff, and the choice of the tuning
constants. (Behavioral hit-rate regression against the adversarial trap suite is
`/climber-gate`'s job, not this audit's.) Do NOT report "the climber could converge faster / oscillates / constant
X should be Y." Report only defects: arithmetic that yields a wrong value, a sign
error, a state that violates a structural invariant, a race, a NaN, or an overflow.

## Methods in scope

- `climb`, `determineAdjustment` — the feedback step (reactive: hit-rate delta; density:
  within-sample densities → step → adjustment). `determineAdjustment` carries
  `mainProtectedMaximum` to size the probation capacity for the probe verdict
- `densityClimb`, `armProbe`, `walkStep`, `probeEnding`, `undoProbe`, `DensityClimber.steer` — the
  large tier's signal, probe machine, and verdict internals (all on `WindowClimber`)
- `increaseWindow`, `decreaseWindow`, `demoteFromMainProtected` — region transfer
- `setMaximumSize` — initial window/main split, plus `WindowClimber.resized` (the
  `SLOW_ADAPT_THRESHOLD` step-sign flip and the probe-machine reset)
- `evictFromWindow` / `evictFromMain` — they consume the region maxima the climber sets

Probe machine (>4096, in `WindowClimber`): starved samples (region hits below
max(4, requestCount >> MIN_SIGNAL_SHIFT)) at a blind corner launch a bold-driver walk.
Endings: crash-abort (full undo, refractory re-armed WITHOUT doubling), reversal-through-base and
budget expiry (failed experiments: full undo + ladder x2), adjudication at >=4x the bar under the
committed depth (confirm keeps the position + ladder resets to 1; anything else fails with a full
undo). The verdict is asymmetric BY DESIGN: an up-probe confirms iff
`ln((windowDensity+eps)/(walk.baseProbationDensity+eps)) > 0` — the probation-marginal baseline
FROZEN in `armProbe` — while a down-probe uses the average-density sign test (`error*dir > 0`).
Ladder: PROBE_BACKOFF_INITIAL (16) doubling to _MAX (64). Invariants to audit:
1 <= starvation.rung <= 64; 0 <= refractoryLeft <= starvation.rung, which both oracles assert
directly — the walk budget is a separate field (`Walk.samples`, bounded by PROBE_WALK_BUDGET) on
an object that exists only while walking, so the two can no longer alias; probe state fully reset
by resized(); an undo returns exactly to `walk.baseWindow`; the below-floor lift cannot exceed the
floor; `sample.probationHits <= sample.hits - sample.windowHits`;
`walk.baseProbationDensity` is written ONLY in `armProbe` (each re-arm re-snapshots) and is
non-negative and finite; the probation capacity denominator is
`max(1, maximum - windowMaximum - mainProtectedMaximum)` — capacities, never occupancy.

NOT bugs (adjudicated design; read `hill-climber.md` §4/§7 before flagging): the up/down verdict
asymmetry (the window has no marginal substructure to price against); the frozen — hence
stale-looking — baseline (judging against the LIVE probation rate is an absorbing false-veto: the
walk's own demotions enrich it — the demoflood gate row; a cold-start-transient baseline is
bounded and self-heals because every re-arm re-snapshots); probation attribution captured
BEFORE `reorderProbation` can promote the entry in `onAccess` (the promoting access counts as a
probation hit, the next as protected); a zero baseline auto-confirming any >=4x-bar earnings
(the opportunity cost of a dead boundary is ~zero — nullchurn stays harmless); and the lowmix
named trade (a gate sentinel). A walk's bases (`down`, `baseHitRate`, `baseWindow`,
`baseProbationDensity`) are `final` on the `Walk` object, which exists only while one is in
flight — "dead state while not probing" is the absent object, so there is nothing to go stale.

Cache-side fields: `windowMaximum`, `mainProtectedMaximum`, `windowWeightedSize`,
`mainProtectedWeightedSize`; `QUEUE_TRANSFER_THRESHOLD`.

`WindowClimber`'s own fields are `adjustment`, `refractoryLeft`, `undoRemaining` plus nine small
objects, each owning its state and the behavior that reads only it (2026-08-02 refactor — a name
from an older flat-field layout means the note predates it): `sample` (`Sample`: `hits`, `misses`,
`windowHits`, `probationHits`, `previousHitRate`), `step` (`Step`: `size`), `reactive` and
`density` (a `ReactiveClimber`/`DensityClimber`, each holding that same `Step`), `walk` (`Walk`,
null while none is in flight:
`ladder`, `isAudit`, `down`, `baseWindow`, `baseHitRate`, `baseSmoothedRate`,
`baseProbationDensity`, `samples`, `belowBarStreak`, `aboveStreak`, `beatBase`), `starvation` and
`audit` (a `Ladder` each: `rung`, `crashStreak`), `auditClock` (`AuditClock`: `down`,
`waitSamples`, `stillSamples`, `lastWindow`), `anchor` (`Anchor`: `window`, `rate`, `held`,
`freshLeft`, `returning`, `returnLeft`, `shortfallStreak`), and `rates` (`Rates`: `smoothed`,
`deviation`). `Reading` is the per-sample derived view, computed once and read-only.

The 38 constants live with the mechanism each tunes, so a knob names its owner:
`WindowClimber` — `RESTART_THRESHOLD`;
`DensityClimber` — `DENSITY_THRESHOLD`, `DENSITY_GAIN`, `SAMPLE_MULTIPLIER`;
`Step` — `STEP_PERCENT`, `STEP_DECAY_RATE`, `MIN_INITIAL_STEP`;
`ReactiveClimber` — `SLOW_ADAPT_THRESHOLD`, `SLOW_ADAPT_RATIO_CAP`, `SLOW_ADAPT_DECAY_RATE`;
`Reading` — `STABLE_BAND_FRACTION`, `MAX_STEP_FRACTION`, `WINDOW_FLOOR_FRACTION`,
`DENSITY_EPSILON`, `MIN_SIGNAL_SHIFT`, `MIN_STARVATION_BAR`;
`Rates` — `VETO_MARGIN_MIN`, `RATE_SMOOTHING`, `DEVIATION_SEED`, `VETO_MARGIN_SCALE`;
`AuditClock` — `AUDIT_WAIT_INITIAL`, `AUDIT_WAIT_FIRST`, `AUDIT_WAIT_MAX`;
`Anchor` — `VETO_STREAK`, `VETO_RETURN_BUDGET`;
`Walk` — `PROBE_WALK_BUDGET`, `PROBE_BAR_CAP`, `PROBE_EXIT_BAR_MULTIPLE`,
`AUDIT_CRASH_PERSISTENCE`, `AUDIT_COMMITMENT`, `AUDIT_CONFIRM_STREAK`;
`Ladder` — `PROBE_BACKOFF_INITIAL`, `PROBE_BACKOFF_MAX`, `PROBE_CRASH_ESCALATION`,
`PROBE_STRIDE_SCALE_MID`, `PROBE_STRIDE_SCALE_DEEP`, `PROBE_COMMITMENT_MID`,
`PROBE_COMMITMENT_DEEP`.

## Structural invariants to attack (violations are real bugs)

1. **Region partition sum**: `windowMaximum + mainMaximum` (probation + protected)
   must equal `maximum()` after every climb and every resize. Can any single transfer,
   or a sequence capped by `QUEUE_TRANSFER_THRESHOLD`, drift the sum?
2. **Non-negative maxima**: can `windowMaximum` or `mainProtectedMaximum` go negative
   — a quota larger than the donor region, or repeated `decreaseWindow` at the floor?
   EXCEPTION (adjudicated 2026-07, F1; duration priced 2026-08-22, M1): a transiently
   negative `policyWeight` — the sanctioned telescoping race, an out-of-order UpdateTask
   drain — can over-shift the caps beyond the commanded adjustment, even past these
   bounds. Tolerated by design: the caps are policy targets (eviction is driven by the
   telescoping `weightedSize`/`maximum`), and they walk back only by the weight each later
   transfer moves, so a swing larger than a cycle's transfer suspends the split for many
   cycles with the total still bounded. Report cap drift only with a mechanism that never
   walks back.
3. **Quota accounting**: in `increaseWindow`/`decreaseWindow` the `quota` is decremented
   per transferred node by `policyWeight`. With weighted entries, can `quota` underflow,
   skip/over-run the loop, or transfer the wrong count? Does the
   `QUEUE_TRANSFER_THRESHOLD` cap leave the regions half-adjusted such that the next
   `climb` mis-reads them? (Same F1 exception as #2 for the negative transient.)
4. **`determineAdjustment` math**:
   - `requestCount = hits + misses`; the early return guards `requestCount <
     effectiveSampleSize`. Is the `hitRate` division ever reachable with
     `requestCount == 0`?
   - small-cache branch (`ReactiveClimber.samplePeriod`): `(long) (sketchSampleSize * ratio)`,
     where `ratio = clamp(initialStep / magnitude)`. Can `initialStep` be 0 (maximum 0 or
     tiny) making `magnitude` 0 → division by zero? Can the `(long)` cast truncate
     `ratio` so it defeats the intended sample-period growth?
   - `ReactiveClimber.climb` uses `Math.copySign(Step.restartMagnitude(max), amount)`. For
     `amount == 0.0` / `-0.0`, does `copySign` choose the intended direction? Can `step.size`
     become NaN or 0 and **permanently stall** adaptation (a stuck-window *bug*, distinct from
     slow convergence)?
5. **`setMaximumSize` at boundaries**: the step-sign flip at `max <= SLOW_ADAPT_THRESHOLD`
   plus a *runtime* maximum change via `Policy.eviction().setMaximum` — when `maximum`
   crosses `SLOW_ADAPT_THRESHOLD` in either direction, do the window/main split, the
   `step.size` sign, and the sample state stay mutually consistent?
6. **Stale `adjustment` consumption**: `climb` calls `determineAdjustment` then
   `increaseWindow`/`decreaseWindow` off the climber's `adjustment`. When `determineAdjustment`
   early-returns (uninitialized sketch, sub-sample request count), can a stale
   `adjustment` from a prior cycle be re-applied?
7. **Layer ownership of the ladders, streaks, and schedules** — the highest-yield row, because
   it has produced two real bugs and both were invisible from a single code path.
   `hill-climber.md` §4 carries a **write-owner table** (observation / active walk / starvation
   retry / audit retry+schedule / goal guard / motion out). Enumerate **every** write to
   `starvation.rung`, `starvation.crashStreak`, `audit.rung`, `audit.crashStreak`,
   `auditClock.waitSamples`, `auditClock.stillSamples`, `anchor.held`, `anchor.freshLeft` and
   check each against its owner —
   *including endings that are not crashes*. Both landed defects were exactly this shape: the
   shared crash streak let exogenous pulses pair separate audit crashes into a rung ratchet
   (H4-C1), and later a non-crash ending (budget expiry, reversal-through-base) still cleared
   the **other** layer's streak, which disarmed that layer's escalation and its
   `AUDIT_CRASH_PERSISTENCE` tolerance — so an interleaved blind corner reached around the first
   fix. One cross-write is sanctioned, journaled: an audit confirm rewards the starvation
   ladder (`starvation.reward()`) and zeroes its refractory. An audit's undo leaves the
   refractory alone (since 2026-08-16, pinned by
   `undoProbe_auditRetreat_leavesTheStarvationRefractoryAlone`). Anything else is a finding.
   Checkable products the oracles already
   assert, and which a new invariant should join: `anchor.freshLeft > 0 ⇒ anchor.held`,
   `anchor.held ⇒ anchor.isPlanted()`, `walk != null ⇒ undoRemaining == 0`, and
   `auditClock.waitSamples > PROBE_BACKOFF_MAX ⇒ audit.rung == PROBE_BACKOFF_MAX` (the ratchet as
   an invariant). Note the one *legitimate* coupling so it is not reported: the audit clock is a
   function of window position, so any layer that moves the window decays `stillSamples` — that
   is the intended semantics, and it is the remaining path by which frequent blind corners defer
   audits.

## Output

For each defect: give concrete maximum/weight/access values, trace the arithmetic
step by step, show the resulting invariant violation or wrong region size, and a
Verification (a `BoundedLocalCacheTest` white-box method plus the required `-P` flags).

Everything here runs under `evictionLock` (single-writer), so most findings will be
arithmetic / state-corruption, not races — but explicitly check whether any
climber-written field (`adjustment`, `step.size`, the region maxima) is also read
off-lock by a concurrent reader before concluding "single-writer, cannot race."
