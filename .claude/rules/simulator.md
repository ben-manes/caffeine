---
paths:
  - "simulator/**"
---

# Simulator Conventions

- Configuration: HOCON in `simulator/src/main/resources/reference.conf`
- Override config with `-Dcaffeine.simulator.*` system properties
- Trace format specified in path: `format:filepath` (e.g., `lirs:trace.gz`)
- 50+ policies across 9 categories (adaptive, greedy_dual, irr, linked, opt, product, sampled, sketch, two_queue)
- Policy interface: `record(AccessEvent)`, `finished()`, `stats()`
- New policies need `@PolicySpec` annotation and registration in `Registry`
- Run single sim: `./gradlew simulator:run -q -Dcaffeine.simulator.*=...`
- Run multi-size with charts: `./gradlew simulator:simulate -q --maximumSize=... --metric=...`
- Convert trace formats: `./gradlew simulator:rewrite -q --inputFormat=... --outputFormat=...`
