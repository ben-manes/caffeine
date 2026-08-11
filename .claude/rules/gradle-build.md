---
paths:
  - "gradle/**"
  - "*/build.gradle.kts"
  - "build.gradle.kts"
  - "settings.gradle.kts"
---

# Gradle Build Conventions

## Build Cache Relocatability (critical — PR #1947)

Every `inputs.files()` on task outputs MUST have `.withPathSensitivity(PathSensitivity.RELATIVE)`.
Without it, absolute paths leak into cache keys, breaking cache reuse across machines.

Common patterns that break relocatability:
- `inputs.files(otherTask.outputs.files)` — add `.withPathSensitivity(RELATIVE)`
- Lambda `argumentProviders.add { ... }` / `jvmArgumentProviders.add { ... }` (incl. `Test`
  tasks) embedding an absolute path — Gradle fingerprints `asArguments()` opaquely, baking the
  path into the key. Use a typed `CommandLineArgumentProvider`: `@get:Internal` on the path
  property (its content is tracked elsewhere — `outputs.dir()` or an input `@Classpath`), value
  args stay `@get:Input`
- Resolving file paths at configuration time (e.g., in Javadoc options) — defer to
  `doFirst` blocks so paths are only resolved at execution time
- `inputs.files(downloadTask.map { it.outputs.files })` — add `.withPathSensitivity(RELATIVE)`

## Publishing

`com.gradleup.nmcp` (on each published project, via `publish.caffeine`) exposes its publications
as a variant; `com.gradleup.nmcp.aggregation` (on the root) collects them and uploads a bundle to
the Central Portal. `publishAggregationToCentralPortal` releases and
`publishAggregationToCentralSnapshots` publishes snapshots. Credentials arrive as
`ORG_GRADLE_PROJECT_centralPortalUsername` / `...Password`.

To see what a release would contain, `nmcpZipAggregation` builds the upload itself, an
intermediate step of `publishAggregationToCentralPortal`, into `build/nmcp/zip/aggregation.zip`;
`nmcpPublishAggregationToMavenLocal` installs the same files for a consuming build to resolve.
Pass `-Pversion.release=true` for the released coordinates rather than a snapshot.

The `Sign` tasks are incompatible with the configuration cache, which discards the entry for that
invocation rather than failing, so the publishing flows need no `--no-configuration-cache`.

Two non-default settings, both deliberate:

- `allowDuplicateProjectNames` — the root project is also named `caffeine`. It contributes no
  publication and carries no group, so there is no module identity to collide with `:caffeine`,
  which is what nmcp's check actually guards against (gradle/gradle#36167)
- `publishAllChecksums` — nmcp defaults to a leaner set that omits `.sha256`. The releases on
  Central carry md5/sha1/sha256/sha512 for every artifact, so this keeps parity

`.asc` signatures and sigstore `.sigstore.json` files need no wiring. Both attach to the
publication as a `DerivedMavenArtifact`, which any `PublishToMavenRepository` writes out, and
nmcp stages through an ordinary Gradle `maven` repository. A derived artifact is absent from
`publication.artifacts`, so listing that property is not a way to check for them. Signing runs
locally when `ORG_GRADLE_PROJECT_signingKey` / `...KeyId` / `...Password` are set, which is worth
doing before a release, as `signing.setRequired { false }` means an unsigned bundle builds
silently. Sigstore is gated on `ACTIONS_ID_TOKEN_REQUEST_URL` and so is reachable only in CI.

Central carries `.asc.md5` and friends for releases up to 3.2.4 that the Portal will not
reproduce. Gradle never generated them; the OSSRH staging API synthesized them server-side.

## Project Version

The released version is `version` in `gradle.properties`, which Gradle applies to every project.
`base.caffeine` appends `-SNAPSHOT` unless the build is given `-Pversion.release=true`, which is what
`release.yml` passes.

## Isolated Projects

The build logic is Isolated Projects clean; keep it that way. Check with

```bash
./gradlew help --isolated-projects -Dorg.gradle.isolated-projects.diagnostics=true
```

Without the diagnostics flag the build stops at the first violation. `help` only configures what
it needs, so a lazily-configured violation (a Javadoc or analysis task) hides until a wider graph
is requested — re-check with `--dry-run` over the module `build`, `javadoc`, `roseau`, `ecj`, and
`jacocoFullReport` tasks.

A project may not read another project's model. The replacements:

- `allprojects`/`subprojects` from the root — move the body into a convention plugin that each
  project applies (`base.caffeine` covers all of them), or `gradle.lifecycle.beforeProject` in
  settings
- `rootProject.layout.projectDirectory` — `isolated.rootProject.projectDirectory`
- Another project's tasks, extensions, or output directories — publish a consumable variant on
  the producer and resolve it on the consumer (see `selectsCoverageData` and
  `selectsJavadocDirectory` in `ConfigurationExtensions.kt`)
- Ordering against another project's tasks — `mustRunAfter` a resolvable configuration whose
  artifacts are `builtBy` those tasks. Ordering against a task *path* works too, but a lifecycle
  task only orders when it is itself in the graph, which is rarely what you want

**The root project must not share `group:name` with a subproject.** It carries no `group` for
this reason (`java-library.caffeine` sets it, `base.caffeine` does not). When the root resolves a
graph containing `project(":caffeine")`, matching coordinates make module conflict resolution
collapse the dependency onto the root — the failure reads as a variant-ambiguity error listing
only the dependency-analysis plugin's variants, which is the tell that the java variants were
never candidates. Because the root's coordinates fed it, `sonar.projectKey` is pinned explicitly;
changing it renames the SonarCloud project. Nmcp guards against the same hazard by project name,
which is why the aggregation sets `allowDuplicateProjectNames`.

