---
paths:
  - "caffeine/src/main/java/**"
  - "caffeine/src/test/java/**"
---

# ErrorProne & NullAway

## Configuration
- ErrorProne and NullAway run on every build; NullAway violations are **errors** (not warnings)
- Annotation library: **JSpecify** (`org.jspecify`), module-wide `@NullMarked` in module-info.java
- All types are non-null by default; use `@Nullable` only where null is intentional
- `jspecifyMode = true` with `handleTestAssertionLibraries`, `checkOptionalEmptiness`, `checkContracts`

## Common Patterns
- **Lazy initialization**: fields initialized after construction (e.g., `FrequencySketch.ensureCapacity()`) may need `@SuppressWarnings("NullAway.Init")`
- **Nullable in non-nullable context**: intentional null returns in framework methods (e.g., CHM compute returning null to remove) may need `@SuppressWarnings({"DataFlowIssue", "NullAway"})`
- **Cross-class `@GuardedBy`**: EP doesn't support it (e.g., FrequencySketch guarded by BoundedLocalCache.evictionLock). Don't add cross-class annotations; concurrency tests are the safety net
- **`UnnecessarySemicolon`**: disabled due to upstream EP bug (google/error-prone#5548), don't re-enable

## Suppressions
- Suppressions are rare (~9 files in main source) — prefer fixing over suppressing
- When suppressing, always combine `DataFlowIssue` and `NullAway` if the issue is a deliberate null assignment
- Code generation (`compileCodeGenJava`) has NullAway disabled entirely

## Disabled Checks
- 18 EP checks disabled as project style decisions (e.g., `AvoidObjectArrays`, `StaticImport`, `StatementSwitchToExpressionSwitch`)
- Refaster rules for ImmutableList/Set/Map builders and JUnit→AssertJ migrations are disabled
