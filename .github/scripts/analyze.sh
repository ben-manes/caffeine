#!/bin/bash
set -eux

./gradlew \
    ecj \
    assemble \
    pmd -Ppmd \
    spotbugs -Pspotbugs \
    forbiddenApis -PforbiddenApis \
    --console colored --no-configuration-cache --continue \
    "$@"
