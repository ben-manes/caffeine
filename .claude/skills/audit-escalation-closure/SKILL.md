---
name: audit-escalation-closure
description: Drive open ESCALATED audit findings to reproduced/unreachable by porting them into Fray, LinCheck, or jcstress and running them
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Write, Edit, Agent
---

Audits generate ESCALATED findings faster than they get resolved, and on a
mined-out codebase the escalation queue — not new static lenses — is where the
remaining real bugs get proven or retired. This skill owns the closure loop:
every open escalation becomes a runnable test that reproduces the bug, a
structural argument for why it is unreachable, or an explicit blocked entry.
A skeleton sitting in a report is a TODO, not a resolution.

Unlike the `/audit-*` snapshot skills, this one reads `.claude/reports/` by
design — it consumes their escalation sections. It does not use the auditor
agent (which is forbidden from reading reports).

## Step 1: Inventory

Grep `.claude/reports/**/*.md` for ESCALATED, PLAUSIBLE, and open-escalation
markers. Build a ledger: finding, source report, hypothesized interleaving,
current status (skeleton-only / test-exists / blocked). If more than ~3 are
open, present the ledger and ask which to drive this session.

## Step 2: Select the tool

Per `.claude/docs/testing.md` ("Choosing the Dynamic Tool for an Escalated
Finding"): sync-point interleaving → Fray; plain-field SC-reachable race →
LinCheck model checking; weak-memory publication → jcstress. For weak-memory
hypotheses, model the REAL structure, not an isolated primitive — isolated
shapes over-state reachability.

## Step 3: Port and run

Write the test into the source tree (`caffeine/src/frayTest/`,
`caffeine/src/lincheckTest/`, `caffeine/src/jcstress/`), following
`.claude/rules/testing.md` and `.claude/docs/testing.md` conventions (Fray:
direct threads, inline executor, `assertThat(cache).isValid()` deep oracle —
never the tautological size comparison). Run the single test, not the full
battery. For suspected JIT-dependent interleavings, prefer many short runs
with varied stress seeds over one long run.

## Step 4: Classify and record

- **REPRODUCED**: keep the test (it becomes the regression test for the fix),
  report the failing interleaving, and stop for the maintainer — do not
  auto-fix.
- **UNREACHABLE**: record the structural reason (wrap gap, single-writer
  discipline, happens-before edge) in an addendum to the source report; remove
  the harness unless it doubles as a useful invariant test.
- **BLOCKED**: name the missing capability (tool granularity, JVM control) so
  a future attempt — or a stronger model generation — re-attempts with better
  tools.

Update the source report's escalation section in place; never leave a resolved
escalation still marked open. Summarize the ledger delta (opened → closed →
still open) at the end.
