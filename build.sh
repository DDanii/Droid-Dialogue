#!/usr/bin/env bash
# Builds the APK with the plain Android SDK command line tools (no Gradle, no AndroidX).
#
# Needs: JDK 17+, an Android SDK (ANDROID_HOME) with any platform and build-tools installed, and zip.
#
# Optional environment:
#   VERSION_NAME, VERSION_CODE        shown in the app / used by Android to order updates
#   KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
#                                     sign with your own key. Without them the debug key in
#                                     ~/.android/debug.keystore is used (created if missing).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "Set ANDROID_HOME to your Android SDK folder." >&2
  exit 1
fi

PLATFORM=$(ls "$SDK/platforms" 2>/dev/null | grep -E '^android-[0-9]+$' | sort -t- -k2 -n | tail -n 1 || true)
BT=$(ls "$SDK/build-tools" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -n 1 || true)
if [ -z "$PLATFORM" ] || [ -z "$BT" ]; then
  echo "No Android platform or build-tools found in $SDK." >&2
  echo "Install them with: sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\"" >&2
  exit 1
fi
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
BT_DIR="$SDK/build-tools/$BT"
echo "Using $PLATFORM and build-tools $BT"

VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
OUT=build
APK="$OUT/wyoming-legacy-satellite-$VERSION_NAME.apk"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "Compiling..."
# Java 8 source level, no lambdas or newer library calls, so the result runs on Android 4.0.
# -parameters: since JDK 21 javac marks the hidden constructor parameters of anonymous classes with a
# nameless MethodParameters entry, and the d8 in build-tools 34 crashes on those with a NullPointerException
# ("Cannot invoke String.length() because <parameter1> is null"). With -parameters they all get names.
javac -parameters -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src -name '*.java')

echo "Dexing for API 14..."
"$BT_DIR/d8" --release --min-api 14 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "Packaging..."
"$BT_DIR/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 14 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
(cd "$OUT/dex" && zip -q -j ../base.apk classes.dex)
"$BT_DIR/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

if [ -n "${KEYSTORE_FILE:-}" ]; then
  KS="$KEYSTORE_FILE"
  KS_PASS="${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required with KEYSTORE_FILE}"
  KS_ALIAS="${KEY_ALIAS:?KEY_ALIAS is required with KEYSTORE_FILE}"
  KEY_PASS="${KEY_PASSWORD:-$KS_PASS}"
  echo "Signing with your keystore..."
else
  KS="$HOME/.android/debug.keystore"
  KS_PASS=android
  KS_ALIAS=androiddebugkey
  KEY_PASS=android
  if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" > /dev/null
  fi
  echo "Signing with the debug key (fine for installing, but updates only work between builds made with the same key)..."
fi
"$BT_DIR/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" --min-sdk-version 14 --out "$APK" "$OUT/aligned.apk"
"$BT_DIR/apksigner" verify "$APK"

echo
"$BT_DIR/aapt2" dump badging "$APK" | grep -E "^(package|sdkVersion|targetSdkVersion)" || true
ls -l "$APK"
