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
| `v0.7.7` | **The permit question after every park.** Not Bluetooth — detection reads position and activity from a *worker*, and with those permissions ungranted it can only time out and ask. Plus the Grant button that asked for one permission of four, the prompt that never said why, and a park with no position that was a dead end with no exit |
| `v0.7.6` | Eleven defects found by reviewing v0.7.5's own code rather than waiting for them — a parser that did not enforce its own promise, a crash waiting in the facility sheet, a dead function with a passing test, and eight smaller ones |
| `v0.7.5` | Amsterdam's 37 published garages and P+R sites on their own map layer, with what they cost in the operator's own words. See below |
| `v0.7.1` | Three things v0.7.0's own release review found after it was tagged: a round cap that undid the walking route's dashes, a header that gave panel width to a chip that could not open, and a chevron announcing a week it no longer opens |
| `v0.7.0` | **Bugs and design in one release.** Five wrong answers fixed — "all day" for 7 areas, boundaries that dropped their day, stepped rates shown as flat, a live line computed once and never again, timetable rows below the readability floor. The panel now says what happens next and the week moved one tap out. Every map line converted from device pixels to dp and given a hierarchy. The dark ground warmed |
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

## v0.7.7 — the question after every park — SHIPPED 2026-09-03

**Wasil:** *"i noticed the message pop up far more often than before and i dont
like that. i have to do it manually now everytime."* He attributed it to the
Bluetooth pairing. It is not, and the reason matters: `CarBluetoothReceiver`
returns before doing anything when the stored MAC does not match, so **a prompt
arriving at all is proof the pairing worked.**

**The mechanism, traced end to end.** `ParkDecisionEngine.decide` can reach a
verdict two ways — a confident activity sample, or two positions more than 4 m
apart. Both are read from a *worker*. Without `ACCESS_BACKGROUND_LOCATION` every
position read returns null; without `ACTIVITY_RECOGNITION` there are no samples.
So the loop runs the full 90 s into `Unclear`, `parkedFix` falls back to
`liveLocation` (another worker, also null), and `unclearPark` asks. Every park,
the same path. *"Doesn't detect"* is the timeout and *"always says parking
detected"* is the prompt — one failure, not two.

**And the remedy could not remedy it.** The health card's Grant used
`RequestPermission` — singular — fed by `needed.firstOrNull { !granted }`, so
four permissions took four presses of a visually identical button with no
indication more were coming. This is also the answer to his separate *"the
settings button + the way it is all being asked"* complaint: the same defect,
reported twice, from two directions.

**Three fixes, and one correction to this file.** `RequestMultiplePermissions`;
the prompt now names its own ignorance (*"Parked — but where?"*) and the likely
cause, but only when that permission is genuinely missing; and the door.

**Verified on the emulator with the permission revoked to match his phone.**
Reaching the decision screen needs the notification's `EXTRA_DECISION_ID` — a
plain `am start` shows the tabs, which is correct behaviour and worth knowing
for anyone seeding this state again.

### Still not settled, and it is his to settle

**Nobody has looked at whether those permissions are granted on either phone.**
Everything above explains the symptom and none of it proves the cause on his
device. One screenshot of Settings → Apps → Handoff → Permissions closes it. The
v0.7.7 fixes make the failure *recoverable and legible* either way; granting the
permission is what makes it *stop*.

## Paying is off the table — DECIDED 2026-08-31

**Wasil, after asking around:** *"i am not able to implement the paying system
into the app as that is a huge pain in the ass with legal stuff and a good
tariff connected with it… the least we can do is go as far as we can to mimick
it and finish the app for a moment when the moment hits."*

**This confirms Q1 rather than contradicting it.** The 2026-08-06 research
reached the same place from the other direction — writing a parking right to the
NPR is limited to SHPV-accredited providers, so the barrier is commercial and
not technical — and recommended *"say what is owed and hand off to the
provider's app."* Two independent routes, one answer. The question is closed.

