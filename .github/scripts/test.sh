#!/bin/bash
set -eux

./gradlew check
./gradlew :caffeine:isolatedTest
./gradlew :caffeine:slowGuavaTest
./gradlew :caffeine:slowCaffeineTest
