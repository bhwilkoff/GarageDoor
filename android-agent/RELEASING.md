# Releasing Garage Agent

Every release of Garage Agent is a signed APK on GitHub, built by
`.github/workflows/android-release.yml` when I push a version tag. It is
not on the Play Store, and that is on purpose. Play's accessibility
policy doesn't allow automation tools, and a rejection there would put my
whole developer account at risk, along with every other app in it
(Decision 007).

## The signing key

| What | Where |
|---|---|
| Keystore (PKCS12, alias `garage-agent`) | `~/.config/humanshaped/garage-agent/release.p12` on my Mac |
| Keystore password | macOS Keychain, service `humanshaped-garage-agent-signing` |
| CI copies | GitHub secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` |
| Certificate SHA-256 | `e8ff0012f92ad5bb7b70ddfc4d28470ca284e4c00bfca8b0a199c94771386464` |

**The key is the one thing that can't be replaced.** If it is lost, no
one can install an update: everyone would have to uninstall, reinstall,
and add the door to Apple Home again.

**Backups**: the keystore is also an identity in the login Keychain
("Garage Agent") and a file in iCloud Drive under "Human Shaped/Garage
Agent signing key". The password is only in the login Keychain, which
does not sync; keep a copy of it in the Passwords app or a password
manager. The GitHub secrets can't be read back out, so they are not a
backup.

To read the password: `security find-generic-password -s humanshaped-garage-agent-signing -w`.

## Cutting a release

1. Bump `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in
   `AppVersion.xcconfig`, and `versionCode` in `app/build.gradle.kts`
   (it must go up every release, or Android refuses the update).
2. Write `release-notes/<version>.md`: what changed, in plain words, and
   how to install. The release page shows it, and the workflow stops if
   it is missing.
3. Optional: `./release.sh` builds the same signed APK locally into
   `build/release/` to try on a phone first.
4. Commit, then tag and push:

   ```
   git tag v1.1 && git push origin main v1.1
   ```

   The tag must be `v` plus `MARKETING_VERSION`, or the workflow stops.
5. The workflow builds, refuses to publish anything not signed with the
   release key, and creates the release with the APK (also as `garage-agent.apk`, which
   is what the download link and QR code point to) and its SHA-256.

Running the workflow by hand (Actions > Android release > Run workflow)
builds and checks the APK and attaches it to the run, without publishing.

## Android developer verification

From 2027 in most countries (September 30, 2026 in Brazil, Indonesia,
Singapore, and Thailand), certified Android phones install sideloaded
apps only from registered developers. The owner's identity is verified
through Play Console (Learning is Change, Inc.), and
`org.humanshaped.garageagent` was registered with the release key on
2026-11-20 (Play Console > Android developer verification; status "In
review" until Google confirms by email). To register another package:

1. Play Console > **Android developer verification** (or the Android
   Developer Console's **Packages** page): register the package name
   `org.humanshaped.garageagent` with the certificate SHA-256 above.
2. If asked to prove ownership, the console gives a small file to put in
   the APK's assets. Add it under `app/src/main/assets/`, run
   `./release.sh`, and upload `build/release/garage-agent-<version>.apk`.

Installs through adb (`setup.sh`) are not affected.