**What this changes, and what it does not.** It does not change the app's
direction; that recommendation has been the plan since v0.6.0 and everything
built since has followed it. What it changes is the *definition of finished*.
Section D of [`USE-CASES.md`](USE-CASES.md) can never be completed here, so it
stops being a backlog and becomes a **specification held ready** — the rows stay
written, the obligation half stays honest, and the settlement half stops one
step short on purpose.

Concretely, "as far as we can" already has a shape and most of it exists:

- **What a spot costs, now and next** — v0.6.0, v0.7.0
- **What a garage or P+R costs instead** — v0.7.5, quoted from the operator
- **The obligation/settlement split** — v0.6.1, which is what makes the last
  step separable at all
- **What is left**: the session log as a record of what *was owed* rather than
  what was paid. [`USER-MODEL`](USER-MODEL.md) item 7 already argues this and
  notes three of its four fields are obligation, not settlement — **so it is the
  one substantial feature that was never blocked on paying**, and it is now the
  natural last piece.

**Do not build a fake payment flow.** Mimicking means going up to the handoff
and stopping, not drawing a button that pretends. A screen that looks like it
takes money and does not is the one thing worse here than a missing feature.

### The gemeente idea — Wasil, 2026-08-31, unexplored

*"mail the gemeente about this with the idea to make it an app that uses their
api (which we already do) and then use it for rented cars and maybe something
more into that direction or get the support of the gemeente to make it
standalone for the people to help them out."*

Recorded because it is the first idea in this project that changes what the app
*is* rather than what it does, and because ideas that live only in a
conversation are lost — which is what this file exists to prevent.

Three things that make it more plausible than it sounds:

1. **The app already runs entirely on the city's own published data** — 29
   tariff areas, 107 permit zones, 518 buurten, 37 facilities, all from
   Amsterdam's open data under CC-BY 4.0, with attribution. There is nothing to
   ask permission for that is not already granted; the ask would be for
   *support*, not access.
2. **It is a working demonstration, not a proposal.** Most people writing to a
   council have a slide. This has an APK, eleven tagged releases and a public
   repository.
3. **Rented cars is a genuinely different reading of the same machine.** The
   permit-switching core is "one entitlement, several vehicles, move it to
   whichever one is parked" — which is a fleet problem wearing a family costume.
   That is the first framing found that does not need paying to be useful.

**Nothing is decided and nothing is being built for it.** The honest next step
is one email, which is Wasil's to send — the same shape as the SHPV email under
Q1 that would have closed the paying question a month earlier.

## v0.7.5 — the garages, and the price they publish — SHIPPED 2026-08-31

**Wasil, 2026-08-11:** *"I want you to gather all data about all parking lots /
garages in amsterdam in order to add them too"*, pointing at the council's own
tariff map. Then, on what it is for: *"display the tariffs so people can know
how much it would cost to park in that garage. and then most of the time a
garage has real life tickets or licence plate scanners so you wont need to pay
in the app itself."*

That last sentence is the whole design. The app never touches money — it says
what a place costs and the barrier handles the rest, which is the same
*say what you know, then hand off* shape already chosen for paying (Q1 below).

**Held back deliberately.** Wasil: *"we dont release them until i have found
some bugs/fixes we need to fix."* `versionCode 29` is claimed, nothing is
tagged, and `v0.8.0` is queued behind it.

### What ships

**37 facilities, 12 KB** — 26 municipal garages and 11 P+R, from the gemeente's
`locaties.json`, the file behind the map he linked. **17 carry a published
rate**; the other 20 say plainly that they do not.

**Why not the 75 that exist.** Three registers publish Amsterdam facilities and
they disagree. Reconciled they give 75 distinct places — but the 28 municipal
entries in RDW's `PARKEERGEBIED` carry **a name and nothing else**: no
coordinate, and RDW's address table holds the council's own postbus rather than
the garage. Placing those means geocoding a name, and PDOK given `P+R RAI`
returns **P+R Muiden, 11 km away**; `Piet Hein` returns a restaurant. Measured,
not assumed. A pin is a claim about where something is, so the ones that ship
are the ones the council itself positioned. The other 38 return when each has a
verified location.

