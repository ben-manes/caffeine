# Audit Rounds

Read when starting or triaging a batch `/audit-*` round.

Bank the audit reports, consolidate them into an index with individual work items, verify that
no claims or counterarguments were dropped, then work one item at a time and revisit coverage
affected by the fixes. Keep the tracker current across sessions; use the
[consolidated queue format](audit-output.md#consolidated-queue), including for `run-audits.sh --consolidate`.
Complete the report batch before beginning repairs. A full cycle has typically taken 2–3 weeks.

**Serialize the runs.** Quota exhaustion mid-run *breaks* an audit rather than pausing it, so
never fire runs in parallel. `/audit-adversarial` is the single priciest skill (roughly half a
weekly quota: 8 reviewers plus their evaluator challenges), so order the batch value-descending
and an early cutoff still banks the most bugs.

**Prefer a second model on a skill over a third model repeating it.** Prior paired runs produced
model-unique findings, including their most severe claims. The preferred order is:
`subsystem-safety`, `liveness`, `feature-interaction`, `jcache-conformance`,
`sibling-divergence`. `build-ci` and `serialization` are the least worth it.

**Down-weight, but do not skip:** re-entrancy (a full pass has closed at zero, and callback
re-entrancy warnings are not wanted, which removes the remedy from most of what it finds);
simulator periphery (a testing tool — its bugs mislead benchmarks, they do not harm users);
and the formal-shape lenses (jmm, linearizability, arithmetic, correctness-proof,
map-contract), which are cheap to run but have gone several passes without a core defect.

**A report row is a claim, not a confirmed defect.** An earlier sweep reported 186 claims,
26 after source review, and 7 after standing rulings; even high-rated claims were rejected.
Those historical counts do not establish a rejection target or release confidence. Check the
contract and matching module rulings first. Use a focused reproduction for a behavior repair;
a source/contract proof can settle a documentation correction or no-code disposition. Preserve
the trigger, controls, counterarguments, and evidence limits.

**Use `general-purpose`, not `auditor`, for verification, triage, and consolidation passes**
over existing reports. The auditor carries a mandatory-report-write gate (`SubagentStop`) that
will pick a canonical filename and overwrite the source report you are consolidating from. If
you must use it for reuse, assign it an explicit `-verification` path.
