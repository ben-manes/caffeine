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

## Hit-Rate Validation

- Canonical trace set: bundled LIRS (`loop`, `multi1/2/3`, `2_pools`, `cpp`, `cs`, `scan` at sizes 500/1k/2k); ARC's `DS1` at 1M to 8M; `S3` at 100k to 800k; the corda_large + 5×loop + corda_large at size 512 stress test.
- For LIRS-family bit-for-bit matching: set `non-resident-multiplier` very high (e.g. 100) so the memory bound doesn't fire — published references don't bound shadows.
- To run a C/C++ reference side-by-side: use `simulator:rewrite --outputFormat=LIRS` to produce one-int-per-line traces, strip `*` checkpoints with `grep -v '^\*$'` if the reference reader rejects them.
