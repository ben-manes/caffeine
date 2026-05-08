#!/usr/bin/env python3
"""
Verification pass for the temporal walker.

Reads the walker's state file, takes the open issues at or above a confidence
threshold, and asks Claude per issue whether the bug witness still applies to
current HEAD code. Findings that survive verification are emitted as a
detail.dev-format markdown report.

Resumable: skips issues already verified in the verified.json file.
"""

import argparse
import json
import os
import re
import string
import subprocess
import sys
import time
from pathlib import Path

DEFAULT_SCOPE = "caffeine/src/main/java/com/github/benmanes/caffeine/cache/"
SCOPE = os.environ.get("WALKER_SCOPE", DEFAULT_SCOPE)
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[2]
_MODULE = SCOPE.split("/", 1)[0] if "/" in SCOPE else "default"
REPORTS_DIR = REPO_ROOT / ".claude" / "reports" / f"audit-temporal-walk-{_MODULE}"
DEFAULT_STATE = REPORTS_DIR / "state.json"
DEFAULT_VERIFIED = REPORTS_DIR / "verified.json"
DEFAULT_FINDINGS_MD = REPORTS_DIR / "findings.md"
DEFAULT_PROMPT = SCRIPT_DIR / "verify.txt"
DEFAULT_LOG_DIR = REPORTS_DIR / "verify-log"
DEFAULT_DESIGN_DECISIONS = REPO_ROOT / ".claude" / "docs" / "design-decisions.md"
MAX_FILE_BYTES = 300_000
MAX_DESIGN_BYTES = 30_000
MAX_FPS_BYTES = 20_000
MAX_EXTRA_FILES = 3
MAX_GREP_SYMBOLS = 25

CONFIDENCE_RANK = {"high": 3, "med": 2, "low": 1}

# Symbols too generic to be useful keywords for relocating moved code.
COMMON_JAVA_SYMBOLS = frozenset({
    "True", "False", "None", "Object", "String", "Override", "Nullable",
    "boolean", "double", "long", "void", "List", "Map", "Set",
})


# --------------------------------------------------------------------------
# Memory discovery (project-relative, not user-specific)
# --------------------------------------------------------------------------

def default_known_fps_path() -> Path | None:
    """Discover the cross_model_audit_results.md file for this project under
    Claude Code's per-project memory directory. Returns None if not found.
    """
    encoded = str(REPO_ROOT).replace("/", "-")
    candidate = (Path.home() / ".claude" / "projects" / encoded
                 / "memory" / "cross_model_audit_results.md")
    return candidate if candidate.exists() else None


# --------------------------------------------------------------------------
# JSON extraction (mirrors walker.py)
# --------------------------------------------------------------------------

def extract_balanced_json(text: str, required_keys: tuple[str, ...]) -> dict:
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
    raise ValueError(f"No parseable verdict JSON in: {text[:600]}")


# --------------------------------------------------------------------------
# Issue selection
# --------------------------------------------------------------------------

def select_open(state: dict, min_conf: str) -> list[dict]:
    """Return open issues at or above the confidence threshold, sorted by
    (confidence desc, times_touched desc, first_seen_date asc)."""
    threshold = CONFIDENCE_RANK[min_conf]
    out = [
        issue for issue in state["issues"].values()
        if issue["status"] == "open"
        and CONFIDENCE_RANK.get(issue.get("confidence", "med"), 2) >= threshold
    ]
    out.sort(key=lambda i: (
        -CONFIDENCE_RANK.get(i.get("confidence", "med"), 2),
        -(i.get("times_touched") or 0),
        i.get("first_seen_date", ""),
    ))
    return out


# --------------------------------------------------------------------------
# Prompt rendering
# --------------------------------------------------------------------------

def read_file_bounded(path: Path) -> str:
    if not path.exists():
        return f"[FILE NOT FOUND AT HEAD: {path}]"
    text = path.read_text()
    if len(text) > MAX_FILE_BYTES:
        return text[:MAX_FILE_BYTES] + f"\n[TRUNCATED {len(text)} bytes total]"
    return text


def render_history(issue: dict) -> str:
    lines = []
    for h in issue.get("history", []):
        sha = h.get("commit", "")[:8]
        action = h.get("action", "")
        note = h.get("note", "")
        lines.append(f"- {sha} {action}: {note}" if note else f"- {sha} {action}")
    return "\n".join(lines)


def render_issue_block(issue: dict) -> str:
    return json.dumps({
        "id": issue["id"],
        "description": issue["description"],
        "files": issue["files"],
        "first_seen_commit": issue["first_seen_commit"],
        "first_seen_date": issue["first_seen_date"],
        "confidence": issue["confidence"],
        "pattern_match": issue.get("pattern_match"),
        "bug_witness": issue["bug_witness"],
        "times_touched": issue.get("times_touched", 0),
    }, indent=2)


