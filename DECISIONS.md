# [APP NAME]: Architecture & Technology Decisions

Entries capture the *why* behind choices, not the *what* (the code
already shows that). Each entry answers: **"what would the next
developer get wrong if they didn't know this?"** Lead with the rule,
follow with `**Why:**` and `**How to apply:**`. Append-only.

Invoke the `architectural-decision-log` skill when adding a new entry.
The full set of template decisions this copy inherits from lives in
UniversalAppTemplate's DECISIONS.md.

---

## 001: A lightweight copy of the template, iOS and web only

*Date: 2026-11-19*

This repository carries only the skills, tools, and docs a lock-screen
garage door control needs: web and iOS shells, the method skills, App
Intents, WidgetKit, ActivityKit, HomeKit, auth and Keychain, networking,
and revocation resilience. Android, macOS, tvOS, Windows, TV, store
submission, CI fleet, and course material were left out on purpose.

**Why**: a small personal utility gets lost in a 150-skill template,
and every unrelated skill is noise an agent has to read past.

**How to apply**: when a need arrives that this copy does not cover,
copy the one skill (or tool) from UniversalAppTemplate and add its row
to the AGENTS.md table. Don't re-add a platform without a decision here.

---

## 002: Every route to the door is presumed revocable

*Date: 2026-11-19 (carried from template Decision 051)*

