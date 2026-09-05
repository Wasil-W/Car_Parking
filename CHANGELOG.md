# Changelog

Every released version, newest first. Each entry is written to double as its
GitHub release notes.

Versioning from v0.3.4 onward: **new features take a minor bump** (0.3 → 0.4);
**everything else — bug fixes, layout, polish — takes a patch bump**
(0.3.3 → 0.3.4). Earlier entries used a looser rule that counted any behaviour
change as a minor.

---

## v0.7.7 — the question that arrived after every park

### Install this one

**The permit question was appearing after every single park, and there was no
way to stop it.** Reported from real use: *"the message pop up far more often
than before and i dont like that. i have to do it manually now every time."*

**It was never the Bluetooth pairing.** The app ignores any device that is not
your car, so a question arriving at all is proof the pairing worked — it is what
lets detection start in the first place.

What actually happened: the app works out that you have parked in one of two
ways — from movement, or from your position shifting a few metres. **Both are
read while the app is in the background**, and both need permissions that had
quietly stopped being granted. With neither, the app waits ninety seconds,
learns nothing, and has to ask. Every time.

### The button meant to fix it could only fix a quarter of it

Settings' **Grant** asked for **one** permission per press, out of four — and
looked exactly the same afterwards, with nothing saying more were needed.
Nobody presses an unchanged button four times, so an install could sit for
weeks with half its permissions and no sign of it.

It now asks for all of them in one press.

### The question says what it does not know

*"A possible park was detected. Choose what to do with the permit"* described
neither situation it covered. When the spot is known, it is a fair question.
When it is not, the app was asking about a place it could not see, and never
said so.

It now reads **"Parked — but where?"**, explains that without a position it
cannot tell whether the spot is paid, and — only when that is genuinely the
cause — says which setting fixes it. It will not point you at a setting that is
already correct.

### A park with no position is no longer a dead end

If the app could not work out where you parked, there was **nothing you could
do about it, ever.** No pin on the map, and correcting the pin was only
reachable by tapping the pin that did not exist. The car stayed lost until you
drove away.

The map now offers **"Where is the car?"** — tap it, tap the spot, confirm. The
first placement is uncapped, because there is no detected point to have wandered
from; every correction after that is capped against it as before.

Telling the app where your car is now also unblocks the other phone. It did not,
which meant placing your car by hand still left your brother waiting on it.

---

## v0.7.6 — eleven things found by looking instead of waiting

Nothing on screen changes. This is v0.7.5's own code reviewed against itself,
at Wasil's instruction: *"check your own code this time completely to find
errors or mistakes in code that could cause the bugs to appear instead of
waiting for them to appear."*

Eleven findings, all of them mine, none of them yet a failure anyone had hit.

**Three that would have become one.**

*The parser did not enforce its own promise.* The facility registry's comment
says the places that ship are the ones the council itself positioned — and 28
were excluded from v0.7.5 for exactly that reason — while the code accepted any
two numbers. An asset with latitude and longitude transposed would have drawn
all 37 plates in the Indian Ocean. The guarantee is now in the code rather than
in a test over today's data.

*A crash waiting in the sheet.* The weekday label indexes a seven-element list
with a value nothing range-checked, so a stray `0` or `8` in the data threw the
moment you tapped that garage. Closed twice over: the parser drops such a line,
and the function no longer trusts it. It **drops** rather than ignores, because
an empty day set means *every day* — silently widening a five-day price to
seven is the wrong direction to be wrong in.

*A dead function with a passing test.* When the layers button became a menu, its
old label lost every caller — and the test kept asserting two strings no screen
could produce. A green check for nothing at all, which is a shape this project
has met before.

**And eight smaller ones**: a second copy of a distance formula the app already
had, thirty-seven icon lookups per frame where two suffice, a sheet that could
offer no action at all if a garage had no page, a comment orphaned from the
function it described, a missing guard on the marker's info bubble, a distance
measured twice, and tests that failed with a bare number instead of saying the
data had simply moved.

Every new test was checked by removing the fix and watching it fail, then
restoring it. 568 pass.

---

## v0.7.5 — every garage and P+R, and what they cost

Amsterdam's published parking facilities on their own map layer, with their
prices in the operator's own words.

### 37 places, on a layer of their own

26 municipal garages and 11 P+R sites, from the council's own map data — the
same map at `amsterdam.nl/parkeren/parkeertarieven-kaart`. The layers button
stopped being an on/off switch and now opens two named rows, **Tariff areas**
and **Garages & P+R**, both off until you ask.

Switching facilities on does not move the map. Tariff areas are kilometres wide
so they need the zoom out; a garage is a point, and pulling the camera away from
where you are standing would undo the fix from v0.6.5.

### What they cost, quoted rather than calculated

Tap a plate and you get the operator's own line — *"0,50 per 7 min"*,
*"Dagkaart 47,50"*, *"1,00 per 24u, max 4 dg"*.

**Nothing is computed.** The national register publishes structured rates as
well as text, and they cannot be evaluated: one garage lists three price bands
covering overlapping durations, which cannot all apply at once. Adding them up
gave **€130 for a day** at a garage whose own day ticket is **€30**. So the app
quotes and never calculates, and the lines stay in Dutch — translating a
published price risks changing what it says.

**17 of the 37 have a published rate. The other 20 say so.** A parking sheet
with no price reads as *free* to anyone in a hurry, so it states the gap and
sends you to the council's page.

### Why 37 and not 75

Three registers publish Amsterdam facilities and they disagree. Reconciled,
there are 75 — but 28 of them carry a name and no coordinate anywhere, and the
register's address table holds the council's own postbus rather than the garage.
Placing those means asking a geocoder, which puts **P+R RAI eleven kilometres
away in Muiden**. A pin is a claim about where something is, so the ones that
ship are the ones the council itself positioned.

### The marker could not be the obvious one

Dutch parking signage is a white **P** on blue, and blue in this app means
Wasil. Nine colours already carry meaning on that screen. So facilities take no
colour of their own: a near-black plate, told apart by shape and glyph rather
than hue, and a plate rather than a teardrop — a teardrop here means a point
*you* chose.

**Known:** a facility directly underneath your own position is hidden behind it.
Your position wins, deliberately.

---

## v0.6.9 — which price is the one you are paying

Four small things from reading the timetable on a real screen.

