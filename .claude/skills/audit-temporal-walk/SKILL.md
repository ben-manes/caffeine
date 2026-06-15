---
name: audit-temporal-walk
description: Heavyweight history-mining bug audit. Walks the caffeine module's git history chronologically (oldest to HEAD), maintains a forward-tracked issue database, and surfaces concerns introduced by past commits that were never resolved. Catches bugs that snapshot mining cannot — half-fixes invisible from current state, latent+trigger pairs across multi-commit interactions, and partial refactors. Slow (model/effort-dependent; ~24h on Opus + max effort) and rare-run (every several months or before a major release).
disable-model-invocation: true
---

# Audit: Temporal Walk

This is a long-running CLI tool. It walks every commit affecting the caffeine
module from project inception to HEAD, asking Claude per commit to
flag/resolve/modify a forward-tracked issue database. Issues that survive to
HEAD are verified against current code and emitted as detail.dev-format
findings.

The default walk is one of several **variants** (see "Variant walks" below).
The variants are not run automatically by the default walk — so they are easy
to forget. The orchestrator (`run.py`) exists to make that impossible.

## When invoked interactively

When the user runs `/audit-temporal-walk`, do NOT silently start the default
walk. Instead, present the full battery so nothing is forgotten, then launch
the orchestrator:

1. Run `python3 .claude/skills/audit-temporal-walk/run.py --list` and show the
   variants with `AskUserQuestion` (multi-select; default to **all**). This
   menu is the reminder — the user consciously picks the battery each time.
2. Create one tracked task per selected variant so progress is visible.
3. Launch `run.py --variants <chosen>` (or `--all`) under `nohup`/`tmux` — the
   battery is multi-hour and must survive the session. Suggest the strongest
   model and `--effort max` for a quality-critical run.
4. Point the user at the live logs and the combined `findings-ALL.md`.

## When to run

- Before a major release, as a final-pass audit
- After a long sequence of refactors, to catch half-fixes
- Once per several months as a baseline audit
- **Not** for routine pre-commit review (use `/review-change` for that)

## How to run

The walker uses the `claude` CLI's default model (the session's current
model) unless `--model` is passed. For a heavyweight rare-run audit, prefer
running it in a session on the strongest model available.

```bash
# Walk (long-running; safe to interrupt — resumable):
python3 .claude/skills/audit-temporal-walk/walker.py

# In tmux/nohup for multi-hour reliability:
nohup python3 .claude/skills/audit-temporal-walk/walker.py \
  > .claude/reports/audit-temporal-walk-<module>/walk.log 2>&1 &

# Process N commits then stop cleanly (useful for chunked runs):
python3 .claude/skills/audit-temporal-walk/walker.py --max-commits 200

# Disable inner-model tool access (faster, less accurate — see Design notes):
python3 .claude/skills/audit-temporal-walk/walker.py --no-tools

# Inspect state without running:
python3 .claude/skills/audit-temporal-walk/walker.py --summary

# After the walk completes, verify surviving issues against HEAD
# (default --min-confidence=low verifies every survivor):
python3 .claude/skills/audit-temporal-walk/verify.py

# Read the verified findings:
cat .claude/reports/audit-temporal-walk-<module>/findings.md
```

Wall clock depends on model and effort and is dominated by model latency:
roughly 8-14 hours on a mid-tier config, and ~24 hours on Opus + max effort
(the recommended quality-critical config) for the full caffeine module (~760
commits). Tool-enabled mode (default) adds modest
overhead from per-commit Read/Grep round-trips. Resumable from checkpoint
after quota exhaustion or interruption.

## Variant walks

The default run is a broad bug hunt over `caffeine/src/main`. The same engine
drives several focused variants — each is a *separate* full (or filtered) walk
with its own multi-hour cost, not an addition to the main run. `--run-name`
gives each one a disjoint `state-<name>.json` / `log-<name>/` / `worktree-<name>/`
so they don't clobber the main walk and can run concurrently. Verify a variant
with the matching `--run-name` (and `WALKER_SCOPE` for the test walk).

### Orchestrated battery (recommended — the don't-forget path)

