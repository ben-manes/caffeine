# Audit Rounds

How a batch `/audit-*` round is run: order, quota, what a second model buys, and how to read
the output. Referenced from `CLAUDE.md`; read it when starting or triaging a round, not
otherwise.

The cycle is: run every audit back-to-back banking reports, consolidate into one tracker,
fix one row at a time, verify nothing was dropped in consolidation, then close the coverage
gaps the fixes opened. Fixing is not interleaved with running. A full cycle is 2-3 weeks and
usually spans sessions, so the tracker is the handoff, not a summary.

**Serialize the runs.** Quota exhaustion mid-run *breaks* an audit rather than pausing it, so
never fire runs in parallel. `/audit-adversarial` is the single priciest skill (roughly half a
weekly quota: 8 reviewers plus their evaluator challenges), so order the batch value-descending
and an early cutoff still banks the most bugs.

**Spend a second model on depth, not breadth.** Every skill that has had two models produced
model-unique findings, and in each case the most severe finding came from exactly one of them.
So a second model on an un-corroborated skill beats a third model on a corroborated one. Order:
`subsystem-safety`, `liveness`, `feature-interaction`, `jcache-conformance`,
`sibling-divergence`. `build-ci` and `serialization` are the least worth it.

**Down-weight, but do not skip:** re-entrancy (a full pass has closed at zero, and callback
re-entrancy warnings are not wanted, which removes the remedy from most of what it finds);
simulator periphery (a testing tool — its bugs mislead benchmarks, they do not harm users);
and the formal-shape lenses (jmm, linearizability, arithmetic, correctness-proof,
map-contract), which are cheap to run but have gone several passes without a core defect.

**A report row is a claim, not a finding.** The last full sweep put 186 rows through source
verification: 26 survived that, and 7 survived the standing rulings in `ruled-out.md`. Two rows
rated **high** were refuted outright. Never quote a backlog length as a defect count — say "N
unverified claims" and give the survival ratio. Verify by *running* the row: every finding and
every refutation that held up came from a repro or an A/B, not a source read.

**Use `general-purpose`, not `auditor`, for verification, triage, and consolidation passes**
over existing reports. The auditor carries a mandatory-report-write gate (`SubagentStop`) that
will pick a canonical filename and overwrite the source report you are consolidating from. If
you must use it for reuse, assign it an explicit `-verification` path.
