# The adaptive window climber

The deep reference for `WindowClimber`, the window-sizing loop `BoundedLocalCache` drives from
`climb`: what it must do, why that is hard, the shipped design, and the graveyard of alternatives
with the reasons that killed them. Read this before touching `determineAdjustment`, the climber
constants, or the simulator's climbing package. The quick rules live in `rules/design-decisions.md`.

This is a design record, not a project log. Every entry answers one of two questions — what the
machine does and why, or what was tried and why it failed — and anything that only answered "what
happened on a given day" has been removed. Where a number appears it is load-bearing: it is the
price of a step, the bar a family is held to, or the measurement that settles an argument. Numbers
that can be re-derived by running the gate battery are not kept here.

`wiki/adaptive-window.html` is the companion design document, written for an external reader judging
the algorithm and its evidence. Internal QA logistics — test and fuzzer names, mutation baselines,
gate tooling — stay out of it and live here and in `rules/testing.md`.

Naming: the **window climber** is the whole controller (`WindowClimber`); its tiers are each named
for their steering signal — the **reactive climber** (≤4096, cross-sample ΔHR) and the **density
climber** (>4096, within-sample density ratio). The density tier in full is a **goal-audited density
climber**: density steers, probes rescue its blind corners, and the goal-metric layer (anchor, guard
rail, equilibrium audits) polices what density cannot judge. "density arm/tier" below always means
the steering component, not the whole machine.

The organising result of the original failure atlas — the steering rule rests where
`capacityShare = hitShare`, not at the hit-rate optimum — is §2.1's identity, and the
marginal-steering thread it opened is settled in §5.

## 1. The problem

W-TinyLFU splits the cache into a recency-serving admission window (LRU) and a frequency-gated main
region (SLRU behind a TinyLFU filter). The best split is workload-dependent and non-stationary:
recency-heavy workloads want windows of 20–80%, frequency-heavy ones want ~1%, and real traces
switch regimes. The climber's job is to track the optimal split online using only signals the cache
can observe about itself, at zero cost to the request path.

Constraints that shape everything:
- **The counterfactual is invisible.** The cache observes only what its *current* split earns.
  What a bigger window *would have* caught requires ghost state (ARC's B1/B2), which Ben rejected:
  heavy, implies untrue things, and does not pay off as caches grow.
- **Moving has real cost.** A resize transfers nodes between regions (capped per maintenance cycle
  by `QUEUE_TRANSFER_THRESHOLD` with a multi-cycle carry-over) and displaces resident entries.
  On corda_large the *churn alone* cost 10.5pp — every fixed window scored 33.33 while the moving
  climber scored 22.8.
- **The goal metric is noisy at exactly the wrong times.** A phasey workload swings the sample hit
  rate ±10–20pp for reasons unrelated to the window, burying the window's marginal effect (often
  under 1pp) — a cross-sample hit-rate comparison then chases the workload's mood.
- **There is no setpoint.** Control-theoretic machinery (PID and friends) needs an error signal
  toward a target; hit-rate climbing has no target, so integrators accumulate noise unboundedly
  (the 2026-05 PID catastrophe: corda_lg −32 to −98pp, unfixable by any anti-windup variant).

## 2. The signals

- **Cross-sample ΔHR (the reactive climber, ≤4096):** bold-driver — keep direction while the hit
  rate improves, reverse otherwise, decay the step, re-anneal to a 6.25% step when |ΔHR| ≥ 5pp
  (`RESTART_THRESHOLD`). Goal-driven, so it can be *confused but never persistently
  wrong* — the restart keeps it exploring. Weaknesses: churns on flat/phasey curves (corda), and
  cannot cross wide plateaus deliberately (bold-driver flips on any sub-noise negative ΔHR, turning
  a plateau crossing into a random walk).
