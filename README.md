# Handoff

Handoff switches the shared Amsterdam visitor parking permit between Wasil's
and Walid's cars. See `PROJECT_BRIEF.md` for the full roadmap.

- `permit.py` — Phase 0 Python CLI proof of the permit API (source of truth
  for request/response shapes).
- `android/` — the Handoff Android app (Compose, MVVM, Retrofit).

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

## Phase 3: shared state between the two phones

The two phones now know about each other, so claiming the permit can no longer
silently strand the other car. Each phone publishes "am I parked in a paid
zone, where, since when" to a shared Firebase Realtime Database (plain HTTPS,
no Firebase SDK). Before any switch — automatic, from a notification, or from
the main screen — the app checks the other phone: if that car is parked in a
paid zone, was seen within 6 hours, and the permit is currently on its plate,
the switch is blocked with a "Claim anyway" override instead of going through.
Taking it anyway alerts the other phone ("Walid took the permit").

Other Phase 3 behaviour:

- **Home zone** (Settings → set to current location, 30–200 m): parking there
  never claims and never blocks the other phone.
- **Real paid zones:** Amsterdam's official tariff areas are bundled, so
  parking outside every paid polygon is recognised as free street parking and
  claims nothing. Claim notifications show the hourly rate and zone code.
- **Give-back:** park at home or in a free spot while the other car is parked
  in a paid zone and still needs the permit, and it is handed back
  automatically.
- **Map** (personal, nothing shared): your car's last parked spot plus your
  own current position.

Fixes for the Phase 2 problems found in real use: claim retries now wait for
connectivity instead of burning their backoff offline; a park with no GPS fix
asks instead of claiming blind; "Free here" reads a fresh location at tap
time; pending claims are cancelled when you get back in the car; and Settings
has a button to disable battery optimization (Samsung app-sleep was very
likely why detection worked once and then stopped).

One-time setup: see `SETUP_FIREBASE.md` (free Firebase project, one URL pasted
into Settings on both phones). On-device checks: `docs/phase3-manual-test-checklist.md`.

## v0.3.1: Handoff — the visual layer

The app is now called **Handoff**, after what it does: the permit is passed
between two cars rather than owned by either. Its mark is two facing arcs with a
dot between them — the arcs are the cars, the dot is the permit. The dot sits
against whichever arc holds it and that arc lights up while the other dims, so
who has the permit survives a glance at any size.

Wasil is slate blue, Walid is terracotta, and those two colours mean *identity*
and nothing else. Status uses a separate green and rust, and only ever on small
icons — so a green tick never reads as "Wasil". There are full light and dark
palettes, following the phone's setting.

The main screen now answers one question at a glance and offers one action:
because there are exactly two cars, the permit can only move to one place, so
"Hand to Walid" replaces a pair of plate buttons. Settings is grouped by how
often you touch each thing, with set-once questions moved into a first-run flow
and system requirements collapsed into a health row that stays quiet when
everything is fine.

This release changed no behaviour: detection, claiming, shared state and the
collision guard are exactly as Phase 3 left them, and all 96 of Phase 3's tests
pass unedited alongside 18 new ones — 114 in total. That the old tests needed no
edits is the evidence that nothing behavioural moved. Deferred work is tracked in
`docs/BACKLOG.md`.

## Data attribution

- Parking tariff areas: © Gemeente Amsterdam, parkeertarieven dataset
  (maps.amsterdam.nl), CC-BY 4.0. Snapshot downloaded 2026-07-29 from
  `https://amsterdam-maps.bma-collective.com/embed/parkeren/deploy_data/tarieven.json`
  and bundled as `android/app/src/main/assets/amsterdam_tarieven.json`
  (29 tariff areas, WGS84 polygons).
- Map tiles: © OpenStreetMap contributors.

## Security notes

- No credentials or plates live in this repo. Everything is entered on the
  in-app setup screen and stored in EncryptedSharedPreferences.
- The API JWT lives 1 hour; the app re-logs-in transparently on 401.
- Every switch is verified by re-reading permit state (`read-after-write`);
  a mismatch shows a loud warning instead of a false success.