**The line in force is the only one at full strength.** *"I see 3 prices i dont
know which one is the correct one."* Fair — a timetable that will not say which
row is *now* makes you redo the lookup the header already did. Everything else
is dimmed rather than the live row being decorated, so the answer is found by
looking. Weight and colour together, so it survives sunlight and never depends
on colour alone.

**The neighbourhood is back before you expand.** It had become expanded-only:
*"i dont see the small area anymore (for example Molenwijk) before i enlargen
the table."* It is the line that says *where*, so hiding it until asked was
backwards.

**A pressed control now inverts.** The active state was two neighbouring steps
of one warm neutral — a real difference in the palette and almost none on a
screen over map tiles. It flips to full contrast instead, which cannot be
missed, needs no new colour, and stays out of identity colour, which may never
mean "selected".

**"Car parked 6 Aug 17:06." became "Parked 6 Aug 17:06".** Eight characters were
pushing the time onto a third line and cutting the date in half. The word "car"
was being carried by the pin beside it anyway.

---

## v0.6.8 — a wrong password looked like success

### Install this one

**Typing the wrong permit username and password still showed your cars.**

Reported from real use: *"i entered incorrect credentials and it still showed my
cars."* The sign-in token lives in memory with nothing tying it to the account it
was issued for, so "Sign in and find my cars" went out on the **previous**
session, the site answered about the **previous** account, and what you had just
typed was never sent anywhere. Nothing could reject it because nothing received
it.

The button now signs in. The old session is thrown away first, so the request has
to authenticate with what is on screen — and a refusal is reported as a refusal
rather than as "couldn't reach the site", which is the sentence that sent you
looking for a network fault that was not there.

**A rejected sign-in also puts your working credentials back.** The old code kept
whatever was typed, on the reasoning that storing a wrong pair was harmless. It
was only harmless while nothing could tell it was wrong: re-typing your password
with a slip left the app signing in with it on every background claim from then
on.

### A park was recorded at a traffic light

Stopped at a light, engine running, and the app put the car there.

A Bluetooth blip drops the link and starts detection; the link comes straight
back. The app already knew what that meant — it resets the parked state and
cancels the pending claim — but the **detection loop that was already running**
was never told, and kept going. A car stopped at a red light reports as *still*
at high confidence within seconds, because "in vehicle" describes motion and a
stationary car has none. Five seconds later the loop called it a park.

Detection now checks whether the car's stereo has answered again, every pass and
once more before it acts. The five-second threshold is untouched: no wait short
enough to keep detection responsive is longer than a red light, so the reconnect
is the answer rather than a bigger number.

### The permit screen stopped forgetting

The permit site had a moment, the app fell back to its blank defaults, and showed
"No plate active" — while the site was fine in a browser.

A failed read says nothing about where the permit is. The card now keeps the last
holder the site actually reported, and says plainly underneath when it was read:
*"Couldn't reach the permit site — showing what it said at 14:32."* With nothing
ever read it says that instead, rather than dressing an empty card as a finding.

### The whole week, in the thing that was already showing you the rate

Tapping a tariff area used to open a second card, so the rate appeared twice on
one screen in two shapes. Now the header chip expands in place, downward, with
the week folded behind a chevron.

**And tapping an area now works at all.** The tap was matched against the areas
being *drawn*, which are none unless the tariff layer is switched on — and it is
off by default. Tapping the very area the header was naming did nothing, silently,
which is why the timetable shipped in v0.6.6 and was never found.

The expanded view shows the **neighbourhood name** where it used to repeat the
tariff code, resolved for whichever area you tapped. The parked line in the
corner stays visible while it is open; it used to disappear exactly when you
opened the thing beside it.

### "Walk to car" draws the route again

It had started offering Google Maps instead. Nothing was removed — the app reads
your position once at launch, that read fails indoors, and nothing ever asked
again, so the pill relabelled itself on a stale answer and stayed that way for
the session. Your position is now re-read whenever you open a tab, and the pill
asks when you press it rather than predicting in advance.

### Free zones are neighbourhoods now

Home zones and free zones felt identical — same circle, same radius slider — and
sizing an area by dragging over a map was never precise. So a free zone is now a
**real Amsterdam neighbourhood** with the city's own published boundary: tap the
map, the app names the area, confirm.

The confirmation says how big it is — *"0.17 km² · the whole neighbourhood"* —
because marking one free tells the app never to claim the permit anywhere inside
it, and buurten run from a few streets to most of a district. That failure is
silent, so the number goes in front of the choice.

**The home zone stays a circle**, 30–200 m, and that difference is now the point
rather than an accident: a home is a point you own, a free zone is an area you
know about.

**Two things to know.** Free zones saved before this version are not carried over
— they were circles, and circles are no longer what a free zone is. And a free
zone can only be placed where the city publishes a neighbourhood; outside that
the app says so rather than offering a button that stores nothing. Handoff has
always been Amsterdam-only — Amsterdam tariffs, Amsterdam areas, an Amsterdam
visitor permit — and free zones were the one thing that happened to work anywhere
because a circle does not need to know what city it is in.

### Zones you can find again

Every zone you have is now one list, from the map's overflow menu. Tapping a row
moves the map to it and opens it — the list is a way back to the map, not a
second place to manage things. Before it, a zone placed at your mother's street
six weeks ago was, in practice, gone.

### Smaller things

- **The plate picker stopped talking about people.** "Mine" and "Other car"
  become "This phone" and "Hand to". Neither names a brother, and neither changes
  meaning depending on which handset you are holding.
- **The Bluetooth list is filtered to what could be a car**, so your earbuds stop
  sitting next to your stereo. Everything else is one tap away and counted —
  a filter that hides the right device is worse than a long list.
- **Zone radius can be typed**, or stepped 5 m at a time, instead of only dragged.
- Switching the tariff layer off no longer throws away the area you had selected.

### Research: parking garages

Garage locations and live free spaces are open data; garage **rates** are the
awkward part, and not because they are missing. Garages price by *how long you
stay*, on-street by *what time it is* — genuinely different shapes, and this app's
whole tariff engine is the second one. Written up in
[`docs/v0.6-zone-registry.md`](docs/v0.6-zone-registry.md). Nothing is built, and
the recommendation is to leave it until there is a paying screen for it to be on.

---

## v0.6.7 — the plates could be stored the wrong way round

### Install this one

**On Walid's phone, the two plates were swapped.**

v0.6.5 relabelled the permit form to "your plate" and "the other car's plate",
which was the right wording. The storage behind it kept its old slot names, and
put whatever was typed first into **Wasil's** slot — whichever phone was typing.

