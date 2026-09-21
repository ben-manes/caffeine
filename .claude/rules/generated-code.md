---
paths:
  - "caffeine/src/javaPoet/**"
  - "caffeine/build/generated/**"
---

# Code Generation

- Node classes (PS.java, PW.java, PSAWMW.java, etc.) are generated — never edit them directly
- Generators live in `caffeine/src/javaPoet/java/com/github/benmanes/caffeine/cache/`
- Node naming: P=strong key, F=weak key, S=strong value, W=weak value, D=soft value
- Feature suffixes: A=access-time, W=write-time, R=refresh, MS=unweighted eviction, MW=weighted eviction
- Node and local-cache MS/MW suffixes need not match. Async eviction requires mutable node
  weights even with `maximumSize`; a synchronous singleton weigher omits per-node weight fields.
  Preserve these accounting requirements when specializing either generator.
- `Node` has constant getters, no-op setters, and throwing defaults. Match consumer feature
  gates to runtime factory selection and inherited overrides; a missing override can expose a
  silent default as well as an exception. `BoundedLocalCacheTest.node_unsupported` and
  `node_ignored` pin the base behavior.
- To regenerate: `./gradlew :caffeine:generateNodes :caffeine:generateLocalCaches`
- The `AddKey`, `AddValue`, `AddExpiration`, `AddMaximum`, `AddDeques`, `AddHealth` classes each add one feature dimension to nodes
- When auditing a field that appears in a generated class but does not exist
  in `BoundedLocalCache.java` (e.g., `weightedSize`, `policyWeight`,
  `metadata`, `climber`, deque links), trace it back to the corresponding
  `Add*.java` generator before reasoning about its type or storage. The
  protected accessors in `BoundedLocalCache` only declare the signatures; the
  actual fields and types are emitted by the generators.
- Hill-climber state is not generated: it lives in plain fields on the
  package-private `WindowClimber`, reached via the generated `climber` field
  (`AddMaximum`)
- `NodeContext.FieldAccess.DIRECT` emits ordinary Java field access using the field's
  declared semantics. `PLAIN` and `OPAQUE` instead select explicit VarHandle modes.
