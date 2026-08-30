#!/usr/bin/env bash
# Copy the unique release jar matching <prefix> into ci-jars/<dest> for unzipped
# Actions uploads (upload-artifact v7 archive: false). Classifier-bearing
# siblings (sources/dev/javadoc/slim) are skipped so the glob cannot resolve
# to two files.
# Usage: ci_stage_jars.sh <prefix> <dir> <dest-filename>
set -euo pipefail
prefix=$1
dir=$2
dest=$3
mkdir -p ci-jars
matches=()
for f in "$dir"/"$prefix"*.jar; do
  [ -e "$f" ] || continue
  base=$(basename "$f")
  case "$base" in
    *-sources.jar|*-javadoc.jar|*-dev.jar|*-slim.jar) continue ;;
  esac
  matches+=("$f")
done
if [ "${#matches[@]}" -ne 1 ]; then
  echo "::error::expected exactly one ${prefix} jar in ${dir}, got ${#matches[@]}"
  ls -la "$dir" || true
  exit 1
fi
cp "${matches[0]}" "ci-jars/${dest}"
