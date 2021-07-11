#!/bin/bash
set -eux

./gradlew pmdJavaPoet pmdMain -Dpmd
./gradlew spotbugsJavaPoet spotbugsMain spotbugsJmh -Dspotbugs
