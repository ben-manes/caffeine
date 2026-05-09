#!/usr/bin/env python3
"""
Temporal walker for git history bug-hunting.

Walks every commit affecting a configured module path, oldest-first. Asks
Claude per commit to flag/resolve/modify a forward-tracked issue database;
issues that survive to HEAD are candidate bugs (verified by verify.py).

State persists to JSON after every commit. Quota exhaustion, parse errors,
or interruption preserve the last successful checkpoint and exit non-zero;
re-running picks up at the next commit.
"""

import argparse
import hashlib
import json
import os
import shutil
import string
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

DEFAULT_SCOPE = "caffeine/src/main/java/com/github/benmanes/caffeine/cache/"
SCOPE = os.environ.get("WALKER_SCOPE", DEFAULT_SCOPE)
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[2]
# Derive the reports directory from the first segment of the configured SCOPE so
# audits of different modules (caffeine, jcache, ...) write to disjoint trees and
# don't clobber each other's state.json / log / worktree.
_MODULE = SCOPE.split("/", 1)[0] if "/" in SCOPE else "default"
REPORTS_DIR = REPO_ROOT / ".claude" / "reports" / f"audit-temporal-walk-{_MODULE}"
DEFAULT_STATE = REPORTS_DIR / "state.json"
DEFAULT_PROMPT = SCRIPT_DIR / "per-commit.txt"
DEFAULT_LOG_DIR = REPORTS_DIR / "log"
WORKTREE_DIR = REPORTS_DIR / "worktree"
MAX_DIFF_BYTES = 120_000
TRUNCATE_HEAD_FRACTION = 0.5
TRUNCATE_TAIL_FRACTION = 0.25
SNAPSHOT_TOOLS = "Read,Glob,Grep"

TOOL_INSTRUCTIONS_SNAPSHOT = string.Template(
    "You have Read, Glob, and Grep available. Your working directory is\n"
    "$worktree_path — a snapshot of the codebase at THIS commit, NOT HEAD.\n"
    "Use tools for VERIFICATION ONLY, not exploration: confirm a hypothesis\n"
    "raised by the diff (e.g., \"is this method called elsewhere?\", \"what\n"
    "does the rest of this method look like outside the hunk?\", \"does the\n"
    "sibling async path have the same guard?\"). One or two targeted calls\n"
    "per commit at most. Default to no-tools-needed.\n"
    "RULES:\n"
    "  - Only access paths under $worktree_path. Absolute paths that point\n"
    "    elsewhere (e.g., the main repo path) would expose newer code from\n"
    "    future commits and corrupt the temporal walk.\n"
    "  - Don't crawl the codebase. The diff and the catalog are the primary\n"
    "    signal; tools are a tiebreaker."
)
TOOL_INSTRUCTIONS_NONE = (
    "No tools are available — analyze only what is shown in the diff and\n"
    "the open issues list."
)


# --------------------------------------------------------------------------
# JSON extraction
# --------------------------------------------------------------------------

def extract_balanced_json(text: str, required_keys: tuple[str, ...]) -> dict:
    """Find a balanced top-level JSON object in `text`, tolerant to prose
    preambles or markdown code fences.

    Iterates each `{`, walks brace depth honoring quoted strings, and returns
    the first balanced object that parses and contains at least one of
    `required_keys`. Raises ValueError if none found.
    """
    candidates: list[tuple[int, int]] = []
    n = len(text)
    i = 0
    while i < n:
        if text[i] != "{":
            i += 1
            continue
        depth = 0
        in_str = False
        escape = False
        for j in range(i, n):
            ch = text[j]
            if in_str:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    candidates.append((i, j + 1))
                    break
        i += 1

    for start, end in candidates:
        try:
            obj = json.loads(text[start:end])
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict) and any(k in obj for k in required_keys):
            return obj

    # Repair-fallback for trailing-brace truncation. The walker has observed
    # the model emitting a complete-looking response (stop_reason=end_turn)
    # whose tail is missing a closing `}` or `]` — the prefix is well-formed
    # but the outer object never balances. Re-walk the first `{` tracking the
    # bracket stack; if the text ended at depth>0, append the matching
    # closers and try once more.
    start = text.find("{")
    if start != -1:
        stack: list[str] = []
        in_str = False
        escape = False
        last_complete = start  # last index where stack was empty
        for j in range(start, n):
            ch = text[j]
            if in_str:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch in "{[":
                stack.append(ch)
            elif ch in "}]":
                if stack:
                    stack.pop()
                if not stack:
                    last_complete = j + 1
        if stack:
            tail = text[start:].rstrip().rstrip(",")
            repair = "".join("}" if c == "{" else "]" for c in reversed(stack))
            try:
                obj = json.loads(tail + repair)
                if isinstance(obj, dict) and any(k in obj for k in required_keys):
                    return obj
            except json.JSONDecodeError:
                pass

    raise ValueError(f"No parseable response JSON found in: {text[:600]}")