`run.py` runs a selected set of variants **sequentially** (walk + verify each),
then aggregates every `findings-<name>.md` into one `findings-ALL.md` with a
summary table. It is resumable: each variant is checkpointed independently, so
re-running picks up where it stopped and skips finished variants cheaply. This
is the entry point to prefer — one command runs everything and reports on it.

```bash
R=.claude/skills/audit-temporal-walk/run.py
python3 $R --list                       # show the battery
nohup python3 $R --all --effort max \   # whole battery, quality config, in tmux/nohup
  > .claude/reports/audit-temporal-walk-caffeine/battery.log 2>&1 &
python3 $R --variants fix-audit,lens-sibling   # a chosen subset
python3 $R --all --report-only          # just rebuild findings-ALL.md
```

Sequential is deliberate: the variants are independent and `--run-name` makes
them parallel-safe, but running one at a time matches the one-active-script
discipline and avoids hammering quota — there is no quality gain from
parallelism (sharpness is per-prompt, not per-schedule).

### Individual variants (focused one-offs)

To run or resume a single variant directly:

```bash
W=.claude/skills/audit-temporal-walk
SCOPE_TEST=caffeine/src/test/java/com/github/benmanes/caffeine/cache/

# (#2) Diff-shape lenses — main scope, one concentrated question each.
#      Run individually; sharpness is the point. Three separate walks.
python3 $W/walker.py --prompt $W/lens-deletion.txt --run-name lens-deletion
python3 $W/walker.py --prompt $W/lens-sibling.txt  --run-name lens-sibling
python3 $W/walker.py --prompt $W/lens-intent.txt   --run-name lens-intent
python3 $W/verify.py --run-name lens-deletion        # etc. per lens

# (#4) Fix-commit walk — only commits whose message looks like a fix
#      (~39% of history). Pass the SAME --grep on every resume.
python3 $W/walker.py --prompt $W/fix-audit.txt --run-name fix-audit \
  --grep 'fix|bug|regression|NPE|race|leak|incorrect|wrong|revert'
python3 $W/verify.py --run-name fix-audit

# (#1) Test-history walk — coverage-regression hunt over the TEST tree.
#      The test scope routes to a disjoint  ...-caffeine-test/  reports dir.
#      Pass WALKER_SCOPE to verify.py too.
WALKER_SCOPE=$SCOPE_TEST python3 $W/walker.py \
  --prompt $W/test-walk.txt --run-name coverage
WALKER_SCOPE=$SCOPE_TEST python3 $W/verify.py --run-name coverage

# (#3) Invariant ledger — carries load-bearing assumptions forward and flags a
#      distant commit that violates one. Violations materialize as issues, so
#      verify/findings work unchanged. Full re-walk (the ledger builds from
#      genesis; it can't be backfilled onto the main run).
python3 $W/walker.py --prompt $W/invariant-ledger.txt --run-name invariants
python3 $W/verify.py --run-name invariants
```

All variants share the `resolved/modified/new` finding schema and emit
`findings-<name>.md`. The invariant ledger additionally tracks
`establish/violate/retire` in `state-<name>.json` (see `--summary`'s
"Invariants by status" line); a violation is also written as a normal issue so
it flows through verification like any other finding.

## What the walker does

For each substantive commit (skipping doc/style/dep-bump only), the walker:
1. Checks out the commit into a managed detached worktree under
   `.claude/reports/audit-temporal-walk-<module>/worktree/`
2. Invokes `claude -p` with `cwd=worktree` and `--tools "Read,Glob,Grep"`,
   so the inner model can verify hypotheses against the codebase **at that
   commit's state**, not HEAD
3. Shows the commit's diff (scoped to the configured module) and the
   currently-open tracked issues whose files this commit touches

Claude returns deltas: which open issues this commit *resolves*, which it
*modifies* (e.g., a contract change makes the issue more dangerous), and any
*new* concerns the commit introduces. Each new concern requires a concrete
bug witness — the input or scenario that exposes the failure, expressed
strongly enough that a developer could write a failing unit test directly
from it.

The pattern catalog and design-priors in `per-commit.txt` are tuned to
caffeine's bug history (operator-order in halving formulas, sibling
divergence between sync/async paths, missing lifecycle guards, etc.) and
caffeine's documented intentional patterns (lossy buffers, best-effort
refresh, async-listener semantics).

