#!/bin/bash
set -eux

./gradlew --console colored \
    forbiddenApis -PforbiddenApis \
    ecjJavaPoet ecjMain ecjCodeGen ecjJmh ecjTest ecjJcstress \
    pmdJavaPoet pmdMain pmdCodeGen pmdJmh pmdTest pmdJcstress -Ppmd \
    spotbugsJavaPoet spotbugsMain spotbugsCodeGen spotbugsJmh spotbugsTest spotbugsJcstress -Pspotbugs \
    "$@"
