# Every condition that leads to a user-facing action

Requested 2026-08-04: *"I want a complete table of every conditional check
currently in the app that leads to a user-facing action or prompt — not just the
home-zone one that turned out to be wrong."*

**This is a discovery document, not a fix list.** The "Correct?" column is my
assessment, for Wasil to overrule. Nothing here has been changed on the strength
of it.

Read against `v0.6.1`. Every row names the file and line so a claim can be
checked rather than trusted — the last audit-style list in this repo carried a
"hazard" that had already been fixed months earlier, purely because nobody
re-read the code.

---

## The headline: the two reported bugs are one bug

Both of Wasil's outstanding reports —

- *"No parked car location even though it detected the parked car"*
- *"Asked in the home zone if I want to claim the permit. Not needed"*

— are the **same root cause**, rows **A3** and **B1** below. Neither is a logic
error in the home-zone check, which is why looking at the home-zone code found
nothing wrong.

**What actually happens.** `confirmedPark()` needs a GPS fix to know which zone
you are in. It asks for a fresh fix, and falls back to a cached one **only if
that cached fix is under two minutes old** (`PlayServicesSignals.kt:96`). Park in
a garage, or after the phone has sat still a while, and both fail. `point` is
null, and the code takes this branch:

```kotlin
if (point == null) {
    markNotOutside()
    return askUnlessAlreadyMine()   // ← prompts
}
```

With no position the app cannot tell home from a paid street, so it asks. **Home
is exactly where a fix is most likely to fail** — indoors, stationary, often
overnight. So the prompt appears precisely where it is least wanted, and the map
shows "no parked location recorded yet" at the same time, from the same missing
fix.

**Wasil's request #2 is the fix.** Polling GPS while Bluetooth is connected means
the drive *to* the house produces a stream of positions, and the disconnect
moment has a known location even when a fresh fix fails. That reframes #2: it is
not a new feature sitting on top of a shaky foundation, **it is the repair to
the foundation**. It should be built first, not last.

---

## A. Park detection — `ParkDetectionUseCase.kt`

| Condition | Current action | Correct? | Notes |
|---|---|---|---|
| **A1** `myCar == null` (not set up) | Nothing at all | **Y** | Silent is right; Settings already shows setup state. |
| **A2** Decision is `FalseAlarm` (a red-light blip) | Nothing | **Y** | The 5 s stillness rule exists for this. Do not loosen. |
| **A3** Decision is `Unclear` — 90 s with no verdict (`:79`) | Prompt "what should I do?" | **N** | Fires when signals are weak, which correlates with being indoors — i.e. at home. Should consult the last known position first and stay silent if it is the home zone. |
| **A4** Parked, **no fix available** (`:101`) | `markNotOutside()` then prompt | **N** | **The reported bug.** Also writes `parkedOutside = false` on a guess — see D2 for why that is the dangerous half. |
| **A5** Parked, zone is Home / free zone / free street (`:109`) | Status only, no prompt, offers permit back | **Y** | This code is correct. It is simply not reached when there is no fix. |
| **A6** Parked in a paid zone, auto-claim **off** (`:121`) | Prompt, unless the permit is already ours | **Y** | "Ask me" should not mean "ask me things I already know". |
| **A7** Parked in a paid zone, auto-claim **on** (`:125`) | Claim silently | **Y** | The core behaviour, working as intended. |
| **A8** Claim blocked — other car parked outside holding it (`:126`) | Prompt "claim anyway?" | **Y** | Correct to ask. Taking it strands their car. |
| **A9** Permit already on our own plate (`:151`) | Ongoing status, no prompt | **Y** | Fixed in v0.4; still correct. |
| **A10** Holder cannot be read (network down) (`:156`) | Prompt | **Y** | An unanswerable question beats a wrong assumption. |

## B. The zone resolver — `ZoneResolver.kt`

| Condition | Current action | Correct? | Notes |
|---|---|---|---|
| **B1** Point falls in the home circle | `ZoneInfo.Home` → no claim, no prompt | **Y** | **The logic is right.** The home-zone bug is A4 upstream, never this check. Worth stating plainly, since this is where we both looked first. |
| **B2** Point falls in a hand-marked free zone | `ManualFree` → no claim | **Y** | Hand-placed beats geometry, deliberately. |
| **B3** Point in a tariff polygon | `Paid(area)` → claim path | **Y** | But see E1 — the polygons are the wrong shape. |
| **B4** Point in no polygon at all | `FreeStreet` → no claim | **Y** | Correct for Amsterdam, wrong outside it — the app has no data beyond the city and will call Utrecht free. |
| **B5** Tariff data missing or corrupt | `Paid(null)` → claims anyway | **Y** | Deliberate bias: assume paid, because claiming a free permit costs nothing and not claiming costs a fine. |

## C. The collision guard — `ClaimGuard.kt`

| Condition | Current action | Correct? | Notes |
|---|---|---|---|
| **C1** Other phone has no state | Proceed | **Y** | Nothing to protect. |
| **C2** Other car not parked outside | Proceed | **Y** | |
| **C3** Other heartbeat older than 6 h | Proceed | **Y** | A stale heartbeat is not evidence they are still there. |
| **C4** Permit is not on the other plate | Proceed | **Y** | Nothing to strand. |
| **C5** All four hold | Blocked → prompt | **Y** | |

