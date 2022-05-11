#!/bin/bash
set -eu

CAFFEINE_STRONG_KEYS=
CAFFEINE_WEAK_KEYS=
CAFFEINE_CHECK=
GUAVA_STRONG_KEYS=
GUAVA_WEAK_KEYS=
GUAVA_CHECK=

for i in "$@"; do
  case $i in
    --caffeine=strongKeys)
      CAFFEINE_STRONG_KEYS=true
      ;;
    --caffeine=weakKeys)
      CAFFEINE_WEAK_KEYS=true
      ;;
    --caffeine=check)
      CAFFEINE_CHECK=true
      ;;
    --guava=strongKeys)
      GUAVA_STRONG_KEYS=true
      ;;
    --guava=weakKeys)
      GUAVA_WEAK_KEYS=true
      ;;
    --guava=check)
      GUAVA_CHECK=true
      ;;
    --all)
      CAFFEINE_STRONG_KEYS=true
      CAFFEINE_WEAK_KEYS=true
      CAFFEINE_CHECK=true
      GUAVA_STRONG_KEYS=true
      GUAVA_WEAK_KEYS=true
      GUAVA_CHECK=true
      ;;
    -*)
      echo "Unknown option $i; --caffeine=? or --guava=? with strongKeys, weakKeys, or check"
      exit 1
      ;;
  esac
done

if [[ -n "$CAFFEINE_STRONG_KEYS" ]]; then
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndSoftValuesStatsSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndSoftValuesSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndStrongValuesAsyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndStrongValuesStatsAsyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndStrongValuesStatsSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndStrongValuesSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndWeakValuesStatsSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndWeakValuesSyncCaffeineTest)
fi
if [[ -n "$CAFFEINE_WEAK_KEYS" ]]; then
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndSoftValuesStatsSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndSoftValuesSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndStrongValuesAsyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndStrongValuesStatsAsyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndStrongValuesStatsSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndStrongValuesSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndWeakValuesStatsSyncCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndWeakValuesSyncCaffeineTest)
fi
if [[ -n "$CAFFEINE_CHECK" ]]; then
  (set -x; ./gradlew --daemon :caffeine:slowCaffeineTest)
  (set -x; ./gradlew --daemon :caffeine:isolatedTest)
  (set -x; ./gradlew --daemon :caffeine:junitTest)
  (set -x; ./gradlew --daemon :simulator:check)
  (set -x; ./gradlew --daemon :jcache:check)
  (set -x; ./gradlew --daemon :guava:check)
fi
if [[ -n "$GUAVA_STRONG_KEYS" ]]; then
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndSoftValuesStatsSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndSoftValuesSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndStrongValuesStatsSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndStrongValuesSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndWeakValuesStatsSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:strongKeysAndWeakValuesSyncGuavaTest)
fi
if [[ -n "$GUAVA_WEAK_KEYS" ]]; then
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndSoftValuesStatsSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndSoftValuesSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndStrongValuesStatsSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndStrongValuesSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndWeakValuesStatsSyncGuavaTest)
  (set -x; ./gradlew --daemon :caffeine:weakKeysAndWeakValuesSyncGuavaTest)
fi
if [[ -n "$GUAVA_CHECK" ]]; then
  (set -x; ./gradlew --daemon :caffeine:slowGuavaTest)
fi