So on the second phone the cars were the wrong way round, and a claim would have
moved the permit to the wrong car. That is a fine, on a real install, introduced
by a change meant to make setup easier. It was found while refactoring past it,
not by a test and not by anyone using the app.

It is fixed structurally rather than patched: cars are now ordered by plate —
the one thing both phones read from the same account and agree on without
talking to each other — and each phone records only which of them it drives.

### The two brothers are no longer an enum

`MyCar.WASIL` was a car with a name on it, not a person. It has become a vehicle
roster plus a note of which car this phone drives, and every two-car behaviour
now happens because there **are** two cars rather than because the code assumed
it.

**Nothing on screen changes** — 11 of 12 rendered screens are byte-identical
across both themes, and the twelfth differs only in map-tile timing, which the
old build also disagrees with itself about.

**Your setup survives the upgrade.** Verified twice: against the real storage
code starting in the old shape, and by installing over a genuine v0.6.6 install
on an emulator. History keeps its badges, the car pairing, home zone, free zones
and the link to the other phone all stay, and the shared node keeps its key so
the two phones stay paired.

### Groundwork for permits that are not like yours

Amsterdam publishes 66 areas where a permit does not apply during the day.
Yours is unaffected — they bind regular resident permits — but the app had no
way to tell one kind from another, and assumed every permit works everywhere.

There is now a permit kind, defaulting to **the restricted sort** when unknown,
because that is the direction that cannot cost a fine. **Nothing reads it yet**
and no exception areas are bundled: this is a foundation, not a feature.

---

## v0.6.6 — tomorrow's rate, and the map knows where it is

### The whole week, when you tap an area

*"Maybe i am curious about tommorows rate then i dont see that."* Fair — and it
was a gap this app made rather than inherited. v0.6.0 replaced the timetable with
a single live line, which was right for the header, but the full view was deleted
instead of moved. "What does this cost on Sunday" then had no answer anywhere.

Tap an area and the week opens:

```
ma–vr   09:00–19:00   €5,37/h
za, zo  free all day
```

**The free line is the point.** A timetable that lists only the charging bands
makes you work out the gaps yourself, and the gaps — Sunday, after seven — are
what the question was about. Nothing else opens it: the header still says one
true thing about now.

### Real neighbourhood names, from the city's own data

The map used to name places through Android's geocoder, because it had nothing
better. It does now: **107 permit zones and 518 neighbourhoods**, bundled, in
123 KB.

| Where | Before | Now |
|---|---|---|
| Waterlooplein | Amsterdam-Centrum / Waterlooplein | Centrum / **Waterloopleinbuurt** |
| NDSM | Amsterdam-Noord / Ms. van Riemsdijkweg | Noord / **NDSM terrein** |

Offline, no rate limit, no network round trip. The geocoder stays as the
fallback outside Amsterdam.

Boundaries are stored at full source precision — simplification was measured and
rejected, so no border moves and no gap opens between neighbourhoods that share
an edge.

**Three things this gives up**, and they are worth saying rather than hiding: the
"Amsterdam-" prefix is gone, because the city publishes bare names and adding it
back would invent "Amsterdam-Weesp"; the specific name now sits on the second
line, under the larger area; and inside Amsterdam the street name is no longer
shown. Say if any of those was the part you wanted.

**The claim decision is untouched.** Which zone charges what, and therefore
whether the permit is taken, still comes from the tariff polygons. This release
changed *where you are* and *what it is called*, not what it costs — there is a
test asserting that rather than a comment promising it.

---

## v0.6.5 — the map stops fighting you, and setup stops asking

### Your blue and Walid's terracotta are back

v0.6.4 decided "is this a two-car app" by asking whether the **sync link** was
set. Sharing is about whether the two phones talk to each other; it says nothing
about how many cars exist. With sync unset, a two-plate permit rendered as the
one-car mono view.

It keys on two plates now. And the argument that put it there was wrong twice
over: handing the permit across is a call to the permit website, not a message
between phones — it works with no sync at all, the other phone simply is not
told.

### One focus button instead of two

*"2 buttons in map i am uncertain about. They seem to do the same thing."*
Right — when the car is parked near you, "centre on me" and "frame both" produce
almost the same picture, so two buttons asked you to predict a difference you
could not see.

One button now cycles: **you → both → the car**. Its icon shows where the *next*
tap goes rather than what the map is doing, so it announces its outcome instead
of its category. And it corrects itself for what is actually on the map — caught
on screen, it was offering to frame a car that was not parked.

### The layers button leaves your camera alone

Toggling the tariff overlay zoomed out first, throwing away wherever you had
navigated and costing a second tap. Reported across two versions, and misread
here as a complaint about zone precision rather than about the camera.

The zoom-out earned its place in v0.5.1, when the map was small and the overlay
invisible at parking zoom. The map is bigger now and gets navigated, so the reset
costs more than it gave. **It toggles, and nothing moves.**

### Setup stops asking for what your account already knows

The permit form asked you to type both plates. **The account lists them** — and
has since v0.1, when `activePlate()` began reading the whole list, taking the one
holding a session and discarding the rest.

Sign in, and the app asks which of your cars is yours. Two plates settle the
second without a second tap. Typing them by hand is still there for when the
site cannot be reached, because a network failure is not your fault and must not
leave the permit unenterable.

### Three bugs from the decision-table audit

**Takeover alerts can reach you while driving.** The check lived in the
heartbeat, and the heartbeat is cancelled the whole time you are not parked —
so the one moment you would most want to know the permit had moved was the one
moment nothing could tell you.

**A park with no position stops telling the other phone it is free to claim.**
The app recorded that it could not tell, and then published the guess anyway.

**The screen and the decision agree.** Two cases where the app claimed the
permit while its own screen said nothing was owed: inside a paid area outside
its charging hours — which now reads *"free until 09:00"* — and a paid area
whose tariff will not parse, which now says the rate is unknown rather than
free. Claiming stays as it was in both: parking Sunday night rolls into Monday
morning, so the permit belongs there even when nothing is owed yet.

### Also

**"Remove permit"** clears the username and password from this phone, and
nothing else — zones, history, the sync link and the parked state all stay, and
a permit currently on your car stays there.

---

## v0.6.4 — the permission that was never asked for

### The bug behind three of your reports

**`ACCESS_BACKGROUND_LOCATION` was declared in the manifest and never requested.**