# --------------------------------------------------------------------------
# Git
# --------------------------------------------------------------------------

def run_git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout


def list_commits() -> list[str]:
    out = run_git("log", "--reverse", "--format=%H", "--", SCOPE)
    return [s for s in out.strip().split("\n") if s]


def ensure_worktree() -> Path:
    """Create or reuse a detached worktree under .claude/reports/.

    Used to materialize per-commit code so the inner model can Read/Glob/Grep
    at that commit's state without seeing newer code from the main worktree.
    Survives across runs — re-using avoids the per-run worktree-add cost.
    """
    if WORKTREE_DIR.exists() and (WORKTREE_DIR / ".git").exists():
        return WORKTREE_DIR
    if WORKTREE_DIR.exists():
        # Stale dir without a worktree linkage — clear and re-create.
        shutil.rmtree(WORKTREE_DIR)
    # Prune unconditionally: handles both "stale dir" and "dir gone but
    # registration survives" cases. No-op if nothing to prune.
    run_git("worktree", "prune")
    WORKTREE_DIR.parent.mkdir(parents=True, exist_ok=True)
    run_git("worktree", "add", "--detach", str(WORKTREE_DIR), "HEAD")
    return WORKTREE_DIR


def checkout_at(worktree: Path, sha: str) -> None:
    """Switch the worktree to a specific commit. --force discards any local
    state from a prior checkout (we never write to the worktree, so this
    just guards against partial writes from interrupted runs)."""
    run_git("-C", str(worktree), "checkout", "--force", "--detach", sha)


_METADATA_SENTINEL = "----audit-temporal-walk-metadata-end----"


def commit_metadata(sha: str) -> tuple[str, str, list[str]]:
    """Return (date, full_message, scope_relative_files) in one git invocation.
    Uses %B so the auditor sees the full commit body, not just the subject —
    bodies often contain "passes the X.Y TCK" or "fixes #NNN" hints that
    materially change whether a diff looks like a bug or an intentional
    contract change. A sentinel separates the format output from the
    --name-only file list since %B is multi-line."""
    out = run_git(
        "show", "--name-only",
        f"--format=%aI%n%B%n{_METADATA_SENTINEL}",
        sha, "--", SCOPE,
    )
    head, _, tail = out.partition(f"\n{_METADATA_SENTINEL}\n")
    head_lines = head.split("\n", 1)
    date = head_lines[0] if head_lines else ""
    message = head_lines[1].rstrip() if len(head_lines) > 1 else ""
    files = []
    for line in tail.splitlines():
        line = line.strip()
        if line.startswith(SCOPE):
            files.append(line[len(SCOPE):])
    return date, message, files


def commit_diff(sha: str) -> str:
    out = run_git("show", sha, "--", SCOPE)
    if len(out) <= MAX_DIFF_BYTES:
        return out
    head_size = int(MAX_DIFF_BYTES * TRUNCATE_HEAD_FRACTION)
    tail_size = int(MAX_DIFF_BYTES * TRUNCATE_TAIL_FRACTION)
    return (f"{out[:head_size]}\n\n"
            f"[DIFF TRUNCATED - {len(out)} bytes total, "
            f"showing first {head_size} and last {tail_size}]\n\n"
            f"{out[-tail_size:]}")


# --------------------------------------------------------------------------
# State
# --------------------------------------------------------------------------

def issue_id(description: str, first_seen: str) -> str:
    h = hashlib.sha256(f"{description}|{first_seen}".encode()).hexdigest()[:8]
    return f"iss_{h}"


def normalize_files(files: list[str]) -> list[str]:
    """Strip the configured SCOPE prefix from issue file paths and remove any
    leading slash. The model is asked to emit scope-relative names but
    sometimes returns scope-prefixed or absolute paths; storing canonical
    relative paths keeps the open-issue filter (which matches against
    commit_metadata's scope-stripped file list) reliable."""
    out: list[str] = []
    for f in files:
        s = (f or "").strip()
        if not s:
            continue
        if s.startswith(SCOPE):
            s = s[len(SCOPE):]
        elif "/" + SCOPE in ("/" + s):
            idx = s.find(SCOPE)
            if idx >= 0:
                s = s[idx + len(SCOPE):]
        s = s.lstrip("/")
        out.append(s)
    return out


