#!/usr/bin/env python3
"""tools/secrets.sh refuses anything but its fixed recipes and never prints a value or puts one
on a command line; android-agent/release.sh reads the Keychain at use time and leaves no keystore
behind (.claude/skills/secrets-without-bookkeeping).

Every recipe runs against a fake store: gh and security are stand-ins on PATH that keep values in a
temp directory and log their own command lines. Planted values must reach the right store and
appear in no output and no command line. Negative controls: copies of the scripts that echo a
value, pass one as an argument, or leave the keystore on disk must be caught.
Run: python3 tools/test_secrets_sh.py
"""
import base64
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "secrets.sh")
RELEASE = os.path.join(os.path.dirname(HERE), "android-agent", "release.sh")

PW = "PLANTEDkeystorepassword0123456789abcdef"
P12 = bytes(range(256)) * 17 + b"PLANTEDp12bytes"
P12_B64 = base64.b64encode(P12).decode()

FAKES = {
    "gh": r'''
import os, sys
S = os.environ["FAKE_STORE"]; a = sys.argv[1:]
open(os.path.join(S, "argv.log"), "a").write("gh " + " ".join(a) + "\n")
if a[:2] == ["secret", "set"]:
    os.makedirs(os.path.join(S, "gh"), exist_ok=True)
    open(os.path.join(S, "gh", a[2]), "w").write(sys.stdin.read())
elif a[:2] == ["secret", "list"]:
    d = os.path.join(S, "gh")
    for n in sorted(os.listdir(d)) if os.path.isdir(d) else ["ANDROID_KEYSTORE_BASE64"]:
        print(n)
else:
    sys.exit("fake gh: " + " ".join(a))
''',
    "security": r'''
import os, shlex, sys
S = os.environ["FAKE_STORE"]; a = sys.argv[1:]
open(os.path.join(S, "argv.log"), "a").write("security " + " ".join(a) + "\n")
d = os.path.join(S, "kc"); os.makedirs(d, exist_ok=True)
def opt(args, k): return args[args.index(k) + 1]
if a and a[0] == "find-generic-password":
    p = os.path.join(d, opt(a, "-s"))
    if not os.path.exists(p): sys.exit(44)
    sys.stdout.write(open(p).read() + "\n")
elif a == ["-i"]:
    for line in sys.stdin:
        w = shlex.split(line)
        if len(line) > 3000:
            print("security: unknown command"); continue
        if w and w[0] == "add-generic-password":
            open(os.path.join(d, opt(w, "-s")), "w").write(opt(w, "-w") + ("X" if os.environ.get("FAKE_KC_CORRUPT") else ""))
            print("security> ")
elif a and a[0] == "dump-keychain":
    print('    "svce"<blob>="someone-else:THING"')
    for n in sorted(os.listdir(d)):
        print('    "svce"<blob>="%s"' % n)
else:
    sys.exit("fake security: " + " ".join(a))
''',
}

FAKE_GRADLEW = r'''#!/bin/bash
# Records what the build would sign with, then makes the APK release.sh expects.
echo "$SIGNING_STORE_PASSWORD" > "$FAKE_STORE/gradle_password"
cp "$SIGNING_STORE_FILE" "$FAKE_STORE/gradle_keystore"
echo "$SIGNING_STORE_FILE" > "$FAKE_STORE/gradle_keystore_path"
mkdir -p app/build/outputs/apk/release && echo apk > app/build/outputs/apk/release/app-release.apk
'''


def make_world(script_text, release_text):
    w = tempfile.mkdtemp(prefix="secrets-test-")
    for d in ("root/tools", "root/android-agent", "bin", "store", "tmp", "home/.config/humanshaped/garage-agent", "sdk/build-tools/1"):
        os.makedirs(os.path.join(w, d))
    def put(rel, text, mode=0o755):
        p = os.path.join(w, rel)
        open(p, "w").write(text)
        os.chmod(p, mode)
    put("root/tools/secrets.sh", script_text)
    put("root/android-agent/release.sh", release_text)
    put("root/android-agent/gradlew", FAKE_GRADLEW)
    put("root/AppVersion.xcconfig", "MARKETING_VERSION = 9.9\n", 0o644)
    put("sdk/build-tools/1/apksigner", "#!/bin/bash\necho 'SHA-256 digest: x'\n")
    put("root/tools/app_config.py", 'GITHUB_REPO = "owner/Repo"\nSECRETS_NAME = "garagedoor"\n', 0o644)
    for name, body in FAKES.items():
        put("bin/" + name, "#!" + sys.executable + "\n" + body)
    return w


