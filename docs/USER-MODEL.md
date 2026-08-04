# The user model — what the app needs to know about you, and when to ask

Written 2026-08-05, at Wasil's request, after he raised the thing he keeps
circling back to:

> *"i think we should abolish the wasil walids car and do it like one user could
> have multiple cars (still thinking about this because for my brother and i its
> a fun and cool functionality so maybe we should finish the permit and then move
> up, i really dont know right now)"*

This document treats that ambivalence as the design problem, not as a
requirement waiting to be signed off. It asks, for every fact the app could
learn about someone: does knowing it change the **UI**, change the **data
model**, or change **nothing**? Most of them change less than they look like
they will, and saying which ones is the whole value here.

Nothing in this document has been built. Rows marked **BLOCKED** cannot be
decided until an outside question is answered, and each says which one.

---

## The recommendation, in short

**Do not abolish anything. Rename one thing, and defer the rest.**

1. **Replace the `MyCar` enum with a two-entry roster plus a device-local
   pointer.** `Vehicle(id, plate, name, bluetoothDevice)` in a list, and
   `thisPhoneDrives: VehicleId?` on the device. Nothing on screen changes. The
   hard-coded names go away, the two-brother behaviour stays, and the later
   general case becomes an extension instead of a rewrite.
2. **Keep every two-car behaviour, and select it on `roster.size == 2` rather
   than on the enum.** The one-button hand-over, the two-arc mark and the
   blue/terracotta pairing are not sentimental decoration — they are the
   *correct* UI when there are exactly two cars and one permit, because the
   permit can only move to one place. With three cars they stop being correct
   on their own merits, not because a general model outgrew them.
3. **Stop treating "no permit" and "no sharing" as broken setup.** Both already
   work in the code; only the Settings copy and the `needsSetup` gate say
   otherwise. Fixing that is the single largest step toward a standalone app and
   it is roughly a day's work with no new concepts.
4. **Ask for one fact on first run: the plate.** Everything else is inferred,
   deferred to the moment it is needed, or never asked at all.
5. **Never ask for email. Never store payment details in this app.** Neither has
   a reader today, and the second one is the payment provider's job even when it
   does.
6. **Do not build for three cars until a third car exists with a name attached
   to it.** The refactor in (1) is the whole insurance policy; buying more than
   that is buying a guess.

Cost of (1) through (3), roughly: one refactor day, one settings section, one
copy pass. Cost of *not* doing (1): every later paying feature has to reason
about two brothers who have nothing to do with it.

Three more, from reading the screenshots in [`docs/inspo/`](inspo/) rather than
from the model:

7. **Build the session log now.** Zone badge, address, time range, price — the
   Q-Park record. Three of its four fields are obligation, not settlement, so it
   works with no payment capability at all, and it is the honest form of "show me
   what I would owe". **Not blocked on Q3.**
8. **Move the map's controls from layout into overlay**, and add the locate-me
   button that does not currently exist. Handoff spends a header row, a
   three-button card, a full-width button and an attribution line on chrome; both
   references spend a floating search pill and two circles. This is the "map is
   not specific enough" complaint's neighbour, and it is cheap.
9. **"Not specific enough per area" is a data problem, not a drawing problem.**
   The references draw points with cluster counts; Handoff draws 3 km polygons.
   Restyling cannot fix that. The zone registry can, and this is the second
   independent argument for it.

---

## What "user" turns out to mean in this app

Worth getting straight before anything else, because the word is doing three
jobs at once and only one of them is real here.

The app has **no user**. There is no account, no profile, no login except the
permit site's — and that login belongs to the *permit*, not to a person. Look at
what is actually stored: a Bluetooth MAC, a plate, an enum saying which car this
phone drives, a home circle, a sync URL. Every one of those is about a **car** or
about a **phone**. None is about a person.

Even the enum admits it. It is called `MyCar`, and `MyCar.WASIL` is a car with
Wasil's name on it, not Wasil.

That is not an accident of implementation, it is the right model, and it is worth
stating as a rule:

> **The app's entities are cars and phones, and the binding between them is
> Bluetooth. It has no concept of a driver and should never acquire one.**

The reason is that "who is driving" is a claim, and the app cannot check claims.
"Which car did this phone just disconnect from" is a fact, and the app already
reads it. If Walid drives Wasil's car, no amount of user modelling helps —
Wasil's phone is at home and never sees the drive. Modelling drivers would add a
field that is wrong exactly when it matters.

So the honest translation of *"abolish the wasil walids car"* is not "add users".
It is **"stop hard-coding two car names"**, which is a much smaller job than it
sounds, and the rest of this document is about how small.

---

## The two-brother question, head on

### Is the identity worth keeping?

**Yes, and the reason is not sentiment.**

With two cars and one permit, the permit can move to exactly one place. That is
why `MainScreen` has one button and not two, and the comment in
`PermitPresentation.kt` already says so. It is also why the mark has two arcs
with a dot that travels between them: there is a single resource with two
possible homes, which is a shape that genuinely has a picture. Add a third car
and "hand it over" becomes a question — *to whom?* — and the one button has to
become a picker, the dot has nowhere unambiguous to sit, and colour-coding stops
working because nobody remembers that the third car is the teal one.

So the two-brother UI is not a special case bolted onto a general app. It is the
**arity-two** rendering of a general app, and it is the best one available at
that arity. That reframes the whole question: the thing to protect is not the
names Wasil and Walid, it is the behaviour that only makes sense when N = 2.

### Can a general model contain it as a special case?

Yes, cleanly, because the general model needs almost nothing the app does not
already have.

Today:

```kotlin
enum class MyCar { WASIL, WALID }
var myCar: MyCar?                                    // on ParkStateStore
data class PermitConfig(username, password, wasilPlate, walidPlate)
data class PlateOption(val label: String, val vrn: String, val car: MyCar)
```

Proposed:

```kotlin
@JvmInline value class VehicleId(val value: String)

data class Vehicle(
    val id: VehicleId,        // stable and NOT the plate — plates change, cars don't
    val plate: String,        // normalised, the thing the permit site and any payment needs
    val name: String,         // display only: "Wasil's car", "Golf"
    val bluetoothMac: String?, // how a phone recognises this car mid-drive
)

// The roster: what cars exist. Two entries today.
val vehicles: List<Vehicle>

// The pointer: which of them this phone drives. Device-local, never shared.
var thisPhoneDrives: VehicleId?
```

