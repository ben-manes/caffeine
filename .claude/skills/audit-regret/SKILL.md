---
name: audit-regret
description: Adversarial workload search for eviction-policy regret. Crafts, shrinks, and classifies workloads on which the adaptive window climber fails to close the gap to its achievable optimum, and routes each failure class to the mechanism responsible (controller, policy structure, or recovery layer)
argument-hint: "[round | <failure-class> | <spec.json> | <trace> --size N [--fmt f]]"
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Write, Edit, Agent
---

# Adversarial policy audit: regret

The third leg of the climber's audit set. `/audit-adaptivity` finds implementation defects in
`WindowClimber`; `/climber-gate` re-runs the known traps so a change cannot regress them; this
skill finds the traps that are not known yet. It searches over synthetic workloads for cells where
the adaptive eviction policy (W-TinyLFU with the window climber) fails to close the gap to what it
could have earned, then works each one until it names a failure class, the state variable that
should have responded and did not, and the layer responsible. Its outputs are candidate rows for
`/climber-gate` and directions for `hill-climber.md` §3 and §5. Run it when a new model arrives, after a
climber change lands, or when a real trace shows a gap nobody can explain.

The unit of work is a **cell**: (workload, maximum). The unit of a finding is a **family**: a
parameterized workload with a named mechanism and a measured neighborhood, not one trace.

Scope: the density tier, maxima above `DensityClimber.DENSITY_THRESHOLD` (4096). At or below it
the reactive tier runs (`ReactiveClimber.climb`, a hit-rate law with `Step` and nothing else), the
debug trajectory is not emitted, and most of the responders in the class table do not exist; such
a cell yields end-to-end regret only and belongs to a tier-straddle pair (class 10) or to nothing
here. The decision count `n = requests / (4 × max)` is the density tier's sample period.

## Argument

$ARGUMENTS

- empty or `round`: the full round below.
- a class token from the table (`structural`, `wrong-equilibrium`, `slow-convergence`,
  `masked-signal`, `insufficient-exploration`, `oscillation`, `memory-failure`,
  `irreversible-damage`, `aliasing`, `premature-commitment`, `tier-discontinuity`): the same round
  with the proposal lanes given that class as their quota and the screen's validity gates unchanged.
  Use it when the record already points at a class (`hill-climber.md` §3) rather than asking what
  is broken.
- a spec path: skip proposal and screening, and run steps 4 through 7 on that one cell.
- a trace path (`--size N`, and `--fmt` for anything but `lirs`: `arc`, `corda`, ...): steps 5
  and 7 on that cell, with `--start` plants when the question is the descent. Shrink, bisect, and
  neighborhood need a spec, so step 4 becomes writing the spec that reproduces the trace's
  mechanism (its anchors and trajectory signature match the trace's) and shrinking that; without
  one the finding stays a single-cell observation and says so.

## Regret, defined for this skill

Every number is relative to anchors from one static sweep (`climber-gate/run.py`'s `curve`:
`linked.Lru` plus `sketch.WindowTinyLfu` at windows 1, 2, 5, 10, 20, 30, 40, 50, 70, 80%):

- **start**: the static window the product starts at (1%; `--start` for a planted window). A plant
  holds probation at its shipped share rather than re-splitting main, so on a planted cell the
  static sweep is not a like-for-like frozen reference; `climber-gate/frozen_matched.py`
  re-measures the frozen point with the plant's geometry (its `CELLS` list is hard-coded; add the
  cell) when the frozen point itself is the question.
- **ceiling**: the best static window in the reachable range. The window cannot exceed 80% (the
  climber donates only the protected allocation), so a peak reported at 80% is flagged
  `peak-at-edge` and the true optimum may be unreachable by any window setting.
- **LRU**: the floor. Below LRU is a bad sign, but "above LRU" is not the bar; the recorded
  families were built to attack the density signal, and most sit far above LRU while pinned.
- **Belady** (`--belady`, `opt.Clairvoyant`): the structural limit. `structural = Belady - ceiling`
  is what no window setting recovers; it belongs to admission, the sketch, or the SLRU split, not
  to the climber.
- The ceiling is the best **static** window, not an upper bound: an adaptive window can beat every
  fixed split where the workload's phases want different ones, so a negative gap
  (`beats-static-ceiling`) can be a real result marking a cell where adaptivity is earning. It can
  also be the grid: the sweep has ten points and a peak between two of them (say 15%) reads as
  beating both. Before crediting adaptivity, re-sweep densely around the peak with
  `regret.py --windows 0.12,0.15,0.17` (the extra windows are merged into the cached
  `<trace>.anchors.<size>.json`, so the ceiling and the position regret update in place).
- **the reactive arm** (harness variant `reactive`): the complexity anchor. Where it ties or beats
  the shipped machine, the density tier and its goal-metric layer are not earning their keep on
  that cell. It runs the reactive tier at every size, so it has no trajectory and is compared on
  its row alone.
- Anchor fidelity: the reference sketch's aging matches the product's at powers of two and runs up
  to 2× slower elsewhere (`rules/simulator.md`), so prefer power-of-two maxima; the 4097 side of a
  tier straddle carries a small offset in `start` and `headroom` that the 4096 side does not.

Per arm, `regret.py` reports `gap = ceiling - cache` (the pp number gate rows are barred on),
`headroom = ceiling - start` (the prize the climber exists to win), `closed = (cache - start) /
headroom` (the fraction of the prize captured; negative means moving hurt), and `missx`, the
relative extra misses a user pays (`(miss_cache - miss_ceiling) / miss_ceiling`). Rank cells by
`gap`; a small gap on a low-hit-rate cell can still be a large `missx`, and `closed` is what
separates "did nothing" from "did the wrong thing".

Two cell types, read differently. A **prize cell** has headroom (the start is not the optimum):
the question is whether and how fast the climber captures it. A **hold cell** has none (the 1%
start is already optimal, as on the frequency-favorable real corpus): the only thing the climber
can do is leave, so any gap is the cost of moving, and the question is what made it move. A hold
cell becomes a descent test by planting the window (`--start 0.3` with the harness), which is the
only instrument that asks whether the climber can come back down; the frequency-favorable corpus
reads clean by default because it never has to.

With a harness tree the trajectory (one `climb` line per density sample) decomposes the gap:
`pos_regret = mean_s (ceiling - static(window_s))` is the cost of *where* the window was, split
at the settle point into transient and steady; `residual = gap - pos_regret` is what the timing of
the moves cost (churn, phase interaction) or won. The static curve is the whole trace's, so on a
phase-structured workload the split is a blend and the per-sample `hr` against the six-block
window profile is the finer instrument. `n` is the number of decisions the trace held; under 40
the cell prices convergence, not the machine, and the row says `SHORT`.

