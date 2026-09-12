# Audit Output Location

Write reports and transient analysis under `.local/`, grouped by producing model:

```
.local/audits/<model>/<name>.md
```

- **`<model>`** — the short id of the model that produced the report, lowercased, no vendor
  prefix and no context-window suffix: `opus-5`, `fable-5`, `sonnet-5`, `haiku-4.5`,
  `gpt-5.6-sol`. When a run spans models (an auditor on one, an evaluator on another), use the
  model that **orchestrated** it. An assigned `AUDIT_REPORT_PATH` overrides this derivation
  entirely; see Rules for agents.
- **`shared`** occupies the `<model>` slot for artifacts that are not one model's output: the
  consolidated backlog, a bug spec worked across sessions, anything aggregating several runs.
- **`<name>`** — the invoking skill's name (`audit-adversarial.md`). A multi-agent run suffixes
  per agent (`audit-jcache-conformance-groupABC.md`, `audit-sibling-divergence-groupA.md`) and
  a verification pass adds `-verification`; the canonical unsuffixed name is the synthesis.
  **Never overwrite a report you were dispatched to verify, consolidate, or read.**

There is no date or cycle level. Rerunning an audit under the same model replaces its report.

Examples:

```
.local/audits/opus-5/audit-jmm.md
.local/audits/gpt-5.6-sol/audit-jmm.md                  # same audit, other provider
.local/audits/fable-5/audit-adversarial.md
.local/audits/fable-5/audit-adversarial-evidence/r1-challenges.md
.local/audits/shared/audit-consolidated.md              # cross-model queue index
.local/audits/shared/queue/<section>/<item-id>.md       # one work item
```

Keep `.local/` between sessions for handoffs and cross-model comparison. It is gitignored and
machine-local; checked-in guidance must remain useful if that tree is absent.

## Consolidated queue

Cross-audit consolidation, including `run-audits.sh --consolidate`, uses this layout:

- `.local/audits/shared/audit-consolidated.md`: brief workflow instructions, counts by status, and
  section tables with linked ID, status, and short subject. Claims and evidence go in item files.
- `.local/audits/shared/queue/<section>/<item-id>.md`: one item per file. Sections, in order, are
  `core`, `async`, `jcache`, `guava`, `simulator`, `examples`, `build-ci`, `docs`. ID prefixes match
  the section except `simulator` uses `sim`, `examples` uses `ex`, and `build-ci` uses `ci`.
- `.local/audits/shared/queue/history.md`: consolidation provenance, report inventory, coverage
  limits, and prior cross-item decisions. Consult relevant portions, not the entire history.

Each item starts with `# <item-id>: <short subject>`, `Status: <status>`, and `Severity: <severity>`.
Include a `Prior rulings and context` section linking to named anchors in `history.md`, then the
current decision and next step, original claim, source-report links and finding IDs, and earlier
evidence/counterarguments. Distinguish reported measurements from current checks.
Use `open` for pending work (including disputed claims), `unverified` for unresolved reachability,
contract, or impact, `resolved` for an evidenced repair, and `closed` for an agreed no-fix outcome.

Extend the queue in place. Reuse IDs for matching claims; assign new numeric IDs above the section's
maximum, never recycling or renumbering them. Separate distinct subclaims without dropping their
parent ID. Preserve resolved/closed decisions when claims recur, and retain contrary evidence.
Never prune items to meet a survival ratio.

Map history entries by mechanism and scope, including family members not named by ID. Label
near-matches and historical counterarguments without treating them as current dispositions.
Give each history entry a stable named anchor. When adding or revising one, update the affected
items' links. If a mechanism review finds no related entry, record that explicitly with the review
date; a missing section means unreviewed. Neither case exempts an item from canonical module rules.

After each pass, refresh index links and counts from item files, using an existing helper if
available. Verify report coverage, retained items/counterarguments, and relative links. Work from
the selected item and relevant rulings; use a fresh context for unrelated items and leave a concise
handoff. Per-skill synthesis reports remain in the producing model's directory.

## Rules for agents

- The orchestrator computes the output path once and passes it to every agent as
  **`AUDIT_REPORT_PATH`**. Use that path, not the agent's self-reported model name; group and
  verification siblings go beside it. `.github/scripts/run-audits.sh` exports it;
  `.claude/agents/auditor.md` Phase 4 and `.claude/hooks/audit-report-guard.sh` honor it.
- The auditor agent must not **read** anything under `.local/audits/` (prior conclusions bias a
  fresh run) while still being required to **write** its own report there. See
  `.claude/agents/auditor.md` Evidence Boundaries.
- Long-running tooling (`audit-temporal-walk`) takes its model from the `AUDIT_MODEL`
  environment variable, since a shell-launched walk cannot know it; the invoking agent exports
  its own model id. `audit_paths.reports_dir` falls back to an existing tree for the module when
  that variable is missing, so a resumed walk still finds its `state.json`.
