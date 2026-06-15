# audit-temporal-walk

Heavyweight history-mining bug audit. Walks every commit in the caffeine
module's git history chronologically, maintains a forward-tracked issue
database, and surfaces concerns introduced by past commits that were never
resolved.

See `SKILL.md` for skill metadata. This README covers the *why* and the
*how it works*.

## Why temporal walking

The `/audit-*` skills are snapshot-style: they read current code and look
for anomalies. They cannot see:

- **Half-fixes invisible from current state.** A commit that fixes pattern X
  in 3 of 4 sibling paths leaves the 4th broken. Without the contrast of
  the introducing commit + sibling fix commit, the broken path can look
  like "just the way that code is".
- **Latent + trigger pairs.** A commit introduces a marginal concern
  (innocent at the time). A later, unrelated commit changes a downstream
  contract (e.g., a method that previously returned a sentinel now throws)
  that makes the marginal concern dangerous. Snapshot mining sees neither
  individual commit as suspicious.
- **Refactor blast radius.** When commit A renames or moves code, the
  conceptual concern survives but the file path doesn't. A snapshot grep
  for the original symbol misses it.

The temporal walker addresses these by walking commit-by-commit:

- Each commit is judged in isolation. Concerns get flagged at the moment
  they're introduced, when the contrast is highest.
- An issue database persists across commits. Later commits resolve, modify,
  or leave issues open. Issues surviving to HEAD are candidate bugs.
- The walker is forward-only — at flag time, it can't know whether a fix is
  coming. That asymmetry is what makes "still open at HEAD" a meaningful
  signal rather than just "AI thinks something looks weird".

## Architecture

```
walker.py + per-commit.txt
    ├── git log --reverse → list commits
    ├── ensure detached worktree at .claude/reports/.../worktree/
    ├── git show <sha> -- <scope> → diff per commit
    ├── git checkout <sha> in the worktree (snapshot at commit time)
    ├── claude -p (per commit, cwd=worktree, tools=Read/Glob/Grep)
    │     prompt: diff + open issues in affected files
    │     model can verify hypotheses against the snapshot
    │     output: { resolved, modified, new }
    ├── apply deltas to state.json
    └── checkpoint after each commit (resumable)

verify.py + verify.txt
    ├── read state.json, filter by min-confidence
    ├── for each open issue:
    │     read affected files at HEAD
    │     auto-grep keywords to find moved/renamed code
    │     claude -p with hardened grounding rules
    │     output: { verdict, finding_markdown }
    └── write findings.md
```

## Walk variants

The same engine (`walker.py`, forward-tracked state, `verify.py`) drives several
focused walks that differ only in **prompt**, **scope**, and **commit filter**.
They occupy cells of an audit grid that the default walk leaves empty:

|                       | snapshot (`/audit-*`) | longitudinal (this walk) |
|-----------------------|-----------------------|--------------------------|
| **main source**       | covered               | default walk             |
| **tests**             | partial               | test-history walk (#1)   |
| **a derived model**   | —                     | invariant ledger (#3)    |

The reason a longitudinal counterpart adds signal: it converts a *fuzzy global*
judgment into a *sharp local* one. Snapshot sibling-divergence must prove two
200-line methods are equivalent today; temporal sibling-parity only asks "was
*this one diff* mirrored?". Snapshot coverage-gap asks "what is untested now?";
the test walk asks "what assertion did *this commit* remove?".

- **Diff-shape lenses (#2)** — `lens-deletion`, `lens-sibling`, `lens-intent`.
  Each concentrates the per-commit question on a single high-yield shape:
  removed guards (Chesterton's Fence), an edit applied to one sibling path but
  not its mirror, and a commit whose diff does less (incomplete) or more
  (overreach) than its message claims. Run individually — concentration is what
  finds what the broad prompt's divided attention misses.
- **Fix-commit walk (#4)** — `fix-audit` + `--grep`. Visits only fix/bug/
  regression commits (~39% of history) and pressure-tests each: is the fix
  complete, is the sibling path fixed too, does the root-cause shape recur
  elsewhere? Cheapest variant; every fixed bug is a clue to an unfixed one.
- **Test-history walk (#1)** — `test-walk` over `caffeine/src/test/...`. Hunts
  *coverage regressions*: assertions loosened, cases deleted, `@CacheSpec`
  matrices narrowed. A silently weakened test is invisible to every code audit.
  Legitimate relaxations (GC/timing flakiness, Awaitility timeout bumps) are
  filtered. Routes to a disjoint `...-caffeine-test/` reports dir.
- **Invariant ledger (#3)** — `invariant-ledger`. Forward-tracks load-bearing
  *assumptions* rather than defects. Each commit may establish a new invariant,
  violate an existing one (materialized as a tracked issue), or retire one it
  deliberately redesigns. The unique catch is a commit that breaks an invariant
  established far away — a latent+trigger pair across *distant* commits, which
  diff-local reasoning cannot see. The whole-ledger index is shown each commit
  so a cross-file violation stays visible; the prompt is deliberately
  parsimonious about establishing, or the ledger bloats and loses its edge.

**Cost.** Each variant is its own full walk (the fix-commit walk is filtered, so
cheaper). They are *not* additive to the main run — a variant re-reads history
under its own lens. Pick the ones worth a multi-hour run deliberately. Variants
isolate via `--run-name` (disjoint state/log/worktree), so they can run
concurrently with each other and the main walk.

**Orchestration.** `run.py` is the don't-forget entry point: it runs a selected
set of variants sequentially (walk + verify each) and aggregates every
`findings-<name>.md` into one `findings-ALL.md`. It is resumable per variant, so
an interrupted battery picks up where it stopped. The skill is wired to present
the full variant menu on invocation (`run.py --list` + a multi-select prompt),
so the battery is chosen consciously each time rather than silently skipped.
Sequential by default — parallelism is safe but buys only wall-clock, and the
one-active-script discipline keeps quota and logs sane.

## Resilience

- **Quota tolerance.** Claude failures (network, parse, quota) preserve the
  last successful checkpoint and exit with code 2. Re-running picks up from
  the next unprocessed commit.
- **Doc-only filtering.** Commits that touch no in-scope file are skipped
  cheaply (no Claude call). Commits with only doc/style changes are filtered
  by Claude itself returning empty deltas — these advance the checkpoint
  without growing the issue database.
- **Graceful failure modes.** Parse errors on Claude output bump an
  `errors` counter and exit; the next run retries the failed commit. The
  hardened JSON extractor handles prose preambles and code-fence wrapping.
- **Tools scoped to the commit snapshot.** `claude -p` runs with `cwd`
  set to a managed detached worktree checked out at the commit under
  analysis, and `--tools "Read,Glob,Grep"`. The inner model can verify
  hypotheses against surrounding code (callers, sibling implementations,
  full method bodies outside the diff hunk) — but only as that code
  existed at that commit. Allowing access to HEAD would collapse the
  forward-tracking premise: every "issue" would appear already fixed by
  some later commit. `--no-tools` falls back to diff-only analysis (faster,
  less accurate). `--disable-slash-commands` is always on.

## Files

| File | Purpose |
|------|---------|
| `SKILL.md` | Skill metadata and usage |
| `README.md` | This file |
| `walker.py` | Walk driver (one walk) |
| `run.py` | Battery orchestrator (runs selected variants + combined report) |
| `per-commit.txt` | Per-commit prompt template (default broad bug hunt) |
| `verify.py` | Verification driver |
| `verify.txt` | Verification prompt template |
| `lens-deletion.txt` | Variant: Chesterton's-Fence (removed guards/branches) |
| `lens-sibling.txt` | Variant: sibling-parity (change in A not mirrored to B) |
| `lens-intent.txt` | Variant: intent-vs-effect (commit message vs actual diff) |
| `fix-audit.txt` | Variant: fix-commit completeness (use with `--grep`) |
| `test-walk.txt` | Variant: coverage-regression hunt (use with test `WALKER_SCOPE`) |
| `invariant-ledger.txt` | Variant: forward-tracked invariant ledger |

Mutable state, the snapshot worktree, and reports live under
`.claude/reports/audit-temporal-walk-<module>/` — the suffix is derived
from the first segment of `WALKER_SCOPE`, plus a `-test` discriminator for a
test-tree scope, so caffeine/jcache and main/test audits don't clobber each
other. A `--run-name` variant suffixes its state/log/worktree within that dir.
The whole `.claude/reports/` tree is gitignored at the project root. State
files are local to a developer's run; the worktree is reusable across walks.

## Tuning

The biggest knobs:

- **Pattern catalog** in `per-commit.txt`. Tuned to caffeine's historical
  bug shapes (operator-order in halving, sibling divergence, missing
  lifecycle guards, etc.). When new shapes recur, add to the catalog.
- **Design priors** in `per-commit.txt`. Lists intentional patterns the
  walker should NOT flag. When a recurring false positive is identified
  (cross-model entry added to memory), append the rule here.
- **Confidence threshold** in `verify.py` (`--min-confidence`). Defaults to
  `low` (verify every survivor). Introduction-time confidence is an unreliable
  gate — in practice real bugs are routinely flagged `low` while
  resolved/false-positive concerns score `high`, and the survivor set is small.
  Raise it only to triage verification cost on an unusually large survivor list.
- **Module scope** via the `WALKER_SCOPE` env var. Defaults to
  `caffeine/src/main/java/...`. Other modules (simulator, jcache, guava)
  would need their own design priors and pattern catalog.
- **Model** via `--model` on either driver. Omit to use the `claude` CLI's
  default (the session model). Quality-critical, so prefer running from a
  session on the strongest model available.

## Limitations

- **Doesn't run tests.** A failing test would be the gold-standard
  validation, but running the caffeine test suite per finding is
  intractable. The verifier substitutes "speculative test" descriptions
  in `bug_witness` instead.
- **AI nondeterminism.** Two runs produce different findings. Bigger
  variation than snapshot audits because issues created in one walk may
  not be created identically in another.
- **Prompt quality is the ceiling.** Per-commit overflagging or
  underflagging propagates to the surviving open list. Tune priors when
  patterns recur as false positives.
- **Cross-module signals are lost.** A bug that requires reasoning across
  caffeine + jcache (e.g., the JCache adapter calls a caffeine method
  with a now-throwing contract) won't be caught when scoped to a single
  module.