**Rates are quoted, never computed, and that is a reversal made mid-build.** The
register also publishes structured `intervalRates`, so the obvious move is to
evaluate them. They cannot be evaluated: P3 Mikado's hourly entry carries three
bands over *overlapping* durations — `1 per 24min [0,24)`, `1 per 25min [24,∞)`
and `1 per 20min [0,∞)` — which cannot all apply, and its own description says
the third. Summing them quoted **€130 for a day at a garage whose day ticket is
€30**. That would have been the most confidently wrong number this app ever
showed. So there is no garage rate engine; `RateLine.text` is the operator's
sentence, verbatim, and it stays in Dutch because translating a published price
risks changing a claim about money.

**The marker problem, and the answer.** Dutch parking signage is a white P on
blue, and blue means Wasil. Nine colours already carry meaning on that one
screen and [`USER-MODEL`](USER-MODEL.md) had already established there is no
fourth safe hue. So facilities take **no colour of their own**: a near-black
plate reusing `ZoneCandidate`, separated from everything by *shape and glyph* —
the same argument v0.7.0 made for the line hierarchy. A plate and not a
teardrop, because a teardrop here means *a point someone chose*.

**The layers button stopped being a boolean**, since two layers cannot share one
toggle and a button cycling four states is unpredictable. It opens the same
`DropdownMenu` the zones button already uses — a third disclosure grammar would
have cost more than the row. Both layers default off. Switching facilities on
does **not** move the camera: tariff areas are 3 km wide so they need the zoom
out, points are only useful near you, and yanking the map is the fault v0.6.5
removed.

### Corrections to this file's own earlier research

**The 2026-08-07 garage assessment was right about scope and wrong about
freshness.** It rejected garages partly on *"rates expired in February 2022, and
no field says which"*. That generalised from one record. The NPR index carries
`staticDataLastUpdated`, and **all 19 matched facilities were refreshed
2024-12-12 or later**; the matched tariff entries carry no `validityEndOfPeriod`
at all.

Its stronger objection — *a garage answers a question this app does not ask* —
still stands for this release, and is worth keeping. What changes it is the case
with no answer today: both cars in paid areas, one permit, and the second
brother's only options are strand your brother or do nothing. **A garage, and
especially a P+R, is a third answer that needs no payment integration.**
P+R Sloterdijk is `1,00 per 24u` against €8,05/h on a Centrum street. This
release is the dataset that makes that possible; it is not that feature.

### Seen on a screen, in both themes

Light and dark: the menu, both switches, plates over Centrum tiles, the sheet
with rates (Markenhoven → *"0,50 per 7 min" / "Dagkaart 47,50"*) and without
(Rokin → the admission line). The mode-independent marker colours hold.

**One edge, found by looking:** a facility directly beneath your own position is
**invisible** — the me-marker is larger than a 15dp plate and draws above it.
The z-order is correct and is not changing, but the facility you are standing on
is the one you cannot tap. It cost ten minutes here believing the layer was
broken.

**Not settled**, and carried rather than hidden:

- **Plate density at city zoom.** 37 markers are fine at parking zoom; zoomed
  out they clump around Centrum and Zuidoost, and nothing clusters or thins
  them. May need a minimum zoom before the layer draws.
- **Whether P+R deserves its own switch.** Folded into one row, and they are
  arguably a different product from an hourly garage.
- **20 facilities have no rate**, including Rokin, Marnix, Albert Cuyp, De
  Hallen and Mercatorplein.
- **Two disclosure grammars.** The tariff panel expands in place from its chip;
  this opens from the bottom.

**Mockup, published before the code:**
<https://claude.ai/code/artifact/ab68f7ce-cc42-4b07-a67d-e89f0a5c2736>

## v0.8.0 — the map that takes a moment, and the introduction — PLANNED

Two halves, and Wasil wants the second designed rather than assumed:
*"it should be somewhat of a introduction, figure out what the user wants and
what we can customize for him. we should brainstorm more about that."*

**The loading screen.** His words, and the thread behind them did **not** survive
the reinstall — searched across the six digests and the full 73 MB archive.
Settled by asking him: it is **map tile loading**, not the launch splash. The
complaint is old and never addressed — 2026-07-30: *"the loading of the map is
quite bad, initially when i wanted to add a map i wanted it to be kinda on the
main screen because then you see it instantly."* It was hit repeatedly during
v0.7.0's own emulator work (*"the map was still settling"*).

