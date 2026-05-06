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
| `walker.py` | Walk driver |
| `per-commit.txt` | Per-commit prompt template |
| `verify.py` | Verification driver |
| `verify.txt` | Verification prompt template |

Mutable state, the snapshot worktree, and reports live under
`.claude/reports/audit-temporal-walk/`, which is gitignored at the
project root via `.claude/reports/`. State files are local to a
developer's run; the worktree is reusable across walks.

## Tuning

The biggest knobs:

- **Pattern catalog** in `per-commit.txt`. Tuned to caffeine's historical
  bug shapes (operator-order in halving, sibling divergence, missing
  lifecycle guards, etc.). When new shapes recur, add to the catalog.
- **Design priors** in `per-commit.txt`. Lists intentional patterns the
  walker should NOT flag. When a recurring false positive is identified
  (cross-model entry added to memory), append the rule here.
- **Confidence threshold** in `verify.py` (`--min-confidence`). Default
  filter drops `low` confidence (smells) before paying for verification.
  Use `--min-confidence high` for a tighter triage; `low` for a full sweep.
- **Module scope** via the `WALKER_SCOPE` env var. Defaults to
  `caffeine/src/main/java/...`. Other modules (simulator, jcache, guava)
  would need their own design priors and pattern catalog.
- **Model** via `--model` on either driver. Omit to use the `claude` CLI's
  default (the session model). Quality-critical, so prefer running from a
  session on the strongest model available.

## Validation history

The first end-to-end run produced 8 surviving verified findings out of
72 open issues at walk completion (750 commits walked, 139 issues resolved
forward, 471 doc-only commits filtered).

After hand-review against design docs and cross-model audit memory:
- 3 confirmed real bugs worth fixing
- 1 misunderstanding by the walker (closed)
- 4 design-intent matches the priors didn't fully cover (added to priors
  in subsequent revisions)

That's a 37.5% true-positive rate — comparable to detail.dev-style
snapshot mining but surfacing different bugs (the walker found the
inverted-comparator bug in `expireAfterAccessOrder`, the iterate-twice
bug in `getAll`, and the `refreshAll` Javadoc drift, none of which appear
in detail.dev's caffeine slice).

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
