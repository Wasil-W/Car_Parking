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
| `v0.6.9` | The timetable row in force is the only one at full strength; the neighbourhood shows before expanding; pressed controls invert instead of shading; the parked line stops wrapping |
| `v0.6.8` | **A wrong permit password looked like success** — the request went out on the previous session's token. Also: a park recorded at a traffic light, a failed refresh that erased the permit holder, the timetable made reachable and merged into the header chip, and free zones became real Amsterdam neighbourhoods with the city's own boundaries |
| `v0.6.7` | **The plates were stored swapped on the second phone** — a claim could move the permit to the wrong car. `MyCar` replaced by a vehicle roster, nothing on screen changed, setup survives the upgrade. Permit-kind foundation, unread |
| `v0.6.6` | The full tariff week on tap, free days included; real zone and neighbourhood names bundled from the city's own data (107 zones, 518 buurten, 123 KB) replacing the geocoder inside Amsterdam |
| `v0.6.5` | Two-colour identity restored (it keyed on sync, not on plates); one cycling focus button; the layers toggle stops moving the camera; setup reads plates from the permit account; takeover alerts reach you mid-drive; a no-position park stops publishing a guess; screen and claim decision agree |
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

**Built, awaiting release** — branch `v066-vehicle-roster`, with item 4.

`PermitKind { VISITOR, RESIDENT, UNKNOWN }` exists, is stored on the permit
account beside the roster, and `boundByExceptionAreas` answers **true** for
UNKNOWN — the restricted default, as agreed above.

**The type is not in the response, as far as anyone can tell, and nobody has
looked.** The endpoint needs a live permit login, which this repo does not
carry, so no `getClientProduct` body has ever been read — the guess above is
still a guess. What shipped instead is the instrument:
`ClientProductLogInterceptor` prints the whole object in a **debug build only**,
and `adb logcat -s HandoffProduct:V` while pressing "Sign in and find my cars"
will settle in one minute what this could only reason about. It is debug-only
because that body carries every plate on the account.

Meanwhile `ClientProductResponse` reads one speculative field, `name`, and that
guess is free in the only direction that matters: an unknown key parses to null,
null is UNKNOWN, and UNKNOWN is treated as restricted. The app can be
uninformed here, never wrong expensively. When the real key is known it is a
one-line change.

**Nothing reads the kind yet**, and that is stated rather than hidden: there is
no `parkeerzones_uitzondering` data bundled, so there is nothing for it to gate.
The field is a foundation with a test, not a feature.

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

**Shipped in v0.6.8, and it took two goes.** v0.6.6 built the panel and it was
unreachable: the tap that opens it was matched against the *drawn* tariff areas,
which are an empty list whenever the overlay is off — and the overlay is off by
default. Tapping the very area the header was naming did nothing, silently.
Wasil, correctly, reported it as *"still no way to see the full timetable"*, and
the whole feature had shipped without anyone being able to reach it.

The lesson is not "test the tap". It is that **one word chose the wrong list**,
and a test asserting `mapHitAt(..., emptyList())` finds nothing passed happily
because it was describing the bug. What caught it was somebody trying to use it.

v0.6.8 also merged the panel into the header chip — *"my initial idea was that
the small thing expands instead of another one"* — so the rate is stated once
rather than twice in two shapes, and the chip grows downward from itself. The
expanded view names the **neighbourhood**, not the tariff code: `T13B` now
appears only as a last-resort heading when no name resolves at all.

### 3. Permit and paying as separate destinations — DECIDED: neither, for now
**Mockup:** <https://claude.ai/code/artifact/5e388efc-0ee1-4974-bcef-530a716f23de>

Drawn as two options and rejected on 2026-08-07 after seeing them. Option A put
the permit in a coloured centre button; Wasil turned it down for a reason the
mockup did not anticipate: *"because i want to eventually switch away from my
brother and i."* Putting identity colour in the most permanent place on screen
is the strongest version of a thing he intends to stop doing.

Neither option is being built. Four tabs stay. Revisit when paying exists and
there is a real second destination to justify a fifth — until then the paying
window is a rate, some hours, and a nearest-machine line whose data is not
bundled.

Kept because the reasoning cost something to reach, and the next person to
propose a centre button should read it first.

### 3b. The old plan, for reference
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

**Built, awaiting release** — branch `v066-vehicle-roster`, with item 1.