On Android 10 and up that is a runtime permission, so declaring it does nothing.
Without it, location only works while the app is on screen — and every part of
this app that needs a position runs in the background: park detection, and the
live sampling added in v0.6.2. Both asked for a fix and got nothing, every time.

That is one cause behind three separately-reported symptoms: no parked pin, being
asked about the permit at home, and the car's location vanishing. It is also why
none of the earlier fixes helped.

**And the app said its permissions were fine.** The health check tested four
permissions and this was not one of them, so it reported a green all-clear while
the one that mattered was missing. It now has its own row, worded as what it
costs you — *"Parks are only noticed while Handoff is open"* — and setup cannot
read as complete without it. The row above it changed from "Permissions all
granted" to "Permissions granted in the app", because a green tick sitting over
a location fault is the same false reassurance in a smaller costume.

### The parked pin survives the drive

It used to be deleted when you got in the car, which was defensible — during a
drive the old pin points at a spot you have left. But that made it the only copy,
so a park that produced no position left no location at all, permanently. It is
now held and hidden: the map says *"You're in the car"*, and a failed park falls
back to where the car was last seen.

### The map is the screen

Controls float over the map instead of taking rows of layout. The map goes from
**64% to 83%** of the screen. Four circular buttons bottom-right, one pill
bottom-centre, and **a locate-me button that did not exist** — until now, panning
away from yourself was a one-way trip.

### It stops calling a working app broken

A one-car, no-permit, no-sharing install is a valid way to use Handoff, and the
app used to call it "Setup incomplete". First run now asks one question — whose
phone this is — because that is the only thing detection cannot work without. The
permit moved behind an **"Add a permit"** row in Settings, and can now be removed
again from the same place: **"Remove permit"** clears the username and password
from this phone and nothing else. Your zones, history and the link to the other
phone all stay.

The permit screen now has three truths rather than one: two cars with a permit
(unchanged), one car with a permit (no hand-over button — there is nowhere to
hand it to), and no permit at all (what the spot costs, and when it goes free).

### History

A fourth tab: every park, newest first, grouped by month. Each record carries
**how the obligation was met** — permit, home, free zone, or unsettled — rather
than a zone code, because that split is what the app is built around.
"Unsettled" means the spot was charging and nothing covered it, which is true and
useful whether or not the app can ever pay.

The tab formerly called "Permit" is now **"Now"** — it can no longer be assumed
that there is a permit on it.

---

## v0.6.3 — the other phone is told only what it reads

No visible change. Everything here is about what leaves your phone.

**The zone code is gone from the wire.** It had been published since v0.3 and
read by nothing — it was meant for a "the other car is in zone T13B" display
that was never built. It is also a location signal in its own right: Amsterdam's
tariff areas run up to 3 km across, so publishing a code narrowed the other car
to a district. Coarse is not the same as private.

**The rate is gone too, along with the comparison it was for.** v0.6.2 published
a price per hour so the dearer of the two spots could keep the permit. That only
pays off once the cheaper car can *pay* instead — today it gets a fine either
way, so deciding by rate would change which brother is fined and nothing else.
Rather than leave working machinery dormant until it is needed, it is removed.
It gets built against a real payment path, or not at all.

**What one phone now tells the other, in full:** is my car on a paid street,
since when, and how fresh is this. Three fields, and the collision guard reads
all three. Nothing else crosses.

Old and new phones still talk to each other in both directions — the upgrade is
not coordinated, so a node still carrying coordinates, a zone code or a rate
decodes cleanly and those simply fall on the floor.

---

## v0.6.2 — the car's position stops being guessed, and stops being shared

Two pieces of work that had to ship together, because the second depends on the
first being true.

### Your two long-standing bugs were one bug

*"No parked car location even though it detected the parked car"* and *"asked in
the home zone if I want to claim the permit"* had the same cause, and it was not
in the home-zone check — which is why looking there never found it.

Resolving a zone needs a GPS fix. The fallback to a cached fix only accepted one
under two minutes old. In a garage both fail, and that branch **prompted you and
told the other phone your car was not on a paid street** — on the strength of a
failed location read. Home is exactly where fixes fail, so the prompt appeared
where it was least wanted, and the map said "no parked location" at the same
moment, from the same gap.

### The fix: the position is now captured across the whole drive

While the car's Bluetooth is connected the phone samples its position every 20
seconds. On disconnect, sampling stops and the last sample is sealed as the
parked location. The drive to the house supplies a position even when the final
fix fails.

**The live driving position can never trigger a claim.** Not by convention — by
type. A driving sample keeps its coordinates private and hands them to nobody;
the only way to a usable position is a function that refuses while the car is
still connected. Eight attempted ways around it were tried against the compiler
and all eight were rejected. A test runs twenty polls through the full claim path
mid-drive and asserts the permit never moves.

A sample older than three minutes is discarded rather than trusted, because the
oldest thing a drive can hold is the driveway you left — and sealing that would
say "parked at home" while the car sits in town.

**And a park with no position no longer claims to be free.** It now says the
state is unknown instead of publishing a guess.

### Your car's location no longer leaves your phone

Each phone published its coordinates to the other. Nothing ever read them —
verified before removal, across every consumer. They are gone from the wire
entirely, and a test asserts the bytes actually sent contain nothing
coordinate-shaped.

Since there is no server between the two phones, not publishing is stronger than
filtering.

### The permit can be decided by cost instead of by who got there first

Each phone prices **its own** spot and publishes a number. Two numbers are
compared. Neither phone learns where the other is.

Equal cost deliberately decides nothing. Any "who parked first" tie-break
compares clocks belonging to two different phones, and only the heartbeat is
server-stamped — so skew could let **both** phones conclude they had won and
both claim. A test asserts the two sides can never both claim and never both
yield.

This is built and tested but not yet acting on its own — see the release notes
discussion. It reports; it does not decide.

---

## v0.6.1 — what is owed, then who is covering it

The Permit screen now answers two questions in the order you actually ask them.

Above the card, **This spot**: €8,05/h · all day, or "Nothing to pay — at home",
or "Not parked". Below it, unchanged, **who holds the permit**.

This is the separation Wasil asked for — permit and paying as distinct things
sharing one mainframe. *Where you parked* decides whether anything is owed;
*what you hold* decides how it gets settled. They were previously fused, with
the permit card implying an obligation it never actually stated.

