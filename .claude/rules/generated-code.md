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
- To regenerate: `./gradlew :caffeine:generateNodes :caffeine:generateLocalCaches`
- The `AddKey`, `AddValue`, `AddExpiration`, `AddMaximum`, `AddDeques`, `AddHealth` classes each add one feature dimension to nodes
- When auditing a field that appears in a generated class but does not exist
  in `BoundedLocalCache.java` (e.g., `hitsInSample`, `missesInSample`,
  `weightedSize`, `policyWeight`, `queueType`, deque links), trace it back to
  the corresponding `Add*.java` generator before reasoning about its type or
  storage. The protected accessors in `BoundedLocalCache` only declare the
  signatures; the actual fields and types are emitted by the generators.
