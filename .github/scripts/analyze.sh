#!/bin/bash
set -eux

./gradlew \
    forbiddenApis -DforbiddenApis \
    ecjJavaPoet ecjMain ecjCodeGen ecjJmh ecjTest \
    pmdJavaPoet pmdMain pmdCodeGen pmdJmh pmdTest -Dpmd \
    spotbugsJavaPoet spotbugsMain spotbugsCodeGen spotbugsJmh spotbugsTest -Dspotbugs \
    "$@"
