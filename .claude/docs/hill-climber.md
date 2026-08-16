# The adaptive window climber

The deep reference for `WindowClimber`, the window-sizing loop `BoundedLocalCache` drives from
`climb()`: what it must do, why that is hard, the shipped design, and the graveyard of
alternatives with the data that killed them. Read this before touching `determineAdjustment`,
the climber constants, or the simulator's climbing package. The quick rules live in
`rules/design-decisions.md`. The design document is `docs/adaptive-window.html` ("The Adaptive
Window, From the Ground Up"): problem, control-theory framing, design space, the shipped machine
built up mechanism by mechanism, evidence, and a measurement appendix — the LIVING document,
updated as fixes land. It is written for an external reader judging the algorithm, design, and
data: internal QA logistics (test-suite and fuzzer names, mutation baselines, gate tooling,
process dates) stay out of it and live here and in `rules/testing.md` instead. The former research-record HTMLs (the journey-shaped
"hill-climber-design" narrative and the failure atlas) are retired from the repo and archived in the
local climber-failure-modes workspace for reference.

Naming: the **window climber** is the whole controller (`WindowClimber`); its tiers are each
named for their steering signal — the **reactive climber** (≤4096, cross-sample ΔHR) and the
**density climber** (>4096, within-sample density ratio). The density tier in full is a
**goal-audited density climber**: density steers, probes rescue its blind corners, and the
goal-metric layer (anchor, guard rail, equilibrium audits) polices what density cannot judge.
"density arm/tier" below always means the steering component, not the whole machine.

The 2026-07-26 failure atlas and its backlog live in the local climber-failure-modes
workspace; its organising result — the
steering rule rests where `capacityShare = hitShare`, not at the hit-rate optimum — is §2.1's
identity, and the marginal-steering thread it opened is settled in §8 item 1.

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
  static-1% ≈ reactive wins, a 2026-07-26 fresh-holdout find; all still beat LRU by 11–61pp,
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
| `VETO_STREAK`, `AUDIT_COMMITMENT`, `Ladder.commitmentDepth()`, `Anchor.freshLeft` | dwell time | Morse 1996/1997; Liberzon 2003 |
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
3. **The ladders imply a duty cycle, and the 2026-08-04 duty study measured the real one.**
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
   budget does generate is the C2 *progress* clause (§7's 2026-08-04 entry).

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
audit walk's own crash abort against its reversal (2026-08-05, §7). The third is the sharpest
reading of the principle, because the two exits are five lines apart in the same walk and shared
one threshold: the crash abort is the cautious branch, priced on the loss it is measuring, while
the reversal is the probing branch, which must not turn around on evidence it cannot distinguish
from noise. Where a proposed change makes one bar serve two questions, this is the shape to check
for. One open
direction comes from the same lens and is recorded under §7: a detrended confirm reference (both
`Walk.beatBase` and `Anchor.freshLeft` exist because the reference is an un-detrended *level*, and
"a trend clears any raw streak" is what detrending removes by construction).

The map's other two suggestions were **built and refuted** by the 2026-08-02 derived-guard study
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
owns it; a confirmed family lands here and in the gate table, and its open direction lands in §8.

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
  classification (2026-08-06): d050 is one of the three genuine steady-state pins, −8 against
  LRU converged, its grid-locked cadence constructed-only — §7's reachability entry.
- **deadphase**: hot-set + pure one-shot scan bursts. Proves exploration during dead samples is
  ~free (admission shields main) — all variants sit at the ceiling. **But the safety is a knife
  edge on the ε symmetry, not on the dead sample** (2026-07-26 failure atlas): a fully dead sample
  gives `ln(ε/ε) = 0`, a no-op; break the symmetry with as few as **six window hits** and the same
  sample yields `err = +17.4` and the full 30%-of-max step. A rider of **380 requests in 1.97M
  (0.019%)** riding a victim's own scan phases costs the victim 8.3pp and drags the window from
  0.03 to 0.48; at 0.4% it pins the ceiling for −9.6pp. Delivered through `armProbe`'s
  refractory fall-through, which suppresses the probe but not the density arm. Reactive tier at
  4096 on the same trace: −0.44pp. See the archived failure atlas §7.1.
- **widepin**: whole-working-set alternation (pairs at 0.6·max ↔ loop over 0.85·max). A fixed
  window wins by never moving; every online climber pays here. Re-classified 2026-08-05/06: the
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
  **constructed-only** (§7's reachability entry). `esc_jam` and `tenant_s10` ride along as
  jam-family and co-tenant-family sentinels for the same layer.
- **shieldtrap / climbtrend / loopcliff** (`gen_adv.py`, round-2 instruments promoted to gate
  rows by round 4): regime-change-after-confirm, trend-driven misconfirm (plus the flat+wave
  `saw_p40` variant), and the no-cliff invariant at the structural misconfirm landing.
  `shieldtrap` and `saw_p40` are the **audit-amplification sentinels** (R4-F1): the round-3
  clock repairs multiply audit reachability — worth +10.4 on the attack rows, giving back
  −1.0..−2.1 on these already-below-LRU synthetics (audit share of the run 6% → 43% on
  shieldtrap, which also turns bimodal) while twelve real density-tier cells move ±0.07.
  The rows exist so the next audit-schedule change is measured against the cost side too.
  **Part of that give-back was never the schedule's**: the 2026-08-05 audit-bar split recovers
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
  (§7's reachability entry; the stillness-measure study stays deliberately unspent).
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
- **shallowmoat** (`/audit-regret` round 1, 2026-08-15; spec `audit-regret/specs/shallowmoat.json`):
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
  reactive arm beats the machine on 6 of 8 seeds (+2.0 mean). §7's 2026-08-15 audit-regret entry.
  Reach was repaired the same day (§7's wedge entry): a confirm the density arm reverses now
  deepens the ladder instead of resetting it, and the wedge seeds cross the cliff at s17 (30.7 /
  30.0 / 30.6 against 28.5 / 28.4 / 28.5). The row still reads far below the ceiling because the
  found position is not kept: after a walk from the floor, density's rest point on the far side is
  at or below the cliff (main holds the protected core, 3.7 hits per entry against the window's
  1.2), the guard rail cannot catch the fall (the fall inflates the deviation its margin is priced
  from), and the deep walk's confirm at the top is itself reversed, so nothing parks. Retention is
  §8 item 4's open half.

## 4. The shipped design (the probe machine, >4096)

Non-starved samples are the pure density step. The additions:

1. **Starvation bar**: a region is starved when its sample hits < `max(4, requestCount >> 10)`
   (0.1%, floored at 4 — the floor binds whenever the sketch's entry-denominated cap makes the
   sample shorter than 4096 requests).
2. **Blind corner gate**: probe only when the starved region is the *small* one (window starved at
   ≤max/4 → probe up; main starved with window ≥3·max/4 → probe down) or the sample is dead (both
   starved; direction away from the nearer bound). A *large* region earning nothing is visible to
   density and must not trigger a probe (probing "for" a scan-filled main destroyed corda_large).
   The gate outranks the goal-metric branches, which is right for the guard rail (it adjudicates a
   shortfall *on* the starved sample) and was wrong for the audit (it adjudicates over the samples
   that follow): a blind corner that never clears served its whole refractory motionless while the
   clock said the position was due, since `refractoryLeft` is armed by every `undoProbe` and
   decremented only inside the hold. A **due** clock now pre-empts that hold, a sample the machine
   was otherwise spending on nothing — the 2026-08-04 blind-corner entry in §7.
3. **The walk**: bold-driver seeded at the 6.25% restart magnitude in the probe direction,
   **scaled by the refractory rung — ×2 at rung 32, ×4 at rung 64, capped at the 30% max step**
   (a bound the current tables sit under — ×4 · 6.25% = 25% — so it binds only under retune)
   (2026-07 study: deep rungs previously bought *permission* to walk deeper but not *speed*, so
   escapes crept; rung-scaled strides punch through deep stray walls the flat seed stalled in —
   straywall2 +5..+11 across seeds and scales, d050 escapes fuller, corpus 205/205 ties since
   natural workloads never reach the deep *starvation* rungs — the audit ladder shares this ×4
   stride scaling and is routinely deep on real traces (2026-08-06: `audit.rung = 64` on 5 of 12
   real density cells, `auditWait = 128` on 4), so an argument from rung unreachability must say
   which ladder it means; full undo on failure is unchanged, which is what
   separates this from the rejected v7 travel-budget family);
   direction flips only on |ΔHR| ≥ the **reversal** bar, which is not the crash abort's (see the
   crash-abort ending): priced for starvation probes, and for audits
   `min(5pp, AUDIT_BAR_FRACTION · max(baseHitRate, noiseBand))` — so plateau crossings survive
   workload jitter.
   Walks honor the 2% floor (via an else-if after the reversal check, so an undo-to-base wins over
   the floor clamp), and carry a **sample budget of 16** in `Walk.samples`, which counts up from
   the arm (it borrowed the refractory countdown until 2026-08-02; the two uses never overlap, but
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
     **The reversal no longer shares this bar** (2026-08-05, §7): the two exits read different
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
     defended set. The walk's exit bars are settled (§7's 2026-08-05 entry): a starvation
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
   A confirm the density arm reverses in the same sample also deepens the ladder (2026-08-15):
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
   `deadphase` −0.2, accepted (§7's 2026-08-15 wedge entry has the guard that removes both and
   what it costs), and on `norank_rep_r6` one seed of eight (41.1 → 20.3): a reversed confirm on
   a rewarded ladder deepens it, so the fail that follows waits 16 samples where the reward's
   ladder waited 2, and the ×1 walk that reached that seed's escape re-arms 25 samples later.
6. **Refractory ladder**: length starts 16 and doubles per adjudicated failure to 64 (the arming
   doubles first, so effective waits are 32 → 64; after a kept confirm the next failure waits 2;
   a confirm the density arm reverses doubles the rung without a wait).
7. **Below-floor lift**: the density arm's clamp raises a sub-floor window to the 2% floor (the
   initial window is 1% and otherwise wedges under the documented floor). The blind-corner
   refractory hold honors the lift too — a workload whose every sample is blind never reaches
   the steering step, and the bare hold wedged the initial 1% window for the life of the run
   (2026-07-31 adversary F4, metronome; enforcing the floor there costs the one thin-signal
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
to sit inline as a comment block, and the conditions are questions (`Reading.hasBlindCorner()`,
`AuditClock.isDue()`, `isBackingOff()`, `Walk.shouldCrashAbort()`, `Walk.isConfirmed()`,
`Walk.canAdjudicate()`)
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
deliberately because three mechanisms in three classes read it (the reactive restart, the walk's
audit-side bars, the stand-down's shift trigger) and giving it to any one would misstate who owns
it. Where a constant has one owner but a second reader, the second names it
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
| Observation | `sample` (a `Sample`: `hits`, `misses`, `windowHits`, `probationHits`, `previousHitRate`) | the counters are zeroed at sample close; `previousHitRate` deliberately keeps a different lifetime, since it is the memory ACROSS samples that the reactive climber's direction and the walk's bold driver compare against — `close(hitRate)` carries it forward while zeroing the rest, and only `reset()` (a resize) discards it. `WindowClimber` keeps thin `recordHit`/`recordMiss`/`resetSample` delegates because `BoundedLocalCache` calls them on the read and write paths |
| The active walk | `walk` (a `Walk`, null while none is in flight: `ladder`, `isAudit`, `down`, `baseWindow`, `baseHitRate`, `baseAnchorRate`, `baseProbationDensity`, `samples`, `belowBarStreak`, `aboveStreak`, `beatBase`), `undoRemaining` | one walk at a time. Since 2026-08-02 it is an object rather than eleven flat fields, so "dead state while not probing" is the absent object rather than a comment, and a reader must hold a walk to ask it anything — `armProbe` is the complete constructor, `endWalk` clears the field, and the router keeps the ended walk in a local because the undo that prices it still reads the bases. The bases are `final`: frozen-at-arm is the property the verdict studies keep re-deriving (§4's "why frozen"), so the compiler now holds it. `walk.ladder` is the arming layer's ledger, which makes "an ending may only deepen the machine that produced it" a reference rather than a lookup; `refractoryLeft` is the starvation refractory alone and belongs to the row below |
| Starvation retry | `starvation` (a `Ladder`: `rung`, `crashStreak`), `refractoryLeft` | moved by starvation endings only (an audit undo re-imposes `refractoryLeft` and an audit confirm cheapens `starvation.rung` — the two journaled bridge writes, spelled out at their sites rather than hidden behind `Ladder`'s methods) |
| Audit retry + schedule | `audit` (a `Ladder`), `auditClock` (an `AuditClock`: `down`, `waitSamples`, `stillSamples`, `lastWindow`) | moved by audit endings and the position-stillness clock only. The clock owns `tick`/`isDue`/`restart`/`reset`, so the stillness rule (a moving sample **decays** the run, it does not zero it) lives with the counter it governs rather than in a climber method. `reset` deliberately leaves `down` standing — it alternates across audits for coverage and a resize has no opinion about which side to explore next, which is why a resize did not clear it before either. `rescheduleAudit` stays on the climber: it reads the ladder's rung and writes the clock, so it belongs to neither alone |
| Goal guard | `anchor` (an `Anchor`: `window`, `rate`, `held`, `freshLeft`, `returning`, `returnLeft`, `shortfallStreak`), `rates` (a `Rates`: `smoothed`, `deviation`) | anchor/park/veto authority and the rate references. `Anchor` is the memory *and* its defense in one object because the layer's three invariants run between those parts, and it now holds them by construction rather than by assertion: a shield lives and dies with its park (`park`/`hold`/`release` are the only writers — an audit's confirm arms a shield, a rail veto holds without arming or spending one, since the shield's clock belongs to the confirm that armed it), a park defends only a planted anchor (`discard` takes the hold with it), and a return implies the park that follows it (`beginReturn` arms both). `isAt`/`isAwayFrom` give the band test one definition instead of three inline copies, and they are deliberately not each other's negation — an unplanted anchor is neither at nor away, and there is no claim to veto against. `Rates` owns the EMA pair and the two bars priced off it — `noiseBand()` is the three-deviation width, `vetoMargin()` is that floored at `VETO_MARGIN_MIN` — so the rail's margin and the starvation probe's walk-interior bar read one definition instead of recomputing `VETO_MARGIN_SCALE * deviation` apiece. The deviation is read LIVE, and the audit's confirming streak is deliberately not priced off it; both notes live on `noiseBand()` itself. A stand-down that discards the claim re-seeds the pair (2026-08-03, below): the event that invalidates a claim invalidates the reference the claim would be re-planted from, and the two are one layer's state |
| Motion out | `step` (a `Step`: `size`), `reactive` and `density` (a `ReactiveClimber`/`DensityClimber`, each holding that same `Step`), `adjustment` | the single per-sample command. `step.size` and `adjustment` are NOT one object despite both being written once per completed sample: `adjustment` is drained by `BoundedLocalCache` across maintenance cycles as the transfer carry-over, so it changes at the cycle rate while the step changes at the sample rate. Both tiers write `step.size` — they are alternatives selected by the maximum, never concurrent, and `resized` re-seeds it — which is why each law holds a reference to the shared `Step` rather than a copy of its own |

Deliberate cross-writes (measured, kept): an audit confirm resets the starvation ladder to one
(cheap re-probing; neutral) *and* clears `starvation.crashStreak` with it, since a reset ladder
carrying a live streak re-escalates on the next crash; and an audit undo re-imposes the
starvation machine's own refractory (dropping it churned the blind-corner families). What an
ending must never do is write the *other* layer's ladder, streak, or schedule: a starvation
confirm leaves `auditClock` (with it the cold-start calibration) and `audit.rung` untouched, and
audit endings never deepen `starvation.rung` — each direction is pinned in
`WindowClimberTest` and range-bounded by the fuzzer/subject oracles.

Since 2026-08-02 each layer's rung and crash streak live in a `Ladder` the layer owns, so a
cross-layer write has to name the other layer's ledger to happen at all, and the two sanctioned
bridge writes are the only places that do. That separation covers **every** ending, not just crashes. A *non-crash* ending retires only
the crash streak of the layer that owns the walk: an audit's budget expiry leaves
`starvation.crashStreak`, and a reversal-through-base leaves whichever streak it does not own
(fixed 2026-08-02, pinned by `audit_budgetExpiry_leavesTheStarvationLedger` and
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
(2026-07-31 adversary round, F2: mixture d025@32768 spent 54% of the trace at its pin; the
room rule is worth +8.1/+9.7/+11.7pp on the mixture-audit family with every other gate row
unchanged). What was a real bug — a restart-scale improving sample at the wall
striding upward off positive zero's sign — is fixed by the negative-zero clamp and
regression-tested in `WindowClimberTest` (the wall-sit scenario replays the review's repro).

**Why the tiers stay:** the `hybrid-all` rejection at cs@563 (−1.27) rules out density at *every*
size, and the 2026-08-04 D2 study closes the weaker question of where the boundary belongs:
`corda`'s own tier crossover sits between 2048 and 4096 (density − reactive reads −0.61 / −0.86 /
−0.50 / **+0.43** at 512 / 1024 / 2048 / 4096), so the threshold is within one octave of the
sensitive trace's crossover even though the aggregate crossover is between 563 and 1024. The
placement is set by the worst case, not the mean — §7's D2 entry.

**The goal-metric layer (the F4 answer; shipped 2026-07-30/31, hardened through 2026-08-05).**
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
depth. Invariants and don't-harmonize warnings live in `rules/design-decisions.md`; the
per-round evidence is §7's ledger.

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
in the study report §6.1. The one survivor SHIPPED: the rung-scaled walk stride (§4.3).

### Killed by the 2026-08-15 wedge round (measured; the local climber-shallowmoat workspace)

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
  fires on the alternating trace, because every sample is a shift and `rates.reset()` re-seeds the
  smoothed rate to the arm sample itself.
- **wedgeshift** (a walk armed on the sample the rate fell into by a restart threshold is
  first-round; the guard the shipped rule can carry): measured and not landed. It restores DS1 and
  `deadphase` bit-for-bit and removes `phases_d050`'s two seed flips, is bit-identical on every
  non-wedge cell run (arc_P8, arc_S3, cp_w015, cp_w050, mixture_d050, straywall2, slowswap, moat),
  and gives back `strad_p8@4097` +1.9 → +0.5, `widepin` +1.7 → −0.8 and `phases_d050@32k` +4.8 →
  +2.7. Its evidence for the floors is one cell (DS1 is the only floor cell with any starvation
  confirm), so the pass is by construction; the instrument that would price it is the unspent
  stillness holdout. Add it if trough-armed deep walks show up at scale.

### Killed by the 2026-08-12 start-knob sweep: the 1% initial window is not worth moving (measured; 37 cells / 29 workloads x 5 starts x 3 seeds, stratified by measured optimum)

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
displacement to be walked back at §7's 4–6%-of-C-per-sample. A larger default therefore buys almost
nothing where it would help and costs nearly full price where it hurts. On two cells it lands in a
*worse* attractor than 1% reaches at all: `wiki_1191a@64k` frozen −0.12 → delivered **−0.99** on
3 of 3 seeds, `arc_P13@64k` frozen −1.59 → **−3.41**.

The storage families are the case against moving it: the ARC set is **16 of 18** frequency-favorable
with an optimum at a 1–2% window, losing monotonically as the start grows (frozen −0.15 / −0.44 /
−1.18 at 5 / 10 / 20%, worst −5.41). A 5–10% default is a straight tax on exactly the large,
frequency-signalled caches the 1% choice was made for.

`cp_w050@123038`'s basin (§7) does **not** argue for a larger default: at N=8 the 2/5/10/20% arms
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
belong to §8's item 1 rather than here: `arc_P1@64k` settles at a 30% window against a 1% optimum
for **3.25pp** (45.63 against a 48.88 frozen ceiling), and `cp_w097@16k` settles near 48% against a
10% optimum over 135 samples.

### Killed by the 2026-08-08 SLRU study: neither main-space knob is worth adapting (measured; 276 cells x 9 arms, plus a noise floor and a within-trace pass)

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

### Killed by the 2026-08-05 audit-bar split round (measured; the shipped form is in §7)

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
  between the shipped 0.45σ and 1.0σ is why §7 says not to re-derive the level.

### Killed by the 2026-08-05 two-sided round: the marginal family has a ceiling below the gate

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

### Killed by the 2026-08-05 margtail round (measured; 9 D3 cells at 3 seeds, 4 gate rows at 8)

`ln(d_tail/d_main)`, the marginal band priced against main's **average** instead of its probation
margin, was §8's named answer to the one marginal-steering residue that halving the gain does not
touch. It is dead in both directions, and the reason was derivable from §4 before it was built.

**It halves the prize.** On the nine D3 cells `margtail` is +0.43 mean with **four** losses
(P9 −0.41, P10 −0.55, w081 −0.64, w060 −0.75) against the full form at half gain `marghalf`,
+0.84 with one.

**It fails the gate it existed to pass.** Seeded at N=8, `slowswap_r1` reads 36.31±0.92 and
`slowswap_r20` 37.52±0.59 against a ≥40 bar and ship's 40.44 / 42.19. It recovers ~63% of
`marghalf`'s slowswap residue, not the ~89% the static rest-point anatomy implied (1.69 against
14.63pp), and still lands 4.1–4.7pp low. Two side results: §8's declared trade was wrong, since
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

### Killed by the 2026-08-04 reactive-band study (measured; 7 real cells + 6 trap families, seeded arms)

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

Two results from that study are **not** kills and are recorded in §7: the magnitude deficit is
partly a reversal-rule problem rather than purely a signal one, and the trap gallery's value is
partly variance reduction that N=1 comparisons hide.

### Killed by the 2026-08-02 derived-guard study (measured; 47-cell battery, arms rotated inside each cell)

The control-theory map (§2.1) suggested two guards that could be *derived* rather than tuned.
Both were built behind variant knobs and both are dead. The holdout frozen for the study was
never spent — neither arm reached a ship gate.

- **Freezing the walk's deviation reference at arm** (`devfreeze`): `walkInteriorBar()` prices a
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

### Killed by the 2026-08-03 stale-claim study (measured; workspace `climber-stale-anchor/`)

Both forms of the thread's other named fix direction — letting an off-anchor claim **age** toward
the live rate instead of freezing — fix the target and are dead for different reasons. The landed
fix re-seeds the goal metric on a discarding stand-down instead (§7).

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

### Killed by the 2026-07-31 F1 study (measured; data in `climber-adversary/data/f1_streak.csv`)

- **Unconditional park persistence** (a parked confirm never crash-releases): captures the full
  w097/whisper-p6 prizes but converts the tracking controller into hold-and-retest exactly
  where tracking is the cure — regimeramp 54.8 → 48.5, widepin 54.8 → 48.5 (below its bar),
  phases@8192 −4.1. The bounded fresh-park shield (one initial audit wait) keeps the prizes
  with all three at ship values — the shield length is load-bearing, not a tuning residue.
- **Deviation-priced audit margins generally** (the F1 mitigation table: `flat`/`ema`/`cap`
  margin modes recover only 25–35% and trade the rail for the audit) — superseded by the
  run-length streak; kept here so nobody re-prices the confirm by any multiple of
  `rateDeviationEma` again.

### Killed by the 2026-07-30 adversary round (measured; numbers in the climber-failure-modes ledger)

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
  live nondeterminism — nothing in the harness seeds it. On most cells the draws average out
  (measured over five batteries: 28 of 47 cells vary by <0.05pp run-to-run, 37 by <0.20pp). On a
  constructed bistable trap an early draw can tip the region composition into the other attractor
  and the climber's own feedback locks it in, which is what a **lottery cell** is: 6 cells
  (`phases_d050`, `widepin`, `shieldtrap_s7`/`s13`, `balloonflip`, `crashnoise_a12`) vary by
  1.1–5.5pp with the code held fixed. Consequences for reading a gate: a mover inside that set is
  evidence of nothing, the arms must be interleaved run-by-run so both see the same draws, and a
  single-cell regression is adjudicated by basin count at N≥10, never by a mean at N=2.
- Adaptive N is sound and ~60% cheaper: N=2 everywhere, N=5 where |Δ| ∈ 0.5–2.5pp or spread >0.5.
- Trajectories (window size per adaptation) are the diagnostic instrument — every fix in §4 was
  found by reading a trajectory, not a scoreboard. The debug hook pattern: stderr print in
  `determineAdjustment` behind a temporary system property (the simulator forwards `caffeine.*`);
  no hook is checked in — the 2026-07 study's working-tree harness (variant knob + debug + seeded
  admission RNG) is preserved in the
  local hill-climber-study workspace (`experiment-harness.patch`).
- **A holdout must be able to exercise the thing under test.** `AUDIT_BAR_FRACTION` is
  `min(RESTART_THRESHOLD, AUDIT_BAR_FRACTION · baseHitRate)`, so above a 1/3 hit rate it equals the
  absolute bar and every arm ties by construction. Selecting by "unspent + density tier + enough
  adaptations" is not sufficient: screen the candidate band against the constant's own binding
  condition first, or the freeze spends cells on a null result that reads like a pass.
- Holdout discipline: freeze a fresh holdout BEFORE tuning, sizes by LRU-only characterization,
  score it exactly once at the ship gate — and screen the candidate cells against the constant's
  own binding condition first (above). Every earlier holdout generation is SPENT (each spend is
  recorded in its study's entry and workspace); the density-climber **merge-gate holdout**
  (frozen 2026-08-01, hash-pinned spec in the merge workspace) remains **UNSPENT** — no
  candidate arm may touch its cells before the final pre-merge gate.
- The simulator has NO reference implementation of this climber (the experimental
  density-arm-only `SimpleDensityPropClimber` was retired rather than shipped
  half-faithful); `product.Caffeine` is the only arbiter until a faithful reference
  lands. The climber-class extraction that was to enable one has happened
  (`WindowClimber`), so a reference is now a port, not a redesign — still unbuilt.
- Simulator operational gotchas: the default `admission` list includes `Clairvoyant`, which wraps
  the trace in a materializing reader (pass `-Dcaffeine.simulator.admission.0=Always` for LRU
  anchors, or run product-only); `simulator:simulate`'s chart renderer wedges headless (run one
  size per call); Twitter/IBM holdout projections live at `~/projects/merlin-traces/holdout/`.

## 7. Current standing and open threads (a dated ledger; newest entries last)

**Corpus standing** (single-substrate, re-verified at the shipped tree): broad 205-cell corpus
**41 significant wins (+117.7pp) / 9 losses (−13.8pp) / 155 ties vs reactive**; the final
shipped build reads **+0.38pp mean vs reactive (14W/10L, worst cp_w097@16384, the named
residual)** and **−0.03pp vs the pre-layer density arm** — the goal-metric layer is
corpus-neutral insurance by design. Weighted byte track 14 wins ≥1pp / 0 losses; the spent
no-selection holdout read 4 wins ≥1pp / 0 losses; Merlin re-ranking objcount 36/56/196. Known
costs, disclosed: the sub-significant S/DS1/w50-family drag vs the pure density tier (the probe
layer's corpus-wide insurance premium) and the phase-lag limit family. The 2026-07 fresh-eyes
study's failure geography — F2 (verdict-veto rare pin), F3 (ladder latency), F4 (sighted false
equilibrium), B1/B2 — is archived with its raw data in the local stale-workspace tree (its
finding F1 was a measurement artifact: the original headline had bundled a read-buffer fix into
the comparison). Battery standing against the LRU/ceiling anchors and the reactive arm is §7.1.

**The fifth verdict form SHIPPED (2026-07-26 study, pre-registered; journal in the
local climber-fifth-form workspace):** up-probes adjudicate against the
probation-marginal baseline frozen at arm. Prizes, trajectory-attributed: trickle 56.1 →
69.5–73.1 by seed, bandtrap2 69.05 → 72.55, widepin 45.5 → 57.7, sample-aligned phases 46.9 →
60.1; every §3 escape and floor preserved; F4 controls exactly unmoved (verdict-only confirmed);
corpus, weighted spot set, and the defended real set all tie. Named trade accepted: the lowmix
family (the frozen baseline false-vetoes its LRU-ward escape; no real-trace echo). Holdout-3
spent at this gate; its proj3@1M/@4M cells sit 10–15pp below LRU for ALL arms and were queued as
the first candidate real-trace F4 instance.

**2026-07-30 (branch `climber-v3`): the F4 equilibrium audit shipped, then survived its
adversary round.** The audit (arm the walk machinery from a long-held *sighted* equilibrium;
adjudicate by the goal metric, since density holds the equilibrium under test and vetoes every
walk away from it) took whisper 55.5 → 64.1 and F4@65536 31.95 → 39.8. The adversary round broke
it twice; both closed: a periodic crash-scale rate swing zeroed a calm-based audit clock forever
— the clock now counts **window stillness only**, and the audit retry is floored at the initial
refractory (both required: either alone re-opens the jam, 55.2 vs 63.7). A 10% hot co-tenant
walked confirmed escapes home rate-neutrally — confirms now **plant the anchor**, the crash
stand-down discards it only when the crash lands *near* it (a far crash is usually the
controller's own retreat), the anchor never seeds mid-walk, and an **audit's confirm parks**,
since density disagrees with the position by construction (victim 57.4 → 59.6 of an undosed
64.1; the residual is bounded audit-excursion duty). The reactive-favored corpus residue
(cp_w097-class) survived three rescue attempts (§5) and is the named noisy-texture residual.

**2026-07-31: the final design landed on `v3.dev`.** Squash-ported tree-identical, gate held
with a paired reference-binary arm proving the port behaviorally identical — and restating two
stale phase-family bars calibrated on high draws, which is where the standing policy comes from:
**bimodal families adjudicate at N=8**. (The corpus numbers verified here are §7's intro.)

**2026-07-31 (adversary round on the shipped commit):** four findings (local
climber-adversary workspace). **F2 landed** — the first interior audit was
hard-coded downward against a floor guard smaller than its stride, so a low-window audit walked
into the floor wall and burned its budget clamped while the expiry doubled both ladders;
`auditDirection` now refuses a direction with less than one stride of room (mixture-audit
family +8..+12). Reopened at deep rungs by the rung-scaled stride, whose rule measured the flat
magnitude; closed 2026-08-03 by giving the distance one definition, `Ladder.stride`. **F1, the
big one** — `vetoMargin()` priced both the guard rail and the audit confirm in units of
per-sample scatter while the effect either must resolve is the window's 1–10pp contribution
(bars 4.3–62.4pp on 32 real cells, the 1pp floor binding on none), so both were inert exactly
where the window matters; margin tuning recovered only 25–35% because the two want opposite
scalings — resolved below. **F3** — the mixture sentinels sat 14–25pp below the parent reactive
climber, a comparison the gate had never run; finished by the room rule plus the cold-start
calibration audit (d025@32k 58.48 vs the reactive arm's 56.46). **F4 landed** — the
blind-corner refractory hold bypassed the below-floor lift, wedging an all-blind workload's 1%
window under the signal-capable floor for the whole run; the hold now returns the lift (w50
−0.5pp, the floor's documented price).

**2026-07-31 (F1 RESOLVED — the goal-metric layer re-priced):** the audit confirm became a
**raw-sample streak** — `AUDIT_CONFIRM_STREAK` = 4 consecutive raw samples above the reference
frozen at arm plus the 1pp floor, and the beat-base gate (round 2, below) — and a freshly
parked confirm **rides out crash-scale weather for one initial audit wait** (`anchor.freshLeft`).
The margins are split by design: the rail keeps `3·dev` (a false veto churns the anchor
continuously) while the confirm is priced by run-length statistics and self-heals at the next
audit — the two were sharing one function while demanding opposite scalings. Honest scope: the
walk offers ~a dozen overlapping streak windows, so this is a ~2–3× sensitivity gain, not
noise-independence; a trend clears any raw streak, the regimeramp/balloonflip/zigzag cost
class. Measured: whisper_mod p12 60.5 → 64.2, p6 56.4 → 62.5, a012/p12 58.6 → 60.4, mixmod
dose-flat; the cp_w097 family recovers +0.3..+3.6, the audit walking to the 7.5% optimum and
the shield keeping it through weather that previously released the park in ~5 samples. Fresh
holdout frozen first and spent once: 7 of 8 ties, prxy_1@196608 −0.49 the one disclosed cost.
Killed en route (§5): unconditional park persistence, every deviation-priced audit margin.
Residue: **park retention between audit cycles** (open thread below).

**2026-07-31 (F3 RESOLVED — the cold-start calibration audit):** the entire remaining mixture
gap was the pre-escape prefix — every cell armed its first audit at s34 and ran at parent-level
rates after confirming, having pinned 25–83% of its trace waiting out `AUDIT_WAIT_INITIAL`.
`AUDIT_WAIT_FIRST` = 4 makes the first audit after a (re)size a calibration probe; every later
wait uses the standard clock, so the jam defense's retry floor is untouched (W∈{4,8,16} swept;
4 dominates). Measured: mixture stock 39.3 → **58.0** (parent 56.4), long → **61.0**, @65536 →
**60.2** — all above the reactive arm; side-wins whisper 64.1 → **66.8**, mixmod undosed →
58.9. Priced cost, named: **regimeramp ~−1.3** — an early confirm during a steady rise is a
misconfirm window, parked for about a shield before the next audit re-tracks; the
no-shield-on-calibration variant was measured and killed (whisper_mod p6 −8: the shield is
load-bearing for young parks under weather). The post-landing audit caught a real latch:
`anchor.freshLeft` was set by every confirm but decremented only while parked, so a starvation
confirm left a stale shield for a later guard-rail veto to inherit — fixed (the shield is set
only by audit confirms, cleared wherever the park clears), with the invariant
(`anchor.freshLeft > 0 ⇒ anchor.held`) pinned in `WindowClimberFuzzer` and
`LocalCacheSubject.checkHillClimber`.

**2026-07-31 (round 2 — the cold-start misconfirm closed).** F1 and F3 compose into a defect
neither has alone: the streak's reference is an ABSOLUTE rate frozen at arm, so a calibration
audit arming while the cache is still filling carries the cold rate, every post-warm-up sample
clears it, and the streak completes on the warm-up ramp alone — on a stationary control the
first audit confirmed a **32.6% window while the raw rate was falling**, the landing structural
(floor + 5 strides ≈ ⅓ of max, reproduced across four constructions). The fix is a **necessary
condition, not a re-pricing**: the walk must match or beat its own starting sample at least
once (`auditBeatBase`), inclusive and margin-free because a saturating arming sample makes any
strictly-greater bar unsatisfiable — that variant silently disabled every later confirm on the
`rungflip` sentinel, caught by the gate, not by reasoning. Re-pricing was tried first and
trades (whisper_mod p6 −2.5): a *streak* against a noise-inflated bar is fragile where a
one-shot "did this ever help" gate is not, and the separation is wide (escapes beat their start
by 11.7–33.7pp; the misconfirm never, −0.18pp). Pre-fix exposure was bounded (three
preconditions must coincide; real-corpus cost mean +0.066pp, worst −0.46 on the corpus's most
window-sensitive cell). Durable guards: the two unit pins
(`audit_walkThatNeverBeatsItsStart_cannotConfirm`,
`audit_walkThatMatchesASaturatedStart_stillConfirms`) plus the regimeramp row — the round's
stationary instances are archived and generator-less; a reconstruction reproduced the
trajectory but not a discriminating cost. Also negative that round: the fresh-park shield is
not load-bearing for harm, and no cliff exists at the ~⅓ landing position.

**2026-08-01 (round 3 — the audit clock jammed by position; both breaks closed).** The round-2
repair made the clock immune to rate events by counting positional stillness, and a hostile
review (local climber-adv2 workspace) showed that exposed it to *position*
events: the run reset to zero on any super-band move, and the density arm commands a
super-band step for a density ratio of only ≈1.95, so a dose-matched, provably
window-irrelevant whisper modulation held the count at zero on 121 of 122 samples and pinned
the climber at the audit-free value (66.92 → 56.06, −8.9 below LRU). The jam's reachability
was itself a second defect: `probeEnding`'s shared confirm path wrote the audit schedule on a
*starvation* confirm, silently spending the never-run calibration audit — the sibling of the
`anchor.freshLeft` latch. Fixed by two one-liners: the clock **decays by one on a moving sample
instead of resetting** (decay-1 is load-bearing — the `d25` recovery works because the
calibration audit gets a foothold, parks, and the park's own stillness sustains the cycle),
and **a starvation confirm no longer writes the audit clock** (the schedule is the audit
layer's own). Recovered: `posjam_j50` 55.98 → 66.39, `posjam_d25` 56.04 → 66.53 vs the 66.92
flat control. Boundary negatives, do not re-derive: 50%- and 67%-motion cadences fail to jam —
audits arm, and `noaudit` *outscores* the layer on both cells (inert, not suppressed) — so the
aligned every-sample jam is the only realizable starving cadence. Priced re-base:
un-suppressing the layer lets the calibration audit reach families whose escape came from a
starvation probe (`mixture_d025@8192` −0.9 still above LRU, `lowmix` −0.4 — the recorded
excursion class).

**2026-08-01 (round 4 — the fix re-attacked; R4-F1 priced and rowed).** Round 2's own
instruments (`shieldtrap`, `loopcliff`, `climbtrend`) existed as committed generators with no
gate rows, so the round-3 fix was never run against them: `shieldtrap` gives back −1.1..−2.1
at N=8 (audits 2 → 7-8, the layer occupying 6% → 43% of the run with nothing confirming
usefully, a deterministic family turned bimodal) and `saw_p40` −1.01, while twelve real cells
move ±0.07 — the cost class is synthetic-confined, the trade stands at +10.4 real vs ≤2.1
synthetic, and all four families gained rows with the cause named (whence the standing rule:
an instrument without a row is not a regression test). Also corrected: round 3's "w097 +1.8"
was a low-N artifact (N=8 +0.47 ± 1.9; the layer's +3.3 vs `noaudit` is the robust signal
there). Negatives, do not re-derive: sawtooth weather does not misconfirm (`auditBeatBase`
holds against periodic as well as monotone drivers); the refractory→wait coupling never bites;
dynamic `setMaximum` churn hits both tiers identically (pre-existing, library-level, repro
`product.CaffeineResize`). Breadth: 50 real density-tier cells show zero materially below LRU
(worst −0.55) against wins to +24.8.

**2026-08-01 (round 5 — code audits; two edge repairs, both gated).** Two parallel code
audits converged on the same illegal transition: the audit-confirm sample fell through to the
density steering step, so the sample that planted the anchor and parked also steered off the
paid-for position — the park then defended a position the anchor did not claim. Fixed: an
audit confirm returns without steering (`cp_w097` +0.76 with run variance 1.9 → 0.06;
`whisper_mod_p6` −1.15 the priced cost); the park must **not** extend to starvation confirms,
which keep their density hand-off — density agrees with those positions, and cheap re-probing
is load-bearing. Second repair: `undoProbe` doubled `auditClock.waitSamples` on *any*
deepest-rung audit
ending, so a lone exogenous crash paid a failure's deferral; only a completed failure doubles
now (gate-silent, a semantic repair). Pinned in `WindowClimberTest`, and the fuzz oracle +
subject mirror gained two invariants (`probing ⇒ undoRemaining == 0`,
`anchor.held ⇒ anchor.window ≥ 0`). The round also re-based four stale off-battery rows and
promoted `balloonflip` + `mixture_d050` into the battery per the instrument-without-a-row
rule. Its last observation (O5/P7, the shared crash streak) was adjudicated won't-fix — and
that adjudication was **falsified the next day**: Terra's H4-C1 pulse train showed the corner
(three lone audit crashes pairing into rung 64 / wait 128, a 130-sample floor pin), and the
2026-08-02 crash-semantics study replaced the shared streak with per-layer ledgers.

**2026-08-01 (adversary round adv3 — the walk's exits carried the layer's only unpriced
bar).** `probeEnding`'s crash abort and `walkStep`'s bold-driver reversal both used the fixed
5pp `RESTART_THRESHOLD` while everything else in the goal-metric layer is priced against the
workload. Evidence: an amplitude-dosed ladder on the whisper base moves the terrain ≤0.8pp
while the climber falls 66.8 → 61.2 exactly where the dose RMS crosses the bar (a threshold,
not a gradient); on 14 real corpus cells the bar sat below the cell's own scatter on 11, and
28 of 37 armed audits ended in a crash abort. Deviation-pricing the bar closes the family and
is **rejected by the full gate** (zigzag −10.5, widepin below bar, demoflood −5.2): a priced
bar lets audits survive and confirm more, and an audit's confirm parks — **F1 and R4-F1 are
two ends of one dial**. The parked commitment-gate candidate (`gatecrash`) measured dead
(−3.85 on `cp_w097`): the axis is the bar's pricing, not the walk's depth. Round negatives
kept: `anchor.held` is not a one-way latch under sub-crash drift, and the probation denominator
cannot underflow its clamp.

**2026-08-01 (the pricing lands probe-side; the audit side is REJECTED at a fresh holdout).**
A four-cell isolation attributed `mn8_sine_a10` to the same defect on the **starvation-probe**
path — the dose's wave crash-aborted every up-probe one stride off the floor, severing the
mixture trap's only escape — which exposed the dial-free candidate: price the probe exits
only, since the R4-F1 coupling runs through *audit* confirms parking while a starvation
confirm hands off to density. Shipped as `min(max(5pp, 3·dev), 15pp)` with audits absolute:
mn8 +8.22 with every probe-load-bearing family tied; the one cost, metronome −0.60 (the
all-blind family's enormous deviation un-bounding the walks), recovered by `PROBE_BAR_CAP`; a
fresh 10-cell holdout frozen before the first candidate run and spent once, 10/10 ties.
Pinned four-sided in `WindowClimberTest` (sub-bar-continue, deviation-floor, cap-binding —
the test that dies if the `Math.min` is dropped — and the audit-absolute pair). The audit
side ran with the same discipline (second fresh holdout, anatomy before candidates) and its
best candidate, `audcap2`, swept the battery and then **failed the holdout**: P8@65536 −2.49
with the good basin eliminated (7/10 ship draws reach it, the priced arm never) and w097
−1.13 pricing-intrinsic. Verdict: cheap early crash-aborts keep audit duty low and
basin-reaching cadence intact on real traces, and every pricing form trades that robustness
for constructed-sentinel prizes — the full dead-candidate list is in the closed thread below.
The audit side was then answered by TIME persistence (2026-08-02) and the reversal's split
(2026-08-05).

**2026-08-02 (the crash-semantics study — two parallel adversary rounds, one fix).** Terra's
round found **H4-C1**: three token-multiset-preserving high→low pulses each kill one audit
walk, and the SHARED `starvation.crashStreak` — cleared only by non-crash probe endings, with
nothing ending between audits — adjudicates crashes 2 and 3 as failures, ratcheting the rung
and, through the deepest-rung branch, `auditClock.waitSamples` to 128: a 130-sample floor pin,
58.55 vs
LRU 64.56 with the token-identical reverse-order control at 65.41. The same day adv4 found
**the moat** (a terrain valley deeper than the audit's absolute bar is absorbing at every
rung — the abort fires at the −bar contour one stride short of the far bank) and **F2-adv4**
(audit endings alone drove the shared refractory ladder to 64, halving a later blind phase's
probe rate). One fix ships for all three, four measured pieces: **cadence** (a CRASHED
ending — lone or streak-escalated — never takes the audit clock's failure doubling),
**ledger** (each layer counts crashes and escalates on its own; audit endings leave the
starvation rung alone), **bridge** (the two neutral cross-writes stay: an audit confirm still
resets the starvation ladder, an audit undo re-imposes the un-inflated refractory), and
**escalated persistence** (`AUDIT_CRASH_PERSISTENCE` = 3: a first audit crash aborts on its
first below-bar sample — every-walk tolerance failed the mixture/mixmod bars — while the
RETRY of a crashing equilibrium tolerates two below-bar samples, holding its committed
direction at a decayed stride, and aborts on the third). Time persistence is the answer the
depth-pricing graveyard pointed away from: depth widening let walks travel and park at the
ceiling, while two samples of time cross the 2-sample moat valley and absorb 1-sample pulses
with a sustained collapse still aborting at 5pp. Results: h4c1 58.55 → **66.36** (all ten
lattice arms ≥63.2), moat 41.97 → **44.34** (above `noaudit`; the 0/22 static scan makes the
family constructed-only), `crashnoise_a12` −2.8 → −0.5 vs LRU, `whisper_mod_a12` → 64.00,
`mixnoise_a10` → 60.70, everything else at ship values. Journaled trades: `whisper_mod_p6`
−1.53, moat mid-doses −0.6/−2.0. Residual, gate-rowed: a pulse on a walk-ENTRY sample still
ends it via reversal-through-base as a completed failure. Pinned by the four crash-semantics
tests plus the re-based deepest-rung pair; the fuzzer and `LocalCacheSubject` oracles carry
the new field bounds; `gen_h4c1.py` and `gen_moat.py` joined the gate.

**2026-08-02 (the derived-guard study — marginal steering's premise measured directly).** The
premise was tested without a controller, by instrumenting the static-window anchor
(`sketch.WindowTinyLfu`) with region-attributed hits plus the window's LRU-tail band and
comparing where each signal rests against where the hit rate peaks. Across nine real cells the
average form's rest point sits **above** the peak on every cell where it crosses at all — the
derived recency bias confirmed with its sign — losing **1.263pp mean / 6.36 worst** against
the marginal form's **0.09pp mean** (figures per the corrected `marginal.py`, whose original
`crossing()` had counted repellers as rest points; the premise got stronger, not weaker; the
tail fraction is robust at δ = 0.1–0.3). This proves the cause and magnitude of the documented
give-back; it does **not** prove a marginal-steering controller scores better — the 2026-08-04
prototype and §8 item 1 carry where that went.

**2026-08-03 (adversary round: the room rule's stride drift LANDED; the seeded harness).**
`auditDirection`'s room rule priced "one stride of room" as the flat `restartMagnitude()` while
the walk it launches strides up to 4× that at deep rungs, re-admitting the wall-sit the
2026-07-31 F2 rule was introduced to prevent — reopened by the rung-scaled stride shipping as a
separate change while both this doc and `rules/design-decisions.md` asserted the invariant
unqualified. The fix gives the distance **one definition** (`Ladder.stride(Reading)`, read by
both the room rule and `nextStride`) rather than passing a corrected number, since two
spellings of one quantity is how the drift happened; pinned by
`audit_direction_roomIsMeasuredAgainstTheRungsOwnStride`; gate-neutral (battery +0.043, real
corpus 10/10 ties). The round also added the capability worth more than either finding:
`-Dcaffeine.climber.seed=<n>` (now `harness.py`) seeds `BoundedLocalCache.admit`'s 1-in-128
HashDoS draw — §6's only live nondeterminism — so runs reproduce **bit for bit** and a lottery
cell is adjudicated in one seeded run instead of N=10 draw counting (it immediately settled two
contested rows: one pure basin draw, one real). The unseeded N≥8 policy still governs claims
about the *distribution* of basins.

**2026-08-03 (the stale away-anchor claim CLOSED — a discarding stand-down re-seeds the goal
metric).** `Anchor.standDown` discards a claim tested at its own position, but `rates.smoothed`
— the EMA the *next* claim is planted from, on that same sample — is ~80% composed of the
regime that just ended, and `resync` refreshes a claim only on-anchor, so one steering step
froze that blend for the rest of the run and no later audit could confirm at any position. A
stand-down that discards the claim now also calls `Rates.reset()`: after a regime change the
machine is cold for its own purposes, and `DEVIATION_SEED` exists precisely so a cold machine
cannot veto or crash-abort against unmeasured scatter (a crash far from the anchor still keeps
both claim and reference — that shape is usually the controller's own retreat). Measured seeded
N=8: `slowswap_r1` 38.80 → **40.43** with the trapped seed 29.21 → 40.85, the dose-matched
ramp control unmoved on all eight; battery +0.080 with every contested mover bit-identical
seeded (basin draws); real corpus 10/10 ties — plus the three ARC cells `real.py` had silently
never scored (it declared `lirs` for ARC-format traces), which also tie. A fresh eight-cell
holdout (the libcachesim production set, never touched by any climber thread) was frozen first
and spent once: **8/8 ties, worst 0.05**, with audits demonstrably running on those cells, so
the ties are evidence rather than absence of opportunity. The two claim-aging alternatives are
dead with mechanisms (§5). Pinned by the three stand-down/regime-shift tests;
`guardRail_crashScaleShift_nearAnchor_standsDown` was re-adjudicated, since its
`anchor.window == 2000` assertion had codified the instant stale re-plant.

**2026-08-04 (adversary round r3: the blind-corner lockout, LANDED).** `densityClimb` ranked
`reading.hasBlindCorner()` above `auditClock.isDue()`, while `refractoryLeft` is armed by every
`undoProbe` and decremented only inside the hold — so on a trap where nothing confirms, the
whole 16/32/64-sample backoff was served motionless inside the trap with the clock permanently
due and never consulted. Isolated by a dose-matched pair over one terrain (`gen_blindlock.py`,
two gate rows): a 0.28% whisper rider flips only `hasBlindCorner()`, and the machine scored
**34.72 blind against 55.88 sighted** on matching terrain; `mixture_d050` and `straywall2` were
sitting 24.4 and 11.7pp below LRU, which the gate had never checked. The fix is two coupled
changes, the first making the second safe: `AUDIT_BAR_FRACTION` caps the audit's crash bar
against the frozen rate (a level test is unsatisfiable where the whole rate is under the
threshold, and such a walk halved the hit rate bounded only by its budget), and then **a due
clock may pre-empt a blind refractory hold** (alone that costs `arc_S2` −2.23 / `arc_S3` −1.41;
together −0.19 / −0.25). Measured: 49-row gate mean **+0.824, zero losses ≥1pp** —
`mixture_d050` 34.74 → **50.80**, `straywall2` 45.85 → **55.51** — with the real corpus and
floors tied and `metronome` re-based to bounded excursion duty. The fraction's level was then
verified at a fresh sub-⅓-rate holdout: inert off-corpus, resolving on `arc_S3` where 0.15 sits
on the plateau (+1.15 for the floor's presence). The one residual that sweep named — the bar
measuring 0.19–0.26 deviations on that family, neither branch noise-calibrated — became §8's
carve-out and was closed by the 2026-08-05 exit split. Pinned by
`armProbe_refractory_dueClockAuditsRatherThanHolding`, its below-floor pair,
`audit_lowBaseRate_crashAbortsOnAProportionalDrop`, and the negative
`audit_highBaseRate_crashBarStaysAbsolute`. Round negatives, do not re-derive: spending the
idle sample on a *starvation probe* (`blindprobe`) is worse everywhere it costs (the audit's
goal-metric adjudication is what keeps a walk from displacing a correct floor), and dropping
the blind-corner refractory outright (`norefr`) breaks demoflood/slowswap/metronome/zigzag —
**the stillness gate is load-bearing**.

**2026-08-04 (the duty-invariant study — §2.1's "one invariant" is measured false).** H4-C1,
adv4-F2 and the blind-corner lockout share a shape — *a deferral measured in the wrong
currency* — but not one checkable quantity: the first two violate the schedule's
**provenance**, not its magnitude (`maxOverdue` measures 0 through the whole H4-C1 pin), so no
elapsed-time or duty bound can see them. What survives is three oracle clauses, none a duty
bound. **C1/C1b (ownership/evidence)**: a layer's rung and wait grow only on a sample that
ended its own walk, and the wait passes its floor only on a walk that reached a verdict — zero
constants, kill mutations of the landed crash-semantics fix in under a minute, but regression
value rather than discovery. **C2 (progress)**: a due audit is refused for at most 29
consecutive samples, the worst chain of bounded branches outranking the clock — real discovery
content (on the pre-fix tree it fails the fuzzer in 90s and fires on exactly the cells the
blind-corner fix moves), yet not adopted: its margin sits one sample under the shallowest
defect instance, and it is a mechanism detector whose firings need a human to price (it also
fires where the refusal is load-bearing). Killed alternates: upper duty bounds (every defect
is duty-too-*low*), sliding-window lower bounds (permanent false positives on
posjam/saw/climbtrend), and "consecutive samples commanding no motion" (C2 in disguise, or
blind at the absolute level a legitimate deep park requires).

**2026-08-04 (the marginal-steering prototype — the signal is real, the controller is not
shippable).** A real-`BoundedLocalCache` prototype (per-node band bit validated on **0 of
393,818** marker disagreements; `margavg` — band maintained, average steering — bit-identical
to ship) measured the signal at **+1.22 mean over 13 real cells with zero losses**, including
`cp_w097` +2.43, the doc's structural residual; the falsifier ran first and confirmed the
shipped machine really rests at the density rest point, leaving 1.74pp mean to the static peak.
The controller failed for two causes, only one belonging to the steering law: the rougher error
(**34 monotonicity inversions against the average form's 5**) commands ~2× the motion, window
stillness collapses, and the audit layer goes dark — repaired *entirely* by halving the gain (a
threshold on the stable band, not a monotone trade) — and a signal defect gain does not touch:
under heavy inflow the **probation denominator stops being main's margin**, probation fills
with transients, and the window is driven to the floor (`slowswap`, rest point 14.63pp below
the peak). Negatives, do not re-derive: the free half `ln(d_w/d_prob)` is the wrong half
(worse than the average form); a fallback-to-average trigger is a net cost; and `margraw`
incidentally **fixes `posjam_d0`** (56.07 → 67.03), evidence the jam belongs to the
consecutive-run stillness measure rather than to the dose. No holdout was spent — no arm
reached a ship gate. Implementation scope if ever resumed: the band bit packs into `queueType`
for free, ~9 marker sites plus `setWindowMaximum`, ~25 lines in `WindowClimber`. **§8 item 1
is the authoritative continuation** — the frontier, the gated arm, and the
rest-point-tracking screen.

Open threads, roughly in order of expected value (each has a ledger entry with its data):
- **The away-shift stale claim — MEASURED 2026-08-06 (Terra r4); cost zero, parked.** The
  2026-08-03 repair keys on the discard, which happens only when a crash-scale shift lands
  **at** the anchor; a shift arriving while the window is **away** deliberately keeps both the
  claim and the reference (a far crash is usually the controller's own retreat). The r4
  instrument (`staleaway.py`) retires this thread's "no repro": the state is common and
  long-lived — battery runs to 39 samples at a 27pp claim gap (`balloonflip`), real corpus to
  70 samples (70.3% of `cp_w058`) at 18–24pp gaps — and 11 of 11 audits armed inside a stale
  run confirm zero times. A constructed monotone decline on whisper terrain (sub-5pp steps so
  `isWorkloadShift` never discards, dose-matched reversed control) builds the state
  deliberately and still loses nothing: the audit layer keeps its escape there, +8.7 over
  `noaudit`. De-staling is measured free: `freshref` (the confirm reference becomes the live
  rate frozen at arm; rail untouched) is bit-identical at N=8 on 12 cells — 96 paired runs —
  because audits inside a stale run die to the crash abort or the budget before the confirm
  reference is consulted. A real state masked by an earlier exit; reopen only with evidence of
  an audit surviving to its confirm test inside a stale run.
- **Marginal steering — see §8 item 1**, the authoritative record (re-priced 2026-08-05: the
  frontier, the gated arm's escape and its recovery residue, and the rest-point-tracking
  screen for any successor).
- **A stillness measure that is not a consecutive run — STUDIED 2026-08-04; nothing ships;
  only the arming side remains.** Five measure families were built behind variant arms and
  measured against the full battery, real corpus, floors, and a dose ladder. `frac` (stillness
  as a fraction) is arithmetically dead — the fraction under the shipped predicate is 0.008 on
  `posjam_d0`, so no bar above zero arms anything; a fraction is a different aggregator over
  the same attacked statistic. The low-pass family defeats the whole jam class and loses a
  deterministic control (`slowswap_r20` 42.19 → 34.90 at every tolerance that fixes the jam;
  `lpg` rules out the lag hypothesis). The transferable insight is why `conf` differs: its
  reference is **frozen for the life of the run**, so an accumulating drift eventually escapes
  it — **cumulative displacement is the right currency for "this equilibrium has been held";
  instantaneous deviation is not** — but `conf2` fixes only the shipped dose while costing
  three modulation rows. The structural conflict: defeating the jam needs a tolerance above
  its ~2.7-band realizable excursion, any tolerance admitting that orbit manufactures one
  extra confirm-and-park per run on the tracking families, and a rung-16 walk stride is 3.1
  bands, so the low-pass cannot tell the machine's own excursion from rest (genuine
  amplitude-independence needs phase-matched block means; two EMAs do not achieve it). The
  confirm-side reframing is dead: `lp20+prov` takes **zero parks** and is bit-identical —
  density puts the window in the hole with no anchor holding it there, the extra
  confirm-and-park was partly *recovering* the loss, and a loosened clock arms audits before
  the damage anyway. Two velocity pins (`auditClock_halfMotionAlternation_neverAccumulates`,
  the wandering half of `auditClock_stillnessToleratesTheOrbit`) re-adjudicate any candidate;
  the study's holdout (two floor-optimal cells with 11–45pp of headroom) is frozen and
  **UNSPENT**.

- **Park retention between audit cycles** (the F1 residue): w097-family audits confirm AT the
  optimum, and the fresh shield holds the park for one audit wait — but on weather-heavy
  traces the position must survive *between* audits, and the parent arm is still +2.6..+5
  ahead there. (Scope, corrected by adv3: the `whisper_mod a0.12/p12` row is no longer this
  thread's evidence — its residue is the walk-interior crash abort. The thread's instance is
  the w097 family, where audits do confirm and the crash-bar counterfactual does not help.) The adversary review's "bound how far density may travel from a validated
  position" (a tether: steer freely within a band of the anchor, refuse beyond) is the
  direction; unconditional persistence is measured dead (regimeramp/widepin/phases, §5), so
  any candidate must keep tracking on drifting workloads.
- **The trend-blind confirm — PRICED 2026-08-06 (Terra r4, the dated entry below).** A
  monotone rate trend clears both confirm levels and parks the window off-optimum
  (−0.94/−0.76 vs `noaudit` on the riser pair, against −0.35/+0.09 on dose-matched flat
  controls); not cold-start-specific, which the M6 "warmup" account assumed. §2.1's detrended
  confirm reference is the direction, and the riser pair ships as its gate rows with whatever
  change closes it.
- **Audit-side excitation**: the killed steering blend's two genuine prizes (trickle +3.6,
  bandtrap2 +3.1 — sustained excitation helps held-position families) want a carrier that can
  price and undo what it stirs: an audit-side dither or a shortened first-audit wait. Any
  candidate must pass the ESC study's standing instruments (does confidence collapse when
  nothing is attributable; does jitter suppress the audit clock).
- **The D2 tier cliff — MEASURED 2026-08-04, then CLOSED by `corda`.** On a constructed
  straddle the discontinuity is the policy, not the terrain: one entry of capacity moves the
  answer **−7.58pp** while LRU steps 0.00 and both single-law arms are continuous (the sketch
  confound controlled via 8192 → 8193; the stationary control reads +0.69, so the alternation
  is load-bearing). On real traces the sign **inverts**: 8 of 21 cells step +1.17 to +2.94
  crossing *upward*, so the cost is borne **below** the boundary — the reactive tier is the
  loser there, not density.

  **Half the cliff is not in the law** (the tier gate switches the sample period with the
  steering law; a 2×2 split shows the dominant term flips with terrain), and the real-cell
  claim replicated across 9 workloads / 29 cells with a one-directional sign (no cell at or
  below −1pp; direction on nine workloads, magnitude on three). At 2048 the same cells prefer
  density 8 of 8, which pointed at the threshold's **placement** — and **`corda` closes that
  interval**: the designated gate trace prefers reactive at every size below the boundary and
  flips exactly at it (−0.61/−0.86/−0.50 at 512/1024/2048, **+0.43 at 4096**, deterministic).
  The placement is set by the worst case rather than the mean, and `corda` is its only
  defender — at 2048 it is the single dissenting cell of twelve, so if that trace were ever
  judged unrepresentative the interval (563, 2048] reopens at once. The residual is one entry:
  at exactly 4096 everything measured prefers density, and `>=` would capture it while landing
  the switch where `ceilingPowerOfTwo` does not change — recorded as an option with its trade
  (it relocates the cliff to an unmeasured boundary), not a recommendation. Cadence alignment
  across the gate is measured dead (−2.37 on the straddle; it trades families). Gate rows
  `strad_p8`/`strad_stat` pin the pair.
- **The frozen sample grid (D4)**: the trace-start alignment offset is worth up to 12.5pp on
  phase-aligned constructions, frozen for the run. No resonance exists (aliasing comb measured
  negative), so a period dither is a one-line candidate — unmeasured, N≥8 territory.
- **Guard-rail attribution — RE-MEASURED 2026-08-04, causes separated 2026-08-06; deletion
  REJECTED.** The rail fires rarely (7 times across 3 of 13 real cells, twice on the battery),
  for two distinct reasons the record once stated as one. On blind-corner cells it is **not
  reached**: the refractory hold outranks the rail's branch, and the starved samples the hold
  claims are exactly the stalest ones (21 of `balloonflip`'s 40 stale-claim samples are
  `hold`). Where it is reached, which test binds depends on the population: on the battery the
  **margin** does — `shortfallStreak` never reaches 1 on 42 of 43 cells with the shortfall
  present and large (`metronome` steers 23 of 49 stale samples at a ~7pp gap that `3·deviation`
  swallows every time) — while on real cells the **streak** does, the shortfall holding on ~¾
  of samples for 25 straight while the streak never exceeds 3, one clearing sample resetting
  it. The corollary worth keeping: between the veto's `rate − 3σ` and the confirm's
  `rate + VETO_MARGIN_MIN` lies a band in which neither goal-metric layer can act, and the
  machine spends real time there. Deletion is rejected by the moat family's shallow doses,
  where the rail is the only mechanism that recovers the window (`moat_h4000` +2.44,
  `moat_h5000` +3.36, 8 of 8 seeds; four audits arm over the run and none rescues it);
  everywhere else removal is free (real corpus 13/13 ties). Honest limit: the moat family is
  constructed-only, and on the real cells where the rail fires it is worth −0.01pp — kept
  because it is free and is the sole exit on a rowed adversarial family; if the moat rows were
  ever retired, the case for the rail goes with them (and §5's `agedown` kill, which rests on
  disarming the rail, would need re-grounding). Two facts not to re-derive: the streak is not
  consecutive **in time** (walk, undo, return and blind samples neither increment nor reset
  it), and `beginReturn`'s hold is cleared only incidentally when the return's own rate move
  trips `isWorkloadShift` — that suppresses density steering, not exploration, since a due
  clock outranks the hold.
- **The audit-side walk-interior bar — CLOSED (2026-08-02)** by the crash-semantics study
  above: the bar's DEPTH stays absolute 5pp (every depth pricing was holdout-fatal or
  mixnoise-fatal — dead, do not re-derive: commitment-gating −3.85 w097, `devaudit` uncapped,
  `audcap`/`audcap2` holdout-rejected, `audref`, `evcrash` on paper, `escbar`/`escbar2`
  escalated-depth −2.48 mixnoise intrinsic, every-walk `crash2`/`crash3` time-tolerance
  fails `mixture_d025`/`mixmod`'s bars) and the shipped answer is **escalated TIME
  persistence** — `AUDIT_CRASH_PERSISTENCE` on the crash-streak retry, with the tolerated
  walk holding its committed direction. Sentinel record: `cn_sine_a12` 61.55 → 63.62 (the
  priced-counterfactual 66.93 remains unreachable without the holdout-fatal depth trade),
  `whisper_mod_a12` 62.21 → 64.00, `mixnoise_a10` 59.82 → 60.70. The third fresh holdout
  (`holdout_crash_semantics.md`: P8 + w097 mandated, P12/ConCat/fin1/mds_0 fresh) was spent
  on this study's ship gate.
- **The walk-entry reversal residual** (crash-semantics study): a pulse on an audit walk's
  entry sample still ends it through reversal-through-base as a completed FAILURE (rung
  doubling; the deepest-rung failure legitimately doubles the clock), so a four-pulse train
  aligned on walk entries reaches one wait doubling. Bounded by cadence at the ladder's own
  values and gate-rowed via the h4c1 lattice; a fix would need the entry stride to
  distinguish its own damage from a shift — unstudied, low expected value.
- **The adv4 closure record (2026-08-02)**: the moat family is **constructed-only on present
  evidence** (0 of 22 informative real cells show moat terrain; the gate rows pin the
  boundary). Negatives not to re-derive: the density arm does **not** self-jam the floor clock
  (the floor clamp is a hard absorber, so posjam needs an attacker's dose), and no sub-LRU
  regression is constructible in the moat family. Still open: **H7**, the composed >75%
  behavior (`mainBlind` arms a down-probe every sample while `auditDirection` refuses upward)
  — judged mostly covered by the ε-asymmetry entry below but never measured as a composition.
- **Weighted traces have a static-window anchor — CLOSED (2026-08-02)**: `WindowTinyLfuPolicy`
  declared no `@PolicySpec` characteristics, so weighted runs silently dropped the
  static-window ceiling. The anchor is now weighted (regions budgeted by entry weight,
  degenerating bit-for-bit to the classic form on unit-weight traces, so every recorded
  unweighted number stands). Judge climber terrain on the object-rate column, not the byte
  panel; boundary pins in `WindowTinyLfuPolicyTest`.
  **The admitter had to be retracked to make that anchor trustworthy (2026-08-07)**: it was
  sized once, when the cache first filled, and never re-sized, while the cache re-calls
  `ensureCapacity(mappingCount())` on every addition. Since the sketch's reset period scales with
  its capacity, a frozen one is TinyLFU aging at near-zero period, which flatters *small* windows
  (where nearly every admission is filtered) and washes out at large ones. It inverted a real
  cell: `metaCDN_rprn@4G` read its static-window optimum at a **1%** window when it is at 80%.
  No recorded number was affected, since no weighted ceiling had been taken yet. The sketch now
  retracks per addition through `Frequency.ensureCapacity`; unweighted output is bit-identical.
- **The occupancy denominator** (probation occupancy instead of capacity): the fifth-form
  study's declared alternate, never spent — its target failure shape (starving-inflow probation
  underpricing the baseline) has not been observed; try it only against that specific shape.
- The density bias give-backs (P5 −2.9 worst vs reactive, +12 above LRU even so) are *not*
  addressed by the probe machine — they are non-starved density decisions. Boundary marginal-cost
  damping moved them for the first time (+1.5) but its admission-boundary comparison is degenerate
  (victims are always sketch-hotter by construction); the in-window-reuse rate (measured 27% on a
  recency winner vs 5.6% on the worst give-back vs ≤1.5% on floors) is the non-degenerate signal
  if the thread resumes.
- The D1 ablation extended the §3 impossibility result to a second statistic: at the floor,
  trapped and legitimate windows are indistinguishable by in-window reuse too (0.0–0.2% both
  classes); post-escape they separate near-binarily (87–92% vs ≤1.6%) — a verdict signal, not a
  trigger signal.
- fiu_ikki trails its static ceiling ~1.9pp from sample starvation on a finite trace (4×max period
  gives it ~7 adaptations); believed a benchmark artifact.
- widepin-class whole-working-set alternation remains the limits family, softened twice (the
  marginal verdict sustains confirmed positions; the anchor shifts the basin mix). Since the
  2026-08-05/06 length audits: widepin's own deficit is convergence cost (it ends at a 77.9%
  window against an 80% optimum; a seeded-only row), while `phases_d050` is the genuine pin
  (−8.03 at steady state, constructed-only) — only a lag-free signal, or accepting
  fixed-window behavior under detected thrash, closes the rest.
- The ε-asymmetry in the density error (`ln((w+1e-9)/(m+1e-9))`) drives the window to its
  structural ceiling when main earns exactly zero. The ~80% figure is `increaseWindow`'s
  geometry, not a tuned cap: climbing donates only the protected allocation, so the window
  tops out at maximum − probation. Read it as a deliberate prior — a region earning anything
  takes everything donatable from a region earning exactly nothing — bounded by the probe
  machinery and priced twice: ~1.2pp per repeated 5-sample blackout (`ratchet`, dose-monotone,
  far above LRU) and **~8pp against both the static ceiling and the reactive arm** on the
  family built to trigger it every other regime (`balloonflip`, diagnosed in the 2026-08-04
  reactive-anchor entry below) while still 26pp above LRU. A cleaner formulation may exist;
  that cell prices any attempt.

**2026-08-04 — two corrections from the reactive-band study (the kills are in §5).** Neither is a
defect; both change how a future proposal should be argued.

- **The magnitude deficit is partly a reversal-rule problem, not purely a signal one.**
  `adaptive-window.html` §3.5 now carries this correction in place (the pre-incumbent-chapter
  text read the gap as intrinsic to finite differencing). It is not entirely:
  banding the reversal recovers density's whole headline on cp_w061@64k (34.17 → 38.23 against
  38.35) and +1.77 on umass F1@32k, because ten unreversed decaying steps already cover 57% of
  the range. What the band cannot do is *know when it is allowed to run*, which is why it loses
  10–26pp on the constructed families. The open question this leaves is narrower and better posed
  than "band the reversal": under what evidence may the reactive law commit to a run? That is the
  adjudication question the probe machine answers for density.
- **On the trap gallery the machine partly buys variance, not level, and N=1 hides it.** The
  reactive arm is close or ahead on the deterministic families (whisper 66.04 vs 66.78, mixture
  d025 56.46 vs 58.48, mixture d050 long 58.44 vs 58.18 — reactive ahead), and where the gap is
  large it is often a spread: straywall2 reactive 48.12 **±19.84** vs 55.07±0.03, demoflood 61.83
  **±9.87** vs 68.56±0.05. Single-seed draws on those two read 54.71 and 68.40, i.e. near-parity,
  and are wrong. These families were constructed to attack the density signal, so "the machine
  beats LRU here" is the wrong bar; the honest bar is the reactive arm at N≥3. Only three gate
  rows currently carry a parent-reactive figure — adding that column is the cheap way to keep
  this checkable.

**2026-08-04 — the reactive anchor, and the flagged rows (each since priced or parked;
reachability verdicts through 2026-08-06).** The gate battery now carries a paired
`hybrid,reactive` column (51 of 52 rows; `climber-gate/SKILL.md`). Headline: **the reactive arm
is ahead on 18 rows, and the density machine is worth under a point on 28 of 51.** The machine's
load-bearing rows are eight — `zigzag_s7` +28.1, `straywall2` +17.5, `trickle_s11` +15.1,
`slowswap_ramp` +13.3, `slowswap_step` +9.3, `widepin` +8.5, `moat_h3000` +6.6, `phases_d050`
+5.6 — and those, the corpus mean, and the variance reduction on the bimodal families are the
whole case for the tier on constructed workloads. The reactive-behind rows, worked to verdicts:

- **`posjam_d0` −9.29, confirmed at N=8 and CONSTRUCTED-ONLY.** §11 priced the aligned position
  jam as the audit layer's forgone value; it is a deterministic regression against the incumbent
  (56.08 ± 0.05 vs 65.37 ± 0.20), below this cell's LRU where the reactive law is above it, and
  the dose-matched flat control inverts (66.92 ± 0.01 vs 64.61 ± **10.71**). But the reachability
  scan the moat precedent requires comes back clean: **0 of 14 real cells** show the signature.
  `exposure.py` on the ten cloud-physics cells at 16k, arc P8/S3/ConCat at 64k and corda at 8k
  reads stillness fraction 0.37–0.92 with `maxrun` 9–128 and 1–15 audits armed per run, against
  the jam's **0.008 / maxrun 1 / zero audits**. The two nearest approaches are instructive:
  `corda` is lowest at 0.368 / maxrun 9 — it calibrates once and never re-audits, the known
  blind-hold — and that costs nothing, because its terrain is flat and it scores 33.0 against a
  33.33 ceiling. Low audit exposure only bites where the terrain has something to find.
  `arc_S3` at maxrun 16 sits under the 32-sample re-audit wait but over the 4-sample calibration
  wait and still audits twice. Both are 9–16× the jam's longest run. Adjacent cadences already pass
  (`posjam_j50` 66.44, `posjam_d25` 66.53), so it is a knife-edge rather than a basin. **Do not
  chase it**: the named fix direction redefines the stillness measure the whole audit layer rests
  on, and that clock has produced the last three severe findings. Priced and parked, like the
  moat.
- **`mixmod_a010` −2.44 against the reactive arm and CONSTRUCTED-ONLY** (2026-08-06). The last row
  in the battery never given a reachability scan. The signature comes from the generator: `gen_adv`
  builds the mixture trap and then trades the hot share against **one-shot cold keys on a slow
  wave**, so the sample hit rate moves while the window that captures the reuse band does not.
  Measurably that is two things at once, and the dose-matched pair calibrates both without an
  invented threshold: **per-sample rate scatter against the audit's crash bar** (`σ/bar` **2.00**
  dosed against **1.14** undosed) and **a stationary optimum** (best static window **20% in all
  three trace segments**, dosed and undosed alike, `trace.skip`/`trace.limit` probes). The undosed
  control is the proof the second half matters: it also has a stationary optimum, carries `σ/bar`
  1.14, and costs nothing (58.83, +3.77 over LRU). Note this is **not** a stillness-starved cell
  like `posjam_d0` or `bandtrap2` — the audit layer is armed and running (`maxrun` 32, 4 audits).
  **The scan returns 1 of 14 real cells, and it is the `corda` case.** `cp_w044` carries both
  halves (`σ/bar` **3.42**, best window **50% in all three segments**) — and its entire terrain is
  **0.45pp wide** (LRU 53.22, ceiling 53.67), so the exposure is worth at most that and the machine
  already banks +0.16 over LRU there. As with `corda` under the `posjam_d0` scan, an exposure only
  bites where the terrain has something to find. The other near approach is instructive in the
  opposite direction: **`arc_ConCat`** is the one real cell carrying the trap's scatter (3.78) on a
  **wide** terrain (11.96pp), and its optimum **moves** (best window `[1, 1, 2]`) — that scatter is
  window-*informative*, which is precisely the case the goal-metric layer exists to answer.
  **The structural reason the trap is hard to reach**: across this corpus high scatter and wide
  terrain are anti-correlated. Every cell with `σ/bar ≥ 3` has ≤2.91pp of terrain (`cp_w015` 4.10
  on **0.03pp**, `cp_w060` 3.39 on 0.39, `cp_w044` 3.42 on 0.45, `cp_w081` 5.17 on 0.80), while
  every cell with a wide terrain sits at `σ/bar` ≤ 1.5 (`arc_P8` 1.48 on 14.59pp, `cp_w100` 1.15 on
  9.45, `cp_w098` 1.17 on 9.21). `mixmod` is constructed to hold both at once — `σ/bar` 2.00 on a
  7.17pp terrain — and no real cell in the set does. Priced and parked, like `posjam_d0`.
- **`bandtrap2` −4.92 against LRU, a genuine steady-state pin and CONSTRUCTED-ONLY** (2026-08-06).
  The 2026-08-06 length study separated this from the warmup rows: the window is flat at
  23.0 / 22.7 / 22.4% across its three thirds against a **40% optimum**, so it is settled rather
  than converging. The signature is in the generator's own note — a pair band at an exact reuse
  distance of 6500 latches only in a **[2087, 4510] window (25.5–55%)**, and adjudication there is
  **density-inverted** (`wd ~1.35` against `md ~4.0`), so the region that would earn the band is
  the one the steering signal shrinks. Measured, the machine cannot escape by re-testing either:
  stillness **0.047 with maxrun 2 and zero audits armed**, which is under even the 4-sample
  calibration wait, so the audit layer never runs on this cell at all. That makes it a
  stillness-starved cell in the `posjam_d0` family rather than a valley. **The reachability scan
  comes back clean: 0 of 14 real cells.** Across the ten cloud-physics cells at 16k, arc
  P8/S3/ConCat at 64k and corda at 8k, `maxrun` runs **9 to 128** with 1–15 audits armed, against
  the trap's 2 / zero. The nearest approach is `corda` at **maxrun 9 / still 0.368 / 1 audit** —
  4.5× the trap's longest run, the known blind-hold, and it costs nothing because the terrain is
  flat (33.0 against a 33.33 ceiling). Only two real cells rest *below* their optimum at all
  (`cp_w015` 0.79 of ceilwin, `cp_w060` 0.85), and both have the audit layer running (10 and 8
  audits) and pay ≤1.02pp for it. Both halves of the signature are needed and no cell has either
  one at trap strength. Priced and parked, like `posjam_d0`.
- **`phases_d050` −8.03 at steady state and CONSTRUCTED-ONLY** (2026-08-06). Its −22.05 whole-trace
  number is mostly warmup, but unlike every other short row it does **not** reach parity: the final
  third is 64.16 against LRU 72.19. The signature is a **cadence locked to the sample grid** —
  `gen_phases` alternates a loop over 0.9·max keys (wants window ≈ 0) with pairs at distance
  0.5·max (wants ≥50%) in phases of `16 × max` requests, and the sample period is `4 × max`, so
  **every phase is exactly four samples and every boundary lands on a sample boundary**. It shows
  up in the controller's own signal as an autocorrelation of the per-sample hit rate of **−0.56 at
  lag 4**, with stillness `maxrun` **5** — enough to calibrate once, never the 32 needed to
  re-audit. **0 of 14 real cells** carry it: the most negative real autocorrelation is `cp_w058`
  at **−0.38, but at lag 11** with `maxrun` 32 and 3 audits armed (a slow drift, not a 4-sample
  alternation), and the only short-lag negative is `cp_w038` at **−0.06 at lag 4**, an order of
  magnitude under the trap. The one real cell whose `maxrun` sits under the audit wait is `corda`
  (9), and its autocorrelation is −0.01 — no cadence at all. So the two halves never co-occur.
  Priced and parked. (Note `arc_S3` now reads `maxrun` 33 where the 2026-08-05 posjam scan recorded
  16; the audit-bar split changed walk lengths and with them the stillness runs, so that figure is
  re-based rather than contradicted.)
- **`balloonflip` −7.06 (N=8) — DIAGNOSED: it is the ε-asymmetry balloon, priced.** The cell's
  ceiling is 86.57 at a **1% window** and LRU is 52.07; the reactive arm scores **87.01** (at the
  ceiling) and the machine **78.15**. The trajectory is unambiguous: **34 of 68 samples have main
  earning exactly zero**, and on those the density error is maximally positive, so the window
  balloons 7.3% → 37.3% → 65.9% → **80.2%** in three samples (s20–s23) and holds, then must walk
  all the way home when the regime flips at s29. Mean window 40.8% against an optimum of 1%; the
  probe machinery does not recover it (1 probe, 2 audits, 0 confirms across the run).
  So this is not a new defect — it is the "deliberate prior" this section already describes
  ("a region earning anything takes everything donatable from a region earning exactly nothing"),
  and the trap is *named* for triggering it. What is new is its price against the right baseline.
  The standing text calls the prior "harmless on corda-class traces and bounded by the probe
  machinery", and the measured blackout cost on record is the `ratchet` figure, ~1.2pp per
  5-sample blackout versus a matched control. On the family built to exercise it the cost is
  **8.4pp against both the static ceiling and the hit-rate law** — which the "far above LRU"
  bar cannot see, since both arms clear LRU by ~27. If the ε-asymmetry is ever revisited, this
  is the cell that prices it.
- **The `shieldtrap` family −1.36 / −1.47 / −3.18 (N=8, all three seeds).** Already named as the
  R4-F1 audit-amplification give-back, but the anchor reclassifies it: not a give-back off a win,
  a net loss. **The `parkbound` candidate does not fix it** — its recorded "+1.3..2.0 uniformly
  on all three seeds" fails to reproduce under seeded paired runs (mean −0.11; s7 −1.14, s11
  +0.41, s13 +0.39) and closes none of the 1.37pp the reactive arm leads by. It collapses the
  basin structure to a fixed ~77.6 rather than lifting the level, which is a good trade only
  where hybrid's low draws dominate; on s7 two of eight seeds fall to ~74.5 against hybrid's
  ~79.0. Lead withdrawn — the family has no candidate now, and that is the honest state.

Two hygiene items the sweep surfaced. `moat_h7800` was not measured (its 12M-request instance was
not regenerated), so that row's anchor is blank.

And the two `mixture_d050` sentinels were **stale bars, since re-based**: they read 50.80 and
58.21 against recorded values of 34.7 and 52.9, while the same build reproduced `mixture_d025` at
59.67 exactly. A bar sitting 16pp below current behavior cannot catch the regression it exists
for. The re-base attributes the gain to the blind-corner ordering fix; an independent ablation
run the same day adds a second necessary condition rather than a competing one — the **cold-start
calibration probe**, whose removal (`nocal`, first audit wait 4 → 32) reproduces both recorded
values to within a point (`mixture_d050` 35.47 against 34.7, `_long` 53.22 against 52.9) while
`precrash`, `flatroom` and `staleclaim` all tie the shipped arm. So the escape on this family
needs an early first audit *and* a router that lets a due clock pre-empt the refractory hold;
either ablation alone puts the cell back at ~35. Useful as a diagnostic signature if the row
ever falls again.

**The pattern across the reactive-behind rows: it is the audit layer.** Of the six rows where the
reactive arm leads by more than a point, five are audit-layer cells — `posjam_d0` (the clock's
stillness measure), all three `shieldtrap` seeds (audit-excursion duty), `mixmod_a010` (the F1
audit dose instrument), and `h4c1_reverse` (the crash-ratchet control). The sixth, `balloonflip`,
is the steering prior instead. That concentration is worth holding next to the layer's measured
corpus value of **−0.03pp** ("corpus-neutral insurance").

It does **not** license removing the layer: it is worth +13 against `noaudit` on `moat_h3000`,
+3.3 on the real `cp_w097`, and it is what repaired the F4 sighted-equilibrium family (whisper
55.5 → 64.1). The honest reading is narrower and more useful — the audit layer is simultaneously
the machine's largest constructed-trap win *and* the entirety of its constructed-trap deficit
against the climber it replaced. Any weaning-down exercise should start there, and should be
framed as "which audit behaviours earn their rows" rather than "is the layer worth keeping".

**2026-08-05 (SHIPPED): the audit walk's two interior exits no longer share a bar.** §8's carve-out
is closed by splitting the exits rather than by widening either. The crash abort is unchanged. The
reversal becomes `min(RESTART_THRESHOLD, AUDIT_BAR_FRACTION · max(baseHitRate, noiseBand))`, so
`AUDIT_BAR_FRACTION` now caps one exit and prices the other.

*Why the two cannot share.* The crash abort is a **level** test against the rate frozen at the arm;
the reversal is a **first-difference** test against the previous sample. Priced by the level alone,
the difference test fires at whatever each cell's rate-to-scatter ratio happens to make it: a
median of **0.28σ** on `shieldtrap_s11`, **0.63σ** on `arc_S3`, **5.21σ** on `slowswap_r20` under
the marginal arm. On `shieldtrap_s11` seed 2 the walk dies at s=9 to a **0.17σ** sample against a
bar of 1.17pp, at a 14.4% window; held, that same walk reaches 26% → 32% → 38% with the hit rate
going 0.116 → 0.501 → 0.985 and confirms at 56.4%.

*Measured, N=8 seeded, arms rotated inside each seed.* `shieldtrap` **+0.79 / +1.75 / +1.17**
(the R4-F1 give-back sentinel, and its recorded values move — see §3); 52-row battery **47 of 52
bit-identical** with every deepened mover at 0.00 except that trio (`slowswap_step` −0.01,
`slowswap_ramp` / `saw_p40` / `esc_jam` / `lowmix_s7` exactly 0.00, `posjam_d0` 0.00); the rows
that killed the widening family (`mixnoise_a10`, `crashnoise_a12`, `whisper_mod_a12`) **0.00**;
real corpus 13 cells mean **−0.019** with `arc_P8` and `cp_w097` at 0.00 and one residual,
`cp_w050` **−0.20** at N=8 (ship sd 0.04 → 0.27, four seeds into a lower basin); thin-signal
floors 4 of 5 identical with `arc_S3` **−0.01**.

*The level is not a new constant, and that is what makes it safe to ship.* `AUDIT_BAR_FRACTION ×
VETO_MARGIN_SCALE = 0.15 × 3 = 0.45σ` — the same 15% applied to the larger of the two scales the
machine already tracks. **Do not re-derive it**: the fitted cliff sits at 0.5σ (at 1.0σ `arc_S3`
gives back the full −0.42, i.e. `floorrev`'s loss), so the margin between the derived level and the
cliff is thin and the real safety is the **absolute 5pp cap**, which bounds the noise term from
above and is what keeps this out of the widening family. A reversal bar with **no** base-rate term
is measured dead at both ends (§5).

*The departure, recorded because it is one.* §6 requires a fresh holdout before a constant-level
change ships, and **this shipped without one, on Ben's ratification**. The reasoning: R51's
exercisable-holdout shape tightens at this level to *sub-⅓ hit rate **and** σ > 30% of that rate*
(the 3σ form needed only 5%), no frozen-and-unspent pool is selected for either property, the one
that was is spent, and the corpus is close to exhausted — so the holdout available to freeze could
not have exercised the change, which is the failure mode §6 exists to prevent rather than an
instance of it. What stands in for it: the level is derived from two shipped constants rather than
swept, the change is provably inert wherever the fraction does not bind (the cap returns the
shipped absolute), and the two cells that rejected `audcap2`/`devaudit` at their holdouts
(`arc_P8`, `cp_w097`) are bit-identical. **This is not a precedent for skipping a holdout when one
exists that can exercise the change.**

*What it does not buy.* Not a corpus gain (47 of 52 battery rows and 10 of 13 real cells are
bit-identical, and the corpus mean is slightly negative); the case is one sentinel family plus the
structural argument, against `cp_w050` −0.20. And it does **not** move the marginal-steering
blocker: at this level both `marggate` slowswap rows read 0.00 / −0.01, because that cell's
scatter-to-rate ratio is 0.21, under the 0.30 the derived level needs to bind. §8 item 1 is
unaffected.

**2026-08-06 (adversary round Terra r4 — the density tier attacked at the reviewed commit; no
severe flaw).** Blind attack plan from the source alone, pruned against the record (8 of 12
hypotheses were already-priced decisions), battery reproduced first (48 rows; every
deterministic cell within 0.03pp, every larger deviation on a documented lottery cell, seeded
`shieldtrap` bit-exact on all three seeds). Two negatives are folded into the threads above
(the away-shift stale claim, the rail's two-cause attribution); two findings stand:

- **`Walk.crossesBase` is unreachable for a walk based at the window floor** — the standard
  configuration: the density arm rests at the floor across the mixture/whisper/straywall
  families, `shouldProbeDown` is false for a starved small window, and the audit's
  `chooseDirection` sends every walk armed at or below `2 × floor` up. Two stacking causes:
  `Reading.floor` is a double (163.84 at 8192) while the machine rests at the truncated
  integer one entry *below* it, so a one-stride reversal's raw landing equals the base exactly
  — which the strict `<` refuses — and the floor clamp then commits one entry *above* the
  base. False in all 24 maximum × rung combinations tried; where the floor is integral the
  strict inequality alone refuses it. Observed on `metronome` s120–136 as an undamped
  16-sample oscillation between 2% and 52% of the cache, the crash abort disarmed on that cell
  by the tolerance alternation below, leaving the budget as the only bound — the state §4 says
  must not be relied on. Measured price of the repair (`basecross`: the up-walk's test becomes
  `position <= max(baseWindow, floor)`): mean +0.085 over the 48-row battery, 19 bit-identical,
  39 within ±0.10, `metronome` itself +0.08 — a walk that should end on the return ends at
  budget expiry with the same FAILED pricing and full undo, so only the excursion shortens.
  Every mover above ±0.8 is a documented lottery cell measured unseeded (`slowswap_step`'s
  +3.58 is basin draw — its own baseline swung 40.53 → 37.03 between runs of one binary); the
  seeded N=8 adjudication on those movers is the one measurement a repair still needs.
  **Resolved 2026-08-07 as a latent-invariant pin, not a repair**:
  `WindowClimberTest.walkStep_floorBasedWalk_endsAtBudgetWithAFullUndo` walks the exact shape
  (base at the truncated floor, the refused exact return, the one-above-base rest) and pins
  the budget ending's FAILED pricing and full undo — the legs that keep the dead exit free.
  The `basecross` one-liner plus that seeded check remain the recipe if it is ever repaired.

- **A monotone rate trend makes audits confirm the wrong position — the trend class is not
  cold-start-specific.** Both confirm tests are levels (`aboveStreak` against the arm-frozen
  reference, `beatBase` against the arming sample) and a climb satisfies both without the
  window contributing; adv3's pricing defends scatter (a ramp barely moves the EMA-tracked
  deviation) and `AUDIT_BAR_FRACTION` magnitude, so neither touches a trend. Dose-matched
  instrument `gen_riser.py` (ramp vs flat control: identical request counts and one-shot mass,
  both floor-optimal at a 1% window): the ramp's two audits both confirm and park the window
  near 20%, −0.94/−0.76 vs `noaudit` at rise 24/48; the flat controls' audits confirm zero
  times and the window sits at the floor, −0.35/+0.09. Difference-of-differences −0.59/−0.85.
  This retires "warmup, therefore convergence cost" as the *general* account of the merge
  holdout's M6 (websearch3 @4M, −5.97): the riser's rise is mid-trace on an 83-sample
  instance, so the warmup framing does not carry. The open thread above holds the direction.

**2026-08-08 — a non-convex window response is a real terrain class, and on it the density law's
rest point, not its travel, is the deficit.** Found while re-measuring the Merlin matrix. Sweeping
static windows on 21 cells, six have a trough deeper than 0.5pp on the way from a 1% window to
their optimum; four are real traces (`P3@152508` 2.44pp, `fiu_webmail@195466` 1.28pp,
`P4@2058732` 0.67, `P14@2762991` 0.62) and two are LIRS2 synthetics (`backf@300`, `Zigzag@800`).
None of §7.1's four canonical cells is one: `OLTP@8k` peaks at w50, `corda@1k` plateaus from w20,
`loop@512` peaks at w1, `financial1@32k` peaks exactly at w80. So the class is invisible from the
published curves and was not in the release table's warmup / overshoot / oscillation / pinned
taxonomy, which classifies *trajectories* where this classifies *terrain*.

`marginal.py` settles whether the shape reaches the controller, and it does. The average law's
zero crossing sits in the wrong basin:

| cell | peak | rest (avg) | rest error | dynamics | rest (marginal) | loss |
|---|--:|--:|--:|--:|--:|--:|
| `OLTP@8192` (control) | w50 | **w48.1** | **0.00** | — | w62.2 | 0.25 |
| `P3@152508` | w80 | **w1.4** | **3.59** | +1.18 | never crosses | — |
| `fiu_webmail@195466` | w90 | **w6.9** | **4.55** | −0.53 | **w75.3** | **0.43** |
| `P14@2762991` | w95 | w36.2 | 2.43 | +1.98 | w53.7 | 2.36 |

The control is what makes the rest trustworthy: on a unimodal cell the average law rests 1.9pp of
window from the peak for **zero** hit-rate loss. On the two deep troughs it rests at a 1.4% and a
6.9% window against 80% and 90% optima, and the rest error is **75% and more than 100%** of the
whole deficit — `fiu_webmail`'s running climber is 0.53pp *above* the static value at its own rest
point. This corrects "the climber holds a small window because it has not travelled": on this
class it holds a small window because that is where its steering law rests. `P14`, whose trough is
mild, is the split case at 2.43 rest against 1.98 dynamics.

**§8 item 1 is the named remedy and these are its first real-trace cells with a visible
mechanism.** The marginal form takes `fiu_webmail` from 4.55 to **0.43**, and on `P3` never
crosses, i.e. is driven to the structural ceiling — which on that cell is the optimum. It does
**not** fix `P14` (2.43 → 2.36) and costs 0.25 on the control. Treat that as evidence for the
signal, not for shipping: the item's blocker was never the prize.

**Corpus scale (all 37 cells Merlin wins on the object-count matrix, static sweep at the shipped
80/20 split).** Classifying each by whether a *fixed* window could have won it — the 2026-07-21
diagnostic, three ways instead of two:

| class | cells | meaning |
|---|--:|---|
| structural | **2** | best fixed window still loses by >2pp; a real W-TinyLFU limit |
| ceiling-bound | **0** | none is lost because the window cannot grow far enough |
| steering | **35** | a *reachable* fixed window ties or beats Merlin, and the climber misses it |

**98.8 pp of the 119.1 pp conceded — 83% — is recoverable inside the reachable window range.**
Mean recoverable per steering cell 2.82 against a mean margin of 3.25. The two structural cells are
both fiu NFS, and on `fiu_ikki@28447` the climber (36.79) already beats every static window (36.47)
while Merlin is 2.6 ahead: that one is fairly lost. Eight steering cells do peak past the ceiling,
but a reachable window already clears Merlin on each, so **raising the ceiling wins none of them**.
Terrain is concentrated: 27 of 35 are ARC block I/O, the family with flat (2–6 pp spread) and
sometimes non-convex window responses.

So the July verdict holds in shape — the losses are the controller's, not W-TinyLFU's — but the
mechanism inverted. In July the climber *churned* (bold-driver on sub-noise ΔHR); it now *rests*,
deterministically, in the wrong basin. `scripts/classify.py` in the merlin-2026-08 workspace
regenerates the table.

**Do not tune the audit schedule for this class — the layer is inert on it, measured.** The
equilibrium audit exists for exactly this shape (§4: "a sighted false equilibrium — unstarved,
earning enough to be believed, and wrong"), and its cycle costs samples these cells do not have:
`AUDIT_WAIT_FIRST` 4 + `AUDIT_COMMITMENT` 5 + `AUDIT_CONFIRM_STREAK` 4 = 13 for the calibration
probe and `AUDIT_WAIT_INITIAL` 32 + 9 = 41 for any later one, against 2–12 samples on eighteen of
the thirty-five steering cells. That invites the conclusion that the clock is too slow. It is not
the conclusion the ablation supports. Shipped against `caffeine.climber.variant=noaudit`, 24 cells
paired at N=3 (22 density-tier; two of the intended controls turned out to be ≤4096 and have no
audit layer at all):

| | |
|---|--:|
| mean delta, audit − noaudit | **+0.054 pp** |
| median | −0.012 |
| significant at 2 SE | 8 — **4 cost, 4 help** |
| cells moving more than 1 pp | **1 of 22** (`P10@1135908` +1.65) |

The audit **does** fire on short cells — the calibration probe arms with as few as three samples —
and then does nothing: effects are almost all under 1 pp against gaps of 1–6.5 pp, and the mean
flipped sign as cells accumulated (−0.055 at n=16, +0.054 at n=22), which is what noise looks like.
So the layer is neither the cause of this class nor its cure, and shortening its schedule would buy
more of a measured zero while disturbing a constant calibrated on the gate battery, where §8 item 2
priced the layer at +268 pp of gain. Driver: `scripts/noaudit.py`.

**Read these losses by magnitude, not by cell count.** Of 288 object-count cells Merlin leads on
170, but only **one exceeds 6 pp** (`P3@152508`, 6.53) and five sit in 4–6; the byte track has none
above 6 pp, so its 8–8 → 10–7 count slip is an artifact. Landing the climber on its own best
reachable static window takes the worst margin to **2.96** and empties the >4 pp band; on the three
tail cells where the marginal law's rest point was measured the residuals are 1.76, 0.75 and 2.99.

Also recorded: **`AUDIT_CRASH_PERSISTENCE`'s tolerance is one-shot by construction** — any
non-crash ending, including the tolerant retry's own budget expiry, retires the crash streak.
`metronome` runs crash → fail → crash → fail for its whole trace (four audits, zero confirms,
the ladder ratcheting to rung 64 / wait 128 while nothing is learned); the streak legitimately
measures *consecutive* crashes, and the one-shot form was ratified as designed 2026-08-07
(arm `crashsticky` exists, unpriced; the rule entry carries the don't-fix). And a scope note: real workloads DO reach the deep
*audit* rungs (5 of 12 real cells at rung 64) — §4.3's unreachability sentence is about the
starvation ladder and now says so.

**The planting gate now spans a probe's undo drain (2026-08-09; adversarial-audit finding 1).**
`Anchor.track`'s `settled` was fed `walk != null`, but a capped retreat drains across later
samples with the walk already ended, and `beginReturn` sits *after* `hasPendingUndo()` in the
router, so `returning` is false throughout. Both planting branches therefore fired at whatever
transient position the retreat was passing through. `isProbing()` closes it; `resync` is
untouched, since it never reads the gate.

Measured before believing the severity, because the report assigned HIGH on mechanism alone and
declined a number. Across 57 battery cells at seed 7: **7,571 density samples, 74 multi-sample
drains, 3 phantom plants** (`metronome`, `slowswap_ramp`, `slowswap_step`). Seeded 1–8 and paired
on exactly those three cells, base and fix are **bit-identical**, trajectory statistics included —
the phantom claim is re-synced or re-planted before anything downstream acts on it. Full battery
unseeded: mean +0.154, max loss −0.34, and the three apparent ≥1pp gains (widepin, phases_d050,
crashnoise_a12) are **basin draws that vanish under seeding**. So this is a contract fix, not a
prize; don't re-open it looking for one.

Two claims from that report are refuted here and should not be re-derived. **The guard rail is not
uncovered**: it fires 4 times across 57 cells at one seed, consistent with the attribution entry
above. The report's "zero vetoes across the entire `/climber-gate` battery" measured
`WindowClimberGateTest`'s three-cell JUnit subset and labelled it the battery. The rail's real gap
is the one already named above — the shallow moat doses where it is the sole recovery mechanism
are journaled, not rowed.

**The up-probe verdict is freed of the sample's length (2026-08-09; adversarial-audit finding 2).**
`Walk.verdictSignal`'s down branch takes `Reading.error()`, both densities from one sample, so the
sample's length divides out. Its up branch divided a live window density by the probation density
frozen at the arm — a different sample — and a density is a hit count over a capacity, so nothing
cancelled. The walk now freezes `baseRequestCount` beside the baseline and re-expresses it at the
live sample's length. Deliberately scaling the frozen baseline rather than converting both sides to
per-request rates: `DENSITY_EPSILON`, `steeringError`'s region floors and `error()` are all
calibrated in hits-per-entry, and normalising globally would move the epsilon's weight on the
steering path, which is far wider than the finding.

**It cannot bite on an unweighted cache, and the battery says so.** The period there is
`min(4 x maximum, 10 x maximum)` = a constant `4 x maximum`. Instrumented over 57 cells at seed 7:
**81 up-probe adjudications on 30 cells, zero verdict flips**, every live-to-arming length ratio
inside 0.9998-1.0004. The residual is drain overshoot, and it is inert because `verdictSignal`
feeds a *sign* test, so a 0.0004-nat perturbation only matters to a verdict already that close to
zero. Consistent with the seeded battery: the five unseeded movers (moat_h7800 -1.86,
phases_d050@32k +4.75, widepin, crashnoise_a12, phases_d050) are **bit-identical at all 8 seeds**,
so they were admission-lottery draws, and `moat_h7800`'s seeded 45.28 re-derives the recorded value.

The bias needs a **weighted** cache, where `samplePeriod` takes the sketch's entry-denominated
sample and therefore moves with the resident count. Two constructed weighted cells failed to
witness it and the reasons are worth keeping: one pinned `sketchSampleSize` at `MIN_SKETCH_SIZE`
(resident count ~200, so the period never moved) and one armed no starvation probe at all. A
witnessing cell needs a resident count that both **exceeds 256** and **moves**, on terrain that
produces blind corners. Unbuilt; the mechanism rests on
`WindowClimberTest.probeEnding_upProbeVerdict_isFreeOfTheSampleLength`, which fails without the fix.

**Hostile initial windows: the descent had no coverage at scale (2026-08-12).** Every gate row and
every real-corpus cell starts the cache where the product starts it, at a 1% window. On the
frequency-favorable traces that is already the static optimum, so those rows pass without the
climber having to move, and a machine that could not *descend* would read clean on the whole real
corpus. `-Dcaffeine.climber.startwin=<frac>` (harness) plants the window instead. Eleven cells were
screened frequency-favorable by anchors alone; ten were run, all with a 1–2% static optimum: ARC S3
at 25k–800k, DS1 at 1M–8M, MergeS at 256k and cloud-physics w050 at 123038.

**The descent is the density law, unobstructed, at 4.0–6.3pp of the maximum per sample.**
Recomputing `|error| × 0.03 × maximum` from the debug counters and comparing against the next
sample's motion, `s3_100k`@80% is seventeen consecutive `steer` samples with prediction and
observation agreeing to a rounding, carrying the window `.80 .79 .76 .72 … .09 .04`. The log-ratio
error saturates near 1.5–2 nats even at a badly wrong window, so the step never approaches the 30%
cap. **A full walk down from 80% therefore costs 13–16 samples ≈ 52–63 × the maximum in requests.**

**Recovery is a function of that sample budget and of nothing else.** Recovered fraction of the
plant's handicap at 80%: 164 samples washes it out, 41 → 0.73, 35 → 0.99, 20 → 0.63, 10 →
0.32/0.35, 5 → 0.05, 2 → 0.02, 1 → 0.02. On `ds1_4M` an 80% plant ends at an **80.1%** window,
because two samples is two commands. Replaying each trace 4× is the control: `s3_400k`@80% goes
−16.05 → **−3.63** and lands at the floor, `ds1_1M`@80% −5.75 → −2.13. Four times the decisions, a
third to an eighth of the deficit.

**So the gap was the scaling, not the mechanism.** The mechanism has coverage at 8192 —
`slowswap`'s phase 1 is exactly a descent from a wide window — but descent cost in requests is
proportional to the maximum, and every large-maximum cell in `real.py` and `floors.py` starts at
1% on a trace whose optimum is 1%. Those rows report the start, not the controller. Nor is this a
cold-start hazard: `setMaximum` recomputes the window to 1%, and it is the climber that puts the
window high (`s3_25k` reaches 76% from the shipped start). What the plant prices is the walk back
after a regime change, which is ~60M requests for a 1M-entry cache and 500k for an 8192-entry one.

**Against the reactive law the density tier replaced, the density tier is the better descender.**
Paired arms rotated inside the seed: reactive holds the higher level at the shipped start on these
cells (`ds1_1M` −0.72, `s3_100k` −0.95, `s3_400k` −1.29, the recency give-back inside its recorded
band), and the arms cross between a 20% and a 40% plant. At 80%, `ds1_1M` 8.45 against 6.78 and
`s3_100k` 9.20 against 8.38, recovering 0.33/0.13 and 0.73/0.52. Mechanically expected: the
reactive period is `10 × maximum` against density's `4 ×`, and its step is a fixed decaying 6.25%
rather than one proportional to how wrong the position is.

**One cell is not the sample budget, and it is the reason to keep this instrument:
`cp_w050`@123038 has a path-dependent rest point.** It holds 54 samples, so it is not starved, and
its default arm reproduces the recorded `floors.py` 48.51 exactly. Over 8 admission seeds per arm:
the shipped 1% start settles at a 20.5–21.2% window for **48.51** (48.48–48.55), a **30% plant
settles at 14.7–14.9% for 53.16** (53.14–53.21), and 40%/50% plants settle at 55–64% for ~42.
Static ceiling 54.80 @2%. **A 30% start is worth +4.65pp over the shipped one on the same trace**,
landing 1.64pp off the ceiling where the shipped start lands 6.29pp off.

The trajectories name the mechanism. From 1% the **first** command leaves the optimum: sample 0
reads error +3.53, since at a 1% window the window is trivially the denser region, and the law
commands +10.59pp in one step; the run then oscillates 20–32% for the whole trace, and its two
audits each **crash after a single walk sample** and undo, both on a low-traffic sample (4,120
window hits against a normal 60,000). From the 30% plant a down-audit instead runs four clean
6.25pp steps from 33% to 8%, **confirms at a 2% window**, and parks there for 32 samples. So the
descent machinery is fine here and the ascent out of the shipped start is not, with the audit layer
that should catch it crashing on the trace's own phase structure. One cell of ten, reproducible in
both directions, not traced to a defect: the question to settle is whether a single-sample audit
crash on a low-traffic sample is the general shape, since `AUDIT_BAR_FRACTION` floors the bar at a
fraction of the rate frozen at the arm and a sample with 15x fewer requests is not the case that
pricing was built for.

One further observation recorded rather than raised: a **failed probe undoes a correct descent in
one capped move**. On `s3_25k`@80% a down-probe walks 30pp the right way, ends FAILED, and `undo`
restores the whole plant; 171pp of 246pp of descent work goes that way. Full undo on failure is
deliberate and the cell absorbs it (1.79 against the default start's 1.63), and the shorter cells
never arm a probe at all (`undone` 0.0).

`ds1_1M` shows the milder, non-bistable version of the rest-point error at 1.63pp, resting at
13–22% against a 2% optimum. That is §8's average-vs-marginal error on a real cell, in the opposite
direction from `P3` and `fiu_webmail`, which rest below their peaks.

**2026-08-15 (the audit sweep's climber rows, worked with Ben into one commit).** Three fixes and
two declines from the consolidated Sol backlog's §4. Fixed: `demoteFromMainProtected` re-arms
maintenance when it exits at its transfer budget, as `evictFromWindow` already did, so a
`setMaximum` shrink no longer leaves protected oversized on an idle cache (pre-existing since the
2019 adaptive commit; the 10k→8k witness sat at 6,920/6,336 with the drain status idle); the crash
streak saturates at `PROBE_CRASH_ESCALATION` through `Ladder.crash()`, since the ledger only
distinguishes none, one, and a run, and the invariants pin that domain; and the undo ledger is
integral, charged with each return command as published rather than with the fractional capped
stride, so a capped return lands on the base. That last one is not cosmetic at ordinary sizes: at
8,192 the cap is 2,457.6, the pinned 5,000 return closed at 4,998, and at a permanently starved
corner, where every deep-rung probe fails and undoes, each cycle re-based the probe 1–2 entries
toward the probed direction, a slow creep toward the corner boundary that battery-length traces
(under 40 cycles) cannot show. Declined: the walk's crossing predicate reading the continuous
command (an actuator landing exactly on base fails one sample earlier than an integer predicate
would, the same ending), and the 2% floor's rounding band at 2^53 weight units and above (see
`design-decisions.md`, "The climber commands in `double`"). Battery for the ledger change against
the 2026-08-09 sweep: 61 cells, mean −0.09, median 0.00; the three cells beyond ±1pp
(`phases_d050` −4.99, `crashnoise_a12` −1.42, `phases_d050@32k` +1.11) are bit-identical to the
pre-change tree on seeds 1–8, so they are basin draws. Fuzzer 770,372 runs clean.

Two cells the LIRS family study (2026-08-13) turned up were adjudicated the same day and rowed as
frontier sentinels in `/climber-gate` (`gen_norank.py`; seeded records in its table) rather than
chased. `rep_r6_w4096`@8192 references every key exactly six times at a reuse distance of 4,096, so
the sketch cannot rank and main fills with retired keys whose counts shield them; window hits
appear only once window ÷ miss rate covers the reuse distance (about 8% of the cache at the good
regime's 1/6 miss rate, about 45% while main is that graveyard), so the starved corner is a
self-consistent trap that only a large walk escapes. Seeds 1–8 score 65.6 / 69.1 / 41.1 / 15.9 /
61.7 / 57.6 / 68.0 / 72.9 against an LRU-equal ceiling of 83.29 at a 50% window: not two basins
but a spread of escape times over 91 samples, every seed reaching the 80% top corner, the slow one
held 31 samples by an audit that confirmed and parked on a starved position. Steady state at the
top is ~75%, since the corner arms a down-probe every refractory cycle and each crash-aborts.
`flood_j100`@8192 (a hot set in rotation plus throwaway pairs at spacing 4) is the average-vs-
marginal rest-point error of §8 with a clean instrument: density steers to 45%+ by sample 7 on
pairs that are dense per slot and worth nothing at the margin, an audit walks down, confirms and
parks, and density walks it back up when the park expires; seeds score 49.4–56.0 against a 65.39
ceiling at a 1% window. Both belong to §8's "recovery from a depressed window" thread.

**2026-08-15 (`/audit-regret` round 1, the skill's first run; report in the fable-5 audit tree).**
Two proposal lanes (one blind to §3/§5, one sighted), sixteen specs, one new family and two dose
notes. **`shallowmoat`** (§3, gate row): a hit-rate valley too shallow to trip any crash bar (2pp)
and too wide for any first-round walk (57% of the cache), with a 19pp band behind it. From the 1%
start every arm fails it (hybrid 27.81 ± 0.57, reactive 29.82 ± 4.05, noaudit 27.45 ± 0.87 at
N=8, against a 42.03 ceiling and LRU 39.40) and the same product planted at 70% holds 42.04, so
the deficit is reach, not the law's rest point. The mechanism was predicted independently by
both lanes before any run and is the sentence in §4 that a starvation confirm hands to a density
arm "which agrees with it": on this terrain the up-probe's verdict (window density against the
probation density frozen at the arm) and the steering law (window density against main's
average) disagree at every mid-depth position, so a first-round walk confirms at 4×bar hits three
or four strides out, `Ladder.reward` resets the rung to 1, and the density arm walks the window
home in the same sample; the ladder alternates 1↔2 forever, the commitment depth stays 0, and
the blind-corner branch outranks the due audit clock in the router, so the audit layer never
arms (the `noaudit` dumps are byte-identical on those seeds). At 127 decisions two of six seeds
are still in that cycle. The other basin is the recorded cold-start misconfirm: the calibration
audit confirms on the trace's own warmup trend at 32%, one stride short of the cliff, parks 32
samples, and escapes only at 78–89 samples through a later audit whose doubled stride crosses,
which is the ladder-reset mechanism seen from the other side. Neighborhood 17 of 18 cells at
12–17pp across band distance, scan share and 16384; a spread of reuse distances (three or five
bands, a ramp instead of a step) keeps the gap at N=8 (26.80 / 28.96) and adds §8 item 1's
rest-point give-back from above (planted at 70%: 34.92 / 37.67), so the delta witness is the
clean instrument for reach and the ramp is its composition with class 1. The scan share is a
level shift with a seed-lottery band (0.125–0.15: per seed either ~30 trapped or ~43 crossed,
depending on whether the audit's confirm streak completes before or after the crossing stride)
and a firm trap from 0.20. Nearest recorded families and the difference: the moat (a valley
deeper than the bar; every walk crashes; here none does), straywall2/mixture_d050/rep_r6 (the
mid-depth adjudications fail and the ladder escalates; here they confirm and reset it), the r3
blind-corner lockout (the trigger), regimeramp/flatctl (the misconfirm basin, composed with a
cliff). Two dose notes, not families: **`s_flashpark`**, a 14-sample recency opening then a
steady core, where the calibration audit confirms at 41% two strides past the capture point, the
regime ends one sample later announced by a rise (+10.6pp at the parked window; the fresh-park
shield holds), and the audit layer nets −3.81 against `noaudit` (−3.47..−4.00 across seeds; it
earns +1.89 on the walk and loses 5.89 on the park and the tail), the largest measured cost of
that layer, against `balloonflip`'s −2.39 in §8 item 2 (the shieldtrap shape at a rising dose,
break-even dwell ~4–5 samples against the shipped 32); and **`s_scarburst`**, a loop plus scan base
with a k=6 short-reuse burst three samples in every ten, where the reactive law beats the machine
by 6.96 (57.36 against 50.40 at N=8; `noaudit` 43.56 pinned at a sighted floor by the keepalive
rider) because the audit's down-walk horizon (5–6 samples) is shorter than the phase period (10)
and the density arm chases the phases without damping after the exit; the margin is grid-
sensitive (4.72 with the trace start shifted a quarter sample, 2.82 at a half), so it is a
candidate reactive-anchor sentinel with that caveat, not a family. No tier discontinuity on
either straddle pair. One instrument observation for the anchors: five blind cells beat the
static reference ceiling by 2–3.9pp, one of them (`b_scarburn`, a large flat zipf plus scan) on
a reference curve flat within 0.5pp, where the product's parked position earned ~4pp more than
the reference at the same window; the two implementations differ on that terrain and small gaps
on flat-zipf synthetics should not be read to the half-point until it is understood.

**2026-08-15 (the wedge: a starvation confirm the density arm reverses now deepens the ladder;
worked in the local climber-shallowmoat workspace).** The repair of `shallowmoat`'s reach half,
and the census that reframed it. A wedge is a starvation confirm whose density steer in the same
sample opposes the walk: the verdict (window against the probation density frozen at the arm)
says keep, the steering law (window against main's average) says go home, and ship rewarded it,
resetting the rung to 1 and re-arming as soon as the corner was blind again. Over the 600
battery-plus-corpus trajectory dumps of the depressed-window census, **668 of 881 starvation
confirms are wedges** (bandtrap2 293 of 293, `arc_ConCat` 191 of 260, deadphase 40 of 40,
strad_p8@4097 32 of 32, widepin 25 of 38, trickle_s7 20 of 27, phases_d050 17 of 29, trickle_s11
16 of 24; none on demoflood, the moats, straywall2, the mixtures, slowswap or the other corpus
cells), so the wedge is the ordinary ending of a first-round up-probe wherever the window is
sparser than main per entry and denser than a thin probation, and §4's sentence that a starvation
confirm hands to a density arm "which agrees with it" described the minority. The change: a
reversed confirm escalates the ladder as a completed experiment does, keeping the confirm's
handoff and its zero refractory; a kept confirm still rewards.

Seeded, arms rotated inside each seed, at N=8 unless noted: `shallowmoat` wedge seeds 1/7/8
28.50 / 28.38 / 28.49 → 30.66 / 30.02 / 30.61 (the rung-64 walk crosses the cliff at s17; the
basin-B seeds are bit-identical, no wedge occurs there), `bandtrap2` 72.10 → 76.15 (8 of 8),
`trickle_s7` 70.70 → 73.52 (8 of 8), `trickle_s11` 72.46 → 73.40 (8 of 8), `strad_p8@4097` 74.83
→ 76.76 on every seed with @4096 unmoved (the D2 cliff narrows 7.58 → 5.65), `phases_d050@32k`
51.63 → 56.41 (4 up, 4 unchanged), `widepin` 49.58 → 51.32 (7 up, one −5.6, the recorded coin),
`phases_d050` 57.16 → 57.44 (six seeds land at 60–61 where ship read 57–60, two fall to 48; the
N=8 mean clears the row), `deadphase` 49.16 → 49.00 (0 of 8: each dead phase's probe now walks
×2/×4 and the live phase's opening sample lands farther out), `arc_ConCat` 34.95 → 34.94 over
seeds 1–4 (noise), `arc_DS1@1051635` 14.16 → 13.44 (a 10-sample trace whose samples alternate a
near-dead 3% and a live 29%: after the first wedge the ×2 walk armed on a low sample spans two
live samples at a 27–39% window, the v7 shape, a walk armed in a trough with no crash abort), and
`norank_rep_r6` 56.49 → 53.89 (seven seeds bit-identical; seed 3 41.12 → 20.32: a reversed
confirm at rung 4 deepens the ladder to 8, the fail that follows serves 16 samples where the
rewarded ladder served 2, an audit crash re-imposes it, and the ×1 walk that reached the 45%
escape at s50 re-arms at s69), and every other cell run bit-identical at the seeds run (1–8 on
the seeded rows, 1–2 elsewhere: moat_h3000/h4000/h5000,
straywall2 and @16k, mixture_d050 and its long form, mixture_d025, spread3/5, demoflood,
balloonflip, shieldtrap s7/s11/s13, crashnoise_a12, resphase_k1_s7, whisper_mod_p6, mixmod_a010,
blindlock_blind, slowswap_step, arc_P8, arc_S3, cp_w015, cp_w050, cp_w050@123038, arc_S1/S2/S3,
posjam_j50, whisper_quarter). The unseeded battery (`gate.py`, per-cell N) moves the same rows;
its other movers (balloonflip −1.2, shieldtrap_s13 −1.3, crashnoise_a12 +1.4, rep_r6 +9.3) are
unpaired-draw noise, bit-identical seeded. `real.py` at five runs: every cell within ±0.1;
`floors.py`: DS1 14.15 → 13.36, the other four within noise. The DS1, deadphase and rep_r6 costs
were accepted; the guard that removes the first two (`wedgeshift`, §5) is recorded and not
landed. The gate skill's rows and its reactive-anchor column carry the re-bases.

Three record corrections came out of the trajectories. (1) `blindaudit`, an arm that lets a due
audit clock arm at a blind corner ahead of the starvation probe, never fires on any seed: the
round's "the blind-corner branch outranks the due audit clock" read the dump's post-tick `stable`
column; at every blind-corner decision of the cycle the clock read 0–3 against a wait of 4, since
the position never holds still for four samples inside the cycle. Same outcome, no audit; wrong
mechanism. (2) "The density law is directionally right on both sides of the cliff" is false. On
the far side, after a walk from the floor, main holds the protected core and density drifts the
window down and off the cliff within ~15 samples (two trajectories); the guard rail never vetoes,
because the fall inflates `rates.deviation` faster than the smoothed rate falls (a 3σ margin of
0.18–0.24 against a shortfall of 0.13); the deep walk's confirm at the top corner is itself a
wedge, so nothing parks. The report's plant@70% "hold" was one audit park inside a 64-sample
horizon: density had drifted the planted window from 5,989 toward the cliff (−60 → −15 per
sample) when an up-audit confirmed at 6,570 and parked. (3) The row's fix bar therefore splits:
"the window crossing 58%" is met; "the mean rising toward 42" needs the retention half. Measured
for that half in the workspace, on top of the wedge pricing: a deepest-rung wedge that satisfies
the audit's own confirm test parks as an audit does (31.4 on the row; the calibration audit arms
down from the park one sample later, crashes at the cliff, and its crash stands the park down,
the depressed-window thread's C1), and with that thread's `auditshield` **36.4 on the row and
37.1 at repeat 2**, the residual being the top corner's down-audit crashing at the cliff every
16 → 32 → 64 samples and the undo's arrival read as a shift (C2). So the full repair is this
change plus the sibling thread's park retention, and it composes with §8 item 1's far-side rest
point rather than replacing it.

## 7.1 Release readiness (measured 2026-08-05; the whole battery anchored)

Every gate row now has an LRU and a static-ceiling anchor
(`gate.py` cells + `run.py --anchors`), so "is the machine ever worse than doing nothing" is a
table rather than an impression. Across 52 rows the mean margin over LRU is **+0.88**, the mean gap
to the static ceiling is **+4.88**, and **21 of 52 sit within 2pp of the ceiling**.

**19 rows read below LRU, and 18 are below for the reactive arm as well** — those traps are
hard for any adaptive climber, not a density defect, and on most of them the density tier is much
the closer of the two: `zigzag_s7` −4.12 against reactive's −32.23, `straywall2` −2.21 against
−19.93, `trickle_s11` −1.35 against −18.03, `blindlock_blind` −7.71 against −17.91,
`phases_d050`
−15.02 against −25.25, `widepin` −22.22 against −28.09.

**Exactly one row is a density-introduced below-LRU regression: `posjam_d0`**, at −8.84 where the
reactive arm is +0.43. That is the standing sample-aligned jam §3 already carries, and it is the
one row that fails the "never worse than doing nothing" criterion on the machine's own account.

Against the reactive climber over the same 52 rows the machine is better on 35, within 1pp on 9,
1–2pp behind on 4, and more than 2pp behind on **4**: `posjam_d0` −9.27, `balloonflip` −7.94,
`mixmod_a010` −2.44, `shieldtrap_s13` −2.08. Mean +2.71, median +0.87, ahead by more than 5pp on
nine rows and by more than 15 on three. `balloonflip` is the one large deficit that is *not* a
robustness problem: it sits 26.5pp **above** LRU while trailing reactive, which is the ε-asymmetry
balloon behaving as designed.

**Most of the below-LRU deficit is convergence cost, not steady-state quality, and the battery
cannot currently tell them apart.** Splitting each trap's trajectory into thirds and comparing the
resting window against the static ceiling's window separates three causes:

| cell | optimum | window T1 → T2 → T3 | samples | cause |
|---|---|---|---|---|
| `resphase_k1_s7` | 80% | 1.5 → 7.8 → 14.5 | **7** | trace shorter than one settling time |
| `widepin` | 80% | 26.5 → 66.0 → **77.9** | 49 | warmup; converges correctly |
| `trickle_s7` | 30% | 13.9 → 24.7 → **37.3** | 49 | warmup; converges correctly |
| `phases_d050` | 50% | 20.0 → 72.0 → 67.6 | 47 | overshoot |
| `mixture_d050` | 40% | 9.2 → 47.6 → 56.0 | 47 | overshoot |
| `zigzag_s7` | 80% | 36.7 → 70.2 → 49.2 | 39 | oscillation |
| `posjam_d0` | 30% | 3.3 → 3.2 → **3.3** | 122 | **genuinely pinned** |
| `bandtrap2` | 40% | 23.0 → 22.7 → **22.4** | 129 | **genuinely pinned** |

Two of the nine worst rows are pins here, and the 2026-08-06 length study adds a third,
`phases_d050`. `resphase_k1_s7` gives the controller **seven decisions
in the entire trace** — the sample period is `4 × maximum`, and `AUDIT_WAIT_FIRST` is 4 with
the ladder reaching 512, so that trace cannot contain one audit cycle; its −21.20 against LRU
prices the trace's length, not the machine. `widepin`, the largest deficit in the battery, ends at
**77.9% against an 80% optimum** with its per-sample hit rate climbing 41.6% → 68.1%: it converges
correctly and pays the whole deficit getting there.

**A whole-trace mean over 7–49 samples measures warmup and reads as if it measured quality.** That
is the third mis-measurement flavour found on one day, after a bar at the distribution's own mean
and a bar set below LRU. Before any bar is treated as a release criterion, decide per row whether
it is asking about convergence *speed* or steady-state *quality*, and either lengthen the instance
or state the bar over the final third.

**Resolved by measurement (2026-08-06, workspace `gate-length`): 12 of the 15 short rows were
pricing warmup, and several change sign.** Whole-trace against final-third margin over LRU:
`mixture_d050` **−8.35 → +3.75**, `blindlock_blind` **−8.39 → +3.77**, `mixture_d025@32k` −1.39 →
+5.00, `trickle_s7`/`s11` −4.39/−1.35 → +2.43/+1.90, `zigzag_s7` and `lowmix_s7` to parity. Rows
this doc reported as 8pp below LRU are 3–4pp **above** it once converged, so the battery's
below-LRU count was mostly an artifact of instance length.

Two consequences worth carrying. **`mixture_d025`'s "+0.09 margin at the noise floor" is not a
result**: its whole-trace 59.67 *is* its own first third (59.83) and steady state is +3.2, so the
elaborate reasoning that row's bar carries about single-run noise was reasoning about warmup. And
**`phases_d050` is a genuine pin**, −22.05 whole-trace but still **−8.03 at steady state**, which
puts it beside `posjam_d0` and `bandtrap2` rather than beside the warmup group. `resphase_k1_s7`
is still ascending at the end of even a lengthened trace and remains unclassifiable.

**Lengthening can retire a phenomenon rather than resolve it, and `blindlock` is the case.** Its
long companions were generated, measured and dropped: per-third hit rates run blind 51.82 → 61.67
→ 62.13 against sighted 55.83 → 62.26 → 61.74, so in steady state the arms are identical within
noise and the gap **reverses** in favour of the blind arm, which reaches LRU parity from the
parent's −8.26. The lockout is a convergence cost paid inside the first third. The short pair stays
as the instrument, and any long companion must be checked this way before it is added.

**Read trajectories in six blocks, not three.** `mixture_d010_long` profiles 25/15/44/15/43/44%,
an oscillation between the optimum and a high excursion that a three-block average renders as a
smooth upward drift and would have been reported as "drifts away from the optimum". The thirds
classification above is sound for rows that settle, and unreliable for rows that oscillate.

**Only 4 of 15 targets could be lengthened faithfully.** `gen.py`'s `--lengthmult` reaches the
mixture family alone; `phases`/`deadphase`/`widepin` take `--phases`/`--phaselen`, `gen_attacks.py`
hardcodes `resphase`'s multiplier and gives `zigzag`/`trickle`/`lowmix` no length concept, and
`a1_tenant.py` has none. Resolving the rest needs a generator revision, which re-bases the whole
family it touches.

**So the algorithmic distance to done is short and named: `posjam_d0` and `balloonflip`.** What is
further from done is the evidence apparatus, and that matters more, because a bar that cannot
detect a regression makes every future verdict unreliable. Two bars were found mis-set on one day:
`widepin`'s sat at its own distribution mean (a coin flip, now re-derived and seeded), and
**`trickle_s7` passes its recorded "≥66 on every seed" while sitting 4.4pp below LRU at 69.42
against 73.79** — a bar set 7.8pp under the do-nothing baseline cannot fail for the reason it
exists. Re-derive every bar against this anchor table before trusting the gate as a release
criterion, and prefer bars stated as a margin over LRU or a gap to the ceiling rather than as bare
levels.

## 8. Where to go next (2026-08-04, after four concurrent studies closed on one day)

Ordered by expected value, with the reason each is where it is. Read §5 and §7 before starting
any of them; the point of this section is to stop the next session from re-deriving a priority
that has already been argued.

**1. Marginal steering — RE-PRICED 2026-08-05. The signal stands; the belief that a denominator
choice can make it gate-safe is dead, and the item is no longer first by a wide margin.**

The signal is real and is now better characterised than by hit rate alone. On nine real cells at
N=8 the full form at half gain (`marghalf`) is **+0.83 mean with one loss**, its rest-point
tracking is **+0.983** against ship's +0.950, and its level error is **11.3pp against 21.3** — the
average-to-marginal correction seen directly rather than inferred. The earlier headline (+1.22pp
across 13 cells, zero losses, best +6.34, +2.43 on `cp_w097`) was measured at full gain on the
larger corpus set and is not contradicted.

What changed is the price of making it shippable. §5's two-sided entry records the measurement:
four independently-motivated denominators trace **one monotone inverse frontier** between corpus
prize and `slowswap` robustness at ≈16pp per 1pp, gain does not move that frontier, and the
family's ceiling is **37.52 mean / 38.67 best-seed against a ≥40 bar**. Extrapolated, an arm that
just cleared the gate would retain about **+0.24pp** — *below* the density tier's own +0.38pp
corpus edge. The prototype cost is still paid and the band marker still validates (`margavg`
bit-identical to ship, 0 of 393,818 oracle disagreements), but "roughly three times the tier's
whole edge" is no longer the right scale for ranking this.

**That re-price was superseded the same day. A gated arm escapes the frontier, and it is the live
candidate.** The frontier bounds arms that use **one law everywhere**; a gate is off it by
construction. Measured: `marggate` is **+0.690 mean over the nine cells with a single −0.04 loss**
(`marghalf` +0.833 with a −0.23 loss), and the comparison that needs no extrapolation is that dead
`margtail` sits at slowswap **36.92 / +0.43** while `marggate` sits at **36.91 / +0.690** — the
same robustness position for 60% more prize. Its rest-point tracking is the **best of anything
measured, Spearman +1.000** with 12.2pp level error against ship's 21.3.

**The detector is two terms `Reading` already computes, so it needs no plumbing.** Probation holds
what has not earned promotion and is normally the *poorer* region; where `probationDensity`
exceeds `mainDensity` it has been filled with transients by heavy inflow and cannot price the
window. Median `d_prob/d_main` is **1.19 / 1.17 on the failing `slowswap` rows against 0.18 / 0.58
on real cells**. That tests the *signal's* trustworthiness rather than the workload's identity,
which is the right side of the classify-the-signal line.

What it fixes and what it does not: `slowswap_r1` goes from 5.6pp **below** LRU to **38.24±0.18,
+1.45 above** and deterministic; `slowswap_r20` goes from −6.9 to −0.95, still marginally under
LRU and bimodal. Neither row meets the ≥40 bar.

**The residue is recovery, not detection, and that is why the obvious next iteration is wrong.**
Per-seed on `r20` the gate fires 11% of steering samples in the good basin and 9% in the bad one,
and firing rate does not predict the outcome (one seed fires 21% and lands at 28.82, another fires
22% and lands at 37.20). What separates the basins is where the window ends up: **30.5% against
20.0%**. The gate prevents further damage but does not climb back out of damage already done, so
the open problem is recovery from a depressed window, which is the audit layer's territory rather
than the steering law's.

Dead within the gate, do not re-derive: requiring a **run to enter** the fallback is monotonically
worse (streak 2 and 3 both lose to streak 1); an **exit latch** is non-monotone and never lifts
`r20` above LRU by more than 0.00 (1/2/4/8/16 measured); and the **gate ratio** cannot satisfy both
rows, which want opposite settings (1.0 gives r1 +1.45 and r20 −0.95; 0.6 gives r1 −0.62 and r20
+1.01).

**"Marginal steering is only a rest-point correction, so gate it by command size" — analysed and
dead, and the reason is the most useful thing in this section.** The premise looks right: the
average law is fast and smooth with a slightly wrong rest point, the marginal law has the right
rest point but is a far rougher function of the window, so `margrest` applied the marginal law only
where `|steeringError()| <= REST_BAND`. It **solves `slowswap_r20` outright**: 42.20±0.05 against
ship's 42.19, deterministic, the row that defeated every previous arm, and it is markedly less
bimodal everywhere (P9 sd 0.03 against `marghalf`'s 0.18).

**And it destroys the prize.** Nine cells: **+0.133** against `marghalf`'s +0.833 and `marggate`'s
+0.690, with `w098` collapsing from **+4.33 to +0.35**. The mechanism is the point: `w098`'s win
*is* a rest-point correction, but a **large** one, ship resting at a 59% window against a 10%
optimum. The valuable corrections are long relocations, and a long relocation is
**indistinguishable from a transition by command magnitude**. That is why no size-gated rule can
work, and it is the mechanism underneath
the frontier itself: the signal cannot separate "a big move because the rest point is wrong" from
"a big move because the workload changed".

Two structural facts fell out and are worth keeping. The average-law error is **bimodal** — near
zero at rest or large in transit, with almost no mass between, which is why bands 0.5, 0.4, 0.3,
0.25, 0.1 are bit-identical. And on the bad `slowswap_r20` seeds the final third is **park 95 of
103 samples at a 16.9% window** against the good seeds' 55.6%, with one audit armed in 103 samples:
the marginal law digs a hole, the anchor parks in it, and `steerCommand` is never called again. Any
future remedy has to prevent the hole, because nothing that lives in the steering law can climb out
of one.

**External corroboration (2026-08-05).** OSDI'26 "Learning-Augmented Heuristics" (Xia et al.,
S4-FIFO) trains a gradient-boosted tree to pick static S3-FIFO parameters from cache-level features
over 4140 production traces, and reports its feature importance: the three queues' **hit-position
histograms carry 75%**, with all sixteen hand-crafted composites (utility gap, filtering
efficiency, ghost pressure, one-hit ratio, scan intensity, thrashing risk) splitting the rest. A
hit-position histogram over a queue is that region's hit-rate-versus-size curve, so it is a
marginal-value estimator: given the full feature space, their model spends three quarters of its
attention on the signal this item proposes to build. That is corpus-scale evidence for the premise
from outside this project, independent of the Stone–Turek–Wolf derivation.

It also poses one shape question. `d_tail` is a single-bin tail estimator against their 20 bins per
region. The 2026-08-02 anatomy found the tail fraction robust (0.099/0.090/0.107 mean rest-point
loss at δ = 0.1/0.2/0.3), but that was static anatomy rather than the shipped controller, so bin
resolution is what to try if the arm returns ambiguous instead of clean in either direction.
Nothing else in that paper transfers: it makes one prediction per trace and freezes it, which is a
static configuration selector rather than a controller, and its adaptivity motivation compares an
offline per-trace oracle against LeCaR.

Two blockers were measured, and only one is still binding. The stillness coupling — a rougher
error commands ~2× the motion and the audit clock goes dark — is **entirely repaired by halving
the gain** (battery −0.40 → +0.33, 13 cells ≥+1pp, every stillness failure gone; gain 0.25 is
*worse* than 0.5, so it is a threshold on the stable band, not a monotone trade). What remains is
the signal defect gain does not touch: on `slowswap` the **probation denominator stops being
main's margin** under heavy inflow, putting the marginal form's own rest point 14.63pp below the
peak.

**The named answer to that residue, `ln(d_tail/d_main)`, was built and is dead** (2026-08-05; §5
carries the numbers and the mechanism). It halves the prize and fails both `slowswap` bars, and
the static rest-point anatomy that motivated it overstated its dynamic recovery. Do not rebuild
it, and treat the anatomy's rest-point loss as a screen rather than a prediction: it ranked
`margtail` second of three where the controller ranks it third.

**What the residue actually asks for is probation's margin, not its replacement.** "Probation
fills with transients, so main looks marginally rich" is a complaint about measuring probation's
*bulk*; the denominator still has to move with the window, which is exactly what `d_main` does not
do. The untried arm is `ln(d_windowTail / d_probationTail)` — marginal on **both** sides, the same
band machinery pointed at the probation deque. Round 1 measured window-avg-vs-probation-avg (wrong
direction, 1.737) and round 2 measured tail-vs-main-avg (dead); tail-vs-probation-tail is
unmeasured and is the only remaining first-order form that keeps a moving denominator. It is more
than one arm (a second band on another deque), so price it against the alternative of closing the
remedy thread and re-ranking this item on the signal alone.

**Screen any successor on rest-point tracking before spending a battery.** Spearman(meanWindow,
peakWindow) over the nine D3 cells separates arms that hit rate only ranks: ship +0.917,
`marghalf` +0.983, `margtail` +0.367. A candidate that does not beat +0.917 is buying stability
with tracking and will show up as trap wins paid for by corpus losses.

**2. Weaning the audit layer — MEASURED 2026-08-05, and the answer is no. The complexity is
earned.** The third column was run (`gate.py <traces> hybrid,noaudit,reactive`, 52 rows at the
table's per-cell N, arms rotated inside each run). The layer is worth **+4.91 mean / +0.75
median**, helping 31 rows, hurting 16 and inert on 5 — but the mean is not the result. The
asymmetry is: **+268pp of gain across the helped rows against −13pp of cost across the hurt ones,
about 21:1**, and its worst row is `balloonflip` at −2.39.

The gains are structural rather than diffuse. `noaudit` collapses to a shared **~55.5** across the
whole whisper / crashnoise_flat / posjam / h4c1 / whisper_mod family (eleven rows, +8 to +11) and
to 31–35 on `mixture_d025@32k`, `blindlock_sighted` and `mixture_d050` (+15 to +27). Those are one
pin apiece and the layer is what escapes them. The costs are small and land on benign terrain,
which is the R4-F1 shape this doc already flags, now priced.

Two things the run corrected, both from the same cause. **`widepin` and `phases_d050` cannot be
read from a battery mean at all.** Unseeded they showed −6.40 and +1.62; seeded at N=8 with arms
rotated they are **−1.41** (layer ahead on 4 of 8 seeds, identical on 2) and **−0.24** (ahead on
**0 of 8**). Both were basin luck in opposite directions, and the −6.40 would have made `widepin`
the layer's largest single cost. And on both rows the value belongs to the **tier, not the
layer**: density is worth +5.9 and +10.2 over the reactive arm there while the audit layer is
inert to slightly negative. The gate table's "load-bearing evidence for the density tier" rows are
not the audit layer's rows, and a weaning argument must not borrow one for the other.

That decomposition also exposed a broken bar, since re-derived (2026-08-05). The shipped machine
reads `widepin` **49.58 at N=8 seeded** against a recorded 52.4–54.9 range and a "mean ≥50" bar,
with no code change involved. Over 24 unseeded runs the true distribution is **50.63 ± 5.25** with
the basins split **12/24 exactly**, and N=8 batch means of 52.04 / 49.52 / 50.34. So the recorded
range was an unrepresentative high sample (52.4 needs 6+ high draws in 8) and **the bar sat at the
distribution's own mean**, failing a random batch about half the time: any verdict that row
returned was a coin flip. It is now a seeded row like `slowswap`, barred on per-seed drift. The
general lesson is the one this doc's own process note already carries, with a second instance: on a
bimodal family an unseeded mean is not a measurement, and a bar set from one is not a bar.

**3. The stillness measure — arming side only.** Clock side and confirm side are both measured
shut (§7's 2026-08-04 entry: five measure families, the structural conflict between the shipped
1 band and the jam's 2.7, and the confirm-side reframing refuted by `lp20+prov` taking zero parks
and being bit-identical). The only shape left is a measure loose about oscillation that preserves
the shipped arming time on a settling workload. Note this is **no longer on marginal steering's
critical path** — the gain halving covers that coupling — so it is now a standalone thread with a
smaller prize, and it should be sequenced after (1).

**4. Keeping a found position past a cliff (`shallowmoat`'s retention half, 2026-08-15).** The
reach half is done: a confirm the density arm reverses deepens the ladder (§4.5, §7's wedge
entry), and the wedge seeds cross the cliff at s17. What remains is that the found position is
not kept. After a walk from the floor, density's rest point on the far side is at or below the
cliff (main is the protected core), so density walks the window back over the edge within ~15
samples; the guard rail cannot catch a fall whose own deviation prices its margin; and the deep
walk's confirm at the top is itself reversed, so it never parks. The measured shape of the repair
(workspace arms, not landed): a deepest-rung wedge that satisfies the audit's own confirm test
parks as an audit does, and the park must survive its own audits' crash at the cliff and their
undo's arrival (the depressed-window thread's C1 `auditshield` and C2 `arrive`); with both, the
row reads 36.4 and repeat 2 reads 37.1 against 42, the residual being the top corner's periodic
down-audit crashing at the cliff. Two things belong to other items: the far-side rest point is
item 1's average-vs-marginal error seen from a cliff, and the park's fragility is the sibling
thread's. The ramp variants add item 1's give-back from above (5–7pp), so a reach-plus-park
repair does not close them either. Below item 1 in expected value; above item 3. The basin-B
seeds (the calibration audit's trend misconfirm at 32%, class 9, `regimeramp`'s sentinel) are the
open trend thread, not this item.

**Do not reopen** (each has a measured negative with a mechanism): a hysteresis band on the
reactive reversal (§5); `parkbound` on `shieldtrap`, absent a mechanism for its s7 tail; any
any **widening** of the audit crash bar — every dead candidate (`devaudit`, `audcap`,
`audcap2`, `audref`, `escbar`, `escbar2`, `crash2`/`crash3`) made it more tolerant so walks
survive longer, and each was holdout- or mixnoise-fatal; see the carve-out below, which is the
opposite direction and is *not* covered by that kill; the `posjam_d0` jam as a target in its own
right — 0 of 14 real cells show its signature, and `margraw` incidentally fixes it (56.07 →
67.03) if marginal steering ever ships; `ln(d_w/d_prob)`, the free half, which is the wrong half;
a fallback-to-average trigger, which is a net cost.

**One carve-out on the bar, scoped so it is not an invitation.** **CLOSED 2026-08-05 by splitting
the two exits — see §7.** The record below is what the carve-out said before it was worked, kept
because the shape it describes is the one a future proposal will resemble. What it got wrong is
that it read the problem as one bar mis-calibrated, when it was two tests sharing a bar; the floor
it prescribes (`max(fraction, 3·dev)` on both exits) is `floorrev`/`revfl300`, measured and
rejected at `arc_S3` −0.45. The 2026-08-04 holdout leaves one
shape untested, directionally opposite to everything killed above. Where `AUDIT_BAR_FRACTION`
binds, the audit's bar measures only **0.19–0.26 deviations** while the *probe* branch of the same
function is ≥3 deviations floored at 5pp: **neither branch is noise-calibrated, in opposite
directions**. On that family the shipped bar **crash-aborts 12 of 18 armed audits**, reaching a
confirm twice against the no-floor arm's four — a hair trigger rather than a damage detector,
harmless there only because the audit layer was not earning on that terrain. A deviation *floor*
under the bar, keeping the absolute 5pp cap, would make it narrower-but-not-below-the-noise, which
no dead candidate did.

**Do not build it speculatively.** Its expected value is low: the constant passed its holdout and
the level is inert off-corpus (Δ0.25 +0.005, Δ0.10 −0.002 across 8 cells), and the exposure needs a
workload with *all three* of a sub-⅓ hit rate, down-marching recoverable dips, and an audit layer
that is actually earning — nothing measured has all three, though that none exists is not
established. Pick it up when such a cell appears, and measure it against the rows that killed the
widening family (`mixnoise_a10`, `crashnoise_a12`, `whisper_mod_a12`) plus a fresh holdout: a floor
and a cap share one function, so a mistake there reads as the family that is already dead.

**One process note.** Three of the four defects the 2026-08-04 anchor round found were failures
of *measurement*, not of code: a bar referenced to LRU with 27pp of slack (`balloonflip`), an
unseeded mean on a bimodal family (`parkbound`, which would have shipped a −4.6 tail), and a
sentinel never re-run after a fix landed (`mixture_d050`, 16pp stale). The adversary rounds are
still finding real defects, but their recent yield is increasingly constructed-only; a round spent
re-verifying existing bars against the reactive arm is now competitive with a round spent
attacking the machine.
