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

---

## Planned — decided, designed, not yet built

Ordered by value per effort. Nothing here is implemented.

**Mockups come first.** Every UI release now opens with a published mockup —
see `CLAUDE.md`, "Draw it before you build it". v0.6.4's:
<https://claude.ai/code/artifact/9ac1d27a-05ea-4735-953d-d43443029b4c>

**v0.6.4 is the UI release.** Items 1, 2 and 3 land together rather than in
separate passes, so the map chrome, the session log and the wider design arrive
as one coherent thing instead of a second pass partly undoing the first. Wasil's
framing, and it is the right one: *"rather something perfect than more and
imperfect"* — depth over width.

### 1. The standalone copy pass
**Source:** [`USER-MODEL.md`](USER-MODEL.md) recommendation 3.
"No permit" and "no sharing" already work in the code; only the Settings copy and
the `needsSetup` gate call them broken. Fixing that is the single largest step
toward the app standing on its own, and it introduces no new concepts.

### 2. Map controls from layout into overlay, plus a locate-me button
**Source:** [`USER-MODEL.md`](USER-MODEL.md) recommendation 8, from the reference
screenshots in [`inspo/`](inspo/).
Handoff spends a header row, a three-button card, a full-width button and an
attribution line on chrome. Both reference apps spend a floating search pill and
two circular buttons. **There is currently no way to re-centre the map after
panning.**

**Built, awaiting release — branch `v064-map-chrome`.** The map screen half of
this item is done: the controls are off the layout and onto the map as four
40dp circles bottom-right, "Walk to car" is a bottom-centre pill, the header and
the OpenStreetMap credit are overlays, and **locate-me exists**. Measured on a
1080x2400 device, the map goes from **64% of the screen to 83%** by area (71% to
83% by height). The mockup's "58% to 92%" was drawn on a shorter phone and did
not count the status bar and the tab bar, which are system chrome the map cannot
have. Not versioned, not in the changelog, not shipped — v0.6.4 is assembled
from several branches at the end.

### 3. The session log
**Also look at:** the permit website's own session history, which Wasil pointed
out on 2026-08-05 — it already lists his past sessions and is the closest thing
to a reference built for this exact permit. Needs a screenshot into `inspo/`;
reading it directly would mean signing in as him, which is not something to do.
**Source:** [`USER-MODEL.md`](USER-MODEL.md) recommendation 7.
Zone badge, address, time range, price — the Q-Park record. **Not blocked on the
payment question:** three of those four fields are obligation, which the app
already computes. The badge should carry *settlement kind*, making the
obligation/settlement split visible in history.

### 4. The vehicle roster refactor
**Source:** [`USER-MODEL.md`](USER-MODEL.md) recommendation 1.
Replace the `MyCar` enum with `Vehicle(id, plate, name, bluetoothDevice)` plus a
device-local `thisPhoneDrives`. **Nothing on screen changes.** Two-car behaviour
is selected on `roster.size == 2` rather than on the enum. Worth doing before
paying features arrive, so they never have to reason about two brothers. Note:
the source document estimates a day; `MyCar` reaches into detection, shared
state, notifications and presentation, so plan for more.

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
| Paid parking sessions | **Blocked on one unanswered question: can a session be started programmatically at all?** Real money and real liability, unlike the free permit. Nothing found so far touches it. |
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

1. **Can our app talk to a parking payment system at all?**

   Not "automatic versus manual" — those need the same thing and are a choice
   made *after* this is answered. The real question is whether Handoff can start
   a session itself, or whether the most it can ever do is tell you what you owe
   and send you to someone else's app:

   | | What it needs |
   |---|---|
   | Handoff starts a session on its own | Handoff can reach a payment system |
   | You tap a button in Handoff, it starts a session | **The same thing** |
   | Handoff shows the rate, you pay in Q-Park | Nothing — works today |

   If the answer is no, both of the first two vanish together and the app is an
   informer rather than a payer. Still useful, much smaller, and honest.

   This is research — reading what the providers expose — not a questionnaire.

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