`MyCar.WASIL` becomes `vehicles[0]`. `myCar` becomes `thisPhoneDrives`.
`PermitConfig`'s two plate fields become the roster. `PlateOption` disappears —
it exists only to carry a label, a plate and an enum together, which is what
`Vehicle` already is.

Then every two-car behaviour is chosen on arity:

| Behaviour | Rule |
|---|---|
| One "hand it over" button | `vehicles.size == 2` — otherwise a picker |
| Two-arc mark with a travelling dot | `vehicles.size == 2` — otherwise the mark is a wordmark, drawn neutral |
| Identity colour on the hero card | `vehicles.size <= 2` — see the design-system section |
| Colour-mirrored hand-over button | `vehicles.size == 2` |
| "The other car" wording | `vehicles.size == 2` — otherwise the car is named |

That is a `when` on a list size, in one file, and it is honest: it says *this UI
is right for two cars*, which is true, rather than *this app is for two brothers*,
which is the thing Wasil is unsure about.

### What is genuinely lost, each way

**If we generalise (the recommended path), we lose:**

- **Exhaustiveness.** `when (car) { WASIL -> …; WALID -> … }` is checked by the
  compiler. `vehicles.first { … }` is not. That is a real loss of safety and it
  should be paid for deliberately: keep the roster behind a type that cannot be
  empty, and keep `thisPhoneDrives` resolving through one function that returns
  the `Vehicle` or fails loudly, rather than scattering `firstOrNull`.
- **A little charm**, honestly. "Wasil's car" printed at 26sp is nicer than
  "AB-123-C". The fix is free: keep the `name` field and default it to the same
  strings. Nothing on screen has to change.

**If we do not generalise, we lose:**

- **Nothing on the permit half.** The permit half is genuinely two-party.
- **Everything on the paying half.** And this is the finding that matters most:
  *all* of the pressure to abolish `MyCar` is coming from section D of
  `USE-CASES.md`, and **section D has no brother in it**. D1 is one person, one
  car, no permit. D4, D5, D6 never mention a second phone. The obligation layer
  — where am I, what is owed, until when — is anonymous by construction, which
  is exactly what the v0.6.0 split was for.

So the split already done has quietly answered most of this question. **Identity
lives entirely in the settlement layer**, and it touches four things: the hero
card, the mark, the hand-over button, and the shared room. Everything v0.6 is
building — the zone registry, tariff windows, session records — never needs to
know who anyone is.

That is why the recommendation is a rename rather than an abolition. There is
nothing to abolish. There is one enum in the way.

---

## The dimensions

Wasil named six and said there were probably more. There are. Here is the whole
set, with the verdict first.

| # | Dimension | Changes UI? | Changes data model? | Verdict |
|---|---|---|---|---|
| 1 | Single user vs multiple users | **Yes, by removal** | Barely | Already built. It is the *absence* of sharing that is mislabelled as broken |
| 2 | Single car vs multiple cars | **Yes** | **Yes** | The real one. Two questions hiding under one phrase |
| 3 | Permit holder or not | **Yes, by removal** | Almost none | A branch deletion, not a feature. This is row D1 |
| 4 | Home zone | No | **No** | Exists, works, and is arguably the same thing as a free zone |
| 5 | Email | No | No | **Do not ask for it.** There is no reader and never will be one here |
| 6 | Bank / payment | Unknown | No | **BLOCKED on Q3.** And it is not a user attribute at all — see below |
| 7 | Which phone this is | No | Renamed | Exists as `myCar`. Must stay device-local forever |
| 8 | Who is driving | No | **No — refuse it** | Unanswerable and unnecessary. Do not model drivers |
| 9 | City / country | **Yes** | **Yes** | Inferred from position, never asked. Row B5 already |
| 10 | Language | **Yes, free** | No | Android resources. The cheapest personalisation there is |
| 11 | Act, or ask | No | No | Already built. It is the auto-claim switch |
| 12 | Vehicle attributes beyond the plate | Later | Later | Mostly free from a public register — see below |
| 13 | Who pays, in a household | **Yes** | **Yes** | **BLOCKED on Q3.** Re-opens a question the backlog closed |
| 14 | What the other phone learns about me | No | **Yes — must shrink** | The privacy boundary. See the privacy section |

### 1. Single user vs multiple users — already built, and mislabelled

The single-user case is not a feature to add. It is the two-brother app with the
shared half switched off, and the code already handles it:
`UnconfiguredSharedStateStore` returns null for everything and swallows writes,
`ClaimGuard` proceeds when there is no other state (row C1 of the audit), and
`loadOtherStatus()` returns null when `!store.configured`.

What is wrong is only what the app *says* about it. `SettingsScreen` computes
`syncConfigured = syncUrl.isNotBlank()` into a health row, so a person with one
car and no brother is permanently told **"Setup incomplete"** with a rust warning
icon, for declining a feature they do not want.

**The fix is a copy and a condition, not an architecture.** Sharing becomes an
optional capability with its own row — "Sharing: off" — and the health check
stops counting it. That is the entire change for this dimension.

The one real gap is how a second phone would join *without* a permit. Today the
shared room is derived from the permit username:

```kotlin
fun roomIdFor(username: String): String   // SHA-256("permit-room:v1:" + username)
```

No permit means no username means no room. So permit-less sharing needs a
different key, and the QR-code idea already agreed in the backlog is exactly it:
generate a random room key on one phone, show it as a QR, scan it on the other.
That also fixes a smaller problem — a permit username is not a secret, and
anyone who knows it can compute the room path. See the privacy section.

### 2. Single car vs multiple cars — the real one, and it is two questions

These get run together and they are not the same:

**(a) How many cars are in the roster.** This is the arity question above. It
changes the UI a lot and the data model a little.

**(b) How many cars *this phone* drives.** This is new, and it is the one that
changes the data model. Today the car *is* the phone's setting: `carMac` and
`carName` live on `ParkStateStore`, one apiece. One phone, one car, by
construction.

The clean answer is already half-written in the backlog — *"let a user register
N cars and map a Bluetooth device to each"* — and it is clean because it needs no
picker and no guessing:

> **A car is identified by which Bluetooth device disconnected.** Move `carMac`
> off the state store and onto `Vehicle`. Detection resolves the vehicle from the
> disconnect event it already receives, instead of comparing against a single
> stored MAC.

That is strictly more information than the app has today, and it removes an
ambiguity rather than adding one. The fallback for a car with no usable Bluetooth
is the NFC tag already noted in the backlog, or a manual pick — but neither is
needed for the common case.

UI consequence: **plates become a managed list, not a setup field.** Both
reference apps agree on this — Q-Park has *Kentekens* as its own profile row,
E-Flux has a car/garage tab, ParkMobile allows five vehicles per personal account
with a nickname each. Today a plate is typed once in `SetupScreen` and is then
unreachable: there is no way to change it after setup without clearing the app.
That is a bug on its own terms — Amsterdam lets you change the plate on a permit
from the council's own portal, so a plate genuinely does change while everything
else stays the same.

### 3. Permit holder or not — a branch deletion

This one changes the *least* of any of them relative to how large it looks, and
that is the dividend of the obligation/settlement split.

The permit is one settlement method. Not holding one means that method is absent.
Nothing else about the app depends on it. The obligation layer already computes
`SpotDemand` from position alone and does not consult the permit at all
(`HandoffTabs.spotDemand`).

What must change is not code that exists but an assumption baked into two places:

- `MainViewModel.init` sets `needsSetup = true` when `credentialStore.load()`
  returns null, and `MainActivity` shows the setup wall instead of the app.
- `ParkOutcome.NotConfigured` surfaces as *"Finish setup first (credentials +
  whose phone in Settings)"*.

Both say **no permit means the app cannot work**. Row D1 says the opposite: show
the zone, the rate and the hours, and offer to settle it another way. So this
dimension is satisfied by deleting a gate, not by adding a mode.

One thing genuinely cannot be modelled yet. A permit's *scope* — city-wide or
district-limited, plate-locked or movable, hour budget or not — is a property of
the permit, not of the user, and none of it can be filled in until the
questionnaire comes back. The model should carry the field and leave it empty:

```kotlin
data class Permit(
    val scope: PermitScope,     // BLOCKED on Q1 — Wasil's is city-wide, that may be unusual
    val hourBudget: Hours?,     // known null for Wasil's; the council's other
                                // permit types do carry budgets, so the field is not dead
)
```

Worth recording, because it cuts against an assumption in the backlog: Amsterdam
publishes visitor-permit products that *do* carry an hour budget (the moving
permit is capped, and ordinary visitor permits are commonly capped per month).
The 2026-07-30 answer *"there is no hour budget"* is true of **Wasil's** permit
and should not be generalised to the field. Q1 is the right place to settle it.

### 4. Home zone — changes nothing, and may be one concept too many

It exists, it works, it is per-device, and it should stay per-device forever (see
privacy). The only interesting question is whether it belongs to the person, the
car or the household, and the answer is: it does not matter, because it is
already a circle on a map that each phone owns.

The finding here is a simplification rather than an addition. Compare the two
resolver outcomes:

| Zone | Action | Difference |
|---|---|---|
| `ZoneInfo.Home` | Don't ask, don't claim, don't pay | It has a name you did not choose |
| `ZoneInfo.ManualFree` | Don't ask, don't claim, don't pay | It has a name you did choose |

They are the same behaviour (rows B1 and B2). **Home is a free zone with a
special label**, and the only thing the distinction buys is that Settings can
say "Home zone: not set". A general model with N users at M addresses gets
simpler, not harder, if home is just the free zone marked `isPrimary`. Not
urgent, but worth knowing that this dimension costs nothing.

### 5. Email — do not ask for it

**The strongest opinion in this document.** The app has no server, no account, no
password reset, no mailing list and no receipts. An email field today would be
stored and never read.

That is precisely the mistake row D1 of the audit already caught in a different
costume: coordinates were published to the other phone and *nothing read them*.
The conclusion drawn there applies unchanged —

> *"not publishing the data beats guarding it"*

— and the collection-side version is: **not collecting it beats storing it.**

The only future moment email becomes necessary is a paid session that needs a
receipt or an account with a payment provider. At that moment the provider will
ask for it, in their own flow, under their own privacy policy, and Handoff should
let them. There is no version of this where Handoff holding an email address is
better than Handoff not holding one.

The one piece of counter-evidence — E-Flux's account screen does show a name and
an email in a header card — is answered in the reference-apps section below. In
short: that card is the visible tip of an account system Handoff does not have,
and copying it without the system would display a field back to the only person
who typed it.

### 6. Bank / payment — not a user attribute, and BLOCKED

Wasil flagged this as "maybe much later" and he is right, but there is a
structural point worth fixing now so that later is cheap.

**Payment is not something the user *has*. It is something a settlement method
*uses*.** Model it there:

```kotlin
sealed interface Settlement {
    data object NothingOwed : Settlement
    data class SharedPermit(val holder: VehicleId?) : Settlement
    data class PaidSession(val provider: ProviderId) : Settlement   // BLOCKED on Q3
}
```

The consequence is that the user model does not grow a payment field at all. If
Q3 comes back "no, a session cannot be started programmatically", nothing has to
be undone — one branch of a sealed interface is never constructed.

Two hard constraints, stated now so they are not decided under deadline later:

- **Handoff must never take raw card details.** Payment credentials go to a
  provider SDK or to Google Pay, and the app holds a token at most. There is no
  design in which typing a card number into this app is the right answer.
- **Nothing in section D or E of `USE-CASES.md` may be treated as designable
  until Q3 is answered.** `NEXT.md` is already blunt about this and it is right:
  if the answer is no, v0.6.0 becomes *"show me what I would owe, and let me pay
  it myself in one tap elsewhere"*.

### 7. Which phone this is — exists, and must never leave the phone

`myCar` today, `thisPhoneDrives` tomorrow. It is the most important single field
in the app and it is not on Wasil's list, probably because it feels like plumbing.

Two properties it must keep:

- **Device-local.** It is never written to the shared room and never inferable
  from it. The room stores state per phone key already; it does not need to know
  which human is holding which handset.
- **Changeable.** Phones get replaced and cars get swapped. It is already
  editable in Settings; keep that.

### 8. Who is driving — refuse to model it