def run(w, script, *args, extra=None):
    env = {**os.environ, "PATH": os.path.join(w, "bin") + os.pathsep + os.environ["PATH"],
           "FAKE_STORE": os.path.join(w, "store"), "HOME": os.path.join(w, "home"), "TMPDIR": os.path.join(w, "tmp"),
           "ANDROID_HOME": os.path.join(w, "sdk"), "JAVA_HOME": "/x", **(extra or {})}
    for k in ("SIGNING_STORE_FILE", "SIGNING_STORE_PASSWORD"):
        env.pop(k, None)
    r = subprocess.run(["bash", os.path.join(w, "root", script), *args], env=env, capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout + r.stderr


def read(w, *p, mode="r"):
    path = os.path.join(w, "store", *p)
    return open(path, mode).read() if os.path.exists(path) else None


def suite(script_text, release_text):
    """The failures found running every recipe against the fake store."""
    fails = []
    w = make_world(script_text, release_text)
    try:
        planted = [PW, P12_B64, "PLANTEDp12bytes"]

        def clean(name, out):
            for v in planted:
                if v in out:
                    fails.append(f"{name}: printed a value")

        S = "tools/secrets.sh"
        for args in [[], ["exec", "ls"], ["list", "x"], ["sync-ci", "x"], ["keychain-import-android", "not-a-p12"],
                     ["keychain-import-android", "/no/such/file.p12"], ["keychain-import-android", "x.p12;id"],
                     ["gen", "SESSION_SECRET"]]:
            code, out = run(w, S, *args)
            if code != 2:
                fails.append(f"refuse {args}: exit {code}")
            if read(w, "argv.log"):
                fails.append(f"refuse {args}: ran a command")
                os.remove(os.path.join(w, "store", "argv.log"))

        # The legacy password item the owner already has.
        os.makedirs(os.path.join(w, "store", "kc"))
        open(os.path.join(w, "store", "kc", "humanshaped-garage-agent-signing"), "w").write(PW)
        p12 = os.path.join(w, "home", ".config", "humanshaped", "garage-agent", "release.p12")
        open(p12, "wb").write(P12)

        code, out = run(w, S, "keychain-import-android")
        clean("keychain-import-android", out)
        if code != 0 or "".join(read(w, "kc", f"garagedoor:ANDROID_KEYSTORE_P12_{i}") or "" for i in range(1, 9)) != P12_B64 \
                or read(w, "kc", "garagedoor:ANDROID_KEYSTORE_PASSWORD") != PW:
            fails.append(f"keychain-import-android: exit {code} or a Keychain value is wrong")
        if not os.path.exists(p12):
            fails.append("keychain-import-android: deleted the .p12 without --delete")

        code, out = run(w, S, "sync-ci")
        clean("sync-ci", out)
        if code != 0 or read(w, "gh", "ANDROID_KEYSTORE_BASE64") != P12_B64 or read(w, "gh", "ANDROID_KEYSTORE_PASSWORD") != PW:
            fails.append(f"sync-ci: exit {code} or a GitHub secret is wrong")

        code, out = run(w, S, "list")
        clean("list", out)
        if code != 0 or "ANDROID_KEYSTORE_P12_PARTS" not in out or "ANDROID_KEYSTORE_PASSWORD" not in out or "someone-else" in out:
            fails.append(f"list: exit {code} or the names are wrong")

        # release.sh signs from the Keychain, and leaves no keystore file behind.
        os.remove(p12)
        code, out = run(w, "android-agent/release.sh")
        clean("release.sh", out)
        if read(w, "gradle_password") != PW + "\n" or read(w, "gradle_keystore", mode="rb") != P12:
            fails.append(f"release.sh: the build did not get the Keychain keystore and password (exit {code})")
        if os.path.exists((read(w, "gradle_keystore_path") or "/").strip()):
            fails.append("release.sh: left the decoded keystore on disk")
        if os.listdir(os.path.join(w, "tmp")):
            fails.append("release.sh: left a temp directory behind")

        # --delete only after the Keychain copy matches.
        open(p12, "wb").write(P12)
        code, out = run(w, S, "keychain-import-android", p12, "--delete")
        clean("keychain-import-android --delete", out)
        if code != 0 or os.path.exists(p12):
            fails.append(f"keychain-import-android --delete: exit {code}, file {'kept' if os.path.exists(p12) else 'gone'}")

        # A Keychain copy that does not match keeps the file, even with --delete.
        open(p12, "wb").write(P12)
        code, out = run(w, S, "keychain-import-android", p12, "--delete", extra={"FAKE_KC_CORRUPT": "1"})
        if code != 2 or not os.path.exists(p12):
            fails.append(f"keychain-import-android: a mismatched Keychain copy did not keep the file (exit {code})")
        # Only a .p12 is taken: an existing file of another kind is refused.
        other = os.path.join(w, "home", "notes.txt")
        open(other, "w").write("x")
        code, out = run(w, S, "keychain-import-android", other, "--delete")
        if code != 2 or not os.path.exists(other):
            fails.append(f"keychain-import-android: took a file that is not a .p12 (exit {code})")

        argv = read(w, "argv.log") or ""
        for v in planted:
            if v in argv:
                fails.append("a value was on a command line")
                break
    finally:
        shutil.rmtree(w, ignore_errors=True)
    return fails


def main():
    src, rel = open(SCRIPT).read(), open(RELEASE).read()
    ran = failed = 0

    def check(name, ok):
        nonlocal ran, failed
        ran += 1
        if not ok:
            failed += 1
            print("FAIL", name)

    fails = suite(src, rel)
    for f in fails:
        print("  ", f)
    check("secrets.sh and release.sh: fixed recipes, no value printed or on a command line, no keystore left", fails == [])

    sh_controls = {
        "echoes the password": ("security find-generic-password -s \"$LEGACY_PW_SERVICE\" -w | kc_put ANDROID_KEYSTORE_PASSWORD",
                                "security find-generic-password -s \"$LEGACY_PW_SERVICE\" -w | tee /dev/stderr | kc_put ANDROID_KEYSTORE_PASSWORD"),
        "passes the password as an argument": ("kc_get ANDROID_KEYSTORE_PASSWORD | tr -d '\\n' | gh secret set ANDROID_KEYSTORE_PASSWORD --repo \"$REPO\"",
                                               "gh secret set ANDROID_KEYSTORE_PASSWORD --repo \"$REPO\" --body \"$(kc_get ANDROID_KEYSTORE_PASSWORD)\" </dev/null"),
        "deletes the file without checking": ("|| die \"the Keychain copy does not match the file; the file is kept\"", "|| true"),
        "stores the keystore in one piece": ("kc_put_chunked ANDROID_KEYSTORE_P12\n", "kc_put ANDROID_KEYSTORE_P12_1\n"),
        "accepts any argument": ("*) die \"not a .p12 file or --delete: an argument\" ;;", "*) p12=\"$a\" ;;"),
    }
    for name, (a, b) in sh_controls.items():
        if src.count(a) != 1:
            check(f"control '{name}' still matches secrets.sh", False)
            continue
        check(f"CONTROL: a secrets.sh that {name} is caught", suite(src.replace(a, b), rel) != [])

    rel_controls = {
        "keeps the decoded keystore": ("trap 'rm -rf \"$tmp\"' EXIT\n", ""),
        "echoes the password": ("export SIGNING_STORE_FILE=", "echo \"$SIGNING_STORE_PASSWORD\"\nexport SIGNING_STORE_FILE="),
    }
    for name, (a, b) in rel_controls.items():
        if rel.count(a) != 1:
            check(f"control '{name}' still matches release.sh", False)
            continue
        check(f"CONTROL: a release.sh that {name} is caught", suite(src, rel.replace(a, b)) != [])

    print(f"{ran - failed}/{ran} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
