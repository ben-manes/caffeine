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
- Run multi-size with charts: `./gradlew simulator:simulate -q --maximumSize=... --metric=...`
- Convert trace formats: `./gradlew simulator:rewrite -q --inputFormat=... --outputFormat=...`

## Trace Characteristics & Policy Matching

A trace declares `characteristics()` (only `WEIGHTED` today); a policy declares what it supports via
`@PolicySpec(characteristics = {...})`. `Registry.policies()` keeps a policy iff it supports **every**
characteristic the trace carries (`policy ⊇ trace`) — so a weighted trace runs only weight-aware policies
and silently drops the rest (ARC/LIRS/Clairvoyant/sketch/…). **By-design, not a bug:** a trace's features
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
  cursors because policies/admitters are built deep in the Registry from `Config` only.
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
- **Simulator policies are simple *reference* implementations — simpler by shedding *library* needs, not by being a *weaker algorithm*.** They omit the production complexity of the library (concurrency, VarHandle access modes, memory layout, industry-specific tuning) so a researcher/developer can read, port, and debug them — the reference is the *ideal* algorithm minus that machinery. But a *degraded* version under a named/published algorithm (a weaker LIRS, or a `simple` climber missing an algorithmic improvement BLC has) misrepresents it and is unfair to its authors. So **match algorithmic quality** with BLC / the reference (e.g. the sim's `simple` climber must keep BLC's small-cache grow-first direction and never-freeze restart — `6f57cf556` was literally *"Improve hill climber adaptation at small cache sizes"*), and simplify *only* the library machinery. Don't over-engineer either — Ben disfavored LIRS/the Indicator partly for being hard to maintain. **`product.Caffeine` is the faithful shipped-behavior proxy in the simulator (it runs the real cache); `sketch…simple` is the readable reference — so a `simple`-vs-BLC gap is a quality bug to close only when it's an *algorithmic* gap, not a library-complexity one.** Verify with the `corda_large + 5×loop + corda_large` stress trace at 512 (phase-shift re-adaptation) plus a spread of bundled traces.

## Hit-Rate Validation

- Canonical trace set: bundled LIRS (`loop`, `multi1/2/3`, `2_pools`, `cpp`, `cs`, `scan` at sizes 500/1k/2k); ARC's `DS1` at 1M to 8M; `S3` at 100k to 800k; the corda_large + 5×loop + corda_large at size 512 stress test.
- For LIRS-family bit-for-bit matching: set `non-resident-multiplier` very high (e.g. 100) so the memory bound doesn't fire — published references don't bound shadows.
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
