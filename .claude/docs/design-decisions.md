# Design Decisions

Intentional behavior and review invariants, with their rationale and evidence. Read the
relevant section before changing a mechanism; use [ruled-out](ruled-out.md) for adjudications.

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

**`FrequencySketch.reset()`'s pause is accepted, and chunking it is the wrong trade.** The reset
halves every counter under `evictionLock`: 0.29 ms at a 100k maximum, 2.0 ms at 1M, 32 ms at 10M,
238 ms at 100M. It is rare in proportion to the cache, since `sampleSize` is `10 × maximum` and the
reset fires when that many increments have been recorded, so a 100M cache resets once per billion
recorded reads, about 0.24 ns/read amortised. Amortising the pause means chunking it, which lowers
eviction quality by querying a half-reset sketch. The default async eviction hides the pause
entirely, and on a caller-runs executor the worst case is still cheaper than the misses the sketch
prevents. The `count += Long.bitCount(table[i] & ONE_MASK)` odd-counter correction is a
cross-iteration reduction and is what stops the loop auto-vectorizing; dropping it is much faster
but loses the correction, and no argument has justified that. SIMD is the answer once it is
available. It has produced no user reports and does not show in `GetPutBenchmark`.

**A weak-key lookup allocates, and that is accepted.** The weak-key node factories build a
`LookupKeyReference` per lookup (20.0 ns/op and 24 B/op against 2.6 ns/op and 0 B/op for strong
keys); it is handed to `ConcurrentHashMap.get`, so escape analysis cannot remove it. Avoiding the
allocation means caching one wrapper in a thread-local and mutating it to set and unset the
referent around each lookup, which pins that instance to the thread. That idea was raised and
rejected in issue #294 over virtual threads, where an instance per virtual thread is unbounded,
and over classloader pinning. A young-generation allocation is the accepted trade.

**The policy weight is 64 bits, packed into the node's `metadata` word.** Reordered update tasks
are normal: the value is written under the node monitor but the task is queued after it is
released, so a second writer can queue first. The node's own field then walks past the true value
and back, which the design tolerates. What it cannot tolerate is truncation. Once the intermediate
sum leaves the int range, a `transfer` copies the truncated value into the long region totals, and
nothing replays that copy: the node settles at its real weight while `windowWeightedSize` or
`mainProtectedWeightedSize` keeps a permanent 2^32 residue. Measured 2026-09-08 with weights near
`Integer.MAX_VALUE`, the window then reads as permanently over its maximum, `evictFromWindow`
drains it every cycle, and every later arrival goes straight to probation, so the admission window
is dead for the life of the cache. The released 3.2.4 and 3.2.5 reproduce it, so it is not new.
A sign test on the transfer does not close it: half the wraps land positive, where the value is a
plausible weight. The excursion is bounded by the buffered tasks for one node, so about
`WRITE_BUFFER_MAX x Integer.MAX_VALUE`, or 2^45; 34 bits was the most a witness reached.
`policyWeight` therefore holds the low half and `metadata`'s spare bits the high half, which costs
nothing in layout (JOL over all 147 node classes is unchanged, where a plain `long` field grows the
16 strong-value weighted classes by 8 bytes). Pinned by
`BoundedLocalCacheTest.put_reorderedUpdates_leaveNoRegionResidue`, which orders two writers through
a removal listener on a direct executor. Don't re-narrow the field, and don't guard the transfer
on the sign instead.

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

**The climber has three size tiers, in the configured maximum's native units.** Weight units
are deliberate: the weighted stress track included about 200 entries of 25–100MB in a 10GB
cache and scored 14 wins / no losses. Many tiny entries under a small weight bound land on
reactive, which remains safe there.

| Maximum | Controller | Reason |
|---|---|---|
| ≤512 | Slow reactive: grow first, minimum initial step 2, decay 0.995 rather than 0.98, period stretch capped at 4× | Integer windows and noisy samples make an initial shrink a no-op and prevent later reversal. |
| 513–4096 | Standard reactive | Density was near-neutral below ~2048 (+0.12–0.24pp), but lost up to 10pp on corda+loop at 513–1024. |
| >4096 | Goal-audited density | Added about 125pp across the large cells of the 48-trace set while accepting small frequency-trace regressions. |

The gates are `SLOW_ADAPT_THRESHOLD` and `DENSITY_THRESHOLD`; slow tuning uses
`SLOW_ADAPT_DECAY_RATE` and `SLOW_ADAPT_RATIO_CAP`.

**Density compares regions within one sample.** It uses
`ln((windowHits / windowMaximum) / (mainHits / (maximum - windowMaximum)))`, with a step
proportional to error (`DENSITY_GAIN = 0.03`), capped at `MAX_STEP_FRACTION` (30% of maximum).
This avoids the cross-sample workload swings
that obscure reactive gradients on a flat hit-rate curve, but it is average resident density,
not marginal value. Its equilibrium depends on the starting window; ghosts would supply a
different signal but were rejected for cost. Extending density below 4096, even with the probe
machine, lost 1.3pp on `cs@563`. Kickoff, regret, anneal, and wide-start variants traded one
small-cache trap for another rather than removing the bias.

