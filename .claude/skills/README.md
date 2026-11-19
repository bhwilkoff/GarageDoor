# The skills

The 22 skills this utility needs, copied from
[UniversalAppTemplate](https://github.com/bhwilkoff/UniversalAppTemplate)'s
`.claude/skills/`. The template's own catalog credits each one's author.
When a need arrives that none of these covers, copy the one skill from
the template and add a trigger row to the AGENTS.md table.

A skill is a folder with a `SKILL.md`. Its `description` is the trigger;
its body is the method.

## Method and design

- `learning-orientation-design`: the four-question test before any feature.
- `feature-shipping-discipline`: read, propose, build, verify, bump, commit.
- `architectural-decision-log`: how DECISIONS.md entries are written.
- `human-shaped-review`: feedback on how human-shaped the app is.
- `mobile-first-density-design`: density by removing chrome.
- `native-platform-first`: platform components before custom ones.
- `universal-feature-states`: loading, empty, error, offline for anything that loads.

## Reaching the door

- `third-party-revocation-resilience`: depending on a service that can take access away.
- `zero-cost-hosted-backend`: a small free server, if a route needs one.
- `authentication`: third-party sign-in from an installed app, tokens.
- `ios-security`: Keychain, Face ID, keeping credentials safe.
- `homekit`: Apple Home accessories, if the door shows up there.
- `ios-networking`: URLSession, async/await, retries.
- `swift-concurrency`: Swift 6 concurrency and data-race safety.

## The lock screen

- `app-intents`: Shortcuts, Siri, and the actions Controls and widgets run.
- `widgetkit`: lock-screen widgets and Control Center / lock-screen Controls.
- `activitykit`: Live Activities, for a door in motion.

## iOS and web

- `swiftui-patterns`: SwiftUI structure and state.
- `ios-production-gotchas`: production iOS lessons, by symptom.
- `ios-simulator`: simctl builds, screenshots, launch doors.
- `swift-testing`: tests for Core logic.
- `web-platform-patterns`: vanilla web, offline, deploys.
