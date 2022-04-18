#!/bin/bash
set -eux

./gradlew --daemon \
    pmdJavaPoet pmdMain -Dpmd \
    spotbugsJavaPoet spotbugsMain spotbugsJmh -Dspotbugs