`MyCar` is gone. `Vehicle(id, plate, name)` in a `Roster` that cannot be empty,
plus `thisPhoneDrives: VehicleId?` on the device. Every two-car behaviour is
selected on arity: one hand-over button and a travelling dot at `size == 2`,
neutrals and a wordmark past two, because there is no third safe hue in this
palette. 474 tests pass.

**Nothing on screen changed, and that was checked rather than asserted.** The
permit screens were rendered on an emulator from both branches with identical
inputs and diffed: **eleven of twelve captures are byte-identical**, in both
themes. The twelfth differs only inside the map card, and master disagrees with
*itself* there between two runs — osmdroid tiles arrive when they arrive.

**Migration was the real work**, and it is read-through rather than rewrite-on-
upgrade:

- `my_car=WASIL` reads back as `VehicleId("wasil")`. The lowercase is the whole
  trick: the shared room has always been keyed on `MyCar.key()` =
  `name.lowercase()`, so **the wire format does not move** and an existing
  pairing keeps its Firebase node. The old key is retired on the first write, so
  two keys can never disagree.
- `wasil_plate`/`walid_plate` become a roster **in stored order, not sorted
  order** — sorting would have swapped the two identity colours on any account
  whose plates sort the other way.
- A park log written before the roster keeps its badges; `"WASIL"` lowercases
  into an id on the way in.

Verified on a device, not only in tests: an emulator was seeded with a real
v0.6.6 `park_state.xml`, the new APK installed **over** it, and History,
Settings, the car pairing, the home zone, the free zones and the sync URL all
came through unchanged — the screenshots differ only in the status-bar clock.

**One defect fixed on the way, and it was a live one.** The permit editor asks
for "your plate" and "the other car's plate", but the store put the first in the
*Wasil* slot whichever phone was typing. On Walid's phone the two were therefore
swapped, so a claim would have moved the permit to the wrong car. The roster
orders cars by plate — the one thing both phones read from the same account and
so agree on without talking — and records which of them this phone drives.

**Two deliberate departures from the design document**, both worth arguing with:

- **`Vehicle` carries no Bluetooth field.** `USER-MODEL.md` asks for one. The
  MAC is a fact about *this phone* and the stereo it rides with; neither brother
  ever learns the other's device, and a roster rebuilt from a shared permit
  account could silently drop the pairing that makes detection work. It stays on
  `ParkStateStore`. Moving it is dimension 2(b) — one phone, several cars —
  which is not being built.
- **The roster is built from the plates the user *picked*, not from every entry
  in `vrns`.** `permit.py` records why: *"the third plate belongs to an inactive
  vehicle and is deliberately not selectable"* on Wasil's own permit. Reading
  `vrns` straight in would put a car nobody drives on screen and tip the arity
  past two, dropping both brothers' colours.

**Still hard-coded, and openly so:** the seed roster's two names are "Wasil" and
"Walid". They are now one constant with a comment rather than an enum spread
across thirty files, and `USER-MODEL.md` explicitly allows defaulting them to
the same strings so nothing on screen moves. Renaming needs UI, which needs a
mockup — and, by the same document, a third car with a name attached first.

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

