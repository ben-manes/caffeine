"""Where the temporal walk writes its artifacts.

The project keeps audit output under `.local/audits/<model>/` so the tree can be retained
long-term and compared across models and providers — see `.claude/rules/audit-output.md`. This
module is the single place the three CLIs (walker.py, verify.py, run.py) derive that path, so
they cannot disagree about it.

`<model>` comes from AUDIT_MODEL: the invoking agent exports its own short model id (opus-5,
gpt-5-codex, …), because a shell-launched walk cannot know it.

Resume safety: a walk runs for hours and is explicitly resumable, so it must not lose its
state.json because a shell forgot to export AUDIT_MODEL. When the variable is absent and exactly
one existing tree for this module is found, that one wins over the placeholder path.
"""

import os
from pathlib import Path


def audits_root(repo_root: Path) -> Path:
    """Returns `.local/audits/<model>` for this run."""
    model = os.environ.get("AUDIT_MODEL") or "unspecified-model"
    return repo_root / ".local" / "audits" / model


def reports_dir(repo_root: Path, module: str) -> Path:
    """Returns the walk's own directory, preferring an in-progress one to stay resumable."""
    name = f"audit-temporal-walk-{module}"
    derived = audits_root(repo_root) / name
    if derived.exists() or os.environ.get("AUDIT_MODEL"):
        return derived
    existing = sorted((repo_root / ".local" / "audits").glob(f"*/{name}"))
    return existing[0] if len(existing) == 1 else derived
