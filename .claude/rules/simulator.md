---
paths:
  - "simulator/**"
---

# Simulator Conventions

- Configuration: HOCON in `simulator/src/main/resources/reference.conf`
- Override config with `-Dcaffeine.simulator.*` system properties
- Trace format specified in path: `format:filepath` (e.g., `lirs:trace.gz`)
- 50+ policies across 9 categories (adaptive, greedy_dual, irr, linked, opt, product, sampled, sketch, two_queue)
- Policy interface: `record(AccessEvent)`, `finished()`, `stats()`
- New policies need `@PolicySpec` annotation and registration in `Registry`
- Run single sim: `./gradlew simulator:run -q -Dcaffeine.simulator.*=...`
- Laptop sleep kills background runs mid-sweep. `caffeinate -i` is best-effort from agent
  shells — its IOKit assertion was observed failing in BOTH sandboxed and unsandboxed
  sessions ("Failed to create PreventUserIdleSystemSleep assertion"; verify with
  `pmset -g assertions`) — so for multi-hour sweeps keep a user-level awake session
  (Amphetamine, or caffeinate in a user terminal), and ALWAYS make sweep outputs resumable
  (append + skip-done rows) so an interruption costs nothing
- Run multi-size with charts: `./gradlew simulator:simulate -q --maximumSize=... --metric=...`
- Convert trace formats: `./gradlew simulator:rewrite -q --inputFormat=... --outputFormat=...`

## Trace Characteristics & Policy Matching

A trace declares `characteristics()` (only `WEIGHTED` today); a policy declares what it supports via
`@PolicySpec(characteristics = {...})`. `Registry.policies()` keeps a policy iff it supports **every**
characteristic the trace carries (`policy ⊇ trace`) — so a weighted trace runs only weight-aware policies
and silently drops the rest (ARC/LIRS/Clairvoyant/most sketch.*/…; `sketch.WindowTinyLfu` declares
WEIGHTED since 2026-08-02, budgeting regions by weight in BLC's candidate/victim loop, so the
static-window ceiling anchor runs on weighted cells). **By-design, not a bug:** a trace's features
are interpreted *uniformly* across the panel so a report is one metric; a weight-oblivious policy is
excluded rather than run on a weighted trace where its object hit rate isn't comparable to the others'
byte hit rate. There is deliberately **no "treat weighted as unit" mode** (libcachesim has one, printing
object + byte columns together — that reintroduces the apples-to-oranges we exclude for). For the
object-hit-rate view of a weighted trace, strip the weight with `simulator:rewrite` — an explicit metric
switch that yields a reusable narrowed trace.

**If downcasting is ever wanted**, add it as a trace-side projection (an adapter reader), never a Registry
change: narrow the declared `characteristics()` *and* re-emit narrowed events (`AccessEvent.forKey(key)`,
dropping weight) so `policy ⊇ trace` is unchanged and the whole panel stays one metric. Re-emitting the
narrowed event is load-bearing — narrowing only the metadata would let a weight-aware policy keep reading
the real weight and optimize byte hit rate amid an object-hit-rate panel. Not worth building until ≥2
routinely-mixed characteristics make a cross-capability comparison genuinely useful (the enum has a single
value — scaffolding that never expanded).

## Reporting Fidelity

- **"Evictions" is each policy's own accounting, not a normalized framework counter.** For an entry
  the policy never admits, `CAMP`/`GDWheel` count an eviction, `S3FIFO`/`Sieve` count nothing, and
  `GDSF` reports a rejection. Don't reconcile them: how an *oversized* entry interacts with eviction
  is part of the algorithm's design, and a policy that flushes its LRU to admit one is its designer's
  choice. Classic LRU simply is not size-aware — the answer is to not run it on such a trace (see
  Trace Characteristics), or to implement a size-aware variant as its own policy, not to retrofit
  size-awareness into a published algorithm on its author's behalf.
