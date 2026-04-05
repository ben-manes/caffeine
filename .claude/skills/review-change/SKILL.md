---
name: review-change
description: Multi-layer adversarial code review of a diff or branch using parallel specialized reviewers with triage
argument-hint: "[branch or commit range, default: uncommitted changes]"
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Write, Agent, WebSearch, WebFetch, AskUserQuestion
---

Review code changes using three parallel specialized reviewers, then triage
and deduplicate findings.

## Input

$ARGUMENTS

If no argument given, review uncommitted changes (`git diff`).
If a branch name, review `git diff main...$ARGUMENTS`.
If a commit range, use it directly.

## Step 1: Gather the diff

```bash
# Uncommitted changes
git diff

# Or branch comparison
git diff main...<branch>
```

Capture the full diff output as `DIFF`.

If the diff is empty, report "No changes to review" and stop.

## Step 2: Launch three parallel review subagents

Launch all three simultaneously. Each runs independently with different context.

### Layer 1: Blind Concurrency Reviewer

Spawn a subagent with ONLY the diff — no project files, no design docs, no context.

Prompt:
```
You are a Java concurrency expert reviewing a diff. You have NO context about
this project's design decisions or conventions. Analyze purely from first
principles:

1. For every shared mutable field touched: is the access mode sufficient?
2. For every lock acquisition: is the ordering safe? Can it deadlock?
3. For every state transition: is it atomic? Can it be observed partially?
4. For every callback/listener invocation: what locks are held?
5. For every exception path: is cleanup complete?

Output findings as a JSON array:
[{"location": "file:line", "issue": "description", "severity": "critical|high|medium|low", "evidence": "the specific code pattern"}]

Return [] if no issues found.

THE DIFF:
<diff content>
```

### Layer 2: Design-Aware Reviewer

Spawn a subagent WITH project read access. It reads the design docs first.

Prompt:
```
You are reviewing a code change to the Caffeine cache library. Before reviewing,
read these files to understand intentional design decisions:
- .claude/docs/design-decisions.md
- .claude/docs/synchronization.md
- .claude/rules/design-decisions.md

Now review this diff. For each change:
1. Is it consistent with the documented invariants?
2. Does it maintain the lock ordering (evictionLock → CHM bin → synchronized(node))?
3. Does it preserve weight accounting convergence?
4. Does it preserve node lifecycle monotonicity (alive → retired → dead)?
5. Are VarHandle access modes correct for the fields touched?
6. If it touches callback invocation points, are they at the documented positions?

ONLY report issues that are NOT explained by design-decisions.md. If a pattern
looks wrong but is documented as intentional, skip it.

Output findings as a JSON array:
[{"location": "file:line", "issue": "description", "severity": "critical|high|medium|low", "design_ref": "which invariant is violated"}]

Return [] if no issues found.

THE DIFF:
<diff content>
```

### Layer 3: Regression Pattern Matcher

Spawn a subagent WITH project read access. It reads historical bug patterns.

Prompt:
```
You are checking whether a code change resembles historical bug patterns in
the Caffeine cache. Read these first:
- .claude/docs/design-decisions.md (the "References" section about keyReference visibility)

Then check the diff against these known bug patterns:

1. REFRESH + EXPIRATION RACE: Does the change touch refresh or expiration paths?
   Could it allow a dead key to be passed to a loader, or a refresh to prevent
   expiration indefinitely?

2. VALUE REFERENCE VISIBILITY: Does the change modify how weak/soft values are
   published? Is setRelease + storeStoreFence used (not just setRelease)?

3. ASYNC COMPLETION RACE: Does the change touch async value handling? Could a
   future complete between a check and an action?

4. WRITE BUFFER TASK LOSS: Does the change modify afterWrite or task creation?
   Could a task be lost without the inline fallback firing?

5. NOTIFICATION ORDERING: Does the change move notifyEviction or notifyRemoval
   relative to user code? notifyEviction MUST be before user code.

6. WEIGHT ACCOUNTING: Does the change modify weight tracking? Does it preserve
   the telescoping sum property?

7. BUILD CACHE RELOCATABILITY: Does the change add inputs.files() without
   .withPathSensitivity(PathSensitivity.RELATIVE)?

For each match, explain which historical bug it resembles and why.

Output findings as a JSON array:
[{"location": "file:line", "issue": "description", "severity": "critical|high|medium|low", "historical_bug": "issue number or description of the original bug"}]

Return [] if no patterns match.

THE DIFF:
<diff content>
```

## Step 3: Large diff handling

If the diff exceeds ~3000 lines, warn and offer to chunk by file group.
If chunked, run steps 2-5 per chunk and consolidate at the end.

## Step 4: Triage

Collect findings from all three layers. Then:

1. **Deduplicate**: If two layers report the same issue, merge them. Keep the
   most specific location and combine evidence. Note which layers agreed
   (agreement = higher confidence).

2. **Filter design-explained findings**: If Layer 1 (blind) reports something
   that Layer 2 (design-aware) explicitly identifies as intentional, drop it
   and note it was filtered.

3. **Classify** each surviving finding:
   - **patch** — real issue, fixable in this change
   - **defer** — real issue but pre-existing, not introduced by this change
   - **reject** — noise, false positive, or handled elsewhere

4. **Track rejections**: Count rejected findings and note any where agents
   disagreed (blind flagged, design-aware cleared). These are worth mentioning
   to show review thoroughness.

5. **Rank** patches by: critical > high > medium > low, then by layer agreement count.

## Step 5: Report

Present findings grouped by classification:

```
══════════════════════════════════════════════════════════
REVIEW: [N] findings ([M] patch, [K] defer, [J] rejected)
══════════════════════════════════════════════════════════

## Patch (actionable in this change)
#1 [critical] [blind+design] file:line — description
   Source: blind + design (2 layers agree)
   Evidence: ...
   Historical: ... (if regression match)

## Defer (pre-existing, not from this change)
...

## Rejected ([J] findings classified as noise)
Notable rejections where agents disagreed:
- [description] (blind: flagged; design-aware: verified correct) — [reason]
- ...

## Filtered (explained by design docs)
- [N] findings from blind review explained by design-decisions.md
══════════════════════════════════════════════════════════
```

If zero findings survive triage, state how many were raised and rejected,
rather than just saying "clean review." Show the work.