## What the verifier does

After the walk, `verify.py` reads each surviving open issue, grounds it
against current HEAD code (file-grep ranks files by symbol-match-count to
find code that has moved/renamed since introduction), and asks Claude
whether the bug witness still applies. Verdicts: `still_exists`,
`implicitly_resolved`, `false_positive`. The verifier prompt includes
`.claude/docs/design-decisions.md` and `cross_model_audit_results.md` as
filter sources.

An interrupted verify (quota/CLI error) records the unreached issues as
`error` and **retries them on the next resume** — `verify.py` skips
non-error verdicts but re-attempts errored ones. When any `error` remains,
verify.py prints an "INCOMPLETE VERIFY" warning, marks the finding count
PROVISIONAL in `findings-<name>.md` (and `⚠️+N?` in `findings-ALL.md`), and
**exits 3** so a partial verify isn't mistaken for a complete one. A verify
is only truly done when its `verified-<name>.json` has zero `error` verdicts.

Output is a detail.dev-format markdown report with full commit lineage
already attached to each finding.

## Output

The reports directory is auto-derived from `WALKER_SCOPE`: the first path
segment (the module name) plus a `-test` discriminator when the scope is a
test tree. So `caffeine/src/main/...` writes to
`.claude/reports/audit-temporal-walk-caffeine/`, `caffeine/src/test/...` to
`audit-temporal-walk-caffeine-test/`, jcache to `audit-temporal-walk-jcache/`,
etc. All outputs are gitignored via `.claude/reports/`:

- `state.json` — walker's issue database (and the invariant ledger, when used)
- `verified.json` — per-issue verdicts
- `findings.md` — detail.dev-format report
- `worktree/` — managed detached worktree used for per-commit snapshots
  (deleting it is safe; the next walk re-creates it)
- `log/<sha>.raw.json` — per-commit raw responses
- `verify-log/<id>.raw.json` — per-issue verifier responses

A `--run-name <name>` variant writes the same set under `<name>`-suffixed
paths in the same module dir: `state-<name>.json`, `verified-<name>.json`,
`findings-<name>.md`, `log-<name>/`, `verify-log-<name>/`, `worktree-<name>/`.

After running, the walker's findings should still be reviewed by hand —
expect ~30-40% true-positive rate among surviving findings, with the rest
being subtle design-intent matches that the priors don't quite cover.

## What to do with a finding

For each `still_exists` finding in `findings.md`:
1. Read the lineage to understand why the bug exists
2. Cross-check against `.claude/docs/design-decisions.md` and
   `~/.claude/projects/-Users-ben-projects-caffeine/memory/cross_model_audit_results.md`
3. Write a failing test that exposes the bug witness
4. If the test confirms, fix and commit. If the test passes (false
   positive), record the case in `cross_model_audit_results.md` so future
   audits don't re-raise it.

## Design notes

- **Forward-tracked, not snapshot-mined.** Catches half-fixes and latent+trigger pairs invisible from current state. See `README.md` for the design rationale and how this differs from `/audit-*` snapshot-style audits.
- **Resumable.** State is persisted after every commit. Quota exhaustion or interruption leaves the next-commit pointer at the last successful commit; re-running picks up from there.
- **Tools scoped to the commit snapshot.** The inner `claude -p` runs with `cwd` set to a detached worktree checked out at the commit being analyzed, and tools restricted to `Read,Glob,Grep`. This lets the model verify hypotheses against surrounding code (callers, sibling implementations, full method bodies outside the diff hunk) without seeing HEAD code from future commits — which would collapse the forward-tracking premise (every "issue" would look already fixed by some later commit). `--no-tools` falls back to diff-only analysis. `--disable-slash-commands` is always on.
- **Self-grounding.** The verifier prompt requires that quoted code be copied verbatim from the shown HEAD code; verdicts that reference symbols not present in HEAD must return `implicitly_resolved`. This was load-bearing in early validation: the first verifier run hallucinated a finding citing a nonexistent file, fixed by hardening the grounding rules.