def init_state(state_path: Path, commits: list[str]) -> dict:
    state = {
        "schema_version": 1,
        "scope": {
            "module_path": SCOPE,
            "start_commit": commits[0],
            "end_commit": commits[-1],
            "total_commits": len(commits),
        },
        "checkpoint": {
            "last_processed_commit": None,
            "last_processed_index": -1,
        },
        "issues": {},
        "stats": {
            "commits_processed": 0,
            "commits_skipped_no_scope": 0,
            "commits_no_changes": 0,
            "issues_open": 0,
            "issues_resolved": 0,
            "ai_calls": 0,
            "errors": 0,
        },
    }
    save_state(state, state_path)
    return state


def save_state(state: dict, state_path: Path) -> None:
    state_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = state_path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(state_path)


EXPECTED_STAT_KEYS = (
    "commits_processed", "commits_skipped_no_scope", "commits_no_changes",
    "issues_open", "issues_resolved", "ai_calls", "errors",
)


def load_state(state_path: Path) -> dict:
    """Load state and migrate older schemas in-place (no save until next write)."""
    state = json.loads(state_path.read_text())

    # Backfill `times_touched` on issues created before that field existed.
    for issue in state.get("issues", {}).values():
        if "times_touched" not in issue:
            issue["times_touched"] = sum(
                1 for h in issue.get("history", []) if h.get("action") == "modified"
            )

    # Normalize issue file paths to scope-relative form. Older entries may
    # have stored scope-prefixed or absolute paths, which the open-issue
    # filter (matching against scope-stripped commit_metadata files) would
    # never hit, hiding resolutions.
    for issue in state.get("issues", {}).values():
        issue["files"] = normalize_files(issue.get("files", []))

    # Migrate older `commits_doc_only` stat key to `commits_no_changes`.
    stats = state.setdefault("stats", {})
    if "commits_doc_only" in stats and "commits_no_changes" not in stats:
        stats["commits_no_changes"] = stats.pop("commits_doc_only")
    for key in EXPECTED_STAT_KEYS:
        stats.setdefault(key, 0)

    return state


def print_summary(state: dict) -> None:
    """Human-readable summary of walker state. No AI calls."""
    scope = state.get("scope", {})
    cp = state.get("checkpoint", {})
    stats = state.get("stats", {})
    total = scope.get("total_commits", "?")
    last_idx = cp.get("last_processed_index", -1)

    print(f"Scope: {scope.get('module_path', '?')}")
    print(f"Range: {scope.get('start_commit', '?')[:8]} -> "
          f"{scope.get('end_commit', '?')[:8]}")
    print(f"Total in scope: {total}")
    print(f"Last processed: {(cp.get('last_processed_commit') or '?')[:8]} "
          f"(processed {last_idx + 1} of {total})")
    print()
    print("Stats:")
    for k, v in stats.items():
        print(f"  {k}: {v}")
    print()

    issues = state.get("issues", {})
    by_status = Counter(i.get("status", "?") for i in issues.values())
    print(f"Issues by status: {dict(by_status)}")

    open_issues = [i for i in issues.values() if i.get("status") == "open"]
    if open_issues:
        by_conf = Counter(i.get("confidence", "?") for i in open_issues)
        by_pat = Counter(i.get("pattern_match") or "novel" for i in open_issues)
        print(f"Open by confidence: {dict(by_conf)}")
        print(f"Open by pattern: {dict(by_pat.most_common())}")


# --------------------------------------------------------------------------
# Prompt
# --------------------------------------------------------------------------

def render_prompt(template: string.Template, sha: str, date: str, message: str,
                  files: list[str], state: dict, worktree: Path | None) -> str:
    """Build the per-commit prompt. Uses string.Template ($name) so the
    template can contain literal `{` and `}` (e.g., example JSON output)
    without escaping."""
    open_in_scope = [
        i for i in state["issues"].values()
        if i["status"] == "open" and any(f in files for f in i["files"])
    ]
    if worktree is not None:
        tool_instructions = TOOL_INSTRUCTIONS_SNAPSHOT.substitute(
            worktree_path=str(worktree)
        )
    else:
        tool_instructions = TOOL_INSTRUCTIONS_NONE
    return template.substitute(
        sha=sha,
        date=date,
        message=message,
        files=", ".join(files) if files else "(none in scope)",
        diff=commit_diff(sha),
        open_issues=json.dumps(open_in_scope, indent=2),
        tool_instructions=tool_instructions,
    )


