#!/bin/bash
set -eux

CI=true \
./gradlew \
    ecj \
    roseau \
    assemble \
    pmd -Ppmd \
    spotbugs -Pspotbugs \
    forbiddenApis -PforbiddenApis \
    --warning-mode all --continue \
    --console colored --no-configuration-cache \
    -PjavaVersion=26 \
    "$@"
