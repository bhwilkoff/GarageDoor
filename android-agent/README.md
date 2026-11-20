# Garage Agent, for builders

If you just want the door on your lock screen, the
[front page](../README.md) is all you need. This page is for folks who
want to know how it works, build it themselves, or help make it better.

I wanted the whole thing to run on hardware I already owned, cost
nothing, and survive Chamberlain changing its mind about who may talk to
myQ. So, Garage Agent never touches myQ's servers directly. It uses the
official myQ app, exactly the way a person would, on a screen no person
can see.

```
iPhone lock screen ──> Home hub (Apple TV / HomePod) ──> Android phone
                                                          ├─ Garage Agent: a HomeKit accessory
                                                          └─ myQ app on a hidden screen ──> myQ cloud ──> door
```

## How it works

- **It is its own HomeKit accessory.** A HomeKit Accessory Protocol
  server, written from scratch in Kotlin with no dependencies: SRP-6a
  pairing, Ed25519, X25519, HKDF-SHA512, ChaCha20-Poly1305, encrypted
  sessions, events, and the timed writes Home uses for garage doors. It
  announces itself over Bonjour with Android's own NsdManager. The code
  is in `app/src/main/java/org/humanshaped/garageagent/hap/`.
- **It presses the real button.** An accessibility service reads the
  door's status in myQ and taps its button. A command only counts once
  myQ shows the change. When it can't trust what it sees, it tells Home
  "No Response" rather than guessing.
- **It keeps myQ on a hidden screen.** `DisplayHost` runs as Android's
  shell user and creates a private virtual display that is always
  unlocked and has its own focus. myQ lives there. The phone's real
  screen is never touched, which is why the phone stays usable for other
  things.
- **It pairs itself, no computer needed.** `AdbPair.kt` is the client
  half of Android's "Pair device with pairing code": TLS 1.3, then SPAKE2
  over edwards25519 the way BoringSSL does it, then AES-128-GCM. That
  lets the agent pair its own key from the code you type, grant its own
  permissions, and restart the hidden screen after a reboot.
- **It handles more than one door, by name.** Each door on myQ's home
  screen is a card with its name, status, and button side by side, and
  the agent reads all three from the same card. Doors are matched to Home
  by their myQ name, never their position. Each door is its own Garage
  Door Opener service on the one accessory: the first keeps identifiers
  10 to 15, door *n* uses *n*·100 + 10 to 15, and slots are never reused.
  Each service carries a ConfiguredName, because Home labels a
  multi-service accessory's doors by that, not by Name. Two doors with
  the same name, or a card it can't take apart cleanly, and it refuses to
  press anything.
- **It heals itself.** A sticky foreground service and a boot receiver
  keep it running. A watchdog re-enables the accessibility service when
  Android drops it, and the agent restarts `DisplayHost` through its own
  Wireless debugging connection when the hidden screen disappears
  (`AdbSelf.kt`).

The reasons behind each of those choices are in
[`../DECISIONS.md`](../DECISIONS.md), Decisions 004 through 007.

## Building it yourself

You will need Android Studio (or the Android SDK and JDK 17 or newer)
and a phone connected with adb, over USB or Wireless debugging. Then,
from this folder:

```
./setup.sh
```

That builds Garage Agent, installs it, grants its permissions, and opens
it on the phone. Finish with step 3 of the checklist (Pair the agent) on
the phone. `./setup.sh garage-agent-1.0.apk` installs a downloaded
release instead of building one.

One catch: a build from source and a release are signed with different
keys, so Android won't update one with the other. Switching means
uninstalling first and adding the door to Apple Home again.

## Testing

Everything is tested on a real phone, never an emulator. The on-device
tests check every piece of cryptography against its published test
vectors, and run a full HomeKit pairing over loopback:

```
./gradlew connectedDebugAndroidTest
```

Be ready for one side effect: this uninstalls the app when it finishes,
which erases the HomeKit pairing. Run it before you add the door to
Home, not after.

The repository also has a few checks for its own docs and settings (US
spelling, version numbers, agent instructions). From the top folder:

```
python3 tools/test_us_english.py
python3 tools/test_version_contract.py
python3 tools/test_agent_files.py
```

**Trying two doors without a second door.** A pretend door that opens
and closes in six seconds, touching nothing real. Only the shell can send
this (it requires the DUMP permission):

```
adb shell am broadcast -n org.humanshaped.garageagent/.TestDoorReceiver --ez on true
adb shell am broadcast -n org.humanshaped.garageagent/.TestDoorReceiver --ez on false
```

The two-door protocol test (a throwaway accessory on port 51901, in its
own process, so the live agent is untouched):

```
adb shell CLASSPATH=<app.apk>:<test.apk> app_process / org.humanshaped.garageagent.MultiDoorCli
```

## Releasing

Releases are signed APKs on GitHub, built automatically when a version
tag is pushed. The whole process, and where the signing key lives, is in
[RELEASING.md](RELEASING.md).

## Where this came from

This repository started as a copy of
[UniversalAppTemplate](https://github.com/bhwilkoff/UniversalAppTemplate),
the template I start my apps from. That is why you will find
an iOS shell, a web shell, and a folder of skills that Garage Agent
doesn't use yet. `AGENTS.md` is what any coding agent reads before it
touches the code, and `SCRATCHPAD.md` is where the work stands right now.

Found a bug, or got it working on a phone I haven't tried? Open an
[issue](https://github.com/bhwilkoff/GarageDoor/issues). I'd love to
hear about it.