Covered above. Stated here as a dimension so that it is on the record as
*considered and declined* rather than overlooked. A "drivers" table is the single
most tempting wrong turn available in this design, because every family-sharing
app has one. Those apps have one because they arbitrate *bookings* — who may take
the car on Thursday. Handoff arbitrates nothing of the sort. It reports what
already happened.

### 9. City and country — inferred, never asked

The app knows Amsterdam and nothing else. `ZoneResolver` returns `FreeStreet` for
any point outside the 29 bundled polygons, which is correct in Amsterdam and a
lie in Utrecht (row B4, and row B5 is the planned fix).

This is a genuine personalisation dimension — *which city's data do I need* — and
it is one that must be **inferred from position and never asked in a form**.
Asking "which city do you live in" on first run is the exact upfront-wall
anti-pattern, and it is also wrong for the person who parks in three cities.

Data model: the zone registry becomes keyed by city and cached per city, which
`v0.6-zone-registry.md` already anticipates. UI: an honest empty state — "no
parking data for here yet" — which is more useful than a confident "free".

### 10. Language — the cheapest thing on this list

The app is English-only. The users are in Amsterdam and both reference apps are
Dutch: *Parkeersessies*, *Producten*, *Geactiveerd*, *Geen transactiekosten*.

Android's resource system and the per-app language preference do all the work,
and the data model does not change at all. Deferred, but it is worth knowing that
this dimension costs one `values-nl/strings.xml` and zero decisions.

### 11. Act, or ask — already modelled

The auto-claim switch is this dimension in disguise: *do things for me* versus
*ask me first*. It generalises without change — a paid session would read the
same preference, and row C5 already establishes the rule that "ask me" does not
mean "ask me things I already know".

No UI change, no model change. Listed so it is not re-discovered as a new idea.

### 12. Vehicle attributes beyond the plate — mostly free, later

A `Vehicle` may eventually need more than a plate: electric or not (charging
zones, different tariffs), emission class (Amsterdam's environmental rules bear
on permits), size (garages).

The useful finding is that **almost all of it is free from a documented public
register**. RDW's open vehicle dataset is keyed by plate and returns `merk`,
`handelsbenaming`, `eerste_kleur` and `brandstof_omschrijving` from a no-key
endpoint. So typing one plate can produce "Volkswagen Golf, grey, petrol" without
asking a second question — which is also how a vehicle gets a recognisable name
in a list without a "nickname" field.

**Do not confuse this with the RDW dead end.** `v0.6-zone-registry.md` says, with
justification, that RDW is a dead end — that is about *parking geometry*, and it
stands. This is a different dataset answering a different question, and it works.
Both facts can be true.

Caution: it sends a plate to a third-party server. Fine for your own plate,
requested by you, once, cached. Not a lookup to run on plates you were not given.

### 13. Who pays, in a household — BLOCKED, and it re-opens something

The backlog closed the fairness question: no fairness ledger, not wanted. That
decision was made when the shared resource was **free**. The permit costs nothing
to hold, so there is nothing to be fair about.

Money changes that. Row E4 — both cars in paid areas, one permit, the second
brother pays — means one brother spends real money because the other got there
first. That is a household dynamic the app would be creating, and it would be
creating it silently.

Not designable yet, but flag it now so it is not discovered after the fact:
**if paying lands, the fairness question comes back, and the backlog's "not
wanted" was answered about a different app.** The minimum honest response is that
a paid session shows whose plate and whose money, which the session record
carries anyway.

### 14. What the other phone learns about me

Not a personalisation dimension so much as a constraint on all the others. It
gets its own section below.

---

## The onboarding sequence

The governing principle, and it is a reversal of how the app currently opens:
**show something true before asking for anything.** Today the first screen is a
four-field form — permit username, password, and two plates — behind which the
app is invisible. That is the upfront wall the research is unanimous about, and
it is also wrong on this app's own terms: the obligation layer can tell you what
the spot under your feet costs without knowing a single thing about you.

| When | What is asked | What is inferred or deferred instead | Why then |
|---|---|---|---|
| **First open, before any question** | Nothing. Show the zone you are standing in and what it costs right now | Position → zone → tariff. All of it already exists | The app proves it is useful before it asks for anything. This is also the honest moment to request location, because the reason is on screen |
| **Outside Amsterdam** | Nothing | Say plainly that there is no data for here (row B5) | A confident "free" would be a lie by omission |
| **When position fails** | *"Where are you?"* — nearby list, type the code on the sign, or scan | Nothing; this is the fallback for when inference has failed | Rows A3 and A4 currently ask *"what should I do?"*, which is unanswerable. E-Flux asks the answerable version, and `verkooppunt.json` already carries the numbers printed on Amsterdam's signs |
| **First question, one field** | *"What's your plate?"* | Make, model and colour from the public register — no second field | Every settlement method needs a plate, permit or paid. It is the one universal fact, and both reference apps treat plates as first-class |
| **After the first detected drive** | *"You connect to 'Golf GTI' when you drive. Is that this car?"* | The Bluetooth device, from the disconnect the app already saw | A confirmation is not a question. Activity recognition and background location are asked for here, where the feature that needs them is visible |
| **After ~3 overnight parks in one place** | *"You park here most nights. Should this be free?"* | The home circle, from parks already recorded | The home zone is the most consequential setting in the app (row B1) and it is currently a map chore on day one. Nobody does map chores on day one |
| **First park in a paid zone** | *"€3,01/h here. Do you have a permit, or shall we look at paying?"* | — | This is the only moment holding a permit is relevant. Credentials are asked for here, and only if the answer is yes |
| **When a second phone appears** | Scan a QR to share | The room key, generated randomly | Sharing is a two-device fact. It cannot be set up meaningfully by one phone on first run, and it should not pretend to be setup |
| **Never** | Email | — | No reader exists. See dimension 5 |
| **Never** | Name | — | The vehicle has a name. The person does not need one |
| **Never** | City | Position | Asking is both slower and wrong for anyone who parks in two cities |
| **Never, by Handoff** | Payment details | The provider's own flow | BLOCKED on Q3, and prohibited even after |

Three things worth calling out about this sequence:

**The permit moves from first to fifth.** That is the single biggest change and
it is the direct consequence of the v0.6 split. If the permit is one settlement
method, it cannot also be the front door.

