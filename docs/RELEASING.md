# Releasing — how to actually produce an installable APK

Written 2026-08-31 because another assistant could not get an APK out. Every
trap below has been hit on this machine at least once.

## The one thing that catches everyone

**Ship the *debug* APK, not the release APK.**

`:app:assembleRelease` produces `app-release-unsigned.apk`. There is no signing
config in this project, so that file **cannot be installed on a phone** — Android
refuses an unsigned package, usually with a message that sounds like corruption
rather than like a signing problem.

The debug build is signed with the local debug keystore, which is why every
release from `v0.1` onward has attached the debug APK. Checked against the
published assets: they are all ~12–13 MB debug builds.

```bash
cd android
./gradlew.bat :app:assembleDebug
```

Output lands at:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

Rename it on the way to the release — `handoff-v0.7.6.apk` — because GitHub shows
the filename and `app-debug.apk` tells the reader nothing.

## Before you build

**Bump `versionCode`. It must always increase.**
`android/app/build.gradle.kts`:

```kotlin
versionCode = 30          // never reuse a consumed number
versionName = "0.7.6"
```

An equal `versionCode` is **not** an upgrade as far as Android is concerned. The
install silently fails or the user gets "app not installed", and it looks like a
broken APK. This project has been bitten by it once: v0.7.0 was nearly tagged on
a tree still carrying v0.6.9's `versionCode 26`.

**`CLAUDE.md` requires `docs/TIMELINE.md` to be updated in the same commit as the
bump.** A release that did not update the timeline is not finished.

## The JDK trap

Gradle must run on **Android Studio's bundled JBR**, not the system JDK. The
system Java on this machine is 25, which AGP rejects. It is already pinned in
`android/gradle.properties`:

```
org.gradle.java.home=<path to Android Studio>\\jbr
```

If a build fails with an "Unsupported class file major version" or an AGP/Java
mismatch, this is why — do not "fix" it by changing the toolchain.

## Full release sequence

```bash
# 1. verify
cd android
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# 2. install over the PREVIOUS version, not onto a clean device.
#    This is the check that catches a versionCode mistake.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dumpsys package dev.wasil.permit | grep -E "versionCode|versionName"

# 3. commit the bump + CHANGELOG + TIMELINE together, then tag
git tag -a v0.7.6 -m "v0.7.6 - short summary"
git push origin master
git push origin v0.7.6

# 4. publish, with the APK attached
gh release create v0.7.6 handoff-v0.7.6.apk \
  --title "v0.7.6 - short summary" \
  --notes-file notes.md
```

`gh` is already authenticated on this machine as `Wasil-W`. The release notes are
the matching section of `CHANGELOG.md` — that file is written to double as
release notes, so lift the section rather than writing something new.

## Windows/shell traps, all hit here

- **Use PowerShell for `adb push` / `adb shell`, not Git Bash.** Git Bash
  rewrites `/sdcard/x.png` and `/data/local/tmp/...` into Windows paths, and the
  error looks like a missing file on the device.
- **Never `adb exec-out ... > file.png` from PowerShell.** `>` corrupts binary
  output. Use `adb shell screencap -p /sdcard/x.png` then `adb pull`.
- **Do not use a `python - <<'PY'` heredoc with a shell fallback.** It hangs this
  environment for the full command timeout. Use a real script file.
- **PowerShell here-strings (`@'…'@`) break on embedded quotes** in git commit
  messages. Use `git commit -F <file>` or a Bash heredoc.

## Per-commit APKs, if you want them

The above is per *release*. If the goal is an artifact on **every commit**, do it
in CI rather than by hand — the repo has no workflows today:

`.github/workflows/apk.yml`

```yaml
name: APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - name: Build
        working-directory: android
        run: ./gradlew :app:assembleDebug :app:testDebugUnitTest
      - uses: actions/upload-artifact@v4
        with:
          name: handoff-${{ github.sha }}
          path: android/app/build/outputs/apk/debug/app-debug.apk
```

Two notes: drop the `org.gradle.java.home` line for CI (it points at a local
Android Studio install that does not exist on the runner) — set it in
`gradle.properties` only for local builds, or override with
`-Dorg.gradle.java.home=$JAVA_HOME`. And CI debug builds are signed with a
*runner-generated* debug key, so they will **not** install over a locally-built
one without an uninstall. For anything you actually put on a phone, build locally
or add a proper signing config.
