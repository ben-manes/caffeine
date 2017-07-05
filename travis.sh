# travis.sh
#!/bin/sh

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

case "$1" in
  analysis)
    run "./gradlew findbugsMain pmdMain -Dfindbugs -Dpmd --console plain"
    run "sh -c 'cd examples/stats-metrics && ./gradlew test --console plain'"
    run "sh -c 'cd examples/write-behind-rxjava && mvn test'"
    ;;
  unitTests)
    run "./gradlew check --console plain"
    run "./gradlew coveralls uploadArchives -x :caffeine:slowCaffeineTest -x :caffeine:slowGuavaTest --console plain"
    ;;
  slowTests)
    runSlow "./gradlew :caffeine:slowCaffeineTest --console plain"
    runSlow "./gradlew :caffeine:slowGuavaTest  --console plain"
    ;;
  *)
    echo $"Usage: $0 {analysis|unitTests|slowTests}"
    exit 1
esac
