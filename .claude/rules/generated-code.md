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
