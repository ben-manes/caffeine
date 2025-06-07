#!/bin/bash
set -eux

./gradlew \
    forbiddenApis -DforbiddenApis \
    ecjJavaPoet ecjMain ecjCodeGen ecjJmh ecjTest ecjJcstress \
    pmdJavaPoet pmdMain pmdCodeGen pmdJmh pmdTest pmdJcstress -Dpmd \
    spotbugsJavaPoet spotbugsMain spotbugsCodeGen spotbugsJmh spotbugsTest spotbugsJcstress -Dspotbugs \
    "$@"