# --------------------------------------------------------------------------
# Claude CLI
# --------------------------------------------------------------------------

def call_claude(prompt: str, model: str | None, effort: str | None,
                log_dir: Path, sha: str, cwd: Path | None, tools: str) -> dict:
    log_dir.mkdir(parents=True, exist_ok=True)
    cmd = ["claude", "-p"]
    if model is not None:
        cmd += ["--model", model]
    if effort is not None:
        cmd += ["--effort", effort]
    cmd += [
        "--no-session-persistence",
        "--tools", tools,
        "--disable-slash-commands",
        "--output-format", "json",
    ]
    proc = subprocess.run(
        cmd, input=prompt, capture_output=True, text=True, timeout=1800,
        cwd=str(cwd) if cwd is not None else None,
    )
    (log_dir / f"{sha}.raw.json").write_text(proc.stdout)

    if proc.returncode != 0:
        raise RuntimeError(f"claude exit={proc.returncode} stderr={proc.stderr[:500]}")
    envelope = json.loads(proc.stdout)
    if envelope.get("is_error"):
        raise RuntimeError(f"claude error: {envelope.get('result', '')[:500]}")
    return extract_balanced_json(
        envelope.get("result", ""),
        required_keys=("resolved", "modified", "new"),
    )


# --------------------------------------------------------------------------
# State updates
# --------------------------------------------------------------------------

