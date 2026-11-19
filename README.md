# [APP NAME]

A personal control for a myQ garage door, reachable from the iPhone lock
screen.

Made from [UniversalAppTemplate](https://github.com/bhwilkoff/UniversalAppTemplate),
cut down to what this job needs: a web shell, an iOS shell, and the
skills for App Intents, WidgetKit Controls, Live Activities, HomeKit,
sign-in and Keychain storage, networking, and services that can be
taken away.

- `AGENTS.md`: what every coding agent reads first.
- `SCRATCHPAD.md`: where the work stands, and the open question of how
  to reach the door at all.
- `DECISIONS.md`: why things are the way they are.
- `.claude/skills/`: the method, one folder per skill.

## Checks

```
python3 tools/test_agent_files.py
python3 tools/test_version_contract.py
python3 tools/test_us_english.py
node tools/test_design_tokens.mjs
```

The iOS project is generated: `brew install xcodegen && xcodegen generate`.
