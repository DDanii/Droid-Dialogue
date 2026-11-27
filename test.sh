#!/usr/bin/env bash
# Compiles the Android-free core and wake word code and runs the tests on the desktop JVM.
set -euo pipefail
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/wyoming-legacy-test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -Xlint:-options -d "$OUT" \
  $(find src/org/wyominglegacy/core src/org/wyominglegacy/wake src/org/wyominglegacy/dlna test -name '*.java')
java -cp "$OUT" org.wyominglegacy.test.TestAll
