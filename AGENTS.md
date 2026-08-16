# Caffeine Agent Entry Point

The canonical project instructions are in `.claude/CLAUDE.md`. Read that file completely before
analyzing or modifying this repository. This file is only a tool-neutral bootstrap.

Tools that do not natively load Claude Code extensions must emulate their behavior:

- Once the task scope or touched paths are known, inspect `.claude/rules/*.md` and their `paths`
  frontmatter, then read every matching rule before analysis or edits. A rule without `paths`
  applies repository-wide when its topic is relevant. If applicability is uncertain, read it.
- Treat `.claude/docs/*.md` as on-demand references. Use the routing guidance in
  `.claude/CLAUDE.md` to read relevant documents before reporting defects or changing behavior.
- Keep canonical shared guidance and reusable content under `.claude/`; do not copy or mirror it
  into tool-specific directories.