Nothing about claiming changed. The value is that the screen now explains
itself: when the permit sits on your car you can see what it is covering, and
when it does not matter — at home, in a free zone — the screen says so instead
of leaving you to infer it.

**It also makes room for paying.** A settlement that costs money slots into the
top half without the permit card needing to know it exists. Whether that is
possible at all is still the open question, and it is a research question about
what the payment providers expose rather than something to design around.

A free spot always wins over a readable tariff, matching how the claim decision
already orders them — the screen cannot disagree with the app about whether you
owe anything. And a spot with no readable tariff says "outside a paid zone"
rather than claiming to be free, because a guess in that direction costs a fine.

---

## v0.6.0 — what it costs right now

The map header used to print a timetable at you: *"€3,01/h · ma-wo,vrij,za
09-19 do 09-21"*. Standing in the street that is a puzzle, not an answer.

It now says one true thing about this moment:

> **€3,01/h · until 19:00**
> **Free · from 09:00**
> **€8,05/h · all day**

Amsterdam's data always carried the real charging windows — times and days, per
rate — they were just never read. They are now parsed into a schedule the app
can ask "am I paying, and for how long".

**A note on the day names**, because it is the kind of thing that silently
breaks: the schedule field and the description field use *different spellings*.
The schedule writes Friday as `vrij` where the description writes `vr`, and it
never writes Tuesday alone — Tuesday only ever appears inside a range like
`ma-wo`. Two vocabularies in one file, so they get two parsers and their own
tests.

Where a window cannot be read it is dropped rather than guessed at, and the area
reads as free — the honest answer when the rule is unreadable.

---

## v0.5.4 — where you are, and quieter

### The header says where you are, at two levels

**Amsterdam-Noord**, with the street underneath it, then the rate. Wasil's ask:
knowing the district *and* the specific spot, rather than a code.

The finer names he saw on the council's map — "Molenwijk", "NDSM-werf" — come
from Amsterdam's own zone data, not from a geocoder, so they arrive with the
zone registry. The street is arguably more use anyway when you are looking for
the car.

### The zone code no longer flashes before the name

*"There is still a small frame where the T11V is visible before it changes."*
Right — the code was shown as a placeholder while the name was being looked up,
then swapped. The header now stays blank until the lookup finishes, and falls
back to the code only when no name is coming.

### A schedule could state the opposite of the truth

One area reads "Basistarief TC7 19-06, niet za op zo" — charged overnight,
**not** at weekends. The header trimmed everything before the first day it
found, which left "za op zo": exactly the opposite. Now it trims from the hours
or the day, whichever comes first. Found by looking at Noord on a map.

### Notifications stopped shouting

`WARNING:`, `FAILED`, `check the website!` — gone. "Permit switch failed"
carries the same urgency without the capitals, and a notification is usually
the first thing you see.

### Two silent failure modes retired

Both were flagged by review twice and both dispatched on **display text**:

- The main button matched its target by comparing labels, and an unrecognised
  label mapped to Walid — so a typo would have lit the wrong brother's arc.
  Plates now carry which car they are.
- A Settings fix button dispatched on the button's own words, so any new row
  silently inherited the battery-settings action. That is why the "no car
  paired" row shipped with no button at all. It is a typed action now, and the
  compiler checks it.

### One thing checked and found already done

The roadmap carried "a pending decision never expires" as a hazard. It is not
true — expiry and a live re-check were both built in v0.4 and are wired. The
item was carried forward without being re-read. Recorded rather than quietly
dropped.

---

## v0.5.3 — the walk back is drawn on the map

"Walk to car" used to hand you to Google Maps and leave. It now draws the
walking route on the app's own map — a dashed green line from where you are to
where the car is, with the distance and how long it will take. Tap again to
hide it.

**No API key, no billing account, no Maps SDK** — the same constraint that chose
OpenStreetMap tiles in the first place. Routes come from OSRM's public service.

**When the route cannot be fetched, a straight line is drawn instead.** A
courtesy service with no uptime promise is fine here, because a failed route is
not a failed feature: for a car three streets away, the direction and the
distance are most of the value. With no position of your own at all, the button
falls back to opening Maps as before, rather than pretending.

**The walking time is calculated, not taken from the routing service.** That
service does not reliably distinguish walking from driving, and returned "2 min"
for a 984 m walk — 30 km/h. Times are now worked out from the distance at a
normal walking pace. Caught by reading it on screen, like everything else that
has ever been caught here.

---

## v0.5.2 — the map screen, tidied

All from Wasil driving and tapping at v0.5.1, 2026-08-02.

### The correction cap could be walked around

*"It is literally possible to keep clicking and then bring the car all the way
to somewhere else."* Correct, and the cause was that the 300 m limit measured
from the **current** pin. Confirm a move, tap the marker again, and the cap
re-anchored to where you just put it — so the car could cross the city 300 m at
a time. A cap that resets is not a cap.

Corrections are now measured from where *detection* put the car, which never
moves. Total drift can never exceed 300 m from the real fix, however many
corrections you make.

### Tapping one region lit up half the city

*"When I press 1 section multiple other sections light up."* Nearly right about
the cause — not the price, the code. **21 of the 29 tariff areas are made of
several disjoint pieces**, and `T13B` alone is 16 of them scattered across
Amsterdam. Highlighting an *area* highlighted every piece that shared its code.
Only the piece you actually tapped highlights now.

### The highlight was hard to see

It reused the boundary colour at a heavier stroke, which does not survive a busy
map underneath. Selected regions now use a near-black of their own, with a fill.

### Real place names instead of codes

*"Don't use codes like T14B, use their real names."* The tariff file genuinely
has no place names in it — its description field is a rate class, not a
neighbourhood — so the name is now geocoded: **"Amsterdam-Centrum"** rather than
`T11V`. The code remains as the fallback when there is no network.

### The map frames the car and you together

Opening either map used to centre on the car alone and leave you off screen, or
the reverse. Both pins are now framed at once — on the Permit tab's preview as
well as the full map — so neither has to be hunted for.

### Less of the map spent on controls

The three-button stack over the map became one row, and the zone information
moved up into header space that was sitting empty. The map is the screen; every
card stacked on it was rent.

---

## v0.5.1 — the tariff overlay actually shows you something

v0.5.0's overlay worked exactly as written and was useless in practice. Caught
by putting it on a screen and looking at it, which is the only thing that has
ever caught this class of bug here.

