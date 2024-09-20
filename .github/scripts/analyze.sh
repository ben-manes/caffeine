#!/bin/bash
set -eux

./gradlew \
    forbiddenApis -DforbiddenApis \
    pmdJavaPoet pmdMain pmdCodeGen pmdJmh pmdTest -Dpmd \
    spotbugsJavaPoet spotbugsMain spotbugsCodeGen spotbugsJmh spotbugsTest -Dspotbugs \
    "$@"