**Blind corners require experiments.** A region with fewer than `requestCount >>
MIN_SIGNAL_SHIFT` hits (~0.1%) supplies too little evidence to hold position. Pure density
can pin at the floor about 28pp below LRU at any size. Probe only when the starved region is
small (≤¼ maximum), or the whole sample is dead; probing for a starved large main region
destroys the functioning window on corda. Deep refractory rungs increase both reach and
commitment, while cheap early adjudication protects thin-signal floors. Failures undo fully
and back off (`PROBE_BACKOFF_INITIAL` 16 through 64 samples), costing about 1pp on short
w50/S1 traces and amortizing toward
zero with longer runs. Preserve the current router and ownership rules in
[climber review constraints](#climber-review-constraints) and [hill-climber](hill-climber.md) §4.

The evidence behind the asymmetric verdicts and crash prices remains relevant:

- Starvation-walk interior noise pricing is `min(max(5pp, 3×rateDeviationEma), 15pp)`.
  It recovered 8.2pp on the dosed-mixture trap; dropping the cap lost 0.6pp on `metronome`.
- Audit crashes retain the depth bound and buy persistence in time. The first crash is cheap;
  an already-crashed equilibrium's retry tolerates two below-bar samples and aborts on the
  third. This raised the constructed moat from 41.97 to 44.3; a 22-cell real-corpus scan found
  no echo. Giving every walk tolerance failed `mixture_d025` / `mixmod` on short traces.
- Sharing crash state paired independent pulses into rung 64 / clock wait 128 and a
  130-sample floor pin, 6pp below LRU. Non-crash endings retire only their owner's streak;
  an audit confirm alone also clears starvation state when resetting that ladder. Pins:
  `audit_budgetExpiry_leavesTheStarvationLedger` and
  `walkStep_reversalThroughBase_leavesTheOtherLayersLedger`.
- An up-probe prices the capacity it takes from probation, frozen at arm. Main's protected
  average vetoed useful positions on `trickle` (14–17pp below its own engine); live probation
  became enriched by the walk's demotions and vetoed every escape on `demoflood`. Down-probes
  keep the average comparison. The low-hit-rate `lowmix` trade remains a gate sentinel, with
  no real-trace echo across the defended corpus.
- Crash veto precedes adjudication, so a destroyed region cannot win by a density ratio
  against another region earning nothing. A neutral verdict is not a success (`S3`). The
  current reversed-confirm rule is in the checklist below; older blanket reward rules no
  longer describe the machine.

**The density floor is 2% (`WINDOW_FLOOR_FRACTION`), with an upward clamp from below it.**
The initial window is 1%, so
a clamp that only blocks further shrink can wedge below the intended floor. At size 600 a
0.5% floor held only three entries and the recency phase scored about 23% versus 44% optimum.
The 2% large-cache safeguard cost at most 0.35pp on frequency-optimal workloads; kickoff and
EWMA-regret escapes lost up to 8pp on w50 or only moved transiently before retrapping.
Re-run corda+loop at 4097–8192 before lowering the floor. Resizing resets probe state and
`sample.previousHitRate`, so the new geometry is not judged against an old sample.

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

The retained table's reset cost after a large shrink is accepted. After a 1M-to-1K
shrink, an 8 MiB table took 1.94 ms under `evictionLock`, about 194 ns per increment amortized
against 0.19 ns when table and maximum match. Cost scales with peak/current ratio: a 10× swing
was negligible, while ~1000× was visible. This is reachable through unweighted
`Policy.Eviction.setMaximum` as well as weighted retracking. Hysteretic shrinking was declined
because it conflicts with per-addition weighted retracking and loses the admission history.

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

**The climber is held until the cache is half full, and the gate is residency, not the sketch's
existence.** `climb()` skips `determineAdjustment` while `weightedSize() < (maximum() >>> 1)`, discarding the
sample through `WindowClimber.discardSample` once it fills, and still resets it outright when the
sketch is uninitialized. A cache below that
line is not evicting, so its sampled hit rate says nothing about the window/main split, and the
sketch's `sampleSize` caps the sample period, so a sketch sized for less than the maximum reads
that meaningless signal on a compressed cadence. `Caffeine.initialCapacity(n)` is one way in (the
generated constructor calls `ensureCapacity(min(maximum, n))`) and growing `maximum` through
`Policy.Eviction.setMaximum` is the other. Measured 2026-09-08 on `product.Caffeine`: a
`maximumSize(400_000)` cache hinted at `initialCapacity(1000)` scored 29.27 on `arc/S3` against
41.04 un-hinted, `arc/DS1@1M` 8.76 against 12.48, `arc/P3@100k` 33.50 against 40.43, and the
window sat at 47–80% of the maximum forty million operations later where the un-hinted arm rested
at 26%. The sketch reinflates on schedule at half fill; the window it drove to does not come back,
which is why the old acceptance ("a transient that self-heals as the cache fills") was wrong. Don't
weaken the gate back to the sketch's initialization alone, and don't latch it. The unlatched form
is what closes the `setMaximum` growth case, and it is two field reads under `evictionLock`. Pinned
by `BoundedLocalCacheTest.adapt_partiallyFilled_holdsWindow` / `adapt_filled_climbsWindow`.

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
  volume. The duration of the excursion does not change this ruling.

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
toward the probed direction, a slow creep toward the corner boundary. Pinned by `probeEnding_adjudication_wrongSignFailsAndDoubles` (the commands sum to
the distance).

**Async load completions replace quietly.** A completed future's `handleCompletion` (and the
bulk `fillProxies`) calls `replace(..., quietly= true)`: the UpdateTask finalizes the weight and
expiration but skips `onAccess`'s sketch increment and climber hit counters. The entry already
paid its miss at insertion; counting the completion as an access doubled the key's per-load
admission frequency and window-attributed one synthetic, write-buffer-lossless hit per miss —
measured at up to −38.6pp (w50) on the density climber and −12.7pp (corda+loop stress @ 512) on
the reactive climber. A
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

