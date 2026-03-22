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
- Lambda-based `argumentProviders.add { listOf(outputDir.toString()) }` — use a typed
  `CommandLineArgumentProvider` with `@Internal` on output directory properties instead
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