**The introduction.** This is not new ground and should not be started from
scratch: [`USER-MODEL`](USER-MODEL.md)'s "The onboarding sequence" already
designs it, and the 2026-08-07 mockup already drew it —
<https://claude.ai/code/artifact/5e388efc-0ee1-4974-bcef-530a716f23de>, §02
"First run, reversed". The governing idea is **show something true before asking
anything**: the obligation layer can state what the spot under your feet costs
without knowing one thing about you. The permit moves from the first question to
the seventh. Two steps become confirmations rather than questions — the
Bluetooth device, and the home zone after several overnight parks in one place.

What is genuinely open, and what the brainstorm is for: *what can be customised
for him* is not the same question as *what must be asked*, and this file has no
answer to the first one. Planned item 5 is the old framing; treat it as input.

## v0.7.0 — the big one: bugs *and* design — SHIPPED 2026-08-11

**What actually shipped**, in the order it was built. Everything below the
"Superseded" heading is the reasoning that got here, kept because the reasons
are the useful part.

| | |
|---|---|
| Five wrong answers | "all day" for 7 of 29 areas; boundaries that dropped their day; stepped rates shown as flat; the live line computed once and never again; timetable rows at 2.91:1 |
| The panel | Says what happens next as a clause; the week moved into a sheet one tap further; no chevron where nothing opens |
| The map | Every line in dp instead of device pixels, with a weight/texture hierarchy; tariff outlines thin and dotted, no fill; taps gated on the layer being on |
| The palette | Dark neutrals warmed; a third hue for `primary` drawn and declined |
| Motion | The open is one movement instead of four properties at four rates |
| Two dead things | The Now-tab map preview never fired its click; the radius Slider was justified by a comment that was false |

**Two mockups, and the second one earned its keep.** Three timetable shapes were
designed independently and judged by nine reviewers; all three died. A fourth
was assembled from the survivors — and putting *that* through the same pass
found it reinstating, one line below the fix for it, the exact defect
`clockAhead` was written to remove. The shape that shipped is the one after
that.
<https://claude.ai/code/artifact/4325d258-5aef-427d-a3a4-618de70e5f98>

**What is still open**, and none of it is hidden:

- **Light mode is unexamined.** Every palette argument here is made in dark,
  because that is what gets screenshotted.
- **The zone editor is only half done.** The Slider is gone (48dp) and the card
  is tighter, but the full split into a home bar and an area bar — and the
  finding that the *home* card is the fat one at 261dp, not the free one — is
  not built.
- **The tile wash and the signage face** from the palette work are not built.
  The face needs a licence-checked file, which is not a code decision.
- **Shared edges on the dotted tariff outlines.** The 29 areas tile the city, so
  most boundaries are drawn twice and two dot runs will not agree in phase.
  Interior edges may read heavier than the city's outer edge, which is backwards.
- **The stepped collapsed line** — "from €0,10/h until 19:00" — is the longest
  string the chip can hold and is known to wrap at 158dp.

### v0.7.1 — what the release review found, after the tag

The v0.7.0 diff went to three reviewers before it was tagged. Their verdict was
"tag it", and the three things they called blockers — the uncommitted version
bump, timeline and slider removal — were committed while the review was still
running. One verifier added a consequence worth keeping: versionCode 26 was
already spent on v0.6.9, so tagging that tree would not merely have
self-reported the wrong version, it would have produced an APK Android refuses
to install as an upgrade, because equal versionCode is not an upgrade.

Their remaining findings arrived after the tag and are fixed here.

**The walking route stopped being dashed.** v0.7.0 set `strokeCap = ROUND` on it
and undid, one line later, the dash it was setting. A round cap extends every
run by half a width at *each* end, so `[2.0w on, 1.5w off]` paints as 3.0w on
and 0.5w off — six parts ink to one, a line with nicks in it. The mechanism is
spelled out in `Line.dot`'s own comment, where it is the whole point of using
ROUND; it was then applied to `dash` without being accounted for. Now BUTT, and
stated explicitly on every dashed stroke rather than inherited from a bare
`Paint`.

