# Caffeine

High-performance, near-optimal caching library for Java 11+.

## Build & Test

```bash
./gradlew :caffeine:build                                    # Full build
./gradlew :caffeine:test --tests 'ClassName'                 # Single test class
./gradlew :caffeine:test --tests 'ClassName.methodName'      # Single method
./gradlew :caffeine:compileTestJava                          # Compile tests only
```

A single test method is fine to run even when it sweeps the full `@CacheSpec` matrix; it's whole
classes and the full suite to avoid locally, or to narrow with the `-P` flags below. CI runs the
full matrix sharded across 40 workers. See `.claude/rules/testing.md`.

### Test Filtering

```bash
./gradlew :caffeine:test -Pimplementation=caffeine  # Cache type (caffeine/guava)
./gradlew :caffeine:test -Pkeys=strong              # Key reference (strong/weak)
./gradlew :caffeine:test -Pvalues=strong            # Value reference (strong/weak/soft)
./gradlew :caffeine:test -Pcompute=sync             # Compute mode (sync/async)
./gradlew :caffeine:test -Pstats=enabled            # Stats recording (enabled/disabled)
```

### Specialized Test Suites

```bash
./gradlew :caffeine:frayTest         # Fray concurrency interleaving
./gradlew :caffeine:lincheckTest     # LinCheck linearizability
./gradlew :caffeine:fuzzTest         # Fuzzing (Jazzer)
./gradlew :caffeine:jcstress         # JCStress concurrency stress tests
./gradlew :caffeine:googleTest       # Guava collections tests
./gradlew :caffeine:apacheTest       # Apache Commons collections tests
./gradlew :caffeine:eclipseTest      # Eclipse Collections' collections tests
./gradlew :caffeine:jctoolsTest      # JCTools collections tests
./gradlew :caffeine:jsr166Test       # JSR-166 collections tests
./gradlew :caffeine:openjdkTest      # OpenJDK collections tests
./gradlew :caffeine:moduleTest       # Java module system tests
./gradlew :caffeine:osgiTest         # OSGi bundle tests
```

Tests cannot be `@Disabled` or skipped — the build fails on any skipped test.

### Static Analysis

```bash
./gradlew :caffeine:build -Pspotbugs  # SpotBugs
./gradlew :caffeine:build -Ppmd       # PMD
.github/scripts/analyze.sh            # all
```

ErrorProne + NullAway run on every build. Prefer fixing warnings over suppressing them; see
`.claude/rules/errorprone.md` for the few sanctioned suppressions and how to write them.

### Benchmarks & Analysis

```bash
./gradlew jmh -PincludePattern=GetPutBenchmark               # JMH microbenchmarks
./gradlew :caffeine:memoryOverhead                           # JOL object layout analysis
./gradlew :caffeine:stress --workload read --duration PT30S  # Stress testing (read, write, refresh)
```

## Style

Google Java Style. Contributors must sign a CLA.

## Guidelines