- **The rewriter's output is best-effort for an external tool, and knowingly loose.** A `.gz`
  output name writes an *uncompressed* file under that name (nothing gzips it), `LirsTraceWriter`
  emits the full 64-bit key where the reference C readers parse one signed int per line, and
  CloudPhysics folds keys into its 32-bit format so distinct keys can collide. None of this is
  fixed: the rewriter exists to hand a trace to another tool, and that tool fails loudly on input
  it cannot read, so the cost of the looseness is a confusing minute rather than a wrong result.
  Don't add compression, range checks, or collision warnings here without a concrete need — it was
  tried and reverted as more machinery than the export path deserves.
- **Text traces decode as ISO-8859-1 — key identity, not display.** Every byte maps to its own
  char, so a dirty real-world trace (wikibench holds raw Latin-1 bytes) parses, and distinct
  malformed byte sequences cannot alias onto a shared U+FFFD — the lenient-UTF-8 hazard, real for
  readers whose key is derived from a text field (the MSR hostname, the Baleen shard). All field
  parsing and filtering is ASCII, so clean traces are byte-for-byte unaffected. A strict REPORT
  decoder was tried and reverted: it closed the aliasing hole by refusing real traces outright.
- **Disclosed chart/sampling limitations** (not defects, don't "fix" silently): the JFreeChart size
  axis is *categorical*, so an exponential size sweep renders equidistant rather than to scale; and
  GUESS sampling is with-replacement, so a sample may draw the same entry twice.

## Clairvoyant Look-Ahead (opt.Clairvoyant + admission.Clairvoyant)

Bélády's MIN (`opt.Clairvoyant`) and clairvoyant admission need each request's *next-access time* — an
inherent look-ahead. When any clairvoyant usage is enabled (`isClairvoyant` in `Simulator`:
`opt.Clairvoyant` in `policies` or `Clairvoyant` in `admission`), the Simulator wraps the underlying
reader with `ClairvoyantTraceReader`, which materializes the trace once, up front, to a fixed-width
temporary file. Key invariants:

- **One pointer per request, not a per-key list.** Bélády only needs the *immediate* next use, so each
  record is `[key, (weight | penalties), nextAccess]`. A forward pass appends the records; a backward pass
  then fills each `nextAccess` from a `nextSeen: key→position` map — O(distinct keys) heap, released before
  the policies run (memory isolated to the pre-pass). This replaces the old O(N) in-memory buffers on both
  policies.
- **The materialization *is* the trace.** `events()` replays it to every policy (reconstructing the
  minimal `AccessEvent` for the sniffed characteristics), so a non-repeatable synthetic (`ThreadLocalRandom`)
  is frozen once and all consumers walk the identical sequence — the old admitter re-read the trace and
  *threw* on synthetic (`"cannot be predicted"`); it now works.
- **Consumers walk a `Cursor`, in lockstep.** `opt` and `admission` each take a sequential `Cursor` over
  the next-access column; both call it exactly once per access (`admitter.record` fires once per access —
  verified in every host), so cursor position tracks the trace position. The reader owns cursor lifecycle
  (closed on `TraceReader.close()`, which is a no-op default except here). A shared static holder hands out
  cursors because policies/admitters are built deep in the Registry from `Config` only. **No
  decorator forwards `close()`** — neither `ClairvoyantTraceReader` (it keeps no reference to the
  delegate it consumed) nor `TraceFormat.readFiles`' composites (both are lambdas). That is inert
  because the clairvoyant reader is the only one that materializes state and `getTraceReader`
  always installs it *outermost*, so every possible delegate inherits the no-op default. A second
  materializing reader must therefore not be nested beneath a wrapper without adding the
  forwarding; don't add it speculatively.
- **All I/O is sequential and buffered.** Reads are buffered sequential `DataInputStream`s; both the forward
  append pass and the backward fill pass are block-sequential (the backward pass is what avoids the random
  writes a forward back-fill would need, since its window can't span a long reuse distance). A key-only
  delegate (`KeyOnlyTraceReader`, e.g. arc) is materialized straight from its `keys()` `LongStream` with no
  per-event boxing; `TraceFormat.readFiles` preserves key-only-ness across its multi-file wrapper.
  **Never drain the delegate via `Stream.iterator()`** — it buffers the *entire* stream before yielding
  (internal chunking runs until the terminal op, contradicting the javadoc), silently reintroducing O(N);
  use `forEachOrdered`. Bit-for-bit vs the prior in-memory impl (corda, DS1); on DS1 @ 4M it's ~2× faster
  and fits ~4× less heap (512 MB vs 2 GB).
- **`opt` records no penalties itself** — the `PolicyActor` attributes penalties from the hit/miss it
  observes (it processes online now, unlike the old buffer-then-replay), so self-recording would
  double-count. Unit tests drive it through the reader and mirror that attribution.

## Policy Implementation

- Consecutive-duplicate-access dedup is a per-policy decision in `record()`, not a trace-reader/framework concern. Song Jiang's reference C code (`lirs.c`) and Chen Zhong's C++ port (`replace_lirs_base.cc` / `replace_lirs2.cc`) both put `if (ref == last_ref) continue;` at the top of the run loop to avoid counting "correlated references" — rapid re-accesses to the same block from one logical event; the 2Q paper (VLDB '94) discusses the same concern. Not in the published LIRS / CLOCK-Pro papers (author intent, confirmed via direct correspondence). **Key subtlety:** the reference increments its hit-rate denominator *before* that `continue` (`warm_pg_refs++` in `lirs.c`; `mTraceLength` in Zhong's), so a duplicate stays in the denominator as a guaranteed non-miss (≡ a hit). The dedup removes the duplicate from the *algorithm*, not from the rate.
  - **Where a per-access transition is non-idempotent** — `Lirs2Policy` (instance role-swap), `ClockProPolicy`/`ClockProPlusPolicy` (adaptive `coldTarget`) — the duplicate can't be replayed, so the guard scores it as a hit (`recordOperation()` + `recordHit()`) and returns, keeping it in the denominator as a non-miss. It must **not** bare-`return` before recording: that drops the dup from the denominator and diverges from the reference (verified — current matches `lirs.c`/Zhong/`clock-pro.c` misses bit-for-bit on `cs`, but an early `return` understated the rate ~1pp; fixed 2026-06-23). `ClockProSimplePolicy` keeps no guard (it regresses with one) and counts the dup as a hit via the normal path.
  - **`LirsPolicy` deliberately omits the guard.** After any access the block sits at the top of stack S, so a consecutive re-access is a no-op on S/Q state; the policy already records that second access as a hit, which reproduces the reference's denominator accounting exactly. It matches `lirs.c` and Zhong's base bit-for-bit on the canonical set *including* `cs` (101 consecutive dups). Adding a top-of-`record()` guard would drop dups from the denominator and *break* that match — it diverges from the reference rather than matching it.
- For ports from a reference implementation, achieve bit-for-bit hit/miss match against the reference on canonical traces before introducing quality deviations (memory bounds, paper-faithfulness, naming). The baseline proves the algorithm is correctly understood; deviations layer on top.
- **For a *published* algorithm, the paper is the spec of record — not the authors' evolving repo.** The reference code validates that we understood the algorithm, but authors keep tuning their repo post-publication (e.g. S3-FIFO's libCacheSim added a warmup and hit-rate tweaks that drift from the SOSP'23 pseudo-code). Caffeine's own policies may be living; a policy named after a published algorithm tracks the paper, so we don't chase repo changes that alter the published hit rate. When the paper and the current repo disagree, prefer the paper and note the divergence. Corollary: when translating a paper's **real-valued** threshold to integer/`long`, don't let it floor to a value the real expression can't take — S3-FIFO's `evict()` routes on `S.size >= 0.1·C` (never true for an empty S since `0.1·C > 0`), but `(long)(maximumSize * percentSmall)` floored to 0 for a small cache and spun the insertion loop (fixed with `Math.max(1, …)`).
- **Simulator policies are simple *reference* implementations — simpler by shedding *library* needs, not by being a *weaker algorithm*.** They omit the production complexity of the library (concurrency, VarHandle access modes, memory layout, industry-specific tuning) so a researcher/developer can read, port, and debug them — the reference is the *ideal* algorithm minus that machinery. But a *degraded* version under a named/published algorithm (a weaker LIRS, or a `simple` climber missing an algorithmic improvement BLC has) misrepresents it and is unfair to its authors. So **match algorithmic quality** with BLC / the reference (e.g. the sim's `simple` climber must keep BLC's small-cache grow-first direction and never-freeze restart — the commit was literally *"Improve hill climber adaptation at small cache sizes"*), and simplify *only* the library machinery. Don't over-engineer either — Ben disfavored LIRS/the Indicator partly for being hard to maintain. **`product.Caffeine` is the faithful shipped-behavior proxy in the simulator (it runs the real cache); `sketch…simple` is the readable reference — so a `simple`-vs-BLC gap is a quality bug to close only when it's an *algorithmic* gap, not a library-complexity one.** Verify with the `corda_large + 5×loop + corda_large` stress trace at 512 (phase-shift re-adaptation) plus a spread of bundled traces.
  - **Sketch *sizing* is algorithmic quality, not library machinery.** A count-min sketch's reset period scales with its capacity (`period = 10 × table.length`), so a sketch sized once and never retracked is not merely coarser — it is the same algorithm aging at a different rate, which shifts the answer most at small windows, where nearly every admission is filtered. `WindowTinyLfuPolicy`'s admitter was frozen at the size the cache held when it first filled, while `BoundedLocalCache` re-calls `ensureCapacity(mappingCount())` on every addition; on a weighted trace, where the entry count keeps moving, that inverted `metaCDN_rprn@4G`'s static-window optimum from 80% to a reported 1% (2026-08-07). Retrack through `Frequency.ensureCapacity`, which is grow-only and retunes the period every call; sketches that cannot resize inherit a no-op default. Unweighted output is unaffected, and that is worth re-checking bit-for-bit on the bundled lirs cells after any change here.
    - **A retrack must re-point the period without restarting the epoch** (2026-08-09). The weighted path calls `ensureCapacity` on *every* access, so rearming an epoch counter there outruns the per-increment decrement and the counter never reaches its test: `ClimberResetCountMin4` set `eventsToCount = period` unconditionally, making `reportMiss`'s `eventsToCount <= 0` unreachable, freezing `step` at 1 and silently degenerating `reset = climber` into a variant of `periodic` under the climber label. Reset the epoch (`eventsToCount`, `additions`) **only when the table was actually reallocated**, which is the one event that forgets the counts. Pinned by `ClimberResetCountMin4Test.ensureCapacity_retrackWithoutReallocation_keepsTheEpochRunning`.
    - **The remaining cadence gap is WON'T DO** (2026-08-09). `FrequencySketch` ages on
      `10 x maximum` while `CountMin4` ages on `10 x table.length`. Those are the *same number at
      every power of two*, and 129-256 also coincide via the library's `MIN_SKETCH_SIZE` floor, so
      at the gate's sizes (4096/8192/16384/32768) there is nothing to fix; the real difference is
      the one the retrack already closed, that the library re-points downward and `CountMin4` is
      grow-only. The residue is non-power-of-two sizes, where the simulator ages up to 2x staler
      (`DS1@1051635` 1.99x, `strad_p8@4097` 2.00x, `arc/P3@152508` 1.72x) and, below 256, up to 32x
      fresher (`loop@101` 2.00x fresher). It touches only `Admission.TINYLFU` — the static-window
      ceiling anchor and the simulator-native policies — never `product.Caffeine`, which builds a
      real cache and therefore already ages on the library's own schedule. So a recorded product
      number is not affected; on a non-power-of-two cell the headroom figure beside it was measured
      under a slower aging rate. Judged not worth re-basing the corpus for.
    - **A wrapping sketch must forward the retrack.** `IndicatorResetCountMin4` wraps a fully resizable `ClimberResetCountMin4` but inherited the no-op default, so `reset = indicator` and `reset = periodic` rows of the same weighted report were aging at different rates and were not comparable. It forwards now; the four that genuinely cannot resize (`PerfectFrequency`, `RandomRemovalFrequencyTable`, `TinyCacheAdapter`, `CountMin64TinyLfu`) are what the javadoc's "cannot be resized" sentence refers to.
    - **Build the admitter in the constructor, never lazily during replay.** A clairvoyant admitter takes its `Cursor` from `ClairvoyantTraceReader.currentCursor()`, and the reader is installed as a `ScopedValue` only for the scope of policy construction. `WindowTinyLfuPolicy` deferred the weighted admitter into `evict()`, so `-Dcaffeine.simulator.tiny-lfu.sketch=clairvoyant` on any weighted trace threw `IllegalStateException` at ~50% fill — and would have handed out a fresh mid-trace cursor even if bound, breaking the once-per-access lockstep. Seed the weighted sketch at `maximum-size = 0` in the constructor and let the retrack grow it; the table is reallocated (forgetting counts) at the first real retrack, so unweighted stays bit-for-bit and weighted keeps its half-fill sizing.

## Hit-Rate Validation

- **Screen a trace family by its first line, not its file size.** `all-trc` in the local research
  corpus carries two unrelated formats under one `.trace.xz` suffix: ten plain one-key-per-line
  LIRS reference streams (the ones its README names) and 27 files of `N <k>` / `I <id> <t>` /
  `O <id> <t>` block records that **no `TraceFormat` reader parses**. The unreadable 27 are every
  large file there, so a study sizing a holdout off `wc -l` selects cells that cannot run —
  `LirsTraceReader` dies with `NumberFormatException` inside `Simulator.broadcast`. That failure
  is loud; the sibling hazard is quiet — a mismatched-but-valid reader yields nothing and writes
  blank rows (the ARC cells in the real-corpus runner were declared `lirs` until 2026-08-04, and
  every ARC row it had ever written was empty).

- **Noise floor — never count a sub-noise delta as a win.** `product.Caffeine` has randomized admission, giving a single-seed run-to-run hit-rate noise floor of **~0.1–0.8pp** (measured: `loop@101` spread 0.12, `multi3@2981` spread 0.49). A delta under **~1pp is not resolvable from noise** and must not be reported as a win — this is a *recurring* mistake (tiny wins overcounted as achievements, e.g. "+0.14 over the ceiling" is noise, the climber merely *matched* it). Report **absolute pp, not relative %** (relative exaggerates noise on low-HR cells); **multi-seed (≥3, ideally 5) any low-HR cell** where a single seed is dominated by the hashing seed. The bar for a change is **robust wins (≥~2pp, multi-seed) with no collapse**, judged cell-by-cell — never a `net +Npp` sum, which is inflated by the sea of sub-noise cells. The 2026-05 large-cache climber sweeps learned this the hard way (a "−68%" was 5,119→1,631 hits, both ~0% HR — pure noise).
- Canonical trace set: bundled LIRS (`loop`, `multi1/2/3`, `2_pools`, `cpp`, `cs`, `scan` at sizes 500/1k/2k); ARC's `DS1` at 1M to 8M; `S3` at 100k to 800k; the corda_large + 5×loop + corda_large phase-shift stress.
- **The corda+loop stress must be run across the climber tiers.** The climber is tiered by size (`.claude/docs/design-decisions.md`): reactive `≤ SLOW_ADAPT_THRESHOLD` (512, small-tuned) and `≤ DENSITY_THRESHOLD` (4096, standard), density `> 4096`. Run the stress at **512, 513, 1024, 4096, 4097, 8192** and confirm `product.Caffeine` stays near its static-window ceiling and above LRU at every size, with **no cliff at either threshold boundary** (the 512→513 cliff was the original symptom of density taking over too early; density is now scoped to >4096 where it doesn't trap). The density climber is fragile at small/medium sizes — a starved region reads zero density and pins at an extreme — which is why it's scoped to large caches; don't lower `DENSITY_THRESHOLD` without re-running this. A synthetic phase-shift does *not* reliably reproduce the trap — use the real bundled `corda:trace_vaultservice_large.gz` + `lirs:loop.trace.gz`.
- For LIRS-family bit-for-bit matching: set `non-resident-multiplier` very high (e.g. 100) so the memory bound doesn't fire — published references don't bound shadows.
- **The LIR/HIR split is rounded on the COLD side, with a per-policy floor.** Both references compute
  `HIR = (int)(HIR_RATE/100 * mem_size)`, clamp it up to a floor, and give LIR the remainder;
  rounding the hot side instead (`(int)(size * percentHot)`) moves the boundary by one block and
  costs 1–4 misses out of thousands. The floors differ and are not interchangeable: `lirs.c` uses
  **2** (`LOWEST_HG_NUM`) and Chen Zhong's `replace_lirs2.cc` uses **4**. Their stack bounds differ
  too — `MAX_S_LEN` is `mem_size*2500` for LIRS and `mem_size*8` for LIRS2, which is why
  `lirs2.stack-length-multiplier` defaults to 8. Verified 2026-08-13: with the cold-side rounding,
  `LirsPolicy` reproduces `lirs.c`'s miss counts on **8/8** canonical cells
  (`cs`@512/1024, `ps`@256/1024, `multi1`@1024, `gli`@512, `cpp`@1024, `2_pools`@1024) **at the
  shipped default**, where it previously matched only when the split was hand-adjusted. Re-check
  with `verify_reference.py` in the lirs-analysis workspace, which rebuilds `lirs.c` from the
  simulator's own resources (`cc -std=gnu89`; it has implicit declarations modern C rejects).
- **The LIRS2 stack bound is load-bearing, not inert.** `lirs2.stack-length-multiplier` looks like
  a memory knob and is not one: `stackLength` tracks the depth of the admission bar, so `MAX_S_LEN`
  clamps how permissive admission may become. Three independent measurements in 2026-08:
  dropping 8 to 1 frees zero blocks while driving slot visits per request from 1.997 to 8,105;
  multiplier 1 moves a constructed cell by 12.91 points by cutting promotions 92%; and at the
  published 8 a working set can be locked out of promotion entirely, curing only at 12 or above.
  Sweeping it as a fairness control and reporting "flat" is only valid over the range actually
  swept, and 1 is outside what the published bound contemplates.
- To run a C/C++ reference side-by-side: use `simulator:rewrite --outputFormat=LIRS` to produce one-int-per-line traces, strip `*` checkpoints with `grep -v '^\*$'` if the reference reader rejects them.

## Reader / Policy Test Scoping

The simulator is an interpretation-heavy research tool: a trace reader encodes *our reading* of an
often-undocumented format. A unit test asserting "parse == the keys I derived from the format" only
locks that reading in — right or wrong. (The K5cloud reader keyed on the block alone until #1974 added
the volume id; a parse-assertion test would have frozen the across-volumes aliasing.) So don't add
blanket per-reader coverage.

Add a reader/policy test only against a **real oracle**, folded into the specific fidelity fix:
- a documented byte layout (byte-order / alignment — e.g. the libCacheSim struct)
- a paper-defined behavior or arithmetic property (CAMP's `roundedCost`)
- a boundary / robustness property that needs no oracle (don't-NPE at size 1; don't-silently-truncate
  a corrupt trace)

The real validation of an interpretation is a **hit-rate run vs a reference impl / paper**, not a unit
test.
