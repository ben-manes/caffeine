---
name: optimize-cache
description: Investigate Caffeine shared-structure costs and read/write hot paths with controlled JMH experiments, correctness review, and a reviewable patch.
argument-hint: "[scope; JDK; hypothesis limit; time budget; priorities]"
context: fork
disable-model-invocation: true
allowed-tools: Read, Grep, Glob, Bash, Write, Edit, Agent, WebSearch, WebFetch, AskUserQuestion
---

# Cache performance experiments

Find demonstrated unnecessary work or shared contention worth removing. Use microbenchmarks as
stress and diagnostic tests, not estimates of application performance. A higher score on one hot
key is not, by itself, a reason to change the cache.

## Inputs and defaults

$ARGUMENTS

Read `.claude/CLAUDE.md`, matching rules, and the relevant design/synchronization references.
The default budget is two hours including setup and validation, which holds about two screened
hypotheses and one confirmation; confirmation alone costs at least 50 minutes at the measurement
reference's settings. More hypotheses need more budget. Honor supplied scope and budgets without a requirements questionnaire.
Return patches from an isolated workspace; apply, commit, or publish only when already authorized.

Prioritize costs shared across unrelated keys: read/write buffers, frequency sketch, eviction
lock, drain status, and timer wheel. Also consider concrete per-operation waste, such as an
unnecessary field read or allocation. A win confined to contention on a single node or hash bin
needs evidence of broader relevance before it merits an implementation experiment. Policy retuning
and weaker semantics are outside the default scope.

For stress-result triage, regard 2–3% differences as small, 4% regressions as concerning, and
6% or larger effects as worth substantial investigation. These bands guide effort, not statistical
certainty or automatic acceptance. Use 3% as the default tolerated loss in required stress cells;
record user overrides and the primary practical gain threshold before candidate measurements.
A small, concrete cleanup can remain useful even when its throughput benefit is unresolved.

## 1. Prepare the comparison

