#!/bin/bash
set -eux

./gradlew --daemon \
    pmdJavaPoet pmdMain pmdCodeGen pmdJmh -Dpmd \
    spotbugsJavaPoet spotbugsMain spotbugsCodeGen spotbugsJmh -Dspotbugs