Two seeds, kept apart: the spec's `seed` is the trace instance (regenerate with `--seed`, or
`regret.py --seed-override`, when a verdict is contested, as the gate does with 11/13), and
`--seeds` are the cache's admission draws (`-Dcaffeine.climber.seed`, harness only), which is what
"eight seeded runs" and "read seed by seed" refer to.

## The failure classes

Name the class whose repair would close the cell (the primary), and any consequence as secondary.
Signatures are `regret.py` fields; "responder" is the machine state that should have moved and
did not, in the debug line's vocabulary (`max win hr s mode adj wh mh ph stable auditWait rung
left arung acs pcs undo anchorW anchorR ema dev hold fresh shortfall ret auditbar wbase wbar`) and
the object that owns it (`hill-climber.md` §4's write-owner map). "Recorded" names an instance
already in the record so a new witness is compared to it, not re-derived.

| # | Class | What it is | Signature | Responder | Owner | Recorded |
|---|---|---|---|---|---|---|
| 0 | **structural** `structural` | no window setting closes the gap; the loss is admission, sketch ranking, or the SLRU split | `structural` large, `gap` small; or `headroom < 1` and `peak-at-edge` | none in the climber | policy structure | `norank_rep_r6` (the sketch cannot rank six-reference keys); the fixed ~19.8% probation (`slru_adaptivity` study, §5) |
| 1 | **wrong equilibrium** `wrong-equilibrium` | converges and holds a position whose static value is below the ceiling | `pos_l3 ≥ max(1, gap/2)` (wrong at the end), law wants the opposite of `needed` or nothing (`rest_err` sign vs `w* - win_l3`), `steer` dominant | `DensityClimber.steer`'s zero (`ln(d_w/d_m)`; the average-vs-marginal rest point, §2.1) | controller | `cp_w097@16384` resting near 48% against a 10% optimum; `ds1_1M` 13–22% vs 2%; `flood_j100`; `P3`/`fiu_webmail` resting below a non-convex peak; `crestpast`'s drift (a caught band makes the window denser than a bulk-filled main, so the law rests above the crest and walks a park away from it) |
| 2 | **slow convergence** `slow-convergence` | the right position is reachable and reached, but late; or the profitable phase ends first | `pos_transient` dominant, `progress` toward `w*`, late `arrival`; `/walk-paced` when the move happens through probes | `Step.size`/`DENSITY_GAIN` (steering-paced), the sample period, or the ladders and `AuditClock` waits (walk-paced); actuator-limited when `adj` far exceeds the realized `Δwin` (`BoundedLocalCache.QUEUE_TRANSFER_THRESHOLD`) | controller gain, or exploration cadence; policy structure when actuator-limited | the 13–16 sample descent from 80% (§5); `rep_r6` escape times spread over 91 samples; mixture round-3 escapes at ~100 samples; the ~2-sample-period lag limit on phases |
| 3 | **masked signal** `masked-signal` | the observed statistic reads healthy while the harmed population's misses are invisible to it | as class 1 with `probes = 0` and `steer` dominant (sighted); `wh` above the bar from traffic worth nothing at the margin; a rider that keeps a starved region "sighted" | `Sample.windowHits/probationHits` attribution, `Reading.steeringError`, the starvation bar (`MIN_SIGNAL_SHIFT`) | controller (estimator) or the goal-metric layer that must judge what density cannot | `whisper` (the trickle keeps the window sighted); `deadphase` rider (six window hits authorize the maximum step); `flood_j100` (dense per slot, zero at the margin); `veilmoat` (whisper's mask on shallowmoat's terrain: no starvation probe arms, so both 2026-08-15/16 repairs are unreachable and every seed takes the audit path, ~88 samples; a dose note); `absolve`'s walks (the verdict confirms on the lure's hits at 2,200–3,400, unattributable and worthless above the lure's knee); `entry_duty` (the Sol round, 2026-08-21: a worthless trickle rider square-modulated at period 13.5 keeps the window sighted over a 40–50% prize, zero probes, `hybrid` ties `noaudit` at 17.2–17.9pp under the ceiling on 8/8 cache seeds, trace-seed-adjudicated at 149/211/313; a dose note on the `posjam_d0` gate row, spec `audit-regret/specs/entryduty.json`); `mainsat` (round 7, 2026-08-21: the main-side mirror with an interior peak, main dense and sighted and worthless at the margin, so density pins the floor (`noaudit` 21.4 against 35.8) and no probe can arm; the equilibrium audit owns it as it owns `whisper`, a ~48-sample approach at any horizon; spec `audit-regret/specs/mainsat.json`, a sentinel row) |
| 4 | **insufficient exploration** `insufficient-exploration` | the machine holds a position and never tests far or often enough to find the better one | `park`+`hold` dominant, `audits` rare, `max_wait` 128–512, `max_rung` 64; `/reach` when every walk ends `crash`/`fail` short of the ceiling window (`repeat_arms`, `top_frac`/`floor_frac`) | `AuditClock.stillSamples/waitSamples`, `Ladder.rung`, `refractoryLeft`, `Anchor.held/freshLeft`, `Walk.samples` and its crash bar | recovery layer | `metronome` (positional clock jam); H4-C1 ratchet; the r3 blind-corner lockout; the moat (a valley deeper than the audit's crash bar); `rep_r6`'s top corner re-probing every refractory cycle; `shallowmoat` (a valley 2pp deep and 57% wide: the first-round walk adjudicated three or four strides out and never reached the prize; repaired 2026-08-16, now a gate row); `absolve` (a lure pulsed at 16 samples inside a wide valley: one walk per period, one rung per period, kept confirms reset the rung, escape at s144–159; round 2, gate row at 128 samples); `latebloom` (round 7, 2026-08-21, gate rows: a flat prelude spends the audit schedule through two honest budget failures, `auditWait` 32 → 128, then a prize caught only from 50% arrives; the stand-down that detects it reschedules nothing, or the undo's retreat covers it, so the held floor waits out the 128 (54.90 ± 0.04 against 66.4, `noaudit` 53.39); the phase-1 ladder is the family's shape and its worst cells send the next audit down the alternation from density's ~19% rest into the wait) |
| 5 | **oscillation / chasing** `oscillation` | the loop follows the workload's phase instead of stabilizing; a fixed window at the mean position wins | `settle` never, `flips` ≥ n/4, `residual` > `pos_regret`, `move` high; six blocks alternate | `Step` decay (reactive) / the proportional density step under alternation; `STABLE_BAND_FRACTION`; the stillness clock decaying instead of arming | controller (no damping or prediction) | `widepin`; `phases_d050` (grid-locked, bimodal); `posjam_d0`; `corda_large`'s churn (10.5pp against any fixed window, §1); `hazefloor` (rowed round 4 at 320 samples: the top-corner retention cycle with a ~105-sample period, the corner audit's crash at the cliff, the C2 discard, density's slide, the floor's failing ×1 walks and the rung-16 re-crossing; 41.1 against LRU 49.2); `parkveil` (round 6, gate rows: a zipf↔band square-wave mix at period 13 whose fixed 50% window beats every adaptive arm — the density rest point alternates with the mix, so the ratio's phase immunity fails, and the machine chases between shield-length parks; 53.1 against LRU 66.0) |
| 6 | **memory failure** `memory-failure` | evidence that a decision was wrong has been discarded, so it is repeated | `/stale-claim`: `bad_veto` (a veto drags to an anchor whose static value is worse, `anchorR - ema` large); `/repeat-probe`: `repeat_arms` (an arm re-launched from a band and direction that already crashed or failed) | `Anchor.window/rate` and `Rates` (the claim), `Ladder.crashStreak`, `sample.previousHitRate` after `resized`, the frozen `Walk.base*` | recovery layer; policy structure when the memory is the sketch's aging | the stale away-anchor claim (a rate claim earned before a regime shift; fixed by the stand-down, `regimeramp`/`staleclaim` rows); H4-C1/adv4-F2 (a streak cleared by the other layer's ending); the undo ledger's creep at a starved corner (fixed by the integral ledger, §5); `shallowmoat` (a mid-depth confirm the density arm reversed in the same sample reset the ladder, so the escalation never started; repaired 2026-08-16: a reversed confirm now deepens the ladder, and one at the deepest commitment that the goal metric confirms parks); `absolve_p8` (a confirm density agrees with on its own sample and dismantles within the lure's 8-sample period is rewarded, so the rung reads 1 or 2 on 223 of 255 samples and never reaches 32: the `deferreward` shape absorbing and deterministic; round 2, gate row at 256 samples); `ghostclaim` (the stale away-anchor claim's other case: a regime shift lands with the window still and off the anchor, the stand-down keeps the previous regime's claim, and it was then the audit's confirm reference (a walk that finds +30pp at the top fails at budget) and the guard rail's reference (a `bad_veto` to a position worse in the new regime, held there); round 3, gate row at 128 samples; repaired 2026-08-17: the walk is measured against the smoothed rate it leaves, `Walk.baseSmoothedRate`, and the claim is the rail's alone; the discard shape died on the moat rows); `parkveil` (round 6: the discard's cost side — a validated audit-grade park at the top corner survives exactly one fresh-park shield, then the first post-shield phase flip fires `isWorkloadShift` and the stand-down discards the claim at the anchor, so the position is re-derived through the full ladder every cycle; arm-independent, the census C3 observation's first cost-bearing cell, and the rail cannot hold it because the phase swing prices its margin at 62–101pp; the signal-classification wall); `mainsat`'s plants (round 7: the C2 arrival-discard shape on the rail's return; a veto returns the window to a correct anchor above a cliff, the landing sample's +11pp recovery reads as a workload shift because a veto's return arms no `isParkTest` cover, the stand-down discards the claim on the anchor and density slides off again every ~19 samples; `arrive` breaks it, +5.3/+4.6 on the plants, bit-identical unplanted) |
| 7 | **irreversible damage** `irreversible-damage` | by the time the mistake is detected the valuable residents are gone; restoring the position does not restore the rate | `undo_deficit` ≥ 2 (post-undo `hr` below `static(win)`), or a recovered window whose `hr` lags for samples | `undoRemaining` (full undo), the transfer cap, demotion into probation (the walk's own demotions) | recovery mechanism (undo policy) or policy structure (region transfer displaces) | `corda_large`'s displaced residents; `s3_25k` (171pp of 246pp of descent undone by one failed probe); the deadphase rider growing the window into main |
| 8 | **aliasing** `aliasing` | two regimes produce the same observables at the decision but need different actions; the machine must fail one | a **pair**: one terrain, one bit flipped, `search.py pair` shows identical fields at the decision sample and divergent optima | the statistic at that decision (starvation bar, crash bar, stillness, first-difference reversal) | controller (a new estimator) or a priced trade (design) | a correct floor is as still as a trap (the `blindprobe`/`norefr` negatives, §5); a monotone drawdown into the moat is a damaging walk at the crash point; a big move because the rest point is wrong is a big move because the workload changed (`margrest`, §5); window-irrelevant modulation (`whisper_mod`, `posjam`) |
| 9 | **premature commitment** `premature-commitment` | the machine parks, confirms, or anchors on a transient (warmup, ramp, trend) and the shield holds it there; or its verdict commits at the wrong position of an honest walk (where the streak completes rather than where the walk was best) | `early_confirm` (an `AUDITCONFIRM` before sample 24 at a position below the ceiling), long `park` after | the audit verdict (`AUDIT_CONFIRM_STREAK`, `beatBase`), `AUDIT_WAIT_FIRST`, `Anchor.freshLeft` | recovery layer | the cold-start misconfirm at ~33% (`flatctl`, workspace-only; `regimeramp` is its rowed sentinel); `climbtrend`/`saw_p40` (a trend clears any raw streak); `shieldtrap`; `absolve` through 64 samples (the calibration audit's level-test confirm on a phase step in main's hits against a stale reference, then the park and the down-first alternation: shallowmoat's basin B forced on every seed); `crestpast` (round 4, gate row: the calibration audit crosses a crest on its first stride and confirms on its fifth, `AUDIT_CONFIRM_STREAK` completing at stride 4 and `AUDIT_COMMITMENT` holding the verdict to 5, so it parks three strides past the crest, and the return walk's fifth stride is on the cliff side where the level test crashes; classes 1 and 6 co-requisites of a repair, since a confirmed crest is lost within one audit cycle); the far-crest face (`s_fatbase`, `regimeramp`'s misconfirm at ten times its dose: the calibration walk declines from its first stride into a valley and every sample still clears a reference frozen while the cache filled) |
| 10 | **tier / scale discontinuity** `tier-discontinuity` | the same shape at maxima straddling 4096 (law and cadence switch) or where a size-relative floor crosses a reuse distance | the regret jumps across a size while the anchors are continuous | `DensityClimber.appliesTo`, the 2%·max floor against `d` | controller (tier gate) | the D2 straddle rows (`strad_p8` at 4096/4097); the mixture trap's scale-relative floor |

Not classes, and excluded before any classification: **warmup** (a short trace; regenerate longer,
compare six blocks not thirds), a **basin lottery** (a bimodal cell read from an unseeded mean;
adjudicate seed by seed), the **alignment offset** (a phase grid commensurate with the sample
grid; shift the trace start), and a **bar inside the spread** (a bar is a margin over a
re-derivable anchor, never a bare level).

**Out of reach of this instrument, not absent from the machine.** `product.Caffeine` builds with a
maximum size or weight and nothing else: no `expireAfter*`, no `Expiry`, strong references. So no
cell here can produce an expired or collected entry, and any regret that only arises when the cache
reuses a dead entry's node is unsearchable in a round, not a negative result. That quarter is real:
the expired-reload miscredit (2026-08-24) is a class 3 shape reached through a TTL, and it collapsed
the converged window from 402 entries to 5 at a maximum of 512. It was found and priced in an
in-JVM harness whose recipe is in `design-decisions.md` under the reload credit. Do not read a
round's silence as evidence about anything gated on expiry or reference collection. Reaching it
here would mean giving the simulator's policy a TTL, which trades the corpus's trace fidelity for
the coverage; that is a call to put to Ben, not to make inside a round.

## Which layer is responsible

The decision procedure `regret.py` encodes as hints, to be applied by hand on the trajectory:

1. Is the window still in the wrong place at the end (`pos_l3` large)? If not, the loss was
   transient: class 2 (or 5 if it never settles).
2. If it is, what does the steering law want there? `rest_err` is `ln(d_w/d_m)` over the last
   third: positive wants a larger window. Compare with the direction the ceiling lies in.
   - Law agrees with the ceiling and the last third is quiet (`park`/`hold`/`veto`): something
     above the law is holding the window. Recovery layer: 4, 6, or 9 by the fields.
   - Law agrees and the last third steers but the window does not move: the floor clamp, the
     top corner, or the actuator. `/reach` (4) or actuator-limited (2).
   - Law disagrees or is silent: the law's own rest point is wrong. Controller: 1, or 3 when the
     signal is being fed by traffic worth nothing at the margin.
3. Confirm with arms, never with the shipped machine alone: `reactive` (is the density tier the
   cause?), `noaudit` (is the goal-metric layer helping or hurting here?), `nocal` (the
   calibration audit), and `--start` plants (path dependence: a rest point that depends on where
   the run began is a memory or commitment failure, not a law failure). A gap that every arm
   shares is not in what the arms differ on (the density law, the audit layer); look at the
   machinery they share (the sample period, the transfer cap, the region structure) or at the
   anchors (`structural`). `climber-gate/marginal.py` answers class 1 without a controller: where
   the average error rests versus where the hit rate peaks. `exposure.py`/`blindlock.py` answer
   class 4's clock questions.
4. Where the anchors themselves say the ceiling is unreachable (`structural`, `peak-at-edge`), stop:
   the finding is routed to admission/sketch/SLRU work, recorded, and not chased here unless asked.

The hint strings `regret.py` prints (the `hints` column; across seeds each carries a vote count,
`name xN`), and the class each points at. `search.py`'s `primary` skips the flags when it names
the class a shrink must preserve.

- Flags: `SHORT` (n < 40), `clean` (|gap| < 1), `beats-static-ceiling` (gap ≤ −1),
  `peak-at-edge`, `left-optimum` (a hold cell with a gap).
- `held-against-the-law`: wrong at the end, the law wants the right move, the last third is
  quiet; the recovery layer, refined by `memory-failure/stale-claim`, `premature-commitment`, and
  `insufficient-exploration` when their fields fire (4, 6, 9).
- `insufficient-exploration/reach`: wrong at the end at the floor or top corner, or ≥3 walks
  ended without a confirm while a move was needed (4).
- `steer-blocked`: wrong at the end, the law steers, nothing moves; the actuator or a clamp
  (2 or 4).
- `wrong-equilibrium`, `wrong-equilibrium/masked-signal`: the law rests wrong; the suffix when no
  probe was ever armed and steering dominates (1, 3). `wrong-equilibrium/mild` is a residual
  position error under the wrong-at-end bar.
- `slow-convergence`, `slow-convergence/walk-paced` (2); `oscillation` (5); `irreversible-damage`
  (7), `irreversible-damage?/phase-confounded` when the deficit is under the run's own scatter;
  `memory-failure/repeat-probe` (6).

Every hint is a pointer; the classification is the trajectory read against the table.

"Classify the signal, not the workload" governs every proposed remedy: a fix may test whether the
machine's own measurement can be trusted (a starved region, an unresponsive hit rate, a sample too
small to mean anything); it may not recognize a workload shape and pick a window for it.

## Instruments

All under this directory unless noted; the runners come from `climber-gate/` (`run.py`,
`harness.py`, `marginal.py`, `startwin.py`, `exposure.py`, `blindlock.py`, `real.py`,
`floors.py`, `gate.py`).

- **`workload.py spec.json --out t.lirs [--max N] [--seed S]`**: the compositional generator. A
  spec names members (`zipf`, `loop`, `uniform`, `scan`, `pairs d|dreq k`, `trickle gap`,
  `rotation n step period`) and segments that give each a share, optionally modulated over the
  segment (`sine`/`square`/`ramp`). Sizes and distances are `× max` except `dreq` and `gap`, which
  are requests; durations (`samples`, `period`) are density samples of `4 × max` requests;
  re-references land at their exact global index (gen.py's delay ring), and each member owns a
  disjoint key space. `--describe` prints the decision count before anything runs. `specs/` holds
  search seeds (mixture, phases, whisper, deadphase, norank, drift), not gate rows; the gate's
  canonical generators stay in `climber-gate/`.
- **`regret.py <trace|spec> --size N [--fmt f] [--seeds 1,..,8] [--variants hybrid,reactive,noaudit]
  [--start f] [--belady] [--windows 0.15,0.25] [--max-override N] [--csv out] [--json out]`**: one
  cell's anchors (cached beside the trace; `--windows` sweeps extra static windows and merges them
  into the sidecar, the dense re-sweep for a peak between two swept points or a cliff the linear
  interpolation misreads), per-arm regret, per-seed trajectory signatures, the aggregate hint
  vote, and a CSV row. `--traj FILE --static '1:hr,2:hr,...' --lru x` analyzes an existing
  dump alone. `--variants` (other than `hybrid`), `--start`, `--seeds`, and the trajectory itself
  are harness knobs; on a stock tree they are silently ignored, so every arm is the shipped machine
  from 1% and `--seeds` runs are unseeded draws, while the anchors still take `--start` at its
  word and a stock-tree plant reports `closed` against a start the cache never had.
  `--variants density` forces the density tier below 4096, a counterfactual for diagnosing a
  straddle, not the shipped machine at that size.
- **`search.py`**: `eval <specs|dir> --csv` screens a batch and ranks by gap (resumable; a
  (label, arm, size, seeds) row is never measured twice, so one spec at two maxima is two rows,
  `--size` runs a geometry at another maximum, which is what a 4096/4097 tier straddle wants, and
  the same cell at a second seed is measured again rather than read from the first seed's row; the
  label is the spec's basename, so keep basenames unique within a round);
  `mutate base --set path=v1,v2 [--set ...] --out DIR` writes a neighborhood or a dose ladder as
  spec files (scale is a `max` axis here, since every size in a spec is relative to it);
  `shrink base --csv --out min.json` is greedy delta-debugging that keeps a reduction only while
  the gap stays in `[keep·g0, g0/keep]` **and the primary class is unchanged and the trace keeps
  ≥ 40 decisions** (the class is the base row's first firm hint, an uncertain `…?/…` hint yielding
  to a firm one; a member drop that would leave a segment without a positive share is skipped; and
  the hint is coarser than the mechanism, so read the witness's trajectory before trusting it, as
  round 3's `ghostclaim` shrink kept "wrong-equilibrium" while dropping the member the mechanism
  needed) (a witness made by shortening measures how long the climber takes, and a
  reduction that amplifies the gap has changed the failure); the class it preserves is the base
  row's primary hint at the seeds given, so shrink at `--seeds 1,2,3` when the base is bimodal,
  and on a stock tree there is no class and only the gap band holds;
  `bisect base --knob path --lo --hi --bar` locates a dose transition and reports cliff or
  gradient, assuming the gap is monotone in the knob (read the printed ladder; if it is not,
  `mutate` a ladder instead); `pair a b` runs an aliasing pair and prints the decision sample's
  fields side by side at the first divergence, from the first seed's `hybrid` dumps.
- **Setup for trajectories and arms**: `git worktree add --detach <workspace>/tree HEAD`,
  `python3 .claude/skills/climber-gate/harness.py apply <workspace>/tree` (then `verify`; the two
  counts must match — 52/52 as of 2026-08-21, growing as harness hooks are added),
  and `CAF_TREE=<workspace>/tree` on every call. Never commit the worktree's edits. A stock tree
  yields end-to-end regret only (`no trajectory`), which is enough for a screen and useless for a
  classification. Maxima in the millions need `CAF_EXTRA=-PjvmArgs=-Xmx26g` for the eleven-policy
  anchor sweep; the gate's sizes do not.
- **Cost**: at 8192 a 60-sample cell is 2M requests; anchors plus one product run take ~10 s
  with a warm daemon, so a 30-spec screen is minutes and a full round with shrink, seeds, and
  neighborhoods is an hour or two of sequential runs. Runs are sequential by construction; do not
  parallelize simulator processes in one tree, never `killall java`, keep CSVs append-and-resume
  as the tools already do, and prefix long commands with `caffeinate -i`, which is best-effort
  from an agent shell (`rules/simulator.md`), so tell Ben when a round will run past an hour and
  needs an awake session on his side.

## The round

Workspace: `.local/experiments/audit-regret-<yyyy-mm-dd>/` with `LEDGER.md` (one row per
candidate, status updated in place), `tree/` (the harness worktree), `specs/`, `traces/`,
`dumps/`, `data/`. Report: `.local/audits/<model>/audit-regret.md` (`docs/audit-output.md`).
Both survive rebases; nothing checked in may reference them. The commands, in the round's order,
run from the workspace (`regret.py`/`search.py` default `--traces-dir`/`--dump-dir` to `./traces`
and `./dumps`):

```
A=$PWD/.claude/skills/audit-regret; G=$PWD/.claude/skills/climber-gate
W=.local/experiments/audit-regret-<date>; mkdir -p $W/specs $W/data $W/traces && cd $W
git worktree add --detach tree HEAD
python3 $G/harness.py apply $PWD/tree && python3 $G/harness.py verify $PWD/tree      # counts match
export CAF_TREE=$PWD/tree
python3 $G/<generator> ... --out traces/<row>.lirs                                   # 0: a gate row
python3 $A/regret.py traces/<row>.lirs --size 8192 --seeds 1
python3 $A/search.py eval specs --csv data/screen.csv --seeds 1                      # 2: screen
python3 $A/search.py shrink specs/<c>.json --csv data/shrink.csv --out specs/<c>_min.json  # 4
python3 $A/search.py bisect specs/<c>_min.json --knob <path> --lo <a> --hi <b> --bar 2 \
    --csv data/bisect.csv
