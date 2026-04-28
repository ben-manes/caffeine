#!/bin/bash
set -eux

CI=true \
./gradlew \
    ecj \
    assemble \
    pmd -Ppmd \
    revapi -Prevapi \
    spotbugs -Pspotbugs \
    forbiddenApis -PforbiddenApis \
    --warning-mode all --continue \
    --console colored --no-configuration-cache \
    -PjavaVersion=26 \
    "$@"
