#!/bin/bash
set -euo pipefail

# Temporary files for checksums
CHECKSUM1=$(mktemp)
CHECKSUM2=$(mktemp)

# Ensure cleanup on exit
trap 'rm -f "$CHECKSUM1" "$CHECKSUM2"' EXIT

# Build function: cleans, assembles, computes checksums of JARs
function calculate_checksums() {
  local OUTPUT=$1

  ./gradlew \
    --configuration-cache \
    --no-build-cache \
    -Porg.gradle.java.installations.auto-download=false \
    -Dscan.tag.Reproducibility \
    clean assemble -x jmhJar

  # Find all JARs in build/libs (excluding javadoc), sort, and hash
  find . -type f -path '*/build/libs/*.jar' ! -name '*javadoc*.jar' -print0 \
    | sort -z \
    | xargs -0 sha512sum > "${OUTPUT}"
}

echo "Calculating first build checksums..."
calculate_checksums "$CHECKSUM1"

echo "Calculating second build checksums..."
calculate_checksums "$CHECKSUM2"

# Compare checksums
if ! diff "$CHECKSUM1" "$CHECKSUM2" >/dev/null; then
  echo "ERROR: Build is not reproducible!"
  echo "Differences between builds:"
  diff "$CHECKSUM1" "$CHECKSUM2"
  exit 1
fi

echo "✅ Build is reproducible!"