- **Within-sample density ratio (the large tier's primary signal):**
  `error = ln(windowDensity / mainDensity)` with `windowDensity = sample.windowHits/windowMaximum`
  and `mainDensity = (sample.hits − sample.windowHits)/(maximum − windowMaximum)`; step
  `sign(error) · min(0.30·max, |error|·0.03·max)`, window floored at 2% of max. Both densities are
  measured in the same sample, so the workload's phase cancels in the ratio — this is what makes it
  immune to the swings that defeat the reactive climber, and why it converges recency workloads to
  their static-window ceiling (OLTP 1%→~21%, metaCDN, fiu_homes, corda's collapse fixed).
- **What density cannot see:** it is *resident-only* and measures *average* (not marginal) value.
  Two consequences: (a) **bias** — window hits are cheaper to earn, so frequency-optimal traces
  give back ~0.5–2.5pp typically (worst observed 4.3pp: websearch2@4M holds a ≳10% window where
  static-1% ≈ reactive wins, a fresh-holdout find; all still beat LRU by 11–61pp,
  that cell by +24); (b) **blindness** —
  a region earning ~nothing has an unmeasurable density, and the signal will happily hold or push
  it further into nothing forever.
- **Signal hygiene (what feeds the counters):** hits are recorded in `onAccess` (read-buffer
  drained, so real hits are ~5% lossy single-threaded — the drain was historically triggered only
  by the offer *after* the fill; the buffer now reports full on the filling offer so the loss is
  recovered), misses in `AddTask`. Only genuine usages may count: async load completions replace
  **quietly** (no sketch increment, no hit counters — the UpdateTask only finalizes weight/expiry).
  When loud, each async miss injected one synthetic, write-buffer-lossless, window-attributed hit
  plus a doubled per-load admission frequency: the reactive tier wedged on phase shifts
  (corda+loop @ 512 −12.7pp, deterministic) and the density tier read miss-heavy async traffic as
  window-dense (w50 −38.6pp). Any new write-path task that calls `onAccess` must decide
  loud-vs-quiet explicitly — user-initiated writes are loud, internal bookkeeping is quiet.

### 2.1 The control-theory map

The density tier was derived from measurement, not from a textbook, but it lands on a named
architecture: a **supervisory switching controller** with **hysteresis** and **state-dependent
average dwell time**, wrapped around a **certainty-equivalence** inner loop, with an explicit
**dual-control probing** term — an event-triggered, non-dithering **extremum-seeking** scheme.
The vocabulary is worth carrying because three of the design's empirical facts fall out of it as
derivations, and because it names what a proposed change would be breaking.

| Mechanism | Formalism | Reference |
|---|---|---|
| `DensityClimber.steer` on `ln(d_w/d_m)` | certainty-equivalence inner loop; P-on-error into an integrating plant = I-on-the-balance-condition | Åström & Wittenmark |
| the log ratio itself | exponentiated-gradient step on the capacity simplex (scale-free by construction) | Kivinen & Warmuth 1997 |
| its rest point vs. the optimum | average- vs. marginal-value equalization | Stone, Turek & Wolf 1992 |
| anchor + guard rail | hysteresis switching; `VETO_MARGIN_MIN` is the constant `h` | Morse, Mayne & Goodwin 1992; Hespanha, Liberzon & Morse 2003 |
| `VETO_STREAK`, `AUDIT_COMMITMENT`, `Ladder.commitmentDepth`, `Anchor.freshLeft` | dwell time | Morse 1996/1997; Liberzon 2003 |
| the doubling ladders | average dwell time with chatter bound `N₀` | Hespanha & Morse 1999 |
| probes and audits | dual control — steering has no *dual effect*, the probe supplies it | Feldbaum 1960–61; Bar-Shalom & Tse 1974 |
| the probe verdict (retain unless refuted) | unfalsified control — no plant model, data from the active controller only | Safonov & Tsao 1997 |
| starvation bar | loss-of-persistent-excitation detector | Boyd & Sastry 1986 |
| confirm / veto streaks | sequential change detection | Page 1954; Basseville & Nikiforov 1993 |
| terrain B, the moat, the rung-scaled stride | non-local stability of extremum seeking (escape needs large-amplitude excitation) | Tan, Nešić & Mareels 2006 |

Three consequences worth stating outright, because each replaces a fact currently carried as
folklore:

1. **"There is no setpoint" is true of the goal metric and false of the loop.** The command is a
   *change* in window size and the cache accumulates it, so the plant is a pure integrator and
   proportional-on-`error` **is** integral action with setpoint `error = 0`. The 2026-05 PID
   catastrophe was not "integrators explode here" — it integrated `HR − target`, an error whose
   zero was *invented*, through a plant that already integrates. The density arm integrates an
   error whose zero is a physical balance condition. The design decision was to move the
   integrator onto a signal that has a true zero, which is why no anti-windup variant could
   rescue the old one.
2. **The rest point has a name and the recency bias has a derivation.**
   `error = ln((H_w/C_w)/(H_m/C_m)) = logit(hitShare) − logit(capacityShare)`, so `error = 0`
   ⟺ `capacityShare = hitShare`: **average-value** (proportional-share) equalization. The
   optimum equalizes **marginals**, `H'_w = H'_m`. For hit curves concave through the origin,
   average ≥ marginal, and the region with the larger average−marginal gap is over-allocated;
   the window is an LRU serving tight reuse, so it saturates fastest and has the larger gap.
   That *derives* the documented give-back ("frequency-optimal traces give back ~0.5–2.5pp") and
   its direction. It also says what the goal-metric layer is: not a patchwork, but the
   correction term for a bias with a closed form. Terrain shape B is simply non-concavity, where
   any first-order matching rule rests self-consistently — F-4 is the definition, not a surprise.
3. **The ladders imply a duty cycle, and the duty study measured the real one.**
   Worst-case arithmetic (a 17-sample walk against each rung) overstates the machine 2–4×: over
   6193 density samples on the 49-cell battery the mean **armed** excursion is 4.2 samples
   (starvation) and 7.7 (audit), and the occupancy split is `steer` 42.2%, `park` 25.9%, audit
   excursion 19.0%, starvation excursion 5.9%, blind-corner `hold` 6.1%, `undo` 1.0% — the
   machine commands nothing (32.0%) more often than it explores. (R4-F1's "6% → 43%" shieldtrap
   occupancy was the round-3 clock; at the shipped tree that cell reads 13.4% park + 13.4%
   walk.) A crash-prone workload sustains
   audit duty indefinitely (~11% at cycle ≈7.7+64: `crashnoise_a12`, `whisper_mod_a12`,
   `h4c1_attack`), because the wait reaches 512 only through completed deepest-rung failures
   while a crash deliberately keeps the ladder cadence — R4-F1's shape, structural. Two
   refutations from the same study: **the constants are not derivable from one declared duty
   number** (a declared audit band recovers two of the four), and **no duty or dwell bound can
   see the deferral defects** — H4-C1 and adv4-F2 violate the schedule's *provenance*, not its
   magnitude (`maxOverdue` measures 0 through the whole 130-sample H4-C1 pin). What the duty
   budget does generate is the C2 *progress* clause (§6).

Two caveats so the map is not over-claimed. Morse's hysteresis lemma bounds the switch count
only for **monotone or exponentially discounted** monitoring signals; `Rates.smoothed` is a
symmetric EMA whose `Anchor.rate` re-syncs on-anchor, so the finiteness bound does not transfer
as written (Hespanha–Liberzon–Morse's discounted monitoring signals are the standard repair, and
would give a stated bound on anchor churn). And "scale-independent hysteresis" in that paper is
the *multiplicative* form `μ_p(1+h) < μ_q`; the rail is additive with a noise-adaptive margin —
related, not the same.

The map also predicts the rail/audit split the F1 study found by measurement: in dual control the
**cautious** branch is conservative in the *loss* metric while the **probing** branch is optimized
for *information*, so the two want opposite pricings and must not share one margin. That has now
found three instances, the last of them **inside a single walk**: the rail's margin against the
audit's confirming streak (F1), the starvation probe's bar against the audit's (adv3), and the
audit walk's own crash abort against its reversal (§4). The third is the sharpest
reading of the principle, because the two exits are five lines apart in the same walk and shared
one threshold: the crash abort is the cautious branch, priced on the loss it is measuring, while
the reversal is the probing branch, which must not turn around on evidence it cannot distinguish
from noise. Where a proposed change makes one bar serve two questions, this is the shape to check
for. One open
direction comes from the same lens: a detrended confirm reference (both
`Walk.beatBase` and `Anchor.freshLeft` exist because the reference is an un-detrended *level*, and
"a trend clears any raw streak" is what detrending removes by construction).

The map's other two suggestions were **built and refuted** by the derived-guard study
(§5). Freezing the walk's deviation reference at arm is wrong because that feedback is
load-bearing, and a within-sample confidence interval is both too small to bind and answers the
wrong question for the position jam. Read those graveyard entries before proposing either again:
a formalism that names a mechanism correctly still does not license changing it.

## 3. The adversarial cases (the ones that must always be re-run)

The generators, run commands, and per-family verdicts for this section live in the committed
`/climber-gate` skill (`.claude/skills/climber-gate/`); the traces are deterministic and
regenerate on demand, so only the generators are preserved. New families are found by
`/audit-regret`, which searches a compositional workload generator for cells where the machine
does not close the gap to its static ceiling and classifies each failure (wrong equilibrium,
slow convergence, masked signal, insufficient exploration, oscillation, memory, irreversible
damage, aliasing, premature commitment, tier discontinuity, structural) against the layer that
owns it; a confirmed family lands here and in the gate table.

- **corda + 5×loop + corda phase-shift stress** (real traces, bundled): run at
  512/513/1024/2048/4096/4097/8192; no cliffs at either tier boundary; near static ceiling.
- **mixture family** (`gen.py mixture`, seed 7): steady Zipf
  hot-set (defends main, feeds mainDensity) + twice-accessed items at reuse distance
  D ∈ (2%, 25%]·max. No phase structure. Pure density pins at the floor for 100% of adaptations
  and lands ~28pp below LRU — at 8192 and any scale where D falls in the band (the trap is
  scale-relative because the floor is 2%·max). This is a plausible production shape (popular
  content + session-scoped re-reads) and the reason a tier threshold alone cannot make density safe.
- **phases family**: alternating loop-over-0.9·max and pair-reuse-at-D phases; d050 (D=50%·max)
  traps pure density at 8192 AND 32768. It is **bimodal with a 14pp run-to-run range** (the
  admission tiebreak picks the basin), so §6's ±0.1–0.8pp noise model does NOT hold and
  single-run numbers here are uninterpretable; the trace-start **alignment offset alone is worth
  up to 12.5pp**, frozen for the run because the sample grid never dithers (no aliasing comb at
  commensurate ratios — 38 ratios × 12 reps, mean +0.62pp mixed sign). What is real is the **lag
  limit**: phases shorter than ~2 sample periods (~8·max requests) are untrackable. Current
  classification: d050 is one of the three genuine steady-state pins, −8 against
  LRU converged, its grid-locked cadence constructed-only.
- **deadphase**: hot-set + pure one-shot scan bursts. Proves exploration during dead samples is
  ~free (admission shields main) — all variants sit at the ceiling. **But the safety is a knife
  edge on the ε symmetry, not on the dead sample** (failure atlas): a fully dead sample
  gives `ln(ε/ε) = 0`, a no-op; break the symmetry with as few as **six window hits** and the same
  sample yields `err = +17.4` and the full 30%-of-max step. A rider of **380 requests in 1.97M
  (0.019%)** riding a victim's own scan phases costs the victim 8.3pp and drags the window from
  0.03 to 0.48; at 0.4% it pins the ceiling for −9.6pp. Delivered through `armProbe`'s
  refractory fall-through, which suppresses the probe but not the density arm. Reactive tier at
  4096 on the same trace: −0.44pp.
- **widepin**: whole-working-set alternation (pairs at 0.6·max ↔ loop over 0.85·max). A fixed
  window wins by never moving; every online climber pays here. Re-classified: the
  whole-trace deficit is mostly **warmup** (it converges to a 77.9% window against an 80%
  optimum, final third −3.7 vs LRU), and the row is **seeded-only** — its unseeded distribution
  is a 50/50 basin coin whose old bar sat at its own mean. Bar: no per-seed drift, seeds 1–8.
- **Thin-signal floors** (w50@123038, S1/S2/S3, DS1): frequency-ish traces whose floor window
  earns wh/n in the *same band* as a trapped window (w50: 131–437 hits per 492k sample vs trap
  strays ≤57 per 32k). **No resident-only threshold separates them** — this is measured, not
  conjectured, and it is why probes+adjudication exist instead of a smarter starvation predicate.
- **lowmix** (`gen_attacks.py`, seeds 7/11/13): low-HR (2–7%) bistable shape whose probation
  concentrates the reuse band while the good state is an LRU-ward diluted window — the marginal
  verdict's named trade (the frozen baseline false-vetoes the escape that the rejected
  diluted-average form confirms by seed-luck). Sentinel: no drift below the recorded values.
- **demoflood** (`demoflood.py`): protected-saturating hot core + walk-reachable band — the
  constructive proof that live-probation adjudication is an absorbing false-veto and the
  pre-walk freeze is earned. Gate row: frozen-v5a must stay far above the live-variant's pin.
- **whisper + window-irrelevant modulation** (`whisper_mod.py`) and **mixmod**
  (`gen_adv.py mixmod`): the F1 dose instruments — a hit-rate modulation that provably does not
  change the optimal window (pin-sweep verified) must not turn the goal-metric layer off. The
  deviation-priced confirm failed this dose-responsively; owned by the raw-sample streak
  confirm and the fresh-park shield, with the deep a0.12 dose (whose scatter crosses the
  audit's crash bar) closed by the crash-streak time persistence — the sentinels now sit
  within half a point of LRU. `mixmod` reads −2.4 against the reactive arm and is
  **constructed-only**. `esc_jam` and `tenant_s10` ride along as
  jam-family and co-tenant-family sentinels for the same layer.
- **shieldtrap / climbtrend / loopcliff** (`gen_adv.py`, round-2 instruments promoted to gate
  rows by round 4): regime-change-after-confirm, trend-driven misconfirm (plus the flat+wave
  `saw_p40` variant), and the no-cliff invariant at the structural misconfirm landing.
  `shieldtrap` and `saw_p40` are the **audit-amplification sentinels** (R4-F1): the round-3
  clock repairs multiply audit reachability — worth +10.4 on the attack rows, giving back
  −1.0..−2.1 on these already-below-LRU synthetics (audit share of the run 6% → 43% on
  shieldtrap, which also turns bimodal) while twelve real density-tier cells move ±0.07.
  The rows exist so the next audit-schedule change is measured against the cost side too.
  **Part of that give-back was never the schedule's**: the audit-bar split recovers
  +0.79/+1.75/+1.17 (N=8: 78.06/77.19/77.44 → 78.84/78.94/78.62, re-based in the gate table) with
  `saw_p40` at 0.00, because `AUDIT_BAR_FRACTION` was pricing the walk's reversal at a median
  0.28σ here and killing walks on samples the machine itself calls noise. Attribute the remainder
  to reachability, not all of it.
- **posjam** (`gen_jam.py`, round 3's position-jam instruments): the whisper base with the
  whisper dose *modulated* at the sample cadence instead of constant — dose-matched and
  provably window-irrelevant (LRU and every static window identical to the flat control). The
  modulation makes the density arm command super-band steps, which starved the hard-reset
  audit clock forever (66.9 → 56.1 = the audit-free pin, −8.9 below LRU; scale-invariant at
  16384). Owned by the decaying clock and the audit-owned schedule (the `j50` ±50%-jitter and
  `d25` period-2.5 rows recover to 66.4–66.5); the sample-aligned every-sample variant (`d0`)
  still jams — it needs ±¼-sample grid alignment (the D4 exposure class), is a deterministic
  9.3pp regression against the reactive arm, and is **constructed-only, priced and parked**
  (the stillness-measure study stays deliberately unspent). A slow
  square duty re-creates the starved clock below every-sample motion (the Sol
  round's `entry_duty`, a dose note on the `posjam_d0` gate row); the loss there is the
  rider's class-3 mask, not the jam.
- **crashnoise / mixnoise** (`crashnoise.py`, `mixnoise.py`, adv3 rows): the whisper and
  mixture bases under a mean-centred, RMS-normalised rate modulation whose **amplitude** is
  the dose — the walk's exit-bar instruments. The two interior exits test different statistics
  (crash abort = level vs the rate frozen at arm; reversal = first difference vs the previous
  sample — §4), so the dose ladder is a threshold, not a gradient: nothing moves under the
  bar, the cell falls off where the scatter crosses it. `mn8_sine_a10` was the defect on the
  **starvation-probe** path — the wave crash-cycled every up-probe one stride off the floor,
  severing the mixture trap's only escape — healed by the probe-side pricing (51.6 → 60.7,
  +5.6 over LRU). `cn_sine_a12`, the audit-side sentinel, is **bimodal** (its "deterministic"
  record was an N=2 artifact) and healed most of the way by the crash-streak time persistence
  (−2.8 → −0.8 vs LRU); the residual is the crash abort's robustness price.
- **whisper** (`whisper.py`): a mixture base plus a ~0.2% immediately-re-read trickle sized to
  keep the window's sample hits JUST above the starvation bar, so no blind corner is ever
  declared and no probe arms — the sharpest F4 instrument (pinned −9pp below LRU at 8192, −12
  at 16384, knife-edged exactly on `MIN_SIGNAL_SHIFT`; the quarter-dose variant drops below
  the bar and the marginal verdict escapes where the average form stays pinned). Owned by the
  equilibrium audit: 66.8 against LRU 64.6 on every seed, identical under both verdict forms
  above the bar.
- **shallowmoat** (spec `audit-regret/specs/shallowmoat.json`):
  a zipf over 1.5·max that fills main and gives the window only stray hits, a two-reference band
  6,200 requests apart caught only past a ~58% window, and a one-shot scan that keeps probation
  thin. The static curve is a valley 2pp deep and 57% of the cache wide with a 19pp prize behind
  it, so no walk crashes and no first-round walk reaches it. Seeded 1–8: 27.4–28.5 against a
  42.0 ceiling and LRU 39.4, and the same product planted at 70% holds 42.0. Two basins, one
  outcome: the starvation up-probe adjudicates at 4×bar hits three or four strides out, confirms
  against the thin frozen probation, `Ladder.reward` resets the rung, and the density arm reverses
  the window in the same sample, so the ladder alternates 1↔2 and no commitment depth is ever
  reached (absorbing at 127 decisions); or the calibration audit confirms on the trace's warmup
  trend at 32%, one stride short of the cliff, and parks. Reach (class 4) through a successful
  verdict erasing the ledger (class 6). Splitting the band over a spread of reuse distances keeps
  the gap (26.8 / 29.0 at N=8) and adds the average law's rest-point give-back from above. The
  reactive arm beats the machine on 6 of 8 seeds (+2.0 mean).
  Reach was repaired the same day (§5): a confirm the density arm reverses now
  deepens the ladder instead of resetting it, and the wedge seeds cross the cliff at s17 (30.7 /
  30.0 / 30.6 against 28.5 / 28.4 / 28.5). The row still reads far below the ceiling because the
  found position is not kept: after a walk from the floor, density's rest point on the far side is
  at or below the cliff (main holds the protected core, 3.7 hits per entry against the window's
  1.2), the guard rail cannot catch the fall (the fall inflates the deviation its margin is priced
  from), and the deep walk's confirm at the top is itself reversed, so nothing parked. Retention
  landed: a reversed confirm at the deepest commitment that the goal metric
  confirms parks as an audit's does, and the park survives its own audits' crash-scale moves; the
  wedge seeds read 36.5 / 36.4 / 36.3 (repeat 2: 37.1) against the 42.0 ceiling, the residual being
  the top corner's periodic down-audit crashing at the cliff. Two facts from `/audit-regret` round 2
 : the basin is decided by the first sample's window hits against the bar (30–31 hits arm a
  probe at s1 against a fat frozen probation, which fails, basin B; 32–34 leave the floor sighted until
  s3, when a probe against a thin one confirms, the wedge), so a regenerated trace reshuffles which seeds
  cross; and both repairs live on the starvation ladder, so a rider that keeps the floor sighted removes
  them: `veilmoat` (the same trace plus a k=8 burst re-read 40 requests apart at 0.06% of traffic, worth
  0.3pp) arms no starvation probe on any seed, and every seed then takes the audit path (26.84 ± 0.34 at
  64 samples on 8 of 8 against a paired unmasked control of 4 of 8 crossing; the rung-32 up-audit crosses
  at ~s88, 32.02 at 128 samples), and after the recorded top-corner loss the guard rail's veto and
  density's slide form a ~17-sample limit cycle with no re-escape (32.45 ± 0.07 at 256 samples, where the
  unmasked geometry re-escapes through the deep starvation walk to 34–40). The mask holds from ~3e-4 of
  traffic (below that the ladder is the parent's own lottery); a dose note, not a family. A park's first
  audit following its walk turns the basin-B seeds' second audit up from the 32% park
  into the cliff crossing at s52 (27.4 → 30.1 on the 64-sample row, 32.2 → 34.0 at repeat 2; the wedge
  seeds identical) and does the same for `veilmoat` (26.84 → 30.54 on 8 of 8, 32.02 → 33.93 at 128
  samples, 32.45 → 33.11 at 256); the retention residual after each escape is unchanged.
- **absolve** (specs `audit-regret/specs/absolve.json` and
  `absolve_p8.json`): a lure population (pairs 1,300 requests apart, 10% of traffic) pulsed on and off
  with a period of 16 samples inside a wide flat valley, a far band (pairs 6,000 apart, 45%) caught only
  past ~54% of the cache, and a zipf core in main. Static: 18.8 to 10%, a +5pp shelf from the lure's knee
  to 50%, 52.5 at 70%; LRU 51.5. Seeded 1–8: 22.59 ± 0.18 at 64 samples, 24.77 ± 0.11 at 128, an escape at
  s144–159 (36.4 at 256 on seeds 1–4), reactive 25.5 and noaudit 22.9 trapped as well, and the same product
  planted at 70% holds (51.07 ± 0.21). Two mechanisms by trace length. Through 64 samples it is the audit
  tier: the calibration audit's level-test confirm at 32% on a phase step (the lure switching off raises
  main's hits by 5pp against a stale reference), a 32-sample park, the down-first alternation and its crash
  when the lure returns mid-walk, the undo's arrival discarding the anchor: `shallowmoat`'s basin B at
  another dose, on every seed. From s60 a cycle paced by the lure: the off phase blanks the window and the
  corner arms a ×1 walk, the on phase confirms it at 2,200–3,400 on the lure's own hits (the verdict cannot
  attribute the watched region's hits, and the lure is dense per slot and worth nothing above its 8% knee,
  `flood_j100`'s shape pulsed), density rests on the lure at ~37% then slams the window home when it goes
  off, and the ladder gains one rung per period because it gets one walk per period, with the kept
  confirms resetting it to 1 (`Ladder.reward`); the escape needs a walk longer than any first-round one
  (six ×1 strides from an arm caught mid-slam at ~1,650, or the rung-32 ×2 walk) and comes ~140 samples in.
  The average law also abandons a partial catch of the band at 4,300–4,400 (window density 1.30 against
  main's 1.59). At a period of 8 (`absolve_p8`, 256 samples) every confirm is kept, the rung reads 1 or 2 on
  223 of 255 samples and never reaches 32, the audit clock never fires (the position never stills 16
  samples), and the cell is absorbing: 27.92 ± 0.19 against a 57.4 ceiling and LRU 55.6. Robust to the
  grid (period 12: 26.35 ± 0.16 at 128 samples; a phase offset: 28.52 ± 0.86; period 20 a 2-of-8 escape
  lottery), to the band distance and to 16384 (18 of 18 neighborhood cells at 32–34pp at 64 samples), and
  a knife edge on the lure's share at ~1.6% of traffic, where the calibration audit's direction flips from
  up-from-the-floor to down-from-an-interior-position. Reach (class 4) through the verdict's attribution
  (class 3), density's chase (class 5) and the ladder reset (class 6); the period-8 form is class 6
  outright, the `deferreward` shape (§5) made deterministic. **Repaired**: the starvation ladder prices a confirm at or
  short of the farthest window its walks have confirmed as a completed experiment, so the period-8 form
  escalates 1 → 64 over six periods and the rung-64 ×4 walk crosses the valley in two strides (27.95 →
  46.29 at 256 samples, above the reactive law's 45.28); and a park's first audit follows the walk that
  confirmed it while the claim stands, so the period-16 form's second audit walks up from the 32% park
  into the band instead of down into the lure's knee (24.77 → 41.46 at 128 samples; 36.45 → 46.89 at 256;
  period 12 and the phase offset 26.4 / 28.5 → 44.0 / 43.1). Period 20 stays a 2-of-8 lottery. What
  remains after each escape is the recorded top-corner residual (the down-audit crashing at the cliff and
  the undo's arrival discarding the anchor).
- **ghostclaim** (spec `audit-regret/specs/ghostclaim.json`): two
  phases, a zipf core with a band 2,000 requests apart and a scan for 28 samples, where density rests
  at ~43%, then a sleeper population 6,400 apart at half the traffic, caught only past a ~64% window.
  Static: 19.9 at the start, 54.1 at 70%; LRU 51.8. The stale-claim family's away-anchor case: the
  calibration audit's down-walk re-syncs the anchor's claim to phase 1's rate as it passes 20%, density
  holds the window at 43%, and the shift lands with the window still and off the anchor, so the
  stand-down keeps the claim (the carve-out). Seeded 1–8: 41.88 ± 0.17 at 64 samples (the s37
  up-audit sits at the top for ten samples at +30pp and fails at budget against the claim), 31.24 ± 2.10
  at 128 (the same claim then vetoes the window to the phase-1 anchor and its hold, the down alternation
  and the deepest-rung wait pin it), reactive 37.96 (bimodal), noaudit 30.30. Knife edges by
  construction: the stand-down's band (a phase-1 band share of 0.16 lands the shift on the anchor and the
  claim is discarded) and the prize's rate against the claim (a sleeper referenced three times clears it).
  Class 6 with class 4 as the pin. **Repaired**: the audit's walk is measured against the smoothed rate it leaves rather than the anchor's
  claim (31.24 → 48.45 at 128 samples, above the reactive law; the witness 41.88 → 46.86; `cp_w100` +2.0
  on the corpus). The discard shape died on the moat rows (§5). What remains is the shift landing on the
  audit's arm sample or during its walk (`ghostclaim_p35..p40`), where the walk crashes on a
  contaminated base and the stale veto's hold pins the machine, and an audit arming inside the smoothing
  horizon after a shift, which measures against a blend of the regimes.

- **crestpast** (spec `audit-regret/specs/crestpast.json`): a
  uniform bulk over 1.2·max at 72% of traffic (main's population, its hits linear in main's capacity)
  and a two-reference band 1,100 requests apart at 25%, caught by an 8% window. Static: 45.8 at 1%,
  44.7 at 5%, 62.5 at 8%, 64.4 at 10%, then a decline of ~0.9pp per 6.25% stride to 54.3 at 80%; LRU
  52.3. A crest 17pp high, one stride from the floor, with a cliff on its near side and a slope on its
  far side. The calibration audit crosses the crest on its first stride and confirms on its fifth
  (`AUDIT_CONFIRM_STREAK` completes at stride 4, `AUDIT_COMMITMENT` holds the verdict to stride 5),
  so it parks at 33% earning 3.2pp less than the crest it walked over; the park's audit walks back
  down, has the crest under foot at its fourth stride and its fifth on the cliff side, where the level
  test crash-aborts before `auditEnding` can be reached; the undo's arrival discards the anchor (the
  C2 shape) and density's average law, which reads the caught band as a dense window against a thin
  main, drifts the window up and away from the crest until the next audit fails at the corner and
  the rail vetoes back. Seeded 1–8: 57.93 ± 0.04 at 132 samples against 64.9 at 10%, reactive 46.5
  and noaudit 46.2 both pinned at the floor (the hit-rate law steps into the 1–7% decline and never
  crosses; density steers the sighted window down), and the same product planted at 10% leaves the
  crest (62.4). The verdict's position is the mechanism: the confirm lands at stride max(k + 3, 5) for
  a crest crossed at stride k, from either side, so the two endpoints the machine visits (the floor
  and the floor's fifth stride) can neither of them confirm the crest, and what the machine holds
  between audits is the average law's rest point above it. Planted at 40% the down-audit's fifth
  stride lands on the crest and confirms there, and the row still reads 59.7 (gap 5.0): a confirmed
  crest is lost after one audit cycle (the corner audit's fail, the veto, the second park's crash,
  density's drift), so the retention half is the larger part of the steady loss and the verdict's
  position decides where the first park lands. Each stride's sample is an arrival transient, so on
  most cells the walk's best sample is one to two strides past the static crest, which bounds what a
  verdict at the walk's best could recover.
  The far side's slope prices it: bisecting the bulk's size finds the knee at 0.68·max, main's capacity
  at the park, below which the bulk still fits from the parked position and the overshoot lands on a
  shelf (`demoflood`'s recorded overshoot, which the C2 discard there accidentally corrects because
  density's rest point sits at that crest). Robust across the band's distance (800–2,200 requests),
  the bulk's size (1.0–1.5) and 16384 (ten cells at 4.4–8.0). Class 9 (the audit verdict,
  `Walk.isConfirmed`) with class 1 holding the loss and the C2 discard handing it over. With the crest
  beyond the fifth stride (the band 4,000–4,400 requests apart), the same verdict parks the window in
  the valley on the cold reference instead, `regimeramp`'s recorded misconfirm at ten times its
  sentinel's dose (`s_fatbase`: 29.6 against 44.3 at 45%, LRU 37.2, at 72 samples).
- **hazefloor** (`/audit-regret` round 3's note, rowed by round 4 at 320 samples; spec
  `audit-regret/specs/hazefloor.json`): a uniform haze over 2·max, a zipf core, a band 7,200 requests
  apart caught only past ~60% and a scan; static flat 33.7 → 32.5 to 50% then 52.3 at 65% (a 60/65%
  re-sweep), LRU 49.2. The calibration audit crosses and parks at the top by s24, and the rest of the
  run is the top-corner residual by itself with a ~105-sample period: the corner's audit is forced down,
  crashes at the cliff, the undo's arrival discards the anchor, density slides off the cliff, the
  floor's ×1 walks fail against the haze, and the rung-16 audit re-crosses. Seeded 1–8 at 320
  samples: 41.09 ± 1.80 (38.3–42.0), 8pp below LRU, against the reactive law's 36.5 and noaudit's
  37.5, so the layer earns its keep and loses most of it every cycle. A sentinel with per-seed bars,
  not a pass bar; the row exists so a change to the corner audit, the C2 discard or density's slide is
  measured against it.

- **parkveil** (specs `audit-regret/specs/parkveil.json` at
  240 samples and `parkveil_min.json` at 60): a zipf core (0.5·max, α 1.05) and a two-reference
  band (pairs at 0.45·max) whose shares alternate in a square wave at period 13 samples, plus a
  10% one-shot scan. The fixed mid window wins by construction (ceiling 66.82 at 50%, an interior
  plateau wholly above LRU 66.04; the product planted at 50% holds 67.03), and every adaptive arm
  sits below LRU: seeded 1–8 the machine reads 53.13 ± 4.09 (a 6-vs-2 basin split), `noaudit`
  55.73 ± 0.55, the reactive law 59.91 ± 0.92. Density's rest point alternates with the mix (the
  ratio's within-sample phase immunity holds for rate swings, not mix swings), each rec phase
  blanks the window into a blind corner, walks crash on the phase boundaries or confirm on the
  flips as wedges (the ladder escalates 2 → 64 as designed), the rung-64 walk parks at the top
  corner as an audit-grade confirm earning within ~2pp of the plateau, and the park survives
  exactly one fresh-park shield: flips inside the shield are absorbed, the park's own audit crash
  returns to it (the cover), and the first post-shield flip fires `isWorkloadShift`,
  whose stand-down discards the claim at the anchor and re-seeds the goal metric, so the position
  is re-derived through the full ladder every cycle. The guard rail never fires on any seed: the
  phase swing holds the deviation at 0.21–0.34 and the veto margin at 62–101pp. The cycle is
  arm-independent (`noaudit` parks at s134 through the ladder alone and re-parks more reliably;
  the audit layer's margin here is delay from a calibration flip-misconfirm's reward reset, plus
  variance), so the loss lives in the shared stand-down and shield machinery. The last-third gap
  is 6.0 of the whole-trace 13.7 (half the headline is one-time ascent); a ~2pp per-cycle
  re-derivation cost and a 2-of-8 lost-seed tail persist. Robust across the period axis (gaps
  27/27/7/27/12/17/12/13 at periods 2–32, a resonance structure with one narrow escape at 6),
  amplitude, band distance (deepens to 22.1 at d=0.6), 16384 (21.2), phase offsets (6.7–20.2,
  none clean) and trace regeneration (~2pp milder). Class 5 (the chase; controller) with class 6
  (the discard; recovery layer); the census C3 observation's first cost-bearing cell, and the
  mirror of `flashpark` (there the shield holds a wrong park through a regime end; here the
  shield's expiry ends a right one).

- **latebloom** (specs `audit-regret/specs/latebloom.json` at
  262 samples and `latebloom_min.json` at 187): a zipf core (0.15·max, α 1.0) at 60% of traffic
  and a scan for 112 samples, flat within the audit's level test at every window, then a
  two-reference band 7,000 requests apart at a third of traffic for 150 samples, caught only from
  a 50% window. Static: 52.4 from 1% to 40%, 66.4 at 50%, 66.2 at 70%, 65.9 at 80%; LRU 64.5.
  The prelude spends the audit schedule: the calibration audit parks the floor on the cold-start
  lag, the park's two audits walk their full budgets to the 80% cap on flat terrain and end
  failed rather than crashed, and the second failure at rung 64 doubles the clock to 128
  (`AuditClock.reschedule`). When the band arrives the floor's rate drops 15pp and the stand-down
  fires, but a stand-down leaves the audit rung and the clock's wait untouched, so nothing is
  rescheduled; at the base's alignment the arrival lands inside the undo's retreat, where
  `isParkTest` covers it, and the held floor survives the change outright. Either way the
  machine sits at the floor against its own law (rest_err +1.3) until the 128-sample wait runs
  out and the next audit catches the band in two strides. Seeded 1–8 the base reads 54.90 ± 0.04
  against the 66.4 ceiling (gap 11.5, 18% of the prize, 123 samples parked), `noaudit` 53.39
  pinned at density's ~19% rest point (the core's head keeps the window sighted at ~4,000 hits a
  sample and worthless at the margin, and the band is a +19pp step between 27% and 51% that a
  marginal law cannot see) and `reactive` 53.44; planted at 50% it reads 57.85, since density
  slides off the flat prelude before the prize exists. The loss is the latency and nothing else
  (gap ≈ (confirm − arrival)/n × 23.9pp fits every cell within 0.5pp), and the phase-1 ladder is
  the family's shape: an arrival during an audit's cap-sit is confirmed at once (56/104 samples:
  0.9/0.8), one on a walk's early stride crashes it and is found at the next wait (48/96:
  3.5/4.4), one just after a budget failure releases the park by the stand-down, lets density
  rest at ~19%, and the next audit alternates down from that interior position and fails into
  the 128 wait (64/80: 16.7/15.4, found at ~s247 when the horizon allows), and one during or
  after the retreat is found ~125 samples late (112–132: 9.8–11.5). Over arrival times the mean
  is 7.2, the median 6.2, the worst 17.1, and about a tenth are found at once; the base is the
  76th percentile. Identical at 16384 and flat in the band's distance down to 3,500 requests,
  where the held floor still catches nothing although density's rest would; the minimal witness
  (187 samples) is the never-found form, 55.78 ± 0.02 against `noaudit` 55.79. Class 4 (the
  exploration cadence, `auditWait`, and `chooseDirection`'s side choice from an interior rest
  point) on a class-3 terrain; the retreat cover's hold is incidental above the family's
  boundary. Nearest recorded: H4-C1 and `metronome`, which reach wait 128 through crashes on
  trap terrain; this reaches it through the designed path and prices a detected regime change
  that reschedules nothing. The latency face of that residual, whose
  re-arm was priced dead (§5's re-arm entry: the stand-down alignments' gaps fall to
  5–6 and the price lands on the F1 sentinels).
- **mainsat** (`/audit-regret` round 7; spec `audit-regret/specs/mainsat.json`, 72 samples): the
  main-side masked signal, the sighted lane's directed slot for seven rounds. A zipf core over
  0.45·max at 30% of traffic (recurrence ~12k requests, longer than the window's residency at
  every window, so main holds it and the window never takes it), a band 5,200 requests apart at
  18% caught from ~50%, a 52% scan that keeps the window's residency short enough for the core to
  stay main-resident even at 80%, and a gap-1 trickle at 0.3% that keeps the window sighted.
  Static, dense-swept: 20.3 flat to 40%, 27.2 at 50%, 32.0 at 52%, 35.8 at 58%, 35.4 at 62%,
  34.6 at 70%, 33.5 at 80%; LRU 30.5. Main's 7,000 hits a sample are real and worth nothing at
  the margin (main holds 8,028 entries for a 3,686-key set), so `steeringError` commands a
  down-step every sample, no probe can arm (a dense main is not a blind corner by design), and
  the raw law pins the floor: `noaudit` 21.41 ± 0.48 against the 35.8 ceiling. The equilibrium
  audit is the only explorer and does the work: the calibration audit confirms a real cold-start
  gain at 32% (+3.3pp over the floor while main warms faster under the larger window; the band
  is five strides past the floor, beyond its reach) and parks for 32 samples, the park's audit
  walks up through the band and confirms at s48 on the peak (six seeds at 56–57%, two at
  68–69%), and the loss is a ~48-sample approach at any horizon: 28.04 ± 0.18 at 72 samples (gap
  7.8), 32.63 ± 0.30 at 160 (gap 3.7, ~1pp of audit duty permanent). Planted above the cliff the
  product does not hold: density slides the window off the cliff at ~49%, the rail returns it,
  and on the landing sample the rate recovers by 11pp, which `isWorkloadShift` reads as a
  workload change, so the stand-down discards the claim with the window on the anchor and the
  slide repeats every ~19 samples with the anchor creeping down (planted at 70% over 160
  samples: 29.99, ten vetoes, `closed` −5.2; at 55%: 27.95). Implemented as the return
  cover (`isReturnTest`): a return's landing and settle samples wait for the retest, which
  breaks the cycle (160 samples: 70% 30.06 → 35.30, 55% 26.13 → 35.08, two vetoes each; the
  pre-cover baseline sat below LRU on both), `noveto` reads 33.75 / 30.82 there, and the cover is
  bit-identical on the unplanted path, where the anchor is a park and the rail never fires.
  Release posture is **HOLD** on the repeated-real-trace recheck; the planted
  synthetic prize remains valid, but the original one-pass gate was not the full price. Class 3
  owned by the audit layer, as `whisper` is on the window side, with
  class 6 on the plants: the C2 arrival-discard shape on the rail's return, which the
  re-price found unreachable because the rail never fired across the battery. Rowed as the
  main-side mask's sentinel.
- **sidecliff** (spec `audit-regret/specs/sidecliff.json`, 96
  samples): a zipf core (0.15·max, α 1.0) at half of traffic, a near band 300 requests apart at 8%
  that the 2% floor already catches in full, and a far band 7,000 apart at 30% caught only from a
  55% window. Static, dense-swept: 36.4 at 1%, a 42% shelf from 5% to 40%, 44.4 at 50%, 63.4 at
  55–65%, 62.3 at the cap; LRU 61.6. The near band's fixed hits spread over a growing window pin
  the average law's rest point at ~14%, sighted and worthless at the margin, so `noaudit` and
  `reactive` sit there (41.85 / 41.80) and the audit layer is the only explorer. The calibration
  audit arms at s7 with `AuditClock.down` still at its initial value and a stride of room below the
  rest, walks down, loses half the near band at the floor, reverses and fails through its own base
  (`arung` 32, `auditWait` 32), and the machine then holds the shelf for 33 samples until the
  rung-32 up-audit crosses the step at s47 and parks at the cap at s50: 52.51 ± 0.03 on the parent
  cell at seeds 1–8 (identical event sequences), a gap of 10.9 that is latency alone (5.32 at 192
  samples with the prefix identical; trace seeds 11 / 13 read 8.57 / 9.02). Two sibling
  constructions from the other lanes price the shape below the rest: `s_faderail` (a trickle-pinned
  rest at 13%, the down walk wall-sits the floor for fourteen samples and fails at budget, 46.12 ±
  0.06 against 55.02) and `s_sidebet` (a starvation probe finds a mid band at 28% and plants a claim
  without a park, so the follow rule has nothing to follow and the calibration audit still opens
  down, crashing at the mid band's cliff with a wait of 16; 45.31 ± 0.15 against 53.30). Planted at
  the peak the product holds the cap (61.35), so the whole loss is the approach. The opening side
  from an interior rest point is the latency-face residual and `absolve`'s
  entry names as a knife edge; this is its stationary witness at the opening rung, with no prelude
  and the prize present from the first sample, filed under class 4 with that residual (the
  signature reads class 2, walk-paced). The symmetric cells, a prize below an interior rest where
  down-first is right (`crestpast`'s drift, `cp_w097`), are the other side of the same coin. The
  guard rail also loses here rather than helping: `noveto` reads 60.62 against 59.28 on all eight
  seeds, and the retest is what bounds the loss, since `noretest` takes seed 2 to 51.63
  (`/climber-minimize`, §5).
- **jumpslide** (spec `audit-regret/specs/jumpslide.json`, 130
  samples): a hot set of 0.5·max keys replaced wholesale every 13 samples at 52% of traffic, a band
  5,600 requests apart at 30%, a scan at 18%. Static, dense-swept: 35.5 flat from 1% to 40%, 50.3 at
  45%, 53.4 at 55%, 54.8 at 65%; LRU 49.5. Between jumps the hot set is main-resident and the miss
  fraction ~0.4, so the band is caught from ~43%, a residency knife edge (catching it lengthens
  residency, losing it shortens it) that collapses in one sample. Seeded 1–8 the machine reads
  38.70 ± 2.79, `noaudit` 44.23 ± 4.07 and `reactive` 41.21 ± 3.64, every arm 5–11 under LRU. The
  route: the calibration park's 32-sample expiry lands two samples after a jump, the recovery
  stands it down and the s42 audit goes down to the floor, so the starvation ladder takes over; a
  first-round probe confirms at 43.2% on the stride where the band is first caught, since 4× the
  bar is first earned exactly there, at the catch region's near edge, and a seven-stride confirm is
  under `PROBE_COMMITMENT_DEEP`, so no park rule applies and the edge position is handed to density
  with a claim planted and nothing held. Density's rest point is below the edge (0.7–1.0% a sample
  down); each jump lands the fresh set in the window and kicks it up 7–8%, and the drift returns it
  to the edge as the next jump lands: seeds 4 and 8 hold that sawtooth at hr 60–62 between jumps,
  while a landing under ~48% lets the drift cross the edge first, the band collapses, density sends
  the window to the floor, and the blind corner re-climbs seven strides of `wh` 0 or a jump crashes
  the climb. The rail never fires, since a one-sample collapse inflates the deviation its margin is
  priced from, `shallowmoat`'s sentence, with or without jumps. Period 17 reads 5.72 (the calibration
  audit goes up instead and parks audit-grade), 26 8.2, 6 / 11 18–20, 40 15.0; trace seed 11 16.7;
  260 samples 14.8; a static uniform hot set in place of the jumps 52.27 against 61.63 (the slide
  released there by a stand-down on the sample after a capped retreat's landing, which the retreat
  cover does not span); planted at 60% the product holds. What the record did not have: a
  first-round starvation confirm's verdict position is the near edge of what it found, by
  construction, and nothing parks it; class 9's second clause feeding class 1 (the retention
  sentence, the parent) and the class-5 sawtooth, with class 4 on the re-climbs. A sentinel row.
- **lowbar** (spec `audit-regret/specs/lowbar.json`, 96
  samples): a uniform bulk over 2·max at 42%, a band 6,000 requests apart at 34% that only a ~68%
  window catches, a scan at 24%, and a gap-1 trickle at 0.4% that keeps the window sighted so no
  probe arms and every seed takes the audit path. Static: 13.0 at 1% declining to 7.8 at 65%, 32.5
  from 70% to the cap; LRU 30.6; a 9% rate. The audit's crash bar is `AUDIT_BAR_FRACTION` of the
  rate frozen at the arm where that is under the 5pp cap, 1.37pp here, and the calibration walk's
  cumulative drawdown along the bulk's decline crosses it at the tenth stride, one short of the
  catch point; the tolerant retry's three sub-bar samples run out at 44%, the rung-32 walk's at
  63% with its next stride on the plateau. Seeded 1–8: 13.40 ± 7.48, seven seeds pinned at
  11.3–12.1 under the 13.0 start (three crashed walks' duty) and one at 26.3 whose tenth stride
  cleared the threshold by 0.07pp where seed 1's missed it by 0.20 (trace seed 11: 26.30 / 11.61);
  `noaudit` 12.72 and `reactive` 12.49 are the terrain's floor at every window short of the catch
  point, the same loss as the 17pp under LRU. With the crash fraction at 0.25, or the absolute 5pp
  bar, three of four seeds arrive at the first walk (26.3) and the fourth takes the reversal exit.
  The moat family's mechanism (the abort at the −bar contour one stride short of the far bank, the
  persistence a stride short on the retry) at a fraction-bar dose: the bar set by the rate rather
  than the terrain, and a sub-bar region wider than the persistence. `s_nearbasin` is the same
  exit at a 16% rate behind a calibration park (about 7 of its 23; the crossing seeds clear the
  level test by 0.02–0.03pp, and `nocal` recovers only +0.35 because its own first walk crashes on
  the same bar). The first measured cell on which the fraction's level is not a tie (the record
  holds it inert at 0.10 and 0.25 on the sub-third holdout and on `arc_S3`'s plateau); its cost
  side is unmeasured where it binds (the `lowmix` rows, `arc_S3` and the thin-signal floors,
  `cp_w050`, a fresh sub-third holdout), and the carve-out the cell resembles is closed (the
  split), its deviation floor dead. Narrow by construction: 5,500 apart catches at the
  tenth stride, 6,600 passes the cap; at 16384 the band fits at 40% and the cell reads 4.8. Class
  4 (reach), the walk's exit pricing; a sentinel row beside the moat rows.


### Terrain classes, and what reachability means

The families above are *shapes that break a climber*. These are properties of the **terrain**
itself, which is a different axis: they classify the hit-rate-versus-window curve rather than a
trajectory over it, and they are what decides whether a constructed trap is worth chasing.

**A non-convex window response is a real class, and on it the rest point is the deficit, not the
travel.** Sweeping static windows, some cells have a trough deeper than 0.5pp on the way from a 1%
window to their optimum, and several are real traces rather than synthetics. The average law's zero
crossing then sits in the wrong basin: on two deep-trough cells it rests at a 1.4% and a 6.9% window
against 80% and 90% optima, and the rest error is 75% and more than 100% of the whole deficit — one
of them runs *above* its own static value at that rest point. The unimodal control is what makes
this trustworthy: there the average law rests 1.9pp of window from the peak for zero hit-rate loss.
So "the climber holds a small window because it has not travelled" is wrong on this class; it holds
a small window because that is where its steering law rests. This is invisible from the canonical
published curves, none of which is non-convex, and it is the terrain half of the marginal-steering
thread (§5).

Classifying a large loss corpus by whether a *fixed* window could have won it: **two** are
structural (no fixed window wins, a real W-TinyLFU limit), **none** are ceiling-bound, and the
rest are **steering** — a reachable fixed window ties or beats the competitor and the climber misses
it. Over 80% of the conceded margin is recoverable inside the reachable window range, so raising the
structural ceiling wins nothing. Terrain is concentrated: most of the steering cells are block I/O,
the family with flat (2–6pp) and sometimes non-convex responses.

**Do not tune the audit schedule for this class.** The equilibrium audit exists for a sighted false
equilibrium, which is exactly this shape, and its cycle costs more samples than these short cells
have — which invites the conclusion that the clock is too slow. The ablation refutes it: against
`noaudit` the mean delta is under 0.06pp, the significant cells split evenly between cost and help,
one cell of twenty-two moves more than a point, and the mean flipped sign as cells accumulated,
which is what noise looks like. The layer is neither the cause of this class nor its cure, and
shortening its schedule would buy a measured zero while disturbing a constant calibrated on the gate
battery.

**Low audit exposure only bites where the terrain has something to find.** This is the rule that
decides reachability, and it is why several deterministic constructed regressions are parked rather
than chased. The stillness-starved traps (`posjam_d0`, `bandtrap2`, `phases_d050`) arm zero or
almost zero audits, and the reachability scans come back clean on every real cell. The nearest real
approach is always the same cell, `corda`, which calibrates once and never re-audits — the known
blind hold — and it costs nothing there, because its terrain is flat and it already scores within a
third of a point of its ceiling. A cell that never re-audits on flat terrain has lost nothing.

**High per-sample scatter and wide terrain are anti-correlated in practice**, which is the
structural reason the modulation traps are hard to reach. Every real cell with scatter at trap
strength has under 3pp of terrain; every cell with wide terrain has scatter well below it. The
constructed traps hold both at once, and no real cell in the corpus does. The one real cell carrying
trap-strength scatter on wide terrain has an optimum that *moves* — which makes its scatter
window-informative, precisely the case the goal-metric layer exists to answer.

**The tier boundary.** At the 4096 switch the discontinuity is the policy, not the terrain: on a
constructed straddle one entry of capacity moves the answer by several points while LRU steps zero
and both single-law arms are continuous. On real traces the sign inverts, so the cost is borne
*below* the boundary and the reactive tier is the loser there. Half the cliff is not in the steering
law at all — the tier gate switches the sample period along with it. The placement is set by the
worst case rather than the mean, and it currently has a single defending cell; if that trace were
ever judged unrepresentative the interval below the boundary reopens at once.

What the boundary also gates is the *layer*, and that is worth more than the difference between the
two laws. A terrain whose response is **flat then a cliff** carries no gradient at all in the region
a climber searches from its start, so no local law can cross it and only the equilibrium audit can —
and below the threshold there is no audit. Constructed at a maximum of 4096, the hybrid, reactive
and `noaudit` arms are one code path and read ~23 with an 11pp spread across seeds, while the
density machine forced at that same size reads 45.8 ± 0.02 against a static ceiling of 48.2. The
tier gate, not the workload, is worth 23pp there. That cell is constructed and its realism is
unestablished: it bounds what the placement can cost, and is not a case for moving it.

**Path-dependent rest points.** At least one real cell settles somewhere different depending on
where it starts, and not because it is sample-starved. `cp_w050@123038` holds 54 samples; from the
shipped 1% start it settles at a 20–21% window, and from a planted 30% start it settles at 14–15%
for **+4.65pp** on the same trace, landing 1.6pp off its ceiling where the shipped start lands 6.3pp
off. The trajectories name the mechanism: from 1% the *first* command leaves the optimum, because at
a 1% window the window is trivially the denser region, so the law commands a large upward step and
the run then oscillates for the whole trace, with both of its audits crashing after a single walk
sample on a low-traffic sample. From the 30% plant a down-audit instead runs four clean strides,
confirms at a 2% window and parks. The descent machinery is fine here; the ascent out of the shipped
start is not, and the audit layer that should catch it crashes on the trace's own phase structure.
One cell of ten, reproducible in both directions, not traced to a defect. The open question is
whether a single-sample audit crash on a low-traffic sample is the general shape, since the bar is a
fraction of the rate frozen at the arm and a sample with an order of magnitude fewer requests is not
what that pricing was built for.

**Descent has to be measured deliberately, because no natural row exercises it.** Every gate row and
every real-corpus cell starts the cache where the product starts it, at a 1% window, and on the
frequency-favorable traces that is already the static optimum — so those rows pass without the
climber having to move, and a machine that could not *descend* would read clean on the whole real
corpus. Planting the initial window is the instrument. Measured, the descent is the density law
unobstructed at **4–6% of the maximum per sample**: the log-ratio error saturates near 1.5–2 nats
even at a badly wrong window, so the step never approaches its 30% cap, and a full walk down from
80% costs 13–16 samples. Recovery is therefore a function of the **sample budget and nothing else** —
replaying a trace four times takes an 80% plant from −16.05 to −3.63. Against the reactive law the
density tier is the better descender (its period is 4× the maximum against reactive's 10×, and its
step is proportional to how wrong the position is rather than a fixed decaying 6.25%), though
reactive holds the higher level at the shipped start on these cells. Two observations recorded
alongside: a failed probe undoes a correct descent in one capped move, which is deliberate and which
the cells absorb; and what a plant prices is the walk back after a regime change, which is tens of
millions of requests for a large cache.

## 4. The shipped design (the probe machine, >4096)

Non-starved samples are the pure density step. The additions:

1. **Starvation bar**: a region is starved when its sample hits < `max(4, requestCount >> 10)`
   (0.1%, floored at 4 — the floor binds whenever the sketch's entry-denominated cap makes the
   sample shorter than 4096 requests).
2. **Blind corner gate**: probe only when the starved window is small (window starved at ≤max/4 →
   probe up) or the sample is dead (both starved; direction away from the nearer bound, deciding
   at half). A starved main beside a large window arms nothing: the equilibrium audit owns that
   terrain (the upper-corner probe, main starved with window ≥3·max/4 → down, was deleted
   §5's corner entry; the `cornerprobe` harness arm restores it). A *large* region
   earning nothing is visible to density and must not trigger a probe (probing "for" a
   scan-filled main destroyed corda_large).
   The gate outranks the goal-metric branches, which is right for the guard rail (it adjudicates a
   shortfall *on* the starved sample) and was wrong for the audit (it adjudicates over the samples
   that follow): a blind corner that never clears served its whole refractory motionless while the
   clock said the position was due, since `refractoryLeft` is armed by a starvation walk's
   `undoProbe` and decremented only inside the hold (an audit's undo left it alone from
   before that every undo re-armed it, which deferred the corner's next probe by the
   whole rung after an audit that was not the probe's doing). A **due** clock now pre-empts that hold, a sample the machine
   was otherwise spending on nothing.
3. **The walk**: bold-driver seeded at the 6.25% restart magnitude in the probe direction,
   **scaled by the refractory rung — ×2 at rung 32, ×4 at rung 64, capped at the 30% max step**
   (a bound the current tables sit under — ×4 · 6.25% = 25% — so it binds only under retune)
   (2026-07 study: deep rungs previously bought *permission* to walk deeper but not *speed*, so
   escapes crept; rung-scaled strides punch through deep stray walls the flat seed stalled in —
   straywall2 +5..+11 across seeds and scales, d050 escapes fuller, corpus 205/205 ties since
   natural workloads never reach the deep *starvation* rungs — the audit ladder shares this ×4
   stride scaling and is routinely deep on real traces (`audit.rung = 64` on 5 of 12
   real density cells, `auditWait = 128` on 4), so an argument from rung unreachability must say
   which ladder it means; full undo on failure is unchanged, which is what
   separates this from the rejected v7 travel-budget family);
   direction flips only on |ΔHR| ≥ the **reversal** bar, which is not the crash abort's (see the
   crash-abort ending): priced for starvation probes, and for audits
   `min(5pp, AUDIT_BAR_FRACTION · max(baseHitRate, noiseBand))` — so plateau crossings survive
   workload jitter.
   Walks honor the 2% floor (via an else-if after the reversal check, so an undo-to-base wins over
   the floor clamp), and carry a **sample budget of 16** in `Walk.samples`, which counts up from
   the arm (it once borrowed the refractory countdown; the two uses never overlap, but
   sharing one field made the walk's depth an inverted expression at each use and forced both
   oracles to bound it by mode) so no walk is unbounded — the review proved the crash veto alone cannot
   bound walks (below a 5% base rate the absolute form was unsatisfiable, which `AUDIT_BAR_FRACTION` now
   covers, and it stays blind to damage under its own bar). Two
   subtleties the code carries: the walk's anti-freeze re-seed (`|step| < 2` re-seeds toward the
   probe direction) cannot be reached by decay in the density tier — the seed is ≥6.25% of a >4096
   maximum and sixteen 0.98-decays cannot fall below 2 — but the floor clamp assigns
   `step = min(-0.0, floor − window)` directly (the negative zero keeps a wall-blocked restart
   striding into the wall rather than flipping upward off positive zero's sign), so a
   floor-blocked down-walk zeroes its step, re-seeds into the wall each sample, and is
   re-clamped, burning budget in place (the stall note under State); and a
   refractory (blind-corner, starved, waiting) sample **is** a hold unless the audit clock is due,
   in which case the re-test takes the sample: it decrements the countdown
   and returns without a steering step (only the below-floor lift, item 7 — one definition,
   `Reading.atLeastFloor`, shared with the steering step so the two cannot drift apart). It used to fall
   through to the density arm, which is what the dead-phase rider exploited — a handful of
   window hits in an otherwise blank sample authorized the maximum step while the probe
   machinery was backing off.
4. **Walk endings** (each maps to a verified failure without it):
   - **Crash-abort** (hit rate ≤ probe-start − the walk-interior bar): undo to the probe's start
     and re-arm the refractory WITHOUT doubling — an exogenous phase shift is indistinguishable
     from probe damage here, and mispricing it as a failed experiment starves the re-exploration
     the shift calls for. A **starvation probe's bar is priced** against the workload's own
     scatter — `min(max(5pp, 3·rateDeviationEma), 15pp)`, the adv3 study: the fixed 5pp sat
     below real per-sample noise and severed the blind corner's only exit (the dosed mixture
     trap crash-cycled one stride off the floor for half its run, −3.4 below LRU, healed to
     +4.8 over by the pricing), while the 15pp cap keeps the abort's second job — bounding a
     damaging walk's excursion — after uncapped pricing let walks roam the all-blind families
     at a measured cost (metronome −0.6, recovered by the cap). An **audit's crash abort keeps the
     absolute 5pp**: a priced audit bar lets audits survive to confirm and park more, which is
     the R4-F1 amplification dial (zigzag −10.5, widepin below bar, demoflood −5.2 under
     `devaudit`). Its **depth** stays absolute, but
     the threshold is a level test against the rate frozen at the arm, so where the whole rate is
     smaller than 5pp the test is unsatisfiable and only the budget bounds a walk doing real
     damage (`arc_S3`: 0.0442 → 0.0216 while the window marched to the structural ceiling).
     `AUDIT_BAR_FRACTION` of the starting rate floors it there. That is not a depth pricing —
     every dead candidate *widened* the bar so walks survive longer — and it binds only below a
     third of the rate the threshold prices, leaving every family in the graveyard untouched.
     **The reversal does not share this bar**: the two exits read different
     statistics, so the crash abort is priced by the level alone and the reversal by
     `AUDIT_BAR_FRACTION · max(baseHitRate, noiseBand)`. `AUDIT_BAR_FRACTION` therefore carries two
     jobs, capping one exit and pricing the other, and the two share a fraction without sharing a
     bar.
   - **Reversal-through-base**: a bold-driver reversal that would cross back through the probe's
     own starting window found nothing — finish as a failed experiment (undo + ladder ×2) instead
     of walking out the other side (a ±5pp blip once converted an up-probe into an unterminated
     down-walk that pinned the window at a single entry).
   - **Adjudication**, when the watched region earns ≥ 4× the bar AND the walk has taken at least
     its **committed depth** (see 5). An up-probe confirms iff
     `ln((windowDensity+ε)/(walk.baseProbationDensity+ε)) > 0` — the probed window must out-earn
     main's MARGINAL rate, the probation density **frozen when the probe armed** — while
     down-probes keep the average-density test (`error·dir > 0`; the window has no marginal
     substructure to price against). Why marginal: climbing donates only the protected
     allocation, whose squeeze demotes into probation and displaces probation's coldest entries,
     so probation is the boundary a window grow actually taxes; adjudicating against main's
     AVERAGE (the rejected simpler form — four simpler verdict shapes were measured, each trading
     a family, see §5) vetoes genuinely-winning positions because the protected core dominates it
     (ablation: trickle 56.1→69.5/73.2/73.1 across seeds, bandtrap2 69.05→72.55, widepin
     45.5→57.7, sample-aligned phases 46.9→60.1). Why frozen: the walk's own demotions enrich live
     probation (a protected-saturating hot core demoted mid-walk earns furiously there), and live
     adjudication is an ABSORBING false-veto (demoflood: 58.94 pinned with zero confirms vs 67.17
     frozen) — the freeze's own cold-start-transient baseline is bounded and self-heals because
     every re-arm re-snapshots. Confirmation keeps the position and resets the ladder to 1
     (control passes to the density arm in the SAME sample); anything else fails (undo + ladder
     ×2) — neutral verdicts must fail or density walks the window home and the probe refires (S3).
     A zero baseline (probation earning nothing at arm) means any ≥4×bar earnings confirm —
     intended, the opportunity cost of a dead boundary is ~zero (nullchurn stays harmless). The
     named trade, priced and journaled: on low-HR bistable shapes whose probation concentrates the
     reuse band while an LRU-ward escape needs a diluted start (lowmix family, 2–7% HR), the
     frozen baseline false-vetoes an escape the old diluted-average verdict luckily confirmed —
     v5a lands the bad basin 3/3 seeds vs ship 1/3, with ZERO real-trace echo across the 32-cell
     defended set. The walk's exit bars are settled: a starvation
     probe's exits are priced against the live scatter, the audit's crash abort keeps its
     absolute depth capped at `AUDIT_BAR_FRACTION` of the frozen rate, and the audit's
     reversal — a first-difference test, not a level test — takes the same fraction of the
     larger of that rate and the noise band; every widening of the crash abort is dead (§5).
     Four simpler verdict forms were measured and each traded a distinct family — see the
     local study archives.
   - **Budget expiry**: failed experiment (undo + ladder ×2).
5. **Escalating commitment** — the resolution of the stray-exit dilemma. Stray and transferred
   hits scale ~linearly with the region's size while their *density* does not, so an absolute exit
   ends deep walks a stride short of a dense reuse band (making that trap absorbing), while
   removing the exit re-creates deep-walk displacement on thin-signal floors (w50/S-class, real
   traces). Neither rule wins both — four rule families were measured (§5). The shipped answer:
   first-round probes exit cheaply (commit 0), and each adjudicated failure lengthens the ladder,
   whose deeper rungs commit the next walk past the stray zone before the adjudication exit may
   fire (length 32 → commit 2 samples, 64 → commit 10). Confirmation is NOT exempt from the gate:
   early confirms on false mid-depth bands reset the ladder and neuter the escalation (measured:
   the long-trace escape fell 53.3 → 38.7 with the exemption). Short traces never reach the deep
   rungs, so thin-signal floors keep the cheap behavior exactly; steady deep-band traps escape by
   ~round 3 (≈100 samples) — an absorbing pin becomes a bounded, temporary dip (demonstrated:
   mixture_d050 scores 33.5 on a dip-length trace and 53.3 on a 3× trace as round 3 escapes).
   A confirm the density arm reverses in the same sample also deepens the ladder:
   the confirmed position is walked home at once and the corner re-arms, so a reset there restarts
   the ladder on every cycle of a dither that never reaches the band it is looking for. That was
   the majority ending of a starvation confirm (668 of 881 across the battery and corpus dumps),
   and pricing it as the completed experiment it is closes the reach half of `shallowmoat` and lifts
   `bandtrap2` +4.1, `trickle_s7` +2.8 and `trickle_s11` +0.9 (seeded, 8 of 8 on each),
   `strad_p8@4097` +1.9, `widepin` +1.7 (7 of 8) and `phases_d050@32k` +4.8 (4 up, 4 unchanged);
   `phases_d050` +0.3 with two seeds falling to its low basin, the N=8 mean above its bar. The
   confirm's handoff and its zero refractory are unchanged, so nothing is undone and
   the escalation is fast: rung 64 in ~10 samples on a steady dither. Its price is on the
   thin-signal floors with a dither in a low phase, where a deep walk armed in a trough has no
   crash abort (its base is the trough, §5's v7): `arc_DS1@1051635` −0.7 on a 10-sample trace and
   `deadphase` −0.2, accepted (§5 has the guard that removes both and
   what it costs), and on `norank_rep_r6` one seed of eight (41.1 → 20.3): a reversed confirm on
   a rewarded ladder deepens it, so the fail that follows waits 16 samples where the reward's
   ladder waited 2, and the ×1 walk that reached that seed's escape re-arms 25 samples later.
   A reversed confirm at the deepest commitment that the goal metric confirms (the audit's own
   streak and beat-base test, `Walk.isAuditGrade`) is an audit in all but name and is kept as an
   audit's confirm is: it parks. Any other starvation confirm still hands to density,
   so the cheap re-probing that phase alternation relies on is untouched. The park's own audits
   are covered by it: a crash-scale move while an audit walks out of a park is what that re-test
   produces and its ending returns to the park, so it does not stand the park down
   (`isWorkloadShift`); a shift with no walk in flight still does. Measured on shallowmoat, where
   a deep walk finds a band density then dismantles (36.4 on the wedge seeds against 30.7), and
   on `flood_j100` (+1.1 at N=8); bit-identical on the rest of the seeded battery and corpus.
   A kept confirm at or short of the farthest window the ladder's walks have already confirmed is
   a repeat: the machine found that ground and lost it, so a walk that only finds it
   again has not earned the reward, and the confirm keeps its handoff and its zero refractory but
   deepens the ladder as a reversed one does. The ladder remembers the farthest confirmed window
   per direction (`Ladder.remember`/`isRepeat`, kept and reversed confirms alike) and forgets it
   when a walk fails or crashes, since a walk that keeps nothing shows the terrain it was found on
   has moved. On `absolve_p8` the reward had pinned the rung at 1 on every lure period (a
   first-round stride catches the lure's +5pp shelf, density dismantles it when the lure stops, the
   corner re-arms); with repeats priced the ladder reaches 64 in six periods and the ×4 walk
   crosses the valley in two strides, parking as an audit-grade confirm at the top: 27.95 → 46.29
   at 8 seeds. Bit-identical to ship on every other cell of the seeded battery (64 cells at seeds
   1–2, the movers at 3–8) and the corpus (`arc_ConCat` −0.03 over four seeds, noise): the census
   of the shipped machine (15 cells × 8 seeds) found no kept confirms at all on `bandtrap2`,
   `trickle`, `deadphase`, `strad_p8` and `shallowmoat` (all wedges), and the kept confirms on
   `rep_r6`, `widepin` and `phases_d050` are followed by crashes, which forget. The memory is not
   cleared on the anchor's discard: the confirm sample is itself the lure's on-step, and where the
   walk lands at the previous anchor that step discards the anchor on the very sample the confirm
   is judged (measured 41.4 against 46.4 on `absolve_p8` seed 1).
6. **Refractory ladder**: length starts 16 and doubles per adjudicated failure to 64 (the arming
   doubles first, so effective waits are 32 → 64; after a kept confirm the next failure waits 2;
   a confirm the density arm reverses doubles the rung without a wait).
7. **Below-floor lift**: the density arm's clamp raises a sub-floor window to the 2% floor (the
   initial window is 1% and otherwise wedges under the documented floor). The blind-corner
   refractory hold honors the lift too — a workload whose every sample is blind never reaches
   the steering step, and the bare hold wedged the initial 1% window for the life of the run
   (adversary F4, metronome; enforcing the floor there costs the one thin-signal
   cell that was enjoying the wedge its documented price, w50 −0.5pp, siblings unmoved).
8. `setMaximumSize` resets the probe state, the pending `adjustment` (a probe-scale residue
   must not crush a fresh partition), and `sample.previousHitRate` (a baseline earned under the old
   geometry inverts the seeded direction of the first sample under the new one, at
   restart magnitude), and the sample period is
   `min(saturated 4·maximum, sketch.sampleSize)` — the sketch's sample is entry-denominated, so
   weighted maxima cannot inflate the period by the mean entry weight (a weight-units period froze
   weighted adaptation entirely), and a near-Long.MAX maximum cannot overflow it. After growing
   the maximum, the sketch keeps its old sample until the lazy half-full `ensureCapacity`, so the
   period transiently runs below 4·maximum while steps already scale to the new maximum —
   accepted; it self-heals as the cache fills.

Shape of the code: `determineAdjustment` closes the sample and `densityClimb` is a **router** —
it builds one `Reading` (the sample's derived view: both densities, the starvation bar, the
geometry every branch clamps against), advances the always-on observers, then dispatches down the
priority lattice to one named branch method per outcome. Each branch owns the rationale that used
to sit inline as a comment block, and the conditions are questions (`Reading.hasBlindCorner`,
`AuditClock.isDue`, `isBackingOff`, `Walk.shouldCrashAbort`, `Walk.isConfirmed`,
`Walk.canAdjudicate`)
rather than expressions needing a comment to read. An ending is priced by the layer that owns it:
`probeEnding` asks the crash question of every walk and then hands to `auditEnding` or
`starvationEnding`. Reading order for the whole machine is the router first, then the branch it
sends you to; sectional comments (`Cache Interface`, `Density Climb`, `Exceptional Scenarios`,
`Probe Walk`) carry that order, and the nested classes read in dependency order: `Sample` →
`Reading` → `Step` → the two laws → `Walk`/`Ladder` → `AuditClock` → `Anchor`/`Rates` →
`ProbeEnding`. Behavior lives with the state it reads (Beck's tests, applied mechanically in the
2026-08 refactor sweeps); repeated quantities have one definition each — `Ladder.stride` (the
rung-scaled stride the walk and the audit's room rule both read), `Reading.maxStep`,
`Reading.upperCorner` (the wall line that the blind corner, the probe direction, and the
audit's direction rule all test), `Step.restartMagnitude`, `Reading.cappedStride`, and
`sample.hitRateChange` (the cross-sample difference the reactive law, the walk's bold driver,
and the stand-down's shift trigger all steer by) — because two spellings of one quantity is how
the room-rule drift happened.

State: fields on the package-private `WindowClimber` (reached through the `climber` field that
`AddMaximum` generates), eviction-locked, cold path only. The flat fields are grouped into nine
small objects — `Sample`, `Step`, `ReactiveClimber`, `DensityClimber`, `Walk`, two `Ladder`s,
`AuditClock`, `Anchor`, `Rates` — each carrying the behavior that reads only its own state. The
grouping is the write-owner map made structural: each row below is one object, so a cross-layer
write has to name the other owner to happen at all (landed object-by-object, each stage verified
bit-close against the pre-refactor tree by the paired battery). The tuning constants live inside
the class that reads them; the one exception is `RESTART_THRESHOLD`, kept on `WindowClimber`
deliberately because four mechanisms in four classes read it (the reactive restart, the walk's
audit-side bars, the stand-down's shift trigger, the audit direction's stale test) and giving it to
any one would misstate who owns it. Where a constant has one owner but a second reader, the second names it
(`Rates.VETO_MARGIN_MIN` floors the audit confirm; the fresh-park shield is
`AuditClock.AUDIT_WAIT_INITIAL` long), which keeps the sharing visible.

**Both control laws are objects, and the climber is the supervisor.** `ReactiveClimber` holds the
regime predicate, step rule, decay and period; `DensityClimber` holds the tier gate, the
proportional step and its period; each steers the one shared `Step` (two laws, never concurrent),
and `determineAdjustment`/`samplePeriod` are two-line switches over
`DensityClimber.appliesTo(maximum)` so the tier boundary is written once. This is §2.1's
supervisory switching made structural: everything else on `WindowClimber` — the probe machine,
the audit layer, the anchor — is exception machinery the supervisor arms when a law cannot be
trusted. The two laws keep different verbs on purpose (the reactive one *climbs*, the density one
*steers*), and the two bold drivers (`ReactiveClimber.climb` and `nextStride`) stay separate:
same algorithm, but their bars, reversal conditions, and return conventions differ, and §4/§5
record each difference as load-bearing.

**Declined refactors, decided rather than deferred:** no `WalkMachine` and no method object for
the router — the branches write `refractoryLeft`, `undoRemaining`, `walk`, the anchor, the audit
schedule and the *other* layer's ladder (the documented bridge writes), so either extraction
needs a back-reference and would move the cross-layer writes out of the class that owns them.
`refractoryLeft` cannot join `Ladder` (the shared type would give the audit's instance a
permanently dead field), and `undoRemaining` deliberately *outlives* the walk, which is why it is
a climber field rather than `Walk` state.

The **write-owner map** — which layer's endings may move which fields — is the boundary the
H4-C1/F2 repairs restored; keep new writes inside their owner:

| Owner | Fields | Notes |
|---|---|---|
| Observation | `sample` (a `Sample`: `hits`, `misses`, `windowHits`, `probationHits`, `previousHitRate`) | the counters are zeroed at sample close; `previousHitRate` deliberately keeps a different lifetime, since it is the memory ACROSS samples that the reactive climber's direction and the walk's bold driver compare against — `close(hitRate)` carries it forward while zeroing the rest, and only `reset` (a resize) discards it. `WindowClimber` keeps thin `recordHit`/`recordMiss`/`resetSample` delegates because `BoundedLocalCache` calls them on the read and write paths |
| The active walk | `walk` (a `Walk`, null while none is in flight: `ladder`, `isAudit`, `down`, `baseWindow`, `baseHitRate`, `baseSmoothedRate`, `baseProbationDensity`, `samples`, `belowBarStreak`, `aboveStreak`, `beatBase`), `undoRemaining` | one walk at a time. It is an object rather than eleven flat fields, so "dead state while not probing" is the absent object rather than a comment, and a reader must hold a walk to ask it anything — `armProbe` is the complete constructor, `endWalk` clears the field, and the router keeps the ended walk in a local because the undo that prices it still reads the bases. The bases are `final`: frozen-at-arm is the property the verdict studies keep re-deriving (§4's "why frozen"), so the compiler now holds it. `walk.ladder` is the arming layer's ledger, which makes "an ending may only deepen the machine that produced it" a reference rather than a lookup; `refractoryLeft` is the starvation refractory alone and belongs to the row below |
| Starvation retry | `starvation` (a `Ladder`: `rung`, `crashStreak`), `refractoryLeft` | moved by starvation endings only (an audit confirm cheapens `starvation.rung` and zeroes `refractoryLeft`, the one journaled bridge write, spelled out at its site rather than hidden behind `Ladder`'s methods; an audit's undo leaves the refractory alone) |
| Audit retry + schedule | `audit` (a `Ladder`), `auditClock` (an `AuditClock`: `down`, `waitSamples`, `stillSamples`, `lastWindow`) | moved by audit endings and the position-stillness clock only. The clock owns `tick`/`isDue`/`restart`/`reset`, so the stillness rule (a moving sample **decays** the run, it does not zero it) lives with the counter it governs rather than in a climber method. `reset` deliberately leaves `down` standing — it alternates across audits for coverage and a resize has no opinion about which side to explore next, which is why a resize did not clear it before either. `rescheduleAudit` stays on the climber: it reads the ladder's rung and writes the clock, so it belongs to neither alone |
| Goal guard | `anchor` (an `Anchor`: `window`, `rate`, `held`, `freshLeft`, `returning`, `returnLeft`, `shortfallStreak`), `rates` (a `Rates`: `smoothed`, `deviation`) | anchor/park/veto authority and the rate references. `Anchor` is the memory *and* its defense in one object because the layer's three invariants run between those parts, and it now holds them by construction rather than by assertion: a shield lives and dies with its park (`park`/`hold`/`release` are the only writers — an audit's confirm arms a shield, a rail veto holds without arming or spending one, since the shield's clock belongs to the confirm that armed it), a park defends only a planted anchor (`discard` takes the hold with it), and a return implies the park that follows it (`beginReturn` arms both). `isAt`/`isAwayFrom` give the band test one definition instead of three inline copies, and they are deliberately not each other's negation — an unplanted anchor is neither at nor away, and there is no claim to veto against. `Rates` owns the EMA pair and the two bars priced off it — `noiseBand` is the three-deviation width, `vetoMargin` is that floored at `VETO_MARGIN_MIN` — so the rail's margin and the starvation probe's walk-interior bar read one definition instead of recomputing `VETO_MARGIN_SCALE * deviation` apiece. The deviation is read LIVE, and the audit's confirming streak is deliberately not priced off it; both notes live on `noiseBand` itself. A stand-down that discards the claim re-seeds the pair (below): the event that invalidates a claim invalidates the reference the claim would be re-planted from, and the two are one layer's state |
| Motion out | `step` (a `Step`: `size`), `reactive` and `density` (a `ReactiveClimber`/`DensityClimber`, each holding that same `Step`), `adjustment` | the single per-sample command. `step.size` and `adjustment` are NOT one object despite both being written once per completed sample: `adjustment` is drained by `BoundedLocalCache` across maintenance cycles as the transfer carry-over, so it changes at the cycle rate while the step changes at the sample rate. Both tiers write `step.size` — they are alternatives selected by the maximum, never concurrent, and `resized` re-seeds it — which is why each law holds a reference to the shared `Step` rather than a copy of its own |

Deliberate cross-writes (measured, kept): an audit confirm resets the starvation ladder to one
(cheap re-probing; neutral) *and* clears `starvation.crashStreak` with it, since a reset ladder
carrying a live streak re-escalates on the next crash; and an audit undo re-imposes the
starvation machine's own refractory (dropping it churned the blind-corner families). What an
ending must never do is write the *other* layer's ladder, streak, or schedule: a starvation
confirm leaves `auditClock` (with it the cold-start calibration) and `audit.rung` untouched, and
audit endings never deepen `starvation.rung` — each direction is pinned in
`WindowClimberTest` and range-bounded by the fuzzer/subject oracles.

Each layer's rung and crash streak live in a `Ladder` the layer owns, so a
cross-layer write has to name the other layer's ledger to happen at all, and the two sanctioned
bridge writes are the only places that do. That separation covers **every** ending, not just crashes. A *non-crash* ending retires only
the crash streak of the layer that owns the walk: an audit's budget expiry leaves
`starvation.crashStreak`, and a reversal-through-base leaves whichever streak it does not own
(pinned by `audit_budgetExpiry_leavesTheStarvationLedger` and
`walkStep_reversalThroughBase_leavesTheOtherLayersLedger`). Both sites previously cleared
both streaks, which disarmed the *other* layer's two crash responses — the escalation at
`PROBE_CRASH_ESCALATION` and, for audits, the `AUDIT_CRASH_PERSISTENCE` tolerance that arms
at a streak of one. A starvation probe ending between two audit crashes therefore restored
the one-sample abort that the moat and the H4-C1 pulse train specifically need it not to
have: the crash-semantics fix was reachable around by an interleaved blind corner.
`setMaximumSize` additionally zeroes the sample counters and re-seeds the step direction; it
drops the walk, which is how the direction and bases that used to linger as dead state are now
discarded with it. A deep walk that reaches `mainProtectedMaximum == 0` stalls (transfers have
nothing to take) while its budget keeps burning — measured 7 of 16 budget samples at the ceiling
in one blind-review round; benign but known. The floor-side mirror exists and rung-scaled strides
sharpen it: a rung-64 down-walk reaches the 2% floor in ~4 strides and spends the remaining ~12
samples standing still before budget expiry prices it as a failed experiment (2026-07 post-ship
review). Adjudicated as intended: the wall IS the probed extreme awaiting adjudication (main
earning ≥4× the bar there confirms), the budget must keep burning or a wall-sit is unbounded,
wall samples counting toward the commitment depth is what lets a wall position adjudicate at all,
and expiry pricing stays uniform because ladder escalation on walled families is load-bearing for
the deep-band escapes. That adjudication covers walks whose wall-ward direction is *forced* (a
starvation probe's direction comes from the starved region); an **audit chooses its direction**,
and `auditDirection` refuses one with less than a full stride of room — a window in
(2·floor, floor+stride] used to be sent into the floor with sub-stride room by the first
interior toggle, clamping on the entry stride and burning 15/16 budget samples motionless
before the information-free expiry doubled both the refractory ladder and the audit clock
(adversary round, F2: mixture d025@32768 spent 54% of the trace at its pin; the
room rule is worth +8.1/+9.7/+11.7pp on the mixture-audit family with every other gate row
unchanged). What was a real bug — a restart-scale improving sample at the wall
striding upward off positive zero's sign — is fixed by the negative-zero clamp and
regression-tested in `WindowClimberTest` (the wall-sit scenario replays the review's repro).

**Why the tiers stay:** the `hybrid-all` rejection at cs@563 (−1.27) rules out density at *every*
size, and the D2 study closes the weaker question of where the boundary belongs:
`corda`'s own tier crossover sits between 2048 and 4096 (density − reactive reads −0.61 / −0.86 /
−0.50 / **+0.43** at 512 / 1024 / 2048 / 4096), so the threshold is within one octave of the
sensitive trace's crossover even though the aggregate crossover is between 563 and 1024. The
placement is set by the worst case, not the mean (§3, the tier boundary).

**The goal-metric layer (the F4 answer; shipped, then hardened).**
The probe machine's trigger taxonomy is starvation-only, and a workload can hold the density arm
at a **sighted false equilibrium** — unstarved, earning enough to be believed, and wrong (F4:
whisper's trickle keeps the window sighted; the mixture family re-pins quietly at scale). The
layer that closes it: an **equilibrium audit** arms the walk machinery from a position held
still for `AUDIT_WAIT_INITIAL` samples — the clock counts positional stillness only, decays by
one on a moving sample rather than resetting, and its schedule is owned by audit endings alone;
the FIRST audit after a (re)size is a calibration probe at `AUDIT_WAIT_FIRST` = 4 — and is
judged by the **goal metric**, `AUDIT_CONFIRM_STREAK` consecutive raw samples above the
reference frozen at arm plus the one-shot `auditBeatBase` gate, because density holds the
equilibrium under test and would veto every walk away from it. A confirm plants the **anchor**
and **parks** without a parting density step, shielded from crash-scale weather for one initial
audit wait; the **guard rail** vetoes a noise-cleared sustained shortfall back to the anchor.
The margins are deliberately split (rail 3·dev; confirm run-length plus beat-base), each layer
owns its ladder, crash streak and schedule with exactly two journaled bridge writes, and audit
crashes price persistence in **time** (`AUDIT_CRASH_PERSISTENCE` on the retry) rather than bar
depth. A park's first audit follows the walk that confirmed it: the confirm ends a
walk on evidence of improvement rather than its exhaustion, so the ground beyond is unexplored
while the ground behind was just covered, and the alternation was sending that audit back through
it (`absolve` p16: down from the 32% park into the lure's knee, a crash, the undo's arrival
discarding the anchor; `shallowmoat` basin B and `veilmoat`: down from the calibration park toward
the floor it came from). It follows only while the claim stands, the park still held at the arm
and the smoothed rate within a restart threshold of its value at the confirm
(`AuditClock.settle`/`chooseDirection`); a rate that moved that much with the window still says
the workload moved and the walk's direction says nothing about the terrain. Unguarded, the rule
swept `climbtrend_up`'s trend misconfirms wall to wall (−0.9 at N=8, its sentinel: the 6570 park
starves main of the growing hot set) and re-phased `whisper_mod_p6`'s re-escape (−1.76, its
sentinel), and on `moat_h5000` its far-slope walk (five strides down a 1.5pp-per-stride decline
before the 5pp bar trips) made the next audit the tolerant retry, whose undo evicted the band
(−1.75); the guard returns all three to ship. It also fires, incidentally, after a step confirm
whose smoothed rate at the confirm still reflected the walk's path (`moat_h3000/h4000`,
`flood_j100` on seven seeds of eight, `shieldtrap_s13` seed 1), where the continued direction had
been a coin flip (moat_h3000 +1.6, h4000 +0.8, h5000 −1.75, flood +4.1); those stay on the
alternation. Priced at N=8, arms rotated: `absolve` 24.77 → 41.46, `absolve_x4` 36.45 → 46.89,
`veilmoat` +3.7, `shallowmoat` basin B +2.7, `flood_j100` +0.81 (one seed +6.5), `saw_p40` +0.77
(8 of 8), `whisper_mod_a12` +0.24, `shieldtrap` +0.2, `scarburst` +0.19; `mixture_d010_long`
−0.27 (8 of 8), `demoflood` −0.16 (8 of 8; the calibration audit's confirm overshoots the walk's
own best sample and the continued audit wastes two samples before the crash returns it),
`whisper_mod_p12` −0.05; the corpus `cp_w050` +1.07 and `cp_w044` −0.17 on one seed each, the
rest bit-identical. Alternation resumes after that audit. Invariants and don't-harmonize warnings
live in `rules/design-decisions.md`.

**What the guard rail is for, and what it is not.** It fires rarely, and for two distinct reasons
that were once recorded as one. On blind-corner cells it is **not reached** at all: the refractory
hold outranks its branch, and the starved samples the hold claims are exactly the stalest ones.
Where it is reached, which test binds depends on the population — on constructed cells the
**margin** does, with the shortfall present and large while the streak never reaches one; on real
cells the **streak** does, the shortfall holding for many consecutive samples while one clearing
sample resets it. The corollary worth keeping: between the veto's `rate − 3·dev` and the confirm's
`rate + VETO_MARGIN_MIN` lies a band in which **neither goal-metric layer can act**, and the
machine spends real time there.

Deleting the rail is rejected by the shallow moat doses, where it is the only mechanism that
recovers the window and several audits arm over the run without rescuing it. Everywhere else
removal is free, and on the real cells where it fires it is worth about −0.01pp. So it is kept
because it is free and is the sole exit on a rowed adversarial family — which makes the case
conditional: **if the moat rows were ever retired, the case for the rail goes with them**, and §5's
`agedown` kill, which rests on disarming the rail, would need re-grounding. Two facts not to
re-derive: the streak is not consecutive **in time** (walk, undo, return and blind samples neither
increment nor reset it), and `beginReturn`'s hold is cleared only incidentally, when the return's
own rate move trips `isWorkloadShift` — which suppresses density steering, not exploration, since a
due clock outranks the hold.

**`AUDIT_CRASH_PERSISTENCE`'s tolerance is one-shot by construction.** Any non-crash ending,
including the tolerant retry's own budget expiry, retires the crash streak. A workload that runs
crash → fail → crash → fail for its whole trace therefore ratchets the ladder while learning
nothing; the streak legitimately measures *consecutive* crashes, and the one-shot form is
deliberate rather than an oversight.

## 5. The graveyard (do not re-explore without new insight)

2026-05 sweeps (~40 variants on real BLC, see `large_cache_climber_findings` memory): PID and all
descendants (integrator + no setpoint = corda catastrophe), feedback/ghost signals (break on loop),
Borkar, Smith predictor, RLS parabola, shadow/probe/MAB state-replacement (noise-driven switching),
EWMA smoothing alone, dead-zone smoothing (`ap_dz`, 28 cells, 6 big wins / 3 big losses — "each
fixes some, breaks others"), Gini/Indicator classifiers (skew does not predict the optimal
window), POSINIT. That campaign is the reason the density tier exists: it was a two-session
attempt to improve the **reactive** law at >512 and its verdict was "ship nothing". Its metric was
relative %, which the project later disowned, so the individual rows are weak evidence — the
dead-zone row in particular was re-tested properly in 2026-08 (below).

2026-07 density-era escapes (Opus session): kickoff-to-center (false-fires on w50 −8), EWMA-regret
(transient — re-traps after the EWMA catches up), annealing HR-walk (inconsistent), wide start
(fixes stress, destroys frequency traces −15), lowering/raising the floor alone (symptom patch).

2026-07 probe-machine ablations (this design's own dead ends, all measured — the tradeoff surface
is real, not tunable away):
- **v6 small-entry probe + absolute exits**: stray hits scale ~linearly with window size while
  their *density* does not, so an absolute hit-count exit fires just short of the reuse band and
  fail-adjudicates the probe (mixture_d025's escape died from a one-step stride change).
- **v7 density-competitive exits + travel budget** (¼ max when the other region is healthy,
  uncapped when dead): principled, kept every escape — but deep walks displace healthy-main
  workloads (w50 doubled to −2.1) because a probe that starts in a low workload-phase never
  triggers the crash-abort.
- **v8 give-up-jumps-ladder-to-max**: no help; the *first* deep round is the cost.
- Verdict logic weaker than confirm-or-fail, and any threshold-only starvation test: see §3/§4.
- **v9 slam-continue** (walks continue past unconfirmed 4×bar hits under the budget): escaped
  every deep synthetic (d050 +25, widepin +15, ratchet +3) but re-imported v7's deep-walk damage
  on real thin-signal traces (w50 −2.2, S3 −1.5 vs the locked design) — the ablation that proved
  the two-class tradeoff is rule-independent and motivated escalating commitment.
- **Early-confirmation exemption from the commitment gate**: false mid-depth confirms reset the
  ladder and neuter the escalation (d050_long 53.3 → 38.7 on the original binary; the exemption's
  harm did not reproduce at N=3 on the shipped binary — era-scoped, the gate stays strict on the
  measured tradeoff surface). The gate is strict.
- **Live-probation adjudication (the marginal verdict's unfrozen variant)**: measured as an ABSORBING
  false-veto on the demoflood construct — a protected-saturating hot core demoted mid-walk earns
  ~11.8/slot in probation, so every walk is vetoed and the machine pins at the floor with zero
  confirms (58.94 vs 67.17 frozen vs 74.10 incumbent-on-that-shape). The pre-walk freeze is
  earned, not decorative; on every non-saturating family live ≡ frozen (mixture d010/d025/
  d050_long identical).
- **Average-density up-probe verdict** (the rejected simpler adjudication): vetoed real
  reuse bands whenever the squeezed main stayed denser on average (F3 trickle −14..17, B1
  bandtrap2 ×3 discard, widepin/aligned-phases pins) — replaced by the probation-marginal frozen
  baseline; its one superiority (lucky diluted-average confirms on lowmix-class bistable low-HR
  shapes) is the named, journaled trade.

2026-07 fresh-eyes study kills (all pre-registered, data in the
local hill-climber-study workspace ledger): per-sample goal-metric triumph
confirm (spoofed
by phase-rise traps; ratchet/troughwalk2); verdict-gated triumph (widepin +9.7 but two defended
traps regress and the pin persists); probe-time sample compression (halved-sample verdict noise
re-pins mixture_d040 −24); sketch-as-ghost repeat-miss lean (null — never engaged its cells);
confirmed-window warm start (null — widepin never first-confirms); boundary marginal-cost damping
(moved the give-backs +1.5 for the first time, but the admission-boundary comparison is degenerate
by construction — victims are always sketch-hotter — and the equilibrium-gated variant is inert);
and four honest-window-hits verdict forms (absolute / vs-main-average / vs-own-baseline /
vs-baseline-with-bar-floor), each trading a distinct family — the verdict-design tradeoff surface
in that study's report. The one survivor shipped: the rung-scaled walk stride (§4, the walk).

### What each step is worth (the ablation prices)

Every step against the whole battery, 91 cells at seeds 1 and 2, beside the firing counts from a
ship run of each cell. Nothing is dead: `corner` reads 0 because that step was deleted, and every
other site fires. What each step buys where it acts, against what it spends elsewhere: the
retest 33:1, a repeat confirm's escalation 22:1, a park's first audit following its walk 20:1,
the frozen probation baseline 17:1, the audit layer 13.5:1, the return and retreat cover 9.3:1, a
reversed confirm's escalation 7.6:1, the walk's commitment depth 5.3:1, a blind corner's probe
4.9:1, the fresh-park shield 4.1:1, the refractory ladder 3.9:1, the rung-scaled stride 3.7:1.

Both candidates are closed. Restoring the deleted upper-corner probe (`cornerprobe`)
costs 9.61 across 18 rows against 4.32 across 5, and 27:1 against on that cell set
itself, so the deletion holds on the evidence that flagged it. The walk's commitment depth read
0.23:1 at N=8 then and 5.3:1 now.

**The guard rail's veto is the one price that inverted, and the battery cannot decide it.** On the
that cell set at the same seed it fell from 11:1 to 1.86:1; over the whole battery it reads
0.28:1, and at N=8 across the eight cells either rail arm moves it reads 0.33:1 with 10.95 of its
12.87pp cost on `sidecliff` alone (−1.34 to −1.42 on all eight seeds, while `cp_w015` splits by
basin, +0.26 on five seeds against −0.26 to −0.37 on three). Every battery cell starts where the
product starts it, and a rail that returns the window to an anchor has little to defend from a 1%
start. Planted, it is worth **+6.3 to +6.6pp on four of four seeds** on `mainsat` at a 55% window
(32.42–32.68 against `noveto`'s 25.90–26.65) and +0.15 to +0.52 at 70%. The step stays and the
`mainsat` plants are what holds it; without a planted cell the battery reads it deletable. On
`sidecliff` the rail is a net cost on every seed and the retest is what bounds it, since
`noretest` there takes seed 2 from 59.25 to 51.63.

**The retreat cover's widening is real and small.** The commit that moved the retreat's
cover out of `isParkTest`, so it now runs without a held park. `nowidecover` (the cover scoped
back to a held park, the return half kept) is bit-identical on 66 of 68 rows and costs 0.07 and
0.08 on `cp_w081`'s two seeds. So `noreturncover`'s 44.12pp belongs to the held-park retreat cover
and the return half rather than to the widening, which is why the moat and `hazefloor` rows move
under that arm while the commit landing the return half read them bit-identical.

### A detected regime change must not restart the audit schedule

The latency face of the top-corner residual: a discarding stand-down (`isWorkloadShift` and `standDown` with the
window on the anchor) restarts the audit schedule, so a detected regime change reschedules
exploration instead of leaving the rung and the wait the ended regime earned. Seven forms of the
one candidate, each read from trajectories on `latebloom`'s alignment ladder at seed 1 and priced
on the rows that wanted the backoff seeded 1–8 (`metronome`, `h4c1_attack`, `h4c1_reverse`,
`whisper_mod_p6`, `whisper_mod_p12`, `whisper_mod_a12`); the reset forms on the moat rows seeded
1–8; the two live forms on the unseeded battery with every mover re-adjudicated at seeds 1–8
with the arms rotated inside each seed, the floors and the corpus:
- **rearm** (the wait drops to `AUDIT_WAIT_FIRST`; stillness and ladder untouched) and
  **rearmstill** (the stillness run restarts as well): inert at the 64 alignment, since the first
  re-armed audit goes down from the interior rest (the alternation state) and its failure at rung
  64 re-doubles the wait to 128; `rearm` reads `whisper_mod_p6` −5.35 and `phases_d050` −1.9
  unseeded.
- **rearmreset** (`auditClock.reset` and `audit.reset`, the cold schedule a resize gives) and
  **rearmboth** (the retest's discard as well): the round-3 `hardreset` kill re-entered through
  the discard. `whisper_mod_p6`'s period-6 swing is ±6–7pp, every swing at the floor anchor
  discards it, the discard zeroes the stillness run, and with discards every three samples the
  clock never reaches its four-sample wait: no audit arms in the whole run, the window sits at
  the floor throughout, 55.58 against 66.01 on every seed (the F4 pin). `whisper_mod_a12` −2.9 at
  seeds 1–8, `mixmod_a010` −12.2, `widepin` −5.7 and `phases_d050@32k` −5.3 unseeded, `moat_h7800`
  −1.1 at seeds 1–8. The pin `auditClock_rateSwings_doNotResetStillness` fails on this form.
- **rearmcold** (every discard; the wait drops and the ladder resets, stillness kept): a
  redistribution across the battery rather than a refinement. `slowswap_step` 40.41 → 31.23 (−7 to
  −13 on seven seeds), `absolve_p8` 45.95 → 42.50 (−5.7 on four), `mixnoise_a10` 60.70 → 59.37 on
  every seed (its bar is no drift below 60.4), `h4c1_reverse` below LRU on three of eight seeds
  (65.39 → 62.84), `parkveil` −5.2 on one seed; against `whisper_mod_a12` 63.59 → 66.03 on every
  seed (above LRU, and deterministic where the row was bimodal), `crashnoise_a12` +3.0 / +5.0 on
  every seed, `parkveil_min` +5.0, `mixmod_a010` +2.1, `whisper_mod_p6` +0.83, the witness +8.75 on
  five seeds. The moat rows bit-identical; floors and corpus within noise. On `h4c1_reverse` the
  low-phase pulse discards the unheld floor anchor, the wait drops with the stillness run intact,
  so the audit arms on the discard sample with the pulse's dip as its base, reads the pulse's end
  as its own reversal, and each such walk spends the rung: the 63% confirm the shipped machine
  reaches at s46 comes at s101.
- **rearmheld** (the cold schedule only where the discarded anchor was a held park, read before
  the stand-down releases the hold) and **rearmheld2** (the same with `settledRate` kept, which
  changes nothing on a12 and costs 0.5 on p6): the closest form. Battery 59 of 75 unmoved and
  ten of the sixteen movers bit-identical at seeds 1–8 (the bimodal rows' draws); the moat rows
  bit-identical at seeds 1–8; `metronome` and the h4c1 rows bit-identical; floors within 0.2;
  corpus within the spread (`cp_w100` 45.77 → 45.23 against a 0.36 spread, `cp_w098` −0.30). It
  earns `whisper_mod_p6` +0.32, `scarburst` +0.54 and the witness +3.79 on five seeds, and it
  pays on the F1 pair: `whisper_mod_a12` 63.59 → 61.72, −2.2 on seven seeds against the row's
  ≥ 62.8 sentinel and 2.65 below LRU, and `crashnoise_a12` 61.69 → 60.13, the high basin lost on
  four seeds. The post-shield swing discards the park, the restart arms an audit four samples
  later from the park's position, and on the ±12pp swings those walks crash and spend the rung
  where the kept wait spaces the audits out.
- **rearmheldcold** (the held gate with the stillness kept): `whisper_mod_p6` 66.01 → 63.31 on
  every seed, since the post-shield discard arms the audit on the swing sample itself; `a12`
  −0.39; `metronome` and `h4c1_reverse` bit-identical.

The latebloom earnings were the same in every reset form and are the candidate's measure. Where
the stand-down fires the gap to the ceiling falls 16.7 → 6.4 (phase 1 = 64), 15.4 → 5.6 (80),
11.3 → 4.9 (120), 9.8 → 4.9 (132) and 14.9 → 5.2 (64 with a 220-sample phase 2); the base is
bit-identical at every seed, since its arrival lands inside the retreat cover and no stand-down
fires; the witness rises on the five seeds whose arrival straddles the cover's end (−8pp on the
landing sample, covered, and −6pp on the next, not); the 48 alignment pays 3.5 → 6.9, an audit
fired from a still-good unheld position four samples after the undo-landing discard walking onto
the flat shelf and failing at budget; and the residual in every form is the alternation's side
choice from the interior rest point, the first re-armed audit spending sixteen samples walking
down to the floor before the next one goes up. The clock alone cannot carry the deep rung, and
the ladder reset is load-bearing: with the rung kept, the first re-armed audit's failure at rung
64 re-doubles the wait.

No form gates clean because the discarding stand-down is the machine's one regime-change
detector and it fires on every crash-scale move at the anchor, which the periodic families
produce every few samples (the track-planted anchor at every swing, the park at the first
post-shield swing) and the pulse family at every dip. The shipped machine pays those discards
with the anchor alone and keeps the schedule. Letting them touch the schedule loses whichever row
the form's shape exposes: the stillness restart starves the clock on the swings, the kept
stillness arms on the dip, and the held gate trades one whisper_mod row for the other. The
distinction a landable form needs, a level change that persists against a swing that returns, is
the signal-classification wall (§5, *Ageing an off-anchor claim*); a restart deferred until the rate has stayed a margin
below the discarded claim through a settle is a new state inside the confidence-gate graveyard
and was not built. The seven arms stay in the harness under one flag table (`rearm*`), so the
square can be re-priced after an alternation change.

### Guarding the probe's arming, and re-reading its verdict, on a regime-change sample

Four guards on the starvation probe's arming, built for `arc_DS1`'s trough-armed confirms at a
1M maximum (§3's thin-signal floors; the v7 shape above), each priced on the unseeded battery,
the floors and the corpus, with the movers re-adjudicated at seeds 1–8 and the arms rotated
inside each seed:
- **shiftarm** (a blind corner on a sample whose rate moved by a restart threshold from the last
  closed sample holds one sample before arming): DS1 11.99 → 13.74 at 1,000,000 and 13.45 →
  14.77 at 1,051,635, floors and corpus within noise, and the phase-alternation families lose
  their escape, since the flip sample is the blind sample and a hold on it arms nothing when the
  next sample is sighted: `phases_d050` −10.6, `widepin` −9.4, `phases_d050@32k` −4.4,
  `shallowmoat` −2.7 (unseeded N=8/8/5/2), against `mixture_d050` +5.7, `blindlock_blind` +5.4
  and `metronome` +2.3, where a shift-armed probe was failing and imposing its refractory.
- **shiftarm0** (shiftarm, and the first sample holds as well): the above plus `demoflood`
  −10.6, `absolve` −5.2 and `slowswap_ramp` −2.9. The first-sample probe is the escape on those
  rows, so a first-sample hold on its own (`coldhold`) was not run.
- **shiftdead** (the hold only where the sample is dead, both regions starved, and a shift: the
  v7 walk armed in a trough and nothing wider): DS1 +1.73 at 1,000,000 and +0.42 at 1,051,635,
  since two of the four arms there are on samples with main at 8k–146k hits against a bar near
  4k (the wedge round's "low samples straddle the starvation bar"); 14 of 16 re-adjudicated
  cells bit-identical at every seed, `widepin` 55.48 → 47.80 (five of eight seeds at −8.4 to
  −13.7; its bar is no per-seed drift) and `phases_d050` −0.69 on four seeds.
The rows that pay are the ones whose escape is a probe armed on exactly the sample the guard
holds.

The verdict side was then exhausted on Ben's ask, three forms on the same cells and battery:
- **rebase** (a starvation walk whose sample rises by a restart threshold re-freezes its base,
  rate and probation density, on that sample once and walks on; **rebasepos** returns the confirm
  to the rebase sample's window): DS1 13.83 / 14.62 at the two sizes, 27.44 at 2M (an extra
  stride), and `phases_d050@32k` −13.7, `phases_d050` −12.3, `widepin` −5.9, `norank_rep_r6`
  −3.2. Re-freezing the probation baseline on a live sample is the `nofreeze` failure for the
  escape: the walk's own demotions enrich live probation into a veto.
- **deadbase** (a walk armed on a dead sample is adjudicated against the live probation density):
  DS1 14.07 / 13.59 (alignment-limited as `shiftdead`), `slowswap_step` −6.7, `widepin` −5.2,
  `phases_d050@32k` −3.5.
- **deferrise** (a crash-scale rise defers the verdict one sample, holding the position, with only
  the crash reference moved and the verdict baseline kept frozen at the arm): DS1 13.91 / 15.12
  (above the hit-rate law's 14.91 at the gate's size), 2M −0.09, S3 +0.13, and `phases_d050@32k`
  −13.2, `widepin` −9.4, `phases_d050` −7.4.
Every form that refuses or defers a verdict on a regime-change sample loses the phase-alternation
escapes, since on those rows the flip sample is the only sample the verdict can be read in: the
regime alternates at the sample cadence as DS1's does, and there the larger window is right. At
the decision the two are one signal. DS1's cost at this size stays the wedge round's accepted
price; `wedgeshift` (above) remains the recorded guard for the shape, still not
landed; the untried lever is the sample period itself (`dens10x` reads 14.34 at 1M by taking four
samples instead of ten, and on S3 +0.2 / +1.0 / −0.6 / −0.3 at 100k–700k), a cadence question
rather than a rule for this shape.

### Whole alternative controllers

- **Marginal-loss controller: locally right is not globally sufficient.** Exact loser-refault
  attribution was locally informative: all seven screened deltas were non-negative (0 to
  +1.80pp). The controller built around it still failed its final-regret gate. Moving a fixed 1%
  of capacity every 2C requests left the ten-cell nearest-rank p90 regret at least 2.83pp against a
  1.5pp bar. P3 states the limitation cleanly: hit rate falls while moving from the 1% start toward
  roughly 20%, then rises to a remote 80% basin. The local signal correctly points back toward the
  floor and therefore cannot discover that basin. Adjacent-direction accuracy and global search
  regret are separate gates. Keep the signal as a possible diagnostic or local trim input, not as
  evidence for a replacement climber.
- **Replicated MiniSim: useful information, rejected realization.** The first prototype was
  invalid: ordinary static W-TinyLFU arms recomputed probation at each window size, while the live
  host keeps probation fixed and transfers capacity between window and protected. The repaired
  experiment constructed reachable integer segment triples, clamped and deduplicated targets
  before scaling, followed capped moves to the exact approved coordinate, and supplied the same
  request-indexed admission variate to every arm. At the co-minimal 128-entry, three-cohort setting,
  its six-cell median and p90 static-oracle regret were 0.197pp and 0.305pp: remote counterfactual
  ranking did contain useful information. The 27-policy realization then failed every production
  cost bar: 103,816 bytes retained when empty, 314,784 bytes steady, 24 bytes per sampled hit, a
  median 1.96 bytes/request on the natural 95/5 mix, and a 37.52% median hot-hit regression. This
  rejects that realization, not the information source.
  The useful correctness subset was later extracted to main: registered MiniSim now preserves the
  live fixed-probation geometry, removes full and scaled aliases, and computes each integral move
  from the host's actual window coordinate. The expensive replicated controller remains rejected.
  Equal RNG seeds still do not give its arms request-indexed common draws, so MiniSim should not be
  described as a fully paired counterfactual oracle.
- **Bounded reuse-survival estimator.** A smaller primitive estimator was a credible attempt to
  retain the remote-ranking information without full policies. Its registered realization missed
  both information bars: 0.727pp median winner regret against 0.5pp, and 2.849pp p90 against
  1.5pp. Retire that realization as controller authority. The broader reuse-survival idea was not
  disproved by one resource setting and seed family.
- **Compact Shadow prototype.** Its controller semantics and bounded layout checks passed, and the
  observer allocated nothing in its steady interval. The first pair of the terminal four-cell cost
  gate then measured 32 bytes per candidate command against 16 bytes for the baseline, so the
  frozen stop rule ended the study before quality was scored. Compact state is possible; an
  affordable observation seam and a quality win remain unproved. There is no superiority result.
- **Archive boundary.** The trace-metadata prototype, compact primitives, static oracles, runners,
  manifests, and experiment evidence plumbing have no upstream consumer or shared owner. Keep them
  archived until one exists. Whole-file transplants and a public `SimulatorAdmissionRandom` API
  are rejected. A future consumer needing exact randomized pairing must first show that both the
  draw count and request-index digest match.

### Cheaper forms of the veto's return re-test

- **A hold before the retreat commits.** A completed shortfall streak stands the window still for
  two samples and re-samples, cancelling the retreat if the rate recovers to within the veto
  margin. Endpoint +1.16 against the retest alone's +1.39, three negative seeds of sixteen against
  none, `norank_flood_j100` −0.08 and `shieldtrap_s11` −0.01 where the retest alone is
  bit-identical, and `cp_w081` −0.42 on the corpus where the retest alone is +0.07. The mechanism
  is self-defeating: spreading the arrival drop across the hold's own samples is what puts it
  under the crash-scale threshold, so the hold manufactures the masking the retest then repairs.
  The cancel branch never fired on any cell. Do not re-propose a pre-retreat hold without a cell
  where a veto is shown to fire on noise.

### Other ways to judge an audit's verdict against its walk

- **`bestgap`** (the verdict pulls back only past one stride of separation, the arrival transient's
  own width): `cp_w097` +0.73 against **`demoflood` −3.59** and `mixture_d050` −0.33. `demoflood`'s
  real crest and `cp_w097`'s noise spike are both one stride behind the walk's end, so the
  pull-back's distance does not separate them.
- **`bestrough`** (the pull-back must beat the largest sample-to-sample move of the walk itself):
  restored the then-baseline `cp_w097` value recorded by the `07c6e370c` crestpast study
  (47.61 under its original unseeded simulator protocol; not comparable to the fresh seeded N=8
  mean) and costs **`demoflood` −3.59** again plus `hazefloor` −0.73. Two independent statistics refusing a
  real crest in order to refuse a spike is the shape of item 1's finding on this layer: the signal
  cannot tell a crest from a peak.
- **`monoland`** (the margin plus a monotone decline from the best to the walk's end): **inert**,
  bit-identical to the shipped form on every decisive cell. Where the margin has decided, the shape
  test has nothing left to refuse.
- **`bestflip`** (a park short of the walk's end points the next audit the other way, since the
  ground beyond is now known worse): `hazefloor` −3.92. The follow rule's value is the direction the
  confirm earned, not the unexplored side.
- **`landclaim` / `refclaim`** (a covered landing keeps the claim but releases the hold, so density
  may still improve on it): `moat_h4000` −2.29, a re-derivation of the recorded `arrive` kill —
  without the discard's `Rates.reset` the rail's margin stays at its 1pp floor and `track` re-plants
  the anchor down density's slide.
- **`retreatref`** (the retreat's samples judged against the rate the probe left, the `freshref`
  rule applied to the stand-down rather than to the confirm): weaker than covering the landing
  outright (`crestpast` −0.09, the plant-at-40 cell −0.45), because the landing's recovery is itself
  an arrival transient that takes more than one sample, so a level test against the pre-walk rate
  still fires while a first-difference test no longer does.

### Other ways to un-stale an away-anchor claim

- **Discarding the claim on a crash-scale swing that lands with the window still** (`stilldiscard`,
  `still2`, `still3`, `quiet`): fixes the family exactly as the landed reference change does, and
  costs the rail's control rows because a still swing is not necessarily the workload's. The one-
  boundary form mistakes a retreat's echo (`moat_h4000`: the undo arrives, the collapse lands one
  sample later inside the band, −2.3 / −3.4); two or three boundaries excuse the echo but not the
  terrain's erosion at a still window (`moat_h3000`: fourteen still samples, the window's hits fall
  5,662 → 112 with main's flat, then a −7pp break: −0.7 to −1.9 on eight of eight). The claim the
  swing discards is the machine's memory of the prize the rail then recovers (veto to 0.62 kept,
  0.46 discarded). Region composition would separate the cases seen (a shift moves main's hits, the
  terrain's collapse only the window's) and was not built: a second threshold on a heuristic.
- **Re-seeding the goal metric on a still swing while keeping the claim** (`resetstill`,
  `freshreset`): the re-seeded deviation prices the shortfall against the kept claim as real, the
  rail vetoes into the dead anchor within four samples and the audit that then arms from it confirms
  a mediocre position (`ghostclaim_p30` 41.2 against the reference change's 49.2). The deviation
  spike a shift leaves is what holds the rail off while the audit walks first.

### Variants of the repeat-confirm memory

- **The repeat memory cleared on the anchor's discard**: the confirm sample is the lure's on-step and,
  where the walk lands at the previous anchor, that step discards the anchor on the sample the confirm
  is judged; the chain breaks (`absolve_p8` seed 1 41.4 against 46.4 for the fail/crash-reset form).
- **The park's first audit following the confirmed walk unguarded** (`momentum`): `absolve` +16.7,
  `veilmoat` +3.7, `shallowmoat` basin B +2.7, `flood_j100` +4.1 (N=8), `moat_h3000` +1.6, `moat_h4000`
  +0.8, `crashnoise_a12` +0.5, `shieldtrap_s13` +0.6 against `moat_h5000` −1.75 (four seeds at −3.4: the
  far slope's gentle decline takes five strides to trip the 5pp bar, the next audit is the tolerant retry
  and its undo evicts the band), `whisper_mod_p6` −1.76 (its sentinel), `climbtrend_up` −0.9 (its
  sentinel: trend misconfirms swept wall to wall), `saw_p40` −0.5. The guard on the smoothed rate is what
  ships.
- **The guard on the confirm sample's raw rate against the arm sample's**: fragile to the lure's phase
  (`absolve_p12` seed 1 falls back into the trap, +0.6 against +17.6) and keeps `moat_h5000` −3.4 and
  `saw_p40` −1.4; the smoothed reference is robust to the phase and fires on the trend.

### Releasing a park that the workload has starved

- **`unpark`** (a parked, non-blind window starved for a sample discards the anchor and hands the
  sample to density; `unparkboth` also on a starved main): fires at three sites in 642 audit
  confirms and loses one of them big (`phases_d050@32k` seed 8 −5.2 against `rep_r6` seed 4
  +3.8). The park on a starved position is priced by rarity, and the census is the instrument to
  re-run before proposing any rule about it.
- **`marggate` and `margrest` on `flood_j100`**: both are the average law there. The gate's
  "probation richer than main's average" test is satisfied by the SLRU geometry alone whenever a
  rotation exceeds protected (every promoted key is demoted before its reuse and protected earns
  nothing), 47 of 48 steering samples, and the rest band is entered only at 57–76% windows. The
  marginal signal itself is right on the cell (tail hits 0 at every window; ungated `marghalf`
  63.19 against 51.98) and dead by the frontier above.
- **`arrive` alone** (an undo's or a return's landing sample is not judged for a shift): moat_h4000
  −2.69, h5000 −0.50, h3000 −0.36 on every seed. Without the discard's `Rates.reset` the rail's
  margin stays at its 1pp floor and `track` re-plants the anchor down density's slide into the
  valley, so the rail's moat win turns out to rest on the reset widening its margin. (The
  undo-landing half of this arm is what the verdict covers; the return half, re-expressed through
  the retest state once the retest existed, was implemented as
  `isReturnTest`, and on release HOLD by the repeated-real-trace recheck.)
- **`auditshield` alone** (a shift during an audit walk armed from a park does not stand the park
  down): bit-identical to ship everywhere; the undo's arrival at the anchor discards what the crash
  spared. **`arrive` + `auditshield`** as an unconditional pair: moat +6.5 in total and `flood`
  +2.2 against **`demoflood` −1.88 on every seed (its bar)** and `whisper_mod_a12` −1.83, the park
  held where ship's release let density drift to a better position. The park-retention trade with
  its mechanism; a scoped form on the top-corner residual is where it would be worth something.
- **`pricedshift`** (the stand-down's shift trigger priced as a starvation probe's crash bar,
  clamp(3·dev, [5pp, 15pp])): crashnoise_a12 +1.71 and deterministic, `rep_r6` seed 4 +30.9, and
  **cp_w081 −0.39 on 4 of 4 seeds**, arc_P8 −0.19, arc_ConCat −0.13, moat_h7800 −0.94 (a basin
  collapse). The fixed trigger's churn on noisy cells is inert on the corpus and protective on
  cp_w081, where a persisting anchor lets the rail veto on slow weather. A trade; do not re-derive
  the level, and do not read the C3 churn as a defect without this cell.
- **`nocorner`** was the diagnostic that priced the upper corner's probe here (+2.3 rep_r6, +3.8
  balloonflip against −0.2 on cp_w015 and arc_ConCat, read then as "the probe stays"). The probe
  was deleted and the arm with it; `cornerprobe` restores it, and the
  battery reads the restoration 2.2:1 against.
- **`deferreward`** (the wedge pricing's next-sample half): a redistribution of `rep_r6`'s seeds
  (+3.0 mean, one seed −20.9) and inert or slightly negative elsewhere (`phases_d050` −0.49); the
  ladder's doubling is coarse against a 91-sample trace, so any faster escalation wins the seeds
  where the deeper probe finds the band and loses the one where it hits a thin burst. Not landed;
  the arm is in the workspace if the sentinel's mean is ever worth that.

### Other prices for a confirm the density arm reverses

The round priced a starvation confirm that the density arm reverses in the same sample (a
"wedge"). What shipped is the escalation without a wait; the rest is dead:

- **wedgehold** (the wedge keeps the rung, neither reward nor escalation) and **wedgefail** (the
  wedge is a failed experiment: undo, escalate, refractory = rung): both hold the floor long enough
  for the calibration audit to arm and misconfirm on the warmup trend, so `shallowmoat`'s wedge
  seeds read 27.5 / 27.3 against ship's 28.5, and neither reaches the prize inside 64 samples.
- **wedgewalk** (the wedge does not end the walk): the v9 slam-continue family (§5 above), kept as
  the diagnostic upper bound (33.5 on the wedge seeds) and not promotable.
- **wedgeflat** (only a wedge on flat terrain escalates; one across a restart-magnitude change
  keeps the rung): `trickle_s7` −6.7 (four seeds at −14) because the kept rung makes the next
  fail's refractory 32 samples instead of the reward's 2.
- **wedgedead** (a walk armed on a dead sample is first-round): `arc_DS1@1051635` −1.7; its low
  samples straddle the starvation bar (2,662 main hits against a bar of 4,108 on one, 10,221 on
  the next), so the second is a rung-64 walk to an 80% window.
- **wedgelow** (a walk armed a restart threshold below `rates.smoothed` is first-round): never
  fires on the alternating trace, because every sample is a shift and `rates.reset` re-seeds the
  smoothed rate to the arm sample itself.
- **wedgeshift** (a walk armed on the sample the rate fell into by a restart threshold is
  first-round; the guard the shipped rule can carry): measured and not landed. It restores DS1 and
  `deadphase` bit-for-bit and removes `phases_d050`'s two seed flips, is bit-identical on every
  non-wedge cell run (arc_P8, arc_S3, cp_w015, cp_w050, mixture_d050, straywall2, slowswap, moat),
  and gives back `strad_p8@4097` +1.9 → +0.5, `widepin` +1.7 → −0.8 and `phases_d050@32k` +4.8 →
  +2.7. Its evidence for the floors is one cell (DS1 is the only floor cell with any starvation
  confirm), so the pass is by construction; the instrument that would price it is the unspent
  stillness holdout. Add it if trough-armed deep walks show up at scale.

### The 1% initial window is not worth moving

`PERCENT_MAIN` has never been re-asked since it was chosen. The hostile-window study above gave it
an instrument, so it was swept properly: 77 cells classified by their *measured* static optimum
(43 frequency-favorable at ≤5%, 14 middling, 20 recency-favorable at ≥40% — the axis the hostile
study could not see, since its cells were screened **for** a small optimum), then 37 of them swept
at starts of 1/2/5/10/20%, plus a 30% arm on the 23 density cells.

**The bar was fixed before measuring**: corpus mean ≥ +0.50pp, no stratum mean below zero, no
workload family losing more than 1.0pp, holding at each tier separately. **The best arm returns
+0.049pp**, a tenth of the bar, and no arm satisfies the stratum clause. Applying this study's own
readability floor (≥15 density samples) the whole table collapses further: every arm's corpus mean
lands within ±0.21pp of zero, the best being **+0.018** at a 20% start. The mid stratum's apparent
+0.25..+0.87 is entirely `corda@2k` and `corda@4k` at **3.3 and 6.6 samples**, which price the
start rather than the controller; with the floor applied that stratum is negative at every arm.

**The mechanism is why, and it is the part worth keeping.** The climber absorbs the plant
asymmetrically: on recency workloads it captures **96%** of what a larger start could statically
buy (frozen +2.68 against a delivered +0.11 at a 10% start), because it was walking to a 20–50%
window anyway and the plant only shortens a journey already underway. On frequency workloads it
passes **90% of the harm through** (frozen −0.80, delivered −0.72), since there the plant is pure
displacement to be walked back at the descent rate of 4–6% of the maximum per sample. A larger default therefore buys almost
nothing where it would help and costs nearly full price where it hurts. On two cells it lands in a
*worse* attractor than 1% reaches at all: `wiki_1191a@64k` frozen −0.12 → delivered **−0.99** on
3 of 3 seeds, `arc_P13@64k` frozen −1.59 → **−3.41**.

The storage families are the case against moving it: the ARC set is **16 of 18** frequency-favorable
with an optimum at a 1–2% window, losing monotonically as the start grows (frozen −0.15 / −0.44 /
−1.18 at 5 / 10 / 20%, worst −5.41). A 5–10% default is a straight tax on exactly the large,
frequency-signalled caches the 1% choice was made for.

`cp_w050@123038`'s basin (§3, *Path-dependent rest points*) does **not** argue for a larger default: at N=8 the 2/5/10/20% arms
read +0.02 / −0.01 / +0.04 / −0.21 and all converge to the same ~20.6% attractor, and only the 30%
arm moves (+4.72, and one seed of eight already falls into the *bad* high basin from a 20% plant).
It is a threshold between 20% and 30% that a 5–10% default captures none of, and it remains a
question about the audit layer rather than about the default.

Not decision-bearing but recorded: strata are near-collinear with families in this corpus (ARC 16
of 18 frequency, cloud-physics@16k 12 of 14 mid-or-recency), broken only by `scarab_recs`,
`wiki_1191a`, `msr_prxy_0`/`hm_0` and the lirs reactive cells; `cp_w097/098/100/101@16384` are
near-duplicates and count as one workload; and `cp_w050@2048` has its whole static curve **below**
LRU (ceiling 13.65 against LRU 14.52). No holdout was spent, and none is owed, since nothing was
fitted. Two rest-point give-backs above the documented 0.5–2.5pp band turned up in passing and
belong to the marginal-steering thread (§5) rather than here: `arc_P1@64k` settles at a 30% window against a 1% optimum
for **3.25pp** (45.63 against a 48.88 frozen ceiling). `cp_w097@16k` is the other face, but it does
not settle: its 135-decision endpoint is near 47% against a broad 5–15% optimum, and continuous
270- and 541-decision runs stay near 47% while cycling through seed-dependent walks and parks.

### Neither main-space knob is worth adapting

The main space has two elements the climber never touches: the 80/20 protected/probation split
(`PERCENT_MAIN_PROTECTED`) and the 1-hit promotion rule (`reorderProbation`). Merlin adapts the
analogous quantities, so both were swept as a second dial: P in {0.20, 0.50, 0.65, 0.80, 0.90,
0.95} and a promotion gate requiring the sketch's estimate to reach T in {1, 2, 3, 4} — the
zero-metadata analogue of Merlin's `guard_freq`, needing no new per-entry field and no scan.
Object-count, the 288-cell Merlin matrix minus the two multi-GB traces, one build, the shipped
arm re-measured rather than inherited.

- **There is no better fixed constant, in either knob.** Every alternative loses on the corpus
  mean: p90 −0.051, t2 −0.031, p65 −0.074, p50 −0.155, t3 −0.140, p95 −0.191, p20 −0.249,
  t4 −0.373. The shipped 0.80 / 1-hit is the optimum, not merely a defensible default. This is
  the opposite of Merlin's own region dials, where the ghost budget was worth +1.49 mean and the
  filter ratio +0.83 (`merlin_region_sizing_headroom`) — those were bad defaults; this is not.
- **The hindsight prize is inside the instrument.** Best-of-9-arms per cell is +0.326 mean, but
  `product.Caffeine` is not bit-deterministic and max-of-9 is biased upward even when the arms are
  identical: re-running the **shipped** arm nine times on a representative 26-cell subsample buys
  +0.116 from noise alone. Net **+0.21 pp** for a perfect per-workload oracle. Any real controller
  captures a fraction of an oracle, and would be paying for it with a second extremum seeker.
- **The response is real but its argmax is not learnable.** 16/26 cells show a P spread exceeding
  twice the same-arm noise range, so the surface is not noise. But the winner is scattered almost
  uniformly (p95 57, p90 54, base 50, p20 43, p50 38, p65 34 of 276), **46/46 traces change their
  preferred P across the size ladder**, and the mean response is flat to ±0.1 pp at every size rung
  with the peak at or adjacent to 0.80. There is no stable per-workload optimum to seek.
- **The within-trace pass, which is the one that could have overturned this, closes the same way.**
  A per-cell oracle prices only a per-workload controller, so each arm was re-run on three disjoint
  thirds (`trace.skip`/`.limit`, binding confirmed by segments that genuinely differ). On
  *selected high-spread* cells the per-segment prize looks large — +1.075 mean, up to +4.11 — and
  the per-segment noise floor is small (0.16–0.39), so those gains are real rather than noise. On
  an **unbiased** 14-cell sample it collapses to **+0.225 gross, ≈0 net**. The difference is cell
  selection, not workload regime: splitting by adaptations available in the segment does not
  separate it (<15: +0.184, ≥15: +0.256), so the tempting "it is climber non-convergence" reading
  is **not** supported and should not be repeated.
- **The downside dominates the prize by an order of magnitude, and it is a cliff.** p95 costs
  −1.76 at the 5th percentile and −6.16 worst; t4 costs **−25.72** on `backf@300`. A controller
  would be risking multiples of what a perfect one could win.
- **The promotion gate is inert where theory says it should be.** t2 changes nothing at all
  (|Δ| < 0.01 pp) on 55/220 cells: an entry that won the TinyLFU admission contest already carries
  frequency ≥ 2, so the first threshold step is a no-op. The gate only bites by starting to *deny*
  promotions that should happen, which is the −25.72 tail.

Two properties of the structure are worth keeping in mind for anyone who reopens this. Because
`increaseWindow` takes its quota from `mainProtectedMaximum` and caps there, window + protected is
conserved and **probation's capacity is fixed for the cache's lifetime** at `(1−P)·0.99·max`
≈ 19.8%; the window's reachable range is `[2%, ~80.2%]` and it can never grow into probation. That
also means the low-P arms conflate probation size with window ceiling (p20 caps the window near
20.6%), so their losses are a lower bound on p20's true merit — but p90/p95 carry no such handicap
and fail on the mean anyway, so the verdict does not rest on it.

Not covered: `ConCat` and `MergeP` (12 of 288 cells, ~9 GB of trace) were not run.

**Companion result: the SLRU main space itself is still earning its keep against a plain LRU
main.** The 2015 commit that introduced it ("Use Segmented LRU for the main space cache") claimed
0–2% in many workloads, especially small caches, and the question is whether that survived the
sketch fixes since. Asked by setting the promotion threshold to 16: the sketch saturates at 15 and
`reorderProbation` is the only path into protected, so nothing is ever promoted, protected stays
permanently empty, and main degenerates to one LRU queue with the climber's window boundary
intact. (`PERCENT_MAIN_PROTECTED = 0` is **not** the way to ask this — it zeroes
`mainProtectedMaximum`, which `increaseWindow` early-returns on, so it measures a crippled climber
rather than plain LRU.)

Over the same 276 cells, plain LRU main is worth **−93.1 pp net, mean −0.337, median −0.227**.
SLRU wins by >1 pp on 40 cells and >2 pp on 8; LRU wins by >1 pp on 9 and >2 pp on 3; 227 cells
tie within 1 pp. **38 of 46 traces prefer SLRU.** Confirmed at N=3 on the 14 headline cells with
**0 sign flips** (σ ≤ 0.31 on all but `P1@69344`, whose N=1 +1.48 collapses to +0.07 — a noise
cell, not a result). The 2015 claim's size dependence also survives: the advantage concentrates on
the smaller rungs (rung 1 −0.596, rung 2 −0.601) and vanishes at rung 4 (−0.002). So: **keep SLRU,
but it is a modest, concentrated effect** — it is inert on 82% of cells and worth 1–3 pp on the
rest. The traces that genuinely prefer a plain LRU main are a coherent set worth remembering:
`fiu_ikki` +0.851, `fiu_webmail` +0.548, `backf` +0.326, `loop` +0.305, `MergeS` +0.259,
`fiu_homes` +0.186, `ps` +0.136 (and `corda_large` +0.002, a tie). They are the same cells that
wanted a smaller protected region in the P sweep, so the preference is consistent across both
knobs — it just isn't worth a controller, per the headroom result above.

**The promotion curve is non-monotone, and that is the interesting part.** Mean vs the shipped
promote-on-first-hit: @2 −0.031, @3 −0.140, @4 −0.373, @6 −0.640, never −0.337. A *partial* gate is
worse than either extreme, and it owns the catastrophic tails (@4 −25.72 on `backf@300`, @6
−20.33) that never-promote does not have (worst −3.06). Mechanism, read from `evictFromMain`:
`victimQueue` starts at PROBATION and only moves to PROTECTED once probation is exhausted, so
under a high-but-finite gate the few entries that do reach protected are effectively removed from
the victim pool indefinitely — protected never approaches its maximum, so `demoteFromMainProtected`
rarely fires to release them. Never-promote avoids the pathology by keeping protected empty. Any
future proposal to gate promotion must answer this, not just pick a better threshold.

### Other pricings for the walk's two interior exits

The split's own dead arms, all measured N=8 seeded. Do not re-derive either.

- **A reversal bar with no base-rate term at all** (`revdev`, `min(5pp, k·dev)`) is dead across its
  whole range, at both ends for different reasons. At **k=3σ** (`noiseBand`) it is **bit-identical
  to `floorrev` on every seed of every cell measured** — gain and loss alike — because wherever a
  floor at 3σ binds, `max(fraction, 3·dev)` *is* `3·dev`, so the two derivations are one bar. At
  **k=0.5σ** it resolves `arc_S3` (+0.04) and then costs **`whisper_mod_a12` −2.35** and
  **`crashnoise_a12` −1.77**. Nothing in between serves: `crashnoise` needs ≥5pp at its scatter
  while `arc_S3` needs ≤1pp at its own, and the required multiples are ordered the wrong way. The
  mechanism is general — a scale-free bar is necessarily *small where the scatter is small*, which
  is exactly where the shipped absolute is load-bearing. **A base-rate term must survive on the
  reversal as a lower bound**, which is what shipped.
- **`floorrev` / `revfl300`** (floor both the reversal and nothing else at `3·dev`, under the 5pp
  cap): the right *shape* at the wrong multiple. It buys `shieldtrap` +0.49/+1.81/+1.40 and
  `marggate`'s `slowswap_r20` +1.81, and costs **`arc_S3` −0.45** — 38% of what
  `AUDIT_BAR_FRACTION` was introduced to buy, on the cell it was bought for — plus `cp_w050` −0.27.
  At the shipped 0.45σ the same arm costs `arc_S3` −0.01 and gives the `slowswap` prize back. The
  whole distance between those two outcomes is one multiple inside one family, not two mechanisms.
- **`revfl100`** (the same floor at 1σ): `arc_S3` −0.42, i.e. the full `floorrev` loss. The cliff
  between the shipped 0.45σ and 1.0σ is why the level is not worth re-deriving.

### Marginal steering on both sides

The round-2 kill pointed at `ln(d_windowTail / d_probationTail)`, marginal on **both** sides,
keeping probation as the denominator while measuring its margin rather than its bulk. It was built
in two sizings (`margboth`, proportional `δ·W` against `δ·P`; `margeq`, equal capacity on each
side so the denominators cancel to `ln(H_wTail / H_pTail)`), and both are dead. But the result
that matters is not the arms.

**Neither beats the one-sided form.** Nine real cells at N=8: ship +0.00 / 0 losses / Spearman
+0.950 / 21.3pp level error; **`marghalf` +0.83 / 1 / +0.983 / 11.3pp**; `margboth` +0.66 / 3 /
+0.967 / 14.0pp; `margeq` +0.69 / 1 / +0.967 / 13.0pp. A second band on a second deque, for
strictly worse numbers. The two sizings land together, so proportional-versus-equal-capacity is
not the question.

**The frontier.** Averaging the two `slowswap` rows against that screen, the four denominators
trace **one monotone inverse curve**: `marghalf` 30.40 / +0.83, `margeq` 31.74 / +0.69, `margboth`
34.92 / +0.66, `margtail` 36.92 / +0.43, ship 41.32 / +0.00. Spearman **−1.0**, slope ≈**16pp of
`slowswap` per 1pp of corpus**. A denominator does not escape the trade, it only picks a point on
it.

**Gain is not the lever, and the family has a ceiling.** `marghalf` swept over gain 0.125–1.0 on
`slowswap_r20` reads 32.90 / 30.38 / 30.02 / 29.65 / 36.20±5.23 / 31.12: non-monotone, unordered,
its one high point a lottery. And `margtail`, the family's best `slowswap` point, reaches only
37.52±0.18 at its best gain. Across the ~14 (denominator, gain) points measured on that row the
best mean is **37.52** and the best single seed anywhere is **38.67**, against a **≥40** bar and
ship's 42.19.

So the thread does not close because arms failed; it closes because the family has a **measured
limit short of the gate over both of its free parameters**. What remains untried is outside it:
every remedy so far picks a different denominator, while the `slowswap` diagnosis names probation's
*composition* under heavy inflow. A regime-gated fallback is a different shape from round 1's
rejected starvation-gated one, but price it against ~+0.24pp, the corpus prize the frontier leaves
at the bar, not against +1.22.

### Marginal steering: the signal is real, the controller is not shippable

The gated arm is the family's best point and its residue redirected the thread. `marggate`
(fall back to the average law where `probationDensity` exceeds `mainDensity`, a trustworthiness
test on two terms `Reading` already computes) reads **+0.690 mean over the nine cells with one
−0.04 loss**, rest-point tracking Spearman **+1.000** with 12.2pp level error against ship's
21.3, and sits at the same `slowswap` robustness as dead `margtail` for 60% more prize. Its
structural false positive: on `flood_j100` a rotation exceeding protected demotes every promoted
key before reuse, probation reads richer than main with no inflow at all (47 of 48 steering
samples), and the arm is the average law there. It fixes `slowswap_r1` (5.6pp below LRU →
38.24 ± 0.18, +1.45 above, deterministic) and not `r20` (−6.9 → −0.95, bimodal); neither meets
the ≥40 bar. **The residue is recovery, not detection**: per-seed on `r20` the gate fires 11% of
steering samples in the good basin and 9% in the bad one, firing rate does not predict the
outcome, and what separates the basins is where the window ends up (30.5% against 20.0%) — the
depressed-window thread's territory. Dead within the gate, do not re-derive:
a run-to-enter requirement (streaks 2 and 3 both lose to 1), an exit latch (non-monotone, never
lifts `r20` above LRU by more than 0.00 over 1/2/4/8/16), and the gate ratio (the two rows want
opposite settings: 1.0 gives r1 +1.45 / r20 −0.95, 0.6 gives r1 −0.62 / r20 +1.01).

`margrest` (the marginal law only inside a rest band, `|steeringError| <= REST_BAND`) solves
`slowswap_r20` outright (42.20 ± 0.05 against ship's 42.19, deterministic) **and destroys the
prize** (nine cells +0.133 against `marghalf`'s +0.833, `w098` collapsing +4.33 → +0.35). The
mechanism is the wall the record now names everywhere: `w098`'s win *is* a rest-point
correction, but a large one — the valuable corrections are long relocations, and a long
relocation is indistinguishable from a transition by command magnitude. No size-gated rule can
work, and the same fact underlies the frontier: the signal cannot separate "a big move because
the rest point is wrong" from "a big move because the workload changed". Two structural facts
fell out: the average-law error is bimodal (near zero at rest or large in transit; bands 0.5
through 0.1 are bit-identical), and on the bad `r20` seeds the final third is a park, 95 of 103
samples at a 16.9% window with one audit armed — the marginal law digs a hole, the anchor parks
in it, and nothing in a steering law climbs out of one. The stillness-coupling blocker is
repaired entirely by halving the gain (a threshold on the stable band, not a monotone trade;
0.25 is worse than 0.5); the probation-denominator defect under heavy inflow is what the
denominators above could not fix.

External corroboration (OSDI'26 "Learning-Augmented Heuristics", Xia et al.): their model's
feature importance puts **75% on the three queues' hit-position histograms** — per-region
hit-rate-versus-size curves, i.e. marginal-value estimators — over 4140 production traces.
Corpus-scale evidence for the premise from outside the project. `d_tail` is one bin against
their 20 per region, so bin resolution is the knob if a future arm reads ambiguous; nothing
else in the paper transfers (it is a static per-trace configuration selector). **Screen any
successor on rest-point tracking before spending a battery**: Spearman(meanWindow, peakWindow)
over the nine D3 cells reads ship +0.917, `marghalf` +0.983, `margtail` +0.367, and a candidate
that does not beat +0.917 is buying stability with tracking — trap wins paid for by corpus
losses.

### Verdict corroboration, and the delayed entry stride

**Verdict corroboration, the smoothed reference (`corrob2`; the raw form died first).** A
pulled-back confirm must reproduce its own claim (`Walk.bestRate`) in the smoothed rate within a
confirm-streak of samples, or the park relocates to the walk's end. It does what the raw form
did — `crashnoise_a12`'s low basins lifted (+1.03 mean, the row near-deterministic at 62.7) and
`whisper_mod_p12` +0.64 on 8 of 8 — and fails `whisper_mod_a12` the same way (−1.67 mean, seven
seeds at −1.98, seed 7's low draw the one gain): at that amplitude the EMA still carries ~40% of
the modulation, the park's first samples land in the trough, and a correct position fails its
own claim. The reference is not the defect; the depth is. The smoothed form also adds
relocation damage the raw one did not have: `demoflood` **−3.64 on 8 of 8** and `crestpast`
−4.2 / −2.9, where the lagging EMA reads a fresh crest park as unproven and revokes it to the
walk's end, re-deriving the exact overshoot the verdict repair removed, plus `straywall2`
−0.78. Dead. Both measurable references die by the same symmetry, and the third (the fallback
position's own rate) is unmeasurable without going there; the corroboration family is closed.

**The delayed entry stride (`delayentry`, item 8's blank).** Freeze the base at the arm and
take the entry stride on the next sample, the WaveCounter comparison. The prior held and the
mechanism sharpened: the entry's timing is itself phase-load-bearing. `absolve` reads **−15.7
on both seeds**, back at its pre-repair value, because the lure-paced escalation needs the walk
to arm in the off phase and stride before the on phase returns; `straywall2` −0.88;
`norank_rep_r6`'s escape lottery reshuffles violently (seed 3 +48.6 against seed 7 −59.8, mean
−2.2); against `blindlock_blind` +5.8 and `demoflood` +0.7, which do not buy that. Dead; the
record's blank is priced in the direction the prior expected.

### The marginal band priced against main's average

`ln(d_tail/d_main)`, the marginal band priced against main's **average** instead of its probation
margin, was the named answer to the one marginal-steering residue that halving the gain does not
touch. It is dead in both directions, and the reason was derivable from §4 before it was built.

**It halves the prize.** On the nine D3 cells `margtail` is +0.43 mean with **four** losses
(P9 −0.41, P10 −0.55, w081 −0.64, w060 −0.75) against the full form at half gain `marghalf`,
+0.84 with one.

**It fails the gate it existed to pass.** Seeded at N=8, `slowswap_r1` reads 36.31±0.92 and
`slowswap_r20` 37.52±0.59 against a ≥40 bar and ship's 40.44 / 42.19. It recovers ~63% of
`marghalf`'s slowswap residue, not the ~89% the static rest-point anatomy implied (1.69 against
14.63pp), and still lands 4.1–4.7pp low. Two side results: the declared trade was wrong, since
`trickle_s7` **improves** (72.59±1.30 against ship's 70.70±3.63); and `widepin` is the arm's one
real win, 56.32±7.00 against ship 49.58±13.73 and `marghalf` 48.12±14.03.

**The mechanism, which is the part worth keeping.** A steering denominator must move with the
window or the rest point stops depending on the window. `d_prob` moves (a grow squeezes main and
demotes protected entries into probation); `d_main` is dominated by the protected core and barely
moves, so the error degenerates toward `ln(d_tail) − ln(const)`. Measured directly as rest-point
tracking across the nine cells, Spearman(meanWindow, peakWindow) is ship **+0.917**, `marghalf`
**+0.983**, `margtail` **+0.367**, with mean |window − peak| 23.0 / 14.9 / 19.2pp: `margtail`'s
middling level error is range compression, not accuracy, and it rests at 12.6% and 13.0% on the two
cells whose optima are 40% and 70%. That is the same fact §4 records for the sibling mechanism, an
up-probe's verdict being priced against probation "rather than its average", and `widepin` is it
read from the other side, since an arm that does not track cannot chase a pin. **An arm that wins
on the traps and loses on the corpus is buying stability with tracking; screen the tracking
statistic before spending a battery.**

### Repairing the reactive law instead of replacing it

Can the reactive law be repaired instead of replaced — specifically by giving its reversal a
noise band, as `nextStride` gives the walk's? Four forms were built and measured against both
incumbents: a flat 5pp band, the walk's priced band (`clamp(3·dev, 5pp, 15pp)`), the same with
the density tier's 2% floor, and `probeEnding`'s base reference (measure from the run's best
sample, ratchet on improvement) with and without aging. All dead, and the reasons are structural
rather than tuning.

- **A band on the reactive reversal, any form.** Real traces: mean **+0.16pp** vs reactive over
  seven cells, range +4.06 (cp_w061@64k) to −2.81 (arc P8@64k), never closing the gap to the
  shipped machine (mean −0.73). Constructed families: **five of six lose**, mixture d050 long
  −25.89, mixture d025@32768 −25.29, straywall2 −12.24, whisper −10.53, demoflood −3.37. A band
  buys commitment, and commitment without an adjudicator is a direction chosen by early noise
  that nothing can revoke — the same wall v7's travel budget and v9's slam-continue hit from the
  probe side. The reversal *is* the reactive law's "confused but never persistently wrong"
  property; a band spends exactly that.
- **A band with no window floor is catastrophic, and this is a standing constraint on the tier.**
  `BoundedLocalCache.decreaseWindow` floors the window at one entry; the 2% floor is climber-side
  in `Reading.floor` and belongs to the density tier alone. The reactive tier has never needed
  one because a bold driver cannot sustain a run. Widen the band and the window walks to ~0,
  where TinyLFU refuses every new entry against an established victim: corda 30.96 → **1.13**.
  Any future change that lengthens a reactive run — a band, a longer period, a confidence gate —
  must bring a floor with it.
- **Aging the base reference** (decay the peak toward the live rate at 0.2/sample): inert on four
  cells, −0.5 on two, and on arc P8 it collapses to the un-ratcheted band's value exactly
  (42.57 vs 46.90). Third independent kill of reference-aging, after the anchor claim's two.

Two results from that study are **not** kills: the magnitude deficit is
partly a reversal-rule problem rather than purely a signal one, and the trap gallery's value is
partly variance reduction that N=1 comparisons hide.

### Guards derived from the control-theory map

The control-theory map (§2.1) suggested two guards that could be *derived* rather than tuned.
Both were built behind variant knobs and both are dead. The holdout frozen for the study was
never spent — neither arm reached a ship gate.

- **Freezing the walk's deviation reference at arm** (`devfreeze`): `walkInteriorBar` prices a
  starvation probe's crash abort at `3×rateDeviationEma`, and because `updateRateReferences` runs
  before the probing branch, the walk's own transient inflates the very statistic that decides
  whether to abort it. That description is accurate; the inference that it is a defect is not.
  The feedback is **load-bearing**: on a dosed workload the deviation at arm is a *pre-dose*
  value, so a frozen bar sits too low and the walk crash-aborts inside the dose — exactly the
  failure the adv3 probe-side pricing exists to fix. Measured: `mixnoise_a10` 60.70 → **55.97**
  (−4.73, spread 0.01, fails its ≥58 bar — the pricing's own prize row) and `crashnoise_a12`
  63.59 → **62.18** (−1.41, spread 0.01, drifts below its 63.6 floor); `devfreeze_nocap` is worse
  (`widepin` −2.48, `phases_d050` −2.75). The live deviation and `PROBE_BAR_CAP` are a matched
  pair — the feedback keeps the bar above the weather while the walk is exposed to it, the cap
  bounds where that would run away. Don't freeze it, and don't drop the cap.
- **A within-sample confidence dead-band** (`deadband<z>`): suppress a steering step whose error
  is inside `z·√(1/H_w + 1/H_m)` (the delta method on two counts — free from counters in hand),
  as a derived answer to F-2 and, since a step never taken cannot move the window, a derived
  stillness band. Dead twice over. **It barely binds**: at a 4×C sample period each region earns
  thousands of hits, so 3·sd is ≈0.04 nats and the step that commands is ≈0.13% of the maximum —
  measured mean **+0.03** across 40 non-bimodal cells, two cells past ±0.2. **And its motivating
  case is not a significance problem**: on `posjam_d0`, **0 of 121** super-band moves have an
  error inside its confidence interval — the jam's motion is statistically *real* and window-
  *irrelevant*, so no confidence test can act as a stillness measure (the dose-matched flat
  control is the mirror image, 5 of its 11 super-band moves inside the CI — it binds exactly
  where a defense is not needed). The `posjam` residual stays a stillness-*measure* problem, not
  a noise problem.

### Ageing an off-anchor claim instead of freezing it

Both forms of the thread's other named fix direction — letting an off-anchor claim **age** toward
the live rate instead of freezing — fix the target and are dead for different reasons. The landed
fix re-seeds the goal metric on a discarding stand-down instead.

- **Symmetric aging** (`ageclaim<pp>`: `anchor.rate` decays toward `rates.smoothed` while the
  window is away and settled): −11.9 on the `slowswap_r20` ramp control, on every one of eight
  seeds (42.14 → 30.29), while fixing `slowswap_r1` exactly as the landed fix does. A claim that
  chases the live rate upward can never fall a `vetoMargin` *below* it, and that comparison is the
  only thing that moves the anchor to a better position: the anchor freezes at a position the
  window has left for the rest of the run (trajectory: park samples 69 → 1, `AUDITCONFIRM` 1 → 0,
  down-probes 7 → 0, steering 128 → 238). Slowing the decay to 0.05 hides it on that trace without
  changing the mechanism.
- **One-sided aging** (`agedown<pp>`: decay only while the live rate sits under the claim): passes
  both slowswap arms, battery mean −0.067 with no seeded mover, and still dead — it **disarms the
  guard rail**. The rail needs `VETO_STREAK` consecutive samples of `smoothed < rate − vetoMargin`,
  and aging closes that gap at the same rate `rateDeviationEma` decays, so the shortfall and the
  margin shrink together and the streak never completes; both rail pins fail
  (`guardRail_sustainedShortfall_vetoesBackToAnchor`,
  `guardRail_marginTracksNoise_silentWhileWideThenVetoes`). The rail is measured near-dead on this
  battery, so the live cost is small — but a compensating fix that trades a documented mechanism is
  the wrong shape when the same defect has a source-level fix that leaves every pin standing.

### Park persistence, and other confirm-side levers

- **Unconditional park persistence** (a parked confirm never crash-releases): captures the full
  w097/whisper-p6 prizes but converts the tracking controller into hold-and-retest exactly
  where tracking is the cure — regimeramp 54.8 → 48.5, widepin 54.8 → 48.5 (below its bar),
  phases@8192 −4.1. The bounded fresh-park shield (one initial audit wait) keeps the prizes
  with all three at ship values — the shield length is load-bearing, not a tuning residue.
- **Deviation-priced audit margins generally** (the F1 mitigation table: `flat`/`ema`/`cap`
  margin modes recover only 25–35% and trade the rail for the audit) — superseded by the
  run-length streak; kept here so nobody re-prices the confirm by any multiple of
  `rateDeviationEma` again.

### Blending the two steering laws

- **Confidence-gated steering blend** (`(1−w)·density + w·reactive`, `w = W·min(1, |ΔHR|/devEMA)`):
  the T1a form. Whisper −1.1, jam-control −3.4, and **zero rescue on its own targets** — the
  density attractor undoes uncommitted per-sample nudges exactly as §T1's coin-flip analysis
  predicted; a confidence gate does not change the physics. Committed walks (probes, audits) are
  the only instruments that cross basins.
- **Settle-then-judge** (hold a walk's final position ≤5 samples, adjudicate by live-margin rate):
  demoflood +6.6 but phases −8.4/−11.5 dose-responsive — an exogenous phase rise credits the
  held position (misconfirm) and plants a wrong anchor. Any goal-metric confirm must survive the
  phase-alternation families before shipping.
- **Goal-metric senior confirm with margins frozen at arm**: no effect — the margin freezes an
  inflated deviation and the anchor reference is stale by construction on noisy traces (the 3·dev
  ratchet bar never fires at dev 0.03–0.07; measured 130-sample-stale on cp_w097_16384).
- **Dwell-gated anchor ratchet** (`auditClock.stillSamples ≥ 2` before the anchor may move): no
co-tenant
  help; widepin re-destabilized.

## 6. Methodology (the discipline that kept this honest)

- `product.Caffeine` in the simulator is the arbiter — the sim reference climbers diverge from BLC
  (ps2-vs-x2 history). Randomized admission gives ~0.1–0.8pp run-to-run noise: N≥5 runs for any
  claim under 2.5pp, absolute pp only, no net-sum headlines over noise cells.
- **Where that noise comes from, and why some cells are bimodal.** The source is
  `BoundedLocalCache.admit`: a candidate at or above `ADMIT_HASHDOS_THRESHOLD` that does not beat
  the victim is admitted on a 1-in-128 `ThreadLocalRandom` draw (the HashDoS defense). TLR seeds
  from system entropy per JVM, and the policy runs `.executor(Runnable::run)`, so this is the only
  live nondeterminism. The experiment harness can replace that contest-time draw with a seeded
  stream, which makes each arm independently reproducible. Equal seeds do not make two arms use a
  draw on the same request when their contest schedules differ. The arms are paired by seed, not
  by request-indexed common random numbers, unless both the draw count and request-index digest
  match. On most cells the draws average out
  (measured over five batteries: 28 of 47 cells vary by <0.05pp run-to-run, 37 by <0.20pp). On a
  constructed bistable trap an early draw can tip the region composition into the other attractor
  and the climber's own feedback locks it in, which is what a **lottery cell** is: 6 cells
  (`phases_d050`, `widepin`, `shieldtrap_s7`/`s13`, `balloonflip`, `crashnoise_a12`) vary by
  1.1–5.5pp with the code held fixed. Consequences for reading a gate: a mover inside that set is
  evidence of nothing, interleaving arms limits temporal drift but does not make their draws
  common, and a single-cell regression is adjudicated by basin count at N≥10, never by a mean at
  N=2.
- Adaptive N is sound and ~60% cheaper: N=2 everywhere, N=5 where |Δ| ∈ 0.5–2.5pp or spread >0.5.
- Trajectories (window size per adaptation) are the diagnostic instrument — every fix in §4 was
  found by reading a trajectory, not a scoreboard. The debug hook pattern: stderr print in
  `determineAdjustment` behind a temporary system property (the simulator forwards `caffeine.*`);
  no hook is checked in — the 2026-07 study's working-tree harness (variant knob + debug + seeded
  admission RNG) is preserved in the
  local hill-climber-study workspace (`experiment-harness.patch`).
- **Separate information, actuation, and deployability.** First ask whether a signal ranks
  counterfactual window sizes on an exact simulator stream. Then ask whether its controller moves
  the live cache safely and converges under clamps, reversals, and phase changes. Only then price
  the actual production host for retained state, steady allocation, command/boundary allocation,
  and CPU. Passing one layer says nothing about the next: replicated MiniSim passed its information
  screen and failed cost, while Compact Shadow passed layout and stopped before quality.
- **Treat movement as a two-phase command.** A decision remains pending until the host acknowledges
  the actual signed setpoint change. Reject overlapping acknowledgements and the wrong sign or
  magnitude; retain geometry-specific evidence after a rail-clamped zero; clear it only after a
  validated nonzero move. Continue a capped trip from the acknowledged setpoint, not from a
  nominal percentage or transient queue occupancy. Tests must pin partial and zero clamps,
  reversal, cancellation, and exact arrival in both directions.
- **Use an oracle ladder, not a self-confirming digest.** Compare a compact estimator first with an
  independent readable model, then request by request with the actual simulator policy under the
  same key, frequency history, and admission variate. Check hits/misses, membership, queue order,
  promotions/demotions, and admission decisions, not only final hit rate. Accumulate live evidence
  independently; copying an expected digest into the measured path proves only that the metadata
  agrees with itself.
- **Low duty cycle is not low request-path cost.** A dormant callback, counter decrement, routing
  indirection, or branch still executes on every request. The compact studies measured zero steady
  allocation alongside material CPU regressions. Dormant observation must be structurally absent,
  and even that is only a hypothesis until the exact product splice passes an order-balanced
  benchmark. Warm the exact root, actor, probe, and branch shape being measured, and make estimator
  work externally observable so dead-code elimination cannot manufacture a win.
- **One clock owns each lifecycle boundary.** Host requests, sampled requests, warmup, measure,
  travel, and cooldown are different quantities. Express each transition as one absolute request
  deadline or one remaining count, not two counters that can both charge the same interval. Pin the
  request immediately before and at every edge, and prove that under-full or dormant requests do
  not advance the estimator's active clock.
- **Classify endpoint state before scoring regret.** A trace that ends in a walk or audit measures
  recovery latency over that horizon; it does not prove a settled rest point. Report the terminal
  phase and pending target beside static-oracle regret. For percentile gates, also pre-register the
  monotone futility bound: stop once the observed order statistics force failure under every
  possible value of the unrun cells.
- **A holdout must be able to exercise the thing under test.** `AUDIT_BAR_FRACTION` is
  `min(RESTART_THRESHOLD, AUDIT_BAR_FRACTION · baseHitRate)`, so above a 1/3 hit rate it equals the
  absolute bar and every arm ties by construction. Selecting by "unspent + density tier + enough
  adaptations" is not sufficient: screen the candidate band against the constant's own binding
  condition first, or the freeze spends cells on a null result that reads like a pass.
- Holdout discipline: freeze a fresh holdout BEFORE tuning, sizes by LRU-only characterization,
  score it exactly once at the ship gate — and screen the candidate cells against the constant's
  own binding condition first (above). Every earlier holdout generation is SPENT (each spend is
  recorded in its study's entry and workspace); the density-climber **merge-gate holdout**
  (frozen with a hash-pinned spec in the merge workspace) remains **UNSPENT** — no
  candidate arm may touch its cells before the final pre-merge gate.
- The simulator has NO reference implementation of this climber (the experimental
  density-arm-only `SimpleDensityPropClimber` was retired rather than shipped
  half-faithful); `product.Caffeine` is the only arbiter until a faithful reference
  lands. The climber-class extraction that was to enable one has happened
  (`WindowClimber`), so a reference is now a port, not a redesign — still unbuilt.
- **Judge climber terrain on the object-rate column, not the byte panel.** The weighted anchor
  degenerates bit-for-bit to the classic form on unit-weight traces, so the unweighted record
  stands, but the byte track answers a different question and its win/loss counts move for reasons
  that are not the climber's. A related trap that inverted a real cell before it was fixed: the
  admitter was sized once, when the cache first filled, and never re-sized, while the cache
  re-calls `ensureCapacity` on every addition. Since the sketch's reset period scales with its
  capacity, a frozen one is TinyLFU aging at near-zero period, which flatters *small* windows and
  washes out at large ones.
- **No duty or dwell bound can detect the deferral defects, and that result is worth not
  re-deriving.** The shapes that motivated the search violate the schedule's **provenance**, not
  its magnitude — `maxOverdue` measures zero straight through a pin that lasts over a hundred
  samples — so no elapsed-time or duty bound can see them. What survives the search is three
  oracle clauses, none of them a duty bound: two ownership/evidence clauses (a layer's rung and
  wait grow only on a sample that ended its own walk; the wait passes its floor only on a walk that
  reached a verdict), which carry zero constants and kill mutations quickly but are regression
  value rather than discovery; and one **progress** clause (a due audit is refused for at most a
  bounded number of consecutive samples). The progress clause has real discovery content — on the
  pre-fix tree it fails in seconds and fires on exactly the cells the fix moves — but it was not
  adopted, because its margin sits one sample under the shallowest defect instance and it is a
  mechanism detector whose firings need a human to price, including where the refusal is
  load-bearing. Killed alternates: upper duty bounds (every defect is duty-too-*low*),
  sliding-window lower bounds (permanent false positives), and "consecutive samples commanding no
  motion" (the progress clause in disguise, or blind at the absolute level a legitimate deep park
  requires).
- Simulator operational gotchas: the default `admission` list includes `Clairvoyant`, which wraps
  the trace in a materializing reader (pass `-Dcaffeine.simulator.admission.0=Always` for LRU
  anchors, or run product-only); `simulator:simulate`'s chart renderer wedges headless (run one
  size per call); Twitter/IBM holdout projections live at `~/projects/merlin-traces/holdout/`.