**Built, awaiting release** — branch `v066-zone-registry`. Both collections are
bundled as `amsterdam_zones.json` (123 KB, against the tariff file's 631 KB):
rings re-encoded as polylines at full source precision, nothing simplified, so
no boundary moves. `ZoneRegistry` resolves a position to a permit zone and a
neighbourhood, and every screen that names a spot now goes through it, with the
geocoder kept as the fallback outside Amsterdam. The map header reads "Centrum
/ Waterloopleinbuurt" and "Noord / NDSM terrein" — stadsdeel over buurt, which
is [`USER-MODEL`](USER-MODEL.md)'s two-level label finally said literally.
**The claim decision is untouched**: `ZoneResolver` still reads the tariff
polygons alone, asserted by a test rather than promised.

Checked side by side on the emulator against the build before it, which is the
only way the difference is arguable:

| Where | Before (geocoder) | After (bundled) |
|---|---|---|
| Waterlooplein | Amsterdam-Centrum / Waterlooplein | Centrum / **Waterloopleinbuurt** |
| NDSM | Amsterdam-Noord / Ms. van Riemsdijkweg | Noord / **NDSM terrein** |

NDSM is the case that settles it: the geocoder names a third of the city and
then a street nobody uses, where the buurt layer says the thing you would say
out loud.

**Three things it does not settle**, all on the top line and all for Wasil:

1. **The "Amsterdam-" prefix is gone** — "Centrum", not "Amsterdam-Centrum".
   Not an oversight: the city publishes the bare names, and there is no field
   distinguishing the seven stadsdelen that take the prefix from Weesp, which
   is a town and does not. Inventing it would make "Amsterdam-Weesp".
2. **The big line may be the wrong one.** The specific name is what he asked
   for and it is in the small grey line, because his own wording put the larger
   area on top — *"Amsterdam Noord and then a bit smaller underneath it
   Molenwijk"*. Implemented as recorded rather than as preferred. Swapping them
   is a mockup, not a patch.
3. **The street is no longer shown inside Amsterdam.** "Ms. van Riemsdijkweg"
   was arguably the more useful line when walking back to the car, and a buurt
   name is not a substitute for it.

What it left open beyond the header: the 107 permit zones are bundled and
resolved but named nowhere on screen, because *where permit parking applies* is
one step from *is the permit valid here*, and that step is item 1's. The paying
window (item 3) is the screen they belong on.

### 7. Free zones are neighbourhoods — SHIPPED in v0.6.8

**Source:** Wasil, 2026-08-08, pointing at the council's own map: *"do you see
the molenwijk. We could do that we can put those for the free zones, with
outline."*

The complaint it answers had been standing for a while: home zones and free
zones **felt identical** — same circle, same radius slider — and sizing an area
by dragging over a map is not precise. His answer is better than the one this
file was carrying (which guessed free zones might split by size): an area has
published edges, so nothing needs sizing at all.

A free zone is now one of Amsterdam's 518 buurten, with the city's boundary,
picked by tapping the map once and confirming. **The home zone stays a circle**,
30–200 m, per `BACKLOG.md`'s locked decision — and that difference is now the
whole distinction rather than an accident: *a home is a point you own, a free
zone is an area you know about.*

The data cost nothing: `amsterdam_zones.json` has been bundled since v0.6.6 and
`ZoneRegistry` already resolved a point to its neighbourhood. This was selection,
drawing and containment, not integration.

**Two decisions worth arguing with:**

- **The confirmation states the area's size**, at full strength rather than as a
  footnote — *"0.17 km² · the whole neighbourhood"*, then *"The permit will never
  be claimed anywhere inside it."* Marking a buurt free switches off claiming
  across all of it, buurten run from a few streets to most of a stadsdeel, and
  the failure is **silent**: what goes wrong is that nothing happens, which is
  exactly what a fine looks like beforehand.
- **Free zones saved before v0.6.8 are dropped, not migrated.** Cleared with
  Wasil — *"Dont really need them as i only have one for my home zone, 1sec
  fix"*; his one circle is the *home* zone, which is untouched. They are filtered
  out on read rather than left listed, because a row in "Your zones" that the
  claim decision quietly ignores is worse than no row.

### Amsterdam-only, deliberately — stated as of v0.6.8

Worth writing down because it has always been true and was never said: **this app
works where Amsterdam's published parking data reaches, and nowhere else.** The
tariff areas are Amsterdam's, the 518 buurten are Amsterdam's, the permit is an
Amsterdam visitor permit.

Free zones were the one feature that happened to work anywhere, and only because
a circle does not need to know what city it is in. v0.6.8 ended that, and the
scope is now consistent rather than accidentally broader in one corner. Wasil on
the rest of the country: *"Utrecht will get its own update hahaha."*

**Coverage is containment, never a name.** His own framing: *"just where the
amsterdam jurisdiction goes over. so all the places on the map we got from
amsterdam vergunning parkeren."* So "are we in scope here?" is `ZoneRegistry`
returning a match for a point — testable, with no latitude ranges and no
hard-coded city name. This matters at the edges: **Weesp** is in the municipality
and in this data while not taking the "Amsterdam-" prefix, which is the same trap
item 6 hit when it declined to synthesise that prefix. Anything checking scope by
string would be wrong exactly there, so the refusal copy talks about *this spot*
rather than about a city.

---

### Identify the car by its Bluetooth address, not by whose phone it is

**Wasil's idea, 2026-08-07**, and it is better than the roster work that
preceded it: *"in the way we have mapped out the car1 car2 to a name plate, we
also could connect the bluetooth address to each car and then based on that
check which car needs the permit."*

**It removes the question rather than answering it.** The app currently asks
"whose phone is this" so it can infer which car you drive. But the car announces
itself: a phone connects to *that* stereo's MAC, so the pairing already says
which car it is riding in. Store the MAC **on the vehicle** and the app knows
which car needs the permit without anyone being named.

This also settles the thing v0.6.7's roster deliberately left open. That work
kept `carMac` on `ParkStateStore`, reasoning the MAC is a fact about this phone
and its stereo. That reasoning holds for *one* phone's pairing, but it misses
this: with a MAC per vehicle, "which car am I in" stops being a stored answer and
becomes an observation, and observations do not go stale when someone borrows a
car.

**Why it matters beyond tidiness.** Wasil: *"i want to eventually switch away
from my brother and i."* Names are the last two-person assumption in the app.
The Bluetooth address is the one identifier that is about the *car*, needs no
agreement between phones, and cannot be typed in wrong.

Open, and worth thinking about before building: a car with no Bluetooth, two
phones paired to the same car, and what happens on a phone that has never
connected to any of them.

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

## v0.7.0 — the design release

Decided 2026-08-08: **v0.6.9 took the fixes small enough to make without
drawing first; everything left is design and goes here.** v0.7.0 carries no bug
fixes of its own — if one arrives it gets its own patch, so this release is not
held hostage to it.

**Mockup first, per `CLAUDE.md`.** And a new option worth using: *AI-powered
artifacts* and *inline visualisations* are enabled on this account, so a mockup
can be **clickable** rather than a picture of a screen. Wasil, listing what he
had been thinking about: *"interactive ui mockups (maybe external app)."* A
prototype you can tap through answers "is this crowded" far better than two
static frames side by side — which is the exact question three of the items
below turn on.

### Carried from v0.6.9's feedback

- **The timetable is still crowded.** He floated a row per day — ma/di/wo/do/vr/
  za/zo — and doubted it in the same breath: *"maybe that is too excessive. But
  this seems to be too crowded what we now have."* Both shapes get drawn; the
  doubt is the useful part, because it means neither is obviously right.
- **Zone editing takes too much of the screen.** The outlines are right —
  *"the dotted lines are amazing"* — the editor card around them is not.
- **A thinner dotted line for tariff sections** while that layer is on, so the
  sections read without competing with zone outlines. His idea.
- **Zone outlines more noticeable**, without shouting.
- **A smoother, more elegant expand animation** for the timetable.

### Also his, from 2026-08-08

- **Force a refresh whenever the app opens.** Recommended yes, and v0.6.8 is
  what makes it safe: refreshing more often is only an improvement once
  *failing* is harmless, and a failed refresh now keeps the last known plates
  and says so rather than blanking. Worth confirming before building.
- **Optimal performance.** No specific complaint attached yet. Worth a real
  measurement before any change — the app now bundles 740 KB of geometry and
  parses it at start, which is the obvious suspect and may well be innocent.
- **Automated messages.** Unclear scope. Could mean scheduled notifications
  (*"you have been parked 3 hours"*), or something about how the two phones
  talk. Ask before designing.

### Not the app

**Remote control** and **New Chat** are about how Wasil and the assistant work
together, not about Handoff. He is opening a separate conversation for remote
control. Recorded here only so they are not mistaken for product scope.

## Open questions needing Wasil

0. **What does `getClientProduct` actually return?** Still unknown — it needs a
   live permit login, and no real response has ever been captured. The claim in
   this file that a permit type is "very probably in the same JSON" is a guess
   and is still a guess. v0.6.7 ships the instrument rather than an inference:
   on a debug build, `adb logcat -s HandoffProduct:V`, then Settings → the
   permit row → "Sign in and find my cars". One minute settles it.

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
| "`sealAtDisconnect` stops a Bluetooth blip that reconnects mid-detection" (comment in `ParkDetectionUseCase`, v0.6.2) | **Half true, and the wrong half.** The seal only ever guarded the *fallback* trail, reached when no live fix exists. At a traffic light in the open the GPS answers fine, so a fresh fix took the other branch and was never checked against the link at all. That is how v0.6.8's stoplight park happened. The comment was carried for three releases stating a guarantee the code did not make. |
| "Storing permit credentials that turn out to be wrong is harmless and recoverable" (`MainViewModel.findPlates`) | True only while nothing could tell they were wrong — which was itself the bug. A working install re-typed with a slip was left signing in with a rejected password on every background claim. v0.6.8 rolls back a refused pair. |

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
