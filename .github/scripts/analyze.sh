#!/bin/bash
set -eux

./gradlew \
    forbiddenApis -DforbiddenApis \
    pmdJavaPoet pmdMain pmdCodeGen pmdJmh -Dpmd \
    spotbugsJavaPoet spotbugsMain spotbugsCodeGen spotbugsJmh -Dspotbugs
