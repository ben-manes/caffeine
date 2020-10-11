#!/bin/bash

set -eu

run() {
  echo $1
  eval $1
}

runSlow() {
  echo $1
  eval $1 &
  pid=$!
  while kill -0 $pid 2>/dev/null
  do
    echo -ne .
    sleep 1
  done
}

case "${1:?''}" in
  analysis)
    run "./gradlew pmdJavaPoet pmdMain -Dpmd --console plain"
    run "./gradlew spotbugsJavaPoet spotbugsMain spotbugsJmh -Dspotbugs --console plain"
    run "sh -c 'cd examples/stats-metrics && ./gradlew test --console plain --no-daemon'"
    run "sh -c 'cd examples/write-behind-rxjava && mvn test'"
    run "sh -c 'cd examples/coalescing-bulkloader && mvn test'"
    ;;
  tests)
    run "./gradlew check --console plain"
    runSlow "./gradlew :caffeine:slowCaffeineTest --console plain"
    runSlow "./gradlew :caffeine:slowGuavaTest --console plain"
    if [[ (${CI:-false} == "true") && (${TRAVIS_BRANCH:-other} = "master") ]]; then
      run "./gradlew coveralls publish --console plain"
      runSlow "./gradlew sonarqube --console plain"
    fi
    ;;
  *)
    echo $"Usage: $0 {analysis|tests}"
    exit 1
esac