**The mistake was an assumption, not a line of code.** The design said the
question "why did it claim here?" is answered by *seeing the boundary* of the
area your car is in. It is not. Amsterdam's tariff areas are neighbourhood-sized
— the one covering Waterlooplein is 3.1 km by 2.7 km — so at the zoom you park
at, the entire screen sits inside a single polygon. Its boundary is kilometres
away, and all the overlay drew was a uniform 7% tint. Switching it on looked
like it did nothing at all.

**The question is answered by the label.** The map now names the area your car
is standing in, under the map, whether or not the overlay is on: *"Your car is
in T11V — €8,05/h · Basistarief TC1 ma-zo 00-24"*. That is the whole answer, in
one line, with no geometry required.

**Switching the overlay on now pulls the map out** to a zoom where boundaries
exist on screen. You see the patchwork across the city, and can tap any region
for its code, rate and hours.

---

## v0.5.0 — what the app thinks, and how to correct it

The app has always held two beliefs it never showed you: where your car is, and
what that place costs. This release makes both visible, and lets you fix the
first one.

**Correct the parked pin.** Tap the car marker, tap where the car really is,
confirm. GPS is accurate to about 25 m at best, which is enough to put the car
on the wrong side of the street and send "Walk to car" the wrong way. There was
no way to override it.

**The correction re-checks whether that spot is paid parking**, because 40 m is
the difference between a paid street and the free zone around the corner. If
the answer changes, the app asks about the permit — it never decides for you.
You are standing next to the car and have just told it something it did not
know; that is the moment to ask, not to act.

**Corrections further than 300 m are refused.** That is not a correction, it is
a different parking spot, and detection will pick that up on its own. The guard
matters because a mis-tap landing inside a free zone would tell the other phone
it is free to claim while the car sits on a paid street with no permit.

**The corrected position reaches the other phone**, so it is not just your view.

**Tariff areas on the map.** Amsterdam's 29 paid-parking regions have been
silently deciding whether the permit gets claimed since v0.2, and you could not
see them. Now you can: the area your car is in is always outlined, and a button
shows the other 28. Tap one for its code, rate and hours.

Nothing about the claim rules changed. This release shows the existing decision
and corrects one of its inputs.

---

## v0.4.2 — the car has a location again

One bug, three symptoms, all reported from real use on 2026-08-01.

Parking at home produced no car pin on the map, an empty card on the Permit
screen, and a "do you want to claim the permit?" prompt while sitting inside
the home zone.

**All three were the same missing fallback.** When a park is detected, the app
asks the GPS for a fresh high-accuracy fix. That request returns nothing when
it can't see the sky — which is exactly the situation when you park at home and
walk indoors. With no position:

- the car's location was never recorded, so the map had nothing to draw;
- the Permit screen's map card had nothing to draw either;
- and the zone check was skipped entirely, so the app never got as far as
  noticing it was at home, and fell through to asking.

It was not ignoring the home zone. It never had a position to compare against
it.

**The fix:** fall back to the position the phone already knows. It costs
nothing, and at the moment your car's Bluetooth drops it is, in practice, the
parking spot itself.

**A cached fix older than two minutes is still refused.** The tightness is
deliberate. A stale fix from the start of a journey would resolve to the home
zone and quietly decide no permit was needed — while the car sat in town
collecting a fine. When nothing usable is available the app asks, exactly as
before.

---

## v0.4.1 — quieter

Layout and colour only. Nothing about detection, claiming or the shared state
changed, and no new capability arrived. The brief was "professional, sleek, and
something that doesn't scream for attention" — so this is a subtraction release.

**Type that has a system behind it.** The old scale set font sizes and nothing
else: no line height, no letter spacing. That single omission is most of why the
app read as unstyled, because default leading is what stock Android looks like.
Body text now sits at roughly 1.45 line height, headings are tighter with
negative tracking, section labels get positive tracking, and the largest
heading came down from 30sp to 26sp — it was competing with the thing it
labelled.

**The hand-over button stopped shouting.** It was filled with the same colour
the identity arcs use. That value is tuned to read brightly as a thin stroke on
a card; stretched across a full-width slab it was the loudest thing on screen,
and it was light enough to force black label text — white measured only 4.34:1
on it. Two deeper fills now carry white at 7.1:1 and 6.8:1: better contrast from
a quieter colour.

**The snackbar is no longer a white slab.** Material's default uses the inverse
surface, which in a dark app means a brilliant white rectangle. It now sits on
the same near-black family as everything else.

**The auto-claim switch stopped glowing.** It filled its track with the
near-white generic accent, making a settings row the brightest element on the
screen.

**Two corner radii instead of six.** Corners had drifted to 14, 16, 18 and 22dp
— each reasonable alone, the set of them reading as unconsidered.

**The empty map card no longer reserves half the screen** when there is no
parked location to show.

---

## v0.4 — notifications you can tap, and zones you can see

The first release since the rebrand that adds capability rather than fixing or
restyling.

**Every notification opens the app.** Tapping one no longer just dismisses it —
it opens a full-screen version of the same question, with the same choices. The
blocked-claim case is the one that matters: the mark with the permit mid-travel,
when the other car parked, when it was last heard from, and why claiming now
would be a fine. The notification's buttons and the screen's buttons run
literally the same code, so they cannot drift apart.

The decision is written to storage rather than carried in the notification, so
it survives the app being killed between the notification arriving and you
tapping it.

**A stale question is now discarded rather than asked.** "Walid is parked, claim
anyway?" stops being a live question the moment Walid drives off — but it used to
sit there, still tappable, still able to do something you no longer wanted. The
situation is re-checked before the question is shown, using the same rule a real
claim uses. Two of the four kinds can go stale this way; "Walid took the permit"
reports something that already happened, so it never expires.

If that check cannot reach the network, the question is **kept**. Failing to
confirm a situation ended is not evidence that it ended.

**Notification icons show who holds the permit** — the dot sits against your arc
or his, matching the mark in the app.

**Zones live on the map.** Home and free zones are drawn as circles you can see
against the street, created by tapping the map, and removed or renamed by tapping
the circle. They used to exist only as rows of coordinates in Settings.

**Zones have addresses.** A new zone is named from its location — "Damstraat 14"
rather than `52.37021, 4.89516` — and you can rename it to whatever you actually
call the place. If the lookup fails, it falls back to coordinates rather than
failing to save.

**Walking directions back to the car**, handed to your maps app.

