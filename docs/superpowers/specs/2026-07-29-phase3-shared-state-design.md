# Phase 3: Shared Parked State + Collision Guard — Design

Approved direction (2026-07-28/29 conversation): fix the real-world Phase 2 bugs,
then make the two phones aware of each other so a claim never silently strands
the other brother's parked car. First-come-first-served with manual override.
Amsterdam's official tariff-area polygons replace guesswork about what is paid
parking. Tariff *comparison* is dropped (no in-app payment yet); tariff data is
informational. Personal map per phone (own position + own car only — no
location sharing between phones).

## Goals

1. **Fix Phase 2 field bugs** found in Wasil's real test (see "Phase 2 bug
   fixes" below — all five are in scope).
2. **Collision guard:** when I claim the permit — automatically or manually —
   the app first checks whether the other person's car is parked in a paid zone
   and currently holds the permit. If so: block, explain, offer override.
3. **Home zone:** each phone sets its own home (point + radius). Parking inside
   it never claims and never blocks the other phone.
4. **Real paid-zone knowledge:** bundle Amsterdam's official tariff-area
   polygons; parking outside all of them is free street parking → no claim.
5. **Give-back:** when I park at home / in a free area while I still hold the
   permit and the other's car is parked outside, automatically hand the permit
   back to them.
6. **Personal map:** my live position + my car's last parked spot.

## Shared state: Firebase Realtime Database over REST

No Firebase SDK, no `google-services.json`. The app talks to RTDB's REST API
with OkHttp — the same stack the rest of the app uses, fully testable with
MockWebServer.

- **Database URL** is entered once in Settings on both phones (never committed
  to the repo — the repo and APKs are public).
- **Room id** is derived on both phones identically:
  `sha256("permit-room:v1:" + lowercase(trim(username))).hexPrefix(32)` where
  `username` is the permit-site login both phones already store. Zero pairing
  UI; unguessable (128-bit) path.
- **Rules** (pasted once in the Firebase console, documented in
  `SETUP_FIREBASE.md`):

```json
{ "rules": { "rooms": { "$room": { ".read": true, ".write": true } } } }
```

Root stays unreadable, so rooms cannot be enumerated. Tradeoff (documented):
anyone knowing URL + room id can read plate + parked coordinates. Acceptable
for a two-person family app; Firebase Auth can be added later.

### Data model

`/rooms/{roomId}/phones/{wasil|walid}`:

```json
{
  "parkedOutside": true,
  "lat": 52.3702, "lng": 4.8952, "accuracyM": 12.0,
  "zoneCode": "T13B",
  "parkedAtMs": 1722240000000,
  "heartbeatAtMs": { ".sv": "timestamp" }
}
```

`heartbeatAtMs` uses RTDB's server-timestamp placeholder, so staleness
comparisons are immune to phone clock skew. When not parked outside, the node
stays with `parkedOutside: false` (no deletes).

`/rooms/{roomId}/permit` (informational cache — never the source of truth):

```json
{ "holder": "wasil", "vrn": "RH950F", "claimedAtMs": 1722240100000, "override": false }
```

**Truth hierarchy:** who holds the permit = the permit API (Phase 1
read-after-write, unchanged). Who is parked where = the RTDB phone nodes. The
RTDB `permit` node only feeds notifications (who took it, when, was it an
override).

### Sync mechanics (no FCM, bounded 15-minute latency)

- **SyncStateWorker** (one-shot, `NetworkType.CONNECTED`, backoff): pushes my
  current phone node. Enqueued on every state change: park decision, claim
  success, BT reconnect, "Free here", home-zone change. Offline changes
  self-heal when connectivity returns.
