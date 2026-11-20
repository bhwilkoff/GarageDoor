# GarageDoor: Agent Project Context

A small personal utility: open, close, and see the state of a myQ
(Chamberlain) garage door from the iPhone lock screen. It is a
lightweight copy of UniversalAppTemplate, kept to the skills and docs
this job needs. The template stays the home of the full method; pull a
skill from it when a need arrives that this copy does not cover.

**Route**: the official myQ Android app on an always-on Pixel, driven
by our own on-device agent that appears in Apple Home as a garage door
(Decision 004). Constraint: $0, no new hardware, no paid services.

## Why we build

Every feature in this app is built in service of human learning and
growth, not to replace thinking, but to deepen it. At each decision
point, ask: does this design invite the user to engage more fully,
think more critically, or connect more meaningfully? If a feature
makes a person more passive, reconsider it. If it opens a door to
curiosity or collaboration, prioritize it. The goal is never a slick
product. It is a tool that makes someone more human.

For a utility this size the test is mostly about honesty and control:
the door's real state is always visible, nothing moves the door that the
owner did not ask for, and a failure says so plainly. **Before
implementing any feature**, invoke `learning-orientation-design`.

This template builds **human-shaped software**: it takes the mechanical
work off people's hands and leaves them the deciding. Every round makes
three moves: **write** (values, features, and feedback live here),
**play** (the owner uses what you built on their real phone; you are
never the tester), **publish** (it goes onto the owner's phone and into
daily use, and that use drives the next round).

---

## Why and who (the owner's words)

> "It is just for me. I'd love for the other members of my family to be
> able to use it if possible, but that isn't a requirement right now. I
> just need a reliable way to open and close my garage door from my lock
> screen."

---

## What this app does

**Goal**: one tap from the iPhone lock screen (a Control, a lock-screen
widget, a Shortcut, or Siri) opens or closes the garage door, and the
door's current state is visible before and after.

**Platforms**: the iPhone is the target. Lock-screen and Control Center
controls exist only in native iOS (WidgetKit `ControlWidget`, iOS 18+,
backed by App Intents), so the iOS app is expected to be where the real
work lands. The web shell stays for a status page or a Shortcut-callable
endpoint if the chosen route needs one. The owner decides the platform
set once the route is chosen; record it in DECISIONS.md.

**The hard constraint**: myQ has no public API, and Chamberlain has
blocked unofficial clients before (late 2023). Any route that leans on
myQ's cloud is presumed revocable (Decision 002). The route decision is
the first open question in SCRATCHPAD.md.

---

## How we build

The method lives in **skills** in `.claude/skills/` (catalog:
`.claude/skills/README.md`). Invoke the skill when its trigger matches;
don't re-derive a pattern.

| When | Skill |
|---|---|
| **Method** | |
| Proposing or reviewing ANY feature, especially automation | `learning-orientation-design` |
| Starting any feature change | `feature-shipping-discipline` |
| Logging an architecture decision | `architectural-decision-log` |
| Feedback on how human-shaped the app is | `human-shaped-review` |
| **Design** | |
| Designing any view or control | `mobile-first-density-design` + `native-platform-first` |
| Any surface that loads state (door status, history) | `universal-feature-states` |
| **Reaching the door** | |
| Choosing or changing how the app talks to myQ, IFTTT, or local hardware | `third-party-revocation-resilience` |
| A small server or proxy (a Worker holding a token, a webhook relay) | `zero-cost-hosted-backend` |
| Signing in to a third-party account, OAuth, token storage | `authentication` + `ios-security` |
| Local hardware exposed to Apple Home (HomeKit accessory) | `homekit` |
| HTTP calls from Swift | `ios-networking` + `swift-concurrency` |
| **The lock screen** | |
| Shortcuts, Siri, and the actions a Control runs | `app-intents` |
| Lock-screen widgets and Control Center / lock-screen Controls | `widgetkit` |
| Showing a door in motion on the lock screen (Live Activity) | `activitykit` |
| **iOS app** | |
| Any SwiftUI view or app structure | `swiftui-patterns` |
| An iOS symptom (see `docs/platforms/apple.md`) | `ios-production-gotchas` |
| Simulator builds, screenshots, launch doors | `ios-simulator` |
| Unit tests for Core logic | `swift-testing` |
| **Web** | |
| Any web UI, routing, offline, or deploy work | `web-platform-patterns` |

### Where each platform's rules live

- `docs/platforms/apple.md`: any Swift work; `project.yml` and XcodeGen.
- `docs/platforms/web.md`: any web work.
- `docs/platforms/design-system.md`: changing the look on either platform.

### Hard rules

