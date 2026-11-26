#!/usr/bin/env bash
# One-shot build for a fresh Linux workspace (GitHub Codespaces, a dev container, a cloud VM, WSL...).
# Installs whatever is missing (JDK, zip, the Android SDK command line tools, platform and build-tools),
# runs the desktop tests and builds the APK:
#
#     bash build-in-workspace.sh
#
# The result is build/wyoming-legacy-satellite-<version>.apk
#
# Optional environment:
#   ANDROID_HOME        where the SDK lives or should be installed (default: ~/android-sdk)
#   SKIP_TESTS=1        do not run ./test.sh before building
#   SETUP_ONLY=1        install the tools and stop, without building
#   VERSION_NAME, VERSION_CODE, KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
#                       passed on to build.sh (see there)
#   PLATFORM_API        Android platform to compile against (default 34)
#   BUILD_TOOLS         build-tools version (default 34.0.0)
#   CMDLINE_TOOLS_URL   override the command line tools download
set -euo pipefail
cd "$(dirname "$0")"

PLATFORM_API="${PLATFORM_API:-34}"
BUILD_TOOLS="${BUILD_TOOLS:-34.0.0}"
CMDLINE_TOOLS_URL="${CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

if [ "$(uname -s)" != "Linux" ]; then
  die "This script is for Linux. On macOS install a JDK and the Android SDK yourself, then run ./build.sh"
fi
case "$(uname -m)" in
  x86_64|amd64) ;;
  *) echo "WARNING: $(uname -m) machine. The Android build-tools (aapt2, zipalign) are x86-64 programs and may not run here." >&2 ;;
esac

# ---------------------------------------------------------------- system packages
SUDO=""
if [ "$(id -u)" -ne 0 ] && command -v sudo > /dev/null 2>&1; then
  SUDO="sudo"
fi

apt_install() {
  command -v apt-get > /dev/null 2>&1 || die "Missing: $*. Install them with your package manager and run this again."
  say "Installing $*"
  $SUDO apt-get update -qq
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
}

java_major() {
  command -v javac > /dev/null 2>&1 || { echo 0; return; }
  javac -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p' | head -n 1
}

PKGS=()
if [ "$(java_major)" -lt 17 ] 2>/dev/null; then PKGS+=(openjdk-17-jdk-headless); fi
command -v zip   > /dev/null 2>&1 || PKGS+=(zip)
command -v unzip > /dev/null 2>&1 || PKGS+=(unzip)
if ! command -v curl > /dev/null 2>&1 && ! command -v wget > /dev/null 2>&1; then PKGS+=(curl); fi
if [ "${#PKGS[@]}" -gt 0 ]; then
  apt_install "${PKGS[@]}"
fi
[ "$(java_major)" -ge 17 ] 2>/dev/null || die "A JDK 17 or newer is needed (found: $(javac -version 2>&1 || echo none))."
command -v keytool > /dev/null 2>&1 || die "keytool not found, install a full JDK (not just a JRE)."

# ---------------------------------------------------------------- Android SDK
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  say "Downloading the Android command line tools"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  if command -v curl > /dev/null 2>&1; then
    curl -fL --retry 3 -o "$TMP/tools.zip" "$CMDLINE_TOOLS_URL" || die "Download failed: $CMDLINE_TOOLS_URL"
  else
    wget -q -O "$TMP/tools.zip" "$CMDLINE_TOOLS_URL" || die "Download failed: $CMDLINE_TOOLS_URL"
  fi
  unzip -q "$TMP/tools.zip" -d "$TMP/x"
  [ -d "$TMP/x/cmdline-tools" ] || die "Unexpected layout in the command line tools download."
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TMP/x/cmdline-tools" "$SDK/cmdline-tools/latest"
  [ -x "$SDKMANAGER" ] || chmod +x "$SDK"/cmdline-tools/latest/bin/*
fi

if [ ! -f "$SDK/platforms/android-$PLATFORM_API/android.jar" ] || [ ! -d "$SDK/build-tools/$BUILD_TOOLS" ]; then
  say "Installing platform android-$PLATFORM_API and build-tools $BUILD_TOOLS"
  # sdkmanager asks for licence acceptance. 'yes' is killed by SIGPIPE when sdkmanager finishes,
  # so pipefail is switched off for these two lines only and the result is checked below instead.
  ( set +o pipefail; yes 2> /dev/null | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null ) || true
  ( set +o pipefail; yes 2> /dev/null | "$SDKMANAGER" --sdk_root="$SDK" "platforms;android-$PLATFORM_API" "build-tools;$BUILD_TOOLS" )
fi
[ -f "$SDK/platforms/android-$PLATFORM_API/android.jar" ] || die "android.jar is missing after the install, see the output above."
[ -d "$SDK/build-tools/$BUILD_TOOLS" ] || die "build-tools $BUILD_TOOLS is missing after the install, see the output above."

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
echo "Android SDK ready in $SDK"

if [ "${SETUP_ONLY:-0}" = "1" ]; then
  echo "SETUP_ONLY is set, stopping here."
  exit 0
fi

# ---------------------------------------------------------------- tests and build
if [ "${SKIP_TESTS:-0}" != "1" ]; then
  say "Running the desktop tests"
  bash ./test.sh
fi

say "Building the APK"
bash ./build.sh

APK="$(ls -1 build/wyoming-legacy-satellite-*.apk | head -n 1)"
say "Done: $APK"
echo "Install on a phone with USB debugging:   adb install -r \"$APK\""
echo "Or download it from this workspace and open it on the phone (allow 'Unknown sources')."
