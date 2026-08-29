# Design Decisions

Non-obvious choices that are intentional, not bugs. If you're tempted to "fix" any of
these, stop — they're load-bearing.

## Eviction

**Weight=0 is a pinning feature.** Entries with weight=0 are skipped during eviction
(`evictFromWindow`, `evictFromMain`, `evictEntry`). This is an intentional user-facing
API (inherited from ConcurrentLinkedHashMap where weight was >=1, Guava's Cache where >= 0).
Used internally for in-flight async futures.

*The re-scan cost is accepted.* `evictFromWindow` restarts at the deque head each cycle and
never relocates a zero-weight node, and `evictFromMain` skips its victim inline, so cold
pinned entries at the LRU end are re-traversed whenever the region is over budget — O(pinned)
per cycle in the worst case. A dedicated zero-weight queue to hold them out of the scan was
explored and rejected: it made the eviction paths messier for little value. Don't propose it
again without a measurement showing the scan actually costs something.

**Region transfers are budgeted per maintenance cycle and re-armed.** `evictFromWindow` and
`demoteFromMainProtected` move at most `QUEUE_TRANSFER_THRESHOLD` entries per cycle and set
`PROCESSING_TO_REQUIRED` when a backlog remains, because a `setMaximum` shrink can leave either
region arbitrarily oversized and one cycle would otherwise drain it whole under `evictionLock`.
The climber's `increaseWindow` / `decreaseWindow` carry their remainder in `adjustment` instead
(see the climber entries below). A region briefly over its maximum right after a resize is the
budget, not a leak; the protected demotion lacked the re-arm from the 2019 adaptive commit until
the 2026-08 audit sweep, which is why an idle cache could sit with protected oversized until the
next access. Don't remove either re-arm.

**Transient negative weightedSize is acceptable.** `maximumSize` allows eviction
before/after threshold. Eventual consistency is fine given documented promises.
Weight convergence is guaranteed by the telescoping sum property across all write
buffer task orderings.

**Weights are static after computation.** The weigher is called at creation/update
time. The weight is not recalculated afterward — relative weights don't influence
eviction ordering, only total capacity accounting.

**Two notions of "weighted", and the internal one gates on both.** The `isWeighted` field is
whether the caller configured a weigher, and it is what `Policy.Eviction.isWeighted()` reports.
`BoundedLocalCache.isWeighted()` answers a different question, whether entries may be assigned
*different* weights, and that is what decides whether the frequency sketch can be sized from the
maximum or has to be sized from the live entry count. Neither test alone answers it: an async
cache always wraps its weigher in `AsyncWeigher`, so identity against the singleton weigher says
"varies" even for an unweighted one, while a caller who passes `Weigher.singletonWeigher()`
explicitly sets the field even though every entry weighs the same. The method conjoins them.
Sizing the sketch from the entry count is not free: `ensureCapacity` forgets all counts when it
grows, so filling a `maximumSize(10_000)` async cache allocated the sketch twice (8192 then
16384) and discarded the frequencies gathered over the first half, where the sync equivalent
allocates 16384 once. It also called `data.mappingCount()` on every insertion past half the
maximum. The one case still answered wrongly is an async cache given an explicit
`Weigher.singletonWeigher()`, which needs unwrapping `AsyncWeigher` to reach and costs only that
same extra warmup allocation. Pinned by `BoundedLocalCacheTest.isWeighted_onlyWhenWeightsVary`.

**Slow-adapt tuning of the hit-rate climber (at/below `SLOW_ADAPT_THRESHOLD`, 512
entries).** At that size the window is only a few integer entries — the default 1%-of-max window is so
tiny that the climber's initial shrink is a no-op, locking it into a direction that never flips — and the
per-sample hit-rate signal is noisy. Three coordinated fixes: (1) positive initial step — grow the
window first instead of shrinking; (2) slower step decay (`SLOW_ADAPT_DECAY_RATE`, 0.995
vs 0.98) so the step stays large enough for HR shifts to trip the restart threshold on workload
transitions; (3) min initial step floor (2 entries) so the integer window can still move. The
sample-period growth (proportional to step decay, capped at `SLOW_ADAPT_RATIO_CAP`, 4×)
reduces noise when fine-tuning near the optimum.

**The climber has two algorithmic modes gated by two size thresholds.** `determineAdjustment` runs the
**hit-rate climber** below `DENSITY_THRESHOLD` (4096) and the **density climber** above it;
the hit-rate climber additionally switches to a **slow-adapt tuning** at/below
`SLOW_ADAPT_THRESHOLD` (512). The two thresholds are distinct concerns — 512 is where the
window shrinks to only a few integer entries and a single sample is too noisy to trust (the hit-rate
climber then grows-first, stretches its period, and decays slowly), while 4096 is where the density climber's
within-sample gains begin to exceed the hit-rate climber's. Density is scoped to large caches deliberately: it is **~neutral below ~2048 on
real workloads** (mean Δ +0.12–0.24pp vs reactive) yet, being resident-only, it is *unreliable and
prone to pinning at an extreme* when a region is small — so on the corda+loop phase-shift stress it
regressed the reactive climber by up to −10pp at 513–1024 while adding nothing. Scoping keeps the
reactive climber's small/medium robustness ("no worse than before") and adds density's large-cache wins
(+~125pp across the >4096 cells of the 48-trace set). The gate compares the configured maximum in its
**native units** — weight units when a weigher is present — deliberately: the risky direction (a cache
holding few huge entries routed into density/probes) was attack-tested on the weighted track (wfew:
~200 entries of 25–100MB in a 10GB capacity; the weighted battery scored 14W/0L), while the inverse
misroute (many tiny entries under a small weight bound) lands on the reactive tier, which is safe
everywhere. The entry-vs-weight comparison is not a unit bug. The density climber and its escapes (kickoff,
regret, anneal, wide-start) could not be made robust at small sizes — every symptom-patch traded the
corda trap for a frequency-trace regression, because the density signal is **biased and bistable**: it
measures *average* density, not *marginal* value, so its equilibrium depends on the starting window
(start small → under-value → floor trap; start wide → over-value → frequency traces stuck wide). Only a
marginal/ghost signal removes that, and ghosts were rejected (heavy, don't pay off as caches grow, and
critical caches are large). A size cutoff is the honest fix: minor accepted regressions on large
frequency traces, big wins on large recency workloads, and the small/medium climbers untouched.

Within the large tier, `determineAdjustment` reads the **within-sample hit density** of the two regions —
`hitsInWindow / windowMaximum` versus `hitsInMain / (maximum − windowMaximum)`, where
`sample.windowHits` is a counter incremented on window hits alongside `sample.hits`. The signed
error is `ln(windowDensity / mainDensity)`: positive means the admission window earns more hits per
entry, so capacity is more valuable there and the window grows; negative shrinks it. Two workloads
motivated the switch: (1) a **flat hit-rate curve** — when HR barely changes with window size, the
cross-sample HR gradient is buried under the ±10pp swings a phasey workload imposes, so the reactive
climber churns; the density is computed *inside* one sample, so it is immune to those swings. (2) The
**window→0 cliff** (the corda scan-plus-loop stress trace): the reactive climber could drive the
window to zero and crash the hit rate. The step is **proportional** — `|error| × DENSITY_GAIN
× maximum`, capped at `MAX_STEP_FRACTION × maximum` — so a tiny window on a recency-heavy
workload takes a large step and reaches a large optimum in a few samples, while near the balance point
the step shrinks to zero and the window settles.

**The large tier is starvation-guarded ("the probe machine") — density is never trusted on a starved
sample.** The density signal is resident-only, so a region earning ~nothing in a sample (fewer than
`requestCount >> MIN_SIGNAL_SHIFT` hits, 0.1%) is the signal's blind state: it cannot see
what a different split would earn, and holding a blind position can pin the window at an extreme
forever. This is not hypothetical above the tier threshold — a steady-state mixture (Zipf hot-set
defending the main region, twice-accessed items at a reuse distance between the floor and ~25% of the
maximum) pins the pure density climber at the floor with ~28pp lost below LRU, at any cache size. The
guard: a starved sample at a **blind corner** — the starved region is the *small* one (≤¼ of the
maximum), or the whole sample is dead — launches a **probe**: a bold-driver walk seeded away from the
blind bound at the restart magnitude and **scaled by the refractory rung** (×2 at rung 32, ×4 at
64, capped at the 30% max step — deep rungs once bought committed depth but not reach, so stray
walls calibrated wider than a flat stride were absorbing), reversing only on a
hit-rate drop past the **walk-interior bar**, so plateau crossings persist through workload
jitter. For a starvation probe that bar is priced against the workload's own scatter —
`min(max(5pp, 3·rateDeviationEma), 15pp)` (adv3 2026-08-01: the fixed 5pp aborted blind-corner
escapes inside real per-sample noise, crash-cycling the dosed mixture trap at the floor, healed
+8.2 by the pricing; the 15pp cap re-aborts genuinely damaging walks, without which all-blind
families let walks roam, metronome −0.6) — while an **audit's walk keeps the absolute 5pp in DEPTH**
(pricing the depth lets audits survive to confirm and park more, the R4-F1 amplification dial —
every depth-pricing form measured holdout- or mixnoise-fatal) **and prices persistence in
TIME** (2026-08-02 crash-semantics study): a FIRST audit crash aborts on its first below-bar
sample exactly like a starvation probe, while the RETRY of an equilibrium that already crashed
one audit (`audit.crashStreak ≥ 1`) tolerates `AUDIT_CRASH_PERSISTENCE − 1` = 2 below-bar
samples — holding its committed direction at a decayed stride while the dip is adjudicated,
since letting the unbelieved dip drive the bold-driver reversal converted cheap crashes into
rung-doubling completed failures — and aborts on the third. Two samples of tolerance cross
the terrain valley that a one-sample abort made an absorbing horizon at every rung (the moat,
F1-adv4: healed 41.97 → 44.3, constructed-only per a 0/22 real-corpus scan) and absorb
single-sample exogenous pulses; a sustained collapse still aborts at 5pp, and first aborts
stay cheap everywhere (every-walk tolerance failed `mixture_d025`/`mixmod`'s bars — short
traces pay longer failed excursions). The probe ends four ways: a **crash-abort** (hit rate
fell below the probe's start by the walk-interior bar → undone in full, and the refractory
re-arms at its current length WITHOUT doubling —
an exogenous workload shift is indistinguishable from probe damage here; consecutive crashes
escalate like failures on the walk's OWN ladder — audit crashes on `audit.crashStreak`/
`audit.rung`, starvation crashes on `starvation.crashStreak`/`starvation.rung`, never
each other's: on the shared form three token-preserving pulses paired three lone audit
crashes into rung 64 / clock wait 128, a 130-sample floor pin −6.0 below LRU — Terra H4-C1,
and audit endings alone drove the shared ladder's stride/commitment to their deepest forms —
F2-adv4; a crash, lone or escalated, NEVER takes the audit clock's failure doubling); a
**reversal through the
probe's own start** (a failed experiment: undone in full, ladder doubles); **budget expiry**
(likewise undone in full, ladder doubles); or an **adjudication** once the watched region earns ≥4×
the starvation bar and the walk has met its committed depth (**escalating commitment**: a
first-round probe may adjudicate immediately — stray and transferred hits scale with a region's
size and reach the bar on workloads whose small window is correct, so cheap early exits protect
thin-signal floors — while each adjudicated failure lengthens the ladder, whose deeper rungs
commit the next walk 2 then 10 samples past the stray zone, turning deep silent reuse bands from
absorbing pins into bounded dips). Ledger ownership binds every ending, not just crashes: a
non-crash ending retires only the crash streak of the layer that owns the walk. Both
non-crash sites once cleared both streaks, which disarmed the other layer's escalation and —
since `AUDIT_CRASH_PERSISTENCE` arms at a streak of one — its tolerance as well, so a
starvation probe ending between two audit crashes restored the one-sample abort the moat and
the H4-C1 train need it not to have; the crash-semantics fix was reachable around by an
interleaved blind corner (fixed 2026-08-02, pinned by
`audit_budgetExpiry_leavesTheStarvationLedger` and
`walkStep_reversalThroughBase_leavesTheOtherLayersLedger`). The single sanctioned cross-write
is the audit *confirm*, which clears `starvation.crashStreak` alongside the starvation-ladder reset
it already performs, because a ladder reset to one carrying a live streak re-escalates on the
very next crash. The verdict prices an up-probe at
main's *margin*: it confirms iff the window's density beats the **probation density frozen when
the probe armed** (`ln((windowDensity+ε)/(walk.baseProbationDensity+ε)) > 0`). Capacity claimed
from main squeezes protected into probation and expels probation's coldest, so probation is what
the grow actually taxes; main's *average* is protected-core-dominated and vetoed
genuinely-winning positions (the trickle family sat 14–17pp below its own engine on the old
average confirm). The baseline is frozen at arm because the walk's own demotions enrich live
probation into an absorbing false-veto (demoflood: the live variant pins at the floor with zero
confirms); each re-arm re-snapshots, so a cold-start-transient baseline self-heals. Down-probes
keep the average-density sign test — the window has no marginal substructure to price against.
Confirmation keeps the position (a reuse band was found) and resets the ladder to 1; anything
else fails and is undone in full, ladder doubled. The one priced trade — lowmix, a low-hit-rate
bistable family where the frozen baseline vetoes an LRU-ward escape the diluted average confirms
by seed-luck — is a `/climber-gate` sentinel with no real-trace echo across the defended set.
Until an ending fires, the walk keeps walking. Failed probes back
off exponentially
(`PROBE_BACKOFF_INITIAL` 16 → `_MAX` 64 samples), so workloads whose small window is
genuinely correct (w50/S1-class thin-signal floors) pay one bounded exploration cost — about −1pp on a
short benchmark trace, amortizing to ~0 in production — and then hold still. Three hard-won
asymmetries: a starved *large* region must NOT probe (density can see it; probing "for" a scan-filled
main destroys the one working region — corda); a probe's success must require the verdict to
*confirm* (a merely-neutral verdict lets density walk the window home and the probe refire
endlessly — S3); and the crash veto is evaluated *before* adjudication, so a walk that has
destroyed the hit rate can never reach a confirming verdict (a destroyed region would otherwise
"win" a density ratio by earning against ~nothing — the stress trace's window once collapsed to a
single entry and the ratio called it success; there is deliberately NO separate absolute-HR check
inside the adjudication branch — the crash-first ordering is that veto). Rejected probe-exit variants (a small entry step with absolute exits — stray hits
scale linearly with window size and end probes just short of the band; density-competitive exits with
travel budgets — deep walks damage healthy-main workloads like w50 by −2pp) are recorded with data in
the hill-climber-fable workspace (local-only archive; the re-runnable trap
generators are committed in the `/climber-gate` skill). Extending the guarded tier below 4096 was
measured and rejected
(it is trap-safe there but breaks the historically fragile `cs@563` by −1.3pp). `setMaximumSize`
resets the probe state and the sample baseline (`sample.previousHitRate`), so the first sample of a
new geometry is never judged against a hit rate the old one earned. The below-floor clamp **lifts** a sub-floor window up to the 2% floor — the
initial window is 1% of the maximum, and without the lift it can wedge permanently below the
"signal-capable" floor the design documents.

**The window floor is *signal-capable* (`WINDOW_FLOOR_FRACTION = 2%`, not 0.5%) — a
secondary safeguard within the large tier.** The density signal is resident-only: a window collapsed
too small catches ~no hits, reads `windowDensity ≈ 0`, and would pin at "shrink" forever, unable to
recover when a recency phase arrives. This is the trap the `corda + 5×loop + corda` phase-shift stress
exposes; it is *severe* at small/medium sizes (a 0.5% floor is 3 entries at size 600 → the recency phase
collapsed to ~23% vs 44% optimal), which is the main reason density is scoped to large caches (the
reactive climber owns those sizes and does not trap). At large sizes the same signal is far less
fragile, but the 2% floor remains as a cheap safeguard — it keeps enough entries resident to estimate
density, at ≤0.35pp cost to frequency-optimal workloads. Symptom-patch escapes (a non-density *kickoff*,
an EWMA-*regret* trigger) were built and rejected — they false-fire on variable frequency traces (w50
−8pp) or only transiently nudge before the EWMA catches up and density re-traps. Don't lower the floor
below 2% without re-running the corda+loop stress at 4097–8192, and don't try to make density robust
*below* `DENSITY_THRESHOLD` with escapes — that path was exhausted; the size cutoff is the fix.

**The sketch's shrink retrack and `reset()`'s zero clamp are a matched pair.** `ensureCapacity`
keeps the table (it is grow-only, since reallocating wipes the counts and blacks out admission)
but re-points `sampleSize` at the new maximum, so a shrunken cache does not age on its old size's
cadence; the observed count is clamped to just below the sample to keep the equality reset test
reachable. That retrack breaks the precondition `reset()` was written under: `count/4 < size` held
only while the table matched the sample (`count/4 ≤ 8·maximum < 10·maximum = sampleSize`), so
without a floor at zero the correction underflows to a large negative `size` and no counter is
halved until `++size` climbs back — restoring exactly the old-size cadence the retrack removes,
and doing it for roughly `4 × tableLength` additions across successive resets. Measured
(2026-08-07, audit-arithmetic F1): a 64K-entry cache shrunk to 256 lands at `size = −62,579`,
about 25 sample periods, and recovery is driven only by *novel* traffic — 1M requests against
saturated counters advanced it by 932. Reachable with no `Policy` call: a weighted cache retracks
on every addition, so updating resident entries to heavy weights collapses the entry count
(200,000 → 20 measured) and drives it. During the stall `admit()` degrades to tie-breaking, so a
post-shrink working set cannot displace stale survivors. The clamp is inert whenever the table
matches the maximum, so it costs nothing on the normal path. Don't remove either half. Pinned by
`FrequencySketchTest.ensureCapacity_shrink_denseTable_agesOnSchedule` — note its neighbour
`_shrink_resetReachable` exercises the same flow on an *empty* table, where the correction is 1,
which is why the underflow shipped.

The large-cache **sample period is `SAMPLE_MULTIPLIER × maximum` (4×), decoupled from the
frequency sketch's own 10× reset** (they use separate counters — `sample.hits`/`sample.misses`
gate
the climber, `FrequencySketch.size` gates the sketch reset). At 10× a large cache gets only one to
three adaptations over a finite benchmark trace and never reaches a large (20–50%) optimal window; 4×
gives it enough steps to converge. It is kept at 4× rather than lower because a shorter period makes
the density estimate near a small converged window noisy enough to jitter a frequency-friendly
workload off its optimum — the `cs` trace craters at 2×. The density signal is inherently
recency-biased (it over-values the window), so frequency-optimal traces (`fiu_madmax`, `cs`,
`fiu_webmail`, several ARC traces) give back ~0.5–2pp versus the reactive climber; on every one they
still beat LRU and match/beat Merlin and stay near-optimal, and the trade buys large recency-workload
gains (corda's cliff, `OLTP`/`fiu_ikki`/`metaCDN`/`fiu_homes` converging to their ceiling). An
*adaptive* period (short while moving, long once settled) was tried and **rejected**: starting short
made dup-heavy/phasey traces jitter persistently and never lengthen, cratering `cs` worse than a fixed
2×. Don't reintroduce it.

**Climber `adjustment` is a multi-cycle carry-over, not stale state.** `increaseWindow` /
`decreaseWindow` transfer at most `QUEUE_TRANSFER_THRESHOLD` (e.g. 1000) nodes per maintenance
cycle, then store the *unfulfilled* remainder back into the climber's `adjustment`
(`quota` / `-quota`) — the leftover, not zero. On a large cache the per-decision step
(`≈ 0.0625 × maximum`, e.g. 62,500 at a 1M maximum) dwarfs the (e.g 1000) node cap, so a single
climber decision is deliberately drained across many later cycles. Each of those cycles
`determineAdjustment` early-returns at `requestCount < effectiveSampleSize` (the sample was
reset and has not refilled) **without touching `adjustment`**, and `climb` re-applies the
carried remainder. Once the sample refills, a fresh `determineAdjustment` overwrites
`adjustment` with a new decision. This looks like "a stale adjustment re-applied without a
fresh hit-rate sample," but it is the completion mechanism for a work-capped transfer.

The symmetric give-back after the transfer loop (`mainProtectedMaximum += quota;
windowMaximum -= quota`) keeps the partition sum (`windowMaximum + mainProtectedMaximum +
implicit-probation == maximum`) constant — the maxima track the *partial* transfer that
actually happened (added by "Fix bugs in adaptive policy"). Three consequences
worth not flagging:
- **Pinned leftover.** If the carried `quota` is smaller than the policy weight of every
  candidate (e.g. `quota = 1` while all entries weigh 100), the loop moves nothing and
  re-stores the same value, so the window stays put until a real sample overwrites
  `adjustment`. The window genuinely cannot grow by a fraction of an indivisible heavy entry.
- **Probation is the implicit slack region.** The transfer draws from both probation and
  protected but only decrements `mainProtectedWeightedSize` for protected moves, so probation
  absorbs the difference between the window-maximum shift and the protected weight moved
  (`Δ windowMaximum == total weight transferred` holds exactly).
- **The quota is a soft knob, not an accounting invariant.** `quota` is how much room the
  climber may borrow from the other region for a probabilistic guess about an unknown future;
  taking a little too much or too little is meaningless. A node carrying a transient negative
  `policyWeight` (a same-key `UpdateTask` reordered against its predecessor — see "Two weight
  fields") passes `quota < weight` and *inflates* the quota via `quota -= weight`, so the
  give-back can overshoot and move `windowMaximum` / `mainProtectedMaximum` opposite to the
  commanded direction, or out of `[0, maximum]`. That is not a defect and must not be clamped:
  the partition sum still holds, the region *weighted sizes* stay exact (they debit the same
  snapshot they credited), a negative `windowMaximum` / `mainProtectedMaximum` only makes
  `evictFromWindow` / `demoteFromMainProtected` drain that region, a policy-quality wobble. An
  out-of-range cap walks back only by the weight each later transfer moves: the
  `min(adjustment, donor)`, `<= 1` and `max(0, …)` guards stop a call from pushing it further
  out, they do not pull it back. A swing larger than a cycle's transfer (one key's weight
  swinging by more than the window holds) therefore suspends the split for many cycles, the
  window cap above `maximum` idling `evictFromWindow` while `evictFromMain` still bounds the
  total; the verdict does not turn on the duration. The invariant that matters is
  that `policyWeight` *converges*, so a region's size keeps reflecting the entries inside it;
  how a mid-flight snapshot lands on the quota does not. Clamping the quota also would not
  restore a reservation — it just relocates the inaccuracy from the maxima to the transfer
  volume. Re-derived five times (arithmetic F4 → adversarial-input F1 → adaptivity L1/F1 →
  adaptivity M1, which priced the drain's duration); adjudicated NOT-A-BUG by Ben 2026-07-27.

The hardening companion to this: `ReactiveClimber.samplePeriod` guards the small-cache
`ratio` against a `0/0` NaN (when both the maximum and step size are zero). The NaN would
otherwise zero the effective sample size, defeat the sample guard, and poison
`sample.previousHitRate`. Decay never produces the state (a positive step size never rounds
to exactly `0.0`), but construction does: with `maximumSize(0)` the constructor's
`setMaximumSize(0)` early-returns on `maximum == maximum()` (the field default), leaving
`step.size` at its `0.0` default. The guard is live for that configuration — covered by
`adapt_smallCache_zeroMagnitudeDoesNotPoisonHitRate` — not just defense-in-depth.

**The climber commands in `double`; the cache applies `(long)` of the command, truncated
toward zero once at publication.** Positions, region maxima, and the walk's base are `long`, and
position identity is band-based (`Reading.stableBand`, 2% of the maximum), so a command's lost
fraction is not itself an error. Two consequences follow and are accepted: `walkStep`'s
base-crossing predicate reads the continuous command, so a reversal whose truncated landing is
exactly the base fails the walk one sample earlier than an integer predicate would (the same
ending, one sample cheaper); and the 2% floor is a `double`, so the integer window rests one entry
below it (pinned by `walkStep_floorBasedWalk_endsAtBudgetWithAFullUndo`), and at maxima above
2^53 weight units the floor comparison has a rounding band of a few units, which is unreachable.
Don't make the floor or the predicate integral. **The one ledger that must close is integral:**
`undoRemaining` is a `long` charged with each return command as published, not with the
fractional capped stride. Charged with the fraction it closed short of the base by the cap's
fraction per capped stride (8,192: 2,457 + 2,457 + 84 for a 5,000 return), and at a permanently
starved corner, where every deep-rung probe fails and undoes, that re-based each cycle 1–2 entries
toward the probed direction, a slow creep toward the corner boundary; the sweep audit's row 4.2
(2026-08-15). Pinned by `probeEnding_adjudication_wrongSignFailsAndDoubles` (the commands sum to
the distance).

**Async load completions replace quietly.** A completed future's `handleCompletion` (and the
bulk `fillProxies`) calls `replace(..., quietly= true)`: the UpdateTask finalizes the weight and
expiration but skips `onAccess`'s sketch increment and climber hit counters. The entry already
paid its miss at insertion; counting the completion as an access doubled the key's per-load
admission frequency and window-attributed one synthetic, write-buffer-lossless hit per miss —
measured at up to −38.6pp (w50) on the density climber and −12.7pp (corda+loop stress @ 512) on
the reactive climber (the async-completion-noise workspace report, local-only). A
material quiet update (weight changed, or the write time moved beyond the 1s tolerance) routes
through the UpdateTask and still reorders the deques; an immaterial one (same weight, within
tolerance — the common fast completion) skips policy work entirely, which is sound because the
entry's position and write time are at most tolerance-stale from its insertion. User-initiated
writes remain loud by design. Don't re-add access recording to the completion path, and don't
flag the immaterial-completion skip as a missing reorder/refresh.

The `refreshIfNeeded` completion's remap is quiet the same way (`RemapHints.quietly`, honored at
`remap`'s update dispatch): the triggering read already recorded its access, so a loud reload
completion double-counted every refresh-eligible read into the sketch and the climber's hit
sample (1000 reads → 2000 of each once past the refresh interval). A reload finalization is
bookkeeping, not a usage. Manual `LoadingCache.refresh` / async `tryComputeRefresh` completions
are unchanged (explicit per-call API action, no read-stream amplification); revisit only with a
measured skew. Pinned by `BoundedLocalCacheTest.refreshCompletion_doesNotRecordAccess`.

A committed refresh completion always takes the material branch, so `remap`'s immaterial one is
quiet-capable but unreached in production (a rejected one returns at the `preserveTimestamps`
exit, before the dispatch). `refreshIfNeeded` requires `refreshAfterWrite`, which
makes `exceedsWriteTimeTolerance`'s refresh disjunct true at every completion: either the
configured duration is within the 1s tolerance, or the refresh fired because the entry aged past a
duration longer than the tolerance. The `quietly` guard on that branch is not defense-in-depth, it
is the same contract `replace` implements for the async completions, whose callers do not require
`refreshAfterWrite` and do settle immaterially. Don't delete it as dead code; it is pinned through
the `compute(..., hints)` seam by `BoundedLocalCacheTest.remap_quietly_doesNotRecordAccess` and its
loud twin.

**A reload that reuses a dead entry's node pays the insertion's miss.** When a `computeIfAbsent`
or a `compute` finds the entry expired or its value collected, it loads into the node that is
already linked, so the policy receives an `UpdateTask` where a reaped entry would have produced an
`AddTask`. Crediting that as an ordinary access recorded a climber hit for an operation the
application experienced as a miss, and left the sampled rate turning on whether maintenance
reaped the entry before the reload arrived. The reload is credited `Access.RELOAD` instead: the admission
filter observes the key exactly as an insertion would, and the task records the miss the
reinsertion owed. `Access.QUIET` still wins over it, so a refresh completion that lands on an
expired entry stays bookkeeping.

The level error was not the reason to fix it. A contamination that is steady cancels in every
difference the machine takes, the reactive law's `hitRateChange` and the walk, veto, and anchor
comparisons alike, because all of them subtract one rate from another. What has nothing to
subtract it against is the density tier's within-sample ratio: `error()` and `steeringError()`
divide each region's hits by that region's capacity, so a hit credited to a region moves the log
ratio outright. On a seeded synthetic reuse stream at a maximum of 512, with the reuse gap set to
the expiration duration, the mislabel moved the converged window from 402 entries to 5 on every
seed and at both maintenance lags. Nearly every phantom lands in main, since an entry that
survives to expire is one the main space is holding, so the density law reads main as earning and
steers capacity out of the window. The reload rate is itself a function of the window, so the
error feeds itself: the collapsed window took 13.6% of requests as reloads against the corrected
window's 4.2%. No workload measured here turns that into an end-user hit-rate loss, and finding
one belongs to `/audit-regret` rather than to the repair.

A cache with neither expiration nor reference values cannot reach the branch, so the simulator's
policies, whose `product.Caffeine` configures a maximum size and nothing else, are bit-identical
under it and the gate battery cannot price it. `put` and `putIfAbsent` take the same in-place path
when the entry they land on has expired or lost its value, and are credited the same way: no
user-visible statistic contradicts a hit there, but the reap race that decides between `AddTask`
and `UpdateTask` does not care which API arrived. `replace` needs no credit, since it refuses a
dead entry outright. Pinned by `BoundedLocalCacheTest.expiredReload_recordsClimberMiss`,
`expiredRemap_recordsClimberMiss`, and the `expiredPut_recordsClimberMiss` / `put_recordsClimberHit`
pair, which bracket the write path's condition from both sides. Each asserts the sketch increment
as well, since suppressing the access with `quietly` would drop the increment `AddTask` performs.

**~1% random admission of rejected candidates.** The TinyLFU admission filter
randomly admits ~1% of candidates that would otherwise be rejected. This provides
HashDoS protection by making frequency estimation attacks non-deterministic.

## Expiration

**EXPIRE_TOLERANCE = 1 second.** Expiration is a maximum lifetime,
not a minimum hold time. Like ScheduledExecutorService, the timing is never exact.
The tolerance applies to multiple per-entry timestamps:
- `writeTime` reorder decisions in remap (`exceedsWriteTimeTolerance`) — avoids
  write buffer saturation from rapid timer wheel rescheduling, ~4x throughput on
  write-heavy workloads.
- `accessTime` updates on the read path — avoids cache-line true-sharing on a hot
  entry under `expireAfterAccess`. When the configured duration is `<= tolerance`
  the skip is bypassed so tiny expiration windows still behave exactly.

**ASYNC_EXPIRY = ~220 years** (`Async.java`). Computing futures get this sentinel
duration to prevent expiration during async computation. The `isComputingAsync()`
check tests both the `isAsync` flag AND whether the future is complete.

**The sentinel also records that a load has not been accounted for, so every
read-extension path preserves it.** `AsyncExpiry.expireAfterUpdate` routes to the
user's `expireAfterCreate` when `currentDuration > MAXIMUM_EXPIRY`, which is how
`handleCompletion`'s quiet replace tells a first load from an update. `AsyncExpiry`
cannot make that distinction on a read: it keys off `getIfReady`, so once the value
arrives it delegates and returns a real duration. The guard therefore belongs in the
cache, and both `tryExpireAfterRead` and `expireAfterRead` carry it (`isAsync &&
currentDuration > MAXIMUM_EXPIRY` returns without consulting the `Expiry`). The
window it covers is not the in-flight state, which `AsyncExpiry` handles: it is the
value arriving while the completion waits on the node lock a `putIfAbsent` is
holding. Consuming the sentinel there also hands the user's callback a 220-year
`currentDuration`, and an `Expiry` that returns what it was given, `Expiry.creating`
among them, then pins the entry for that span instead of expiring it after its
configured duration.

**The completion finalizes only what the insertion deferred.** `handleCompletion`
finalizes an entry with a same-instance quiet `replace`, which lands on the update path.
For a future that was in flight when it was inserted that is the entry's first and only
evaluation, since the install stored the sentinel and `AsyncExpiry` routes it back to
`expireAfterCreate`. A future that was **already complete** when inserted was weighed and
dated by that write, so finalizing it again charged the creation as an update: the user's
`expireAfterCreate` ran at the install and their `expireAfterUpdate` a moment later, and
the update's duration is the one the entry kept. Measured with an `Expiry` of 1h on create
and 1m on update, `put(k, completedFuture(v))` and a loader returning a completed future
both produced a 1m entry where the synchronous cache and an in-flight future produced 1h.
So the callers pass whether the insertion deferred its accounting and the completion skips
the replace when it did not. A ready future replacing a live entry likewise takes one
update rather than two. Reachability is not exotic: any loader that can answer without I/O
returns a completed future.

Three things about the shape are load-bearing. **The readiness is read before the store**,
because a future observed as ready before the install was ready when the install evaluated
it, whereas one observed afterwards may have completed in between, and skipping there
leaves the entry holding weight 0 and the sentinel permanently (measured: moving the read
after the `put` stranded 2,950 of 20,000 entries against a completion race, versus 0 of
500,000 with the read where it is). **The decision cannot live in `AsyncExpiry`**: its
`expireAfterUpdate` receives the key, the new value and the current duration, so a
completion's `replace(k, f, f)` and a genuine `put` of an equal ready future are identical
from inside it; the sentinel test only works for the deferred case because the entry still
carries the mark. **And it must not be `AsyncExpiry.expireAfterCreate` deferring every
creation** — the synchronous view's `asMap()` compute family installs completed futures
with no completion handler at all, so deferring strands them at the sentinel and the user's
`expireAfterCreate` is never called (9,013 failures across the narrowed async matrix when
tried).

**MAXIMUM_EXPIRY = ~150 years** (`Long.MAX_VALUE >> 1`). User-provided expiration
durations are clamped to prevent nanoTime arithmetic overflow. `now + ASYNC_EXPIRY`
overflows to negative after ~73 years of JVM uptime, but this is within the
documented assumption that JVM instances don't run for 73+ years continuously.

**accessTime uses opaque write, not CAS.** CAS on every read would cause contention
storms on hot entries. Backward movement only causes benign early expiration, which
is acceptable. Variable time CAS (`casVariableTime`) is justified because
`expireAfterRead` can change duration arbitrarily.

**Read-path expiry extension can briefly resurrect a just-expired entry — accepted.**
A reader that observed an entry live and then extends it (`tryExpireAfterRead`'s
`casVariableTime` for variable expiry, or `setAccessTime` for `expireAfterAccess`) can land
the extension just after the entry crossed its boundary, leaving it visible slightly later than
expiry. This is inherent to lock-free read-extension over lazy expiration: the expired entry
keeps its timestamp until maintenance removes it, so the CAS — which only checks the field is
unchanged — cannot reject an expired entry, and any fresh-clock guard before the write still
races a context switch between the read and the write; only a read-path lock (rejected) would
close it. The window is a few instructions for a normal `Expiry` callback; a wide one requires a
slow `expireAfterRead` (callback misuse, like a slow `Weigher`). So "never visible later than
expiry" is best-effort for read-extension — the over-stay is bounded by one duration and
self-heals on the next maintenance. Don't add a *fresh-clock* re-check guard for this
over-stay — it still races a context switch and can't reject an already-expired entry.
(Distinct from the `node.getValue() == value` value-identity check that `casVariableTime`
*does* carry: that guards a separate, closable bug — a read duration rebinding onto a
*replaced* value — and is load-bearing; keep it.)

**Bulk reads evaluate expiry at a single scan-wide `now`, by design.** `getAllPresent`
(and `containsValue`) read `expirationTicker()` once and reuse that `now` for every
element's `hasExpired`/`setAccessTime`/`tryExpireAfterRead`. This gives the batch
*internal consistency* — every key judged at one instant, a point-in-time snapshot. A key
late in a long scan can therefore be returned present just after a concurrent single-key
`get` (fresh `now`) reported it expired (a LATE-direction over-stay). That's accepted
best-effort: a concurrent single-key read can always disagree with a bulk read on a
boundary entry under lock-free expiration, the over-stay self-heals on the next
maintenance/access, and it's sub-millisecond (inside `EXPIRE_TOLERANCE`) unless a *user*
`Expiry.expireAfterRead` callback is slow (callback misuse). Don't "fix" this by re-reading
the ticker per element — it judges keys of the same call at different instants (a downgrade
of the snapshot) and adds a `nanoTime` read per key on the hot path.

**A hit probes the value future's readiness only where the answer is consumed.** `hasExpired`
is timestamp-only, so a reader probes `isComputingAsync` solely on an expired verdict, and the
successful-read blocks in `getIfPresent` and `computeIfAbsent`'s optimistic hit test
`expiresAfterRead()` (access or variable) before their own probe, since `setAccessTime` and
`tryExpireAfterRead` early-return otherwise. `Async.getIfReady` calls `isDone`,
`isCompletedExceptionally` and `join`, each an acquire load of `CompletableFuture.result` that
the JIT cannot elide or coalesce, so a `maximumSize`-only async hit was paying six of them for
a result no branch could read. Measured on `AsyncGetPutBenchmark.read_only` (M3 Max, JDK 26, 8
threads, 3 forks, ABA): 238–246M ops/s before, 370–384M after, **+53%**; an acquire load is
`ldar` on arm64, so x86 should gain less. The probe count per healthy hit is 0 with no
expiration or with expire-after-write, 1 for expire-after-access and 2 for variable expiry (the
extra one is `AsyncExpiry.expireAfterRead`'s own `getIfReady`), pinned by
`BoundedLocalCacheTest.getIfPresent_readinessProbes`. Don't cache the first probe's answer to
save the second: a future can complete, or be obtruded, between the two observations.

**The expiry read protocol pairs timestamp-before-value reads with value-before-timestamp
writes.** A lock-free read must never return a value whose EXPIRED notification a concurrent
rewrite already fired. `hasExpired` is therefore timestamp-only and every lock-free reader
consults it before loading the value; a `loadLoadFence` at the end of `hasExpired` and a
`storeStoreFence` in the generated `setValue` hold both orders on weak memory, and `put`
stores the value before `setWriteTime` (the other rewrite sites already did). A reader that
observes a fresh timestamp therefore observes the rewritten value, closing the LATE direction.
The EARLY direction (stale timestamp with the fresh value) is one spurious miss that
linearizes between the old value's expiry and the rewrite, a 64-bit timestamp read being
atomic, so only the read-extension resurrection above and the bulk single-`now` scans remain
non-linearizable. A caller acting on an expired verdict must exempt an in-flight async load by
probing `isComputingAsync` against a value loaded after `hasExpired` returns. `writeTime`'s
setter is opaque (it was plain, a formal tearing gap on 32-bit VMs), and the swap in `put`
shifts a nanosecond `refreshIfNeeded` window from suppressing a refresh to launching one
wasted best-effort reload, absorbed by the reservation re-check and the ABA commit guards.
Pinned by `ExpirationFrayTest.getIfPresent_expiringRewrite_neverReturnsExpiredValue` (failed
on the first iteration before the reorder) and the `ExpiredReadTear` jcstress test, whose
old-modes model reproduced the tear at 0.21% of samples on aarch64 while the fixed pairing
produced zero across a tough-mode soak. The measured price on the M3 Max is nil: the reader
fence sits within run-to-run drift on `HotEntryBenchmark`'s expiring configs, and the writer
fence is below `GetPutBenchmark.write_only`'s noise floor (interleaved baseline/fixed forks;
that cell drifts more between forks than any fence effect, so judge it with paired runs, not a
plain before/after). Don't move a reader's `getValue()` above its `hasExpired` call, and don't
reorder a writer's `setValue` below its timestamp stores.

**Expiration eviction is capped at `EXPIRATION_THRESHOLD` (1000) entries per maintenance
cycle.** `expireAfterAccessEntries` (shared across its window/probation/protected deques),
`expireAfterWriteEntries`, and the variable `TimerWheel.advance` each evict at most this
many entries, then set `PROCESSING_TO_REQUIRED` so `rescheduleCleanUpIfIncomplete` re-arms
and the backlog drains across subsequent cycles — mirroring `drainWriteBuffer`'s cap and the
climber's `QUEUE_TRANSFER_THRESHOLD`. The cap is high enough that normal traffic never
reaches it; it only bounds the abnormal spike where a cache with no `Scheduler` goes idle,
lets a large population expire logically, then returns to traffic — one maintenance cycle
would otherwise evict the whole backlog under `evictionLock`, stalling any writer that
overflows the write buffer and assists (post "Assist maintenance directly when the write buffer is full"). The work isn't reduced, only
sliced, and since eviction runs async by default the slicing keeps a single cycle from
blocking a thread too long. The **timer wheel** rewinds `nanos` to `previousTimeNanos` when
its budget is exhausted (reusing the exception-rewind path) and re-links the unprocessed
bucket remainder in place (mirroring the catch block, but from `next` since the evicted node
is gone), so the next advance reprocesses the backlog — already-drained buckets rescan
cheaply, and the eviction check keeps non-expired nodes from being evicted early. A capped
cycle can briefly leave expired entries counting toward `weightedSize`, so a same-cycle
`evictEntries` could pick a live victim over an expired one; negligible — frequency-based
selection favors the cold expired entries and it self-corrects next cycle. Don't flag the
cap as under-expiring, and don't remove the `PROCESSING_TO_REQUIRED` re-arm.

**The expiration scans reposition through `reorder`, not `moveToBack`, because a reentrant cycle
can move the entry they are holding.** Each scan reads its successor into a local, then calls
`evictEntry`, which delivers the removal notification; under `executor(Runnable::run)` or the
rejection fallback the listener runs inline, and a `RemovalListener` is permitted to modify the
cache. A nested `maintenance()` can therefore unlink that successor or transfer it to another
deque before the scan resumes on it. Reentrancy is not a supported style and cannot be detected
or refused, so the requirement is only that it not corrupt: the scans confirm the entry is still
theirs rather than repositioning it blindly. Two guards are needed, because the window,
probation, and protected deques **share one pair of link fields on the node**, so
`AccessOrderDeque.contains` answers "linked somewhere", not "linked here":
- `contains` alone covers an *unlinked* entry. Without it, `unlink` sees both links null, runs
  `first = next; last = prev`, and discards the whole deque, leaving every entry in it live in
  `data` and in no eviction queue.
- `getQueueType()` covers a *transferred* entry, whose links belong to another deque. Without it,
  `unlink` splices that deque and assigns one of its nodes as this deque's `first`/`last`.
Skipping is the correct action, not a fallback: `transfer` appends with `offerLast`, so a moved
entry is already at its target's MRU end with nothing stale to repair. `expireAfterWriteEntries`
needs only the `contains` half, since the write-order links are exclusive to the one write-order
deque. Pinned by `BoundedLocalCacheTest.maintenance_recursive_accessOrder` / `_writeOrder` and
`expireAfterAccess_transferredDuringScan`. Don't reduce either scan back to a bare `moveToBack`,
and don't drop the queue-type argument as redundant with `contains`.

The wheel budget counts **only evictions**, never the cascade (rescheduling a non-expired
node to a finer level) — mirroring the deque caps, which count `evictEntry` but not the
`moveToBack` reorder. Cascading a densely-populated coarse bucket is O(n) and *not* sliced,
but it's accepted: an O(1) pointer splice with no CHM write or listener (~1–2% of an
eviction), done at most once per node per advance, and a given coarse bucket cascades only
~once per its multi-day span. The only trigger is a whole cache landing in one coarse bucket
— entries scheduled past the ~6.5-day overflow span (a JVM won't outlive it) or bulk-loaded
at startup with periodic reload (a cache anti-pattern). Debated and declined (2026-07-03):
capping it bounds a lock-hold no worse than one already-accepted post-cap eviction cycle
(the equivalent threshold is ~50–100K), for the cost of new concurrently-mutated state in
the wheel. **Critically, a cascade cap must never reuse the eviction rewind:** the rewound
re-advance re-scans the finer levels the cascaded nodes moved to and re-cascades them,
starving the eviction drain (or livelocking). Evictions can rewind only because an evicted
node is gone, so the re-traversal skips it. Don't cap cascades via the rewind.

*If ever revisited (needs a repro — a coarse bucket with ~10^6 live entries pinning
`evictionLock` past the eviction cap while a writer-assist blocks):* a safe cascade cap needs
a **forward-carried backlog**, not a rewind — the wheel's analog of the climber's `adjustment`
carry-over. On hitting the budget, stitch the unprocessed remainder (the current bucket's tail
plus the un-visited buckets/levels, reusing the nodes' existing variable-order links) into a
backlog list held on the wheel, and let `nanos` advance **normally**. The next advance flushes
that backlog first (evict the due, reschedule the rest), then resumes the level walk — forward
progress, nothing re-scanned. The hard part is lifecycle reconciliation between advances: a
backlogged node that gets `deschedule`d unlinks transparently (same links), but `reschedule`
must move it out of the backlog and back into a wheel bucket, and the flush must tolerate the
list shrinking under it. That concurrent-mutation surface in the codebase's most intricate
structure is why it's deferred, not the mechanism itself.

**Collected references are drained at `REFERENCE_THRESHOLD` (1000) per queue per maintenance
cycle**, then `PROCESSING_TO_REQUIRED` re-arms the backlog like every other budget here. The
budget counts *polls* rather than evictions, so a run of stale references cannot extend the
hold, and each queue gets its own so both make progress. This is the expiration cap's shape
with a different trigger: a garbage collection clears an arbitrary number of keys or values at
once, with no user action to blame it on, and the drain then ran to exhaustion under
`evictionLock`. Measured (M3 Max, JDK 26, 1M weak keys cleared and enqueued at once): one
`cleanUp()` held the lock **130–190 ms**; capped it is 0.12–0.5 ms per cycle over 1000 cycles,
and a concurrent writer completed 2.8k–81k operations during the backlog where it had completed
~1,100. The cost is ~45% more total drain time from the extra cycles. **What the cap does not
do is bound a concurrent operation's wait**: `rescheduleCleanUpIfIncomplete` re-submits
immediately on the common pool and `evictionLock` is not fair, so the drainer barges back in
and the backlog as a whole still holds the lock (a probe acquiring and releasing every 100 µs
got in 2–92 times over the backlog, capped or not). That residual is shared with the expiration
budget and is a lock-fairness question, not a reason to drop the cap or to widen it.

**The maintenance consumer never waits for a producer's publication.** `MpscGrowableArrayQueue`
publishes in two steps, a CAS of the producer index and then a release store of the element, and
the strong `poll()` spins on that element when the index says the queue is non-empty. Both
consumers (`drainWriteBuffer` and `clear`) hold `evictionLock`, so a producer descheduled
between those two instructions — a container throttling a thread, say — stalled every other
policy operation for its whole pause and burned a core doing it. Both use `relaxedPoll()`, which
returns null instead, and JCTools offers exactly that pairing for a consumer that must not
block. Nothing is stranded: every production `offer` is `afterWrite`'s, and `scheduleAfterWrite`
runs after `offer` returns, so the passed-over task is re-armed by its own producer (IDLE →
REQUIRED, or PROCESSING_TO_IDLE → PROCESSING_TO_REQUIRED, which makes maintenance's final CAS
fail). `clear` is safe for a second reason: it already abandons the buffer once a concurrent
writer refills it past `WRITE_BUFFER_MAX / 2`, `AddTask` links only `if (isAlive)`, deque
removal is contains-guarded, and `makeDead` takes the weight from the node rather than from the
policy precisely because an update may still be buffered, so a task that outlives the entry it
describes costs a weight swing that telescopes back to zero. It ends with
`rescheduleCleanUpIfIncomplete`, which is what carries the remainder forward.

## Exception Handling

**Catch-commit-rethrow pattern** in `doComputeIfAbsent` and `remap`. Both catch
`Throwable`, not just RuntimeException. When user code
(mapping function, weigher, expiry) throws after `notifyEviction` was called, the
phantom eviction is made real: the node is retired, null is returned to CHM, and the
exception is deferred past cleanup.

**notifyEviction is called BEFORE user code**, not after. This can't be reordered —
it preserves linearizability for resource-based listeners (e.g., file delete before
recreate). The catch-commit-rethrow pattern handles the case where user code then
throws.

**wasEvicted flag** in `remap`: `boolean wasEvicted = (ctx.cause != null)` is captured
BEFORE the try block because `ctx.cause` can change from null to REPLACED
inside the try. The catch block uses `!wasEvicted` to distinguish eviction-path
exceptions (commit+defer) from non-eviction exceptions (immediate rethrow).

**`remap`'s no-op exit records itself in `ComputeContext.unmodified`; the post-write dispatch
must not re-derive it.** The in-lambda short-circuit takes the exit on four conditions
(`preserveTimestamps`, a same-instance return, and no removal cause); the dispatch used to test
only the hint, so a hinted call that *did* mutate committed the mutation and then skipped its
`AddTask`/`UpdateTask`. A hinted update left `weightedSize` short by the delta forever, and a
hinted create on an absent key (the absent branch has no short-circuit at all) installed a node
linked into no deque and counted in no weight, which `makeDead` later *subtracted* from
`weightedSize` anyway, relaxing the bound by that much per cycle. Not reachable from the public
API: every caller that sets `preserveTimestamps` returns the instance it was handed, and each
insert branch returns before the hint is set. It is latent because `RemapHints` is
package-private, so nothing warns a future caller. Pinned by
`BoundedLocalCacheTest.remap_preserveTimestamps_newValueDiffers_publishesTheUpdate` and
`remap_preserveTimestamps_absentCreate_publishesTheAddition`. Don't restore a second copy of the
predicate; the exit that skips the work is the one that says so.

**`remap` same-instance return is a setter no-op, NOT a metadata no-op**. When a
user `compute`/`merge` remapping function returns the same value instance as the
current value, `setValue` is skipped, but `weight`, `accessTime`, `variableTime`,
and `writeTime` still update. This is intentional: `compute` is a mutation API,
so a same-value return is still treated as a write for eviction-policy purposes
(the entry's age/weight/access are refreshed). The only documented full no-op is
the explicit `preserveTimestamps` path. A reader expecting
`compute(k, (k, v) -> v)` to leave eviction ordering undisturbed would be
surprised; the source does not call this out, so this entry is the canonical
place the behavior is documented (preferred over a source comment).

**A contract-violating user component is the user's problem — Caffeine breaks reasonably and
pushes back, it does not add defensive ceremony.** This covers a throwing `Ticker`, `Weigher`,
`Expiry`, or loader; a broken `equals`/`hashCode`; a hostile `CompletableFuture`; and `Error`/OOME.
Repeatedly re-derived — it has been raised as a fresh finding in at least seven audit runs, so the
reasoning is pinned here rather than re-argued:
- **The guarded/unguarded split is deliberate, not an oversight.** `StatsCounter`
  (`GuardedStatsCounter`), `Scheduler` (`GuardedScheduler`), and the removal/eviction listeners are
  wrapped in catch-`Throwable` because they are *fire-and-forget* — there is a sensible default to
  fall back to (do nothing, `CacheStats.empty()`, `DisabledFuture`). `Ticker`, `Weigher`, `Expiry`,
  and the loader are **value-bearing**: they return something the cache must have, so there is no
  default to recover from and propagating is correct. `Ticker` is not "the asymmetry" — it is on the
  correct side of the line.
- **The recurring finding shape** is that a value-bearing throw lands *after* a commit and leaves
  skewed state: `AddTask.run`/`UpdateTask.run` advance `weightedSize`/`windowWeightedSize`/
  `mainProtectedWeightedSize`/`policyWeight` before an `expirationTicker().read()` argument to
  `evictEntry` (permanent telescoping-sum skew, node linked into no deque); completion prologues in
  `refreshIfNeeded`/`handleCompletion`/`getAll` read `statsTicker()` before their cleanup (orphaned
  `refreshes` token, stranded async proxies). The mechanisms are **real and present** — confirm them
  and close, don't propose containment. Hoisting the reads or adding try/finally is ceremony against
  a cache whose clock is already broken, and the containment op is usually throw-prone on the same
  trigger.
- **Ben's precedent:** Quarkus shipped a broken `CompletableFuture`; Caffeine pushed back and Quarkus
  fixed it — a better outcome than defensive code would have produced. Don't add a must-not-throw
  clause to `Ticker`'s javadoc either; that was considered and declined.
- **The one containment taken in this family is that a concurrent obtrusion reads as not-ready.**
  `Async.getIfReady` checks `isDone() && !isCompletedExceptionally()` and then joins, so a standard
  `CompletableFuture.obtrudeException` landing between the two threw a `CompletionException` out of
  whatever asked: `AsyncCache.getIfPresent`, the synchronous view, and every `isComputingAsync`
  caller, `hasExpired` and eviction under `evictionLock` included. Measured with a plain future and
  no subclass, a free-running obtruder produced 3.5M throws in 1.28B query rounds. The join is now
  wrapped and returns null, which is the classification the method's own doc promises, and the
  catch is narrow (`CancellationException`/`CompletionException`) so a hostile subclass throwing
  anything else from `join` still propagates. It defends the readiness question only, not the
  entry: the completion handler is one-shot, so obtruding onto an already-successful future leaves
  the entry physically present while queries filter it, which is the documented physical-vs-logical
  split and is accepted. Nothing better exists once a user completes a future the cache owns. The
  cache's own use is safe by ordering, `AsyncBulkCompleter.failProxies` removing the mapping before
  it obtrudes.
- **A throw inside `maintenance` does not lose work.** The `finally` CASes `PROCESSING_TO_IDLE → IDLE`
  on a clean exit *and* on a throw (only a racing writer's `PROCESSING_TO_REQUIRED` forces `REQUIRED`),
  so the un-drained buffer entries are **deferred, not dropped**: they stay in the write buffer, the
  next write's `scheduleAfterWrite` re-arms from `IDLE`, and a full buffer forces `afterWrite`'s
  inline `maintenance` fallback. `performCleanUp` skipping `rescheduleCleanUpIfIncomplete` on the
  throw path is the same benign deferral. Don't "fix" the exception path to force `REQUIRED`.
  (The one leg here that *was* a real defect — the inline fallback skipping the write's own task —
  is already fixed by the `try { drains } finally { task.run(); }` in `maintenance`; keep it.)
- **`scheduleDrainBuffers`'s rejection fallback has no paired re-arm, deliberately.** It is the
  only one of the five `maintenance` call sites without a following
  `rescheduleCleanUpIfIncomplete()`, and unlike the throw path above its `maintenance` completes
  **normally**, so it can end `REQUIRED` from any exhausted budget. Adding the epilogue there buys
  nothing, because the catch only runs when `executor.execute` **rejected**: the common-pool arm
  cannot apply (a rejecting executor is not the common pool), the pacer arm would schedule onto
  that same rejecting executor whose fire-time rejection is swallowed in the JDK Delayer, and with
  no scheduler there is no arm at all. The inline `maintenance(null)` in the catch is the
  resilience, and recovery relies on later cache activity. The distinction that decides any
  "missing drain epilogue" finding: **reject means futile, so skip; a lock bounce means the
  executor is healthy, so reschedule** (that sibling case, the drain bouncing off `clear()`'s held
  eviction lock, was a real defect and is fixed). A rejecting executor is a broken configuration,
  and `executor(Executor)`'s javadoc already warns about one that discards or never runs tasks.

## References

**The keyReference in a weak/soft value reference is read and written opaquely.** The
field is set during construction and, under `synchronized(node)`, mutated to a sentinel
value (`RETIRED_*_KEY` / `DEAD_*_KEY`) when the node is retired or dies. Lock-free
readers tolerate the resulting staleness window as weakly-consistent observation, but a
strong-key weak/soft-value node keeps its key *inside* that reference, so `getKey()` and
`isAlive()` read the field independently: with plain reads, a reader that observed the
sentinel and then observed the older key would judge a retired node alive while handing
out an internal sentinel as its key. Opaque access forbids exactly that, since opaque
operations on one variable are coherent, and it costs nothing at runtime because the
constraint binds the compiler rather than the hardware (opaque loads compile to ordinary
loads on x86 and aarch64 alike). The field stays non-volatile and the constructor's store
stays plain, the object not yet being published; only the accessors are opaque. A jcstress
probe was written to arbitrate first and found no violation over both weak and soft values
while exercising the window (it observed the monotonic `key`-then-retired interleaving),
so it was discarded rather than kept as a pin: the hazard is a legal compiler
transformation that a green run cannot refute.

In `setValue`, a new `WeakValueReference` is installed via
`setRelease` followed by `VarHandle.storeStoreFence()`, then the old reference is
cleared via `ref.clear()`. The fence prevents the old reference's `clear()` from
being reordered before the publication of the new reference. Without the fence, a
reader that re-reads the same reference and observes a cleared referent cannot
distinguish "the clear was already committed" from "the clear's store buffer is
ahead of the new reference's publication" — breaking the `getValue` re-check loop
invariant. `setRelease` alone orders the new reference's constructor writes before
the publication, but does not constrain the subsequent `ref.clear()` against any
racing reader (#1820, confirmed on aarch64 M3 Max via JCStress IntermittentNull test).

In the constructor, a plain `VALUE.set` is used since the object itself is not yet
published. Strong value caches use `setRelease` without the fence since there is
no inner object to publish and no `ref.clear()` to order against.

**Weigher.boundedWeigher** wraps all user weighers and enforces `weight >= 0` at
runtime via `requireArgument`.

## Concurrency

**No debug-mode assertions.** Runtime invariant assertions are impractical for
concurrent code — too hard to assert on a running system. Correctness relies on
testing (Fray, LinCheck, JCStress) and static analysis (ErrorProne `@GuardedBy`).

**nanoTime is monotonic.** Per JVM spec, `System.nanoTime()` is monotonic. Backward
movement would be a JVM bug, not a cache issue.

**`scheduleAfterWrite`'s IDLE arm retries its failed swap rather than acting on what it read.**
The status can advance between the opaque read and the compare-and-swap, so a writer that read
`IDLE` may find `PROCESSING_TO_IDLE`. Dropping the failed swap and calling `scheduleDrainBuffers`
anyway leaves that write with no driver: the guard sees a drain in flight and returns, while the
drain has already passed the task's slot, and its exit swap then settles the machine at `IDLE`
with the task still buffered. Retrying against the observed status routes to the processing arm,
which converts the exit to `PROCESSING_TO_REQUIRED` — the same shape the processing arm has always
used, and why the two arms now look alike. The end state it prevents was benign (the task is not
lost; the next write, a `cleanUp`, or a read that fills a stripe drains it, which is the deferral
already priced for the maintenance-throw path and the `WRITE_BUFFER_MAX` cap) and reaching it
needs the entry store's visibility to lag the drain's loads, so this is a tidiness fix, not a
correctness one — recorded because three audit runs have now reached for this machine. `IDLE` is
written in exactly one place, the value-checked `casDrainStatus(PROCESSING_TO_IDLE, IDLE)`, so no
`REQUIRED` is ever swallowed. Don't collapse the arm back to an unconditional swap. Pinned by
`BoundedLocalCacheTest.scheduleAfterWrite_staleIdle_retriesAgainstTheObservedStatus`.

**skipReadBuffer optimization.** When the cache is less than half full with strong
keys/values and no expiration, `skipReadBuffer()` returns true, avoiding read buffer
overhead entirely. This means frequency tracking is disabled until the cache is
sufficiently populated — the eviction policy bootstraps without frequency data.

## Node State

**Two weight fields**: `weight` (entry's perspective, guarded by `synchronized(node)`)
and `policyWeight` (policy's perspective, guarded by evictionLock). They're correlated
but updated at different times — this is intentional for the telescoping sum to work.

`makeDead` subtracting the finalized `getWeight()` (not `policyWeight`) and `UpdateTask.run`
being deliberately dead-guard-free are a **matched pair**: a late-applied `UpdateTask` adds back
exactly the δ that `makeDead` over-subtracted. Don't add an `isDead` guard to `UpdateTask` and
don't switch `makeDead` to `policyWeight` — either one alone breaks the cancellation.
Because racing updates offer their `UpdateTask`s outside the node lock, out-of-order
drains can leave a live node's `policyWeight` transiently negative; the climb transfer
loops then charge that weight to their quota and over-shift the region caps beyond the
commanded adjustment (the net can even invert the commanded direction). Adjudicated
tolerated, not guarded (2026-07, audit-adaptivity F1): the caps are the controller's
policy targets, not capacity enforcement — eviction and the total bound ride on the
telescoping `weightedSize`/`maximum` — and the split coerces back on its own: the next
completed sample overwrites the inflated carry-over, the below-floor lift is not
step-capped, and the excursion is bounded by a single weigher swing on one key. Don't
clamp the transfer quota against negative weights, and don't "fix" the offer ordering.

**Queue type constants** are plain ints, not enums: WINDOW=0, PROBATION=1, PROTECTED=2.
The field is plain (not volatile), guarded by evictionLock.

## ConcurrentHashMap Constraints

**No recursive computations.** Writing to the cache from inside an atomic
compute/computeIfAbsent/merge callback violates ConcurrentHashMap's contract; this is
not a Caffeine bug. Detection is best-effort, not guaranteed: only recursion that lands
on an empty bin's ReservationNode reliably throws `IllegalStateException("Recursive
update")` (surfaced raw, unwrapped). Recursion into a populated or treeified bin is
undetected and can silently corrupt (lost inserts, double count updates, clobbered
writes). Never rely on the ISE as a safety net. During a refresh completion this can
orphan the key's `refreshes` token (suppressing its auto-refresh) only if `data.compute`
throws *before* `remap`'s lambda — a broken `hashCode` or a rare cross-bin ISE (same-key
recursion silently re-enters a populated bin instead); in-lambda throws self-clean on the
exits a completion can reach (the create-branch `finally` and the present-entry `catch`).
The one exit that *preserves* — an absent-branch **user-function** throw (B1-1; both
siblings, after ULC's catch was narrowed to `value != null`) — a refresh completion never
hits, because its own lambda cannot throw before materialization. Either way the orphan
self-heals on the next write/removal. Don't add a catch-side `refreshes.remove` — it
re-throws on the broken-`hashCode` sibling.

The **`evictionListener` runs inside the CHM compute lambda** — `notifyEviction` is called
within `data.compute`/`computeIfPresent`, holding the entry's bin lock — so it is subject to this
rule: a listener that modifies the cache (same-key *or* other-key) is a recursive update → an ISE
(caught + logged in `notifyEviction`, so the write is silently lost) or silent corruption. The
`Caffeine.evictionListener` javadoc says "must not modify this cache." That (and the parallel
`mappingFunction`/`remappingFunction` warnings across `Cache`/`AsyncCache`/`LoadingCache`/`Policy`)
was tightened from the wording inherited from ConcurrentHashMap's `compute` javadoc — "must not
attempt to update any *other* mappings" (CHM's phrasing through JDK 13; JDK-8232652 replaced it with
"must not modify this map" in JDK 14, though `merge` still carries the old form) — which by negation
wrongly implied a *same-key* mutation was safe. The `removalListener`, by contrast, runs outside the
atomic operation (async/after the fact) and *may* modify the cache. Don't flag a same-key mutation
from the eviction listener as a corruption bug — it's documented misuse.

**CHM bin blocking is not a Caffeine bug.** `compute()` locks the hash bin. If the
mapping function (cache loader) is slow, all other operations on keys in the same
bin are blocked. This is the #1 recurring user issue (~20 reports). The answer is
always: use `AsyncCache` for slow loaders, increase `initialCapacity` to reduce
collisions, or make loaders faster.

**The same doctrine covers a two-map deadlock, not just blocking, including when it wedges an
innocent thread.** A loader that touches the cache can take the `refreshes` bin monitor and then a
`data` bin monitor, while any write takes `data` and then `refreshes` through `discardRefresh`.
Opposite orders, and `findDeadlockedThreads()` reports it on both implementations. It is declined
on the same basis: the only path that acquires a `data` bin lock while holding a `refreshes` one is
the user's `reload`/`asyncReload`, and `CacheLoader.reload`'s javadoc bolds the prohibition
("loading **must not** attempt to update any mappings of this cache directly or block waiting for
other cache operations to complete"). Everything else inside `refreshes.computeIfAbsent` is either
a lock-free `data` read (`getIfPresentQuietly`) or the `asyncReload` call itself, and the
completion `handle` that does the `data` work is attached **outside** the lambda, deliberately, so
the refreshes monitor is not held while it runs. Keep it outside.

Two objections are answered rather than ignored. That the victim is an ordinary `put` which did
nothing wrong is true, and is a property of every lock-order inversion in a shared structure; the
counterparty of misuse is always innocent. And a silent deadlock is a worse diagnostic than
`Cache.get`'s documented `IllegalStateException` for recursive updates, which is a fair criticism
of the *diagnosis* rather than evidence the ordering is wrong. Don't reorder the internal maps to
make a forbidden loader safe.

**`Expiry` must not call into the cache, and carries no javadoc saying so.** The callback is
invoked under `synchronized(prior)` in `put` and under the bin lock on the compute paths. A
calculator that calls `cleanUp()` or a `Policy` ordering method waits on `evictionLock` while
holding the node monitor, deadlocking against the maintenance thread's `evictEntry`, which takes
those locks in the documented order. One that calls `invalidate` re-enters the monitor on its own
thread, retires the node, and lets the outer `put` commit into the dead entry. Neither is guarded.
The omitted warning is deliberate: computing a duration has no reason to re-enter the cache, so
the note that fits `CacheLoader.reload` and `evictionListener` would be noise on `Expiry` and
`Weigher`. Hoisting the callback out of the monitor the way `Weigher` was hoisted is not available
either, since `expireAfterUpdate` and `expireAfterRead` read the node's variable time under it and
the compute-path invocations need the atomic context.

**`clear()`/`invalidateAll()` do not wait for an in-flight `computeIfAbsent` insert.** The insert
is invisible to `clear()`'s `data.values()` snapshot (the CHM Traverser skips the in-flight
`ReservationNode`), so it survives and serializes *after* the clear. CHM blocks per-bin and removes
it, but both orderings are linearizable, and the layered design can't see CHM's internal per-bin state
to block (a fix would require forking CHM). Inserts only — an in-flight *update* on an existing node
**is** waited for (`clear()`'s `removeNode` goes through `computeIfPresent` + `synchronized(node)`).
Documented user-facing on the `invalidateAll()` javadoc ("behavior … is undefined for an entry that is
being loaded (or reloaded) and is otherwise not present") + the wiki. Don't flag the clear-vs-in-flight-
insert divergence.

**Eviction is async, not immediate.** After `put`, the cache may temporarily exceed
`maximumSize` until the executor runs maintenance. Use `executor(Runnable::run)` for
inline eviction in tests, or call `cleanUp()` before assertions.

That determinism has a cost worth knowing before reaching for it under load. The expiration and
window scans bound *evictions*, not traversal: `remaining--` runs only on an eviction or a
transfer, so a node the scan relinks and skips (an in-flight async load, a zero-weight entry) is
free and the walk continues past it. The default executor hides that, since maintenance coalesces
through the drain status and many writes share one cycle. `Runnable::run` removes the coalescing,
so a burst of pending async loads pays a walk over the pending set on every write in the burst,
which is quadratic across it. Fine for a test with a handful of entries; not a knob to reach for
in a benchmark or a reproduction that holds thousands of loads in flight. The scan is
self-correcting once any load completes (pending entries migrate to the MRU end and the walk stops
at the first completed, unexpired node), so the cost needs a deque with no completed entry at all,
not merely N loads outstanding.

**Expiration and cleanup are amortized, not instant.** Caffeine performs maintenance
during write operations and occasionally during reads. For idle caches, use
`Scheduler.systemScheduler()` to get prompt expiration. This is best-effort with
no hard timing guarantees.

**No close() by design.** The `Cache` interface deliberately does not extend
`Closeable`. The cache is a data structure that becomes GC-eligible when
unreferenced. The `WeakReference` in `PerformCleanupTask` breaks the
scheduler→cache reference chain, so scheduled maintenance becomes a no-op
when the cache is unreachable. (JCache's `CacheProxy` is the only component
with explicit close semantics, as required by JSR-107.)

## Refresh

**Refresh returns the stale value, not the fresh one.** `get()` returns the current
value immediately and triggers an async reload. The next `get()` returns the
refreshed value. This is the entire point — hiding reload latency from callers.

**Refresh only triggers on access.** An idle cache with no reads will never refresh.
For proactive refresh, use `ScheduledExecutorService` with `cache.refresh(key)`.

**"Logged and swallowed" is a promise about the future's result, not about producing it.**
`refresh` documents that a failed reload is logged and swallowed, and that covers the
`CompletableFuture` completing exceptionally: the load ran and failed, the mapping is unchanged,
and the caller who ignores the returned future sees nothing. It does not cover `asyncReload`
throwing *synchronously*, which happens before there is a future at all. Those are distinct
failures. Guava and early Caffeine did not hand the future back to the caller, which is the
wording's origin; a loader that throws while merely constructing its future has a bug in the most
basic step, and surfacing it is right. Do not "fix" `refresh`/`refreshAll` to swallow a
synchronous throw from `asyncReload`/`asyncLoad`, and do not cite the declared `throws Exception`
on those methods as evidence that it should be swallowed. Audits raise this repeatedly; it was
rejected on 2026-08-29 (rows 9.1 and 7.6).

**`expireAfterAccess` + `expireAfterWrite` together is discouraged.** Inherited from
Guava for compatibility. The two timestamps are independent; whichever has the
shortest remaining duration wins. Prefer `expireAfter(Expiry)` for custom logic.

## Iteration

**`asMap()` iteration is not a cache read.** Iterators do not update access times or
frequency counters. This prevents iteration from polluting the eviction policy.
Expired entries are skipped during iteration.

**Under `weakKeys()` the key and entry spliterators still claim `DISTINCT`, and must.** The
`weakKeys()` javadoc names `IdentityHashMap` as the model for its semantics, and the JDK's own
identity-keyed map advertises `DISTINCT` on exactly those two spliterators while omitting it on
`values()`, which is the shape Caffeine matches. It produces the same triple audits report as an
anomaly: two keys that are `equals` but not `==` give `size() == 2`,
`keySet().stream().distinct().count() == 2`, and `new HashSet<>(keySet()).size() == 1` (verified
on JDK 25). Removing the flag would not be a no-op, it would be wrong: `distinct()` would then run
with `equals`, merging two genuinely distinct live entries so the stream reports fewer elements
than the cache holds. Under identity semantics the keys *are* distinct, so the flag is the correct
claim. Rejected on 2026-08-29 (row 6.9).

**The `keySet()`/`entrySet()` views inherit `AbstractSet.equals`/`hashCode`, so a formal
contract breach in the dead-entry window is unavoidable and accepted.** `size()` is physical
(counts an expired-but-unreaped or GC'd-weak-key entry) while iteration/`contains`/`hashCode`
are logical (skip it). With a live `a` + a pending-dead `b`: `HashSet{a,b}.equals(keySet)` is
*true* (size `2==2`, then `containsAll` over the logical iterator `{a}`) yet the hashCodes differ
(`Object.hashCode` forbids equal-but-unequal-hash), and it's asymmetric — `keySet().equals(HashSet{a,b})`
is false, and a view doesn't even equal a copy of itself. **No receiver-side fix exists:** the
true-returning direction runs inside the *argument's* `HashSet.equals`, which calls
`keySet.size()`+`iterator()`, so overriding the view's own equals/hashCode can't intercept it; only a
*logical* `size()` would close it, and that's rejected (lock-free instantaneous physical size; a
physical hashCode isn't even computable once a weak key is GC'd). This is inherent to
`AbstractSet`/`AbstractMap` computing over `size()`: **no correct, compatible equals is writable for
a collection whose contents change under an equality iteration — `WeakHashMap` has the identical
property** (entries GC'd mid-comparison; even its own `size()` javadoc only notes the snapshot /
changes-underneath behavior at the *impl* level, not on the `Map` interface). `values()` is clean
(identity equals/hashCode, like CHM). Same best-effort window as "`size()` is an estimate"; call
`cleanUp()` with no concurrent ops before comparing if exact equality is needed. Don't try to "fix"
the view equals/hashCode, and don't add a `size()`-over-counts warning to the `asMap()` view javadoc
(redundant — `asMap()` is a view of a cache whose `estimatedSize()` already says "approximate," and
even `WeakHashMap` doesn't warn at the interface level).

## Maintenance nudges

**Read paths nudge `scheduleDrainBuffers()` when they observe an expired/collected
entry** (`getIfPresent`, `containsKey`, `containsValue`, `getAllPresent`, the iterator,
and the key/value/entry spliterators), so lazily-detected garbage is reclaimed promptly;
the nudge is skipped on a plain miss and is a cheap flag check when maintenance is
already running. On a caller-runs executor the nudge runs maintenance **inline**, so an
in-progress scan of `data.values()` can have a node reaped underneath it — e.g.
`containsValue` is an O(n) scan and the internal `LocalCacheSubject` validator calls it
*per node* while iterating `data.values()`; a weak key collected mid-scan is then
drained, correctly removing and killing a node the weakly-consistent iterator still
yields. Production readers tolerate a dead node (they check `isAlive`/`getValue`), and
the validator was made robust to it: it iterates `data.entrySet()` and validates a node
only if it is still mapped under its key, so a node reaped mid-scan is skipped while a
node genuinely stuck in the map (a leak) stays mapped and is still caught.

**A failed `replace` nudges only when the entry is garbage.** Both overloads signal "did not
update" by clearing `ReplaceContext.oldValue`, which alone cannot tell a dead or expired entry
from a caller's expected value that did not match. `ReplaceContext.garbage` separates the two so
that only the first schedules maintenance. "fix minor edge cases in put and remap" added the nudge
for the expired case but hung it on the shared signal, so `replace(k, expected, new)` also
submitted a task on every healthy CAS failure. Over 10⁶ failing replaces against a one-entry
cache, removing it took an unexpiring cache from 34 ms to 15 ms and an `expireAfterWrite` cache
from 33 ms to 28 ms. Both directions are pinned:
`AsMapTest.replaceConditionally_wrongOldValue_noMaintenance` and
`ExpirationTest.replaceConditionally_expired_maintenance`.

The conditional overload tests `hasExpired` before `containsValue`, matching `remove(k, v)`'s
branch order, so an expired entry is reclaimed whether or not the caller's expectation held.
Testing `containsValue` first would let a healthy mismatch skip the ticker read and reach 16 ms
on the expiring cache, and it was rejected: the two conditional operations should read alike, and
the saving is one `System.nanoTime()` (9 ms per 10⁶ calls on the same machine). `containsValue`
cannot be tested before the null checks at all, since a cleared value reference fails it and the
entry would be misfiled as a mismatch.

## Known JDK Interactions

**StackOverflowError can leak the eviction lock.** If user code causes a
`StackOverflowError` inside a cache operation, `ReentrantLock.unlock()` can fail
to execute (JDK bug JDK-8319309), leaving the eviction lock permanently held and
blocking all subsequent writes.

**PerformCleanupTask.exec() returns false.** This is an optimization — the task is
allocated once and reused instead of creating a new `Runnable` wrapper per executor
submission (which showed up as a memory hotspot in profiling).

## Pacer

The Pacer rate-limits expiration maintenance scheduling. It uses
`TOLERANCE = ceilingPowerOfTwo(1 second)` (~1.07s) as a minimum delay threshold,
preventing scheduling storms from rapid expirations.

`nextFireTime = 0L` is the unscheduled/cancelled sentinel. `calculateSchedule`
bumps any computed fire time that would equal `0L` up to `1L` to prevent a
collision with the sentinel — needed only in edge cases where `now + TOLERANCE`
or `scheduleAt` lands exactly on zero, but the guard removes the ambiguity for
readers of `schedule()`'s recursion check.

**`future == null && nextFireTime != 0L` is a deliberate transient, not a wedge —
and its safety leans on `GuardedScheduler`.** `schedule()` commits `nextFireTime`
(via `calculateSchedule`) *before* calling `scheduler.schedule()`, then publishes
the returned `future`. Between those two steps the pacer is momentarily in that
state, which the immediate-scheduler short-circuit relies on: an immediate scheduler
runs `command` synchronously inside `schedule()`, re-entering `schedule()` before the
`future` is published, and the `nextFireTime != 0L` check breaks the recursion.
**Reaching that state on the replacement path is what makes the short-circuit
complete, and it is why the reschedule arm calls `cancel()` rather than
`future.cancel(...)`.** `cancel()` clears `nextFireTime` and the field together, so a
re-entrant call meets a fully unscheduled pacer and takes the guard; cancelling the
future alone leaves the old one published, and then `!future.isDone()` is permanently
false for an immediate scheduler, so every re-entry cancels and reschedules without
bound. That was a live defect: a `Scheduler` running its command synchronously and
returning a *completed* future hung `put` on every executor including `commonPool`,
silently, because `GuardedScheduler` and `PerformCleanupTask.exec` swallow the
`StackOverflowError` and the stack immediately re-descends. Pinned by
`ExpirationTest.schedule_immediate_completed`; its neighbour `schedule_immediate`
returns an *incomplete* future, which is the shape a real inline scheduler never
produces and is what masked this. If
`scheduler.schedule()` could *throw* on the first call, that same state would never
clear — every later `schedule()` early-returns and `cancel()` no-ops (`future` stays
null), permanently disabling prompt expiration. It can't: `GuardedScheduler` catches
every delegate throw and maps a null return to `DisabledFuture`. `Caffeine.getScheduler`
wraps a *user-supplied* scheduler this way, but passes the built-in `systemScheduler()`
and `disabledScheduler()` through unwrapped — Caffeine's own scheduler types are known
not to throw or return null, so only user types need guarding. Either way
`scheduler.schedule()` here never throws or returns null. The ordering is safe as written
— don't wrap it in a try/catch to "harden" an unreachable throw, and don't hand `Pacer`
an unguarded *user* scheduler (that, not the ordering, would be the bug).

**A fire-time executor rejection orphaning the pacer's future is accepted best-effort — don't
wrap `SystemScheduler`.** `SystemScheduler` schedules via `CompletableFuture.runAsync(command,
delayedExecutor)`; at fire-time the JDK `Delayer` submits the future-completing task to the
cache `executor`, and if `executor.execute` throws `RejectedExecutionException` there, the
returned future never completes. **This is a JDK `delayedExecutor` limitation, not our misuse:**
`delayedExecutor.execute` accepts the task synchronously (schedules a `TaskSubmitter` on the
shared `Delayer` STPE) so `runAsync` believes submission succeeded, but the fire-time
`baseExecutor.execute` REE is thrown inside the Delayer's own (discarded) `ScheduledFutureTask`
→ swallowed, and `AsyncRun` never runs to complete the CF. A *synchronous* rejection from
`runAsync` **does** propagate to the caller (confirmed empirically on JDK 25) — only the
deferred one is dropped. `GuardedScheduler` can't catch it either — that REE is asynchronous
(fire-time, on the Delayer thread), while the guard wraps only the synchronous `schedule()`
call. `Pacer.schedule` then suppresses *similar-or-later* re-arms while `!future.isDone()`, so
one expiration cycle is lost — but it self-heals: an *earlier* re-arm fails `maySkip` and
reschedules, and once `now` passes the phantom `nextFireTime` the next re-arm reschedules
regardless (no permanent wedge; the stale future is replaced next schedule, no leak). This sits
inside the documented amortized/best-effort expiration envelope, the `executor(Executor)`
javadoc already warns that an executor "that discards tasks or never runs them may experience
non-deterministic behavior," and the default `commonPool` only rejects at JVM shutdown (where a
lost expiration cycle is irrelevant). Don't add an executor wrapper to complete the future on
rejection — it hardens a self-healing, user-configuration-warned corner for no real gain.

**`rescheduleCleanUpIfIncomplete` piggybacks an already-scheduled pacer fire, by
design.** A `drainStatus == REQUIRED` backlog re-arms the pacer only when
`!pacer.isScheduled()`; if a fire is already pending (the next expiration event), the
backlog rides that fire rather than stacking a second schedule. An *expiration*
backlog stays prompt regardless — a >`EXPIRATION_THRESHOLD` backlog leaves an
already-expired deque/wheel head, so `getExpirationDelay` returns `≤ 0` and
`expireEntries` already scheduled the pacer at `TOLERANCE` (~1s). Size eviction is
uncapped (drains fully in one cycle), so it never backlogs. A *reference* backlog defers
the same way a write-buffer one does, and its entries are already unreachable, so the
delay costs a late `COLLECTED` notification rather than a stale read. The shape is a
*write-buffer* backlog (`drainWriteBuffer`'s `WRITE_BUFFER_MAX` cap, reached only when
a concurrent writer refills during the drain, or its `relaxedPoll` passing over a slot a
producer has not published) on a cache whose next expiration is
distant, that then goes idle: the buffered policy tasks — LRU/weight bookkeeping over
CHM mappings that are *already committed and visible* — wait for that distant fire or
any later write / read-stripe / `cleanUp`. Worst observable is a transient over-
`maximumSize` on an idle cache, the documented async-eviction contract — plus, under
*variable* expiry, a deferred **expiration notification**: an entry whose `AddTask` is
still buffered is not yet in the timer wheel, so it was invisible to the
`getExpirationDelay` that armed the pending fire, and its removal listener waits for that
fire (which can exceed the entry's own TTL, since the armed time came from the
policy-visible entries) or the next cache operation. Reads stay correct throughout — lazy
`hasExpired` gates every read. Best-effort amortized maintenance; don't drop the gate to
force a ~1s reschedule (it churns the distant fire's cancel+reschedule for a narrow,
self-healing transient).

**Without a `Scheduler`, maintenance is amortized onto callers, by design.** The immediate
re-arm in `rescheduleCleanUpIfIncomplete` is restricted to `commonPool` because any other
executor may run the submission on the calling thread, where the work is no longer
amortized and the caller pays a whole cycle. The deferred pacer arm above is the fallback
for a custom executor, and it needs a `Scheduler`; configuring one is the published way to
ask for prompt eviction. With neither, a `REQUIRED` backlog waits for the next cache
operation, so a quiesced cache stays over `maximumSize` until then. The excess is capped,
not unbounded: the backlog is write-buffer tasks, `MpscGrowableArrayQueue` is bounded at
`WRITE_BUFFER_MAX`, and a full buffer forces `afterWrite`'s inline assist, so
`estimatedSize()` cannot exceed `maximum + WRITE_BUFFER_MAX`. Measured with
`maximumSize(10)`, a single-thread executor and no scheduler, 200,000 writes then idle: 4
of 10 runs stayed over maximum, and the largest residue over 20 runs was 1,987 against a
2,058 bound. The model is a garbage collector's: the excess is capped and reclaimed on the
next operation rather than on a timer. Don't add a third arm. A caller cannot know whether
the executor would run the submission inline, and moving it into `PerformCleanupTask`,
where a held eviction lock does identify a caller-runs execution after the fact, fails on
the same ground: prompt eviction without a `Scheduler` is not a contract the cache offers.

## Refresh

**`refreshIfNeeded` is intentionally lock-free.** Reads of `writeTime`, `getKey`,
`getValue`, `getKeyReference`, `isAlive`, and the CAS of `writeTime` happen
without `synchronized(node)`. A stale observation could let `asyncReload` fire
on a just-retired node, but the completion-path ABA guards (`currentValue ==
oldValue` + `(node.getWriteTime() & ~1L) == writeTime`) discard the result. Cost
of the rare spurious loader call is accepted to keep the refresh fast path lock-free.
The write-time guard is itself immune to a same-instance overwrite reusing the old
value: a refresh-eligible entry's age exceeds `refreshAfterWriteNanos`, which makes
`exceedsWriteTimeTolerance` true in both of its refresh arms (a duration within the
tolerance takes the always-true disjunct, a longer one is exceeded by the age), so an
intervening write always moves `writeTime` and the stale completion cannot match it.
That arithmetic is load-bearing; a change letting an update skip `setWriteTime` on a
refresh-eligible entry would re-open the stale-reload commit.

**The low bit of `writeTime` is a soft-lock marker, and the completion ABA check
must mask it.** A reader probing for a refresh CASes `writeTime → writeTime | 1`
while it registers the token in `refreshes`, then resets it; it starts no load if
the token already exists, so its transient marker is invisible to any stampede check.
The completion compares the *base* write time (`& ~1L`), not the raw value — a
concurrent reader's transient soft-lock is not a modification, and comparing the raw
value discards a perfectly good reload (issue #1970). The completion also keeps the
token registered in `refreshes` across the value swap: it reads the token to confirm
ownership rather than removing it, and the compute machinery's `discardRefresh` clears
it *after* `setWriteTime`. Holding it for the swap keeps concurrent reads debounced so
none can trigger a stampede in the remove-then-refresh window. Finally, the
`computeIfAbsent` lambda re-validates the marker under its per-key atomicity and aborts
when a concurrent refresh already completed (its `setWriteTime` cleared our marker),
so a delayed reader that passed the `containsKey` gate does not launch a duplicate,
stale reload from the same prior value. On the absent-create path the token clear runs
in a `finally`, so a `weigher` or `expiry` callback that throws while creating the entry
cannot orphan the token (#1970).

**`discardRefresh` is deliberately over-aggressive.** A mutation that races a
refresh discards whatever token is in `refreshes` without trying to prove it's
the same generation. Any refresh in flight was launched against a pre-mutation
snapshot, so killing it is correct for linearizability even if it happens to be
a "newer" generation from a later reader.

**The bounded cache's `containsKey` prescreen stays, and the race it leaves open is
benign** (adjudicated 2026-08-15). `ConcurrentHashMap.remove` takes the bin lock
whether or not the key is there, so the prescreen is what keeps an ordinary write
off that lock. It was added to the bounded cache only; the unbounded cache still
removes unconditionally. What the prescreen cannot see is an in-progress
`computeIfAbsent` reservation, which reads as absent, so a write landing inside
that window leaves a token launched from the generation it superseded. Nothing is
committed wrongly: the completion's ABA guards reject the value, and the
registration re-validates `node.getWriteTime()` inside the reservation, which
rejects any write that lands earlier. That first clause is load-bearing and was
only true by accident until 2026-08-24. The completion's write-time term reads
the node captured when the reload started, and `retire()`/`die()` leave
`writeTime` frozen, so a remove and reinsert of the **same value instance**
presented an unchanged value and an unchanged write time and the guard committed
a reload launched from the dead generation. The commit branch now also requires
`node.isAlive()`, which is exactly the generation test the other two terms cannot
make; both remain necessary, since the write time still catches an in-place
update of a node that never died. Pinned by
`BoundedLocalCacheTest.refreshIfNeeded_reinsertedNode_rejectsStaleReload`. The residue is bounded by one load, and it
is a delay rather than a loss — automatic refresh is suppressed for that key while
the orphan is in flight (`refreshIfNeeded` gates on the same `containsKey`), and a
`refresh(k)` issued after the write coalesces onto the superseded token and is
discarded. Don't drop the prescreen to close it, and don't add a
registration-side re-check: closing it there would fix only `refreshIfNeeded` and
leave the manual `LocalLoadingCache` and `LocalAsyncLoadingCache` registrations,
which carry no write-time marker to test, while implying all three were closed.

**A cleared reference is equal only to itself.** `InternalReference`'s `equals` compares referents,
so two distinct references that have both been cleared used to compare equal (`null == null`) and
aliased whenever their cached `System.identityHashCode` values collided. Nothing in production
depends on that: every lookup that can involve a cleared reference (`drainKeyReferences`,
`drainValueReferences`, `evictEntry`) passes the *same* object, which the identity short-circuit
already answers. `referenceEquals` and `objectEquals` therefore require a live referent. The one
property this drops is cross-type null equality between a `WeakValueReference` and a
`SoftValueReference`, which cannot arise since a cache is one or the other; `ReferenceTest
.reference_equality` now asserts each cleared reference forms its own equality group.

**Automatic refresh uses `>` where expiration uses `>=`, and that stays.** `refreshIfNeeded` tests
`(now - writeTime) > refreshAfterWriteNanos()` while `hasExpired` tests `>=` on all three of its
predicates, so with equal durations an entry is expired one nanosecond before it is
refresh-eligible. The `>` matches Guava and is unobservable on a real ticker (only a `FakeTicker`
advanced by exactly the duration can see it). Ruled 2026-08-24: the javadoc's "once a fixed
duration has elapsed" is not wrong enough to reword, and other Guava-compatibility choices rank
ahead of this one. Don't re-raise the boundary or the asymmetry.

**All three refresh registrations key `refreshes` by `referenceKey(key)`, never by the node's own
key reference.** Under weak keys a node's `retire()`/`die()` calls `clear()` on the very
`WeakKeyReference` the node holds, and `InternalReference.equals` compares referents by identity,
so a cleared reference is equal to no reference built later for the same live key. A token
registered under the node's reference and preserved past `retire()` (the `preserveRefresh` exits
do exactly that) is then unreachable: the successor's own `discardRefresh` builds a fresh
reference and cannot match it, and `Policy.refreshes()` skips it because its referent is null, so
the registration and its future are retained for the cache's lifetime and the growth is invisible
through the public view. `LookupKeyReference` holds the key strongly and is never cleared, which
is why the two manual paths always used it; `refreshIfNeeded` now does too. This costs no extra
retention: the loader call and the completion closure both capture the key strongly for the
future's lifetime. The `containsKey` prescreen still reads `node.getKeyReference()`, which is
free and equal to the lookup key while the key is live, so the allocation lands once per
registration rather than on every refresh-eligible read. Don't key `refreshes` by anything the
node owns. Pinned by
`BoundedLocalCacheTest.refreshIfNeeded_weakKeys_preservedTokenIsDiscardable`.

**A refresh completion releases its own token in its `catch`, not only through `remap`.** The
completion's commit normally clears the registration inside `remap`, so the outer `catch` looks
redundant. It is not: `remap` has throw sites that precede every `discardRefresh` — the ticker
read that builds the `ComputeContext`, `requireIsAlive`'s broken-`equals` check, and the ticker
read behind `hasExpired`. A throw there leaves the registration behind, and because
`refreshIfNeeded` gates on `refreshes.containsKey`, that key's **automatic refresh is suppressed
for the rest of the cache's life** from one transient user-component failure. All three
completions therefore mirror their own error branch with the identity-conditional
`refreshes.remove(keyReference, ownFuture)`, which cannot take a successor's token. Note the
throw sites *after* the discard are already safe, since the material tail discards before
returning from the map computation. Pinned by
`BoundedLocalCacheTest.refreshIfNeeded_completionThrows_releasesToken` and its `refresh` /
`refreshAsync` twins, which fail a refresh completion through a throwing `Ticker`.

**A rejected reload is notified even though it was never in the cache.** When the
completion's `compute` declines to install the reloaded value it sets a cause and calls
`notifyRemoval(key, value, cause)` — `EXPLICIT` on the absent exit, `REPLACED` on the reject
exit (a same-instance reload is not notified). So a `RemovalListener` can see a value that was
never a mapping. That is intentional and follows from linearizability: the value was produced,
the cache decided not to keep it, and the listener is the disposal hook, so *not* notifying
would be the surprise — the value would be dropped with no chance to release what it holds.
Deliberately not spelled out in the public javadoc: the surrounding refresh ordering is
vague there (Guava was not linearizable either), and pinning this corner would over-specify it.
Don't "fix" the notification away, and don't treat the two causes as interchangeable.

The one exception is a **query-style no-op**, flagged with `RemapHints.preserveRefresh`:
`putIfAbsent` on a present key, a non-matching conditional `remove`/`replace`, or a
same-instance `compute` return routed through the async synchronous view. These don't
actually mutate the entry, so they leave a racing refresh intact. Both
`BoundedLocalCache.remap` and `UnboundedLocalCache.remap` honor the hint (a same-instance
return with `preserveRefresh` set skips `discardRefresh`); a real mutation still discards.
The unbounded cache used to drop the hint and cancel the reload — the sibling caches must
stay in sync here.

The same hint also **owner-scopes a refresh completion**. When a refresh finishes it
re-enters `compute` to install or reject the reloaded value. Because `refreshes` holds one
future per key, a by-key `discardRefresh` from that completion is safe *only while the
completing refresh still owns the registration*. If a newer refresh has since registered — the
prior token was cleared by a racing write, or by an `invalidate` + `refresh` that re-registers
an `asyncLoad` on the now-absent key — the by-key discard would steal the successor's token,
dropping its freshly loaded value and leaving the cache stale (self-heals only on the next
refresh-eligible read). So each completion path (`LocalLoadingCache.refresh`,
`LocalAsyncLoadingCache.tryComputeRefresh`, `BoundedLocalCache.refreshIfNeeded`) computes
`owned = refreshes.get(kr) == ownFuture` and sets `preserveRefresh = !owned` on its non-commit
exits — reject *and* absent — mirroring the error path, which was already owner-scoped
(`refreshes.remove(kr, ownFuture)`). Honoring the hint therefore extends beyond the
same-instance no-op block: `remap`'s two absent **null-return** exits (`n == null` and the
evicted-retire) and the unbounded absent exit skip the discard when `preserveRefresh` is set.
The absent-**create** exit does not, and must not: installing a value is a mutation, so the
over-aggressive-discard doctrine applies to it like any other write, and a completion that
installs on an absent key is by construction the owner (both manual paths create only in their
owned branch), so its `finally` is clearing its own token. Every one of the twelve callers that
sets `preserveRefresh` either returns null or returns the existing value of a present entry, so
the exit is not reachable with the hint set. Read twice as a hint violation from the sentence
above; pinned now by `BoundedLocalCacheTest.remap_absentCreate_discardsPendingRefresh`.

The `!computeIfAbsent` evicted-retire exit is outside the rule for a second reason: it runs
*before* the remapping function, and the remapping function is the only thing that ever assigns
`preserveRefresh`, so no hint exists yet whatever the caller passed (`computeIfPresent` and
`replaceAll`, the only non-creating callers, pass none). Reaping a dead entry for a caller that
may not recreate it is a purge, so it discards, the same reading as `invalidate`/`clear` below.
Pinned by `BoundedLocalCacheTest.remap_evictedRetire_nonCreating_discardsPendingRefresh`.

On a **reject** exit the hint cannot stand alone. `remap` honors `preserveRefresh` for a
same-instance return only at its `preserveTimestamps` no-op exit, so all three completion paths
set `preserveTimestamps` unconditionally there. `refreshIfNeeded` used to set it only when the
value or write time had changed, which left the one sub-case owner-scoping exists for
(registration superseded, entry untouched) falling through to the material tail. Both halves of
the fix then failed together: the tail's by-key discard stole the successor's token, and its
`setWriteTime` moved the write time the successor's own ABA guard tests, so the successor's fresh
value was dropped too and `expireAfterWrite` was extended by the stale reload's in-flight
duration. Gating the tail's discard on `preserveRefresh` fixes only the first half, which is why
the bounded cache does not carry that gate; `UnboundedLocalCache.remap` does, because it has no
no-op exit to route to. There is no debounce lost: a write that discards a token moves the write
time anyway, and a superseded token blocks re-arming through `refreshes.containsKey`. Pinned by
`RefreshAfterWriteTest.refreshIfNeeded_staleAfterReinsert_preservesEntry`, which reaches the case
through a remove and reinsert of the same value instance, since a dead node's write time is
frozen and the completion's ABA guard therefore sees no change. A rejected completion now leaves
the entry refresh-eligible, so the next read arms a fresh reload instead of waiting out another
interval; `BoundedLocalCacheTest.refreshIfNeeded_skip_discarded` reads quietly for that reason.

The **absent-branch** steal is reachable in **sync mode only**: a successor `refresh(k)` on an
absent key registers an `asyncLoad` without inserting the entry, so the stale completion observes the entry absent;
in async mode the successor's `get` inserts an in-flight future, making the entry present so the
completion takes the reject branch instead. Don't reintroduce an unconditional by-key discard on
any refresh-completion exit.

The same sibling-sync covers a **vanished-key skip**: a non-creating caller (`replaceAll`,
`computeIfPresent`) whose key was concurrently removed hits `remap` with `value == null` and
returns null — a no-op, not a mutation, so it must **not** discard a refresh registered
*after* the removal (that refresh raced nothing). Both caches thread a `computeIfAbsent` flag
(`false` for `replaceAll`/`computeIfPresent`, `true` for `compute`/`merge`) and return early
without discarding on the absent branch when creation is disallowed. A creating caller
(`compute`/`merge`) that returns null on an absent key still discards (over-aggressive, as
above). `UnboundedLocalCache.remap` used to discard unconditionally on the absent+null path,
diverging from `BoundedLocalCache` on the `replaceAll`-races-remove race.

**`invalidate(k)`/`clear()` discard an *absent* key's pending refresh — a purge is not a
query.** A `refresh(k)` on an absent key registers a reload in `refreshes` with **no data-map
node** (sync `asyncLoad` only), so a `remove`/`clear` that reaches `discardRefresh` only through
a present-node lambda leaves the registration alive; its completion then commits (`owned` still
holds, `null == oldValue[0]`) and **resurrects the key past the purge** — sync-only, since an
async refresh-of-absent inserts a physical in-flight entry that `remove` does see (audit A2-F1b).
The fix keeps `remove(Object)` on `data.compute` (not `computeIfPresent`) so an absent key still
enters the lambda **under the bin lock** and discards there — deliberately, because the refresh
*completion* commits under that same bin lock, so doing the discard outside it (after a
`computeIfPresent` miss) races: the completion can insert between the absence check and the
discard. `clear()` purges the whole `refreshes` map up front for the same reason (the node loop
can't reach node-less registrations). This is the over-aggressive-discard doctrine applied to a
purge, which is legitimate exactly as it is for present keys. Note `remove(k, v)` is **not**
extended: a conditional remove that matched nothing (absent or wrong value) is a query-style
no-op that "raced nothing," so it preserves the refresh (like `remove(k, wrongValue)` on a
present key). Don't move the absent-key discard back outside the bin lock.

**A sync `refresh(k)` on an absent key is an isolated side-load; the async view makes it a
first-class in-flight entry — a structurally-forced divergence, not a bug (A2-F1a).**
`LocalLoadingCache.refresh` on an absent key registers `asyncLoad` **only in `refreshes()`** and
leaves the data map untouched until the completion `compute` inserts. So the pending reload is
invisible to a concurrent `get(k)`, whose `computeIfAbsent` sees an absent map and loads **again**
— two loader invocations, and the refresh's value is then discarded with a **phantom `REPLACED`**
notification carrying a value that was never a mapping. The async view
(`LoadingCacheView.tryOptimisticRefresh`) instead side-loads via `asyncCache.get(...)`, inserting
the in-flight future into the data map immediately, so a concurrent `get` joins it (one load) and
the future is joinable / invalidatable / cancellable. This is inherent: a sync cache **cannot** join
an in-progress `computeIfAbsent` from outside the bin lock, so it cannot dedup a refresh against a
`get` the way the async view (whose loads are visible cache entries) can. The `LoadingCache.refresh`
javadoc — "Returns an existing future without doing anything if another thread is currently loading
the value for {@code key}" — holds under the **narrow** reading (another *refresh* in flight; both
siblings dedup via the `refreshes` map) but not the broad reading (any load, including a
`get`-initiated one); that scope gap is intentional and left as-is. Cancellation blast radius
differs accordingly: cancelling the future from async `refresh(absentKey)` cancels the *shared*
in-cache load (all `get` waiters get `CancellationException`, `handleCompletion` removes the entry),
while cancelling the sync refresh future only unregisters the isolated reload and leaves a
concurrent `get`'s own load untouched. Don't try to make the sync cache dedup a refresh against a
`get`; pinned by `RefreshAfterWriteTest.refresh_absent_sideLoad_*`.

**Sync `getAll` discards an unloaded key's refresh only on the sequential path — by design,
and it reflects a real consistency difference, not a bug.** For a key that fails to load
while a refresh is in flight (an absent key whose refresh is still pending): the **sequential**
path (`loadSequentially`, no `loadAll` override) loads via `get(key)` → `computeIfAbsent`, so a
null load *discards* the refresh; the **bulk** path (an overridden `loadAll`) loads via
`loadAll` outside any lock and side-loads the results with `put`, so an *omitted* key never
reaches `compute` and its refresh *survives*. This is not an oversight to unify. A single
`get(k)` that loads null discards (same `computeIfAbsent`), and sequential `getAll` is exactly
N linearized `get(k)` calls — each observes absence *atomically under the bin lock*, which is
the standing that justifies discarding a racing refresh. The bulk path has no such standing:
`loadAll` cannot run under a lock (CHM won't let us hold a bin across the load, and we can't
lock entries), so it is a **non-linearizable side-load** — closer to `refreshAll` than to a
load. Its only linearized moments are the `put` insert/replace points (where we match Guava,
atomic or not); the "this key was absent" observations happen outside any lock and a key may
materialize afterward (we stomp the still-missing keys but do not remove ones that appeared).
With no atomic absence-observation instant, it has nothing to hang a discard on. So the
sequential path discards because it *can* judge; the bulk path preserves because it *can't* —
forcing bulk to discard would impose an absence-decision onto the one path structurally unable
to make one. Don't "fix" the split; don't add `discardRefresh` to the `LocalCache` interface
for it.

**`doComputeIfAbsent`'s new-node path preserves a racing refresh on a weigher/expiry throw —
by design; don't add a `discardRefresh` there.** It discards on a clean value return (a real
mutation) and on a clean null return (the loader's authoritative "no value" verdict, same as a
single `get(k)` that loads null), but a `weigher.weigh`/`expireAfterCreate` throw aborts the
creation without installing anything, so it is *not* wrapped in a token-clearing `finally`. The
rule is coherent: a clean completion (value or null) is an authoritative verdict that discards a
racing refresh; a throw is an aborted op with no verdict, and an independent in-flight refresh
may still legitimately populate the absent key, so it is preserved. This is asymmetric with
`remap`'s create branch, which *does* discard on a throw (`try {…} finally { discardRefresh }`,
the #1970 fix) — but only because `remap` doubles as the **refresh-completion** path and must
self-clean *its own* token on every exit; it can't tell a user compute from a completion, so it
discards uniformly (a doctrine-safe over-discard). That uniform discard is scoped to the exits a
completion can reach — the present-entry `catch` and the create-branch `finally`; the
absent-branch **user-function** throw precedes any materialization and no completion throws there,
so both siblings *preserve* it. `UnboundedLocalCache.remap`'s catch used to discard on that path
too (a blanket `catch (Throwable)` from the "unbounded compute throws" fix whose intent was the
*present*-entry parity); it was narrowed to `if (value != null)` to match `BoundedLocalCache`,
which never had an absent-branch catch (B1-1). `doComputeIfAbsent` is only ever a user load,
so it can afford the correct behavior. Adding the `finally` here would make a *failed*
`computeIfAbsent` also abort an unrelated legitimate refresh — the inverted direction. Pinned by
`BoundedLocalCacheTest.computeIfAbsent_absent_weigherThrows_keepsRefresh` (extends the
already-adjudicated "mapping-function throw doesn't discard is correct" to the weigher/expiry
throw).

**Quick reference — `discardRefresh` across the compute family** (verified consistent, both
siblings). `compute`/`merge`/`computeIfPresent`/`replaceAll` all route through `remap`;
`computeIfAbsent` uses `doComputeIfAbsent` (BLC) or a direct `data.computeIfAbsent` lambda (ULC).

| Verdict | `remap` (compute · merge · computeIfPresent · replaceAll) | `computeIfAbsent` |
|---|---|---|
| clean value (mutation) | discard | discard |
| clean null — present (removal) | discard | discard |
| clean null — absent (no-value verdict) | discard | discard |
| same-instance no-op (`preserveRefresh`) | preserve | preserve (returns existing) |
| user-function throw — present | discard | discard |
| user-function throw — absent | preserve | preserve |
| weigher/expiry throw — absent create | **discard** | **preserve** |

Cross-cutting rules layered on top: non-creating callers (`computeIfPresent`/`replaceAll`) on a
*vanished* key take the `computeIfAbsent=false` early return and preserve; refresh completions and
async-view no-ops set `preserveRefresh` so a stale completion can't steal a successor's registration
(owner-scoping); `invalidate`/`clear` discard an absent key's refresh (a purge, not a query). The
**only** intentional split is the last row — `remap` doubles as the completion path and self-cleans,
`doComputeIfAbsent` is a user-load-only path (and ULC, having no weigher/expiry, has no such case at
all). The absent *user-function* throw row was made uniform by B1-1 (ULC's `remap` catch narrowed to
`value != null`).

The **jcache adapter deliberately does not get this narrowing**: `RemapHints` does not cross the
package boundary, so the adapter's query-style operations (a failed `putIfAbsent`, a NONE-action
entry processor) do discard an in-flight refresh and reset the write time. Adjudicated won't-fix —
see `.claude/rules/jcache-adapter.md`.

## Async Synchronous View

**`AsyncCache.synchronous().asMap()` queries are logical, mutations are
physical.** `containsKey`, `get`, iteration, and `containsValue` treat in-flight
entries as absent (`Async.isReady` / `Async.getIfReady`). But `KeySet.remove`,
`removeAll`, `removeIf`, `retainAll`, and `EntryIterator.remove` operate on the
raw delegate map without blocking on in-flight futures. Blocking everywhere
would invite deadlock and non-linearizable observations; the split is the
inherent sync-over-async tradeoff. `keySet().contains(k) != keySet().remove(k)`
on a loading entry is accepted. `size()`/`isEmpty()` are physical too — they delegate straight to
the backing map and count in-flight entries.

**Value-conditional CAS ops are the exception to "raw", and it is CHM-faithful.** `remove(k,v)`,
`replace(k,v)`, `replace(k,old,new)` and the `compute`/`merge` family **block** on an in-flight
future (resolved *outside* the `compute`, then CAS inside), mirroring CHM where a mutation must
take the bin lock an in-flight `compute` holds: in-flight is absent to *reads* but blocks
*mutations* as if present. Only the key-based removals above are raw. Bulk collection-view ops
split along the same line: `values().removeAll`/`remove`/`retainAll` are value-searches that skip
in-flight via the `getIfReady` filter (like CHM `ValuesView.remove`) so they never block, while
`entrySet().removeAll`'s iterate-argument branch routes through the blocking `remove(k,v)` (like
CHM `EntrySetView.remove` → `map.remove(k,v)` → bin lock) so it can. Don't "fix" that asymmetry —
and a test on the blocking path must coordinate threads (complete the future off-thread), as the
`_async` conditional `remove`/`replace` tests do.

**A logical read can return the value of a superseded future — the synchronous
view is not linearizable.** Each read is a two-step composite (map read, then
`Async.getIfReady` unwrap) with no re-validation between. If the future found in
step one is in-flight, is superseded by a concurrent `put`/`invalidate`, and then
completes, the resumed unwrap returns that future's value even though it was the
mapping at no instant in the reader's window (the completion's identity-conditional
`replace`/`remove` no-ops once the future is unmapped). Only the raw
`AsyncCache.asMap()` view over the futures is linearizable; the synchronous view
reads "the future it found." Double-collecting (re-reading the mapping after the
unwrap and returning null on change) would close it but adds a map re-read to every
sync-view hit for a narrow, non-linearizable-by-design corner. Don't add the
re-check guard.

The same two-step shape is `AsMapView.computeIfAbsent`'s optimistic prior-future
branch, the one exit in its retry loop that does not re-read the mapping. A future
removed and completed while the caller sits between the map read and the `isDone`
test yields that future's value with no mapper call, where an unpaused caller would
have waited on the pending future, looped to `getIfPresentQuietly`, found the key
gone, and computed. Same adjudication: the value came from a future that was the
mapping when it was read, an in-flight mapping counts as present for
`computeIfAbsent` under the coalescing rule below, and `ConcurrentHashMap` promises
nothing about a mapping surviving the return either. Three reports have re-derived
this, each proposing the re-check guard.

**The compute variants adopt the found future too — `synchronous().get(k, func)` /
`getAll` load-coalesce, they do not recompute.** The present-branch of
`LocalAsyncCache.get` returns any in-flight future it finds (no `isDone`/`isReady`
screen) and `AbstractCacheView.resolve` joins it, so the caller's function never runs
and the future's outcome is adopted — a null result, a rethrown *foreign* load
exception, a raw `CancellationException` (`resolve` doesn't unwrap it), or an
indefinite wait on a stuck future. This diverges from sync `Cache.get(k, func)` (=
`computeIfAbsent`, the ConcurrentHashMap family, which recomputes when the prior load
left no mapping) and from the *same view's* `asMap().computeIfAbsent` (whose retry loop
recomputes on null/exception/cancel). That is not a bug: `Cache.get`/`getAll` promise
neither model, load-coalescing is a legitimate policy (Guava's `LoadingCache` coalesces;
CHM does not), and the synchronous view cannot fully emulate the async view's
linearizability regardless — it "reads the future it found" for computes as well as
reads. Whether coalescing is better or worse is perspective-dependent; the point is only
that it *differs*. Don't "fix" `get(k, func)` by routing it through
`AsyncAsMapView.computeIfAbsent`'s retry loop. (Audit F-E4-1, adjudicated by-design.)

**`size()` and `isEmpty()` are physical**, delegating straight to the backing map
(`AsMapView` → `delegate.size()` / `delegate.isEmpty()`). They count in-flight
(still-loading) entries that `containsKey` / `get` / iteration treat as absent, so
`size()` can disagree with what iteration yields. This is the same logical-query /
physical-bookkeeping split and matches the documented "`size()` is an estimate" stance —
they belong on the physical side alongside the mutations above.

## Async Put Re-registration

**`AsyncCache.put(k, future)` re-registers a completion handler whenever the prior
mapping differs by identity, so re-inserting an already-registered future
double-fires `handleCompletion`.** The dedup in `LocalAsyncCache.put` only skips a
*consecutive* same-instance put (`prior == castedFuture`), so `put(k, f1);
put(k, f2); put(k, f1)` leaves two `whenComplete` handlers on `f1`. A single
completion then replays `replace` + `recordLoadSuccess` (and re-invokes
`Expiry.expireAfterUpdate`) once per handler. Accepted — there is no correct dedup:
we cannot inspect a future's already-registered dependent actions, and tracking our
own registration history would be wrong (unbounded, and stale the moment the entry
is replaced). It is benign regardless — re-inserting a specific future instance
after replacing it is unusual, and the second `replace(k, f1, f1)` is idempotent
(no state corruption; `notifyOnReplace` suppresses on identity). Pinned by
`AsyncCacheTest.put_reregisteredInstance_completionRecordedTwice`.

**A cancelled bulk proxy stays mapped, and the completer re-asserts its lifecycle.**
`getAll` installs a proxy per absent key and gives its lifecycle to `AsyncBulkCompleter`
rather than to `handleCompletion`, so a caller who cancels the mapped proxy leaves the
entry in place. Measured: the entry stays mapped and counted while queries filter it, a
`get` in that window adopts the cancelled future instead of starting a fresh load, and
once the load settles `fillProxies` obtrudes the value onto that same future, leaving the
entry holding it and the proxy no longer cancelled. The single-key path differs because
its `handleCompletion` removes an entry whose future completed without a value, so
cancelling there is self-healing.

Accepted 2026-08-16 as the least bad of unsatisfactory options, and the reasoning is what
to re-read before proposing a change. Cancellation does not stop the computation, so the
value still materializes, and a cache that dropped the entry on cancel would have no way
to hand that value to a removal listener for cleanup, where an entry removed while in
flight does have a listener attached to notify. Cancelling says downstream chained actions
may be abandoned, which still happens; removing the mapping directly says the value is not
cacheable. Obtruding is then the cache treating the value as having materialized elsewhere
and re-asserting its own lifecycle over it. Removal-on-cancel is not free either: the
cancellation happens outside the cache, so it takes a dependent action per proxy, and it
would leave the completer's `replace` unable to install the loaded value. `CompletableFuture`
offers no API that satisfies every case and there is no clean decision matrix, so don't add
cancel-aware completion logic to the bulk path, and don't screen the cancelled future out of
`LocalAsyncCache.get`'s present branch, which the adopt-the-found-future decision covers.

## TimerWheel

**Sub-tick advances correctly produce `delta = 0`.** Advancing `nanos` by less
than `2^SHIFT[i]` nanoseconds within one tick (e.g., `0 → 1`) shifts to the same
unsigned tick index, so no buckets are processed. This is correct: no tick
boundary was crossed. (The former `-1 → 0` example is stale since "Fix the timer
wheel wrap bias at Long.MIN_VALUE" — that crossing now lands on a rebased tick
boundary and yields `delta = 1`, also correct.)

**Bucket width is the wheel's resolution. That is the data structure, not a
decision this implementation made,** and it should not be re-raised as though it
were a Caffeine quirk (it has been, repeatedly). A hashed wheel advances in whole
ticks, which is what buys the O(1) insert and delete; an entry due sooner than the
next tick waits for it. Varghese and Lauck, and every wheel built from them, work
this way. See `research-foundations.md` §Hashed and Hierarchical Timing Wheels.

Here the level-0 tick is `SPANS[0] = ceilingPowerOfTwo(1s) = 2^30 ns`, or 1.074s.
An entry due sooner is filtered by every query the moment it expires, but stays
physically resident and unannounced until the clock crosses the next tick.
Measured 2026-08-28 on a fake ticker: a variable expiry of 1ns, 1ms or 500ms
leaves the entry resident with no `EXPIRED` event after `cleanUp()`, while one of
`2^30` ns is reaped promptly, and a deadline set into the past by
`Policy.VarExpiration.setExpiresAfter(k, 0, NANOSECONDS)` is reaped at exactly
`2^30`, not at `2^30 - 1`.

**A cache promises a maximum lifetime, not a scheduling resolution,** which is why
the second is accepted here (Ben, 2026-08-28). The same delay in a system where
milliseconds or microseconds carry meaning, network packet timers being the usual
example, would deserve an argument; nothing in the cache context supplies one.
Supporting facts rather than the reason: `EXPIRE_TOLERANCE` is already 1s, and
`Pacer.TOLERANCE` is the same `ceilingPowerOfTwo(1s)` constant, so the wheel's
granularity and the scheduler's minimum delay match by construction. Nothing
accumulates without bound either, since on a real ticker the backlog is one tick's
worth of expirations and a bounded cache reclaims them by size regardless.

**The one mitigation, considered and not taken.** Sweeping the current level-0
bucket eagerly rather than waiting for the tick would close it, at O(k) in that
bucket's occupancy, and the machinery already exists because cascading sweeps it.
Ben is not opposed to it; no argument has justified trying it. What would have to
be measured first: `expire` detaches the whole bucket onto `pending` and calls
`deschedule` then `schedule` on every node it does not evict, so an eager sweep
pays an unlink and re-link for each not-yet-due entry on **every** maintenance
cycle instead of once per tick, and maintenance runs far more often than the
tick. Realtime systems that need this usually change the structure instead,
making the nearest bucket a heap. Don't remove the `delta <= 0` break without
that measurement.

Two things the earlier wording got wrong, both re-derived incorrectly during the
2026-08-28 sweep before being measured. The bound is one tick, **not** a full
wheel cycle (~68s); the buckets swept are `[start, start + delta]` inclusive of
the current one, since `expire` takes `steps = 1 + delta` and filters per node on
`variableTime - nanos > 0`. And the read path does **not** reap: `getIfPresent`
and `containsKey` leave the entry resident with no notification, filtering it
without removing it. Only the compute path (`get(k, fn)` through `remap`) reaps
an expired entry on access.

The fixed and variable policies therefore differ in eagerness, which is a
property of the data structures rather than a decision.
`Policy.FixedExpiration.setExpiresAfter(Duration.ZERO)` on `expireAfterWrite`
reaps immediately because the pass walks the write-order deque while `hasExpired`
holds, with no tick to wait for.

**`expire` detaches a bucket onto the `pending` sentinel, not into the stack frame.** The bucket
being expired is moved off the wheel so that a recursive call cannot find those entries — that
detachment is deliberate and load-bearing (a rescheduled node can otherwise land back in the bucket
being drained, notably the catch-all `wheel[length][0]`, and be reprocessed). What it does *not*
protect against is a nested operation that reaches an entry by a reference it **already holds**,
e.g. a `RemovalTask` calling `deschedule`; hiding the bucket only defeats traversal-based access.
Before the `pending` sentinel the detached chain was reachable only from `expire`'s stack frame, and
its ends still pointed at the live bucket sentinel, so a nested `deschedule` of the successor the
walk was carrying nulled that node's links and stranded the rest of the chain (permanently — those
timers never fire again, and the walk NPE'd at the capture line *outside* the per-node restore
catch). Reproduced as a real defect; latent since "Variable expiration support (fixes #70, #75,
#141)" in 2017. Three properties keep it correct now, don't regress any of them:
- Detaching itself is **not** about recursion: it stops `schedule()` re-linking a not-yet-due entry
  into the list being drained and reprocessing it, which is unavoidable on the last wheel (a single
  bucket). Don't justify the detach by "hides entries from a recursive advance" — `advancing`
  covers that, and the reprocessing hazard is what the detach actually earns its keep on.
- The chain lives on a **field** (`pending.next`), not a local, so a nested `unlink` of the head
  repairs the walk's anchor through the ordinary path with no special case. The loop must re-read
  `pending.getNextInVariableOrder()` each iteration rather than carry `next` across the callback.
  This is the leg `advancing` cannot cover: a nested `deschedule`/`reschedule` arrives via
  `drainWriteBuffer` → `RemovalTask`/`UpdateTask` and never calls `advance`. Verified by keeping
  `advancing` while restoring the stack-frame detach — the regression test fails identically.
- Both lists stay **circular**. A linear or sentinel-less chain breaks `reschedule`'s
  `getNextInVariableOrder() != null` scheduled-check (a tail with a null `next` reads as
  unscheduled, so it gets linked into a bucket while still chained) and makes `unlink` a silent
  no-op on the tail. The invariant is *scheduled ⟺ non-null links*, which is also why the in-flight
  node's links are nulled before it is processed. A self-closing ring fixes the live-bucket splice
  but **not** the stranding — verified by building it and watching the regression test still fail.
- `advance` **defers while `advancing`**. A recursive advance would append its bucket to the same
  list and splice the combination into its own bucket, and — the leg that matters more — it moves
  `nanos` forward underneath the in-progress caller, which then rewinds or skips buckets and leaves
  entries permanently unexpired. It returns before touching `nanos`, so the nested call is a true
  no-op rather than skipping a time span. The predicate must be the explicit flag, **not** "is
  `pending` non-empty": the outer walk empties `pending` the moment it unlinks its last node, so
  the inferred version lets a nested advance through in exactly that window. That was a real
  defect in the first cut of this fix, found by the fuzzer in 559 runs.

Pinned by `BoundedLocalCacheTest.maintenance_recursive` (a removal listener on a same-thread
executor invalidating a sibling and calling `cleanUp()`).

**`getExpirationDelay` returns when a bucket is next *flushed*, not when an entry is due.** A flush
either expires the entry or **cascades it towards a finer resolution**, so the wake-up is needed even
when nothing is due at it. `expire` covers ticks `[previousTicks, previousTicks + delta]`, so a bucket
at offset *b* flushes at `(b << SHIFT[i]) - (nanos & spanMask)` and the **current** bucket — whose tick
has already passed — flushes at offset **1**, one tick out, *not* a full `SPANS[i]`. Both places that
compute this must use that offset-by-one:
- The main scan clamps with `Math.max(1, j - start)`. It previously fell back to a whole `SPANS[i]`
  when the current bucket was occupied *and* returned before probing `start + 1`, hiding an event one
  bucket ahead that then fired up to a span late (68.7s / 1.22h / 1.63d on wheels 1–3; wheel 0 is
  absorbed because both values are ≤ `Pacer.TOLERANCE`). Reachable because `findBucket` chooses the
  **wheel by duration** but the **index by absolute time**: a duration within `nanos & spanMask` of
  `SPANS[i+1]` wraps a full revolution onto the current bucket, so a far-future entry masks an
  imminent one. Don't restore the `delay > 0 ? delay : SPANS[i]` ternary — with `buckets >= 1` the
  result is always positive, so the guard is dead.
- `peekAhead` checks the higher wheel's **current** bucket as well as `ticks + 1`; both flush at the
  same instant. This leg is benign on its own — only wrap-arounds land in a wheel ≥ 1 current bucket
  (measured: over 300 random wheels no entry there is ever due before that wheel's next tick), so the
  missed flush would only re-file an alias. It is honored anyway so the derivation "current bucket ⇒
  only aliases" isn't load-bearing, and so the contract is assertable.

Partial-drain remainders are **not** affected — `expireVariableEntries` re-arms via
`PROCESSING_TO_REQUIRED`, so the backlog never depends on this delay. Pinned by
`TimerWheelTest.getExpirationDelay_occupiedCurrentBucket` and by `getExpirationDelay_fuzzy`, which
asserts **exact equality** against an oracle scanning every bucket of every wheel. That fuzzy
assertion was previously vacuous — it guarded the assert with its own condition and compared an
absolute `variableTime` against a relative delay — which is why the defect survived the fuzzer.

**Interner `drainKeyReferences` does not need a value-identity check.** Unlike
`drainValueReferences`, which guards against the value being replaced on the
same node, keys on Interned nodes never rebind. If two hash-colliding weak keys
are both cleared and aliased via `WeakKeyEqualsReference.equals` (which becomes
`null.equals(null) == true` post-clear), both queue polls still complete and
both nodes still evict — only the attribution is swapped, which is unobservable
(uniform `Boolean.TRUE` values, no listener on the interner).

## Serialization

**The `SerializationProxy` captures configuration only, and intentionally drops
`executor`, `scheduler`, and custom `StatsCounter` suppliers.** Threads and
executors are runtime state, not serializable configuration; the deserialized
cache uses the defaults (common pool, disabled scheduler, default counter),
matching Guava's proxy behavior. Don't propose capturing them — the actionable
gap is only the `Caffeine` class javadoc, which overstates "retain all the
configuration properties."

**Serialization is same-version only, and that is not a gap to close** (ruled
2026-08-15). The library never promises cross-version compatibility, and neither
does Guava; a promise of that kind has to be offered by the contract rather than
expected of it. So a finding of the shape "a stream written by an older release
deserializes into a broken or silently wrong cache" is closed on principle, not
on reachability or on whether the failure is loud. No `serialVersionUID` bump, no
`readObject` defaulting, no wire-format tolerance shim, no release note, no
javadoc qualifier. This covers `SerializationProxy`, `CaffeineConfiguration`, and
any serializable type added later.

**Proxy field names and sentinel values are still wire format within a version.**
Two changes broke cross-version streams: the `loader` → `cacheLoader` rename
(3.0.4) and the `0` → `UNSET_INT` sentinel change for the three duration fields
(3.2.4, the zero-duration fix). The reason to know this is not compatibility but
debugging a bug report: streams from ≤ 3.2.3 carry literal `0` for unset
durations, and a field *absent* from an old stream deserializes to the JVM
default (`0`/`null`), because field initializers do not run during
deserialization. Golden streams written by real released jars are kept with the
serialization audit's local records (`audit-serialization-repro`).