**In-place reloads record a climber miss.** `computeIfAbsent`, `compute`, `put`, and `putIfAbsent`
reuse a node when its entry has expired or its value was collected. Their `UpdateTask` uses
`Access.RELOAD` to increment the sketch and record the miss an `AddTask` would record after
physical removal. `replace` rejects dead entries. `Access.QUIET` takes precedence for internal
refresh completions.

For caches with size eviction, a nonquiet reload must use `UpdateTask` even when the weight is
unchanged and timestamps are within tolerance: the read buffer always records `Access.HIT`.
Access expiration without write expiration exposed this missing case in `remap`;
`expiredRemap_recordsClimberMiss` covers both expiration modes.

Steady contamination can cancel in cross-sample rate differences, but it biases the density
tier's within-sample regional ratio. In an earlier synthetic reuse study (maximum 512, reuse
gap equal to expiry), misattribution moved the converged window from 402 entries to 5 across
all seeds and both maintenance lags. Nearly all false hits were in main, and reloads rose from
4.2% to 13.6%. Those measurements concern the earlier reload-accounting fix, not the
same-weight `remap` case. No end-user hit-rate loss has been established for either case.

The simulator's `product.Caffeine` uses neither expiration nor reference values, so its gate
cannot exercise this path. Pins: `BoundedLocalCacheTest.expiredReload_recordsClimberMiss`,
`expiredRemap_recordsClimberMiss`, `expiredPut_recordsClimberMiss`, and `put_recordsClimberHit`.
They also check the sketch increment, which a quiet update would incorrectly suppress.

**~1% random admission of rejected candidates.** The TinyLFU admission filter
randomly admits ~1% of candidates that would otherwise be rejected. This provides
HashDoS protection by making frequency estimation attacks non-deterministic.

## Climber review constraints

The current machine and its rejected alternatives are described in [hill-climber](hill-climber.md)
§4–6. Preserve these distinctions when changing related branches:

- **Recovery takes samples.** A density sample is 4× maximum requests; typical log error of
  1.5–2 yields steps of 4–6% of maximum. Descending from an 80% window takes 13–16 samples,
  or 52–63× maximum requests. On ten frequency-optimal cells, 41 samples recovered 73% of
  an 80% plant, two samples recovered 2%, and 4× replay cut the deficit 3–8×. Density recovered
  roughly twice as much as reactive. Shortening the period needs a measurement of its jitter cost.
- **Reactive reversal substitutes for a floor.** The reactive tier can reach a one-entry
  window; `Reading.floor`'s 2% floor belongs only to density. A banded reactive law without a
  floor drove corda from 30.96 to 1.13. Any change that sustains its runs must revisit the floor.
- **Setpoint density has an accepted burst bias.** Budgeted transfers can leave actual window
  occupancy above `windowMaximum`, inflating its density. Candidates still pass `admit` and
  `LocalCacheSubject` checks the bound at quiescence. Do not substitute occupancy without measuring.
- **References differ by purpose.** Up-probes freeze probation density and its sample length
  at arm, scaling the baseline to the live sample length; down-probes compare one sample.
  Starvation-walk deviation pricing stays live. Audits freeze their rate reference and use a
  raw-sample streak plus one beat-base test. The rail's `3×deviation` price is separate.
- **Each layer owns its state.** Crash streaks and ladders belong to their walk. Non-crash
  endings clear only that layer's streak, so alternating crash/budget endings do not repeatedly
  get tolerance. Crashes never double the audit clock. Stillness alone advances that clock;
  moving samples decrement its run by one rather than reset it. Only interior-chosen directions
  update the alternation bit; a due audit pre-empts a refractory hold.
- **Walk exits use different bars.** Audit crash abort is a level test against the arm's frozen
  rate, with 5pp depth capped at `AUDIT_BAR_FRACTION` of that rate. Reversal is a first-difference
  test, priced by `AUDIT_BAR_FRACTION × max(baseHitRate, noiseBand)` under the same absolute cap.
  That fraction is `0.15 × VETO_MARGIN_SCALE`; widening the bars failed the measured controls.
  A floor-based walk can miss `crossesBase` through integer rounding; budget expiry gives the
  same FAILED price and full undo, pinned by
  `WindowClimberTest.walkStep_floorBasedWalk_endsAtBudgetWithAFullUndo`.
- **Anchor discard and metric reset stay paired on crash-scale shifts.** The inherited EMA is
  about 80% old regime. A distant crash keeps the claim and reference. On-anchor `resync` runs
  even during return drain; planting waits for both a walk and its pending undo to finish.
  Symmetric claim aging lost 11.9pp on the ramp control; one-sided aging disarmed the rail.
- **An audit judges against the position it leaves.** `Walk.baseSmoothedRate`, not `Anchor.rate`,
  is its baseline (`ghostclaim` 31.2 → 48.5; `cp_w100` +2.0). Discarding a claim on a still swing
  loses retreat recovery (`moat_h3000`, −0.7 to −1.9 on all eight seeds); resetting the metric
  while keeping the claim sends the rail to a dead anchor (`ghostclaim_p30` 41.2 vs 49.2).
