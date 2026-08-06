# Timeline — what shipped, what is next, what is parked

**This file is the single source of truth for where the project is.** Every
other document under `docs/` is a deep-dive that this one points at.

## Rules for whoever reads this (human or AI)

1. **Read this before planning or building anything.** It is the only file that
   knows both what has shipped and what was decided but not yet built.
2. **Update it as part of every release**, in the same commit as the version
   bump — not afterwards. A release that did not update this file is not
   finished.
3. **When an idea arrives, put it in "Planned" or "Parked" here first**, even if
   it is going straight into the next version. Ideas that live only in a
   conversation are lost the moment that conversation ends. That is the failure
   this file exists to prevent.
4. **When a plan turns out to be wrong, say so here** rather than deleting it.
   A retracted item is cheaper than the same wrong idea arriving twice — this
   has already happened once (see v0.5.4).
5. **Design documents are not implementations.** If something lives only in a
   `docs/` file, it belongs under "Planned" or "Parked", never "Shipped".

---

## Shipped

| Version | What it did |
|---|---|
| `v0.1` | Switching the permit between two plates |
| `v0.2` | Automatic park detection (Bluetooth + activity + GPS) |
| `v0.3` | Shared state between two phones, with a collision guard |
| `v0.3.1` | The Handoff identity: name, two-arc mark, colour system |
| `v0.3.2` | Dark-mode hotfix — black-on-black text everywhere |
| `v0.3.3` | Navigation and layout: tabs, Settings rows, map on the main screen |
| `v0.3.4`, `v0.3.5` | Bugs found in real driving |
| `v0.4` | Tappable notifications, zones on the map, walking directions, addresses |
| `v0.4.1` | The quietening pass: typography, contrast, corner radii |
| `v0.4.2` | Cached-fix fallback so a failed GPS read stops erasing the pin |
| `v0.5.0` | Correctable parked pin; tariff areas drawn on the map |
| `v0.5.1` | The overlay made legible — the boundary was 3 km away and invisible |
| `v0.5.2` | Map screen tidied; correction cap anchored so it cannot be walked around |
| `v0.5.3` | The walk back to the car drawn in-app |
| `v0.5.4` | Two-level place names in the header; notification copy calmed; two label-string couplings typed |
| `v0.6.0` | Live tariffs — what this spot costs *right now*, not its timetable |
| `v0.6.1` | The obligation/settlement split made visible: "This spot" above the permit card |
| `v0.6.2` | Live location while Bluetooth-connected, sealed at disconnect; coordinates removed from the wire; rate comparison built |
| `v0.6.3` | Zone code and rate removed from the wire, comparison machinery deleted. `PhoneState` is three fields, all of them read. No UI change |
| `v0.6.4` | **The background-location permission was never requested** — the cause of three long-standing symptoms. Map controls float; locate-me exists; the parked pin survives the drive; the app stops calling a one-car, no-permit install broken; a History tab; "Remove permit" |

---

## Planned — decided, designed, not yet built

Ordered by value per effort. Nothing here is implemented.

**Mockups come first.** Every UI release opens with a published mockup — see
`CLAUDE.md`, "Draw it before you build it". v0.6.4's:
<https://claude.ai/code/artifact/9ac1d27a-05ea-4735-953d-d43443029b4c>

### 1. Read the permit's *type*, not just its plates

**The 66 exception areas do not affect Wasil.** Checked with him 2026-08-06:
`parkeerzones_uitzondering` lists 66 valid areas stating *"uw parkeervergunning
geldt niet van ma t/m za 9.00 tot 18.00 uur"* — Haarlemmerdijk, Javastraat, PC
Hooftstraat and other shopping streets. Those bind **regular resident permits**.
His visitor permit has access at all times, so nothing is currently mis-stated
and no fine is being risked.

It was flagged as a probable fine for one turn before he corrected it. The
caveat was attached at the time and the check took one question — which is the
process working, not a near miss.