**The header gave panel width to a chip that could not open.** `chipExpanded`
still asked "is the week open and is an area selected" while the chip had moved
on to "does this area open anything". For T11V, T12V and T13V those differ:
expand a normal area, then select Centrum, and the header handed the chip two
thirds of the row while it stayed collapsed, wrapping the parked line into a
third of the width. It could not be undone either, because tapping such a chip
is a no-op by design. Both now call one `tariffChipOpens`. This is the same
desync v0.7.0 fixed *inside* the chip, missed one level up — which is the
argument for sharing the predicate rather than restating it.

**The chevron announced a week it no longer opens.** Its label still said "Show
the whole week" after the week moved into its own sheet behind its own row. On
an area whose week is a single row the panel it opens has no route to a week at
all. It says "Show what happens next", which is what it reveals.

**And the Firebase example.** v0.7.0 replaced the URL in step 4 with a
placeholder and left step 1 suggesting the project name it was derived from,
thirteen lines above — one substitution away. That example is gone. It does not
close the hole: the literal string remains in three commits and in v0.7.0's own
commit message, all immutable in a public repo. **Only rotating the database URL
closes it**, and whether the live project carries that name is Wasil's to check.

**Not seen on a screen:** the route fix. A clean install for the release smoke
test left no parked car to route to. The change restores the geometry that
shipped from v0.4 to v0.6.9 to within two pixels — `strokeWidth 12f`,
dash `[26, 18]`, BUTT — so it is a return to a known-good rendering rather than
a new one, but it wants an eye on a real walk.

**Superseded 2026-08-09.** This was "the design release", on the reasoning that
v0.6.9 had taken the fixes small enough to make without drawing first, so
everything left was design and a bug arriving later would get its own patch.

Wasil overruled that the next day: *"i said also v0.7.0 is a big update
containing both bug + design."* So v0.7.0 carries both, and the three defects
found while measuring for the mockup (below) go **into it** rather than into a
v0.6.10 ahead of it.

The original reasoning is kept because it is not silly — it just loses to the
fact that these particular defects are *in the thing being redesigned*. The
timetable's shape and the strings it renders are one decision, and splitting
them across two releases would mean designing a panel around sentences that are
about to change.

### The three defects, verified 2026-08-09

Found while costing the mockup, each re-derived from source and the bundled data
rather than taken on a reviewer's word. All three are wrong answers, not
polish.

1. **"· all day" is not all day.** `TariffSchedule.kt:73` only computes an end
   `.takeIf { active.endMin < 1440 }`, so a window ending at midnight reports no
   end and `MapZones.kt:367` renders "· all day". T17N charges 19:00–24:00; at
   Monday 20:00 the app says "all day" after being free since 06:00 — and it
   does not stop at midnight either, because the 00:00–06:00 band continues it.
   True answer: until 06:00 Tuesday. **7 of 29 areas** are affected (T12A, T12B,
   T13B, T14G, T16A, T16F, T17N). T11V/T12V/T13V genuinely charge round the
   clock, so "all day" is correct for those and must stay.
2. **The boundary loses its day.** `clock()` at `MapZones.kt:352` does
   `minutes % 1440`, while `tariffNow` deliberately scans seven days. T17N on a
   Saturday at 14:00 has its next charge on **Sunday 19:00, 29 hours out**, and
   the app prints "Free · from 19:00" — which reads as tonight.
3. **Stepped rates show only their first tier.** `TariffAreas.kt:85` cuts the
   key at `[`. T17_UB01 is `0,10[0-180];1,72[180-…]` and renders "€0,10/h", so
   nine hours reads as ninety cents against a real ~€10,60. T14_UA01 renders
   "€1,72/h" where it becomes €4,19/h after three hours. T17F is
   `1,72[…];1,72[…]` — genuinely flat, no defect.

