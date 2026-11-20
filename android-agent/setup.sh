#!/usr/bin/env bash
# Installs Garage Agent on the Android phone connected over adb and grants
# what it needs. With no argument it builds from source; with a path it
# installs that APK (for example one downloaded from GitHub Releases).
# Safe to run again.
#
#   ./setup.sh                  build from source and install
#   ./setup.sh garage-agent.apk install a downloaded release
set -euo pipefail
cd "$(dirname "$0")"

PKG=org.humanshaped.garageagent
MYQ=com.chamberlain.android.liftmaster.myq
SVC=$PKG/$PKG.AgentService

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found. Install Android SDK platform-tools."

SERIAL=${ANDROID_SERIAL:-}
if [ -z "$SERIAL" ]; then
  devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  count=$(printf '%s' "$devices" | grep -c . || true)
  [ "$count" = 1 ] || die "connect exactly one phone over adb, or set ANDROID_SERIAL (found $count)."
  SERIAL=$devices
fi
A() { adb -s "$SERIAL" "$@"; }

sdk=$(A shell getprop ro.build.version.sdk | tr -d '\r')
[ "$sdk" -ge 33 ] || die "Android 13 or newer is needed (this phone is API $sdk)."
A shell pm path "$MYQ" >/dev/null 2>&1 || die "install the myQ app on the phone and sign in first."

if [ $# -ge 1 ]; then
  apk=$1
  [ -f "$apk" ] || die "no such file: $apk"
  say "Installing $apk"
else
  say "Building the agent from source"
  studio_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -z "${JAVA_HOME:-}" ] && [ -d "$studio_jbr" ]; then export JAVA_HOME="$studio_jbr"; fi
  ./gradlew -q assembleDebug
  apk=app/build/outputs/apk/debug/app-debug.apk
fi
if ! out=$(A install -r "$apk" 2>&1); then
  case "$out" in
    *UPDATE_INCOMPATIBLE*|*signatures*)
      die "the installed Garage Agent was signed differently (a release vs. a source build). Uninstall it first; you will add it to Apple Home again afterwards." ;;
    *) die "install failed: $out" ;;
  esac
fi

say "Granting permissions and turning on the accessibility service"
A shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
A shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
A shell appops set "$PKG" RUN_ANY_IN_BACKGROUND allow
A shell dumpsys deviceidle whitelist "+$PKG" >/dev/null
A shell cmd notification allow_listener "$PKG/$PKG.MyqNotificationWatcher" 2>/dev/null || true
current=$(A shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in
  null|"") wanted=$SVC ;;
  *"$SVC"*) wanted=$current ;;
  *) wanted="$current:$SVC" ;;
esac
A shell settings put secure enabled_accessibility_services "$wanted"
A shell settings put secure accessibility_enabled 1
A shell settings put global adb_wifi_enabled 1
A shell am start -n "$PKG/.MainActivity" >/dev/null

say "Waiting for the agent to start (up to 1 minute)"
setup_code=""
for _ in $(seq 1 20); do
  setup_code=$(A logcat -d -s GarageAgent 2>/dev/null | grep -o 'setup code [0-9-]*' | tail -n 1 | cut -d' ' -f3 || true)
  [ -n "$setup_code" ] && break
  sleep 3
done
[ -n "$setup_code" ] || die "the agent did not start. Check: adb logcat -s GarageAgent"

cat <<EOF

Installed. One step left on the phone, then Apple Home:

1. In Garage Agent (now open on the phone), find step 3, "Pair the agent",
   and tap Pair. In Wireless debugging, tap "Pair device with pairing
   code" and type the code into the notification. This lets the agent
   restart its hidden myQ screen after a reboot without a computer.
2. In the Home app on your iPhone: + > Add Accessory > More options,
   pick "Garage Door", choose Add Anyway, and enter the setup code:

    $setup_code
EOF