Whichever service reaches the door (myQ's cloud, IFTTT, a local board),
keep it behind one client seam so it can be swapped without touching
the control, widget, or intents, and design the fallback before the
route is load-bearing.

**Why**: Chamberlain shut out unofficial myQ clients in late 2023, and
apps built on that API stopped working overnight. A garage control that
silently fails is worse than none: someone thinks the door closed.

**How to apply**: `third-party-revocation-resilience`. The intents and
widgets call a `DoorService` protocol in `apple/Core`; each route is one
implementation. A failed command surfaces as an error on the control
and in the app, never as a success.

---

## 003: Debug/state env hooks ship in the app, as production no-ops

*Date: 2026-11-19 (carried from template Decision 018)*

The app honors launch hooks (`APP_START_TAB`, `APP_DIAG=1`) that drive
it to a known screen or turn on diagnostics. All are no-ops in Release.

**Why**: screenshots and debugging both need a known state without a
code change.

**How to apply**: `apple/Core/Store/LaunchDoors.swift` on iOS, query
params on web. A door-moving action is never a launch hook.

---

<!-- Add new entries below this line. Lead with the rule. Number
     sequentially. -->

## 004: Reach the door by driving the official myQ app on a home Pixel

*Date: 2026-11-19*

The door is reached through the genuine myQ Android app, signed in on
the owner's always-on Pixel 8a, operated by our own agent app on the
same phone (an accessibility service reads the state text and taps the
door button; myQ's own notifications add state events). The agent
publishes itself to Apple Home as a garage door, so iOS's lock-screen
control, Siri, widgets, and remote access via the HomePods do the rest.

**Why**: every unofficial myQ client that broke was imitating the app
(spoofed identity, extracted App Check tokens), which Chamberlain can
revoke at will; the real app on a real phone is the one client they
cannot block without blocking customers. The owner ruled out buying
hardware (ratgdo) or paying for services: "We are not going to buy any
new hardware or pay for any services." The Pixel and the HomePods were
already in the house.

**How to apply**: the read-only spike (2026-11-19, myQ 5.324.0) found
stable ids: `device_name`, `device_status` ("Closed for 23 hours"), and
the button as content-desc "device state icon". Match on those, never
on screen coordinates. Turn off auto-update for myQ on the Pixel and
re-run the selector check before accepting a new version. The Pixel is
also used for app testing, so the agent brings myQ forward only for a
command or status check and then returns to the previous app. Any
unreadable screen (signed out, update prompt, unknown layout) reports
the door as unknown, never closed. Fallback channel if Apple Home fails:
a free Cloudflare Worker relay plus our own ControlWidget, behind the
same agent. The agent's Android project lives in `android-agent/`;
this is a home appliance, not an Android port of the app.

## 005: The Pixel agent is its own HomeKit accessory, written from scratch

*Date: 2026-11-19*

`android-agent/` is a dependency-free Android app that implements HAP
over IP itself (SRP-6a, Ed25519, X25519, HKDF-SHA512, ChaCha20-Poly1305,
TLV8, encrypted sessions, events, pairing management) and advertises
`_hap._tcp` through Android's own NsdManager. Apple Home pairs with it
directly; the HomePods give remote access and the lock-screen control.

**Why**: no always-on computer is needed, nothing to pay for, no
third-party code on the device that holds the myQ session, and Home's
secure-accessory rules (Face ID before opening) come free. Homebridge
on the Pixel (Termux + Node) was two processes and a multicast lock to
babysit; one APK is one thing to keep alive.

**How to apply**:
- Every crypto primitive has a published vector test, and a loopback
  controller pairs, verifies, writes, and gets events; all run on the
  Pixel (`./gradlew connectedDebugAndroidTest`). Never on a Mac
  simulator or emulator (owner rule).
- `connectedDebugAndroidTest` uninstalls the app afterwards, which wipes
  the pairing and setup code. Once the owner's iPhone is paired, run
  tests with `adb shell am instrument` against an installed test APK,
  or not at all.
- Real-door tests go through `RealDoorCli` under `app_process` (its own
  process; `am instrument` would force-stop the agent, and Android
  leaves a force-stopped accessibility service unbound). It refuses to
  pair while the agent is already paired.
- Uptime: a sticky foreground service restarts the process after a
  crash; a boot receiver starts it after reboot; a watchdog re-enables
  the accessibility service when it is enabled but unbound (needs the
  one-time `pm grant ... WRITE_SECURE_SETTINGS`). A force-stop removes
  the service from the enabled list on this Android; once the service
  has run, the agent re-adds it anyway (`a11yWanted`), because an
  accidental force-stop must not silently kill the door. Uninstall to
  turn it off for good. Measured 2026-11-19: back 2 s after `am crash`,
  about 45 s after `am force-stop` plus any start of the app.
- A stale (over 30 min) or faulted reading is reported to Home as
  "No Response" (HAP -70402), never as closed. A command answers 204
  only after myQ shows it accepted the tap.
- While a command runs, the HomeKit target stays what was asked for;
  myQ's status text lags the tap by about a second and would otherwise
  flicker the tile.

## 006: myQ lives on a hidden display; the real screen is never used

*Date: 2026-11-19*

`DisplayHost` runs as the shell user (app_process) and creates a virtual
display flagged trusted, always-unlocked, own-focus, and
steal-top-focus-disabled. myQ runs there permanently. The agent reads it
through `getWindowsOnAllDisplays` and taps with gestures bound to that
display id. Nothing appears on, or takes focus from, the Pixel's screen.

**Why**: the owner uses the Pixel for app testing: "There absolutely no
way that I can use the pixel for testing if you are grabbing the screen
every 15 minutes." myQ's manifest has no screenless entry (no widget,
Device Controls, tile, car service, or exported receiver; checked
5.324.0), so myQ must run somewhere; a display nobody sees is the
answer. Side benefits: a locked Pixel no longer matters, and state is
read live (once a minute, plus myQ's own notifications) instead of by
polling the real screen.

**How to apply**:
- Checks never take the real screen. If the hidden display is down, the
  state goes stale and Home shows No Response; only an explicit command
  falls back to the real screen.
- Apple Home sends garage door commands as timed writes (`PUT /prepare`,
  then a write carrying `pid`), even without the `tw` permission. Without
  /prepare support the hub drops the connection and the tap does nothing
  (the first failed lock-screen attempt, 2026-11-19).
- A repeat of a command already running is acknowledged at once, not
  queued (iOS sent OPEN twice a second apart).
- DisplayHost restarts without a computer through the agent's own paired
  adb key (`AdbSelf`, loopback adb over TLS; owner approved permanent
  shell access, paired 2026-11-20). The key lives only in the agent's
  vault. Two traps: desktop adb 1.0.41 ignores `ANDROID_USER_HOME`, so
  pair a separate key with `HOME=<dir> adb pair`, never the default; and
  the start command must wait until DisplayHost prints `DISPLAY` before
  the agent hangs up, or the child dies during startup.
- Never run `uiautomator` on the Pixel while the agent is live: it
  unbinds accessibility services while it runs.

## 007: Distribute on GitHub Releases under org.humanshaped.garageagent, not the Play Store

*Date: 2026-11-20*

Garage Agent ships as a signed APK on GitHub Releases, built by
`.github/workflows/android-release.yml` from a version tag. Its package
is `org.humanshaped.garageagent`, so it sits with the other apps built
with the Human Shaped method. Setup needs no computer: the agent pairs
its own key with wireless debugging from a code the owner types
(`AdbPair.kt`), then grants its own permissions.

**Why**: Play's accessibility policy excludes automation tools, and a
policy strike there can end the owner's whole developer account, which
carries other apps. GitHub costs nothing and the app is free to install.
Android developer verification (2027) needs only this package and key
registered under the owner's already verified identity.

**How to apply**:
- One release key, forever (RELEASING.md). Losing it strands every user
  on their current version; back it up with its password.
- The package name and key are part of every user's Apple Home pairing:
  changing either means reinstalling and re-adding the door.
- The workflow refuses to publish an APK not signed with the release key
  (certificate SHA-256 pinned in the workflow).
- The setup screen is the help desk: each check says what it is, why it
  matters, and offers the one fix; Share report is what an issue asks for.
