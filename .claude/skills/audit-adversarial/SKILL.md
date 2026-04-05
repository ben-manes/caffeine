---
name: audit-adversarial
description: Hostile full-codebase review by parallel adversarial agents with no design context — finds bugs that domain familiarity masks
argument-hint: "[subsystem or file to focus on, default: all source files]"
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Agent
---

Run a hostile adversarial review of the Caffeine source code. Unlike the other
audit skills which use the auditor agent (with design context), this skill
deliberately gives reviewers NO project context — they review with fresh eyes
and a hostile mindset.

## Target

$ARGUMENTS

If no argument, review all source files in `caffeine/src/main/java/`.

## Step 1: Inventory source files

List all Java source files in scope. Group into 4-6 subsystems for parallel review.

## Step 2: Launch parallel hostile reviewers

Spawn 4-6 subagents simultaneously. Each reviews one subsystem. Critically:
**DO NOT give them design-decisions.md, synchronization.md, or any .claude/docs.**
They should review from first principles only.

Each agent gets this prompt (adapted to their subsystem):

```
You are a senior Java concurrency expert performing a hostile code review.
A competitor built this library and it's gaining adoption over your work.
You want to find every flaw to demonstrate it's not production-worthy.

Your reputation is on the line. Be ruthless but precise — cite methods,
trace code paths, construct failing scenarios.

Rules:
- Dig deep. Zero findings are allowed ONLY with a coverage proof listing
  files inspected, methods traced, interleavings attempted, and attack
  surfaces checked. If coverage is shallow, keep looking.
- Every finding must include: exact location, concrete evidence, a
  falsifiable scenario, and confidence (high/medium).
- Only report issues provable with code evidence
- Construct concrete interleavings, inputs, or scenarios
- Do not critique style — focus on correctness and robustness
- Do not accept "by design" — if the design has consequences, document them
- Read the actual source code before making claims
- Look for what's MISSING, not just what's wrong

Attack surfaces:
1. Memory model violations — insufficient access modes, missing happens-before
2. State corruption interleavings — weight divergence, deque corruption, stuck drain status
3. Resource leaks under failure — OOME/SOE leaving unrecoverable state
4. Silent data loss — values dropped without notification
5. Specification violations — ConcurrentMap contract, Javadoc promises
6. Denial of service — O(n) operations on O(1) paths
7. Sentinel value collisions — can valid input equal an internal sentinel?
8. Validation gaps — inputs accepted at parse time but rejected later
9. API surprises — public methods returning nonsensical values
10. Notification asymmetries — some paths notify, equivalent paths don't

Rate each finding: critical/high/medium/low
Format: numbered list with file:method, description, evidence
```

## Step 3: Validate completeness

If any agent returns zero findings, require a coverage summary from that
agent (scope inspected, attack surfaces checked, interleavings attempted).
Re-launch with a more specific prompt only if coverage is shallow. Zero
findings with thorough coverage proof is acceptable.

## Step 3.5: Evaluator challenge (per reviewer)

For each reviewer that returned findings OR a zero-findings coverage proof,
spawn a separate evaluator subagent. The evaluator gets ONLY the reviewer's
report — no source code, no design docs.

```
You are a hostile evaluator reviewing another auditor's report of a Java
cache library. Your job is to find what the auditor MISSED.

1. For each confirmed invariant, construct a 2-thread interleaving that
   would violate it. If you cannot, explain what prevents it.
2. For each zero-finding claim, identify the most likely bug category
   the auditor could have missed given their stated coverage.
3. For each finding, check whether the evidence is concrete or hand-wavy.
   Flag findings that assert a bug without a specific interleaving.

Output: prioritized list of challenges for the reviewer to address.
```

Have the original reviewer address each challenge by re-reading source code.
Drop findings the reviewer cannot defend. Add new findings from challenges
the reviewer confirms.

## Step 4: Consolidate and deduplicate

Collect findings from all agents. Deduplicate (same issue found by multiple
agents = higher confidence). Remove findings that are clearly wrong (misreading
the code). Keep findings even if they might be "by design" — the point is to
surface things domain familiarity masks.

**Confidence decay check**: If any reviewer's findings are >60% medium-confidence,
note this in the report — that reviewer's area may need a more targeted follow-up
audit rather than more speculative findings.

**Escalation**: If any reviewer flagged issues they could not resolve statically
(e.g., "depends on JDK internal behavior"), mark these as ESCALATED for dynamic
testing (Fray, LinCheck, JCStress) rather than guessing.

## Step 5: Adjudicate against design docs

NOW read `.claude/docs/design-decisions.md` and `.claude/rules/design-decisions.md`.
For each finding, check: is this an intentional trade-off? Reclassify as:
- **confirmed** — not explained by design docs
- **intentional** — documented design decision, not a defect
- **ambiguous** — needs more evidence or maintainer input

Keep intentional findings in the report (labeled as such) but do not count
them as bugs. The value is surfacing them for review, not asserting they're wrong.

## Step 6: Triage confirmed findings

Classify using `.claude/docs/finding-taxonomy.md` for severity and categories.
Additionally tag each confirmed finding:
- **bug** — incorrect behavior, provably wrong
- **api-issue** — public API returns surprising/incorrect values
- **validation-gap** — input accepted when it shouldn't be
- **robustness** — works but fragile, could break with minor changes
- **cosmetic** — dead code, wasteful patterns, poor diagnostics

## Step 7: Report

Write the full report to `.claude/reports/audit-adversarial.md`.

Format:
```
# Adversarial Review: Caffeine Source Code

[N] parallel auditors reviewed [M] source files (~K lines).
Findings consolidated, deduplicated, and triaged by severity.

## Likely Bugs
...

## API/Behavioral Issues
...

## Robustness/Validation Gaps
...

## Design/Maintenance Concerns
...

## Summary
[N] likely bugs, [M] API issues, [K] validation gaps, [J] concerns.
[N] evaluator challenges received across [M] reviewers.
```
