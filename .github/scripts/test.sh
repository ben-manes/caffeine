#!/bin/bash

case $* in
  --caffeine)
    set -eux
    ./gradlew --daemon \
        :caffeine:strongKeysAndSoftValuesStatsSyncCaffeineTest \
        :caffeine:strongKeysAndSoftValuesSyncCaffeineTest \
        :caffeine:strongKeysAndStrongValuesAsyncCaffeineTest \
        :caffeine:strongKeysAndStrongValuesStatsAsyncCaffeineTest \
        :caffeine:strongKeysAndStrongValuesStatsSyncCaffeineTest \
        :caffeine:strongKeysAndStrongValuesSyncCaffeineTest \
        :caffeine:strongKeysAndWeakValuesStatsSyncCaffeineTest \
        :caffeine:strongKeysAndWeakValuesSyncCaffeineTest \
        :caffeine:weakKeysAndSoftValuesStatsSyncCaffeineTest \
        :caffeine:weakKeysAndSoftValuesSyncCaffeineTest \
        :caffeine:weakKeysAndStrongValuesAsyncCaffeineTest \
        :caffeine:weakKeysAndStrongValuesStatsAsyncCaffeineTest \
        :caffeine:weakKeysAndStrongValuesStatsSyncCaffeineTest \
        :caffeine:weakKeysAndStrongValuesSyncCaffeineTest \
        :caffeine:weakKeysAndWeakValuesStatsSyncCaffeineTest \
        :caffeine:weakKeysAndWeakValuesSyncCaffeineTest \
        :caffeine:junitTest \
        :guava:check \
        :jcache:check \
        :simulator:check
    ./gradlew -x compileCodeGenJava -x compileJava :caffeine:isolatedTest
    ./gradlew -x compileCodeGenJava -x compileJava :caffeine:slowCaffeineTest
    ;;
  --guava)
    set -eux
    ./gradlew --daemon \
        :caffeine:strongKeysAndSoftValuesStatsSyncGuavaTest \
        :caffeine:strongKeysAndSoftValuesSyncGuavaTest \
        :caffeine:strongKeysAndStrongValuesStatsSyncGuavaTest \
        :caffeine:strongKeysAndStrongValuesSyncGuavaTest \
        :caffeine:strongKeysAndWeakValuesStatsSyncGuavaTest \
        :caffeine:strongKeysAndWeakValuesSyncGuavaTest \
        :caffeine:weakKeysAndSoftValuesStatsSyncGuavaTest \
        :caffeine:weakKeysAndSoftValuesSyncGuavaTest \
        :caffeine:weakKeysAndStrongValuesStatsSyncGuavaTest \
        :caffeine:weakKeysAndStrongValuesSyncGuavaTest \
        :caffeine:weakKeysAndWeakValuesStatsSyncGuavaTest \
        :caffeine:weakKeysAndWeakValuesSyncGuavaTest
    ./gradlew -x compileCodeGenJava -x compileJava :caffeine:slowGuavaTest
    ;;
  *)
    set -eux
    ./gradlew --daemon check \
        :caffeine:isolatedTest \
        :caffeine:slowGuavaTest \
        :caffeine:slowCaffeineTest
esac