**The map's own pins are ours now**, and tapping one no longer pops the stock
off-centre bubble.

One honest limit: a takeover alert is not instant and cannot be without push
notifications. The app now checks the moment you open it, on top of the
fifteen-minute floor Android enforces on background work. A phone left in a
pocket still waits.

185 tests, `versionCode` 9.

---

## v0.3.5 — three fixes from a day of driving

**Your car no longer vanishes from the map.** When a park was confirmed but the
GPS fix failed, the app wrote `null` over the location it already had. One line,
two symptoms: the pin disappeared, *and* the app fell into its "we don't know
where you are" branch, which asks what to do. A failed fix now means "we don't
know where you are right now", not "the car is nowhere" — the last known
position stays.

**It no longer asks about a permit that is already yours.** If the permit is on
your own car there is nothing to decide, but every prompt was raised without
checking. Combined with the bug above, this is why the app felt as though it was
ignoring auto-claim: it wasn't, it just never got that far. Auto-claim itself was
never broken — the setting and its default were verified correct.

If the holder can't be read — no network — it still asks. An unanswerable
question beats a wrong assumption about a permit.

**Walking away is recognised sooner.** The "you left the car" threshold drops
from 10 m to 4 m. Detection only begins once the car's Bluetooth has dropped, so
the car is already stationary and this measures you leaving it rather than the
car moving. Not lowered further: fixes are accepted at up to 25 m accuracy, so
two readings from a motionless phone can differ by more than a couple of metres
on noise alone.

The 5-second "sitting still" path is unchanged — it was already as quick as
asked for, and shortening it would only have weakened the guard against a
Bluetooth blip in traffic.

119 tests, `versionCode` 8.

---

## v0.3.4 — three bugs from real use

No new features. Three fixes, all reported from actually driving around with
this thing.

**The permit switch no longer retries forever when you already hold it.** Park
somewhere while the permit is already on your own plate and you would get a
failure notification, then another, then another — indefinitely. The cause:
`switchTo` always called activate, and the API rejects activating a plate that
already holds the session. That rejection looked like a failed switch, and a
failed switch was retried on exponential backoff with no limit.

Now the app reads the current state first and, if the permit is already where
you wanted it, reports success — which it always was. Nothing to activate,
nothing to reject, nothing to retry.

**Retries now stop.** Any switch that keeps failing — wrong credentials, an API
change — used to retry forever and notify on every attempt. It now gives up
after five attempts with a single notification stating plainly that the permit
did **not** move, so you can switch it yourself. Going quiet after giving up
would have been worse than the loop.

**The car pin no longer gets left behind.** The map kept showing your previous
parking spot for an entire drive, because the parked location was recorded when
you parked and never cleared. It is now cleared when your car's Bluetooth
reconnects: at that point the car is wherever you are, so the old pin is wrong
rather than merely stale. It regains meaning the moment you walk away again.

116 tests, `versionCode` 7. Two existing tests were updated deliberately —
`switchTo` now makes two API reads where it made one, since reading first *is*
the fix, so the tests that counted calls needed to count differently. What they
assert about behaviour is unchanged.

---

## v0.3.3 — navigation, and Settings as it was meant to look

Layout only. Detection, claiming, shared state and the collision guard are
untouched; all 114 tests pass unedited.

**Three tabs instead of three floating icons.** Map, refresh and settings used
to sit as bare icons in the middle of the main screen, belonging to nothing.
There is now a proper bottom navigation bar — Permit, Map, Settings — with
labels and a selected state, so the map is permanently one tap away instead of
a detour.

**The app finally says its own name.** "Handoff" appears at the top of the main
screen beside the mark. The design called for this and the implementation plan
quietly dropped it, so until now the name existed only on the launcher icon.

**Your car is on the main screen.** A map card shows where it is parked without
leaving the screen, and tapping it opens the full map. It also fills the space
the previous centred layout left conspicuously empty.

**Settings looks like the prototype again.** It had shipped as verbose blocks:
every item a large heading, an explanatory paragraph, and a full-width cream
button, with section labels *smaller* than the items inside them — so the
hierarchy read backwards. Items are now compact rows: label left, value right,
chevron. `Home zone — Not set ›`. Free zones moved under Zones as a row beside
Home zone rather than standing as its own section.

**Proper map markers.** The Handoff mark for your car and a location dot for
you, replacing osmdroid's stock pins.

Caught by running the app on an emulator rather than by review: the selected-tab
indicator had been given Wasil's identity blue, which would have made blue mean
"selected" rather than "Wasil" — precisely the collision the palette exists to
prevent. It reads as correct in code and is obvious on screen. This release also
adds screenshot tests that render Compose on a device, so that class of bug has
somewhere to be caught in future. `versionCode` 6.

---

## v0.3.2 — hotfix: unreadable screens in dark mode

Fixes a bug that made Settings, the map and the setup screens effectively
blank on a dark-themed phone. Reported immediately after v0.3.1 shipped:
*"the settings part is completely dark and I can't see most of it."*

**Cause.** In Compose, `LocalContentColor` falls back to plain black unless
something up the tree provides a `Surface`. `MainScreen` had one, because its
`Scaffold` supplies a `Surface` implicitly. Settings, the map and both setup
screens were bare `Column`s, so all their text rendered black — on a `#171715`
background, invisible.

**This bug is older than v0.3.1.** Those screens have always lacked a
`Surface`. It never showed because the app was permanently light-themed before
v0.3.1, where black text on a white background looks entirely normal. Adding
dark mode didn't create the fault; it revealed one that had been sitting there
since Phase 2.

**Fix.** One `Surface` at the root of `MainActivity`, wrapping every screen, so
content colour is correct everywhere — including on any screen added later.
Fixing it in one place rather than patching four screens is what stops this
recurring.

Nothing else changed. All 114 tests still pass, untouched. `versionCode` 5.

Why the unit tests didn't catch it: every test in this project is pure Kotlin
logic with no Compose rendering, and every code review reads diffs, where each
file looked locally correct in isolation. Only running the app reveals this
class of bug — which is the argument for on-device screenshots as a release
gate, not an optional extra.

---

## v0.3.1 — Handoff

This release changes how the app looks and reads, not what it does — every
switch, claim, and collision check behaves exactly as it did in v0.3.

**A name, and a mark to go with it.** The app is now called **Handoff**, after
what it actually does: the permit is passed between two cars, not owned by
either. Its mark is two facing arcs with a dot between them — the arcs are the
cars, the dot is the permit. The dot sits against whichever arc holds it, and
that arc lights up while the other dims, so who currently has the permit
survives a glance at any size, on the main screen or in a notification.