def grep_keywords(issue: dict) -> list[str]:
    """Pull distinctive symbols from the issue's description and bug_witness,
    grep the scope for each, and rank files by how many distinct symbols they
    match. Returns absolute paths, most-relevant first.

    Used to surface code that may have been moved or renamed since the issue
    was first seen.
    """
    text = issue.get("description", "") + " " + issue.get("bug_witness", "")
    symbols: set[str] = set()
    for m in re.finditer(r"[A-Za-z_][A-Za-z0-9_]{4,}", text):
        sym = m.group(0)
        if sym in COMMON_JAVA_SYMBOLS:
            continue
        # Distinctive: starts uppercase (class/Type), contains underscore
        # (CONSTANT), or has a non-leading uppercase (camelCase method).
        if sym[0].isupper() or "_" in sym or any(c.isupper() for c in sym[1:]):
            symbols.add(sym)
    if not symbols:
        return []

    scope = REPO_ROOT / SCOPE
    if not scope.exists():
        return []

    file_scores: dict[str, int] = {}
    for sym in list(symbols)[:MAX_GREP_SYMBOLS]:
        try:
            result = subprocess.run(
                ["grep", "-l", "-w", sym, "-r", str(scope)],
                capture_output=True, text=True, timeout=15,
            )
        except subprocess.SubprocessError:
            continue
        for line in set(result.stdout.strip().split("\n")):
            if line.endswith(".java"):
                file_scores[line] = file_scores.get(line, 0) + 1

    return [path for path, _ in sorted(
        file_scores.items(), key=lambda kv: (-kv[1], kv[0])
    )]


def render_code_block(issue: dict) -> str:
    """Build the CURRENT CODE block: the issue's tracked files plus up to
    MAX_EXTRA_FILES additional files that match the issue's keywords (in case
    the affected code has been moved/renamed)."""
    chunks: list[str] = []
    listed: set[str] = set()
    for f in issue["files"]:
        rel = f if "/" in f else SCOPE + f
        path = REPO_ROOT / rel
        listed.add(str(path))
        chunks.append(f"=== {rel} (issue's tracked file) ===\n"
                      f"{read_file_bounded(path)}")

    extras = [p for p in grep_keywords(issue) if p not in listed][:MAX_EXTRA_FILES]
    for p in extras:
        rel = p.replace(str(REPO_ROOT) + "/", "", 1)
        chunks.append(
            f"=== {rel} (additional file matching issue keywords; included "
            f"because the original file may have been refactored — verify "
            f"whether the bug actually lives here now) ===\n"
            f"{read_file_bounded(Path(p))}"
        )

    if not chunks:
        chunks.append("[NO CODE LOCATED FOR THIS ISSUE — assume "
                      "implicitly_resolved unless proven otherwise]")
    return "\n\n".join(chunks)


def render_prompt(template: string.Template, issue: dict,
                  design_excerpt: str, fps_excerpt: str) -> str:
    return template.substitute(
        issue_block=render_issue_block(issue),
        history_block=render_history(issue),
        code_block=render_code_block(issue),
        design_decisions=design_excerpt,
        known_fps=fps_excerpt,
    )


# --------------------------------------------------------------------------
# Claude CLI
# --------------------------------------------------------------------------

def call_claude(prompt: str, model: str | None, effort: str | None,
                log_dir: Path, iid: str) -> dict:
    log_dir.mkdir(parents=True, exist_ok=True)
    cmd = ["claude", "-p"]
    if model is not None:
        cmd += ["--model", model]
    if effort is not None:
        cmd += ["--effort", effort]
    cmd += [
        "--no-session-persistence",
        "--tools", "",
        "--disable-slash-commands",
        "--output-format", "json",
    ]
    proc = subprocess.run(
        cmd, input=prompt, capture_output=True, text=True, timeout=1800,
    )
    (log_dir / f"{iid}.raw.json").write_text(proc.stdout)
    if proc.returncode != 0:
        raise RuntimeError(f"claude exit={proc.returncode} stderr={proc.stderr[:500]}")
    envelope = json.loads(proc.stdout)
    if envelope.get("is_error"):
        raise RuntimeError(f"claude error: {envelope.get('result', '')[:500]}")
    return extract_balanced_json(
        envelope.get("result", ""), required_keys=("verdict",),
    )


# --------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------

def save_verified(verified: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(verified, indent=2))
    tmp.replace(path)