## D. Shared-state writes — `SharedSync.kt`, `ParkWorkers.kt`

| Condition | Current action | Correct? | Notes |
|---|---|---|---|
| **D1** Parked in a paid zone | Publish `parkedOutside`, **lat/lng**, accuracy, zone code (`SharedSync.kt:123`) | **N** | **Publishes raw coordinates to the other phone.** This is request #1. See below — it can be removed outright without losing anything. |
| **D2** No fix at all (via A4) | Publish `parkedOutside = false` | **N** | Tells the other phone it is free to claim, on the basis of a failed GPS read. Same root cause as A4 and the more expensive half: their car may be on a paid street. |
| **D3** Other phone forced a claim | Notify "taken over" (`:160`) | **Y** | Report of something that already happened. |
| **D4** Ask-first mode, other car still needs the permit | Prompt "hand it back?" (`:202`) | **Y** | Re-reads their state before asking. |
| **D5** Claim retries exhausted | "Gave up" notification (`ParkWorkers.kt:126`) | **Y** | Capped rather than notifying forever. |
| **D6** Server shows a different plate than requested | Mismatch warning (`ClaimPermit.kt:27`) | **Y** | Read-after-write verification. Keep. |

## E. Map and display

| Condition | Current action | Correct? | Notes |
|---|---|---|---|
| **E1** Car sits in a tariff area | Header names the area, rate now | **Y**, on wrong data | Rate regions up to 3 km wide, not parking zones. Needs the zone registry. |
| **E2** Correction moves the car and flips paid/free | Offer to claim or hand back | **Y** | Never automatic — the user just supplied better information than GPS had. |
| **E3** Correction beyond 300 m from detection | Refused | **Y** | Anchored to detection so it cannot be walked around. |
| **E4** `parked == true` but no location | Map says "no parked location recorded yet" | **N** | Technically true, actively misleading — the app *does* think you are parked. Should say so. |

---

## Notes on the other five requests

**#1 Per-user location privacy — there is no server to filter at.**

The app has no backend. Both phones read and write one Firebase Realtime
Database node with a shared URL and no per-user auth, so "filter server-side"
has nothing to filter at, and any client with the URL can read any path.

**The good news is that the fix is better than filtering: stop publishing
coordinates at all.** Row D1 sends lat/lng, but nothing on the receiving side
uses them — the guard reads `parkedOutside`, the heartbeat, and the plate
(`ClaimGuard.kt:22-25`). Coordinates are written and never read.

And request #3's comparison does not need them either. Comparing *tariffs* needs
each side's **rate**, which each phone can compute locally from its own position
and publish as a number. Neither phone ever learns where the other is, the
comparison still works, and no server is required. That satisfies #1 and #3
together, which is why they belong in the same release.

If real enforcement is wanted later, Firebase security rules plus per-user auth
is the route — but not publishing the data beats guarding it.

**#2 Live location while Bluetooth-connected — confirmed, with the guarantee you
asked for.**

Explicitly confirming the requirement: **only the disconnect-moment location will
be able to trigger a zone lookup, a permit switch, or a tariff purchase.** The
way to make that structural rather than a promise is to keep the live stream in a
type that the claim path cannot accept — a `LiveLocation` that is never a
`ParkedLocation`, so a stale timer or a race cannot feed driving positions into
claiming, because it would not compile. Tests will assert the claim path is never
invoked while connected.

**#3 Dual-location comparison — this reverses an earlier decision, deliberately.**

Tariff comparison was dropped earlier in the project ("tariff data is
informational only"). Reinstating it is your call and I am not arguing against
it — but flagging it so neither of us finds the old note later and assumes it
still holds. It is also *more* feasible now than when it was dropped: v0.6.0
built the schedule engine, so "what does each car's spot cost right now" is a
question the app can already answer.

**#5 Extendable tariff table — mostly built already, in v0.6.0.**

`TariffWindow` is data-driven with multiple bands per day and per-day-of-week
schedules, parsed from the bundled JSON with no per-zone code. What is *not* yet
true: adding a new zone still means editing a bundled asset rather than fetching,
and per-*day* granularity is limited by what Amsterdam publishes — it writes
`ma-wo,vrij,za`, so a genuinely per-day schedule is expressible but not present
in the source data.

**#6 Map specificity — waiting on your screenshots.**

---

## Suggested sequencing

Different from the order requested, for one reason: **#2 is the bug fix**, so it
cannot come after the things that depend on reliable parked-location capture.

- **v0.6.2 — the foundation.** Live location while Bluetooth-connected (#2),
  which repairs A3, A4, D2 and E4 together. Plus the honest "parked, location
  unknown" state so the map stops contradicting the app.
- **v0.6.3 — privacy and comparison.** Stop publishing coordinates (#1, row D1),
  publish a rate instead, and decide the permit from the two rates (#3).
- **v0.6.4 — whatever the first two get wrong**, per your plan, plus the map
  specificity work once the screenshots land.