**Two of the steps are confirmations, not questions.** The Bluetooth device and
the home zone are both things the app can observe and offer. A confirmation with
a right answer already filled in is not onboarding friction, and it is available
here because the app is already recording the facts it needs.

**Nothing in the sequence is a wall.** Every step can be skipped and the app
still does the previous step's job. Skip the plate and you still get zones and
tariffs. Skip Bluetooth and you get a manual "I parked" instead of detection.
Skip the permit and you get the paying half, whatever that turns out to be.

---

## What changes on screen, per answer

| Answer | What appears | What disappears |
|---|---|---|
| **One car, no permit, no sharing** | The spot strip, the map, the zone and rate | The hero card, the hand-over button, the mark's dot travel, the other-car status card, the sync section, "Setup incomplete" |
| **One car, permit, no sharing** | A permit card that says "on your car" and nothing to decide | The hand-over button (there is nowhere to hand it to), the collision guard, the takeover notification |
| **Two cars, permit, sharing** | Exactly what ships today | Nothing |
| **Two cars, no permit, sharing** | The spot strip for each car, and eventually the tariff comparison of request #3 | The permit card entirely |
| **Three or more cars** | A vehicle picker instead of one button; the mark drawn neutral as a wordmark; identity carried by plate and name rather than by hue | The two-arc state display, the colour-mirrored button |

---

## What the reference apps actually show

Eleven screenshots in [`docs/inspo/`](inspo/), read first-hand rather than
described. Two apps: **E-Flux** (EV charging, light) and **Q-Park** (parking,
dark). What follows is what they demonstrably do, then what is worth taking.

| File | Screen | The thing worth noticing |
|---|---|---|
| `].webp` | E-Flux home | Hero action card, three plain rows, **map preview card at the bottom** |
| `preview.webp` | E-Flux "Charge Now" | **Three ways to identify a charge point**: nearby list, type the ID from the sticker, scan a QR |
| `preview (1).webp` | E-Flux map | Full-bleed map, floating search pill, **two 48dp circular controls bottom-right**, status-coloured pins with count badges |
| `preview (2).webp` | Q-Park sessions, empty | Segmented Actief / Geschiedenis, illustration, and a sentence saying *when* something would appear here |
| `preview (3).webp` | E-Flux account, scrolled | Sentence-case section headers, icon + label + trailing chevron, external-link glyph, version as a row |
| `preview (4).webp` | Q-Park Producten | **A capability list**: MOBIEL PARKEREN / `GEACTIVEERD`, Abonnementen / `0` |
| `preview (5).webp` | Q-Park Profiel | Grouped cards: Persoonlijke gegevens, Betaalmethoden, Laadpassen, **Kentekens**, Vouchers |
| `preview (6).webp` | E-Flux account, top | **An identity card: avatar, name, email** — the counter-evidence to dimension 5 |
| `preview (7).webp` | E-Flux Wallet | Sessies / Betalingen / Facturen, month header, **a 2×2 stat grid**, a session row with an `Actief` pill |
| `preview (8).webp` | Q-Park map, continent zoom | Dark tiles, floating search, **the same two circular controls bottom-right** |
| `preview (9).webp` | Q-Park history | **The session record, definitively.** See below |

### The session record — the most transferable thing in the set

Q-Park's history rows have a fixed anatomy, and it is worth copying exactly:

```
[ P  ZONE 19692 ]                                    € 1,81
                                          Geen transactiekosten
Amsterdam - Elzenstraat 2
woensdag 22 juli, 14:12 → 15:15
```

A badge, an address, a weekday-date-and-time-range with a literal arrow, a price
set large top-right, and a quiet qualifier beneath it. One card per session.

Two observations that matter more than the layout:

**The badge slot is polymorphic.** Street sessions carry `ZONE 19692`; the
garage session carries the Q-Park logo and `HERMITAGE, ZAANDAM` instead. The slot
does not mean "zone code", it means **"what kind of place was this"**.

Handoff's version of that slot should mean **what kind of settlement this was** —
`PERMIT`, `FREE · HOME`, `€ 3,01/h` — which renders both halves of the app in one
list and makes the obligation/settlement split visible for the first time.

**Three of the four fields are obligation, not settlement.** Zone, address and
time range are computable today, from the resolver and the tariff windows that
already exist. Price is computable as *what was owed*, whether or not anything
was ever paid. So a session log is the one substantial feature on this whole list
that is **not blocked on Q3**, and it is the concrete form of the fallback
`NEXT.md` already sketched: *"show me what I would owe"*.

### The identification trio answers a bug, not a feature

`preview.webp` offers three routes to the same fact — *which charge point is
this?*: pick from a nearby list, **type the ID printed on the sticker**, or scan
the QR.

Handoff has the same question in a worse form. Rows A3 and A4 of the audit are
the no-GPS-fix branch, and today it asks *"what should I do?"* — which the user
cannot answer, because the app has not told them what it does not know. The right
question is E-Flux's: **"where are you?"**, with three ways to answer.

And the data is already identified. Wasil photographed the numbers on Amsterdam
parking signs (`12671`, `19900`); `verkooppunt.json` carries exactly that
identifier space as `VERKOOP_PU`, 6,445 of them, each with an address. So "type
the code on the sign" is buildable from a source already found, and it converts
the app's most common failure — parked in a garage, no fix — from a dead end into
one field.

That is a personalisation finding as much as a map one: **the fastest way to
learn where someone is, is to let them say so.**

### The identity card, and why it loses

`preview (6)` is the strongest argument against dimension 5, so it deserves a
straight answer rather than being left out.

E-Flux shows a dark card with an avatar, a name and an email. Directly beneath it:
Dashboard (external), Meldingen, Profiel, Wachtwoord, Facturatie adres,
Abonnement annuleren, Verwijder account, App versie. Q-Park's profile is the same
shape — Persoonlijke gegevens, Betaalmethoden, Vouchers, Uitloggen.

**The identity card is the visible tip of an account system.** E-Flux shows the
email because the account *is* the email: there is a server, a password to
change, a subscription to cancel, invoices to issue and a dashboard to link out
to. Handoff has none of those rows because it has none of those things. Copying
the card without the system underneath it would be a costume — a field displayed
back to the one person who typed it.

Dimension 5 stands. What Handoff should copy from these screens is the *row
grammar*, which it already has in `SettingsScreen`, not the header.