- Before suggesting dependency versions, Semgrep rulesets, or tool integrations, verify they exist (check Maven Central, registries, JDK release notes). Never recommend unverified tools. Use latest versions.
- Stay focused on the specific task requested. Don't produce unsolicited broad recommendation plans or premature "ready for engineer follow-up" conclusions.
- Lossy/best-effort semantics (read buffer drops, approximate frequency counts, eventual consistency) are intentional design trade-offs in the cache — not defects. Read `.claude/docs/design-decisions.md` before flagging these.
- When fixing a bug or making a design change, update or create `.claude/` files (docs, rules, skills, agents) to keep them in sync with the change.
- Work that will span sessions gets a `LEDGER.md` work queue (itemized rows, status updated in place) alongside its scripts and data under `.local/experiments/<topic>/`. Being gitignored, that workspace survives the branch resets and rebases that remove checked-in artifacts — a narrative report on its own is not a handoff. It is ephemeral and machine-local, though: checked-in files must not reference `.local/` paths (the tree may be purged, and other clones don't have it). Distill durable conclusions into `.claude/` docs; workspace pointers belong in other `.local` files or in memory.
- When parallel workstreams report conflicting values for the same measurement, re-measure it directly rather than averaging them or trusting the more confident one. The conflict is usually an instrumentation artifact in one of them, and it otherwise ships as a finding.
- Don't blindly suggest committing after writing code. Actually run the tests and verify the output before proposing to commit.

## Architecture

Core: `caffeine/src/main/java/com/github/benmanes/caffeine/cache/`

| File | Purpose |
|------|---------|
| `BoundedLocalCache.java` | Main cache logic: eviction, expiration, compute |
| `FrequencySketch.java` | TinyLFU admission frequency counters |
| `WindowClimber.java` | Adaptive hill climber sizing the admission window |
| `BoundedBuffer.java` | Striped ring buffer for read recording |
| `MpscGrowableArrayQueue.java` | Write buffer (multi-producer single-consumer) |
| `TimerWheel.java` | Hierarchical timer wheel for variable expiration |
| `Node.java` | Node interface (implementations are code-generated) |

Tests: `caffeine/src/test/java/com/github/benmanes/caffeine/cache/`

## Code Generation

Node classes (PS, PW, PSAWMW, etc.) are **generated by javaPoet**. Never edit files
in `build/generated/`. Edit the generators in `caffeine/src/javaPoet/java/` instead.

```bash
./gradlew :caffeine:generateNodes :caffeine:generateLocalCaches
```

Node naming: P=strong key, F=weak key, S=strong value, W=weak value, D=soft value.
Suffixes: A=access-time, W=write-time, R=refresh, MS=unweighted eviction, MW=weighted eviction.

## Project Structure

```
caffeine/    — Core cache library
guava/       — Guava compatibility adapter
jcache/      — JSR-107 JCache adapter
simulator/   — Cache policy simulator
```

## Reference Docs

For deep dives, read these on demand (not auto-loaded to save context):

- `.claude/docs/design-decisions.md` — why non-obvious choices are intentional, not bugs
- `.claude/docs/synchronization.md` — lock hierarchy, access modes, callback invocation points
- `.claude/docs/testing.md` — CacheSpec parameterization, Truth subjects, test utilities
- `.claude/docs/research-foundations.md` — papers mapped to implementation (TinyLFU, BP-Wrapper, etc.)
- `.claude/docs/hill-climber.md` — the adaptive window climber: goal, adversarial cases, the probe machine, and the graveyard of alternatives
- `.claude/docs/adaptive-window.html` — THE climber document (problem → control theory → design space → the shipped machine → evidence → appendix); the retired research-record HTMLs are archived in the local climber-failure-modes workspace
- `.claude/docs/finding-taxonomy.md` — standard severity/category schema for audit and review findings
- `.claude/docs/jsr107-conformance.md` — JSR-107 (JCache) conformance

When to read which doc:
- Concurrency or thread-safety work → `synchronization.md`
- Auditing or reviewing code → `design-decisions.md` first (prevents false positives)
- Writing or modifying tests → `testing.md`
- Understanding algorithm choices → `research-foundations.md`
- Touching the window climber / `determineAdjustment` → `hill-climber.md` (new to the area → `adaptive-window.html` first)
- Interpreting or writing audit findings → `finding-taxonomy.md`
- Auditing JSR-107 conformance of the jcache adapter → `jsr107-conformance.md`

## Claude Code Extensions

- **Rules** (`.claude/rules/`): project conventions, loaded automatically when relevant
- **Skills** (`/review-change`): multi-layer parallel code review with blind + design-aware + regression pattern matching
- **Skills** (`/audit-*`): 25 snapshot-style deep analysis skills for concurrency, correctness, and performance. Scope is repository-wide — the auditor agent's module map covers core, guava, jcache, simulator, and examples (all hold the same quality bar); pass a module or path argument to focus a run
- **Skills** (`/audit-adversarial`): hostile full-codebase review with NO design context — finds bugs domain familiarity masks
- **Skills** (`/audit-temporal-walk`): heavyweight history-mining audit. Walks every commit oldest-first, forward-tracking issues across the project's full history. Catches bugs snapshot-style audits cannot — half-fixes invisible from current state, latent+trigger pairs across multi-commit interactions. Manually-invoked CLI tool (`walker.py` + `verify.py`), hours-long, rare-run (every several months or before a major release). Ships focused variants over the same engine — diff-shape lenses (deletion/sibling/intent), a fix-commit walk, a test-coverage-regression walk, and a forward-tracked invariant ledger — orchestrated as a battery by `run.py`; invoking the skill presents the variant menu so they aren't forgotten
- **Skills** (`/audit-jcache-conformance`): JSR-107 1.1.1 spec-conformance verification for the jcache adapter.
- **Skills** (`/audit-third-party-contracts`): external-library and JDK contract misuse across adapters, simulator, and examples — verifies call-site assumptions (error paths, duplicate/empty inputs, disposal) against upstream docs
- **Skills** (`/sim-*`): simulator workflow automation — `/sim-compare` for policy comparison charts, `/sim-analyze` for trace characterization
- **Skills** (`/climber-gate`): regenerate the window climber's adversarial trap traces (deterministic generators, committed) and run the behavioral gate vs LRU/ceiling anchors in the simulator — run after any `WindowClimber` change; companion to `/audit-adaptivity`
- **Skills** (`/audit-regret`): adversarial workload search for eviction regret. Mutates a compositional trace generator to find workloads where the climber fails to close the gap to its achievable ceiling, shrinks each to a minimal witness, finds its phase transition, classifies the failure (wrong equilibrium / slow convergence / masked signal / insufficient exploration / oscillation / memory / irreversible damage / aliasing / premature commitment / tier discontinuity / structural), and routes it to the controller, policy structure, or recovery layer. Produces new `/climber-gate` rows and `hill-climber.md` §8 directions rather than bug reports
- **Auditor agent** (`.claude/agents/`): multi-pass — analysis → reflection → evaluator challenge → targeted re-audit

### Audit Selection Guide

| If concerned about... | Run |
|---|---|
| Thread-safety of a specific change | `/audit-jmm` |
| API contract ordering under concurrency | `/audit-linearizability` |
| Feature interactions (eviction+expiry+refresh) | `/audit-feature-interaction` |
| Exception paths leaving inconsistent state | `/audit-exception-safety` |
| Memory leaks after removal/eviction | `/audit-memory-retention` |
| Arithmetic edge cases (overflow, off-by-one) | `/audit-arithmetic` |
| Shutdown/close/GC races | `/audit-lifecycle` |
| Fresh-eyes adversarial sweep (no domain context) | `/audit-adversarial` |
| Full correctness proof of public methods | `/audit-correctness-proof` |
| Map/ConcurrentMap contract compliance | `/audit-map-contract` |
| Re-entrancy from user callbacks | `/audit-reentrancy` |
| Concurrent iteration and view consistency | `/audit-iteration` |
| Performance inefficiencies on hot paths | `/audit-performance` |
| Serialization proxy completeness and safety | `/audit-serialization` |
| Behavior under extreme/adversarial API inputs | `/audit-adversarial-input` |
| Progress and termination guarantees | `/audit-liveness` |
| Test coverage gaps and missing edge cases | `/audit-coverage-gaps` |
| Per-subsystem concurrency correctness | `/audit-subsystem-safety` |
| Build/CI configuration correctness | `/audit-build-ci` |
| Documented behavior vs. implementation drift | `/audit-contract-drift` |
| Divergences between sibling implementations | `/audit-sibling-divergence` |
| Adaptive hill-climber / window-resize correctness | `/audit-adaptivity` |
| Workloads where eviction underperforms its own ceiling | `/audit-regret` |
| Drain-status / node-lifecycle / async-value state machines | `/audit-state-machine` |
| JSR-107 (JCache) spec conformance of the adapter | `/audit-jcache-conformance` |
| Third-party/JDK API contract misuse (adapters, simulator, examples) | `/audit-third-party-contracts` |

**Audit output**: reports go to `.local/audits/<model>/<skill-name>.md` — one directory per
producing model (`opus-5`, `gpt-5-codex`, …) plus `shared` for cross-model working documents like
the consolidated backlog. Gitignored but kept long-term; see `.claude/rules/audit-output.md`.

**Correctness vs regret**: the `/audit-*` skills above look for defects in what the code does. `/audit-regret` looks for workloads where correct code still loses hit rate, and its findings are failure classes with a responsible layer, not bugs. Its companions are `/climber-gate` (re-runs the traps already known) and `/audit-adaptivity` (implementation defects in the same subsystem).

**Review vs Audit**: `/review-change` is for pre-commit code review — reads design docs and filters known-intentional patterns. `/audit-*` skills are for correctness doubts — independent, no design context filtering. Use review for routine changes, audit when you need fresh-eyes analysis. `/audit-temporal-walk` is a third category (heavyweight, rare-run history-mining) — see its `SKILL.md` for invocation.
