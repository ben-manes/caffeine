---
paths:
  - "caffeine/src/main/java/**"
---

# Design Decisions (Quick Reference)

Before reporting a bug or suggesting a "fix," check this list. These are intentional.

- **Weight=0** is a pinning feature (skipped during eviction), not a bug. Instead verify weight convergence via the telescoping sum.
- **EXPIRE_TOLERANCE (1s)** is intentional inexactness — entries may expire up to 1s earlier than the configured duration (never later). Applies to both `writeTime` reorder decisions in remap and `accessTime` updates on the read path (skipped if the last update is within tolerance, to avoid hot-entry cache-line contention). Tolerance is bypassed when the configured duration is `<= tolerance`.
- **Expiration eviction is capped per maintenance cycle** (`EXPIRATION_THRESHOLD`, 1000), then
  re-armed via `PROCESSING_TO_REQUIRED` to drain the backlog across cycles. The wheel rewinds
  `nanos` and re-links the unprocessed remainder, so a `schedule` inside that window measures
  against a behind clock — benign, measured, don't "fix" it. The budget counts **only evictions**,
  never the cascade, and a cascade cap must never reuse the rewind (it livelocks). Don't flag it
  as under-expiring or remove the re-arm. Read the doc.
- **Transient negative weightedSize** is acceptable eventual consistency. Instead verify convergence after maintenance completes.
- **Collected references are drained at `REFERENCE_THRESHOLD` (1000) per queue per maintenance
  cycle**, counting polls rather than evictions, then re-armed with `PROCESSING_TO_REQUIRED`. A
  garbage collection can clear an arbitrary number of entries at once, and the drain otherwise ran
  to exhaustion under `evictionLock` (130–190 ms for 1M cleared keys). The cap bounds the *hold*,
  not a concurrent operation's *wait*: the re-arm re-submits immediately and the lock is not fair,
  so the backlog still monopolizes it. Don't read that residual as the cap failing, and don't
  raise the budget to "make the drain finish" — the slicing is the point.
- **Neither write-buffer consumer waits for a producer's publication.** `drainWriteBuffer` and
  `clear` both use `relaxedPoll()`, so a producer descheduled between its index CAS and its
  element store no longer spins the lock holder. The task is not stranded: `scheduleAfterWrite`
  runs after `offer` returns and re-arms the drain. Don't restore the strong `poll()` in either
  place; `clear` in particular does not need exhaustiveness, since it already abandons the buffer
  at `WRITE_BUFFER_MAX / 2`, `AddTask` links only when the node is alive, and `makeDead` reads the
  weight from the node so a late task telescopes back to zero.
- **A hit probes the value future's readiness only where an expiration policy reads the answer**
  (`hasExpired` tests `expires()` first, the read blocks test `expiresAfterRead()`). Six acquire
  loads of `CompletableFuture.result` per `maximumSize`-only async hit were dead; removing them is
  +53% on `AsyncGetPutBenchmark.read_only`. Don't cache one probe's answer for the other — a
  future can complete or be obtruded in between.
- **accessTime uses opaque write (not CAS)** to avoid contention storms. Instead verify that stale reads cause only benign early expiration.
- **Read-path expiry extension can briefly resurrect a just-expired entry** (`tryExpireAfterRead`'s `casVariableTime`, or `setAccessTime`) — accepted, not a bug. A reader that saw the entry live and then extends it can land the write just after the entry crossed its boundary, leaving it visible slightly later than expiry. Inherent to lock-free read-extension over lazy expiration (the CAS only checks the field is unchanged, so it can't reject an expired entry; a fresh-clock guard before the write still races a context switch — only a read-path lock, rejected, closes it). Wide window needs a slow `expireAfterRead` (callback misuse). "Never later" is best-effort here; over-stay is bounded by one duration and self-heals on maintenance. Don't add a *fresh-clock* re-check guard (distinct from the load-bearing `node.getValue() == value` value-identity check that blocks rebinding a read duration onto a replaced value — keep that one).
- **`maximum` and `weightedSize` have a plain reader and an acquire reader, and no public-facing
  path may use the plain one.** `AddMaximum.addPlainAndAcquireField` emits `maximum()` as a
  `VarHandle.get`, which guarantees bitwise atomicity only up to 32 bits, so a plain read of the
  64-bit field can tear against `setMaximum`'s release write on a 32-bit JVM. The plain reader is
  for callers already holding `evictionLock`; everything reachable from the public API takes
  `maximumAcquire()`/`weightedSizeAcquire()`. There is no way to assert an access mode in a test,
  so this is a review-time invariant: check the call site's lock state, not the accessor's name.
  The three duration fields need no such care, since `addAcquireReleaseField` emits only an
  acquire reader.
