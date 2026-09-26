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
- Multi-line YAML values that get interpolated elsewhere (a `>` folded scalar
  keeps a trailing newline; splicing one into another folded scalar embeds that
  newline mid-string, so a consumer splitting on literal spaces mis-parses the
  spliced-in value's last token)
- Performance problems in the build
- Security issues (dependency vulnerabilities, secret exposure)
- Bad practices that could cause false confidence
- Retry and re-run paths (in-job retry loops, `workflow_run` re-runners) and what each treats
  as infrastructure. A job that exceeds `timeout-minutes` concludes `cancelled`, not `failure`,
  and a hung test reaches CI that way
- Matrix legs that name a JDK distribution: without a vendor constraint, toolchain resolution
  may pick another detected JDK of the same version

Report only issues that could cause incorrect artifacts, missing
failures, or false confidence in test results.

Price a CI finding against live history, not only the YAML: `gh api` for the ruleset and
check-run conclusions, `gh run view <id> --log` (with `--attempt`/`--job`) for what a green run
actually hid. A mechanism in a workflow and a run that concealed a failure are separate claims.
Read-only queries only.
