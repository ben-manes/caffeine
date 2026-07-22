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

Use *constraints*, not a BOM `platform()`, for this. `base.caffeine` applies the pin to every
declarable configuration, and a platform is a real dependency — adding one suppresses
`Configuration.defaultDependencies`, which is how the sigstore plugin injects sigstore-java into
`sigstoreClientClasspath` (and how PMD/SpotBugs/etc. inject their tools). A blanket platform
silently empties those classpaths; `constraints.add` doesn't count as a declared dependency, so
defaults still fire. (`bc-jdk18on-bom` covers the whole family, but can't be used this way here.)
