#!/usr/bin/env python3
"""
Orchestrator for the temporal-walk battery.

Runs a selected set of walk variants SEQUENTIALLY (walk + verify each), then
aggregates every findings-<name>.md into one findings-ALL.md. This is the
"don't-forget" entry point: one resumable command runs the whole battery and
reports on everything, so the variants can't be left un-run.

Each variant is isolated by walker.py's --run-name (disjoint
state-/log-/worktree-<name>), so a re-run resumes cleanly and nothing clobbers
the others. Variants are independent; if one errors mid-walk the orchestrator
records it, skips its verify, and continues — re-run to resume the errored one.

  # Whole battery (sequential), then combined report:
  python3 run.py --all

  # A chosen subset:
  python3 run.py --variants fix-audit,lens-sibling,invariants

  # Quality config (forwarded to walker.py and verify.py):
  python3 run.py --all --effort max

  # QA slice — first N commits of each selected variant:
  python3 run.py --variants fix-audit --max-commits 30

  # Just regenerate findings-ALL.md from existing per-variant findings:
  python3 run.py --all --report-only
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[2]
MAIN_SCOPE = "caffeine/src/main/java/com/github/benmanes/caffeine/cache/"
TEST_SCOPE = "caffeine/src/test/java/com/github/benmanes/caffeine/cache/"
FIX_GREP = "fix|bug|regression|NPE|race|leak|incorrect|wrong|revert"


def module_of(scope: str) -> str:
    base = scope.split("/", 1)[0] if "/" in scope else "default"
    return f"{base}-test" if "/src/test/" in scope else base


# name -> (prompt, scope, grep, description). Order is the run order.
VARIANTS: dict[str, tuple[str, str, str | None, str]] = {
    "main":          ("per-commit.txt",       MAIN_SCOPE, None,     "Broad bug hunt (the default walk)"),
    "fix-audit":     ("fix-audit.txt",        MAIN_SCOPE, FIX_GREP, "Fix-commit completeness (incomplete / unfixed sibling / recurrence)"),
    "lens-sibling":  ("lens-sibling.txt",     MAIN_SCOPE, None,     "Sibling parity (change in A not mirrored to B)"),
    "lens-deletion": ("lens-deletion.txt",    MAIN_SCOPE, None,     "Chesterton's Fence (removed guard/branch/precondition)"),
    "lens-intent":   ("lens-intent.txt",      MAIN_SCOPE, None,     "Intent vs effect (message vs actual diff)"),
    "invariants":    ("invariant-ledger.txt", MAIN_SCOPE, None,     "Invariant ledger (distant-commit violations)"),
    "coverage":      ("test-walk.txt",        TEST_SCOPE, None,     "Test coverage regressions (weakened assertions / dropped cases)"),
}
COMBINED_NAME = "findings-ALL.md"


def reports_dir(scope: str) -> Path:
    return REPO_ROOT / ".claude" / "reports" / f"audit-temporal-walk-{module_of(scope)}"


def findings_path(name: str, scope: str) -> Path:
    return reports_dir(scope) / f"findings-{name}.md"


def env_for(scope: str) -> dict:
    env = dict(os.environ)
    env["WALKER_SCOPE"] = scope
    return env


def run_step(cmd: list[str], env: dict) -> int:
    print(f"\n$ {' '.join(cmd)}", flush=True)
    return subprocess.run(cmd, env=env, cwd=str(REPO_ROOT)).returncode


def run_variant(name: str, args) -> str:
    """Walk then verify one variant. Returns a short status string."""
    prompt, scope, grep, _ = VARIANTS[name]
    env = env_for(scope)
    prompt_path = str(SCRIPT_DIR / prompt)

    walk = [sys.executable, str(SCRIPT_DIR / "walker.py"),
            "--prompt", prompt_path, "--run-name", name]
    if grep:
        walk += ["--grep", grep]
    if args.model:
        walk += ["--model", args.model]
    if args.effort:
        walk += ["--effort", args.effort]
    if args.max_commits:
        walk += ["--max-commits", str(args.max_commits)]

    rc = run_step(walk, env)
    if rc != 0:
        return f"WALK EXITED rc={rc} (state preserved; re-run to resume) — verify skipped"

    verify = [sys.executable, str(SCRIPT_DIR / "verify.py"), "--run-name", name]
    if args.model:
        verify += ["--model", args.model]
    if args.effort:
        verify += ["--effort", args.effort]
    rc = run_step(verify, env)
    if rc != 0:
        return f"verify exited rc={rc}"
    return "ok"


def count_findings(path: Path) -> int:
    """Surviving findings in a verify.py report = '### [iid] ...' headers."""
    if not path.exists():
        return 0
    return sum(1 for ln in path.read_text().splitlines() if ln.startswith("### ["))


def count_unverified_errors(name: str, scope: str) -> int:
    """Issues left with verdict=error in verified-<name>.json — an interrupted
    verify. A nonzero count means this variant's surviving total is provisional
    (some issues were never grounded against HEAD)."""
    vp = reports_dir(scope) / f"verified-{name}.json"
    if not vp.exists():
        return 0
    try:
        verified = json.loads(vp.read_text())
    except (ValueError, OSError):
        return 0
    return sum(1 for r in verified.values()
               if isinstance(r, dict) and r.get("verdict") == "error")


def aggregate(selected: list[str]) -> None:
    """Concatenate each variant's findings-<name>.md into one report under the
    main-scope reports dir, with a summary table on top."""
    out_dir = reports_dir(MAIN_SCOPE)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / COMBINED_NAME

    rows, bodies = [], []
    for name in selected:
        _, scope, _, desc = VARIANTS[name]
        fp = findings_path(name, scope)
        n = count_findings(fp)
        err = count_unverified_errors(name, scope)
        rows.append((name, desc, n, fp.exists(), err))
        header = f"# {name} — {desc}\n"
        if fp.exists():
            bodies.append(header + "\n" + fp.read_text())
        else:
            bodies.append(header + "\n_(not run yet — no findings-"
                          f"{name}.md)_\n")

    lines = ["# Temporal-walk battery — combined findings\n",
             "| variant | surviving | description |",
             "|---|---:|---|"]
    for name, desc, n, exists, err in rows:
        cnt = str(n) if exists else "—"
        if err:
            cnt += f" ⚠️+{err}?"
        lines.append(f"| `{name}` | {cnt} | {desc} |")
    total = sum(n for _, _, n, ex, _ in rows if ex)
    nrun = sum(1 for _, _, _, ex, _ in rows if ex)
    total_err = sum(e for *_, e in rows)
    lines.append(f"\n**{total}** surviving findings across "
                 f"{nrun} run variant(s).")
    if total_err:
        lines.append(
            f"\n> ⚠️ **{total_err} issue(s) still unverified** (verdict=error, "
            f"from interrupted verifies). Counts marked `⚠️+N?` are PROVISIONAL "
            f"— re-run the battery (or verify.py for that variant) to finalize.")
    lines.append("\n---\n")
    lines.append("\n\n---\n\n".join(bodies))
    out.write_text("\n".join(lines))

    print("\n" + "=" * 60)
    print("BATTERY SUMMARY")
    for name, desc, n, exists, err in rows:
        tag = "" if not err else f"   ⚠️ {err} unverified"
        print(f"  {name:14} {('—' if not exists else n):>4}  {desc}{tag}")
    if total_err:
        print(f"\n⚠️  {total_err} issue(s) left unverified across the battery "
              f"— counts are provisional; re-run to finalize.")
    print(f"\nCombined report: {out}")


def main() -> None:
    sys.stdout.reconfigure(line_buffering=True)
    p = argparse.ArgumentParser(description="Run the temporal-walk variant battery.")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--all", action="store_true", help="Run every variant.")
    g.add_argument("--variants", help="Comma-separated subset, e.g. "
                   "fix-audit,lens-sibling,invariants.")
    p.add_argument("--model", default=None, help="Forwarded to walker.py / verify.py.")
    p.add_argument("--effort", default=None,
                   choices=["low", "medium", "high", "xhigh", "max"],
                   help="Forwarded to walker.py / verify.py.")
    p.add_argument("--max-commits", type=int, default=None,
                   help="Cap commits per variant this run (chunked / QA slices).")
    p.add_argument("--report-only", action="store_true",
                   help="Skip walks; just regenerate the combined report.")
    p.add_argument("--list", action="store_true", help="List variants and exit.")
    args = p.parse_args()

    if args.list:
        for name, (_, scope, grep, desc) in VARIANTS.items():
            tag = "test" if scope == TEST_SCOPE else ("grep" if grep else "main")
            print(f"  {name:14} [{tag:4}] {desc}")
        return

    if not args.all and not args.variants:
        p.error("one of --all / --variants / --list is required")

    selected = list(VARIANTS) if args.all else [
        s.strip() for s in args.variants.split(",") if s.strip()]
    unknown = [s for s in selected if s not in VARIANTS]
    if unknown:
        sys.exit(f"Unknown variant(s): {', '.join(unknown)}. "
                 f"Known: {', '.join(VARIANTS)}")

    if not args.report_only:
        results = {}
        for i, name in enumerate(selected, 1):
            print(f"\n{'#' * 60}\n# [{i}/{len(selected)}] variant: {name}\n{'#' * 60}")
            results[name] = run_variant(name, args)
        print("\nPer-variant status:")
        for name, status in results.items():
            print(f"  {name:14} {status}")

    aggregate(selected)


if __name__ == "__main__":
    main()
