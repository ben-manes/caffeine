#!/usr/bin/env bash
#
# Sparse, blobless clone of OpenJDK master plus the GA tags an audit compares against: Caffeine's
# floor (11), the LTS releases, and the newest GA. Prints master's commit and the tags fetched.
#
# usage: fetch-openjdk.sh <workspace-dir> [extra tags, e.g. jdk-28+19]
set -euo pipefail

ws=${1:?usage: fetch-openjdk.sh <workspace-dir> [extra tags]}
shift
dest="$ws/openjdk"
url=https://github.com/openjdk/jdk.git

if [ ! -d "$dest/.git" ]; then
  git clone --quiet --depth 1 --filter=blob:none --sparse "$url" "$dest"
  git -C "$dest" sparse-checkout set --cone \
    src/java.base/share/classes/java/util/concurrent \
    src/java.base/share/classes/java/lang/ref \
    src/java.base/share/classes/java/lang/invoke
fi

newest=$(git ls-remote --tags "$url" 'jdk-*-ga' | grep -v '\^{}$' \
  | sed -E 's#.*refs/tags/jdk-([0-9]+)-ga$#\1#' | sort -n | tail -1)
for tag in jdk-11-ga jdk-17-ga jdk-21-ga jdk-25-ga "jdk-$newest-ga" "$@"; do
  git -C "$dest" fetch --quiet --depth 1 --filter=blob:none origin tag "$tag"
done

git -C "$dest" log -1 --date=short --format='master %H %cd'
git -C "$dest" tag | sort -V | tr '\n' ' '
echo
