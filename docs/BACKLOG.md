# Backlog

Running list of deferred work. **Read this before designing or planning any
update** — it records decisions already made and problems already diagnosed, so
they don't get re-litigated or forgotten.

Versioning convention agreed 2026-07-29: layout/visual-only releases get a
patch bump (`0.3.1`), releases that change behaviour get a minor bump (`0.4`).

---

## v0.3.1 — layout and branding only (in progress)

No functional changes. Scope:

- Brand identity: name **Handoff**, the two-arc mark with a travelling dot,
  muted sage-teal (Wasil) and clay-ochre (Walid) palette, charcoal icon field.
- Real Compose theme: light + dark colour schemes, typography, shapes,
  replacing the bare `MaterialTheme {}` (currently baseline purple, light-only).
- Replace the legacy `android:Theme.Material.Light.NoActionBar` XML parent.
- Adaptive launcher icon (currently `@android:drawable/sym_def_app_icon`,
  the stock Android robot).
- Main screen: single "hand it over" action instead of two plate buttons,
  colour-coded hero card, icon row for map/refresh/settings.
- Settings restructured — one-time setup moved out, see below.
- Edge-to-edge (`enableEdgeToEdge()`), required from Android 15.

---

## Bugs

### 1. Claim fails in a retry loop when the permit is already yours — HIGH

Reported 2026-07-29 from real use: switching works once, but parking again
while the permit is already on your own plate produces a constant failure
notification and endless retries.

Cause: `PermitRepository.switchTo()` (`android/app/src/main/java/dev/wasil/permit/data/PermitRepository.kt`)
calls `api.activate(vrn)` unconditionally. When that plate already holds the
session the API rejects the call, `ClaimPermit.claim()` catches it and returns
`ParkOutcome.SwitchFailed`, and `ClaimPermitWorker` maps that to
`Result.retry()` — so WorkManager retries on exponential backoff indefinitely,
notifying on every attempt.

Fix: make `switchTo` idempotent — read `activePlate()` first and return
`Confirmed(vrn)` immediately when it already equals the target, without calling
activate. Add a test for "target already active → Confirmed, activate never
called".

### 2. No cap on claim retries — MEDIUM

`ClaimPermitWorker` returns `Result.retry()` for any `SwitchFailed` with no
attempt limit. Any persistent failure (bad credentials, API change) loops
forever. Fix: give up after ~5 attempts (`runAttemptCount`) with one final
notification that says retries stopped.

---

## v0.4 — the map becomes the home for everything location-based

Decided 2026-07-29. All location actions should happen where you can see them
on a map, instead of being buried as buttons in Settings:

- Show the home zone on the map as a circle, with its radius draggable, rather
  than the current blind "set home to current location" button.
- Show free zones as circles on the map; add and remove them there.
- Show the parked car and allow correcting its position by dragging the pin
  (GPS drift currently has no manual override).
- Show the Amsterdam tariff-area polygons as an overlay, and let a zone be
  selected or drawn by hand — including entering a tariff area code directly.

---

## Later / unscheduled

- Notification deep-links: every notification opens the app to a full-screen
  version of the same decision. Highest value on the blocked-claim notification
  (Claim anyway / free spot here / leave it). Needs a `PendingIntent` content
  intent plus an in-app decision screen, so it is behaviour, not pure layout.
- Bluetooth device picker: show paired devices with clearer identification
  (name, whether currently connected) instead of a plain radio list.
- Tariff-area data refresh: the Amsterdam snapshot is bundled and manual.
  Boundaries change rarely, so this is low priority.
- iOS: keep detection behind the `DetectionSignals` interface so a port stays
  possible. No work planned.
- Firebase Auth: current rules leave a room readable to anyone who knows the
  database URL and the room hash. Acceptable for two brothers; revisit if the
  app ever leaves the family.

---

## Locked decisions — do not re-open without a reason

- Auto-switch is the default, not ask-first.
- Tariff *comparison* between the two cars was explicitly dropped: it only
  matters once the app can pay for parking. Tariff data stays informational.
- Time windows on tariff areas are display-only. Parking at 20:00 in a
  09:00–19:00 area still claims, because holding the permit is free and the car
  will still be there when paid hours resume.
- Home zone is a small circle (30–200 m), deliberately not an Amsterdam
  neighbourhood polygon — those are large enough that a brother parking three
  streets away would read as "at home".
- The two phones run the same APK. "Whose phone is this" makes the UI mirror
  per device; there is no separate build for Walid.