**What it leaves behind is a real requirement**, and it is Wasil's own framing:
if the app extracts plates from the permit account, it should extract the
**permit type** too. The app has no concept of one today — it assumes "there is
a permit, permits work". That assumption is true for him and unverified for
anybody else, and it is what decides whether those 66 areas are a hazard or
noise.

**Likely already in a response we fetch.** `ClientProductResponse` declares a
single field, `vrns`, but the endpoint is `getClientProduct` — the *product* is
the permit, so its name and type are very probably in the same JSON, discarded
unparsed. The same shape as the plates: read what is already arriving before
building anything new.

Belongs with the roster work (item 4), which is where the account is read.
Until a permit type is known, the honest default is to assume the **restricted**
kind, because that is the direction that cannot cost a fine.

### 2. The full tariff table, when you tap an area
**Source:** Wasil, 2026-08-06: *"maybe i am curious about tommorows rate then i
don't see that."* Correct, and it is a gap this project created rather than one
it inherited.

v0.6.0 replaced the timetable with a single live line, arguing that
`ma-wo,vrij,za 09-19 · do 09-21` was a puzzle rather than an answer. That was
right for the header. The mistake was **deleting the full view instead of
moving it** — so "what does this cost tomorrow" now has no answer anywhere.

Tapping an area is where it belongs: the header says *now*, the tap says
*everything*. Something like:

```
T13B · Basistarief TC3
ma–za   09:00–24:00   €5,37/h
zo      free all day
```

**No new data.** `TariffArea.windows` already holds every band with its rate and
days, parsed at app start. This is formatting, and a panel to put it in.

Do it after v0.6.5, which is editing the same screen.

### 3. Permit and paying as separate destinations, and what sits in the middle
**Source:** Wasil, 2026-08-05. **Mockup first** — see `CLAUDE.md`.

Two parts, and they arrive together because both land on the tab bar.

**Separate windows.** The obligation/settlement split currently shows as one
strip above the permit card (v0.6.1). The full version gives each half its own
destination: *what this spot demands* and *how it is being settled*. The paying
window is useful before paying exists — rate now, when it goes free, and once
the zone registry lands, the nearest machine. That is the "informer" app, and it
is honest rather than a placeholder.

**A centre action button**, as E-Flux has. Wasil's own test: *"if we find a
usability for it"* — and that is the right bar, because E-Flux's works only
because "Charge Now" is a real primary action.

The strongest candidate is **"I parked here"**. The app has no manual way to be
told this. Detection can miss entirely — Bluetooth off, someone else drove, you
walked away — and today there is no recovery except waiting or correcting a pin
that was never dropped. It is always relevant, and it is the one thing the app
cannot currently be told.

Rejected candidates, with reasons, so they are not re-proposed: *hand over the
permit* is contextual and already has its own screen; *pay* does not exist yet
and would be a button that apologises for itself.

### 4. The vehicle roster refactor
**Source:** [`USER-MODEL.md`](USER-MODEL.md) recommendation 1.
Replace the `MyCar` enum with `Vehicle(id, plate, name, bluetoothDevice)` plus a
device-local `thisPhoneDrives`. **Nothing on screen changes.** Two-car behaviour
is selected on `roster.size == 2` rather than on the enum. Worth doing before
paying features arrive, so they never have to reason about two brothers.

Wasil arrived at this independently on 2026-08-05, asking for setup that does
not show two cars to someone who has one. Note the source document estimates a
day; `MyCar` reaches into detection, shared state, notifications and
presentation, so plan for more.

**The roster comes from the permit account, and the app already fetches it.**
Wasil's correction, 2026-08-06, and it is better than the RDW idea it replaced:
sign in once and the account states which cars it covers, so the user maps them
to names and never types a plate.

