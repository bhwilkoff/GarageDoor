#!/bin/bash
# The only way an agent or the owner touches a secret value (.claude/skills/secrets-without-bookkeeping).
# Fixed recipes and fixed names only, so an exact permission rule can allow it. A value is never
# echoed, never put on a command line, and never written to a file: it moves from where it is kept
# (a file the owner already has, the login Keychain) through a pipe into the one place that uses it
# (the Keychain, a GitHub Actions secret). Names come from tools/app_config.py (GITHUB_REPO, SECRETS_NAME).
# GarageDoor has no server and no Worker, so there are no Worker recipes.
#
#   tools/secrets.sh list                         names only, from GitHub Actions and the login Keychain
#   tools/secrets.sh keychain-import-android [P12] [--delete]
#                                                 the Android release keystore into the Keychain
#                                                 (<name>:ANDROID_KEYSTORE_P12_1..n as base64) and its password
#                                                 copied from the old Keychain item
#                                                 (humanshaped-garage-agent-signing) to
#                                                 <name>:ANDROID_KEYSTORE_PASSWORD; --delete removes the
#                                                 file once the Keychain copy is checked byte for byte
#                                                 (the owner approves it). Default file:
#                                                 ~/.config/humanshaped/garage-agent/release.p12
#   tools/secrets.sh sync-ci                      set the GitHub secrets ANDROID_KEYSTORE_BASE64 and
#                                                 ANDROID_KEYSTORE_PASSWORD from the Keychain copies
#
# Anything else is refused before any command runs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cfg() { (cd "$ROOT/tools" && python3 -c "import app_config; print(app_config.$1)"); }
REPO="$(cfg GITHUB_REPO)"
KC_ACCOUNT="$(cfg SECRETS_NAME)"
KC_PREFIX="$KC_ACCOUNT:"
LEGACY_PW_SERVICE="humanshaped-garage-agent-signing"
DEFAULT_P12="$HOME/.config/humanshaped/garage-agent/release.p12"
[ -n "$REPO" ] || { echo "secrets.sh: set GITHUB_REPO in tools/app_config.py" >&2; exit 2; }

die() { echo "secrets.sh: $*" >&2; exit 2; }

# Prints a Keychain value to stdout: call it only inside a pipe or $(...), never bare.
kc_get() { security find-generic-password -a "$KC_ACCOUNT" -s "$KC_PREFIX$1" -w; }

# Stores stdin as a Keychain value through `security -i`, which reads the command
# on stdin, so the value is never on a command line (ps). One token, no spaces.
kc_put() {
  local v
  IFS= read -r v || true
  [[ "$v" =~ ^[A-Za-z0-9+/=_.-]+$ ]] || die "refusing to store $1: not a single token"
  printf 'add-generic-password -U -a %s -s %s -w %s\n' "$KC_ACCOUNT" "$KC_PREFIX$1" "$v" | security -i >/dev/null
}

b64() { base64 < "$1" | tr -d '\n'; }

# `security -i` takes a line of about 3000 characters at most, and a keystore is longer, so a big
# value is stored as NAME_1..NAME_n (2000 characters each) and NAME_PARTS (n).
kc_put_chunked() {
  local all n=0 part
  IFS= read -r all || true
  while IFS= read -r part || [ -n "$part" ]; do
    n=$((n + 1))
    printf '%s\n' "$part" | kc_put "$1_$n"
  done < <(printf '%s' "$all" | fold -w 2000)
  printf '%s\n' "$n" | kc_put "$1_PARTS"
}
kc_get_chunked() {
  local n i
  n="$(kc_get "$1_PARTS")"
  for ((i = 1; i <= n; i++)); do kc_get "$1_$i" | tr -d '\n'; done
}

recipe="${1:-}"
[ $# -gt 0 ] && shift

case "$recipe" in
  list)
    [ $# -eq 0 ] || die "list takes no arguments"
    echo "GitHub Actions ($REPO):"
    gh secret list --repo "$REPO" --json name --jq '.[].name' | sed 's/^/  /'
    echo "login Keychain ($KC_PREFIX*):"
    security dump-keychain 2>/dev/null | sed -n "s/.*\"svce\"<blob>=\"$KC_PREFIX\([A-Za-z0-9_]*\)\".*/  \1/p" | sort -u
    ;;

  keychain-import-android)
    delete=0 p12=""
    for a in "$@"; do
      case "$a" in
        --delete) delete=1 ;;
        *.p12) p12="$a" ;;
        *) die "not a .p12 file or --delete: an argument" ;;
      esac
    done
    [ -n "$p12" ] || p12="$DEFAULT_P12"
    [ -f "$p12" ] && [ ! -L "$p12" ] || die "not a regular file: $p12"
    b64 "$p12" | kc_put_chunked ANDROID_KEYSTORE_P12
    cmp -s <(kc_get_chunked ANDROID_KEYSTORE_P12) <(b64 "$p12") || die "the Keychain copy does not match the file; the file is kept"
    security find-generic-password -s "$LEGACY_PW_SERVICE" -w | kc_put ANDROID_KEYSTORE_PASSWORD
    echo "ANDROID_KEYSTORE_P12 (in parts) and ANDROID_KEYSTORE_PASSWORD are in the Keychain"
    if [ "$delete" = 1 ]; then rm -f -- "$p12"; echo "the .p12 file is deleted"; else echo "the .p12 file is kept (--delete removes it)"; fi
    ;;

  sync-ci)
    [ $# -eq 0 ] || die "sync-ci takes no arguments"
    kc_get_chunked ANDROID_KEYSTORE_P12 | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
    kc_get ANDROID_KEYSTORE_PASSWORD | tr -d '\n' | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
    echo "ANDROID_KEYSTORE_BASE64 and ANDROID_KEYSTORE_PASSWORD are set on GitHub from the Keychain"
    ;;

  *)
    die "unknown recipe; one of: list, keychain-import-android, sync-ci"
    ;;
esac
