# Running the measurements

## Task controls

The convention plugin currently fixes one fork, three warmup/measurement iterations, and a shared
result path. Use [jmh-session.init.gradle](../scripts/jmh-session.init.gradle) with `-I` to configure
the experiment without editing that plugin. Apply the same init script and settings to both arms.

| Property | Default / requirement |
|---|---|
| `optimizeRunDir` | Required absolute, unique output directory for this invocation |
| `optimizeWarmups` | 5, minimum 5 |
| `optimizeIterations` | 5, minimum 5 |
| `optimizeWarmupTime` | `10s` |
| `optimizeMeasurementTime` | `10s` |

Each invocation uses `fork=1`. The script redirects JSON, the HTML report, and profiler captures
under `optimizeRunDir`, and refuses to overwrite an existing `results.json`. The JMH extension's
`resultsFile` is wired during configuration; the report plugin also captures its path as a string.
Both are redirected then, not in `doFirst`. Profiler options are mapped lazily from the extension
provider so native-library extraction still runs before use. Captures land in `<optimizeRunDir>/async`
with `threads=true`, which profiles workers, maintenance, and pool threads separately. The script
leaves the event as `asyncEvent` set it.

Without the init script, the stock `--rerun` command is still usable for diagnostic runs, but it
uses the stock 3+3 settings and overwrites `caffeine/build/reports/jmh/results.json`. Copy that result
out immediately after every invocation. It is not the 5+5 confirmation protocol below. Editing the
convention plugin is another option only when the identical edit is frozen in both arms.

## Prepare the host and runtime

Select one JDK version and pass both `-PjavaVersion=27 -PjavaTestVersion=27` (or the selected version)
on benchmark and test commands. `JAVA_HOME` alone does not set these project toolchains; the
project's language-version default is 11. Verify actual JVM paths/versions in JMH JSON and test logs.
Use another JDK only as a separately identified confirmation, with compatible rebuilt artifacts.

Worktrees share Gradle caches. Version/release settings should participate in normal cache keys,
so a class-version failure is not proof of a cache-key bug. If one occurs, inspect the loaded
artifact and runtime before blaming the candidate. Once other runs are stopped, rebuild affected
outputs inside the isolated arm with the same explicit version settings and
`--no-build-cache --rerun-tasks`; `clean` alone can be followed by restoring cached outputs.
Preserve the failure and qualification; leave global caches and the caller's outputs alone.

Use the selected JDK's `jps -l` to find active JMH, simulator, test, or compiler JVMs. Use
`lsof -a -p <pid> -d cwd` or `lsof -p <pid>` to associate them with worktrees and artifacts.
These are useful when `ps`/`pgrep` are restricted; neither proves the entire host is idle. Identify
process owners and wait or coordinate instead of killing unrelated jobs. Check again before timed
batches. If visibility or machine activity prevents a useful comparison, report that limit.

Arrange sleep prevention before a long batch, and confirm it rather than assuming it. On this
host `caffeinate` cannot create a power assertion from the agent shell: it prints `Failed to create
PreventUserIdleSystemSleep assertion` to stderr, exits 0, and runs the wrapped command unprotected,
so prefixing a command with it proves nothing and leaves an error line in every captured stderr.
Read `pmset -g assertions` before starting; if it reports none, ask the user to hold one for the
duration. Idle sleep costs the whole comparison, not one cell. Include host activity, sleep, and
interrupted-run observations with the results.

Freeze source, generator inputs, build settings, and the harness for each build/measurement batch.
Hash them before starting and recheck afterward. Source edits during a run invalidate it even if
builds did not overlap. Agents may analyze frozen evidence while timing runs, not mutate those inputs
or run other CPU-intensive jobs on the same host.

## Execute A/B comparisons

Use an explicit class and anchored pattern: `.*GetPutBenchmark` also matches
`AsyncGetPutBenchmark`. Inspect the current harness for occupancy, capacity, key/value distribution,
value identity, features, and operation dispatch. Repeated resident updates do not measure insertion
or eviction churn. Include the shared-structure path the hypothesis claims to improve.

