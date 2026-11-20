#!/usr/bin/env bash
# Builds a signed release APK on the owner's Mac. The keystore lives outside
# the repository and its password in the macOS Keychain (RELEASING.md).
# CI builds the same thing from GitHub secrets on every version tag.
set -euo pipefail
cd "$(dirname "$0")"

store="${SIGNING_STORE_FILE:-$HOME/.config/humanshaped/garage-agent/release.p12}"
[ -f "$store" ] || { echo "error: no keystore at $store (see RELEASING.md)" >&2; exit 1; }
SIGNING_STORE_PASSWORD="${SIGNING_STORE_PASSWORD:-$(security find-generic-password -s humanshaped-garage-agent-signing -w)}"
export SIGNING_STORE_FILE="$store" SIGNING_STORE_PASSWORD

studio_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -z "${JAVA_HOME:-}" ] && [ -d "$studio_jbr" ]; then export JAVA_HOME="$studio_jbr"; fi
./gradlew -q assembleRelease

version=$(sed -n 's/^MARKETING_VERSION *= *//p' ../AppVersion.xcconfig)
out="build/release/garage-agent-$version.apk"
mkdir -p build/release
cp app/build/outputs/apk/release/app-release.apk "$out"
sdk=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
apksigner=$(ls -d "${ANDROID_HOME:-$sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)
"$apksigner" verify --print-certs "$out" | grep -E "SHA-256|DN"
(cd build/release && shasum -a 256 "$(basename "$out")" > "$(basename "$out").sha256")
echo "Built $out"