**And one reported defect that is not one, recorded so it does not come back:**
"Free · no paid hours" is unreachable. All 29 areas parse to a non-empty window
set, so `TariffNow.Free(null)` is dead code. A design proposed rendering "Hours
unknown" for T14_UA01, which would have hidden that area's real 09:00–21:00
schedule. The adversarial pass caught it; the design pass did not.

**Mockup first, per `CLAUDE.md`.** And a new option worth using: *AI-powered
artifacts* and *inline visualisations* are enabled on this account, so a mockup
can be **clickable** rather than a picture of a screen. Wasil, listing what he
had been thinking about: *"interactive ui mockups (maybe external app)."* A
prototype you can tap through answers "is this crowded" far better than two
static frames side by side — which is the exact question three of the items
below turn on.

### First thinking, 2026-08-08 — for tomorrow's mockup, not decided

**The timetable's problem is that it is a table.** Wasil proposed a row per day
and doubted it in the same breath, and the doubt was right: seven rows where
there are now two or three is more crowded, not less. But the current shape is
not right either, so the useful question is what both get wrong.

A table answers *"what is the rate on Thursday at 3pm"* — a lookup. That is not
the question being asked. Standing at the car, the question is **what happens
next**, and a table makes the reader compute that from a grid.

Two shapes worth drawing instead, neither of them a table:

1. **A sentence.** *"€1,72/h until 19:00, then free until 09:00 tomorrow."* One
   line, no grid, and it is the actual answer. The whole week goes behind a
   "show the week" affordance for the rare time it is wanted. Note this is very
   close to what the header already says — which is either a sign it is right,
   or a sign the expanded state has no reason to exist.
2. **A week strip.** Seven short bars, one per day, shaded where charging
   applies. Dense but *scanned* rather than read: "when is it free" is answered
   by shape before any digit is parsed. Costs a legend, and legends are a
   warning sign.

The test for tomorrow: which one answers *"can I leave the car here overnight"*
without the reader doing arithmetic.

**The zone editor may shrink on its own.** Free zones became area-backed in
v0.6.8, so they no longer need a radius at all — the +/- and the slider exist
for the home zone. If the editor is split by kind rather than shared, the free
path collapses to a name, a size and a confirm, which is most of the complaint
gone without designing anything new.

**The thin dotted tariff outline is a hierarchy, and worth stating as one.**
Wasil's idea, and it generalises: zone outlines are *yours* and should be
heavier; tariff sections are *ambient* and should be thin and dotted. Weight
carries whose thing it is. That also stops the two competing, which is the
reason the outlines needed thickening in v0.6.8 in the first place.

**The animation is `animateContentSize()` on its default tween.** "Smoother and
more elegant" is concrete: a spring instead of a tween so the growth eases
rather than ramps, and the rows fading in rather than being clipped into view.
Small, and the kind of thing that reads as quality without being noticed.

**Build the mockup as a clickable prototype.** Three of the items above turn on
"is this crowded", and tapping through a thing settles that in a way two static
frames cannot.

### The mockup, 2026-08-09 — three shapes judged, all three killed

<https://claude.ai/code/artifact/4325d258-5aef-427d-a3a4-618de70e5f98>

Both shapes above were drawn, plus a third. Each was designed without sight of
the others, then reviewed by three readers with different jobs: **the street**
(standing at the car, deciding whether to walk away), **the hard data** (the
overnight area, the Thursday-late area, the stepped areas), and **the system**
(tokens, palette, map budget). Nine reviews; several re-implemented the proposed
logic and ran it over all 29 areas rather than trusting the design.

| Shape | Score | Killed by |
|---|---|---|
| Run-length agenda from now | 21 | **+36 % taller than what ships**, while claiming to answer crowding |
| Until / then, as a sentence | 19 | Names instants, never durations — "then €8,05/h" hides a 3 h window |
| Week strip, anchored at 06:00 | 16 | Writes "free" beside a day that charges; the 06:00 anchor fixes the charged night and splits the free one |

**The sentence was the right instinct and the wrong grammar; the week strip was
the right instinct and the wrong anchor.** Recorded because both were Wasil's
own two candidates from 2026-08-08, and the reasons they fail are not obvious
from looking at them.

