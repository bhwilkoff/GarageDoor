# GarageDoor: Scratchpad

## Current state

- **Status**: working from the owner's lock screen (first real open
  2026-11-19 16:20, via the hub, myQ on the hidden display).
- **Active milestone**: M2, survive reboots without a computer.
- **Last session**: 2026-11-20 (see log).
- **Next actions**:
  1. Owner adds the second door in myQ; confirm it appears in Home under
     its myQ name and moves only itself.
  2. Watch for Google's email confirming the package registration.
- **Open questions**:
  1. Family members: Apple Home sharing, when wanted.

---

## Out of scope

- Android, macOS, tvOS, Windows, smart-TV builds: not carried in this
  copy. Pull them from UniversalAppTemplate if ever wanted.
- App Store distribution: personal install first. If TestFlight or the
  store is wanted later, pull `cloud-appstore-submission` and
  `apple-app-store-cli-submission` from the template.

---

## Session log

### 2026-11-19: hidden display, timed writes, first lock-screen open
- **Found**: owner paired the iPhone; lock-screen tap did nothing; owner
  rejected the 15-minute screen grabs.
- **Did**: HAP request logging showed the hub's `PUT /prepare` getting
  404; added timed writes and the door opened from the lock screen.
  Moved myQ to a hidden virtual display (DisplayHost); removed all
  real-screen checks; repeat commands acknowledged; wrote AdbSelf for
  self-restart. Pairing its key was stopped by the safety check: owner's
  call.
- **Left**: DisplayHost running (started from the Mac), door OPEN at
  16:22 from the owner's test. Not committed.

### 2026-11-20: the agent restarts its own hidden display
- **Found**: agent key unpaired (the first pairing went to the Mac key).
- **Did**: paired the agent key via `HOME=`; first self-restarts died
  with an empty log (agent hung up before the JVM started); the start
  command now waits for `DISPLAY`. Killed DisplayHost: the agent restored
  it in 3 s and read the door. Deleted the Mac copies of the key.
- **Then**: reboot test passed (working 53 s after unlock); owner
  opened and closed from the Home control. Wrote `android-agent/setup.sh`
  (rerun on the Pixel: pass) and `android-agent/README.md` for others.
- **Then**: on-device pairing (AdbPair: SPAKE2 matched first try),
  setup checklist with fixes and Share report, icon; package renamed to
  `org.humanshaped.garageagent`; release key (Keychain), CI workflow,
  GitHub secrets set, local signed release verified (Decision 007).
- **Then**: v1.0 released by CI (signed, verified); repo made public;
  package registered in Play Console (in review); key backed up to the
  login Keychain and iCloud Drive; Pixel moved to the v1.0 release.
- **Then**: owner re-added the door; v1.1 shipped (update notices,
  calmer screen, QR download). Installing 1.1 over 1.0 kept the Home
  pairing, permissions, and the agent's adb key (verified on the Pixel).
- **Then**: multi-door support (Decision 008), verified with a pretend
  second door from the owner's iPhone; ConfiguredName fixed identical
  labels in Home. 1.2 on the Pixel, test door off.
- **Left**: Pixel on 1.2, one real door, closed.
