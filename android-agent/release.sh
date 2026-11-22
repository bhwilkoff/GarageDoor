#!/usr/bin/env bash
# Builds a signed release APK on the owner's Mac. The keystore and its password
# live in the macOS Keychain (RELEASING.md).
# CI builds the same thing from GitHub secrets on every version tag.
set -euo pipefail
cd "$(dirname "$0")"

# Credentials come from the login Keychain at use time (tools/secrets.sh keychain-import-android);
# the old keystore file and password item remain a fallback until the owner has migrated.
kc() { security find-generic-password -a garagedoor -s "garagedoor:$1" -w 2>/dev/null; }
if [ -z "${SIGNING_STORE_FILE:-}" ] && kc ANDROID_KEYSTORE_P12_PARTS >/dev/null; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  for ((i = 1; i <= $(kc ANDROID_KEYSTORE_P12_PARTS); i++)); do kc "ANDROID_KEYSTORE_P12_$i" | tr -d '\n'; done | base64 -d > "$tmp/release.p12"
  store="$tmp/release.p12"
else
  store="${SIGNING_STORE_FILE:-$HOME/.config/humanshaped/garage-agent/release.p12}"
fi
[ -f "$store" ] || { echo "error: no keystore in the Keychain or at $store (see RELEASING.md)" >&2; exit 1; }
SIGNING_STORE_PASSWORD="${SIGNING_STORE_PASSWORD:-$(kc ANDROID_KEYSTORE_PASSWORD || security find-generic-password -s humanshaped-garage-agent-signing -w)}"
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
