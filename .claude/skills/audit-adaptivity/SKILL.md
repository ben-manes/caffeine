---
name: audit-adaptivity
description: Audit the adaptive window hill-climber and region-resize logic for implementation defects (not algorithm quality)
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the adaptive W-TinyLFU control loop and the admission-window / main-region
resize logic for IMPLEMENTATION defects. This is the one eviction subsystem not in
`/audit-subsystem-safety`'s scope, and its small-cache path was recently reworked
(`SMALL_CACHE_THRESHOLD` / `SMALL_CACHE_SAMPLE_RATIO_CAP`), so it has elevated
bug density.

## Scope boundary — implementation correctness, NOT algorithm quality

The adaptation policy itself is at its tuned frontier. Out of scope: convergence
rate, hit-rate, oscillation-as-a-design-tradeoff, and the choice of the tuning
constants. Do NOT report "the climber could converge faster / oscillates / constant
X should be Y." Report only defects: arithmetic that yields a wrong value, a sign
error, a state that violates a structural invariant, a race, a NaN, or an overflow.

## Methods in scope

- `climb`, `determineAdjustment` — the feedback step (hit-rate delta → step → adjustment)
- `increaseWindow`, `decreaseWindow`, `demoteFromMainProtected` — region transfer
- `setMaximumSize` — initial window/main split and the `SMALL_CACHE_THRESHOLD` step-sign flip
- `evictFromWindow` / `evictFromMain` — they consume the region maxima the climber sets

Fields/constants: `windowMaximum`, `mainProtectedMaximum`, `windowWeightedSize`,
`mainProtectedWeightedSize`, `stepSize`, `adjustment`, `hitsInSample`,
`missesInSample`, `previousSampleHitRate`; `HILL_CLIMBER_STEP_PERCENT`,
`HILL_CLIMBER_STEP_DECAY_RATE`, `HILL_CLIMBER_RESTART_THRESHOLD`,
`HILL_CLIMBER_MIN_INITIAL_STEP`, `SMALL_CACHE_THRESHOLD`,
`SMALL_CACHE_SAMPLE_RATIO_CAP`, `QUEUE_TRANSFER_THRESHOLD`.

## Structural invariants to attack (violations are real bugs)

1. **Region partition sum**: `windowMaximum + mainMaximum` (probation + protected)
   must equal `maximum()` after every climb and every resize. Can any single transfer,
   or a sequence capped by `QUEUE_TRANSFER_THRESHOLD`, drift the sum?
2. **Non-negative maxima**: can `windowMaximum` or `mainProtectedMaximum` go negative
   — a quota larger than the donor region, or repeated `decreaseWindow` at the floor?
3. **Quota accounting**: in `increaseWindow`/`decreaseWindow` the `quota` is decremented
   per transferred node by `policyWeight`. With weighted entries, can `quota` underflow,
   skip/over-run the loop, or transfer the wrong count? Does the
   `QUEUE_TRANSFER_THRESHOLD` cap leave the regions half-adjusted such that the next
   `climb` mis-reads them?
4. **`determineAdjustment` math**:
   - `requestCount = hits + misses`; the early return guards `requestCount <
     effectiveSampleSize`. Is the `hitRate` division ever reachable with
     `requestCount == 0`?
   - small-cache branch: `effectiveSampleSize = (long)(sampleSize * ratio)`, where
     `ratio = clamp(initialStep / magnitude)`. Can `initialStep` be 0 (maximum 0 or
     tiny) making `magnitude` 0 → division by zero? Can the `(long)` cast truncate
     `ratio` so it defeats the intended sample-period growth?
   - `nextStepSize` uses `Math.copySign(max(...), amount)`. For `amount == 0.0` / `-0.0`,
     does `copySign` choose the intended direction? Can `stepSize` become NaN or 0 and
     **permanently stall** adaptation (a stuck-window *bug*, distinct from slow convergence)?
5. **`setMaximumSize` at boundaries**: the step-sign flip at `max <= SMALL_CACHE_THRESHOLD`
   plus a *runtime* maximum change via `Policy.eviction().setMaximum` — when `maximum`
   crosses `SMALL_CACHE_THRESHOLD` in either direction, do the window/main split, the
   `stepSize` sign, and the sample state stay mutually consistent?
6. **Stale `adjustment` consumption**: `climb` calls `determineAdjustment` then
   `increaseWindow`/`decreaseWindow` off `adjustment()`. When `determineAdjustment`
   early-returns (uninitialized sketch, sub-sample request count), can a stale
   `adjustment` from a prior cycle be re-applied?

## Output

For each defect: give concrete maximum/weight/access values, trace the arithmetic
step by step, show the resulting invariant violation or wrong region size, and a
Verification (a `BoundedLocalCacheTest` white-box method plus the required `-P` flags).

Everything here runs under `evictionLock` (single-writer), so most findings will be
arithmetic / state-corruption, not races — but explicitly check whether any
climber-written field (`adjustment`, `stepSize`, the region maxima) is also read
off-lock by a concurrent reader before concluding "single-writer, cannot race."
