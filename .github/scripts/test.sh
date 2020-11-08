#!/bin/bash
set -eu

run() {
  echo $1
  eval $1
}

run "./gradlew check"
run "./gradlew :caffeine:slowGuavaTest"
run "./gradlew :caffeine:slowCaffeineTest"