Aggregation across projects goes through `jacoco-report-aggregation` rather than reaching into
the covered projects. Its `aggregateCodeCoverageReportResults` resolves with no attributes, which
the dependency-analysis plugin's seven extra consumable variants make ambiguous, so the build
requests a runtime jar on it explicitly. `executionData` stays a `fileTree` glob rather than the
plugin's resolved coverage data: CI runs `jacocoFullReport` over `.exec` files unpacked from the
sharded test jobs and must not re-run the tests.

Still blocked upstream, both at their latest release: `org.sonarqube` (`allprojects`) and
`org.pastalab.fray.gradle` (`rootProject.layout.buildDirectory`, `FrayPlugin.kt`). Isolated
Projects cannot be enabled until both move, so clearing one buys nothing.

Applying them conditionally on the requested task names is the obvious workaround, and it is a bad
trade for fray: the `frayTest` suite is registered by the build, not the plugin, so an invocation
that misses the condition runs it without instrumentation and passes green. That is the silent
skip the suite exists to prevent. Sonar fails loudly instead (no such task), but on its own it
leaves fray, so there is nothing to gain. Wait for upstream.

## Develocity

Caffeine has a free Develocity license for build cache analysis and optimization.
Build validation experiments verify cache relocatability.

## Source Sets

Beyond standard `main`/`test`, the build uses custom source sets:
- `javaPoet` — code generators (must compile before `generateNodes`/`generateLocalCaches`)
- `codeGen` — generated output (compiled after generation, included in main jar)
- `frayTest`, `fuzzTest`, `lincheckTest`, etc. — specialized test suites

## Configuration Cache

Enabled by default (`org.gradle.configuration-cache=true`). Some tasks are incompatible:
`frayTest`, `jmh`, `jmhReport`, `coverallsJacoco`.

## Lint toggle (`-Plint`) — ErrorProne/NullAway off the critical path

`isLintEnabled()` (`ProjectExtensions.kt`, `providers.gradleProperty("lint")`, default `true`)
gates the javac-level analysis bundle: ErrorProne + NullAway (`errorprone.caffeine`) and `-Werror`
(`java-library.caffeine`, still additionally guarded by `isCI()`). `-Xlint:all` stays always-on
(cheap, emit-only). A local `./gradlew build` runs the full analysis; `-Plint=false` gives a bare
compile.

In CI the `run-gradle` action defaults `lint=false` (injected as `ORG_GRADLE_PROJECT_lint`,
mirroring `earlyAccess`), so ErrorProne/NullAway run **only** in the dedicated `analysis.yml`
`errorprone` job (JDK 11 + 26) — parallel to the tests, like PMD/SpotBugs/ECJ. Everything else
(build.yml compile + shards, the other analysis jobs, examples, jcstress, …) compiles bare. The
gate is a branch-protection required check, not a `needs:` edge (cross-workflow isn't possible).

**Invariant — do not break:** toggling `lint` changes each `JavaCompile`'s compiler args, hence its
build-cache key. Any job that pulls compiled output from the remote cache must run with the *same*
`lint` value as whoever populated it, or it silently recompiles. This is why the whole `build.yml`
pipeline stays `lint=false`: the 40 test shards hit the bare `compile` job's cache entry. Never
re-enable ErrorProne on `build.yml`'s compile path without also flipping the shards, or they each
recompile with ErrorProne.

## BouncyCastle module alignment

The `bc*-jdk18on` modules (`bcprov`, `bcpkix`, `bcutil`) are one release train and MUST
resolve to the same version — mixing them fails at runtime with a bare algorithm name
(e.g. `id_MLKEM768_RSA2048_SHA3_256` from bcprov 1.85's new composite-KEM registry that an
older bcpkix/bcutil can't map). All three are pinned to `bouncycastle-jdk18on` via the
`constraints` bundle so a Renovate bump moves them together. The trap: we consume only
`bcprov` directly, but `bcpkix`/`bcutil` arrive transitively (e.g. sigstore-java on
`sigstoreClientClasspath`) — constraining `bcprov` alone silently skews the signing classpath.
Verify with `dependencyInsight --configuration sigstoreClientClasspath --dependency org.bouncycastle`.

**An aggregation scope must be added to `base.caffeine`'s `ignored` list.** The pin goes on every
declarable configuration, and a resolvable configuration that extends one inherits it. When that
configuration asks for something other than a library (`Category=verification` for `coverageData`,
`Category=documentation` for `caffeineJavadocDirectory`), no external module can answer, and
`dependencyUpdates` — which the root runs with `checkConstraints = true` across every project —
reports the whole bundle as "Failed to determine the latest version". The report is the only
symptom; resolution itself is unaffected, so this surfaces nowhere else.

Use *constraints*, not a BOM `platform()`, for this. `base.caffeine` applies the pin to every
declarable configuration, and a platform is a real dependency — adding one suppresses
`Configuration.defaultDependencies`, which is how the sigstore plugin injects sigstore-java into
`sigstoreClientClasspath` (and how PMD/SpotBugs/etc. inject their tools). A blanket platform
silently empties those classpaths; `constraints.add` doesn't count as a declared dependency, so
defaults still fire. (`bc-jdk18on-bom` covers the whole family, but can't be used this way here.)