def apply_response(response: dict, state: dict, sha: str, date: str
                   ) -> tuple[int, int, int]:
    """Apply Claude's resolved/modified/new deltas to state. Returns counts."""
    new_count = resolved_count = modified_count = 0
    issues = state["issues"]

    for r in response.get("resolved", []):
        iid = r.get("id")
        issue = issues.get(iid)
        if issue is None or issue["status"] != "open":
            continue
        issue["status"] = "resolved"
        issue["last_updated_commit"] = sha
        issue["history"].append({
            "commit": sha, "action": "resolved",
            "note": r.get("evidence", "")[:500],
        })
        resolved_count += 1

    for m in response.get("modified", []):
        iid = m.get("id")
        issue = issues.get(iid)
        if issue is None or issue["status"] != "open":
            continue
        issue["last_updated_commit"] = sha
        issue["times_touched"] = issue.get("times_touched", 0) + 1
        issue["history"].append({
            "commit": sha, "action": "modified",
            "note": m.get("update", "")[:500],
        })
        modified_count += 1

    for n in response.get("new", []):
        desc = n.get("description", "").strip()
        if not desc or not n.get("bug_witness"):
            continue
        iid = issue_id(desc, sha)
        if iid in issues:
            continue
        issues[iid] = {
            "id": iid,
            "description": desc,
            "files": normalize_files(n.get("files", [])),
            "first_seen_commit": sha,
            "first_seen_date": date,
            "last_updated_commit": sha,
            "status": "open",
            "confidence": n.get("confidence", "med"),
            "pattern_match": n.get("pattern_match"),
            "bug_witness": n.get("bug_witness", ""),
            "times_touched": 0,
            "history": [{"commit": sha, "action": "introduced"}],
        }
        new_count += 1

    state["stats"]["issues_open"] = sum(
        1 for i in issues.values() if i["status"] == "open"
    )
    state["stats"]["issues_resolved"] = sum(
        1 for i in issues.values() if i["status"] == "resolved"
    )
    return new_count, resolved_count, modified_count


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Walk git history with Claude to find latent bugs.")
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--prompt", type=Path, default=DEFAULT_PROMPT)
    parser.add_argument("--log-dir", type=Path, default=DEFAULT_LOG_DIR)
    parser.add_argument(
        "--model", default=None,
        help="Claude model alias or full name. If omitted, uses the claude "
             "CLI's default (the session model).")
    parser.add_argument(
        "--effort", default=None,
        choices=["low", "medium", "high", "xhigh", "max"],
        help="Reasoning effort level for the inner model. Higher levels "
             "use more thinking tokens per commit; 'max' is appropriate "
             "for a heavyweight rare-run audit.")
    parser.add_argument(
        "--max-commits", type=int, default=None,
        help="Process at most N commits this run, then exit cleanly.")
    parser.add_argument(
        "--no-tools", action="store_true",
        help="Disable inner-model tool access. Skips worktree setup and "
             "feeds only the diff and open issues. Faster but less accurate "
             "— the model can't verify hypotheses against surrounding code.")
    parser.add_argument("--init", action="store_true",
                        help="Initialize state and exit.")
    parser.add_argument("--reset", action="store_true",
                        help="Back up existing state and reinitialize.")
    parser.add_argument("--summary", action="store_true",
                        help="Print state summary and exit (no AI calls).")
    args = parser.parse_args()

    if args.summary:
        if not args.state.exists():
            sys.exit(f"No state file at {args.state}")
        print_summary(load_state(args.state))
        return

    if args.reset and args.state.exists():
        backup = args.state.with_suffix(".json.bak")
        shutil.copy(args.state, backup)
        args.state.unlink()
        print(f"Reset. Previous state backed up at {backup}")

    commits = list_commits()
    if not commits:
        sys.exit(f"No commits found in scope: {SCOPE}")

    if not args.state.exists():
        state = init_state(args.state, commits)
        print(f"Initialized state at {args.state}. "
              f"Total commits in scope: {len(commits)}")
        if args.init:
            return
    else:
        state = load_state(args.state)
        last_idx = state["checkpoint"]["last_processed_index"]
        print(f"Resuming: {last_idx + 1} of {len(commits)} commits processed")

    template = string.Template(args.prompt.read_text())
    last_idx = state["checkpoint"]["last_processed_index"]
    processed_this_run = 0
    start_time = time.time()

    if args.no_tools:
        worktree = None
        tools = ""
        print("Tools disabled (--no-tools): inner model sees only diff + open issues.")
    else:
        worktree = ensure_worktree()
        tools = SNAPSHOT_TOOLS
        print(f"Snapshot worktree: {worktree} (tools: {tools})")

    for idx in range(last_idx + 1, len(commits)):
        if args.max_commits and processed_this_run >= args.max_commits:
            print(f"Hit --max-commits={args.max_commits}, stopping cleanly")
            break

        sha = commits[idx]
        date, message, files = commit_metadata(sha)

        if not files:
            state["checkpoint"]["last_processed_commit"] = sha
            state["checkpoint"]["last_processed_index"] = idx
            state["stats"]["commits_skipped_no_scope"] += 1
            save_state(state, args.state)
            continue

        try:
            if worktree is not None:
                checkout_at(worktree, sha)
            prompt = render_prompt(template, sha, date, message, files, state, worktree)
            response = call_claude(prompt, args.model, args.effort,
                                   args.log_dir, sha, cwd=worktree, tools=tools)
            n_new, n_res, n_mod = apply_response(response, state, sha, date)

            state["checkpoint"]["last_processed_commit"] = sha
            state["checkpoint"]["last_processed_index"] = idx
            state["stats"]["commits_processed"] += 1
            state["stats"]["ai_calls"] += 1
            if n_new == 0 and n_res == 0 and n_mod == 0:
                state["stats"]["commits_no_changes"] += 1

            save_state(state, args.state)

            elapsed = time.time() - start_time
            rate = processed_this_run / elapsed if elapsed > 0 else 0
            short_msg = (message[:55] + "…") if len(message) > 56 else message
            print(f"[{idx + 1:4d}/{len(commits)}] {sha[:8]} {short_msg:<56} "
                  f"+{n_new} ~{n_mod} -{n_res} | "
                  f"open={state['stats']['issues_open']} "
                  f"({rate:.2f}/s)")
            processed_this_run += 1

        except Exception as e:
            state["stats"]["errors"] += 1
            save_state(state, args.state)
            print(f"\nError at idx={idx} sha={sha[:8]}: {e}", file=sys.stderr)
            print("State preserved. Re-run to resume.", file=sys.stderr)
            sys.exit(2)

    stats = state["stats"]
    print(f"\nDone this run. Processed {processed_this_run} commits.")
    print(f"Total: {stats['commits_processed']} processed, "
          f"{stats['commits_skipped_no_scope']} skipped, "
          f"{stats['issues_open']} open, "
          f"{stats['issues_resolved']} resolved.")
    if state["checkpoint"]["last_processed_index"] >= len(commits) - 1:
        print("Walk complete. Run verify.py next.")


if __name__ == "__main__":
    main()
