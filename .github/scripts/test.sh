#!/bin/bash

case $* in
  --caffeine)
    set -eux
    ./gradlew --daemon :caffeine:strongKeysAndSoftValuesStatsSyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndSoftValuesSyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndStrongValuesAsyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndStrongValuesStatsAsyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndStrongValuesStatsSyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndStrongValuesSyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndWeakValuesStatsSyncCaffeineTest
    ./gradlew --daemon :caffeine:strongKeysAndWeakValuesSyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndSoftValuesStatsSyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndSoftValuesSyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndStrongValuesAsyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndStrongValuesStatsAsyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndStrongValuesStatsSyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndStrongValuesSyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndWeakValuesStatsSyncCaffeineTest
    ./gradlew --daemon :caffeine:weakKeysAndWeakValuesSyncCaffeineTest
    ./gradlew --daemon :caffeine:slowCaffeineTest
    ./gradlew --daemon :caffeine:isolatedTest
    ./gradlew --daemon :caffeine:junitTest
    ./gradlew --daemon :simulator:check
    ./gradlew --daemon :jcache:check
    ./gradlew --daemon :guava:check
    ;;
  --guava)
    set -eux
    ./gradlew --daemon :caffeine:strongKeysAndSoftValuesStatsSyncGuavaTest
    ./gradlew --daemon :caffeine:strongKeysAndSoftValuesSyncGuavaTest
    ./gradlew --daemon :caffeine:strongKeysAndStrongValuesStatsSyncGuavaTest
    ./gradlew --daemon :caffeine:strongKeysAndStrongValuesSyncGuavaTest
    ./gradlew --daemon :caffeine:strongKeysAndWeakValuesStatsSyncGuavaTest
    ./gradlew --daemon :caffeine:strongKeysAndWeakValuesSyncGuavaTest
    ./gradlew --daemon :caffeine:weakKeysAndSoftValuesStatsSyncGuavaTest
    ./gradlew --daemon :caffeine:weakKeysAndSoftValuesSyncGuavaTest
    ./gradlew --daemon :caffeine:weakKeysAndStrongValuesStatsSyncGuavaTest
    ./gradlew --daemon :caffeine:weakKeysAndStrongValuesSyncGuavaTest
    ./gradlew --daemon :caffeine:weakKeysAndWeakValuesStatsSyncGuavaTest
    ./gradlew --daemon :caffeine:weakKeysAndWeakValuesSyncGuavaTest
    ./gradlew --daemon :caffeine:slowGuavaTest
    ;;
  *)
    set -eux
    ./gradlew --daemon check \
        :caffeine:isolatedTest \
        :caffeine:slowGuavaTest \
        :caffeine:slowCaffeineTest
esac