### The capability list is the right shape for settlement

`preview (4)` is Q-Park's "Producten": MOBIEL PARKEREN with a pale-green
`GEACTIVEERD` pill, Abonnementen with a `0` count, and a pill button to activate
with a registration code. It is a list of **what this account can do**.

That is exactly the shape Handoff's settlement methods want, and it solves a
presentation problem that has no other good answer: how do you show that paying
exists as a concept but may not be possible?

| Method | State |
|---|---|
| Permit | `ACTIVE` — on Wasil's car |
| Paid session | `NOT AVAILABLE` — pending Q3 |
| Free | always |

A row that says "not available" is honest. A missing feature is not.

### The tab bar: stay at three

Q-Park has five flat tabs. E-Flux appears to have five but does not — the centre
orange circle is a **docked primary action**, not a destination, so it is four
destinations and one action.

Both have four or five because they are account-bearing commercial products with
a wallet, invoices, purchasable products, support and a profile. Handoff has no
account, no wallet, no invoices and no products. Matching the count would mean
adding empty rooms and then looking for furniture. Material's guidance is 3–5, so
both ends are legal and the constraint is content, not the spec.

And Handoff should **not** adopt the docked centre action, for a specific reason:
E-Flux's centre button always means the same thing ("Charge Now"). Handoff's
primary action changes meaning with context — claim, hand over, hand back, and
eventually pay. An action whose label changes is a poor fixed landmark, and it
already lives on the Permit screen where the context that gives it meaning is
visible.

**Three tabs now; a fourth ("Sessions") when there are sessions to list; never
five.**

### Where the references and Handoff genuinely disagree

Worth recording, because "the reference app does X" is not an argument on its own.

Q-Park's empty session tab (`preview (2)`) is a full-screen illustration, a
headline and a sentence. Handoff's empty states are one grey line, deliberately —
`MainScreen` shrinks the map card when there is no car precisely because
*"reserving half a screen to announce that nothing has happened makes absence the
loudest thing on it"*.

Both are right, and the difference is context. Q-Park's empty state owns a
dedicated tab whose only job is sessions; an empty tab has to explain itself or
it is a bug. Handoff's is one card among several on a screen that has other
things to say. **The rule to take is narrower than "build big empty states":
an empty state must explain when something would appear here — and it earns
space in proportion to how much of the screen it owns.**

---

## The map, and "not specific enough per area"

Wasil's standing complaint about the map is a personalisation problem in
disguise: the map is the one screen where everything the app knows about *him* —
his home circle, his free zones, his car, his position — is visible at once. So
it belongs here.

### The chrome arithmetic

Measured against `MapScreen.kt`, Handoff currently spends, before a single tile
is drawn:

| What | Where |
|---|---|
| A "Map" title and a status line | Header row, above the map |
| A tariff card (district, street, rate now) | Same header row |
| A `Card` holding three `OutlinedButton`s — Set home, Free zone, Tariffs | Floating at the bottom, full width |
| A separate full-width "Walk to car" button | Beneath that |
| An OpenStreetMap attribution line | Below the map |
| 20dp horizontal insets and a rounded `HandoffShapes.Card` clip | Around the whole thing |

E-Flux (`preview (1)`) and Q-Park (`preview (8)`) both spend: a floating search
pill at the top, and **two circular ~48dp buttons stacked bottom-right**. Nothing
else. The map runs edge to edge, behind the status bar.

The difference is not styling. It is a model:

> **Handoff treats map controls as layout. The references treat them as overlay.**
> Layout subtracts from the map. Overlay sits on it.

The comments in `MapScreen.kt` show this has already been fought twice — *"every
card in that bottom stack costs map, and the map is the screen"*, and the
three-buttons-on-one-row compromise that followed. The compromise was the right
move within the layout model. The references suggest the model itself is what to
change.

### Three specific changes, all inside the existing system

**1. Move the bottom controls into floating circles.** Set home, Free zone and
Tariffs become icon buttons in a vertical stack at the bottom-right — the same
place both references put theirs. They keep their surface (`surface` on
`HandoffShapes.Control`, or a circle) for the reason already documented: map
tiles stay light whatever the theme does, so a transparent control vanishes. No
new colours, no new shapes, and roughly a third of the map comes back.

**2. Add a locate-me control, because there is not one.** Once you pan, there is
no way back to yourself. This is also the fix for a defect already logged in
`NEXT.md` — *"your own position is read once, when the app starts"* — since `me`
is captured in a single `LaunchedEffect(Unit)` in `HandoffTabs`. One circular
button that re-reads position and recentres closes both.

**3. Add a search field only if the zone registry lands.** Handoff has no search
at all, and both references pin one at the top. But search needs something to
search: with 29 rate regions there is nothing worth typing. With 6,445
`verkooppunt` entries carrying addresses, there is. **Search follows the
registry; it does not precede it.**

### The specificity complaint has a data answer, not a drawing answer

E-Flux's map shows **points** — teardrop pins in status colour, with circular
count badges where several cluster. Handoff draws **polygons**: 29 rate regions,
up to 3 km wide, 21 of them in disjoint pieces, which `NEXT.md` already concluded
are *"rate regions, not parking zones"*.

So *"not specific enough per area"* is not a rendering failure and no amount of
restyling will fix it. It is the wrong data, and the fix is the one already
chosen for other reasons: points from the zone registry, drawn as pins with
cluster badges, with the polygons kept only for the paid-versus-free test they
genuinely answer.

**The reference screenshots confirm the v0.6 zone-registry direction from the UI
end.** That is the most useful thing in the whole set, because it is the same
conclusion arriving from a completely different direction.

### The one place I argue for changing the design system

`Color.kt` fixes every map colour — `ZoneHome`, `ZoneFree`, `ZoneCandidate`,
`TariffBoundary`, `TariffSelected`, `WalkRoute` — to a single value in both light
and dark, and gives an explicit reason:

> *"The map tile layer itself (osmdroid's MAPNIK source) doesn't change with the
> app's theme, so tying zone colour to dark/light would risk a dark-mode value
> vanishing against tiles that stay light regardless."*

