# Amsterdam Parking Permit App — Project Brief

## The problem

Wasil and his brother Walid share one Amsterdam visitor parking permit
(3 plates allowed, 2 in active use — Wasil's car and Walid's car). Only
one plate can hold the active permit at a time. Right now switching is
manual on the website, and it's easy to forget — which risks a fine for
whoever isn't currently covered.

Inspiration: Google's "at a Glance" parking-detection card (auto-detects
when you've parked, shows the location) — but rebuilt, and hooked up to
actually switch the permit instead of just showing a map pin.

## Core goal

Never forget to activate the permit for whichever car is actually
parked and needs it — automatically where safe, with a human decision
only when the situation is ambiguous (both cars parked, home, etc).

## Confirmed technical foundation (Phase 0 — DONE)

The permit site (parkeervergunningen.amsterdam.nl) is a thin frontend
over a clean JSON REST API:

- **Base URL:** `https://api.parkeervergunningen.egisparkingservices.nl/api`
- **Login:** `POST /ssp/login_check` — body `{"username": "...", "password": "..."}`
  → returns `{"token": "<JWT>"}`. Token expires in exactly 1 hour
  (`exp - iat = 3600`), no refresh token — just re-login on 401.
- **State/plates:** `GET /v1/client_product/{PRODUCT_ID}` — returns the
  full permit object including a `vrns` array. Each entry has `vrn`,
  `id`, and `has_parking_session` (bool) — exactly one is `true` at any
  time and that's the currently active plate. This doubles as the
  "read current state" call.
- **Switch active plate:** `POST /v1/ssp/parking_session/activate` —
  body `{"client_product_id": PRODUCT_ID, "vrn": "PLATE"}` → returns
  `{"parking_session_id": ...}`. No separate "deactivate" call exists —
  activating one plate implicitly ends the other's session.
- **Auth:** Bearer JWT on every authenticated call
  (`Authorization: Bearer <token>`).
- **Critical gotcha:** the API 403s without browser-like headers
  (`origin`, `referer`, `user-agent`) even with valid credentials —
  looks like basic bot filtering. Always send those three headers.
- **PRODUCT_ID:** `5807976` (the permit ID, constant).
- **Plate format:** no dashes (e.g. `RH950F`, not `RH-950-F`).
- Third plate on the permit belongs to an inactive vehicle — the app
  should only ever offer Wasil's and Walid's plates as choices, never
  the third.
- The `permit.geo_json` field in the state response contains the full
  polygon(s) of the permit zone — usable later for point-in-polygon
  "am I even in the zone" checks, no external map data needed.
- `validity.ended_at` = 2026-11-19 — permit expiry, worth a reminder
  eventually.

A working Python reference implementation (`permit.py`) proves all of
this end-to-end: login → read active plate → switch → verify. That
script is the source of truth for exact request/response shapes.

## Build order (why this order)

Sequence chosen so the riskiest unknown (does the site's automation
even work reliably) gets proven before investing in UI/Android work.

### Phase 0 — DONE
Python CLI script proving the API: login, read state, switch plate,
verify the switch landed. No Android involved.

### Phase 1 — MVP (build this first in Claude Code)
Minimal Android app. One screen:
- Two buttons: "Set to Wasil's car" / "Set to Walid's car"
- A label showing which plate is currently active (read on launch via
  the state endpoint)
- Tapping a button calls activate, then re-reads state to confirm the
  switch landed before updating the UI — never trust the write blindly
- Login: store the account codes in EncryptedSharedPreferences /
  Android Keystore, never plaintext. Auto re-login on a 401 (token
  expired) and retry the original request once.
- No Bluetooth, no detection, no shared state between phones yet —
  this alone is already an upgrade over using the website.

Stack: Kotlin, MVVM, Retrofit + OkHttp with a persistent CookieJar-less
setup (it's bearer-token, not cookie-based) and an OkHttp Authenticator
for the auto-relogin-on-401 flow.

### Phase 2 — Parked detection
- Bluetooth disconnect from the car's stereo (primary trigger — near
  instant, near-zero battery cost) — register a receiver for
  `ACTION_ACL_DISCONNECTED` filtered to the car's known MAC
- Android Activity Recognition API as a fallback/secondary signal
  (`IN_VEHICLE` exit → `STILL` entry) — slower (~30–90s) but doesn't
  need a paired Bluetooth device
- On detected park: notification with three actions — **Claim permit**
  / **Ignore** / **Free here** (see Phase 4)
- Bluetooth *connect* event = driving off = release/clear local
  "parked" state

### Phase 3 — Shared state between the two phones
- Firebase Realtime DB or Firestore holding: each phone's current
  parked status + location + timestamp, and who currently holds the
  permit
- Decision logic on a new park event:
  1. Is this a known free zone (home or a marked spot)? → do nothing,
     never touch the permit
  2. Is the other car also currently parked (and not in a free zone)?
     → compare parking tariffs for both locations, the more expensive
     one keeps/gets the permit, notify the other person of the
     decision
  3. Otherwise → auto-switch the permit to this car, notify the other
     phone ("Wasil claimed the permit at 21:40")
- Staleness handling: if a phone hasn't reported in N hours (dead
  battery etc), treat its "parked" status as stale/unknown rather than
  trusting it blindly, but surface a warning rather than silently
  overriding

### Phase 4 — Free-zone map
- Base layer: import Amsterdam's open parking-zone/tariff data once
  (or derive zone from the permit's own `geo_json` + a tariff lookup)
- User layer: a `freeZones` collection in the same Firebase project —
  circle markers (lat/lng/radius ~50-80m) that either person can add
  by tapping "Free here" on the parking notification. Both phones read
  the shared list, so marking a spot once teaches the whole app.
- User overrides always take precedence over the base layer data
- Small "manage my marked spots" screen for editing/undoing mistaken
  entries
- Geofence registration only for nearby free zones if the list ever
  exceeds Android's 100-geofence cap

## Non-negotiable safety behaviors (carried through every phase)

- **Read-after-write always.** Every plate switch is followed by a
  re-fetch of state to confirm it actually landed — never assume a 200
  response means the permit is now correct.
- **Never auto-switch away from a car that's still parked and not in a
  free zone**, even if the other car just parked — that's what the
  tariff-comparison / ask-first logic in Phase 3 exists to prevent.
- **WorkManager retry** for the activate call — a failed switch on bad
  signal is the one failure mode that actually costs money (a fine),
  so it should never fail silently.
- Login codes are re-usable long-term (no expiry per Wasil/Walid), but
  the JWT dies hourly — the app must handle that transparently, not
  surface "please log in again" to the user.

## What's already been ruled out / decided against

- No headless-browser or WebView automation — the JSON API makes that
  unnecessary and more fragile.
- No attempt to hook into Google's own "at a Glance" parking data —
  it's not exposed to third-party apps; detection is rebuilt from
  Bluetooth + Activity Recognition instead.
- No server-side component beyond Firebase — no reason to run a
  backend for two users.