- **HeartbeatWorker** (periodic 15 min — WorkManager's minimum — unique KEEP,
  `NetworkType.CONNECTED`): runs only while `parkedOutside == true`
  (enqueued when it becomes true, cancelled when false). Each run:
  1. refresh my `heartbeatAtMs` (server timestamp),
  2. read `/permit`; if `holder != me` and `claimedAtMs` is newer than the
     last claim I alerted about → **takeover alert** (high-priority
     notification: "Walid took the permit — your car is unpermitted at
     [spot]", tap opens the map). Dedupe via `lastAlertedClaimMs` in local
     state.
- **On app open:** one fresh read of the other phone's node + `/permit` for
  the main-screen status line.

**Staleness rule (user decision):** the other phone's `parkedOutside` is
trusted only while `now - heartbeatAtMs ≤ 6 h`. Older = treated as not
parked. With 15-minute heartbeats a live phone is never near the cutoff;
a dead phone stops blocking after 6 h.

## The claim guard

Pure class, JVM-tested:

```kotlin
object ClaimGuard {
    val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
    sealed interface Verdict {
        data object Proceed : Verdict
        data class Blocked(val other: PhoneState) : Verdict
    }
    fun evaluate(other: PhoneState?, otherPlate: String, activeVrn: String?, nowMs: Long): Verdict
}
```

**Blocked iff:** `other.parkedOutside` AND fresh (≤ 6 h) AND
`activeVrn == otherPlate` (the API says their plate holds the permit). If the
permit isn't actually on their plate, claiming strands nobody — proceed.

**Every** claim path goes through the guard — this closes the "it always
overwrites" hole:

| Path | On `Blocked` |
|---|---|
| Auto-claim after park detection | High-prio notification: "Wasil's car is parked (since 14:05, seen 14:20) and holds the permit. **Claim anyway** / Ignore" |
| Notification "Claim" action | Same notification with **Claim anyway** (force) |
| Main-screen switch button | In-app warning dialog with the same facts + **Claim anyway** |
| Retry after failed switch | Guard re-evaluated on every attempt |

**Force/override** skips the guard, writes `override: true` to `/permit` →
the other phone's next heartbeat raises the takeover alert.

**If the guard can't read RTDB** (network blip, wrong URL): automatic claims
degrade to the manual-decision notification (never gamble in the background);
user-initiated claims proceed with a visible note ("couldn't check Walid's
status"). Rationale: a human pressing the button has context the worker lacks.

**Simultaneous park race** (both claim within seconds): the permit API
serializes; the loser's read-after-write returns Mismatch → existing loud
warning, plus the takeover alert on the next heartbeat. Rare and recoverable.

## Zones: Amsterdam tariff areas + home + manual free circles

One resolver, strict precedence:

```kotlin
sealed interface ZoneInfo {
    data object Home : ZoneInfo
    data class ManualFree(val label: String) : ZoneInfo
    data class Paid(val code: String, val tariffText: String) : ZoneInfo
    data object FreeStreet : ZoneInfo   // outside every paid polygon
}
class ZoneResolver(home: HomeZone?, manualZones: List<FreeZone>, areas: List<TariffArea>) {
    fun resolve(point: GeoPoint): ZoneInfo   // Home > ManualFree > Paid > FreeStreet
}
```

- **Tariff areas:** snapshot of the city's own map data
  (`tarieven.json` from `amsterdam-maps.bma-collective.com`, CC-BY 4.0
  Gemeente Amsterdam; 29 areas, Polygon/MultiPolygon, WGS84 lon-lat, ~617 KB)
  bundled as an app asset with source URL + download date in the repo README.
  Parsed lazily with kotlinx-serialization into
  `TariffArea(code, description, tariffText, polygons)`. Point-in-polygon =
  ray casting incl. holes; MultiPolygon = any hit. Pure Kotlin, unit-tested.
  Updates are a later phase (re-download + rebuild); boundaries change rarely.
- **Time windows are display-only.** Parking at 20:00 in a "09–19" area still
  claims: holding the permit is free, and the car will still be there when
  paid hours resume tomorrow. Notifications show the rate and hours.
- **Home zone:** circle (default 60 m, editable 30–200 m), set in Settings
  from a fresh GPS read ("Use current location as home"). Deliberately NOT an
  Amsterdam polygon — those are neighborhood-sized; the brother parking three
  streets away must not read as "at home". Stored per phone.
- **Manual free circles** ("Free here") stay as user overrides — e.g. a
  private lot inside a paid area.
- **Degradation:** if the bundled asset fails to load, everything outside
  home/manual circles is treated as paid-with-unknown-rate (bias toward
  claiming — a claim costs nothing when the other car isn't parked; missing a
  claim costs a fine).

### Park decision table (confirmed park at point P; me = X, other = Y)

| P resolves to | Y parked outside (fresh ≤ 6 h) | Permit holder (API) | Action |
|---|---|---|---|
| Paid | no / stale / not parked | any | claim(X), read-after-write |
| Paid | yes | Y's plate | **blocked** → notification with Claim-anyway |
| Paid | yes | X's plate / none | claim(X) (strands nobody) |
| Home / ManualFree / FreeStreet | yes | X's plate | **give-back:** switch to Y, notify "Permit returned to Walid" |
| Home / ManualFree / FreeStreet | otherwise | any | no claim; status shows the reason |
| unknown (no GPS fix) | — | — | manual-decision notification — never auto-claim blind |

`autoClaim OFF` turns every automatic claim/give-back row into the
manual-decision notification; status-only rows are unchanged.

`parkedOutside` (shared) := confirmed park AND zone == Paid. Parking at
home/free never blocks the other phone.

**Give-back details:** runs through the same guarded-claim plumbing with
`target = other` (GiveBackWorker, one-shot, CONNECTED, backoff; re-checks
conditions on every retry). BT reconnect cancels it (situation changed). If
conditions no longer hold (Y no longer parked / permit no longer mine), it
exits silently.

## Personal map

- **osmdroid 6.1.20** (OpenStreetMap), not Google Maps: the Maps SDK requires
  an API key on a billing-enabled Google Cloud project (credit card), which
  this project deliberately avoids. Swappable later; OSM attribution shown.
- Two markers: **my car** (last park location + "parked 14:05") and **me**
  (single current-location read when the screen opens — no tracking, nothing
  shared). Opens from the main screen and from the takeover alert.
- A new park with no GPS fix stores `null` — the map then shows only "location
  unknown" for the car rather than a stale wrong pin.

## Phase 2 bug fixes (all confirmed in code review of the shipped build)

1. **Workers retry while offline** — `ParkDetectionWorker`/`ClaimPermitWorker`
   have no network constraint; after a DNS failure the exponential backoff
   pushes retries past the moment connectivity returns (matches Wasil's
   "only manual retry worked"). Fix: `NetworkType.CONNECTED` constraints on
   all network workers (allowed alongside expedited).
2. **Unknown location auto-claims** — `ParkDetectionUseCase` skips the
   free-zone check when GPS returns null and falls through to `claim()`
   (claims while parked at home). Fix: the decision-table row above — manual
   notification, never blind auto-claim.
3. **"Free here" marks nothing or the wrong spot** — the Unclear path never
   sets `lastParkLocation`, so the action stores a stale point or silently
   nothing. Fix: MarkFreeZoneWorker reads a *fresh* location at tap time.
4. **Stale claim retries outlive "back in car"** — `ACTION_ACL_CONNECTED`
   cancels detection but not `CLAIM_WORK`; an old failed claim can fire while
   driving or after parking at home. Fix: cancel `CLAIM_WORK` (and give-back)
   on reconnect + workers no-op when their preconditions no longer hold.
5. **Manual claims never set parked state** — notification-Claim leaves
   `parked = false`, which would corrupt the shared state. Fix: claim success
   updates local + shared state on every path.

Additionally, Settings gains a **"Disable battery optimization"** request
(Samsung's app-sleep very likely contributed to "worked once, then stopped")
and `SETUP.md` documents the Samsung settings to check.

## Architecture (new units; existing units unchanged unless named)

Pure/testable core (JVM, no Android):
- `shared/PhoneState.kt` — shared-state DTOs (kotlinx-serialization) incl.
  `ServerTimestamp` placeholder handling.
- `shared/RoomId.kt` — `roomIdFor(username): String` (SHA-256).
- `shared/ClaimGuard.kt` — verdicts, staleness.
- `zones/TariffAreas.kt` — asset JSON parsing → `TariffArea`.
- `zones/PointInPolygon.kt` — ray casting.
- `zones/ZoneResolver.kt` — precedence resolve.
- `parking/GuardedClaim.kt` — orchestrates: read other + activePlate → guard →
  switch/block/give-back → write shared state → notify. Replaces the direct
  `ClaimPermit.claim()` calls; `ClaimPermit` stays as the raw switch executor.
- `ParkDetectionUseCase` — reworked to the decision table (zone resolve +
  guarded claim + parkedOutside semantics).

Android shell:
- `shared/RtdbClient.kt` — OkHttp GET/PUT/PATCH `<url>/rooms/<room>/<path>.json`
  (kotlinx-serialization; MockWebServer-tested; lives in main source but has
  no Android imports).
- `SyncStateWorker`, `HeartbeatWorker`, `GiveBackWorker`, `MarkFreeZoneWorker`
  (+ constraints fixes to the two existing workers).
- `MapScreen.kt` (Compose + osmdroid `AndroidView`).
- Settings additions: home zone, database URL (+ "Test connection"), battery
  optimization button.
- Main screen: brother status line ("Walid: parked outside since 14:05 ·
  holds permit" / "not parked" / "unknown"), map button, blocked-switch
  warning dialog.
- `ParkStateStore` gains: `homeZone`, `syncUrl`, `parkedOutside`,
  `lastAlertedClaimMs`.

New dependency: `org.osmdroid:osmdroid-android:6.1.20`. (RTDB via existing
OkHttp — no Firebase SDK.)

## Error handling

- RTDB unreachable: auto-claims → manual notification; user-initiated claims →
  proceed + note; sync/heartbeat → WorkManager retry with CONNECTED
  constraint. A wrong database URL surfaces in Settings "Test connection".
- Permit API unreachable: unchanged Phase 2 behavior (visible failure +
  constrained retry), now with the network constraint actually holding retries
  until connectivity.
- Both phones must run Phase 3 for the guard to work; a Phase 2 app on one
  phone simply never writes its node → reads as "not parked" (same as today,
  no worse). Ship both APKs together.

## Testing

JVM unit tests (TDD, existing 47 stay green):
- `ClaimGuard`: fresh-blocked / stale-proceed / absent-proceed / parked-but-
  not-holder-proceed / boundary at exactly 6 h.
- `RoomId`: deterministic, normalized (case/whitespace), 32 hex chars.
- `PointInPolygon`: square, concave, hole, MultiPolygon, on-vertex, outside.
- `TariffAreas`: parse a real 2-area extract of the bundled asset (incl.
  Polygon + MultiPolygon, comma-decimal tariff keys).
- `ZoneResolver`: precedence Home > ManualFree > Paid > FreeStreet; asset-
  load-failure degradation.
- `GuardedClaim` with fakes: every row of the decision table, RTDB-fail
  branches, override write, give-back conditions-changed exit, shared-state
  writes on success, takeover-dedupe.
- `RtdbClient` with MockWebServer: GET null body, GET state, PUT/PATCH
  payload shape (incl. `.sv` placeholder), HTTP 4xx/5xx → typed failure.

Manual on-device checklist (docs): park in paid zone both phones, blocked +
override + takeover alert, give-back on returning home, free-street no-claim,
home no-claim, airplane-mode park → retry fires when network returns.

## Out of scope

FCM/instant push (15-min bound is acceptable), in-app payment and tariff
comparison, iOS, live location sharing, automatic zone-data updates,
Firebase Auth.

## One-time setup (documented in SETUP_FIREBASE.md)

Wasil creates a free Firebase project → Realtime Database (europe-west1) →
pastes the rules above → copies the database URL into Settings on both
phones. The URL is never committed to the repo. Both phones then derive the
same room automatically from the permit username.