That reasoning is sound and it is **entirely conditional on MAPNIK**. Both
references use calm, theme-appropriate tiles: E-Flux a muted light style, Q-Park
dark navy. Handoff's MAPNIK tiles are busier than either — full-saturation
greens, heavy road casings, every POI label — which is a large part of why the
overlay had to be drawn at 0.07 alpha to avoid burying the zone circles.

**Dark map tiles are already on the backlog's *wanted* list.** So the argument is:
a calmer, theme-paired tile source is worth taking, and taking it releases the
constraint that forced six map colours to be mode-independent. Those six could
then be mode-paired like every other colour in the system, which would make the
map the only part of the app that stops being an exception.

Not decided here — a tile source is a dependency and a licensing question, and
the current one is doing an honest job. Flagged as the one place where this
proposal would change the system rather than live inside it, with the reason
written down so it can be argued with.

---

## Where this sits in the design system

The brief asks that any proposal live inside the existing system or argue
explicitly for changing it. **This one lives inside it, and needs no new hue.**

**Identity colour.** The locked decision — slate blue for Wasil, terracotta for
Walid, kept clear of `fine` and `alert` by hue *and* by scale — survives
unchanged, but it needs a rule attached that is currently implicit:

> **Identity by hue is a two-body affordance.** Two identity colours exist
> because there are two identities and one contested resource; a third would have
> to be found in a palette that already excludes green (`fine`), rust (`alert`),
> two neutrals for zones, a grey-blue for tariff boundaries and a teal for the
> walk route. There is no fourth safe hue, and even if there were, colour as an
> identifier stops working past two — nobody remembers which car is teal.

So: for `size <= 2`, identity colour is assigned by roster position, exactly as
`strongFor`/`containerFor`/`onContainerFor` do now, with the enum swapped for an
index. For `size >= 3`, identity is carried by the plate and the name, drawn in
neutrals, and the hero card falls back to `surfaceVariant` — which is already the
`holder == null` branch in `MainScreen`, so the code path exists.

**`HandoffColors` needs one change and no new values.** `strongFor(car: MyCar)`
becomes `strongFor(index: Int)` returning the two existing pairs. Nothing is
added.

**`HandoffShapes`** is untouched. Two radii still cover a vehicle list — rows sit
inside `Card`, a plate chip is `Control`.

**Typography** is untouched, and better than that: the grouped-rows profile screen
both reference apps show **is already built**. `SettingsScreen` has
`SectionHeader` (on `labelSmall`, with the deliberate positive tracking),
`SettingRow` (label left, value right, chevron only when tappable) and `RowHint`.
That is the same component set as E-Flux's account screen (`preview (3)`) and
Q-Park's profile (`preview (5)`). A vehicles list is a new *section*, not a new
screen and certainly not a new pattern.

The three apps do disagree on how a section header looks, and Handoff should not
move: E-Flux uses large sentence-case headers (~17sp, near body weight), Q-Park
uses small caps with tracking inside grouped cards, and Handoff uses `labelSmall`
with `0.6.sp` tracking. All three work. Handoff's is the quietest, which suits an
app whose settings screen is mostly meant to be reassuring rather than browsed.
The `Producten`-style capability list described above uses the same `SettingRow`
with a trailing state pill — one new trailing element, drawn in `fine` or in
`onSurfaceVariant`, never in an identity colour.

**The mark.** `HandoffMark` takes `MarkState(lit, dot)` and already renders
`null` as "neither lit, dot centred". The N≥3 case is that state, permanently —
the mark becomes a wordmark and stops carrying state. No new drawing code.

**One thing does need designing, and it is small:** a neutral vehicle chip for
lists — plate text in `HandoffShapes.Control` on `surfaceContainerHigh`, with the
name in `bodyMedium` beside it. That is the only new component in this entire
proposal.

---

## Privacy — what must not come back

A parallel change removes shared location outright, so that no phone learns
another's position. Every proposal here has to be checked against that, and one
of them nearly fails.

**The rules, stated so they can be checked:**

1. **The home zone never leaves the device.** It is where you live. Two brothers
   at one address each draw the same circle, and the duplication is the price of
   the guarantee. Do not "improve" this by syncing it.
2. **`PhoneState` is the privacy boundary, and it should shrink.** Today it
   carries `lat`, `lng`, `accuracyM` and `zoneCode`, of which the guard reads
   none. After v0.6.3 it should be `{parkedOutside, heartbeatAtMs, rateNow}` and
   nothing else. **A third phone must not turn the room into a location feed** —
   and the way to make that structural rather than a promise is that there is no
   field to put a position in.
3. **More phones must not mean more fields.** Every dimension in this document
   that would add something to the shared room is a dimension to refuse. Checking
   the list: the roster (plates) already crosses, because the room must know which
   plate holds the permit. Everything else — home zone, Bluetooth devices, which
   phone drives which car, city, language, permit credentials — stays local. The
   room's shape does not grow.
4. **No email and no name means nothing to leak.** Dimension 5 is a privacy
   decision as much as a product one.
5. **There is no server, so there is nothing to enforce at.** Both phones read and
   write one Firebase node with a shared URL and no per-user auth. Enforcement is
   therefore "do not write it", which is the same conclusion the audit reached.

**And the one that nearly fails:** the room ID is `SHA-256` of the *permit
username*. A permit username is a login name, not a secret — it is shared between
the brothers and may well be an email address. Anyone who knows it, plus the
database URL, can read both phones' states. That is acceptable for two brothers
and is already recorded as such. It is **not** acceptable as the joining
mechanism for a general multi-phone model, and it does not survive contact with
permit-less sharing anyway, since there would be no username to hash.

So: **a random room key, exchanged by QR, is required by both the privacy
argument and the permit-less argument at once.** That is the same QR item already
agreed in the backlog, arriving here from a second direction, which is usually a
sign it is the right piece.

---

## Blocked on the payment question

**Q3 — can a paid session be started programmatically at all — is unresolved, and
nothing below may be designed as though the answer were yes.**

The route is at least identifiable now: parking rights in the Netherlands are
registered per plate in the Nationaal Parkeer Register, administered by SHPV on
behalf of RDW, and the technical description of NPR data traffic is available
from RDW on request rather than published. That is a real lead and it is also a
warning — a system whose interface spec is handed out on request to registered
providers is not one an app joins casually.

Gated on the answer:

