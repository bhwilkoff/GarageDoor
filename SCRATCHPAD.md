# [APP NAME]: Scratchpad

## Current state

- **Status**: NOT STARTED. A lightweight copy of UniversalAppTemplate,
  made 2026-11-19.
- **Active milestone**: M0, choose how the app reaches the door.
- **Last session**: none yet
- **Next actions**:
  1. The owner says, in their own words, why this exists and who uses it
     (just them? family members' phones too?). Record it in AGENTS.md.
  2. Settle the route to the door (open question 1) and log it in
     DECISIONS.md.
  3. Name the app and run `tools/set_app_identity.py`.
  4. Prototype the smallest end-to-end path: a read-only door status,
     then one tap that toggles the door, fired from a Shortcut before any
     widget work.
  5. Then the lock-screen Control (WidgetKit `ControlWidget`, iOS 18+)
     and a lock-screen status widget.
- **Open questions**:
  1. **How does the app reach the door?** myQ publishes no API. Routes
     found so far (2026-11-19, a quick search, not yet verified in depth):
     - **IFTTT's myQ service.** IFTTT lists applets that fire a myQ
       action when an iOS Shortcut runs. Official partner path, so the
       least likely to be blocked, but likely needs a paid IFTTT plan,
       and it may control the door without reporting its state back.
     - **myQ's own app.** No widget for one-tap door control that I could
       find. Shortcuts can open the app but not press the button without
       an accessibility workaround that needs the phone unlocked.
     - **Unofficial myQ cloud API** (the reverse-engineered one older
       Home Assistant and Homebridge plugins used). Chamberlain blocked
       these in late 2023; presumed revocable at any time (Decision 002).
     - **Local hardware on the opener** (for example a ratgdo board
       wired to the opener's terminals, which can show up in Apple Home
       or speak MQTT/HTTP on the home network). Bypasses myQ's cloud
       entirely; costs a one-time hardware purchase; not verified for
       this owner's opener model. If it lands in Apple Home, iOS already
       gives a lock-screen control with no app at all, and an app only
       earns its place if it adds something.
     The owner's opener model and whether they will buy hardware or pay
     a subscription decide this.
  2. Does anyone besides the owner need the control?
  3. Is a web surface needed at all, or is this iOS only?

---

## Out of scope

- Android, macOS, tvOS, Windows, smart-TV builds: not carried in this
  copy. Pull them from UniversalAppTemplate if ever wanted.
- App Store distribution: personal install first. If TestFlight or the
  store is wanted later, pull `cloud-appstore-submission` and
  `apple-app-store-cli-submission` from the template.

---

## Session log

(none yet)