Create both isolated arm directories from the same preserved starting snapshot; apply only the
candidate diff to B. In this loop, `perf_a` and `perf_b` are their absolute paths, `perf_init` is the
absolute path of the frozen init script, and `perf_run` is a fresh absolute result directory outside
both arm trees. `perf_jdk` is the chosen version. Prepare these before invoking the loop; record
source/artifact hashes and the schedule in the experiment plan.

Five fork pairs are ten fresh `--rerun` invocations. This balanced schedule runs each of the three
GetPut groups once per invocation, retaining both mixed subgroup metrics:

```bash
for perf_pair in 1 2 3 4 5; do
  if [ "$((perf_pair % 2))" -eq 1 ]; then perf_order=(A B); else perf_order=(B A); fi
  for perf_arm in "${perf_order[@]}"; do
    if [ "$perf_arm" = A ]; then perf_tree="$perf_a"; else perf_tree="$perf_b"; fi
    (
      cd "$perf_tree" || exit 1
      ./gradlew :caffeine:jmh --rerun --no-configuration-cache -I "$perf_init" -PjavaVersion="$perf_jdk" -PjavaTestVersion="$perf_jdk" -PoptimizeRunDir="$perf_run/pair-$perf_pair-$perf_arm" '-PincludePattern=^com.github.benmanes.caffeine.cache.GetPutBenchmark.(read_only|readwrite|write_only)$' '-PbenchmarkParameters=cacheType=Caffeine'
    ) || exit 1
  done
done
```

At 5 warmups + 5 measurements, 10 seconds each, this costs at least 50 minutes before startup/build
costs. Adding CHM to every invocation doubles that; run it separately as a diagnostic control.
Screen one promising mechanism on the affected cell first, with one A/B pair, rather than spending
this full matrix on every hypothesis. Use at least 5+5 iterations for small-effect timing claims;
if the budget cannot fit them, produce source/profiling evidence or an inconclusive outcome.

Preserve JSON and stdout/stderr per invocation. Require a fresh executed task and the expected
benchmark names, parameters, group/thread counts, JVM settings, and raw measurements. Reject empty,
missing, duplicate, or unexpected cells even if the process exits successfully. `Cnt` counts
measurements, not independent JVM forks. Keep artifacts immutable and verify loaded class origins
if using a jar/classpath overlay. Prefer normal builds to ad-hoc test runtimes assembled from a
shaded benchmark jar.

## Decide what the numbers support

Use the skill's 2–3% / 4% / 6% triage bands to allocate attention, while keeping the actual comparison
statistically honest. Choose the practical gain threshold and required cells before screening;
the default tolerated loss is 3%. A 6% difference can still be noise or a hot-key stress artifact,
and a well-supported smaller removal of work may still be worth keeping as a cleanup.

- Establish repeated controls. Investigate drift or distinct performance modes instead of hiding
  them in an average. Fix or record input randomness when it obstructs comparison; use the same
  modified harness in both arms, then recheck ordinary-workload claims on the ordinary harness.
- Nominate one patch for fresh confirmation by default. Choose the sample count, aggregation,
  confidence level, and exclusions in advance. Default to five paired forks and a two-sided 95%
  interval on paired log throughput ratios, stating its assumptions.
- Aggregate iterations within a fork first; analyze paired fork ratios as the independent units.
  Require the primary gain to clear its practical threshold and each required cell to exclude
  a loss beyond its tolerance. Uncertain outcomes are inconclusive, not harmless by default.
- Compare against the current confirmed version. Initial-baseline deltas are cumulative reporting,
  not grounds for keeping a regression from the current best. Confirm a combined diff independently.
- Complete the planned sample, including losses. Further sampling requires a newly declared
  comparison, with earlier results retained. If confirming multiple nominees, predeclare the number
  of attempts and account for repeated testing; do not sample or retry until a candidate passes.