**Identity and state, kept apart.** Wasil is slate blue and Walid is
terracotta, and those two colours mean *whose car* and nothing else. Whether
something needs attention is a separate green-and-rust pair, used only on small
icons and labels, never as a card fill. An earlier palette used a desaturated
green and amber for identity, which meant a green card could be read as both
"success" and "Wasil" at once — this release keeps the two systems apart by hue
and by scale so that collision can't happen again.

**Full light and dark palettes.** Both modes are first-class: every identity
and state colour has its own value in light and in dark, with nothing shared
between the two palettes, because a mid-tone that contrasts well on near-black
reads as washed out on near-white. Material's default purple is gone from
every slot it used to leak into.

**A launcher icon that doesn't apologise.** The stock Android robot is
replaced by an adaptive icon built from the same two-arc mark — a static
launcher glyph with the dot centred between the arcs. (The icon itself doesn't
show who's holding the permit; the dot only moves to reflect that inside the
app.)

**One button, because there are only two cars.** The main screen used to offer
two plate buttons; now it offers one — "Hand to Walid" or "Hand to Wasil",
whichever is the only place the permit can currently go. Settings is
reorganised the same way: grouped by how often you actually touch each thing,
with the set-once questions (whose phone this is, the sync URL) moved into a
first-run flow that appears once, and permission and battery requirements
collapsed into a single health row that stays quiet when everything is fine.

No behaviour changed in this release: detection, claiming, shared state and the
collision guard are exactly as v0.3 left them, and all 96 of its tests pass
unedited alongside 18 new ones. That the old tests needed no edits is the
evidence, not just a statistic — a layout change that quietly altered behaviour
would have broken one. 114 tests total, `versionCode` 4.

---

## v0.3 — Phase 3: shared state between the two phones

Tag [`v0.3`](https://github.com/Wasil-W/Car_Parking/releases/tag/v0.3) ·
commit `e072aeb` · `versionCode` 3

The two phones now know about each other, so claiming the permit can no longer
silently strand the other car.

**Collision guard.** Before any switch — automatic, from a notification, or from
the main screen — the app checks the other phone. If that car is parked in a paid
zone, was heard from within six hours, and the permit is currently on its plate,
the switch is blocked with a "Claim anyway" override rather than going through
silently. Taking it anyway alerts the other phone.

**Shared state without the Firebase SDK.** Each phone publishes whether it is
parked in a paid zone, where, and since when, to a Firebase Realtime Database
over plain HTTPS. No SDK, no `google-services.json`. The room both phones share
is derived from the permit username (SHA-256), so there is no pairing step, and
the database URL is entered in Settings and never committed. Heartbeats use
server timestamps, so staleness checks survive phone clock skew.

**Real paid zones.** Amsterdam's 29 official tariff areas are bundled as
polygons, so parking outside every paid area is recognised as free street
parking and claims nothing. Claim notifications show the hourly rate and zone
code.

**Home zone.** A 30–200 m circle per phone. Parking there never claims and never
blocks the other car.

**Automatic give-back.** Park at home or somewhere free while the other car is
still parked in a paid zone and needs the permit, and it is handed back without
you doing anything.

**Personal map.** Your car's last parked spot and your own position. Nothing on
it is shared with the other phone.

**Fixes for problems found in real use during Phase 2:**

- Claim retries now wait for connectivity instead of burning their exponential
  backoff while still offline — the cause of "it worked once, then only manual
  retries worked".
- A confirmed park with no GPS fix now asks instead of claiming blind, which
  previously could claim the permit while parked at home.
- "Free here" reads a fresh location at the moment you tap it, rather than
  storing a stale one.
- Pending claims are cancelled when you get back in the car, so an old failed
  claim can no longer fire while driving.
- Settings gained a "Disable battery optimisation" button; Samsung's app-sleep
  was very likely why detection worked once and then stopped.

**Setup:** one-time Firebase project, URL pasted into Settings on both phones —
see `SETUP_FIREBASE.md`. Both phones must run 0.3 or later for the guard to
work; a phone on an older build never publishes its state and reads as "not
parked".

96 unit tests. Tariff data © Gemeente Amsterdam, CC-BY 4.0.

---

## v0.2 — Phase 2: automatic parked detection

Tag [`v0.2`](https://github.com/Wasil-W/Car_Parking/releases/tag/v0.2) ·
commit `54009d3`

No more remembering to open the app.

**Bluetooth as the park trigger.** When the car's Bluetooth disconnects, the app
starts checking whether you actually parked.

**Confirmation before acting.** Activity Recognition and GPS are sampled for up
to 90 seconds, so a Bluetooth blip while still driving is never acted on. Only a
confirmed park claims the permit.

**Auto-claim** on a confirmed park, still verified read-after-write, with a
persistent notification showing who currently holds the permit. Reconnecting to
the car clears the parked state.

**"Free here"** marks a spot with a 60 m radius so the permit is never touched
there again — intended for home and private parking.

**Settings:** pick your car from paired Bluetooth devices, set whose phone this
is, and turn auto-claim off if you would rather be asked each time.

> **Known issue with this release:** the version number was never bumped. This
> APK reports itself as `versionName` 0.1 / `versionCode` 1, identical to v0.1,
> so the two builds cannot be told apart on-device. Corrected from v0.3 onward.
> If you are unsure which build a phone is running, reinstall.

---

## v0.1 — Phase 1: one-tap switching

Tag [`v0.1`](https://github.com/Wasil-W/Car_Parking/releases/tag/v0.1) ·
commit `c7f54e3` · `versionCode` 1

First working version. Replaces logging into the permit website on a phone
browser and fighting the form.

**One tap** to move the permit between Wasil's and Walid's plate.

**Read-after-write verification.** A 200 response from the activate call is not
treated as proof. Every switch re-reads permit state and compares; a mismatch
raises a loud warning instead of a false success. A wrong permit is a parking
fine, so the app never assumes.

**Transparent re-login.** The API token lives one hour; the app re-authenticates
on a 401 without involving you.

**Credentials stay on the phone,** in `EncryptedSharedPreferences`, entered on a
one-time setup screen. Nothing sensitive is in the repository.

Built on the Phase 0 Python CLI (`permit.py`), which remains the reference for
the API's request and response shapes.