**The fourth shape — what to build.** The kills do not overlap, so the parts are
separable:

- Keep the **run-length model**: stop rendering the week, render runs of state
  starting now, so the join across midnight happens once in code rather than in
  the reader's head. All three reviewers agreed this part is right.
- Keep the **day letter** on any boundary that is not today. Two characters, and
  it is the difference between "in five hours" and "in twenty-nine".
- Fix the footprint by **refusing to say the same thing twice**. The winning
  design states the current span as a row, then a clause, then a gutter time.
  Fold all three into the collapsed line, which is already there and already
  read.
- Show **one** upcoming span, not two or three. The collapsed line says when the
  current state ends; one more span says what follows. Together those answer
  "can I walk away, and will it still be free when I get back". More than that
  is week-browsing, and week-browsing is what the link is for.
- **"Whole week ›"** demotes the v0.6.9 table into a sheet rather than deleting
  it — *"maybe i am curious about tommorows rate"* still has an answer. Hidden
  when the week has one row.

Measured **123dp against the 129dp that ships**, drawn from `MapChrome.kt`'s
real padding and line boxes.

**Two cautions carried forward, both mine:**

- **I got the height wrong first.** The mockup claimed 123dp for a panel that
  actually drew 147dp — the arithmetic dropped the link row, and the browser
  caught it, not the reasoning. The published page now measures its own panels
  at load and writes the captions from the result. Same lesson as every other
  entry in `CLAUDE.md`: the screen settles it.
- **The stepped-area collapsed line does not fit.** "from €1,72/h until 21:00"
  wraps at 158dp, growing the chip by a row. Unsolved, and it is the string that
  decides whether this shape works at all.

**The fourth shape has had no adversarial pass.** It is assembled by me from the
survivors of three that each died. That is a weaker position than any of the
three had, and it should be judged before it is built.

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

### The palette, answered 2026-08-09

Wasil: *"Do you think we can have a different colour palette than the one we
currently have — sometimes i feel like it is too dull and dosent have any vibe
to it."*

**It is not dull, it is barely used.** Counted rather than judged: on the map
screen the tiles are roughly three quarters of the pixels and they are stock
MAPNIK. Of the rest, everything is a warm grey except the walk route and a
0.07-alpha tariff tint. Identity colour — six tokens per brother, argued out
over three versions — reaches exactly **two** elements, and neither is on that
screen.

`primary` is near-white `#DEDCD4`, and that was a side-effect rather than a
decision. The rule was *"identity colour never enters a generic `ColorScheme`
slot"*; it never said generic slots must be greyscale.

Four levers, three taken:

1. **Warm the dark neutrals** — `#171715`/`#201F1C`/`#2E2D28` →
   `#16130E`/`#221D15`/`#332B20`. Red exceeds blue by 2–6 points today, which is
   measurable and not perceptible. Contrast **improves**: 13.49:1 against 13.07:1
   for primary text, 5.66:1 against 5.48:1 for secondary, computed from the sRGB
   luminance formula. Warm not cool, because light mode is already cream and a
   blue-black dark would make the two themes feel like two apps.
2. **A 10 % fixed wash over the tiles.** The largest surface in the app is
   somebody else's palette. One overlay, mode-independent by construction, which
   is what the map-colour rule requires. Strength is a guess until seen on glass
   — v0.5.1 is the scar this could reopen.
3. **A signage face for numerals, times, rates and plates.** `Type.kt` is the
   most carefully argued file in the theme and then the *face* is the platform
   default. DIN is the voice of European road infrastructure and touches no
   colour rule. Body copy stays on the system face. Bundled, ~40–90 KB.
4. **A third hue for `primary` — declined.** Brass or violet are the only hues
   far enough from fine/alert/walk/tariff/zones. Violet is the Material default
   the theme spent effort escaping; brass sits three steps from Walid's
   terracotta and would read as *a third person* on a small control at arm's
   length. Identity must stay the only thing colour means — v0.6.5 already lost
   the two-colour system once.

**Still open:** light mode is unexamined (everything above is argued in dark,
because that is what gets screenshotted); the wash strength needs a device; the
specific font file is not chosen.

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
