# Audit Rounds

Read when starting or triaging a batch `/audit-*` round.

Complete the audit batch before repairs. Use the
[consolidated queue format](audit-output.md#consolidated-queue), verify no claims or counterarguments
were dropped, then work one item at a time and revisit coverage affected by fixes. Keep the tracker
current across sessions. A full cycle has typically taken 2–3 weeks.

**Serialize the runs.** Quota exhaustion mid-run *breaks* an audit rather than pausing it, so
never fire runs in parallel. `/audit-adversarial` is the single priciest skill (roughly half a
weekly quota: 8 reviewers plus their evaluator challenges), so order the batch value-descending
and an early cutoff still banks the most bugs.

**Prefer a second model on a skill over a third model repeating it.** Prior paired runs produced
model-unique findings, including their most severe claims. The preferred order is:
`subsystem-safety`, `liveness`, `feature-interaction`, `jcache-conformance`,
`sibling-divergence`. `build-ci` and `serialization` are the least worth it.

**Leave `/audit-jdk-seam` out of a batch.** It is kept for an occasional manual run, not the
normal checklist.

**Down-weight, but do not skip:** re-entrancy (a full pass has closed at zero, and callback
re-entrancy warnings are not wanted, which removes the remedy from most of what it finds);
simulator periphery (a testing tool — its bugs mislead benchmarks, they do not harm users);
and the formal-shape lenses (jmm, linearizability, arithmetic, correctness-proof,
map-contract), which are cheap to run but have gone several passes without a core defect.

**A report row is a claim, not a confirmed defect.** An earlier sweep reported 186 claims,
26 after source review, and 7 after standing rulings; even high-rated claims were rejected.
Those historical counts do not establish a rejection target or release confidence. Check the
contract and matching module rulings first. A ruling closes a row by its mechanism; where its
reason is an observation (a trial count, a measured rate, an untested bound), re-run it at
[the refutation bar](finding-taxonomy.md#a-refutation-must-be-able-to-detect-the-defect) before
closing on it, since every model that rediscovers the mechanism meets the same observation. Use a
focused reproduction for a behavior repair; a source/contract proof can settle a documentation
correction or no-code disposition. Preserve the trigger, controls, counterarguments, and evidence
limits.

**Re-run the recorded observations once per full round.** Grep `ruled-out.md`, both
design-decisions files and the module rule files for counts, rates and bounds, and re-run each at
the refutation bar, as `/audit-jdk-seam` Step 4 does for JDK premises. A premise that fails
reopens its ruling. An external scan that did this overturned five recorded premises in one pass.

**Review a batch of repairs before pushing it.** A repair is new code in an area just shown to
hide defects: six of the seventeen real defects in that scan's report came from our own recent
changes, four of them hardening fixes. Run the built-in `/code-review max` over the range. Over the
seventeen repairs that followed the scan it found five regressions in four commits that targeted
tests and maintainer review had passed, because its verifiers reproduced candidates against the
builds before and after.

**Use `general-purpose`, not `auditor`, for verification, triage, and consolidation passes**
over existing reports. The auditor carries a mandatory-report-write gate (`SubagentStop`) that
will pick a canonical filename and overwrite the source report you are consolidating from. If
you must use it for reuse, assign it an explicit `-verification` path.