This needs no new integration. `ClientProductResponse(val vrns: List<VrnEntry>)`
has been read since v0.1 — the app pulls every plate on the account plus which
one holds a session, then uses only the second part and discards the list.

Two consequences: setup never asks how many cars there are, because `vrns.size`
already says; and the roster has a real source rather than one invented during
setup. It only covers permit holders — someone with no permit has no account to
read — but that user may need no plate at all, since the obligation half does
not use one.

(RDW-by-plate remains a fallback for a no-permit user who does want their car
named. Keep it apart from the other RDW finding: dead end for zone *geometry*,
right source for vehicle data.)

### 5. The onboarding reversal
**Source:** [`USER-MODEL.md`](USER-MODEL.md), "The onboarding sequence".
Show something true before asking anything. The app currently opens on a
four-field form. The permit moves from the first question to the fifth, asked at
the first park in a paid zone. Two steps become confirmations rather than
questions — the Bluetooth device, and the home zone after several overnight
parks in one place.

### 6. The zone registry
**Source:** [`v0.6-zone-registry.md`](v0.6-zone-registry.md), verified against
live APIs 2026-08-04.
- `parkeerzones` — 107 permit zones, all `VERGUNP`, real names, WGS84 via one
  `Accept-Crs: EPSG:4326` header. The RD trap is not a trap.
- `gebieden/buurten` — 518 neighbourhoods including `Molenwijk` and
  `NDSM terrein`, which is what the map header should be naming.
- **This is what fixes "the map is not specific enough per area."** That is a
  data problem: the reference apps draw points with cluster counts, Handoff
  draws 3 km polygons. Restyling cannot fix it.

---

## Parked — deliberately not being built

| Item | Why |
|---|---|
| Paid parking sessions, started by Handoff | Researched 2026-08-06 — see Open questions. Writing a parking right to the NPR is limited to accredited providers, so this is a commercial barrier rather than a technical one. **Replaced by**: say what is owed and hand off to the provider's app. |
| Payment details in the app | Prohibited regardless of the answer above. The provider's own flow owns this. |
| Email, accounts, user profiles | No reader exists. The app has cars and phones, not users — see [`USER-MODEL.md`](USER-MODEL.md). |
| Three or more cars | Not until a third car exists with a name attached. The roster refactor (4) is the whole insurance policy; more than that is buying a guess. |
| Home-screen widget | Waiting for the map work to settle. |
| Typing a tariff code by hand | Dropped in v0.5.0 design — nothing reads it. |
| **Deciding the permit by tariff** | Decided 2026-08-05. "The expensive spot keeps the permit" only pays off once the cheaper car can **pay instead**; today it gets a fine either way, so deciding by rate would change which brother is fined and nothing else. It was built and tested in v0.6.2 and **deleted again in v0.6.3** on Wasil's call — no unused machinery. It gets built against a working payment path, or not at all. Recoverable from git if that day comes, but likely to need different requirements by then anyway. |

---

## Open questions needing Wasil

*(`zoneCode` was the other one. Answered 2026-08-05: dropped. Wasil's reasoning
is worth keeping — for anything a person reads, the **name** of a place matters
and the code does not, which is also why the map header shows a neighbourhood.)*