- Six readers and two writers do not mean a fixed 75/25 operation mix. Report both mixed reads and
  writes. A matched-rate or fixed-mix experiment is a separate control with its own workload effects.

Select mechanisms with broader relevance to shared structures or demonstrated per-operation waste.
A result confined to hot-entry/bin contention does not estimate application benefit. A stress-suite
mean cannot replace the losing cells or establish latency, memory use, eviction quality, or portability.
Use a new input/configuration to confirm useful scope, and another supported JDK for general JVM claims.

## Inspect profiles and compiled code

Prefer call trees. From a prepared arm, choose a unique capture directory and use the same init:

```bash
./gradlew :caffeine:jmh --rerun --no-configuration-cache -I "$perf_init" -PjavaVersion="$perf_jdk" -PjavaTestVersion="$perf_jdk" -PoptimizeRunDir="$perf_run/wall-01" '-PincludePattern=^com.github.benmanes.caffeine.cache.GetPutBenchmark.write_only$' '-PbenchmarkParameters=cacheType=Caffeine' -Pasync=tree -PasyncEvent=wall
./gradlew :caffeine:jmh --rerun --no-configuration-cache -I "$perf_init" -PjavaVersion="$perf_jdk" -PjavaTestVersion="$perf_jdk" -PoptimizeRunDir="$perf_run/lock-01" '-PincludePattern=^com.github.benmanes.caffeine.cache.GetPutBenchmark.write_only$' '-PbenchmarkParameters=cacheType=Caffeine' -Pasync=tree -PasyncEvent=lock
```

`-PasyncEvent=lock` reaches the profiler as `event=lock`, a primary capture that works with tree
output. This script preserves that explicit event and the profiler's default lock sampling interval.
In JMH 1.37, `lock=<duration>` without an explicit event is also valid: the sole capture is promoted
to primary, so `lock=1ms;output=tree` is lock-only, not CPU profiling. The CPU default is emitted only
when no event or allocation/lock capture was selected. Combining an explicit primary event with
the separate `lock` option requires `output=jfr`; specifying both `event=lock` and `lock=1ms` with
tree output is rejected. See the [JMH selection logic](https://github.com/openjdk/jmh/blob/1.37/jmh-core/src/main/java/org/openjdk/jmh/profile/AsyncProfiler.java).

The generic CPU `interval` does not configure async-profiler 4.5's cumulative lock sampling interval.
This script exposes no custom lock interval; choosing JFR output alone does not set one. A custom
interval would require separately configuring JMH's `lock` option and verifying the emitted native
configuration and captured events for the installed versions.

For async-profiler 4.5 on macOS, CPU profiling samples threads reported running through a wall-clock
fallback. Run CPU and wall captures separately; they cannot use that engine together. Lock profiling
can distort short critical sections and misses successful initial spinning. Use it to identify
monitors; sampled lock durations are not total blocked time. Use JFR only when richer event detail
is needed. Acceptance timing is always unprofiled.

Separate active workers, maintenance, and idle pool threads. Exclude setup/inter-iteration waits;
keep inclusive versus leaf percentages distinct. A Java line beside monitor entry/exit or a fence
is not proof that its expression caused the cost. `javap` is bytecode, not C2 machine code: inspect
hot native instructions and inlining/compilation context for JIT-dependent claims. Without that
evidence, retain an unverified hypothesis rather than a score-driven source rearrangement.

## Validate the candidate

Select named methods from the Test Discovery Guide in `.claude/rules/testing.md`, with relevant
concurrency and behavioral gates. Keep test task/selectors on one physical line for the current
`test-scope-guard.sh` parser. Verify fresh XML counts, no failures/errors/skips, and correct loaded
artifacts. Failed discovery containers and zero-test exits are not passes. Preserve any toolchain
failure and the scope of a supported workaround.

Keep raw failures and uncertainty in the local ledger. Distill adjudicated negative mechanisms into
`.claude/docs/ruled-out.md` Performance with self-contained scope, evidence, rationale, and a reopening
condition. A missing signal is not proof of dead code; an incomplete measurement is not a ruling.
