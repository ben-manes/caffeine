# Audit Output Location

Audit reports and other transient analysis artifacts are written under `.local/`, not
`.claude/`. The tree is kept long-term and is compared across models and providers, so the path
records **who produced it**:

```
.local/audits/<model>/<name>.md
```

- **`<model>`** — the short id of the model that produced the report, lowercased, no vendor
  prefix and no context-window suffix: `opus-5`, `fable-5`, `sonnet-5`, `haiku-4.5`,
  `gpt-5-codex`. When a run spans models (an auditor on one, an evaluator on another), use the
  model that **orchestrated** it.
- **`shared`** occupies the `<model>` slot for artifacts that are not one model's output: the
  consolidated backlog, a bug spec worked across sessions, anything aggregating several runs.
- **`<name>`** — the invoking skill's name (`audit-adversarial.md`). A multi-agent run suffixes
  per agent (`audit-jcache-conformance-groupABC.md`, `audit-sibling-divergence-groupA.md`) and
  a verification pass adds `-verification`; the canonical unsuffixed name is the synthesis.
  **Never overwrite a report you were dispatched to verify, consolidate, or read.**

There is deliberately **no date or cycle level**. The same audit is not re-run under one model,
so the model is the only dimension that distinguishes output; a date would just be noise on a
path that is already unique. Re-running an audit under a model that already has that report
overwrites it — intentionally, since the newer run is the one being worked through.

Examples:

```
.local/audits/opus-5/audit-jmm.md
.local/audits/gpt-5-codex/audit-jmm.md                  # same audit, other provider
.local/audits/fable-5/audit-adversarial.md
.local/audits/fable-5/audit-adversarial-evidence/r1-challenges.md
.local/audits/shared/audit-consolidated.md              # cross-model working backlog
```

The whole `.local/` tree is gitignored, so nothing here is committed — but it is **not**
disposable: it is the durable record a later run reads to avoid re-deriving a settled question,
and the basis for cross-model comparison.

## Rules for agents

- An orchestrator that dispatches auditors **computes the directory once and passes the full
  path** to each agent, so a fan-out cannot scatter files across models.
- The auditor agent must not **read** anything under `.local/audits/` (prior conclusions bias a
  fresh run) while still being required to **write** its own report there. See
  `.claude/agents/auditor.md` Evidence Boundaries.
- Long-running tooling (`audit-temporal-walk`) takes its model from the `AUDIT_MODEL`
  environment variable, since a shell-launched walk cannot know it; the invoking agent exports
  its own model id. `audit_paths.reports_dir` falls back to an existing tree for the module when
  that variable is missing, so a resumed walk still finds its `state.json`.

## History

Reports lived under `.claude/reports/` until 2026-07-29, and were wiped between runs. That set
was relocated to `.local/audits/fable-5/` (the batch's auditor runs, 2026-07-15 → 07-23) with
the cross-model working documents under `.local/audits/shared/`. Those reports record no model
in their headers, so the attribution is inferred from how the batch was run rather than stated —
re-file it if wrong.