1. **Can our app talk to a parking payment system at all?** — researched
   2026-08-06, and the answer is *probably not, and it does not matter as much
   as we thought.*

   **The app already starts parking sessions by code.** `ActivateResponse`
   carries a `parking_session_id`; every permit move opens a real session on
   Wasil's account. The capability is not missing — the paid variant is.

   **The barrier is not technical.** Every Dutch parking right is registered in
   the NPR, run by RDW. Reading anonymised data is open; *writing* a right is
   limited to SHPV participants — municipalities, parking companies and
   accredited providers — and SHPV is the procurement organisation for them.
   That is a commercial contract, not a developer signup.

   | Route | Verdict |
   |---|---|
   | Become an accredited provider | A contract plus handling other people's money. Not proportionate here. |
   | Partner API from EasyPark or Parkmobile | B2B and contract-gated. An email, not a plan. |
   | Drive a provider's own app with the user's login | The same shape as the permit automation — but that is a free permit on the user's own account. This is money, on someone else's terms, in a flow that breaks on their next redesign. Advised against. |
   | **Say what is owed and hand off to their app with the zone ready** | Needs nothing, works today, touches no money. |

   **The last row is the one to build.** It is what the standalone screen already
   gestures at, with a handoff at the payment step rather than an impersonation
   of one.

   **Confidence:** no source states outright that an individual may not register
   rights. The picture is consistent but inferred. One email to SHPV
   (info@shpv.nl) would settle in a week what this can only reason about —
   Wasil's to send, and the only remaining way to close this properly.

---

## Retracted — wrong ideas, kept so they do not return

| Claim | Reality |
|---|---|
| "A pending decision never expires" (listed as a hazard for v0.5.4) | False. Expiry and a live freshness re-check were both built in v0.4 and wired. The item had been carried forward without re-reading the code. |
| "RDW is a dead end" | True **for geometry only**. For vehicle data by plate it is the right source and works. Keep the two apart. |
| "The `V` in tariff codes means *vergunning*" | It means round-the-clock. Every `V` code carries `ma-zo 00-24` and no other does. |
| "The RD-versus-WGS84 coordinate system is a hazard" | It is one HTTP header. |
| Tariff comparison is out of scope | Reinstated by Wasil 2026-08-04, and more feasible than when it was dropped, since v0.6.0 built the schedule engine. |

---

## Map of the other documents

| File | What it is for |
|---|---|
| [`USE-CASES.md`](USE-CASES.md) | Every situation and the action it should produce. Write the row before the code. |
| [`CONDITION-ACTION-AUDIT.md`](CONDITION-ACTION-AUDIT.md) | Every condition that currently leads to a user-facing action, assessed. |
| [`USER-MODEL.md`](USER-MODEL.md) | What the app needs to know about someone, and the two-brother question. **Design only.** |
| [`v0.6-zone-registry.md`](v0.6-zone-registry.md) | The Amsterdam zone data, verified against live APIs. |
| [`BACKLOG.md`](BACKLOG.md) | **Historical.** Decisions and diagnoses as they were made, including "Locked decisions — do not re-open without a reason". Superseded by this file for sequencing. |
| [`IDEAS.md`](IDEAS.md) | **Historical.** A ranked menu from 2026-07-30, with honest assessments of the bad ideas. Superseded by Planned and Parked above. |
| [`superpowers/specs/`](superpowers/specs/), [`superpowers/plans/`](superpowers/plans/) | **Archive.** How each release was designed and built, dated. Not maintained after the release ships. |
| [`TOOLING.md`](TOOLING.md) | Emulator, adb, and why no third-party MCP server has ADB access here. |
| [`inspo/`](inspo/) | Reference screenshots — E-Flux and Q-Park. |

---

## Appendix — learning the UI side

Kept from a deleted document because it is the only thing in it that was not
superseded. For Wasil, in the order worth reading:

1. **Refactoring UI** (Wathan & Schoger) — the highest-value one by far.
   Spacing, hierarchy, why greys look muddy. Most of what v0.4.1 did to this app
   is in that book.
2. **m3.material.io** — the colour-roles page especially. It explains *why* the
   slots exist, which is exactly the bug v0.3.1 shipped.
3. **Steve Schoger's design tips** — before/after pairs, one idea each. Builds
   the instinct to notice what is off before being able to name it.
4. **mobbin.com** — real app screenshots by flow. Use with a specific question
   ("how do five map apps do a bottom bar"), not for browsing.

What has helped this project most is not theory but Wasil pointing at a screen
and saying "this looks off" — which has caught more real defects than tests and
review combined. The reading turns that into "the label competes with the value".
