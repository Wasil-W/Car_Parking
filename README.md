# Permit Switcher

Switches the shared Amsterdam visitor parking permit between Wasil's and
Walid's cars. See `PROJECT_BRIEF.md` for the full roadmap.

- `permit.py` — Phase 0 Python CLI proof of the permit API (source of truth
  for request/response shapes).
- `android/` — Phase 1 native Kotlin app (Compose, MVVM, Retrofit).

## Build

Requires Android SDK (`C:\Android\sdk`, see `android/local.properties`) and
JDK 21 (Android Studio's bundled JBR is configured in `gradle.properties`).

    cd android
    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

## Phase 2: automatic parked detection

When your car's Bluetooth disconnects, the app checks Activity Recognition and
GPS (up to 90 s) to confirm you actually parked — a Bluetooth blip while
driving is never acted on. On a confirmed park it automatically claims the
permit for this phone's plate (verified read-after-write) and keeps a
persistent notification showing who holds the permit. Reconnecting to the car
clears the parked state. "Free here" on a park notification marks the spot
(60 m radius) so the permit is never touched there again — mark home first.

One-time setup in the app's Settings screen:
1. Grant all listed permissions, then set Location to "Allow all the time"
   via the Open app settings button.
2. Pick your car from the paired Bluetooth devices.
3. Choose whose phone this is (Wasil / Walid) — that's the plate auto-claim uses.
4. Leave auto-claim on (or turn it off to get ask-first notifications instead).

Smoke test: park and stay seated (expect a claim notification within ~20 s);
park and walk away; drive somewhere with the permit website open and verify
every switch; mark home with "Free here" and park there again (no switch).

## Security notes

- No credentials or plates live in this repo. Everything is entered on the
  in-app setup screen and stored in EncryptedSharedPreferences.
- The API JWT lives 1 hour; the app re-logs-in transparently on 401.
- Every switch is verified by re-reading permit state (`read-after-write`);
  a mismatch shows a loud warning instead of a false success.
