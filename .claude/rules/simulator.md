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

- Consecutive-duplicate-access dedup is a per-policy decision in `record()`, not a trace-reader/framework concern. LIRS-family operations are idempotent on dups; LIRS2 (role swap) and frequency-counting policies are not.
- For ports from a reference implementation, achieve bit-for-bit hit/miss match against the reference on canonical traces before introducing quality deviations (memory bounds, paper-faithfulness, naming). The baseline proves the algorithm is correctly understood; deviations layer on top.

## Hit-Rate Validation

- Canonical trace set: bundled LIRS (`loop`, `multi1/2/3`, `2_pools`, `cpp`, `cs`, `scan` at sizes 500/1k/2k); ARC's `DS1` at 1M to 8M; `S3` at 100k to 800k; the corda_large + 5×loop + corda_large at size 512 stress test.
- For LIRS-family bit-for-bit matching: set `non-resident-multiplier` very high (e.g. 100) so the memory bound doesn't fire — published references don't bound shadows.
- To run a C/C++ reference side-by-side: use `simulator:rewrite --outputFormat=LIRS` to produce one-int-per-line traces, strip `*` checkpoints with `grep -v '^\*$'` if the reference reader rejects them.