- **All network calls through one shared client** (`js/api.js`, the Swift
  `APIClient` actor); views, widgets, and intents never call the network
  directly.
- **Secrets never in git, and never in cleartext on disk.** myQ, IFTTT,
  or any account credential or token lives in the iOS Keychain (shared
  with the widget extension through an access group) or in a server's
  secret store. Never in `UserDefaults`, a plist, or a committed file.
- **The door never moves without an explicit tap or spoken request.** No
  automation that opens the door on its own (location, schedule) unless
  the owner asks for it by name and DECISIONS.md records it.
- **Show the state you know, and when you learned it.** A stale or
  unknown door state is shown as stale or unknown, never as closed.
- **Deep links land in an inbox** the root consumes (`DEEP_LINKS.md`).
- **One version, bumped on every ship**, from `AppVersion.xcconfig`
  (`tools/test_version_contract.py`).
- **One identity, set once** with `tools/set_app_identity.py` when the
  owner names it; never ship `com.example.appname`.
- **Native components first**; no third-party Swift packages.

---

## Debugging philosophy

**Do not iterate blindly on behavior you cannot observe.** When the root
cause is not clear from reading the code, the first move is diagnostics,
not another implementation attempt.

1. **Add observability before another implementation.** Write what you
   *expect* to see vs. what would *indicate* the bug.
2. **Isolate layers**: the control, the intent, the network client, the
   remote service, the door. The bug is in the layer whose actual output
   diverges from its expected output.
3. **Use `print` / `console.log`**: it lands in the console immediately.
4. **For invisible UI bugs, add a temporary on-screen overlay** so the
   numbers appear on the screenshot.
5. **Make diagnostics env-gated when the bug class recurs**
   (`APP_DIAG=1`); remove one-off diagnostics before declaring a fix done.
6. **Drive the app to a known state** with DEBUG-only launch doors
   (`apple/Core/Store/LaunchDoors.swift`, web `?view=`).

Widgets and Controls run in a separate process with tight time and
memory budgets, and the simulator is lenient where a device is not.
Anything touching the Keychain, App Groups, or the lock screen needs a
real-iPhone check before "done."

**Testing against a real garage door moves a real door.** Never trigger
open or close during development without the owner's go-ahead for that
run. Prefer a read-only status call, or a stub client, until the owner
is standing there.

---

## Shared design system

**One source: `design-tokens.json`.** `node tools/design_tokens.mjs`
writes the web and Apple tokens; `node tools/test_design_tokens.mjs`
fails on drift or a text pair below WCAG AA. Never hand-edit a generated
file or a `BEGIN design-tokens` block. Brand colors are UI chrome only;
semantic colors (success, warning, error) carry meaning only, so "open"
and "closed" use semantic colors, not brand ones. UI icons come from SF
Symbols (the system's garage symbols) or inline SVG; emoji are never
chrome.

---

## How we collaborate

**The memory ratchet.** When the owner corrects an approach, save it as
a feedback memory with a `**Why:**` line. When they confirm a
non-obvious choice, save that too.

**Trust but verify.** Subagent summaries describe what the agent
intended, not what it did. Check the actual diff.

**Auto-pace decisiveness.** When how to build is reasonably inferable,
make the call. Reserve questions for decisions only the owner can make:
which route to the door, what may cost money, anything that moves the
door.

**Verify before declaring done.** Build it, run it, and show the
evidence (the command and its output, or a screenshot). "Compiles" is
not "works," and only the owner can confirm the door moved.

**Session log discipline.** Each SCRATCHPAD.md entry is state found,
work done, state left. Keep the two most recent entries.

---

## Standing instructions

- **Read the relevant skill before re-deriving a pattern.**
- **Commit messages quote the owner's request verbatim** when applicable.
- **DECISIONS.md leads with WHY**: the rule, then `**Why:**`, then
  `**How to apply:**`.
- **Don't add features beyond what's requested.** Scoped diffs.
- **Default to writing no comments**, except a non-obvious WHY.
- **No emojis in code or commits.**
- **Only essential words on screen.** *No check yet.*
- **One spelling locale, linted.** *Check: `tools/test_us_english.py`.*
- **Zero ongoing cost unless the owner says otherwise.** A route that
  needs a paid subscription (IFTTT Pro, a hosted server tier) is the
  owner's call, stated with its price before it is built. *No check yet.*
- **Update SCRATCHPAD.md "Out of scope" when rejecting an idea.**
- **AGENTS.md stays under 24,000 bytes.** *Check:
  `tools/test_agent_files.py`.*

---

## Current state

`SCRATCHPAD.md`: active milestone and open questions. `DECISIONS.md`:
architecture decisions. `PARITY.md`: what exists on web and iOS.