- **A return retests its frozen claim only at its original position.** `RETEST_SETTLE = 2`
  avoided the seed losses of 1 and 3; `ghostclaim_p35/p40` improved 31.7 → 33.9 and 31.4 → 33.5.
  Retest follows `anchor.returning`, rechecks `isAt`, and is cleared by both `discard` and `plant`.
  Clearing only on stand-down misses budget-expired returns and confirmed-position replants
  (#2002). Do not retest a claim after its anchor has moved onto a position the return never reached.
- **A reversed starvation confirm is a completed experiment.** Escalate its ladder and hand
  to density with zero refractory; rewarding it restarted a recurring dither (668/881 confirms,
  `bandtrap2` −4.4pp and an absorbing `shallowmoat`). Accepted costs include `arc_DS1` −0.7,
  `deadphase` −0.2, and `norank_rep_r6` seed 3 falling 41 → 20 while seven seeds were unchanged.
  The unlanded `wedgeshift` guard needs a holdout. Only deepest-commitment, audit-grade confirms
  park; other starvation confirms keep their density handoff.
- **Park and refractory scopes remain narrow.** A parked audit covers its own walk's crash-scale
  move, but an external shift and undo arrival still judge the park (`demoflood` −1.9 if widened).
  Only a starvation undo arms the starvation refractory. Removing audit-undo rearming bought
  `widepin` +5.1, `rep_r6` seed 3 +5.4, `shallowmoat` +1.1 for `metronome` −0.9,
  `balloonflip` −0.3 and `cp_w050` −0.55.
- **Repeat confirms deepen rather than reward.** Per-direction memory tracks the farthest
  confirmed window; starvation failure/crash clears it, audit endings and anchor discard do not
  (`absolve_p8` 27.95 → 46.3). A park's first audit follows its confirming direction only while
  the park and smoothed-rate guard still hold. Consume that exception at arm; later audits
  alternate. Raw-rate substitution fails `absolve_p12`; unguarded direction costs `moat_h5000`
  1.75pp and fails `climbtrend_up` / `whisper_mod_p6`.
- **Main-space experiments already priced the alternatives.** Across 276 cells, plain-LRU main
  lost 93.1pp net (mean −0.337); SLRU won >1pp on 40 cells versus nine, 38/46 traces preferred it,
  and N=3 had no sign flips. Partial promotion gates were worse than either extreme (−25.7/
  −20.3pp tails), because sparse protected entries stay outside probation's victim pool.
  The 80/20 split and one-hit promotion beat alternative constants; a perfect per-cell oracle
  was only +0.21pp after the max-of-N noise floor, with winners changing across sizes on all
  46 traces. Wrong constants cost −6.16pp to −25.72pp. Probation stays near 19.8%; the density
  window borrows protected capacity over roughly [2%, 80.2%].

Audit confirms park without a parting steering step; starvation confirms normally do neither.
The room check and walk use `Ladder.stride`. Before changing floor-crossing arithmetic, use
seeded admission comparisons to distinguish a repair from a shifted probabilistic outcome.

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

**The flag is conservative rather than exact, so the write re-tests the mark.** Reading
before the store rules out one direction only. A future observed as ready was ready at the
install, but one observed as unready may still complete before the install evaluates it, and
the stale flag then charges as an update a creation the install already accounted for.
Measured on the shipped defaults with an ordinary future and an `Integer` key, four to nine
puts in every 200,000 take that path, and a key whose `hashCode` completes the future makes
it deterministic, yielding a 59s entry for the 1h/1m `Expiry` above. No read point fixes it,
since readiness is monotonic and neither side of the store is the observation the install
used, and narrowing the window is not a repair. So `replace` asks the entry instead. A quiet
write, which only a completion performs, preserves the variable time when the entry no
longer carries the sentinel, and still finalizes the weight and the write time. Nothing else
can have cleared the mark, because the two read-extension paths above return early on it.

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

**A removal's cause is attributed at the instant that removal is performed, and `clear()` is not
an exception to that.** `clear()` reads the ticker once and passes that `now` to `removeNode`,
which amortizes the read across the entries it removes under the eviction lock and calls a
user-supplied `Ticker` once rather than once per entry while holding it. It is a cost decision,
not a point-in-time guarantee, and it cannot become one: the sweep breaks out as soon as
`writeBuffer.size()` reaches `WRITE_BUFFER_MAX / 2`, and every straggler goes through the public
per-key `remove(key)`, which reads its own clock. Measured on 200,000 entries with the deadline
crossed after the sweep began, one writer running: the prefix covered by the captured clock was 5
and 18 entries in two of three trials, with the remaining ~199,990 attributed `EXPIRED` by their
own reads. Uncontended, the same call reported all 200,000 `EXPLICIT`. Across methods the gap is
the same thing from outside: with a ticker stepping 1 ms per read and the deadline set midway
through, `invalidateAll()` reported 1,000 `EXPLICIT` where `invalidateAll(keys)`, a loop of
independent `remove(key)` calls, reported 250 `EXPLICIT` and 750 `EXPIRED`. So cause counts are
not comparable between the two bulk calls and are not stable within one `clear()`. The consequence
is confined to the removal cause and `evictionCount`, both best-effort. Don't read the captured
`now` as a snapshot: making it one needs an internal removal variant carrying it through the
straggler path, and dropping it puts a per-entry ticker call back under the eviction lock.

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

**Expiration scans re-check their captured tail after callbacks.** A nested cycle can remove or
transfer `last`, leaving entries cycling indefinitely under `evictionLock` because reorders do
not consume the eviction budget. After `evictEntry`, a scan with work remaining checks tail
membership (including queue type for access order). If absent, it exhausts the budget so
`PROCESSING_TO_REQUIRED` starts a fresh scan. A scan that reached its own tail needs no re-arm.
The timer wheel instead uses its field-backed `pending` sentinel and rejects nested advances
with `advancing`. Pins: `maintenance_recursive_accessOrder_removedTail` and
`maintenance_recursive_writeOrder_removedTail`.

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

**Checked-exception conversion restores interruption.** Kotlin, Scala, and Groovy callbacks
can throw `InterruptedException` through `Function`, `BiFunction`, or `CacheLoader`; checked
exceptions are a javac rule, not a JVM constraint. `Caffeine.toUnchecked` rethrows `Error`,
returns `RuntimeException` by identity, restores the interrupt for `InterruptedException`
(JDK interruptible waits clear it when throwing), and otherwise wraps in `CompletionException`.
Conversion is used by loader chains, catch-commit-rethrow for COLLECTED/EXPIRED recomputation, and
`AsyncBulkCompleter`. Absent-key paths and `UnboundedLocalCache` propagate unchanged and
need no conversion-side repair.

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

**Value-bearing user callbacks propagate; fire-and-forget callbacks are guarded.**
`Ticker`, `Weigher`, `Expiry`, and loaders return information the cache needs, with no safe
default. Stats, scheduling, and listener wrappers can instead return an empty/default result.
Do not add containment for broken equality, hostile futures, or JVM errors. The precedent is
to fix the user component (as Quarkus did for its broken future), not add defensive code or a
must-not-throw note to `Ticker`.

A value-bearing throw can land after a commit. Examples include `AddTask` / `UpdateTask`
updating policy totals before a ticker read, and refresh/async prologues reading `statsTicker`
before cleanup. Such failures can skew accounting or strand tokens/proxies; those mechanisms
are accepted under this boundary, not denied. Containment often invokes the same broken
component. Preserve existing targeted cleanup, including refresh completion's own-token catch;
the boundary is not a reason to remove it.

**Concurrent standard-future obtrusion is the supported exception.** Between readiness checks
and `join`, an `obtrudeException` can make `Async.getIfReady` throw. A plain-future stress run
produced 3.5M throws in 1.28B query rounds. Its narrow `CancellationException` /
`CompletionException` catch now returns null as the method promises, including to maintenance
under `evictionLock`; other hostile-subclass exceptions propagate. Completion handlers are
one-shot, so obtruding after success can leave a physical entry that queries filter. That is
accepted. `AsyncBulkCompleter.failProxies` removes before obtruding.

**A maintenance throw defers buffered work; it does not drop it.** The final CAS can settle
`PROCESSING_TO_IDLE → IDLE` on a throw, leaving unprocessed tasks in the buffer. Later writes
re-arm, and a full buffer forces inline assist. Do not force `REQUIRED` or add a re-arm to
`performCleanUp`'s throw path. The inline fallback's own write task is different: it must run
in `maintenance`'s `finally`, preserving the repair for that task's former loss.

**Executor rejection and lock contention need different epilogues.**
`scheduleDrainBuffers` deliberately does not follow rejection-fallback maintenance with
`rescheduleCleanUpIfIncomplete`. The rejecting executor cannot use the common-pool arm; a
pacer would submit to the same broken executor and its fire-time rejection is swallowed by
the JDK Delayer. Recovery relies on later activity, as documented for discarded/never-run
tasks. A drain that merely bounces off `clear`'s held lock still has a healthy executor and
must reschedule. Do not generalize the rejection exception to that case.

## Buffers

**`MpscGrowableArrayQueue` is a shaded JCTools port and is not wrap-safe; that is accepted.** At
`pIndex == cIndex == 2^63 - 256` the "is there room" sum overflows and a resize is selected on an
empty maximum chunk, stranding the odd producer-index marker so every producer spins at 100% CPU. It
needs 2^62 offers on one never-reset queue (millennia at a measured 10-20M put/sec), and upstream
master is identical. Don't harden it: `(pIndex - cIndex) < bufferCapacity` does not suffice
(`producerLimit` stores the same wrapping sum), and rolling the index back after `producerBuffer =
newBuffer` strands the consumer, which is worse than the spin.

**`StripedBuffer.offer` treats `FULL` as success and only expands on `FAILED`.** A failed CAS is
evidence of real contention between threads, which striping fixes; a full buffer only means the
drain is behind, which it does not. Routing `FULL` into `expandOrRetry` would let one thread's
routinely-full buffer grow the table and allocate stripes nobody contends for. So a `FULL` home
stripe returns `FULL` without probing a sibling, which is what makes a stalled stripe skip that
thread's reads until it drains. Both are intended: the `FULL` return is the signal that tells
`afterRead` to drain. Don't make `FULL` probe for another slot.

**A thread's starting stripe never moves.** The probe is re-derived from the thread id on every
`offer`, so the incremental hashing in `expandOrRetry` varies the slot only within one call. This is
forced, not chosen: `ThreadLocalRandom.getProbe`/`advanceProbe`, which `Striped64` uses to
permanently move a colliding thread, are package-private to `java.util.concurrent`. Don't "restore"
the convergence search; the only alternative is a `ThreadLocal` on the read hot path.

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
published. Strong-value setters also use a store-store fence, to order the value before
subsequent timestamp stores as required by the [expiry read protocol](#expiration);
they have no reference-clearing operation to order.

**Weigher.boundedWeigher** wraps all user weighers and enforces `weight >= 0` at
runtime via `requireArgument`.

## Concurrency

**`maximum` and `weightedSize` have a plain reader and an acquire reader, and no public-facing path
may use the plain one.** `AddMaximum.addPlainAndAcquireField` emits `maximum` as a `VarHandle.get`,
which guarantees bitwise atomicity only up to 32 bits, so a plain read of the 64-bit field can tear
against `setMaximum`'s release write on a 32-bit JVM. The plain reader is for callers already
holding `evictionLock`; everything reachable from the public API takes
`maximumAcquire`/`weightedSizeAcquire`. There is no way to assert an access mode in a test, so this
is a review-time invariant: check the call site's lock state, not the accessor's name. The three
duration fields need no such care, since `addAcquireReleaseField` emits only an acquire reader.

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
The one exit that *preserves* — an absent-branch **user-function** throw (both siblings; ULC guards its catch with `value != null`) — a refresh completion never
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

**CHM never hands back a stored key, so the unbounded cache notifies with the caller's
instance.** `compute` and `computeIfPresent` pass the remapping function the key the caller
supplied, and the table keeps the instance it already held; there is no API that returns the
stored one. `UnboundedLocalCache` is a thin wrapper over that map, so its `Object`-accepting
removals notify with the argument, where `BoundedLocalCache` reads `node.getKey()` and Guava's
`LocalCache` reads `entry.getKey()`. A `RemovalListener` therefore receives an equal key, not a
particular instance. The visible consequence is a cross-type equal key: with a stored `HashSet`
and an equal `TreeSet` passed to `remove(Object)`, `remove(Object, Object)`,
`keySet().remove(Object)` or `entrySet().remove(Object)`, all eight bounded and unbounded cells
removed the entry, but a correctly typed listener ran on every bounded one and none of the
unbounded ones, where the checkcast throws and `notifyRemoval` logs it as a listener exception.
Recovering the canonical key there needs an O(n) scan or a per-entry node, which is the structure
the unbounded cache deliberately does not have, so this is a boundary rather than a repair.
Identity preference in cases that lose no notification is separately unspecified.

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
on those methods as evidence that it should be swallowed.

**`expireAfterAccess` + `expireAfterWrite` together is discouraged.** Inherited from
Guava for compatibility. The two timestamps are independent; whichever has the
shortest remaining duration wins. Prefer `expireAfter(Expiry)` for custom logic.

## Iteration

**`EntrySet.removeIf` predicates receive immutable snapshots** (`Map.entry(k,v)`), so
`setValue` throws. Like CHM and Guava's JDK-8078726 repair, removal is conditional on the
captured value. This applies to bounded, unbounded, async synchronous `AsMapView`, and raw
`AsyncEntrySet`; both async views delegate to the inner cache's `removeIf`, like their values
views. Keep write-through entries for `iterator` / `spliterator` / `toArray`, and do not
restore the positional `iterator.remove` default for predicate removal.

**Map equality uses size, iteration over this map, and `count == expectedSize`.** The
AbstractMap shape is symmetric with HashMap and costs O(n), versus CHM's O(n+m) two-sided
scan. The final count catches maintenance trimming dead entries after the size prescreen;
otherwise a surviving subset can incorrectly compare equal. Preserve it in
`BoundedLocalCache.equals` and the future-typed `LocalAsyncCache.AsMapView.equals`.

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
claim.

**Identity comparison applies to one-sided queries, never to `equals` or `hashCode`.** A
one-sided query (`remove(k, v)`, `replace(k, old, new)`, `containsValue`, `values().remove`,
`keySet().removeAll`) can be identity-based unilaterally, because the worst case is declining to
act. A bilateral contract cannot: the other side is `AbstractMap` or `AbstractSet`, which compares
with `equals` and never reciprocates, so identity there breaks `Object.equals` symmetry and buys
nothing. Measured with the cache holding one value and a `HashMap` holding an equal-but-distinct
one: at HEAD both directions of `equals` are true, while an identity-comparing `equals` gives false
one way and true the other. This is the boundary of the `_byIdentity` convention, and every method
that convention covers is one-sided. No implementation anywhere compares keys by equality and
values by identity in `equals`: `IdentityHashMap` is reference-based on keys and values alike, and
Guava's `LocalCache` extends `AbstractMap` and declares no `equals` at all.

**So value identity is coherent with no map, and that is accepted.** Under `weakValues()` or
`softValues()` the value-bearing queries compare by identity (`containsValue`, `remove(k, v)`,
`replace(k, old, new)`, `values().contains`/`remove`, `entrySet().contains`, and through
`AbstractSet` the entry view's `equals`, `removeAll` and `retainAll`), while `Map.equals`,
`hashCode` and the `WriteThroughEntry` objects the views emit compare with `equals`. Probed against
a `HashMap` holding an equal-but-distinct value: `equals` true both ways with matching hash codes,
`entrySet().equals` false one way and true the other, `contains` and `containsValue` false, and the
emitted entry equal to the probe entry. Guava's `weakValues()` cache reproduces that row element for
element. Both coherent alternatives are worse. `IdentityHashMap` buys internal coherence and is
still asymmetric against a `HashMap` (`equals` false one way, true the other, differing hash codes),
which is the bilateral case above. Making the queries `equals`-based would contradict the value
semantics `weakValues()` and `softValues()` document. Reference implementations do not agree with
each other here, and the cache is biased toward size eviction rather than reference collection, so
do not raise the disagreement as a defect or propose a consistency repair.

**View equality has an accepted physical-size/logical-content gap.** Key and entry views
inherit `AbstractSet.equals` / `hashCode`. `size()` includes expired or collected entries
pending maintenance; iteration, containment, and hash code skip them. With live `a` and
pending-dead `b`, `HashSet{a,b}.equals(keySet)` can return true while the reverse comparison
is false and hash codes differ. The argument's `HashSet.equals` performs the true-returning
comparison, so overriding the view's equality cannot repair it. A logical `size()` was
rejected to preserve the lock-free physical estimate; a physical hash is unavailable once a
weak key is collected. `WeakHashMap` has the same changes-during-comparison problem.
Values views use identity equality like CHM. For exact comparisons, call `cleanUp()` with no
concurrent operations. An additional `asMap()` size warning was declined as redundant with
the cache's existing approximation contract.

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
for a custom executor, and it needs a `Scheduler`. Configuring one requests prompt expiration;
a size-only cache has no pacer. Without this arm, a `REQUIRED` backlog waits for the next cache
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
the same ground: prompt size eviction in an idle cache is not a contract the cache offers.

## Refresh Internals

**The loader's original future is both refresh token and public result.** Its identity
represents the generation. Per-generation copies would change `refresh(k)` / `policy.refreshes`
and stop cancellation reaching the loader's future. Completion also precedes dependent
handlers, so awaiting that future cannot guarantee the cache-updating handler has run.

Reusing one pending future for two generations of the same key is outside this model: the
older handler can claim the successor's token and both can dispose of the produced value,
affecting a non-idempotent listener. Ordinary LIFO dependent completion makes the window
narrow; a coalescing loader can hand out copies itself. A write-time ownership condition
repairs neither double disposal nor orphaning across preserved timestamp changes.

Distinct future instances with custom equality are likewise unsupported. Map conditional
`remove` / `replace` and `refreshes.remove` use equality, which matches identity for standard
`CompletableFuture` but can remove a successor for a hostile subclass. Repair would require
identity-conditional operations throughout both caches and the refresh registry, not a local
ownership check.

**A manual synchronous-cache refresh uses one `LocalLoadingCache.RefreshOperation` per call.**
It carries the registration and completion state, while the loader's original future remains
the token and return value. Registration runs inside `refreshes.compute`; completion is attached
after it returns, and coalescing onto an existing refresh attaches no additional handler.

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
benign** (accepted). `ConcurrentHashMap.remove` takes the bin lock
whether or not the key is there, so the prescreen is what keeps an ordinary write
off that lock. It was added to the bounded cache only; the unbounded cache still
removes unconditionally. What the prescreen cannot see is an in-progress
`computeIfAbsent` reservation, which reads as absent, so a write landing inside
that window leaves a token launched from the generation it superseded. On the
automatic path nothing is committed wrongly: the completion's ABA guards reject
the value, and the registration re-validates `node.getWriteTime()` inside the
reservation, which rejects any write that lands earlier. That first clause is load-bearing and was
only true by accident until 2026-08-24. The completion's write-time term reads
the node captured when the reload started, and `retire()`/`die()` leave
`writeTime` frozen, so a remove and reinsert of the **same value instance**
presented an unchanged value and an unchanged write time and the guard committed
a reload launched from the dead generation. The commit branch now also requires
`node.isAlive()`, which is exactly the generation test the other two terms cannot
make; both remain necessary, since the write time still catches an in-place
update of a node that never died. Pinned by
`BoundedLocalCacheTest.refreshIfNeeded_reinsertedNode_rejectsStaleReload`. There the residue is
bounded by one load and is a delay rather than a loss: automatic refresh is
suppressed for that key while the orphan is in flight (`refreshIfNeeded` gates on
the same `containsKey`), and a `refresh(k)` issued after the write coalesces onto
the superseded token and is discarded. Don't drop the prescreen to close it, and
don't add a registration-side re-check: closing it there would fix only
`refreshIfNeeded` and leave the manual `LocalLoadingCache` and
`LocalAsyncLoadingCache` registrations, which carry no write-time marker to test,
while implying all three were closed.

**A manual refresh that started absent can commit across the same window, and that is
accepted.** `LocalLoadingCache.RefreshOperation` has no node and no write time, so its only
ownership test is `currentValue == oldValue`, which is `null == null` when the key was absent
at registration. A `put` followed by an `invalidate` inside the reservation window therefore
leaves the token registered and lets the completion install its loaded value over both.
Measured on the bounded cache with a loader parked inside the registration, 5/5 per arm: put
then invalidate installs the load, put alone leaves the written value (so the guard works
wherever it can tell the two apart), and a present-start refresh is discarded. The escape needs
the loader to run inside `refreshes.compute`, which means a direct executor, a saturated pool
with `CallerRunsPolicy`, or an `AsyncCacheLoader` returning a completed future; on
`ForkJoinPool.commonPool()` the registration publishes first and the refresh is discarded 0/5.
The resulting history is still a legal linearization (put, invalidate, then the refresh's
install), and no public contract covers it: `Cache.invalidate` is undefined only for an entry
being loaded and otherwise not present, which the put makes false. The asynchronous cache does
not share the shape, since its absent case installs an in-flight future through
`computeIfAbsent` on `data` and registers with `putIfAbsent`.

**The unbounded cache keeps the unconditional `remove`, so a write there waits out an inline
refresh load on the same key** (measured: a 2004 ms `put` against a 2 s load). Accepted.
`UnboundedLocalCache` is a light `ConcurrentHashMap` wrapper, and CHM's `put` is pessimistic
anyway, so there is no optimization proper to apply there. The bounded cache takes the
complexity because it already coordinates a great deal of metadata for speed.

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
the exit is not reachable with the hint set. Pinned by `BoundedLocalCacheTest.remap_absentCreate_discardsPendingRefresh`.

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
async refresh-of-absent inserts a physical in-flight entry that `remove` does see.
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
first-class in-flight entry — a structurally-forced divergence, not a bug.**
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
which never had an absent-branch catch. `doComputeIfAbsent` is only ever a user load,
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
all). The unbounded `remap` catch is guarded by `value != null`, preserving an absent
user-function throw like the bounded implementation.

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
nothing about a mapping surviving the return either.

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
`AsyncAsMapView.computeIfAbsent`'s retry loop.



## Async Put Re-registration

**`AsyncCache.put(k, future)` re-registers a completion handler whenever the prior
mapping differs by identity, so re-inserting an already-registered future
double-fires `handleCompletion`.** The dedup in `LocalAsyncCache.put` only skips a
*consecutive* same-instance put (`prior == castedFuture`), so `put(k, f1);
put(k, f2); put(k, f1)` leaves two `whenComplete` handlers on `f1`. A single
completion then replays `replace` + `recordLoadSuccess` once per handler. Accepted,
because there is no correct dedup: we cannot inspect a future's already-registered
dependent actions, and tracking our own registration history would be wrong (unbounded,
and stale the moment the entry is replaced). It is benign regardless. Re-inserting a
specific future instance after replacing it is unusual, and the second
`replace(k, f1, f1)` is idempotent: `notifyOnReplace` suppresses on identity, and the
quiet write preserves the expiration because the first handler already cleared the async
sentinel, so the user's `Expiry` sees one creation rather than a creation and an update.
The load is still counted once per handler. Pinned by
`AsyncCacheTest.put_reregisteredInstance_completionRegisteredTwice`.

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

**Wheel resolution is one level-0 tick: `2^30` ns (~1.074s).** An advance within a tick
(for example `0 → 1`) yields `delta = 0`; crossing `-1 → 0` after the wrap-bias repair crosses
a rebased boundary and yields 1. Hashed wheels process whole ticks to retain O(1) insertion
and deletion; see [research foundations](research-foundations.md) on timing wheels.

Queries filter an expired entry immediately, but physical removal and its notification can
wait until the next tick. On a fake ticker, expiries of 1ns, 1ms, and 500ms stayed resident
after `cleanUp`; `setExpiresAfter(k, 0)` was reaped at `2^30`, not `2^30 - 1`.
The bound is one tick, not a whole wheel revolution: `expire` scans `[start, start + delta]`
inclusively (`steps = 1 + delta`) and checks each node's deadline. `getIfPresent` and
`containsKey` filter without removing; the compute path (`get(k, fn)` through `remap`) reaps.
Fixed expiration is more eager because its deque has no tick boundary to wait for.

**That bound assumes a deadline that has not moved since the node was scheduled.** A
variable-expiry read applies its new duration through `tryExpireAfterRead`'s CAS and leaves the
matching `reschedule` to `onAccess`, which runs only when the read buffer accepted the node. A
dropped offer leaves the node in the bucket its previous deadline chose, so a shortened deadline
waits for that bucket's sweep. Measured with an `Expiry` of 1h on create and 2s on read, 400 keys
read once each on the default executor and system ticker: 208 to 368 keys across five trials kept
their original bucket and none were readable, and the deterministic fake-clock arm reaped them with
`EXPIRED` at 3574s against the 3s they had asked for. A `Scheduler` does not shorten the window,
because `getExpirationDelay` reads bucket occupancy rather than deadlines and reports the next flush
3569s out. The delay ends at the sweep of the previous deadline's bucket, at or before that deadline,
so a dropped read never makes an entry outlive the schedule it already had; a lengthened deadline
self-heals the same way, its node sitting in an earlier bucket whose sweep pushes it forward.
Accepted: the node reference is gone once the offer is dropped, routing the reschedule onto the write
buffer would put most reads of such a configuration on the MPSC queue, and declining the CAS instead
would leave the entry readable past the duration the user asked for.

The resolution is accepted for a cache's maximum-lifetime contract. It aligns with the 1s
expiration tolerance and pacer's minimum delay; a bounded cache can also reclaim by size.
An eager current-bucket sweep remains a possible optimization, not an approved change.
It costs O(bucket occupancy) per maintenance cycle: detach onto `pending`, deschedule, and
reschedule every not-yet-due node, instead of paying once per tick. Measure that cost before
removing `delta <= 0`; a nearest-bucket heap is another structure for stricter timer resolution.

**`expire` detaches its bucket onto a field-backed, circular `pending` sentinel.** Detachment
prevents a not-yet-due node from being rescheduled into the list being drained, especially on
the last wheel's single bucket. It does not prevent a nested `RemovalTask` from descheduling
an entry it already holds. A stack-local detached chain used to strand the remainder when
that operation cleared the carried successor's links. Preserve all three protections:

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

**Interner `drainKeyReferences` does not need a value-identity check.** Unlike values, keys on
interned nodes never rebind. Cleared references now compare equal only to themselves. In the old
implementation, hash-colliding cleared keys could alias, but both polls still evicted both nodes;
only attribution swapped, unobservable with uniform `Boolean.TRUE` values and no listener.

## Builder and bulk loading

**`LoadingCache.getAll` is not atomic.** Valid entries may commit before failure and are not
rolled back. Per-key null results omit that key; bulk maps must omit a key to mean no value.
An explicit null value fails the bulk load in both sync and async paths, matching Guava.

**`Caffeine`'s builder mirrors Guava's `CacheBuilder` validation shape, asymmetries included.**
`refreshAfterWrite(long, TimeUnit)` checks the unit before state and requires `duration > 0`;
`expireAfterWrite` / `expireAfterAccess` do not check the unit first and allow zero.
Consequently `expireAfterWrite(-1, null)` throws `IllegalArgumentException` rather than NPE.
Changing either order diverges from `CacheBuilder` and the adapter's compatibility suite.

**`Caffeine.from(CaffeineSpec)` disables strict parsing**, matching `CacheBuilderSpec` and
allowing programmatic overrides such as a weigher after `maximumSize`. The possibility of
accidentally disabling eviction is accepted.

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
deserialization.