| Item | Why it waits |
|---|---|
| Payment method in the model | Belongs to `Settlement.PaidSession`, which may never be constructed |
| Session start / stop (rows D1, D4, D5, D6) | The whole of section D |
| Paying instead of contesting (rows D3, E2, E4) | The concrete win, and the one that most needs it |
| A wallet or payments tab | Nothing to put in it |
| The household fairness question (dimension 13) | Only exists once money does |
| Any onboarding step that mentions paying | Would promise something the app may not be able to do |

**Not gated, and worth doing regardless:**

- The session record as a log of **obligations** — zone, address, time range,
  what was owed. Every field is computable today.
- The roster refactor, the permit-optional gate, the sharing-optional copy.
- Everything in the onboarding sequence above except the last line.

---

## What I am unsure about, and what would settle it

**Whether a third car will ever exist.** Everything in this document is sized for
"probably not, but do not paint yourself into a corner". *Settled by:* Wasil
naming a specific third person. If there is no name, do not build past the
roster refactor. If there is one, the arity rules above are the plan.

**Whether the two-brother identity survives contact with strangers.** The mark
and the colour pairing are built around two named people, and I think they hold
up as an arity-two design rather than a private joke — but I would want to see it
with `vehicles[0].name = "Golf"` and `vehicles[1].name = "Polo"` before claiming
that confidently. *Settled by:* renaming the two entries in a debug build and
looking at the screen. Half an hour.

**Q1 — how permit scope varies.** The `Permit` shape above has empty fields
because nobody has answered. Specifically I am no longer confident that "there is
no hour budget" generalises past Wasil's own permit; the council publishes
capped visitor products. *Settled by:* the questionnaire in
`ZONE-QUESTIONNAIRE.md`.

**Q3 — programmatic paid sessions.** Unchanged, and still the biggest unknown in
the project. *Settled by:* the RDW technical description, requested.

**Whether "home is just a free zone" is worth collapsing.** I think it is
simplification rather than churn, but it touches the resolver, which is the one
piece of this app that is currently correct and quiet. *Settled by:* deciding
whether anything ever needs to treat home differently from a named free zone. I
could not find such a thing, but I did not look exhaustively.

---

## Sources

**Primary evidence — the screenshots Wasil put on disk**, read first-hand and
inventoried in the reference-apps section above: the eleven files in
[`docs/inspo/`](inspo/), covering E-Flux (EV charging, light theme) and Q-Park
(parking, dark theme). Where these disagree with anything second-hand, they win.

**Primary evidence — this repo's own source**, read rather than remembered:
`ui/MapScreen.kt` for the chrome arithmetic, `ui/HandoffTabs.kt` for the
once-only position read, `ui/theme/Color.kt` for the mode-independent map
colours and their stated reason, `parking/shared/RoomId.kt` for the room key
derived from the permit username, `ui/MainViewModel.kt` and `ui/SetupFlow.kt`
for the setup gate.

Design and platform guidance:

- [Navigation bar – Material Design 3](https://m3.material.io/components/navigation-bar/guidelines) and [Bottom navigation – Material Design](https://m2.material.io/components/bottom-navigation) — the 3-to-5 destination rule
- [Build adaptive navigation | Android Developers](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation) — `NavigationSuiteScaffold` and window size classes
- [Material Design 3 in Compose | Android Developers](https://developer.android.com/develop/ui/compose/designsystems/material3)

Onboarding and progressive profiling:

- [Mobile App Onboarding Best Practices in 2026 — lowcode.agency](https://www.lowcode.agency/blog/mobile-onboarding-best-practices) — just-in-time permissions tied to the feature that needs them; deferring optional configuration
- [Mobile App Experience in 2026: Why Apps That Ask Less Win More Users — Userpilot](https://userpilot.com/blog/app-experience/) — deferring account creation until after first value
- [Progressive Profiling 101 — sparkle.io](https://sparkle.io/blog/progressive-profiling) — collect gradually; *"ask for data when dopamine is high, not when patience is low"*
- [What Is Progressive Disclosure in UX? — UXPin](https://www.uxpin.com/studio/blog/what-is-progressive-disclosure/) — step-by-step, conditional and contextual disclosure

How other apps model vehicles and sharing:

- [ParkMobile — add a license plate (Android)](https://support.parkmobile.io/hc/en-us/articles/36854779252507-How-do-I-add-a-license-plate-to-my-account-using-the-ParkMobile-app-for-Android) — up to five vehicles per personal account, each with a nickname
- [EasyPark — manage your vehicles](https://www.easypark.com/en-sk/help/parking-app/account-manage-your-vehicles--21461307057180)
- [Family Car Sharing (Google Play)](https://play.google.com/store/apps/details?id=ch.daluapps.family_car_sharing) — one account per family, one owner per vehicle, admin-created members. The booking-arbitration model this document declines
- [Dual-Device Authorization with QR Codes — Cendyne](https://cendyne.dev/posts/2025-02-17-qr-code-login.html) — random session key, polled, time-limited

Dutch parking and vehicle data:

- [Nationaal Parkeer Register — parkeer- en verblijfsrechten](https://www.nationaalparkeerregister.nl/parkeer-en-verblijfsrechten) and [SHPV — NPR](https://shpv.nl/dienst/npr/) — rights registered per plate; the technical description of NPR data traffic is obtained from RDW on request
- [Open Data RDW: Gekentekende voertuigen (`m9d7-ebf2`)](https://opendata.rdw.nl/Voertuigen/Open-Data-RDW-Gekentekende_voertuigen/m9d7-ebf2) — plate to `merk`, `handelsbenaming`, `eerste_kleur`, `brandstof_omschrijving`, no key required
- [Gemeente Amsterdam — parkeervergunning kenteken wijzigen](https://www.amsterdam.nl/parkeren/parkeervergunning/parkeervergunning-voor-bewoners/parkeervergunning-bewoners-kenteken/) — plates change on a permit; environmental requirements are checked

Related documents in this repo: [`USE-CASES.md`](USE-CASES.md) for the
obligation/settlement split, [`CONDITION-ACTION-AUDIT.md`](CONDITION-ACTION-AUDIT.md)
for the privacy reasoning this document extends, [`v0.6-zone-registry.md`](v0.6-zone-registry.md)
for the zone data, [`BACKLOG.md`](BACKLOG.md) for the locked decisions.