def write_findings_markdown(verified: dict, path: Path, state: dict) -> None:
    by_file: dict[str, list] = {}
    for iid, rec in verified.items():
        if rec.get("verdict") != "still_exists":
            continue
        issue = state["issues"][iid]
        files_key = ", ".join(issue["files"]) or "(unknown)"
        by_file.setdefault(files_key, []).append((iid, issue, rec))

    lines = ["# Temporal walker findings (HEAD-verified)\n"]
    total = sum(len(v) for v in by_file.values())
    lines.append(
        f"**{total}** verified surviving findings across "
        f"**{len(by_file)}** file group(s). "
        f"Generated from {state['stats']['commits_processed']} walked commits, "
        f"{state['stats']['issues_open']} open at walk completion.\n\n---\n"
    )

    for files_key in sorted(by_file):
        lines.append(f"## {files_key}\n")
        for iid, issue, rec in by_file[files_key]:
            lines.append(f"### [{iid}] {issue['description'][:120]}\n")
            lines.append(f"- **Confidence:** {issue['confidence']}")
            lines.append(f"- **Pattern:** {issue.get('pattern_match') or 'novel'}")
            lines.append(
                f"- **First seen:** `{issue['first_seen_commit'][:8]}` "
                f"({issue['first_seen_date'][:10]})"
            )
            lines.append(
                f"- **Times touched without resolution:** "
                f"{issue.get('times_touched', 0)}\n"
            )
            lines.append(rec.get("finding_markdown", "").strip())
            lines.append("\n#### Lineage")
            lines.append(render_history(issue))
            lines.append("\n---\n")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines))


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def truncate_to(text: str, limit: int, label: str) -> str:
    if len(text) > limit:
        return text[:limit] + f"\n[{label} truncated to {limit} bytes]"
    return text


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify temporal-walker findings against current HEAD code.")
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE)
    parser.add_argument("--verified", type=Path, default=DEFAULT_VERIFIED)
    parser.add_argument("--findings", type=Path, default=DEFAULT_FINDINGS_MD)
    parser.add_argument("--prompt", type=Path, default=DEFAULT_PROMPT)
    parser.add_argument("--log-dir", type=Path, default=DEFAULT_LOG_DIR)
    parser.add_argument(
        "--model", default=None,
        help="Claude model alias or full name. If omitted, uses the claude "
             "CLI's default (the session model).")
    parser.add_argument(
        "--effort", default=None,
        choices=["low", "medium", "high", "xhigh", "max"],
        help="Reasoning effort level for the verifier model.")
    parser.add_argument("--min-confidence", choices=["high", "med", "low"],
                        default="med")
    parser.add_argument("--max-issues", type=int, default=None)
    parser.add_argument(
        "--design-decisions", type=Path, default=DEFAULT_DESIGN_DECISIONS,
        help="Path to design-decisions.md (intentional-pattern filter).")
    parser.add_argument(
        "--known-fps", type=Path, default=default_known_fps_path(),
        help="Path to cross_model_audit_results.md (known false-positive "
             "filter). Auto-discovered from Claude Code's per-project memory "
             "directory if available.")
    args = parser.parse_args()

    if not args.state.exists():
        sys.exit(f"No state file at {args.state}. Run walker.py first.")

    state = json.loads(args.state.read_text())
    issues = select_open(state, args.min_confidence)
    print(f"Open issues at confidence >= {args.min_confidence}: {len(issues)}")

    verified: dict = {}
    if args.verified.exists():
        verified = json.loads(args.verified.read_text())
        print(f"Resuming: {len(verified)} already verified")

    template = string.Template(args.prompt.read_text())
    design_excerpt = truncate_to(
        args.design_decisions.read_text() if args.design_decisions and args.design_decisions.exists() else "",
        MAX_DESIGN_BYTES, "design-decisions",
    )
    fps_excerpt = truncate_to(
        args.known_fps.read_text() if args.known_fps and args.known_fps.exists() else "",
        MAX_FPS_BYTES, "known-fps",
    )

    processed = 0
    for issue in issues:
        if args.max_issues and processed >= args.max_issues:
            break
        iid = issue["id"]
        if iid in verified:
            continue
        try:
            prompt = render_prompt(template, issue, design_excerpt, fps_excerpt)
            t0 = time.time()
            result = call_claude(prompt, args.model, args.effort,
                                 args.log_dir, iid)
            elapsed = time.time() - t0
            verified[iid] = result
            save_verified(verified, args.verified)
            verdict = result.get("verdict", "?")
            desc = issue["description"][:60]
            print(f"  [{iid}] {verdict:<22} ({elapsed:.0f}s) {desc}")
            processed += 1
        except Exception as e:
            print(f"  [{iid}] ERROR: {e}", file=sys.stderr)
            verified[iid] = {
                "verdict": "error", "explanation": str(e), "finding_markdown": "",
            }
            save_verified(verified, args.verified)

    write_findings_markdown(verified, args.findings, state)
    surviving = sum(1 for v in verified.values()
                    if v.get("verdict") == "still_exists")
    print(f"\nVerification done. {surviving} surviving findings.")
    print(f"Markdown report: {args.findings}")


if __name__ == "__main__":
    main()