python3 $A/regret.py specs/<c>_min.json --seeds 1,2,3,4,5,6,7,8 \
    --variants hybrid,reactive,noaudit --csv data/classify.csv --json data/<c>_min.json   # 5
python3 $A/search.py mutate specs/<c>_min.json --set <knob>=<lo>,<hi> --set max=8192,16384 \
    --out specs/nbhd && python3 $A/search.py eval specs/nbhd --csv data/nbhd.csv --seeds 1  # 6
```

**0. Baseline.** Record the tree (`git rev-parse HEAD`), build the harness worktree, and reproduce
one recorded gate row to prove the instruments read the same numbers: regenerate it from the
gate's generation block and pick a low-spread row (`straywall2_8192_d050`; `demoflood_8192` is
bimodal since the 2026-08-18 re-base and no longer qualifies), not a lottery cell, since the
recorded means are unseeded and a `--seeds 1` draw must land inside the row's spread. Read `hill-climber.md` §3 (the recorded families), §5 (the graveyard), §5's last
entries before proposing anything, and keep the §3 family list and the gate table at hand
for step 3.

**1. Propose.** Spawn the two proposal lanes in parallel, each returning specs plus a one-paragraph
prediction per spec: the class it targets, the state variable it expects to see fail, the terrain
argument (why the static curve has a better window than the machine will find), and the regret it
expects. The prediction is the finding's hypothesis; a spec whose regret appears for a different
reason than predicted is a new finding, not a confirmation.

- The **blind lane** gets `WindowClimber.java`, `hill-climber.md` §2 and §4 (the machine), the
  member/segment grammar, and the class table above. Nothing about §3, §5, or the gate table.
  Fresh eyes on the mechanism find shapes the recorded families anchor a sighted reader away
  from.
- The **sighted lane** additionally gets §3, the gate table and §5's "do not re-explore" list,
  with the instruction that every proposal must name the nearest recorded family and state what
  differs, and must not rebuild anything §5 killed.

Give each lane a target count (six to eight specs), a class quota when the argument names one,
and the mechanism-directed prompt: for each class, "construct the workload that maximizes this
class's regret against this machine, and say which line of `densityClimb` decides wrongly on it".
A lane writes specs; it runs nothing.

Dispatch both lanes on Opus (`model: opus`), each with an explicit time box of about twenty
minutes; dispatch step 7's evaluators on the session model (Fable), since refuting a finding with
runs and reading its trajectories is where round 1's errors slipped through on Opus (an evaluator
confirmed "the plant at 70% holds" without opening the trajectory, where it was one audit park
inside a 64-sample horizon, and let a post-tick misreading of the audit clock stand), and where
round 2's Fable evaluators corrected both writeups materially. The lanes differ by what they are
shown, not by model: a Fable proposal lane spent four consecutive 96,000-token thinking turns on
the open-ended design prompt and was aborted at the output-token cap after two hours with nothing
written, while the Opus lane on the same prompt delivered eight specs in fourteen minutes (round
2, 2026-08-16; the earlier Fable attempt at this task shape tripped a safety classifier on the
construction framing, 2026-07-30). Ben's direction, 2026-08-17 (either is fine; pick what finds
issues), replacing the 2026-08-16 one-lane-per-model rule. If a Fable lane is ever wanted for
proposal diversity, run it as a third, non-blocking lane with a different shape: three or four
specs, each written to disk the moment it is designed so the harness has tool rounds to deliver a
time-box message, and screen whatever it wrote when the round's screen runs; never wait on it.
Keep every prompt concrete about the domain it is in (simulator request traces, a cache policy's
hit rate, a window-sizing loop) rather than generic red-team language.

**2. Screen.** `search.py eval <specs> --csv screen.csv --seeds 1` on the harness tree at the
spec's own maximum (8192 unless the attack is about scale), so every row carries a hint. Apply the
validity gates before reading a gap: `n ≥ 40`, no `peak-at-edge` unless the attack is structural,
and a `headroom ≥ 1` prize or a deliberate hold cell. Keep every cell with `gap ≥ 2` (or
`missx ≥ 20%` on a low-rate cell) as a candidate.

**3. Deduplicate.** For each candidate, name the nearest recorded family (§3, the gate table, the
frontier sentinels) and the difference; a candidate that is a recorded family at another dose is
noted on that family's row and dropped from this round unless its dose is new. `pair` a candidate
against its nearest neighbor when the mechanism is claimed to be the same.

**4. Shrink and parameterize.** `search.py shrink` to the minimal witness (class-preserving), then
`bisect` the one continuous knob that carries the effect for its transition and shape, then
express the witness as a family with one to three knobs (dose, distance or period relative to
max, phase length in samples). Record the transition; a threshold and a gradient are different
mechanisms.

**5. Classify.** `regret.py` at eight seeds with `--variants hybrid,reactive,noaudit`, the
trajectory read per seed (a bimodal cell has a class per basin; the reactive arm has no
trajectory), the layer named by the procedure above, and the responder named in the debug line's
vocabulary. If the adjudicated class is not the hint the shrink preserved, the witness may hold a
different mechanism than its parent: shrink again from the parent at more seeds. Add `--start`
plants when the question is path dependence, `marginal.py` when it is the rest point,
`exposure.py`/`blindlock.py` when it is the clock. Where the arms tie the shipped machine, say so,
and name what they share.

**6. Neighborhood.** `mutate` the family's knobs one step each way and to a second maximum
(16384 or 32768; 4096/4097 when the tier is implicated), `eval`, and record the surface. The
neighborhood is what a fix will be judged on.

**7. Challenge.** Spawn one evaluator per finding (the session model, Fable) with the finding's writeup, its
CSV rows, the anchor sidecars, and the dumps, no source, told to refute it: is it warmup, a basin,
an alignment artifact, a peak beyond 80% or between two swept windows, a recorded family re-dosed,
or an anchor error? Every objection is answered by a run or the finding is downgraded.

**8. Fix, only when asked.** Present the classified findings and stop; Ben decides which class to
work, one at a time. A candidate change is the smallest one that closes the **class**: it must
close the whole neighborhood (step 6), not the witness, or it has fit the benchmark; it must hold
`gate.py` on the full battery, `real.py`, `floors.py`, and the reactive anchor column where the
change touches shared state; and it needs a fresh holdout per `hill-climber.md` §6 (check the
unspent inventory first). Two things at once is two rounds.

**9. Promote and record.** A confirmed family becomes a `/climber-gate` row (its spec under this
skill's `specs/`, generated by `workload.py` in the gate's generation block, a `gate.py` `CELLS`
row with its seeded record) or a frontier sentinel when it is accepted residue; its mechanism goes
to `hill-climber.md` §3 (a family) or §5 (a dated entry) with the class named; open directions go
to §3 and §5. Write the report; leave the ledger current.

## Directions carried from earlier rounds

What a round did not get to is written here rather than in its report, since a fresh round does
not read prior reports. Strike a line when it is done or dead.

- Real-trace instances of `shallowmoat`'s or `absolve`'s shape (a large flat population, a far
  recency band, a scan or a pulsed short-reuse population): a `climber-gate/marginal.py` static
  anatomy over the corpus, or a `real.py` pass, would say whether a wide shallow valley with a prize
  behind it exists outside the generator (round 1, 2026-08-15; still open after round 2). Round 3's
  screen-level answer from the 77-cell start-knob screen (w1/w2/w5/w10/w20 plus the ceiling): no
  density-tier cell is flat to 20% with its ceiling far above at 50–70%; every recency-favorable
  cell rises monotonically from 1% and the nearest valley is `arc_DS1@256k` (a 0.1pp dip, a 0.5pp
  prize). Only a full anatomy (the 30–70% windows) can close it.
- The anchor-fidelity discrepancy on flat-zipf-plus-scan synthetics: the product's parked position
  earned ~4pp more than `sketch.WindowTinyLfu` at the same window on a reference curve flat within
  0.5pp (`b_scarburn`, round 1), and the product held at its 2% floor earned +1.6pp over the
  reference's static 2% on the veil trace while three such cells beat every reference window by
  1.2–3.8pp (round 2), so small gaps on that terrain must not be read to the half-point until a
  same-window comparison settles whether it is admission jitter, sketch parameters, or the
  reference's own aging.
- ~~The masked-signal class beyond `whisper`: neither round's lanes constructed a main-side mirror …~~
  Closed in round 7 (2026-08-21): `mainsat` is the main-side mirror with an interior peak (the
  two-sided residency condition: the core stays out of the window at 80% and inside main at the
  peak, with a 52% scan holding the window's residency down), `noaudit` pins the floor 14pp under
  the dense-swept ceiling, and the equilibrium audit owns it as it owns `whisper`; rowed as a
  sentinel. No new class; the round-2 note on cheap keepalives stands for the window side.
- `s_scarburst` and `s_flashpark` are dose notes on recorded classes with open controls (a second
  trace seed, a neighborhood) (round 1). ~~Still open after rounds 2–5.~~ Round 6's `parkveil`
  supplied the class's independent second construction, period ladder and neighborhood, and
  family-ized it (the reactive-anchor phase-chase class); `scarburst`'s own grid caveat stands on
  its row. The `pair` instrument was exercised in round 2
  (`s_alias_band`/`s_alias_scan`): the pair's premise failed at the deciding field, window hits 30
  against 45 around the bar of 32 at the arming sample, so no class-8 pair has yet survived a screen.
- Two dose ladders in round 2 flipped by the parent terrain's own lottery rather than by the knob
  (the veil share below 3e-4; the lure share at ~1.6%): a regenerated trace jitters a basin
  selector that sits within ±2 hits of the starvation bar, so read the ladder's dumps at the
  decision sample before calling a transition. `workload.py` cannot overlay a rider on a fixed base
  trace (every member draws from one RNG stream); a per-member seed would remove that confound.
- ~~`regret.py`'s position regret interpolates the static curve linearly between swept windows …
  a `--windows` option for a dense re-sweep is the follow-up (round 2).~~ Done in round 3
  (`--windows`; on `demoflood` the 15%/25% points move the position regret 6.44 → 5.91 and the
  residual −0.51 → +0.02).
- `absolve` was repaired 2026-08-17 (`hill-climber.md` §5): both gate rows moved (24.77 → 41.46,
  27.95 → 46.29) by a repeat-confirm memory on the starvation ladder and a park's first audit
  following its walk; the period-16 witness at 256 samples reads 46.89 on 8 seeds and the family
  cells at 128 samples 43–44. Still open on the family: `absolve_p20` (a 2-of-8 lottery either way)
  and the period-16 form's audit crash on the lure's off-step at other doses (three of the
  neighborhood's period-32 cells at 64 samples).
- The proposal lane on the session model (Fable) stalled in round 2 (four consecutive 96,000-token
  thinking turns with two tool rounds between them, then the output-token cap aborted it after ~2
  hours; nothing written): the model, not the machine (continuous timestamps, no permission or
  classifier events); the blind lane fell back to Opus, which delivered in 14 minutes. The dispatch
  rule above now puts both lanes on Opus with a ~20-minute box. Rounds 3 and 4 ran the third-lane
  shape (three or four specs, each written to disk the moment it is designed, aimed at named lines)
  on Fable: three specs and predictions in nine and eight minutes, done before the screen; none
  survived either screen (round 3: `f_ghostlure` recorded, `f_cornerloop` peak-at-edge,
  `f_crestshelf` clean; round 4: `f_dipref`, `f_crestcliff`, `f_probetax` clean or beating the
  reference), so the shape works and its yield is a fair sample of two.
- ~~`hazefloor` (round 3): … a sentinel row needs an x4 run at fixed seeds, per-seed bars, a 60/65%
  re-sweep~~ Done in round 4: rowed at 320 samples (41.09 ± 1.80 at seeds 1–8; the re-sweep moved the
  ceiling to 52.27 @65%; reactive 36.5, noaudit 37.5; per-seed bars). Still open from that note: an arm
  with the crash-undo discard disabled, to say how much of the cycle is the discard and how much the
  corner audit's forced direction; a fix-side question.
- Two round-3 observations for the record rather than the round: after a regime shift on which
  everything works (the claim discarded, the audit walk confirms), the machine still spends ~27
  samples in the valley (16 of audit wait after the crash-scale swing plus a nine-stride ×1 walk;
  `ghostclaim`'s 16-sample-phase-1 neighborhood cells at n = 80: 10.6pp, all transient), the
  recovery latency's floor; and a walk armed in one phase of a large square-wave modulation
  crash-aborts against its own frozen base the moment the other phase returns, at the position
  that is right for that phase (`s_farecho`: the level test across a 20pp phase boundary; the
  recorded exit-bar threshold at an extreme dose).
- Class-8 pairs: round 3's `s_pairup`/`s_pairdown` held identical machine state through the
  decision by construction (a member at share 0.0 in the shared segment, only its reuse distance
  differing), so the `pair` instrument's premise can be met; the pair diverged at the shift sample
  itself (hr 0.30 vs 0.55) and the regret came from the stale claim rather than the shared
  direction bit, so no class-8 finding has yet survived a screen.

- Round 4's leftovers. `b_causepair_b` (a hot zipf phasing into main at s6 on a hold cell): the
  calibration audit's level-step confirm at 33% (`absolve`'s calibration mechanism), the follow rule
  sending the park's audit up into a crash and back; the reactive law 48.7 against the machine's
  42.9 ± 1.9 and noaudit's 47.0, so a reactive-anchor sentinel candidate of `scarburst`'s kind (the
  hold-cell face); the spec is in the round-4 workspace, not rowed. `s_fatbase` (the far-crest face
  of `crestpast`: `regimeramp`'s cold-reference misconfirm parking the window in a valley 21pp below
  the ceiling for 32 samples; 29.6 against 44.3 at 45%, LRU 37.2, deterministic) is the trend thread's
  largest measured dose and would make a sharper sentinel than `regimeramp` (whose priced cost is
  ~1.3) if that thread is worked; not rowed. The class-8 pair `b_causepair_a/b` met the pair premise
  by construction and failed it at the decision (+22pp against +6pp on the arm's first stride), the
  third pair in three rounds to do so. The arrival transient (a stride's sample under-reads the
  position just reached, so a walk's best sample is one to two strides past the static crest on most
  `crestpast` cells) bounds any walk's-best verdict and should be measured before one is built. The
  masked-signal main-side mirror is still untried after four rounds (the sighted lane's `s_fatbase`
  attempt at a probation poisoned before the arm did not produce it: no starvation probe armed).
  Three round-4 specs beat the reference on zipf-plus-scan terrain again (`f_dipref` −2.3, `f_probetax`
  −5.6, `s_thrashundo` −0.7): the anchor-fidelity note stands.

- Round 5's leftovers (the consolidation round, 2026-08-20; per-tenant instrument `tenants.py`).
  The `dilute-valley` family (h95±1 on the mixture terrain, class 3 reaching the audit walk's exit
  tests; witness deterministic at 8 seeds, hybrid 45.65 / noaudit 49.99 on the band tenant) awaits
  Ben's promotion call as a per-tenant sentinel bar. Unmapped: a d-ladder at fixed h95, the h-scan
  at 16384/d0.5 beyond h93/h95, K>2 tenants, and per-seed per-tenant readout on a bimodal instance
  (the second trace instance reads +2.29 with aggregate sd 0.25; `tenants.py` prints seed means).
  The admission-duel structural construction failed at 2–3% minority share (a uniform and a
  mild-zipf minority both admitted fine against a heavy zipf majority) — the class-0 consolidation
  story is unconstructed, and the masked-signal main-side mirror is still untried after five
  rounds. A weighted (byte) variant of any tenant family needs a weighted trace writer first;
  named residue, not scheduled. Dedicated-counterfactual arms above 0.5 of the minority's fraction
  carry warmup (n < 40); keep the load-bearing comparisons at fractions where n clears the bar.

- Round 7's leftovers. `latebloom`'s fix-side questions are the latency
  face (a stand-down that re-arms the clock; a hold that expires with the shield), and its
  boundary is the catch window beyond density's ~19% rest (dreq·miss/max ≳ 0.19) for the
  alignments where the stand-down releases the park, with the held alignments losing at any
  distance above the floor's residency. `mainsat`'s plants are the rail-return arrival discard
  (its second face); `arrive` is the measured arm and needs the plants on its benefit
  side before the C2 verdict is restated. The class-8 pair instrument has failed its premise at
  the deciding field five times in six rounds; a pair needs the divergent member invisible to
  `hr`, `wh`, `mh` and `ema` at the decision, which a ramped or boundary-introduced member never
  is, so the next pair must differ only in a reuse distance both beyond the current residency
  and inside the walk's reach, with the divergence opening during the walk. Class 7 still has no
  synthetic witness (`s_recoil`'s post-undo deficit sat inside its scatter at one seed; run it
  seeded and longer). `s_floorstraddle` is a sharper calibration-park sentinel than `regimeramp`
  (13.8 against ~1.3) if that thread is worked; not rowed. `b_slowfade`'s monotone-ramp attack on
  the rail needs an interior peak (size the lean core to leave room) before its `bad_veto`
  question can be asked. The dump file name carries no size, so `eval --size N` on a spec
  overwrites the spec's own-size dump (the 8192 `s_floorstraddle` dump was lost to its 32768 run);
  regenerate the geometry with `mutate --set max=` instead, which also keeps the sample count.

- Round 6's leftovers (2026-08-21). The masked-signal main-side mirror is now 0 for 8 across six
  fable rounds (the parallel Sol synthetic round's two deliberate MAIN mirrors also failed, 0 for
  10 across the lanes), and this round's two failures both died at the terrain rather than the mask:
  `s_mainmask`'s band was worth nothing at any window (its cell is a hold cell with LRU
  structurally above the whole curve) and `b_mainwhisper`'s peak fled to the 80% edge while the
  mask itself worked (probes = 0 for the whole trace with main sighted — the first construction
  to get that far; the spec is in the round-6 workspace for an interior-peak retry). The three
  constraints — main dense, marginally worthless, and an interior-peak curve — fight each other
  through the admission filter, and the next attempt should budget the far prize's reuse distance
  against the miss-rate-scaled residency the round-6 report works out. Two sighted terrains put
  plain LRU 20+pp above the entire static W-TinyLfu curve (long-distance k=2 bands admission
  refuses; `rep_r6`'s norank geometry at k=2) — check a screen row's LRU against its ceiling
  before reading its gap as climber regret. `parkveil`'s 240-sample period ladder was not run
  (the 60-sample ladder plus the base's p13 record cover the axis for a sentinel, not for a fix);
  its promotion edits were staged uncommitted for Ben. `s_valefloor` is preserved as a
  fast-running class-1 witness (a compressed micro-valley at the floor, 17.99/17.76/23.61 at
  1/3/5%, hybrid ≡ noaudit) if marginal steering (§5) is ever worked; the FIU mechanism itself (settling in
  the near basin) still has no synthetic reproduction — the machine overshoots instead.

## Rules of evidence

- A gap under 2.5pp needs eight seeded, paired runs; a bimodal cell is read seed by seed and never
  from a mean; seeded arms pair by seed (`regret.py`), unseeded comparisons rotate arms inside
  each run (`gate.py`), and neither compares across sweeps.
- A trace holds `requests / (4 × max)` decisions; classify nothing under 40 and re-check any
  finding at `repeat` ×2 before calling it steady state.
- Sign-uniform losses across a family are a mechanism, not noise, whatever their size, and a
  result that contradicts the round's hypothesis is written up as the result rather than
  reframed into one the data supports.
- Do not rebuild §5's graveyard, and do not reopen anything in it without a new
  mechanism; when a proposal resembles one, say which and what differs.
- Holdout discipline is §6's: freeze before tuning by LRU-only characterization
  (`run.py --anchors --variants ''`), spend once, and match the holdout to the change.
- One session's fix is presented before it is committed; docs (`hill-climber.md`, the gate table,
  `rules/design-decisions.md`) move in the same commit as the code.

## Reporting

`.local/audits/<model>/audit-regret.md`, with the metadata header (`Audit`, `Date`, `Commit`) and,
in this order: the screen table (label, size, n, LRU, start, ceiling@w, cache, gap, headroom,
closed, missx, hint vote); one section per finding (the family and its knobs, the minimal witness
spec, the transition, the class and secondary classes, the responder and owner, the arm table
at eight seeds, the neighborhood surface, the nearest recorded family and the difference, the
evaluator's objections and how each was answered, and the promotion status); the candidates
dropped and why (dedup, validity gate, unreproduced); and what was not tried. Findings that turn
out structural go in their own section addressed to the admission/sketch/SLRU owner. Everything
that changed the record goes into `hill-climber.md` in the same session, and the ledger row is
closed with the report's path.
