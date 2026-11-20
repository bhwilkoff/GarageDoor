# Garage Agent: a myQ garage door in Apple Home, from a spare Android phone

Open and close a myQ (Chamberlain / LiftMaster) garage door from the
iPhone lock screen, Control Center, Siri, or the Home app. No myQ API, no
Homebridge, no IFTTT, no subscription, no new hardware, and no computer
needed to set it up.

An Android phone you already own runs the official myQ app plus this
agent. The agent is its own HomeKit accessory, so Apple Home talks to it
directly. When you tap the control, the agent presses the button in the
myQ app on a hidden screen, then reports the door's real state back to
Home. The phone's own screen is never touched, so the phone stays usable
for other things.

```
iPhone lock screen ──> Home hub (Apple TV / HomePod) ──> Android phone
                                                          ├─ agent: HomeKit accessory
                                                          └─ myQ app on a hidden screen ──> myQ cloud ──> door
```

Because it drives the official app instead of myQ's private API, it does
not break when Chamberlain blocks unofficial clients (as it did in late
2023). It can break if a myQ app update changes its screens. Turn off
myQ's auto-update and you decide when that happens.

Garage Agent is one of the apps built with the
[Human Shaped](https://humanshaped.org) method.

## What you need

- **An Android phone, Android 13 or newer**, that can stay plugged in
  and on your home Wi-Fi. Tested on a Pixel 8a (Android 17 beta). Other
  phones should work but are untested.
- **The myQ app** on that phone, signed in, with the door on its home
  screen. One door per account is supported; the agent uses the first.
  The myQ app must be in English.
- **An Apple Home hub** (Apple TV or HomePod) to use the control away
  from home and from the lock screen.

## Install

1. **On the Android phone**, open the
   [latest release](https://github.com/bhwilkoff/GarageDoor/releases/latest) and download
   `garage-agent-<version>.apk`. Open the download. Android asks you to
   allow installs from your browser the first time; allow it, then
   install.
2. Open **Garage Agent**. It is a checklist: each step says what it is,
   why it matters, and has one button that does it. Work down the list:
   1. **myQ app**: install it and sign in. In the Play Store, open myQ,
      tap ⋮, and uncheck **Enable auto update**.
   2. **Wireless debugging**: the app takes you to the setting. If
      Developer options are hidden, it tells you how to show them (tap
      Build number seven times).
   3. **Pair the agent**: tap Pair, then in Wireless debugging tap
      **Pair device with pairing code**. A notification pops up asking for
      the six-digit code; type it there. (If Do Not Disturb is on, the
      notification will not pop up: swipe down from the top of the screen
      to find it.) Garage Agent comes back on its own when it is done.
   4. **Permissions** and 5. **Private myQ screen** finish on their own
      within a minute.
   6. **Door reading** shows the door's state.
3. **On the iPhone**, open **Home**, tap **+ > Add Accessory > More
   options**, choose **Garage Door**, tap **Add Anyway** (it is not
   MFi-certified), and enter the setup code shown at the top of Garage
   Agent.
4. Add the control: **Control Center** (swipe down, long-press, Add a
   Control, Home) or **Lock Screen** (long-press the lock screen,
   Customize, Lock Screen, tap a control slot, Home). Pick the garage
   door. Home asks for Face ID before opening a garage door from the lock
   screen.

When every step says **Done**, the line at the top of Garage Agent reads
"Everything is working" and you can close the app.

### Updates

Garage Agent does not update itself. Either check the
[releases page](https://github.com/bhwilkoff/GarageDoor/releases) now and then, or install
[Obtainium](https://github.com/ImranR98/Obtainium) and add this
repository's URL to it: Obtainium watches GitHub releases and offers each
new version.

### With a computer instead

If you would rather build from source, connect the phone with adb (USB
debugging or Wireless debugging) and run `./setup.sh` from this folder. It
builds the agent, installs it, and grants its permissions; then do step 3
of the checklist (Pair the agent) on the phone. `./setup.sh <file.apk>`
installs a downloaded release instead of building. A source build and a
release are signed differently, so switching between them means
uninstalling first and adding the door to Apple Home again.

## Keeping it working

- **After the phone restarts, unlock it once.** Android keeps every
  app's data, including the agent's keys and your myQ sign-in, locked
  until the first unlock after boot. Within about a minute of the unlock,
  the agent restarts on its own, brings back the hidden screen, and reads
  the door. Until then Home shows the door as "No Response".
- Keep the phone **plugged in and on home Wi-Fi**. Wireless debugging,
  which the agent uses to restart its hidden screen, only works on Wi-Fi.
- Keep **automatic system updates off** if you can, so the phone does not
  reboot itself overnight and wait for an unlock.
- **Don't sign out of myQ.** A force-stop of the agent is recovered
  automatically; a signed-out myQ is not.

## When something is wrong

Open **Garage Agent** on the phone. The line at the top names the next
thing to fix, and the checklist below it says what each part is and what
to do. Most problems fix themselves within a minute or two; the buttons
retry right away.

| Home shows | Likely cause | Fix |
|---|---|---|
| No Response, right after a reboot | Phone not unlocked yet | Unlock it once |
| No Response, all the time | myQ signed out, or a myQ update changed its screens | Open Garage Agent; step 6 shows the problem. Open myQ to sign in |
| The control works at home but not away | No Home hub, or the hub is off | Check Home > Home Settings > Home Hubs |
| Commands briefly show on the phone's screen | The hidden screen is down; the agent used the real one | Garage Agent step 5; it restarts on its own |
| Garage Agent step 3 says the phone no longer accepts the agent's key | The pairing was removed, or wireless debugging is off | Tap Pair again |

**Asking for help.** Tap **Share report** in Garage Agent. It makes a
plain-text summary of every check and the recent log, with no passwords
and no setup code. Paste it into a
[new issue](https://github.com/bhwilkoff/GarageDoor/issues/new?template=problem.md).

## How it works

- **HomeKit accessory**: a HomeKit Accessory Protocol (HAP) server
  written from scratch in Kotlin with no dependencies. It covers SRP-6a
  pair setup, Ed25519, X25519, HKDF-SHA512, ChaCha20-Poly1305, encrypted
  sessions, events, and the timed writes Home uses for garage doors. It
  advertises itself over Bonjour with Android's NsdManager. The code is
  in `app/src/main/java/org/humanshaped/garageagent/hap/`.
- **Driver**: an accessibility service reads the door status from the
  myQ app and dispatches a gesture on its button. A command counts as
  accepted only when myQ shows the change. A failure is reported to Home
  as "No Response", never as a false state.
- **Hidden screen**: `DisplayHost` runs as the shell user and creates a
  private, always-unlocked virtual display with its own focus. myQ lives
  there permanently. The phone's real screen and focus are left alone.
- **Pairing without a computer**: `AdbPair.kt` is the client half of
  Android's "Pair device with pairing code" (TLS 1.3, then SPAKE2 over
  edwards25519 as BoringSSL does it, then AES-128-GCM), so the agent pairs
  its own key from the code you type.
- **Self-healing**: a sticky foreground service and a boot receiver keep
  the agent running. A watchdog re-enables the accessibility service if
  Android unbinds it. If the hidden screen disappears, the agent restarts
  `DisplayHost` itself through its paired wireless debugging connection
  (`AdbSelf.kt`).

The reasoning behind each choice is in `../DECISIONS.md` (004 to 007).

## Security

- **The agent has shell-level access to its own phone.** It needs this to
  grant its own permissions and restart the hidden screen after a
  reboot. Its key is created on the phone and kept in storage encrypted
  by the Android Keystore; it never leaves the phone. It appears in
  Wireless debugging's paired devices as `garage-agent`; remove it there
  to revoke it.
- **Only devices paired with your Home can control the door.** HomeKit
  pairing uses the setup code once. After that, every session is
  end-to-end encrypted, and the accessory accepts only controllers it
  has paired with. Long-press **Reset Apple Home** in the app to remove
  every pairing.
- **Your myQ password never leaves the myQ app.** The agent never sees it.
- **Releases are signed** with one key, certificate SHA-256
  `e8ff0012f92ad5bb7b70ddfc4d28470ca284e4c00bfca8b0a199c94771386464`.
  Each release also lists the APK's SHA-256 checksum.

## Removing it

Remove the accessory in the Home app, then uninstall Garage Agent from
the phone. Remove `garage-agent` from Wireless debugging's paired
devices.

## Development

All testing happens on a real phone, never an emulator. On-device tests
(RFC test vectors and a loopback HomeKit controller):

```
./gradlew connectedDebugAndroidTest
```

This uninstalls the app afterwards, which erases the HomeKit pairing.
Run it before pairing with Home, not after. Releasing is in
[RELEASING.md](RELEASING.md).
