# Code Comments

Google Java Style, with `java.util.concurrent` as the model for how much to say and where.
`BoundedLocalCache` follows the same shape with more comments throughout, still succinct.

## Who gets a doc

- **Every class and method** — often one line is enough when it is simple.
- **Fields only when the intent is unclear elsewhere.** Not every field. A sentinel (`-1` means
  unplanted), a validity condition ("meaningful only while planted"), or a cryptic name earns one;
  a name the class doc already explains does not.
- **Inner comments** only for complexity that would not otherwise be understood.
- A class with real implementation subtlety puts it in a `/* ... */` block **inside** the class,
  as `ConcurrentHashMap` does, rather than spreading it over per-constant javadoc.

The reader is expected to do pre-work. Refer to papers and background in the impl notes; do not
re-derive first principles or narrate every detail.

## What a doc must do

- **Define the thing.** The first sentence says what it *is*, not when it is written or who calls
  it. "The hill climber's step size", not "the last command the climber issued".
- **Do not describe a type at its field declaration** — the reader opens the type.
- **No meta-commentary about the code's own organization**: "one definition because…", "so no two
  branches can disagree", "instead of threading eight". That justifies a refactor; it does not help
  a reader.
- **A field needing a doc about a cross-class contract is the wrong shape.** Make it an accessor
  and document that.
- Write sibling docs (all the class docs, all the constants in a class) against one template, or
  they drift into different voices.

## Style

Avoid `—` / `--` as a dramatic pause; use a comma, parens, or a full stop. Avoid CAPS for
emphasis. Both are usually a sign the clause is overblown — check whether it should exist at all
before re-punctuating it.

Excessive docs usually mean the code is too complex or poorly named, not that the docs need
trimming. Verbosity is not communication; noise hides the content that matters.

The same voice applies to `.claude/` prose and `wiki/adaptive-window.html`. Match the existing
document's voice. State what the mechanism does; avoid personification, aphorisms, and extended
metaphors.

## Maintaining agent guidance

Read the affected section before and after editing. State the current rule, scope, and rationale;
replace superseded text and merge repetition. Link to named doc sections for detail. Preserve
counterarguments, rejected alternatives, and evidence needed to prevent repeated mistakes. Routine
fixes need no new guidance entry.

Session chronology, raw runs, and audit-item progress belong in `.local/`. Checked-in guidance must
be self-contained: use descriptive topics and source/test references, not opaque audit row IDs or
local workspace contents.

## Where the evidence goes

Keep invariants and necessary warnings in source comments. Put supporting measurements and rejected
alternatives in `.claude/docs/` when future decisions need them; keep raw study records in `.local/`.
Verify that useful evidence is retained before removing it from a comment.
