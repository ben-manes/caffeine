#!/bin/bash
set -eux

./gradlew --console colored --continue \
    ecj \
    assemble \
    pmd -Ppmd \
    spotbugs -Pspotbugs \
    forbiddenApis -PforbiddenApis \
    "$@"
