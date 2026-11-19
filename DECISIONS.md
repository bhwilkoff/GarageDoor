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