- **The async expiry sentinel is also the mark that a load has not been accounted for, so both
  read-extension paths guard on it.** `AsyncExpiry.expireAfterUpdate` routes to the user's
  `expireAfterCreate` while `currentDuration > MAXIMUM_EXPIRY`, which is how a completion's quiet
  replace tells a first load from an update; `AsyncExpiry.expireAfterRead` keys off `getIfReady`
  and cannot see it. So `tryExpireAfterRead` and `expireAfterRead` each return early on `isAsync &&
  currentDuration > MAXIMUM_EXPIRY`, and the duplication is not redundant. The uncovered case is
  not the in-flight one: it is the value arriving while the completion waits on the node lock a
  `putIfAbsent` holds. Read the doc.
- **notifyEviction before user code** preserves linearizability. Instead verify catch-commit-rethrow handles exceptions after irrevocable notification.
- **Catch-commit-rethrow** in doComputeIfAbsent/remap makes phantom evictions real on exception. Instead verify the committed state is consistent. This is the most commonly misunderstood pattern — read the doc before flagging exception handling in compute paths.
- **Two weight fields** (weight + policyWeight) is intentional for the telescoping sum; verify
  both converge rather than flagging transient negatives. `makeDead` subtracting `getWeight()` and
  `UpdateTask.run` being dead-guard-free are a matched pair — don't change either alone. The same
  out-of-order drain can leave `policyWeight` transiently negative and over-shift the region caps;
  tolerated, not guarded (the caps are policy targets, eviction is driven by the telescoping
  `weightedSize`). Don't clamp the transfer quota against negative weights. Read the doc.
- **The sketch's shrink retrack and `reset()`'s zero clamp are a matched pair** — don't change
  either alone. `ensureCapacity` re-points `sampleSize` at the new maximum while keeping the
  grow-only table, which breaks the precondition `reset()`'s truncation correction relied on
  (`count/4 < size` held only while the table matched the sample). Unclamped, `size` underflows
  deeply negative and aging stalls until it counts back, restoring the old size's cadence that the
  retrack exists to remove. The clamp is inert whenever the table matches the maximum. Read the
  doc. **The retained table's lock-hold cost is accepted, not a defect** (adjudicated 2026-08-09):
  the table is grow-only while `sampleSize` re-points down, so after a large shrink `reset()`
  walks the old table at the new, much shorter cadence — 1.94 ms under `evictionLock` on a
  retained 8 MiB table after a 1M→1K shrink, ~194 ns/increment amortized against ~0.19 ns when
  the two match. The amortized cost scales with the peak-to-current ratio, so a 10x swing is free
  and only a ~1000x one is visible. Reachable on unweighted caches through
  `policy().eviction().setMaximum()`, not only weighted ones. Don't add a hysteretic table shrink:
  it would fight the per-addition retrack a weighted cache already performs, and the aging
  correctness the retrack buys is worth more than the tail cost.
- **`scheduleAfterWrite`'s IDLE arm retries its failed swap** instead of scheduling on what it
  read, mirroring the processing arm. Dropping the failed swap can leave a write with no driver
  when a drain is in flight that already passed its task. Don't collapse it back. Read the doc.
- **The keyReference in a weak/soft value reference is non-volatile but read and written
  opaquely** (published via setRelease + storeStoreFence; verify the fence is present in setValue).
  A strong-key weak/soft-value node stores its key in that reference, so `getKey()` and `isAlive()`
  read the field independently and plain reads would permit observing the sentinel and then the
  older key, judging a retired node alive while handing out the sentinel. Opaque reads are coherent,
  which forbids it, and cost nothing at runtime. Don't demote them to plain, and don't promote the
  field to volatile.
