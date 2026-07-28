# Phase 2: Parked Detection — Design

Approved direction (2026-07-28 conversation): Approach 1 (local-only, no Firebase),
auto-switch on confirmed park, persistent status notification, Bluetooth reconnect
clears parked state. WorkManager is used as plumbing for the one-shot detection job
(a receiver alone cannot run a 60–90s pipeline); the full "Approach 3" persistent
architecture stays deferred to Phase 3.

## Goal

When the phone's owner parks their car, the app automatically claims the permit
for their plate (with Phase 1's read-after-write verification), then keeps a
notification visible showing which car holds the permit. No taps needed in the
normal case; a human decision is requested only when the signals are ambiguous.

## Detection model

**Trigger:** `BluetoothDevice.ACTION_ACL_DISCONNECTED` for the user's registered
car device (MAC stored at setup; both ACL broadcasts are on Android's implicit
broadcast exemption list, so a manifest-registered receiver works).

**Confirmation (inside a WorkManager expedited job, up to 90 s):** the job
samples two signal sources and feeds them to a pure decision engine:

- Activity Recognition (Play Services), when available on the device
- Location (fused provider): displacement from the disconnect point

Decision rules, evaluated over accumulated samples:

| Evidence | Decision |
|---|---|
| Activity `IN_VEHICLE` (confidence ≥ 70) | `FalseAlarm` — BT blip while driving, do nothing |
| Activity `STILL` (confidence ≥ 70, sample ≥ 5 s after disconnect) | `ParkedInCar` |
| Activity `WALKING`/`ON_FOOT` (confidence ≥ 70) | `ParkedWalkedAway` |
| GPS displacement > 10 m from disconnect point (accuracy ≤ 25 m) and latest activity is not `IN_VEHICLE` | `ParkedWalkedAway` |
| 90 s elapsed with none of the above | `Unclear` |

On devices without Activity Recognition the engine simply never receives
activity samples; the GPS rule and the `Unclear` fallback still work (this is
the "older phones" fallback agreed in brainstorming).

**Never auto-switch on `Unclear` or `FalseAlarm`.** `Unclear` posts a
notification with explicit **Claim permit** / **Ignore** / **Free here**
actions; `FalseAlarm` does nothing.

**Reset:** `ACTION_ACL_CONNECTED` from the car's MAC = driving again → clear
parked state, cancel any running detection job, dismiss park-event
notifications (the permit-status notification stays).

## Actions and outcomes

**Confirmed park (`ParkedInCar` / `ParkedWalkedAway`):**
1. If the location is inside a stored free zone (≤ 60 m from a marked point) →
   do NOT switch; update status notification ("Parked in free zone — permit
   untouched").
2. Else if auto-claim is ON → `PermitRepository.switchTo(myPlate)` (Phase 1
   path, read-after-write). Success → status notification "Permit on Wasil's
   car (RH950F), claimed 21:40". Mismatch → loud warning notification, exactly
   like Phase 1's rule. Network failure → WorkManager retry (backoff), plus a
   "switch failed — Retry / Ignore" notification; a failed switch must never be
   silent (it costs a fine).
3. If auto-claim is OFF → same notification as the `Unclear` case (manual
   Claim / Ignore / Free here).

**"Free here" action:** stores the parked location in a local free-zone list
(lat/lng, 60 m radius) in app storage and clears parked state without touching
the permit. There is no "release permit" API — activating one plate implicitly
ends the other — so "free" only ever means "don't claim here, now or in the
future". The list is local-only in Phase 2; Phase 4 moves it to shared storage.

**Persistent status notification:** low-importance ongoing notification showing
the current permit holder, updated after every switch the app performs or
observes on refresh. Park-event notifications (high-importance channel) are
separate and dismissible.

## Configuration (new Settings screen)

- Car Bluetooth device: picked from the phone's bonded device list, MAC stored.
- Whose phone this is: Wasil / Walid → determines which plate auto-claim uses.
- Auto-claim toggle (default ON).
- Manage free zones: list with delete.
- Permission status + request buttons: `POST_NOTIFICATIONS`,
  `BLUETOOTH_CONNECT`, `ACTIVITY_RECOGNITION`, `ACCESS_FINE_LOCATION`, and a
  deep link to app settings for background location ("Allow all the time",
  which Android only grants there).

Config lives in plain `SharedPreferences` behind a `ParkStateStore` interface
(MACs and toggles are not secrets; credentials stay in the Phase 1 encrypted
store).

## Architecture (units and boundaries)

Pure/testable core (JVM unit tests, no Android deps):
- `ParkSignals.kt` — `ActivitySample(type, confidence, elapsedMs)`,
  `GeoPoint(lat, lng, accuracyM)`, `distanceMeters()` (haversine).
- `ParkDecisionEngine.kt` — `decide(samples, disconnectPoint, latestPoint, elapsedMs): Decision?`
  implementing the table above. Pure function.
- `FreeZoneStore.kt` (interface) + zone-hit test `isInFreeZone(point, zones)`.
- `ParkDetectionUseCase.kt` — orchestrates: poll signals → decision → free-zone
  check → switch via `PermitRepository` → emit outcome. Depends only on
  interfaces (`DetectionSignals`, `ParkStateStore`, `FreeZoneStore`,
  `PermitRepository`, `ParkNotifier`); fully covered by fakes in tests.

Android shell (thin, no logic, verified manually):
- `CarBluetoothReceiver` — manifest-registered; filters by stored MAC; enqueues
  the worker on disconnect, clears state on connect.
- `ActivityUpdatesReceiver` + `PlayServicesSignals` — funnel Activity
  Recognition results and fused-location reads into `DetectionSignals`.
- `ParkDetectionWorker` (`CoroutineWorker`, expedited) — builds the use case
  and runs it; WorkManager provides the retry policy for failed switches.
- `ParkNotifications` — two channels, builders, action `PendingIntent`s
  (actions handled by a small `ParkActionReceiver`).
- `SettingsScreen` (Compose) + wiring in `MainActivity`/`PermitApp`.

New dependencies: `androidx.work:work-runtime-ktx`,
`com.google.android.gms:play-services-location`.

## Error handling

- Switch failure → WorkManager backoff retry + visible failure notification;
  retry never bypasses read-after-write.
- No car device registered → Bluetooth path inert; app behaves exactly like
  Phase 1 until setup is completed.
- Missing permissions → detection degrades (no activity samples / no location →
  `Unclear` → manual notification); Settings screen surfaces what's missing.
- Reboot: manifest receiver keeps working; status notification reappears on
  next app open or park event (acceptable for Phase 2).

## Testing

- JVM unit tests (TDD): decision engine (every rule + `Unclear` timeout +
  confidence thresholds), haversine distance, free-zone hit test,
  `ParkDetectionUseCase` outcomes (auto-claim on/off, free zone, mismatch,
  network failure, false alarm) with fakes.
- Manual on-device: register car BT, park scenarios (stay in car / walk away /
  drive through a BT blip / park at home after "Free here"), verify against the
  permit website.

## Out of scope (unchanged from brief)

Firebase/shared state (Phase 3), tariff comparison, iOS (architecture keeps
signals behind an interface so a future port swaps `PlayServicesSignals`),
multi-car per person, geofencing APIs.
