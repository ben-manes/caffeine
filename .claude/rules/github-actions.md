---
paths:
  - ".github/workflows/**"
  - ".github/actions/**"
  - ".github/actionlint.yml"
---

# GitHub Actions

Use `$/` for same-repository action references, as required by zizmor's `self-repository` rule.
Until [actionlint #711](https://github.com/rhysd/actionlint/issues/711) is supported in CI,
`.github/actionlint.yml` ignores only the missing-ref diagnostic for `run-gradle` and
`run-benchmark`. Remove the ignore when upgrading to a version that supports this syntax.
The ignore does not add action input or output validation for these references.
