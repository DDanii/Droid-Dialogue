#!/usr/bin/env bash
# Compiles the Android-free core and wake word code and runs the tests on the desktop JVM.
set -euo pipefail
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/droid-dialogue-test-classes"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -Xlint:-options -d "$OUT" \
  $(find src/org/droiddialogue/core src/org/droiddialogue/wake src/org/droiddialogue/dlna test -name '*.java')
java -cp "$OUT" org.droiddialogue.test.TestAll
