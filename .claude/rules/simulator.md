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