Inspect current source and read the **Performance** section of
[ruled-out.md](../../docs/ruled-out.md#performance). Reuse a current
[/audit-performance](../audit-performance/SKILL.md) report as the hypothesis queue, rechecking its
locations and mechanisms at the starting version. If none exists, use bounded source/profiling
questions; run that explicit-only audit when the user requests it rather than silently starting
an exhaustive audit inside this budget.

Create `.local/experiments/<run>/` for source snapshots, commands, raw results, and `LEDGER.md`.
Prepare separate baseline and candidate worktrees from the same snapshot, including relevant
staged/unstaged changes and required untracked source/build inputs. A clean HEAD is not the dirty
caller tree. Preserve the caller's checkout. Record source, build, generator, harness, and artifact
hashes so the starting state is reconstructible without committing it.

Name the immutable starting version and the last independently confirmed version. They initially
match. Screen candidates against the latter; use the former only for cumulative reporting.
Freeze source and build inputs during each build/measurement interval, including edits by other
agents. Verify hashes afterward and invalidate a run if inputs changed while it was running.

Read [measurement.md](references/measurement.md) and choose the exact cells, runtime, fork order,
practical thresholds, and confirmation sample count. Pin both `javaVersion` and `javaTestVersion`
for all commands. Check for other active JVM work with the selected JDK's `jps -l` and `lsof`.
On macOS, confirm sleep prevention with `pmset -g assertions` before a long batch; `caffeinate`
cannot create one from the agent shell here, and exits 0 after printing the failure to stderr.
Keep timed runs separate from builds, tests, profiles, and other benchmarks on the host.

## 2. Run and profile the baseline

Use the bundled init script to set real task properties at configuration time: one fork, at least
five warmup and five measurement iterations, and unique JSON/report/profile destinations. It leaves
the convention plugin unchanged. Read its controls in the measurement reference.

The following commands run **inside a prepared isolated arm**. Choose `perf_run` as a fresh absolute
output directory outside disposable worktrees; `perf_init` is an absolute path to the same frozen
copy of this skill's `scripts/jmh-session.init.gradle` for both arms. These macOS examples use JDK 27:

```bash
./gradlew :caffeine:jmh --rerun --no-configuration-cache -I "$perf_init" -PjavaVersion=27 -PjavaTestVersion=27 -PoptimizeRunDir="$perf_run/timing-01" '-PincludePattern=^com.github.benmanes.caffeine.cache.GetPutBenchmark.(read_only|readwrite|write_only)$' '-PbenchmarkParameters=cacheType=Caffeine'
./gradlew :caffeine:jmh --rerun --no-configuration-cache -I "$perf_init" -PjavaVersion=27 -PjavaTestVersion=27 -PoptimizeRunDir="$perf_run/cpu-01" '-PincludePattern=^com.github.benmanes.caffeine.cache.GetPutBenchmark.write_only$' '-PbenchmarkParameters=cacheType=Caffeine' -Pasync=tree -PasyncEvent=cpu
```

Prefer call trees for inspection; use JFR when event detail is needed. Keep profiled timing out of
acceptance comparisons. Verify fresh execution and actual JVM/settings/cells in the JSON. Trace the
measured path: resident unchanged-weight puts may feed the read buffer, not the write queue.
Grouped reader/writer counts specify threads, not the completed operation ratio. Track read-only,
write-only, mixed reads, and mixed writes separately. Add insertion, churn, or other configurations
only where they test the proposed shared mechanism or a plausible regression.

## 3. Form one candidate

For each hypothesis record the source site, observed cost, operation removed, JIT evidence,
contract boundaries, and a falsifying observation. Keep this queue short; an empty queue is valid.
Source-only relocation of constant-foldable conditions is not a mechanism. A compiler-directed
idea needs evidence from the hot native code and its compilation/inlining context; `javap` alone
cannot establish removed runtime work.

Branch from the confirmed snapshot and make one conceptual change. Preserve publication and
lifecycle ordering, callbacks, statistics, and access recording. Use generator inputs for generated
classes. Trace all relevant writers/readers/subclasses before changing memory ordering. Diagnostic
removals that weaken behavior may price a cost, but are not candidate optimizations.

Build immutable artifacts. Select focused methods using the **Test Discovery Guide** in
[testing.md](../../rules/testing.md#test-discovery-guide). Keep `gradlew :caffeine:test` and its
`--tests` selectors on one physical line: the current test-scope hook can miss continued selectors.
For example, when the change concerns put/refresh invalidation:

```bash
./gradlew :caffeine:test -PjavaVersion=27 -PjavaTestVersion=27 --tests 'LoadingCacheTest.refresh_discard_put' --rerun
```

Inspect fresh XML discovery and loaded artifacts. Zero tests, failed discovery containers, or
reported skips are not a pass. Add a public contract witness when required; passing ordinary tests
alone cannot prove a concurrent change safe.

## 4. Measure, review, and decide

Screen the affected cells first and label apparent wins provisional. Nominate one final patch for
fresh confirmation by default. Five A/B fork pairs mean **ten separate invocations with fork=1**;
use the balanced loop in the measurement reference. Confirm against the current confirmed version,
with identical inputs and no editing during runs. Combining edits creates a new candidate whose
whole diff needs measurement; percentages do not add.

Use [/review-change](../review-change/SKILL.md) for the independent final diff review when explicitly
requested. Otherwise give independent reviewers its applicable contract checks and the candidate-only
diff, excluding the starting user edits. Consume the review's findings rather than duplicating a
completed review. Read an invoked sibling's instructions in full and preserve its isolation; batch
reviewers if concurrency slots are limited. The coordinator checks the actual patch, raw results,
and discovered tests rather than trusting a delegate's keep/drop flag.

Changes to `WindowClimber` or window resizing require
[/climber-gate](../climber-gate/SKILL.md), plus other tests required by the matching project rules.
Throughput does not establish eviction quality. If the candidate affects policy work, validate the
associated quality contract rather than winning by recording or maintaining less.

Promote only when mechanism, correctness, and confirmed performance support it and every required
cell meets its declared regression limit. Keep a small mechanism-confirmed cleanup separate from a
claim of measurable speedup. Restore rejected candidates only inside the disposable workspace,
checking the restored diff/hash. `git checkout` alone does not undo an already committed candidate.

## 5. Preserve the result

Stop at the budget, a fully confirmed goal, or an exhausted hypothesis queue. Reserve confirmation
and review time before starting more screens; a full three-group confirmation at the supplied
5+5/10-second settings already costs at least 50 minutes. Start a batch only when its planned duration
fits the remaining budget. An unfinished candidate stays unconfirmed.

Return the best confirmed candidate-only diff, separate results and uncertainty for every cell,
mechanism evidence, validation limits, and the original checkout's status. Preserve commands,
artifacts, rejected patches, and unresolved questions in `LEDGER.md`.

Also distill adjudicated negative mechanisms into `ruled-out.md` **Performance**, as a reviewable
documentation diff. Record the mechanism, affected configurations, reason/evidence, and what would
justify reopening it. Make the entry understandable without local artifacts; never cite a `.local/`
path as its evidence. Preserve scope and counterarguments. A noisy or incomplete experiment is
inconclusive, not a durable ruling that the mechanism is dead. This closes the loop for later runs
without turning machine-specific measurements into universal claims.
