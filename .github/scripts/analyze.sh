#!/bin/bash
set -eu

run() {
  echo $1
  eval $1
}

run "./gradlew pmdJavaPoet pmdMain -Dpmd"
run "./gradlew spotbugsJavaPoet spotbugsMain spotbugsJmh -Dspotbugs"
