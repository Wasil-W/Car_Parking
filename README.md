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

## Security notes

- No credentials or plates live in this repo. Everything is entered on the
  in-app setup screen and stored in EncryptedSharedPreferences.
- The API JWT lives 1 hour; the app re-logs-in transparently on 401.
- Every switch is verified by re-reading permit state (`read-after-write`);
  a mismatch shows a loud warning instead of a false success.
