---
name: audit-build-ci
description: Audit build and CI configuration for correctness risks
context: fork
agent: auditor
disable-model-invocation: true
---

Audit the build and CI configuration for subtle correctness risks.

Read the build files and CI workflows before analyzing:
- `build.gradle.kts` (root and caffeine module)
- `gradle/plugins/` (custom Gradle plugins)
- `.github/workflows/` (GitHub Actions)
- `gradle.properties`

Consider:
- Misconfigured dependency scopes
- Incorrect test isolation
- Non-reproducible builds
- Incorrect Gradle cache configuration
- Missing failure modes (tests passing when they shouldn't)
- Incorrect CI matrix coverage
- Silent test skipping
- Performance problems in the build
- Security issues (dependency vulnerabilities, secret exposure)
- Bad practices that could cause false confidence

Report only issues that could cause incorrect artifacts, missing
failures, or false confidence in test results.
