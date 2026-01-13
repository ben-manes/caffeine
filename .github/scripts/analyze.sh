#!/bin/bash
set -eux

./gradlew \
    ecj \
    assemble \
    pmd -Ppmd \
    spotbugs -Pspotbugs \
    forbiddenApis -PforbiddenApis \
    --warning-mode all --continue \
    --console colored --no-configuration-cache \
    -PjavaVersion=25 \
    "$@"
