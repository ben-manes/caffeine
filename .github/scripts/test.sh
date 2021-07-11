#!/bin/bash
set -eux

./gradlew check
./gradlew :caffeine:isolatedTests
./gradlew :caffeine:slowGuavaTest
./gradlew :caffeine:slowCaffeineTest
