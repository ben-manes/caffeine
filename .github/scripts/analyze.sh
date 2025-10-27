#!/bin/bash
set -eux

./gradlew --console colored \
    ecj \
    assemble \
    pmd -Ppmd \
    spotbugs -Pspotbugs \
    forbiddenApis -PforbiddenApis \
    "$@"