- **`refreshIfNeeded` is lock-free and `discardRefresh` is over-aggressive** — both intentional;
  a refresh racing a real mutation must die for linearizability. Don't add `synchronized(node)` or
  narrow the discard. The one sanctioned narrowing is `RemapHints.preserveRefresh` for query-style
  no-ops, which **both** bounded and unbounded caches must honor, and which also owner-scopes the
  completion itself (`preserveRefresh = !owned` on every reject/absent exit — else a stale
  completion steals a newer refresh's token). Don't reintroduce an unconditional by-key discard on
  a completion exit. "Every exit" includes `remap`'s **exception** exit, which honors the hint too
  (a throwing weigher/`Expiry`/`Ticker` lands there after the remapping set it); the exits that
  precede the remapping cannot, since the hint is what the remapping computes, and none of them is
  reachable from a completion. The jcache adapter deliberately does not get this narrowing. Read
  the doc. The bounded cache's `containsKey` prescreen in `discardRefresh` **stays**: a
  `ConcurrentHashMap.remove` takes the bin lock whether or not there is anything to remove, so the
  prescreen is what keeps a write off that lock. It was added to the bounded cache only, and the
  unbounded cache still removes unconditionally. It cannot see an in-progress `computeIfAbsent`
  reservation, so a write landing inside that window leaves an orphan token; that race is benign
  and adjudicated. The commit is protected by the ABA guards, the registration's own re-validation
  inside the reservation rejects any write that lands earlier, and the residue is bounded by one
  load: automatic refresh is suppressed for the key, and a `refresh(k)` issued after the write
  coalesces onto the superseded token and is discarded. Don't remove the prescreen to close it, and
  don't add a registration-side re-check for it.
- **Async sync-view mutations are physical while queries are logical** — in-flight entries are
  absent to queries but the key-based removals operate on the raw delegate; `size()`/`isEmpty()`
  are physical too. Blocking everywhere invites deadlock. The exception is **value-conditional CAS
  ops** and the `compute`/`merge` family, which block on the in-flight future, mirroring CHM's bin
  lock; bulk collection-view ops split the same way and it is CHM-faithful. The sync-view **read**
  is likewise non-linearizable, and the sync `get(k, func)`/`getAll` **coalesce** (Guava-family)
  rather than recompute. All by design — don't "fix" the asymmetry, don't add a double-collect
  re-check, and don't route `get(k, func)` through `computeIfAbsent`. Read the doc.
- **`EntrySet.removeIf` hands the predicate an immutable snapshot** (`Map.entry(k,v)`), so `setValue` throws — matching ConcurrentHashMap and Guava (JDK-8078726: their inherited default `removeIf` removed *wrong* entries under concurrent updates, so they overrode it with a snapshot + conditional `replaceNode(k,null,v)`). The write-through entry is intentionally **only** for `iterator`/`spliterator`/`toArray`; the iterator-vs-`removeIf` asymmetry is deliberate, not a bug. All four views follow this (bounded, unbounded, async sync-view `AsMapView`, async raw view `AsyncEntrySet`), removing conditionally via `remove(k,v)`; the two async views delegate to the inner cache's `removeIf` (mirroring their `values().removeIf`), the raw view having previously inherited the *positional* `iterator.remove` default. Don't "restore" a write-through entry here or flag the asymmetry.
- **`TimerWheel.advance` delta=0 on sub-tick advances** is correct (e.g., `nanos = 0 → 1`; since "Fix the timer wheel wrap bias at Long.MIN_VALUE", the `-1 → 0` crossing lands on a rebased tick boundary and yields delta=1, also correct). Entries in the "last bucket" are visited on the next full wheel cycle; read-path `hasExpired` evicts on access sooner.
- **`TimerWheel.expire` detaches a bucket onto the `pending` sentinel.** Keep all three
  properties or a nested `deschedule` strands the remainder: the head lives in a **field**
  (`pending.next`) re-read each iteration, both lists stay **circular** (the invariant is
  *scheduled ⟺ non-null links*), and `advance` **defers on the `advancing` flag** — never infer
  that predicate from `pending` being non-empty, which was a real defect the fuzzer caught. Pinned
  by `maintenance_recursive` and `TimerWheelFuzzer`. Read the doc.
- **A checked exception can arrive through any functional interface, so the conversion restores the
  interrupt.** Kotlin, Scala, and Groovy have no checked exceptions (`@Throws` is interop metadata),
  so a lambda passed as a `Function`/`BiFunction`/`CacheLoader` can throw `InterruptedException`
  with no ceremony and no sneaky-throw. `Caffeine.toUnchecked` is the single conversion
  point for all of it: rethrow an `Error`, return a `RuntimeException` by identity, restore the
  interrupt for an `InterruptedException`, else wrap in a `CompletionException`. The restoration is
  load-bearing because the JDK **clears** the flag when it throws, so a conversion that omits it
  silently swallows the interruption. Don't reason from "the interface declares no `throws`" — that
  is a javac rule, not a JVM one. Reached from the loader chains and from catch-commit-rethrow only
  (`doComputeIfAbsent`/`remap` recomputing a COLLECTED or EXPIRED entry, and `AsyncBulkCompleter`);
  the absent-key path propagates the throwable raw and owes nothing, and `UnboundedLocalCache` never
  converts (`throw t` unchanged), so neither is an asymmetry to "fix".

- **The refresh token is the user's own future, and its identity is what stands in for the
  generation.** `CompletableFuture` has no equality, so identity is the only way to map a
  registration onto a side structure (conditionally clearing `refreshes` when a load fails against
  concurrent updates), and the loader's future is returned and exposed rather than wrapped. A
  per-generation `copy()` is therefore not available: it changes what `refresh(k)` and
  `policy().refreshes()` hand back, and cancelling a copy no longer reaches the loader's future.
  The exposure has known annoyances, the cache-updating `whenComplete` running after the user's
  future is already done, with no way to wait for the cache to be populated, since an expensive
  handler ahead of ours in the dependent stack is slow to pop. A loader that returns one
  still-pending future for two refresh generations of the same key is **out of scope**
  (adjudicated 2026-08-15): the earlier completion reads the successor's identical token as its
  own, discards the newest reload, and the one produced value takes the declined-value disposal
  from both handlers, which bites only a non-idempotent listener. The window is narrow, the JDK
  completing its dependent stack LIFO so the successor installs first in the ordinary case, and a
  user who coalesces can hand out a copy. Don't add a per-generation wrapper, and don't tighten
  the ownership test with a write-time term, which repairs neither the double disposal nor the
  orphan-token risk on paths that keep a registration across a timestamp change. The mirror case
  takes the same ruling: a `CompletableFuture` subclass whose `equals` makes distinct instances
  compare equal defeats the conditional map operations a completion cleans up with
  (`remove(k, future)`, `replace(k, future, future)`, and the CHM `refreshes.remove`), so a stale
  completion can remove or overwrite a successor. Those are `equals`-based because `Map` requires
  it, and they coincide with identity only because `CompletableFuture` has none. Out of scope with
  the rest of the hostile-future family; the repair would be identity-conditional internal variants
  threaded through both caches plus a `computeIfPresent` for the refresh token.
- **A concurrent obtrusion on a cached future reads as not-ready rather than propagating.**
  `Async.getIfReady` wraps its `join`, because a standard `obtrudeException` landing between the
  readiness check and the join otherwise threw a `CompletionException` out of any caller, public
  queries and `evictionLock`-held maintenance alike. Only the readiness question is defended: a
  completion handler is one-shot, so a future obtruded after it succeeded leaves the entry
  physically present while queries filter it, which is accepted. Don't widen the catch past
  `CancellationException`/`CompletionException`, and don't report the lingering entry as a leak.
- **A contract-violating user component is the user's problem** — a throwing
  `Ticker`/`Weigher`/`Expiry`/loader, broken `equals`/`hashCode`, a hostile future, `Error`/OOME.
  The guarded/unguarded split is deliberate: fire-and-forget extensions are wrapped because a
  default exists, **value-bearing** ones must propagate because none does. The recurring finding
  shape is a value-bearing throw landing *after* a commit — real and present, so confirm-and-close
  rather than proposing containment (the containment op throws on the same trigger). A throw inside
  `maintenance` defers work, it doesn't lose it; don't force `REQUIRED` on that path. Read the doc.
- **`TimerWheel.getExpirationDelay` returns when a bucket is next *flushed*, not when an entry is
  due** — a flush expires the entry *or* cascades it, so the wake-up matters even when nothing is
  due. The **current** bucket flushes at offset 1, not a full span: the main scan clamps with
  `Math.max(1, j - start)` (don't restore the `delay > 0 ? delay : SPANS[i]` ternary) and
  `peekAhead` probes the higher wheel's current bucket too. Getting this wrong fired entries up to
  a span late. Pinned by `getExpirationDelay_occupiedCurrentBucket` + `_fuzzy`. Read the doc.
- **`Pacer.calculateSchedule` bumps a would-be 0L result to 1L** — `nextFireTime = 0L` is the unscheduled sentinel. Don't remove the guard.
- **`LoadingCache.getAll` is not atomic; a null value is handled differently per path** — the Javadoc's "the mapping is left unestablished" is singular, so valid entries are partial-committed rather than rolled back. A *per-key* loader returning null drops just that key and keeps the rest. A *bulk* loader's returned map must not contain a null *value*: it is rejected (the whole load fails, matching Guava and the sync bulk path), not silently dropped. Omit a key to signal "no value" — an omitted requested key is dropped; only an explicit null value is rejected. (The async bulk path once dropped null values; that was reverted so all bulk paths reject.)
- **`Caffeine`'s builder mirrors Guava's `CacheBuilder` validation shape, asymmetries included.**
  `refreshAfterWrite(long, TimeUnit)` alone calls `requireNonNull(unit)` before its state checks and
  guards `duration > 0` ("must be positive") while `expireAfterWrite`/`expireAfterAccess` have no
  null check and guard `duration >= 0` ("cannot be negative"). That is copied verbatim from
  `CacheBuilder`, which has the identical split, and it dates to the initial
  multi-project build, not to any audit. The consequence is real but intended: without the null
  check, `expireAfterWrite(-1, null)` reports `IllegalArgumentException` ("duration cannot be
  negative: -1 null") where a null unit arguably deserves an NPE. Don't "fix" it in either
  direction — adding the check to the siblings or removing it from `refreshAfterWrite` both diverge
  from `CacheBuilder`, which the guava adapter's compatibility suite is built against.

- **`Caffeine.from(CaffeineSpec)` disables strict parsing** — mirrors Guava's `CacheBuilderSpec`; permits programmatic overrides like adding a weigher after `maximumSize`. The footgun of disabled eviction is accepted.
- **Climber `adjustment` is a multi-cycle carry-over**, not stale state: a capped window transfer
  stores the unfulfilled remainder back, and the sub-sample early-return preserves it so the
  transfer drains across cycles. Don't flag "adjustment re-applied without a fresh sample." The
  `quota` is a soft knob for a probabilistic guess, not an accounting invariant — a transient
  negative `policyWeight` can push a region cap out of `[0, maximum]` for a cycle, which re-clamps
  on the next call. Don't clamp it (adjudicated after four re-derivations). Read the doc.
- **Async load completions are quiet updates** — `replace(..., quietly= true)` finalizes
  weight/expiry but skips the sketch increment and the climber's hit counters, because a completion
  is bookkeeping rather than a usage (loud completions measurably skewed admission and the density
  climber's window attribution). `BoundedVarExpiration.setExpiresAfter` and the `refreshIfNeeded`
  completion are quiet for the same reason; manual `refresh` completions stay loud. Don't re-add
  access recording or flag the loud-write vs quiet-completion asymmetry. `remap`'s immaterial
  (read-path) settle is quiet-capable but unreached in production, since `refreshAfterWrite` forces
  `exceedsWriteTimeTolerance` and routes every completion through the UpdateTask; the guard is not
  dead code, `replace`'s async-completion callers reach the same shape. Read the doc.
- **The climber is tiered by size, and both tiers are measured, not tuned by taste.**
  `≤512` reactive with slow-adapt tuning (grow-first, stretched period, slow decay); `≤4096`
  reactive at standard period/decay; `>4096` the **goal-audited density climber** — a
  proportional step on `ln(windowDensity/mainDensity)` (gain 0.03, capped at 30% of the maximum,
  window floored at 2%, period `4 × maximum`), plus the probe machine and the goal-metric layer
  that police what density cannot judge. The tier gate compares the configured maximum in its
  NATIVE units (weight units when weighted): deliberate, attack-tested, not a unit bug. Extending
  density below 4096 was measured and rejected.
- **Walking the window back down costs 52–63× the maximum in requests, and that is arithmetic
  rather than a defect.** A density sample is `4 × maximum` requests and the law's step is
  `|error| × 0.03 × maximum`, with the log-ratio error saturating near 1.5–2 nats, so the descent
  runs at 4–6pp of the maximum per sample and a full walk down from an 80% window needs 13–16 of
  them. Measured 2026-08-12 on ten frequency-optimal real cells with the window planted away from
  its shipped 1% (`/climber-gate`'s `startwin.py`): recovery tracks the trace's sample count and
  nothing else — 41 samples recovers 73% of an 80% plant, 2 samples recovers 2% and leaves the
  window where it was planted — and replaying a trace 4× cuts the deficit 3–8×. The density tier
  recovers about twice as much of a deep plant as the reactive law it replaced. Don't read a
  large-cache deficit from a wrong window as a broken descent before counting the cell's samples,
  and don't propose shortening the sample period from this alone: the period is what keeps a
  converged small window from jittering, and the cost side was never measured.
- **The reactive tier has no window floor, and the bold driver's reversal is what stands in for
  one.** `BoundedLocalCache.decreaseWindow` floors the window at a single entry; the 2% floor and
  its below-floor lift live in `Reading.floor`, which only the density tier reads. A bold driver
  cannot sustain a run — it reverses on any worsening sample — so the reactive law never reaches
  the rail. Any change that lets it run longer (a hysteresis band on the reversal, a longer sample
  period, a confidence gate) must bring the floor with it: measured 2026-08-04, a banded reactive
  law with no floor walks the window to ~0, where TinyLFU refuses every new entry against an
  established victim, and corda falls 30.96 → **1.13**. The band itself is measured dead
  (`hill-climber.md` §5, 2026-08-04) — this entry is about the floor, which outlives it.
- **The density signal is resident-only, so a region earning ~nothing is a STATE, not a bug.**
  The climber must never be trusted to hold position on a starved sample (pure density pins ~28pp
  below LRU on constructible traps at any size above the threshold). Hence: probe churn on starved
  samples is not a defect, the refractory ladder must not be removed, a *large* region earning
  nothing must NOT trigger a probe, and the below-floor clamp **lifts** a sub-floor window to 2%
  (don't restore the wedge). The recency give-back is intentional and derived, not drift:
  frequency-optimal traces return ~0.5–2.5pp versus the reactive climber (4.3pp worst observed)
  while still beating LRU comfortably. **The densities divide by the region's setpoint, not its
  occupancy, and that is a known during-burst bias** (raised independently by two reviewers,
  2026-08-09): the window transfer is bounded per maintenance cycle, so during a burst the window
  physically holds more than `windowMax` while `Reading.windowDensity` still divides by it, which
  inflates the window's density and biases the step upward. Both escalations were refuted —
  `evictFromMain` pulls window candidates through `admit()` like anything else, so there is no
  admission bypass and no stable wrong fixed point, and `LocalCacheSubject` still asserts the bound
  at quiescence. Don't re-raise it as a defect, and don't switch the denominator to occupancy
  without measuring: the setpoint is what the controller commands and the transfer converges to.
- **The goal-metric layer's references are deliberately inconsistent with each other; do not
  harmonize them.** Frozen at arm: the probe verdict's probation-marginal baseline **with the
  sample length it was measured over** (2026-08-09 — a density is a hit count over a capacity, so
  the frozen baseline is re-expressed at the live sample's length before the ratio is taken; the
  down verdict needs none of this, reading both densities from one sample), and the audit
  confirm's rate reference. Read LIVE: the starvation probe's walk-interior deviation pricing —
  the walk's own transient must lift its own bar. The rail is priced at `3×deviation` while the
  audit confirm is a raw-sample run-length streak plus a one-shot beat-base gate; they want
  opposite pricings and must not share one. Each layer owns its ladder, crash streak and schedule,
  and a crash never takes the audit clock's failure doubling. The audit's crash tolerance is
  one-shot by intent: any non-crash ending, including the tolerant retry's own budget expiry,
  retires the crash streak — the streak measures *consecutive* crashes, so an equilibrium
  alternating crash and budget-fail endings is never tolerated twice in a row (ratified
  2026-08-07; don't make the streak sticky across non-crash endings). The audit clock counts **window
  stillness only**, never rate events, and a moving sample decays the run by one rather than
  zeroing it (a hard reset lets one super-band move per wait suppress audits forever). The
  audit's alternation bit records only interior-chosen directions; a corner-forced walk leaves it
  untouched — a direction bit cannot encode positional coverage, and recording the forced walk
  points the first interior audit back into just-covered ground (inverted at the ceiling corner;
  adjudicated 2026-08-07). An
  audit's confirm parks and returns without a parting
  steering step; a starvation confirm does neither. The audit's room rule and the walk read one
  stride definition (`Ladder.stride`) — two spellings of that distance is how it drifted before.
  The blind-corner gate outranks both goal-metric branches, which is right for the rail (it
  adjudicates *on* the starved sample) and wrong for the audit (it adjudicates over later ones),
  so a **due** clock pre-empts a refractory hold — a sample that commands nothing and is spent
  only there, which left a corner that never clears standing still with the clock permanently due.
  The audit's crash bar keeps its absolute 5pp **depth**, capped at `AUDIT_BAR_FRACTION` of the
  rate frozen at the arm so the abort threshold itself is floored: where the whole rate is under
  5pp the level test is otherwise unsatisfiable and only the budget bounds a walk. Not a depth
  pricing (every dead candidate widened the bar), and inert unless the launch rate is under a
  third. **The walk's two interior exits do not share a bar** (2026-08-05): the crash abort is a
  level test against the frozen rate and keeps the pricing above, while the bold driver's reversal
  is a first-difference test against the previous sample and is priced at `AUDIT_BAR_FRACTION ×
  max(baseHitRate, noiseBand)` under the same absolute cap, so it is never below the noise it must
  survive. Don't harmonize them, don't drop the cap (it is what keeps the noise term out of the
  dead widening family), and don't re-derive the level — it is `0.15 × VETO_MARGIN_SCALE`, and the
  measured cliff is close. A walk based at the window floor cannot exit through `crossesBase`:
  the machine rests one integer entry below the fractional floor, an exact return is refused by
  the strict inequality, and the clamp re-lands one entry above the base. Known and measured
  free — the budget bounds the walk with the same FAILED pricing and full undo, pinned by
  `WindowClimberTest.walkStep_floorBasedWalk_endsAtBudgetWithAFullUndo` — so don't flag it, and
  don't repair the inequality without the seeded lottery-mover adjudication the record calls for.
- **A crash-scale shift that discards the anchor also re-seeds the goal metric** (`Rates.reset`).
  The EMA the next claim would be planted from is ~80% composed of the regime that just ended, and
  `resync` refreshes a claim only on-anchor, so before this the claim froze the moment density
  steered away and no later audit could confirm at any position (2026-08-03; the repro landed on
  its own audit-free value, 7.6pp below LRU). A crash *far* from the anchor keeps both the claim and
  the reference. Don't drop the reset, and don't move the fix into `Anchor.track`: aging the claim
  toward the live rate is measured dead in both forms — symmetric aging costs −11.9 on the ramp
  control (a claim that chases the rate upward can never fall the margin below it, which is the
  test that moves the anchor to a better position) and one-sided aging disarms the guard rail.
  The on-anchor `resync` is deliberately ungated by walk/return state (only planting waits for a
  settled sample): it sits in the `isAt` branch and never reads the gate, so it re-syncs while a
  crash-abort's retreat is still draining; the contaminated blend rides the EMA either way and
  heals over later on-anchor samples, where a frozen-high claim never heals off-anchor
  (adjudicated 2026-08-07). **Planting's gate does span that drain** (2026-08-09): `isProbing()`
  is the walk *and* the capped retreat undoing it, because `hasPendingUndo()` outranks
  `anchor.returning` in the router, so `returning` is false for the whole drain and both planting
  branches would otherwise claim a position the probe was charged a ladder escalation for
  rejecting. Don't narrow it back to `walk != null`.
- **A starvation confirm the density arm reverses deepens the ladder; it is not a success** (2026-08-15).
  The up-probe's verdict prices the window against the probation density frozen at the arm, the
  steering law prices it against main's average, and where they disagree the confirmed position is
  walked home in the same sample and the corner re-arms. That was 668 of 881 starvation confirms
  across the battery and corpus dumps, and rewarding it (rung → 1) restarted the ladder on every
  cycle of a dither that never reaches the band it is looking for (`bandtrap2` 4.4pp below LRU,
  `shallowmoat` absorbing). `Walk.isReversedBy` prices such a confirm as a completed experiment
  (`escalate()`), keeping the handoff and the zero refractory; a kept confirm still rewards. Don't
  restore the reward on the reversed branch, don't add a refractory to it (holding the floor lets
  the calibration audit misconfirm on the warmup trend: `wedgefail`/`wedgehold`, dead), and don't
  let the walk continue past it (the v9 family). Its accepted price is a deep walk armed in a
  trough on a thin-signal floor with a dither (`arc_DS1@1051635` −0.7 on a 10-sample trace,
  `deadphase` −0.2) and, on a rewarded ladder mid-dither, the fail after a wedge waiting 16 samples
  instead of 2 (`norank_rep_r6` seed 3, 41 → 20; seven seeds identical); the guard that removes the
  first two is measured and recorded in `hill-climber.md` §5 (`wedgeshift`), not landed. Don't
  re-derive it without a holdout. **The one reversed confirm that does park** (2026-08-16) is the
  deepest-commitment walk whose confirm the goal metric confirms (`Walk.isAuditGrade`: the audit's
  own streak and beat-base test): it is an audit in all but name and density would dismantle it.
  Every other starvation confirm hands to density; the cheap re-probing that phase alternation
  relies on is intact. **A parked audit's own walk is covered by the park** (`isWorkloadShift`):
  its crash-scale move is the re-test's product and its ending returns to the park; a shift with
  no walk in flight still stands the park down, and the undo's arrival is still judged (the
  depressed-window study's `arrive` costs `demoflood` −1.9 unconditionally). Don't widen either.
  **The starvation refractory is armed only by a starvation walk's undo** (2026-08-16): an audit's
  retreat leaves a running hold to run out rather than re-arming it to the whole rung, which
  deferred the corner's next probe for an audit that was not the probe's doing. Priced: `widepin`
  +5.1 (the low basin lifted), `rep_r6` seed 3 +5.4, `shallowmoat` +1.1 against `metronome` −0.9,
  `balloonflip` −0.3, `cp_w050` −0.55; accepted as a small loss for big ones. Don't restore the
  re-arm without that ledger, and don't extend it the other way (a starvation undo must arm it).
- **The SLRU main space still earns its keep against a plain LRU main** (measured 2026-08-08, 276
  cells). It was introduced in 2015 and the sketch has been fixed several times since, so it was
  re-asked by disabling promotion entirely (threshold above the sketch's 4-bit ceiling leaves
  protected permanently empty; `PERCENT_MAIN_PROTECTED = 0` would instead cripple the climber,
  since `increaseWindow` early-returns on a zero `mainProtectedMaximum`). Plain LRU main is worth
  **−93.1pp net / mean −0.337**; SLRU wins >1pp on 40 cells vs LRU's 9, 38/46 traces prefer it,
  N=3 confirmed with 0 sign flips. It is inert on 82% of cells and worth 1–3pp on the rest, still
  concentrated at smaller sizes as the 2015 commit claimed. Don't propose replacing it with plain
  LRU. Note the promotion curve is **non-monotone** — a partial gate (@4, @6) is worse than either
  extreme and owns the −25.7/−20.3pp tails, because `evictFromMain` only reaches PROTECTED after
  probation is exhausted, so sparsely-promoted entries leave the victim pool indefinitely.
- **The main space's 80/20 split and its 1-hit promotion rule are measured optima, not untested
  defaults.** Both were swept as candidate second dials on 2026-08-08 (276 cells x 9 arms):
  no alternative constant beats the shipped value on the corpus mean, a perfect per-workload
  oracle is worth +0.21pp net of the max-of-N noise floor, the winning setting is scattered
  near-uniformly and moves across the size ladder on 46/46 traces, and the within-trace pass
  collapses to ~0 once the cells are not cherry-picked. Getting it wrong costs -6.16pp (protected
  at 0.95) to -25.72pp (promotion gated at frequency 4). Note also that `increaseWindow` conserves
  window + protected, so probation's capacity is FIXED for the cache's lifetime at ~19.8% and the
  window's reachable range is [2%, ~80.2%] — it never grows into probation. Don't propose an
  adaptive SLRU split or a Merlin-style promotion threshold without new insight; read
  `hill-climber.md` §5.
- **Before proposing any climber change, read `.claude/docs/hill-climber.md`** (§4 the shipped
  machine, §5 the graveyard, §7 open threads) and `docs/design-decisions.md`'s Eviction section.
  Five probe-verdict forms, every audit-bar depth pricing, PID and its descendants, ghost/shadow
  state, steering blends, and settle-then-judge confirms are all **measured dead** with the
  families each one traded. Re-run `/climber-gate` after any change; its battery, sentinels and
  bars are the regression contract, and bimodal cells adjudicate seeded or at N=8.

- **`BoundedLocalCache.equals` uses size + iterate-this + count==expectedSize**, not CHM-style two-sided iteration. AbstractMap-style is symmetric with the most common comparison target (HashMap), `BLC.size()` is reliable enough that the prescreen earns its keep, and O(n) beats O(n+m). The `count == expectedSize` postcondition catches a measured race shape ("bug fixes during coverage audit"): maintenance trimming dead entries between the size prescreen and iteration would otherwise yield a silent false-true on the surviving subset. Don't propose CHM's no-size two-sided iteration here. The same pattern is mirrored in `LocalAsyncCache.AsMapView.equals` (the future-typed view).

For full rationale, see `.claude/docs/design-decisions.md`
