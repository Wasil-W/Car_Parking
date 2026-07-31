# What comes after v0.4 — a brainstorm

Written 2026-07-31 while v0.4 was being built, for Wasil to argue with. Nothing
here is decided. Where a choice is genuinely his, it is marked **open**.

Shipped so far: `v0.1` switching · `v0.2` detection · `v0.3` shared state ·
`v0.3.1` branding · `v0.3.2` dark-mode hotfix · `v0.3.3` navigation and layout ·
`v0.3.4` three bugs. `v0.4` in progress: tappable notifications, map and zones,
walking directions.

---

## v0.4.1 — pay off what the last four releases left behind

A patch. No new features, and the case for it is that the debt is now large
enough to slow the next feature down.

**Two things v0.4 deliberately deferred.** Notification icons that reflect who
holds the permit, and clearing a pending decision when the situation that
raised it lapses — the other car drove off, so "claim anyway?" is now a
question about nothing. The second is the more important: a stale decision is
worse than no decision, because acting on it does something you no longer want.

**Three brittle couplings reviews have flagged twice each.**

The primary button finds its target by matching a *display string*:
`options.first { it.label == action.target.label() }`. It cannot fail today
because both sides come from hard-coded literals, but its failure mode is a
silently disabled button. `holderFor` is worse — it maps any unrecognised label
to Walid, so a typo would light the wrong brother's arc. One shared
`MyCar ↔ PlateOption` mapping retires both.

The Settings health row dispatches its fix action on the row's *label text*:
`when (fix) { "Grant" -> …; else -> battery }`. A third row silently inherits
the battery-optimisation intent. That is a wrong-action bug waiting for a
fourth health row, and it is why the "car not paired" row currently has no
"Pair" button — wiring one would have opened the wrong screen. Make `fixLabel`
a sealed action and the compiler enforces it.

**The app has two voices.** In-app copy is calm and sentence-cased; notification
copy still shouts — `WARNING:`, `FAILED`, `NOT`, `WITHOUT`, `check the website!`.
That wording was frozen through the layout releases on purpose, and it was the
right call then. It is now the loudest inconsistency left. Worth asking whether
the shouting earns its keep: "Permit switch failed — the permit did not move"
carries the same urgency without the capitals.

---

## v0.5 — the features that need real design

**Addresses instead of coordinates.** Wasil's complaint: home is stored as
`52.37021, 4.89516`, and free zones are labelled by the day they were marked
rather than where they are. Android's built-in `Geocoder` is free and needs no
API key. The work is small; the reason it keeps slipping is that it makes a
network call, so it is behaviour, not layout.

Worth deciding: is the address a *display* label computed on the fly, or is it
stored with the zone at creation time? Storing it is faster and works offline
but goes stale if a street is renamed. Computing it is always right but needs a
network round-trip. **Open** — though I would store it, since a zone's name is
about how you remember the place, not about cartographic accuracy.

**Tariff areas on the map.** The 29 Amsterdam polygons are already bundled and
already drive claiming decisions; they are simply invisible. Drawing them would
make the app's behaviour legible — you could see *why* it claimed here and not
there. Cheap, since the data and the point-in-polygon test already exist.

**Correcting the parked pin.** GPS drift sometimes puts the car across the
street. Dragging the pin is the obvious fix, and there is a real question
behind it: **should a corrected position update what Walid's phone reads?**
If yes, a mis-drag could wrongly unblock or block him. If no, the two phones
disagree about where your car is. **Open, and the answer matters more than it
looks.** My instinct is that it should update shared state but only within a
small radius of the detected position — a correction, not a relocation.

**The QR code.** Wasil liked this. The clearest use: show a QR on one phone
that the other scans to join the same Firebase room, replacing the manual URL
paste in `SETUP_FIREBASE.md`. It turns the fiddliest part of setup into a
five-second action, and it is the first feature that would matter to anyone
beyond the two of them.

**The home-screen widget.** Also liked, and the honest way to see who holds the
permit without opening anything — the launcher icon cannot do it, as v0.3.1
established. It wants the map work settled first, because it should show the
same visual language for "who has it right now".

---

## Beyond — the direction that changes what this is

**In-app payment.** Wasil's own framing: *"most people don't have this permit
but are always paying people. If we automate that, that would be amazing."*

This is the one idea here that changes the product rather than improving it.
Everything built so far serves two brothers with one permit; paying for street
parking automatically would serve anyone who parks in Amsterdam. It also
inverts a decision already made: tariff *comparison* was dropped as pointless
because holding the permit is free — but the moment money is involved, the
cheaper zone matters, and the time windows that are currently display-only
become the difference between paying and not paying.

It needs, at minimum: a payment provider, a real account system, an operator
integration for street parking, and a legal position on charging people. That
is a different project wearing this one's clothes. Worth naming as the
destination without pretending it is the next step.

**Multiple cars.** Also Wasil's, and the natural precondition for anything
public: let a user register several cars and map each to a Bluetooth device.
The architecture is closer to this than it looks — `MyCar` is an enum of two,
but the claim guard, shared state and zone logic are all indifferent to how
many cars exist. The two-car assumption is mostly in the UI, where "the permit
can only move to one place" is what justifies a single button.

**NFC tags.** Wasil's read was right: this is for other people, not for him. A
tag in the car that triggers a claim on tap is a good answer for someone whose
car has no Bluetooth — which is exactly the case Phase 2 deliberately set aside.

**Wear OS.** Liked, not now. Fine as a later thin client over the same state.

---

## What I would actually do next

**v0.4.1 before v0.5.** Two of the deferred items are correctness problems
rather than tidiness — a stale decision that can still be acted on, and a
health row that opens the wrong settings screen. Both are small, and both get
harder to fix once more features sit on top.

Then **v0.5 led by addresses and tariff areas on the map**, because they make
the app explain itself: where your zones are in words you recognise, and why it
claimed where it did. The QR code and the widget are better once the map has
settled its visual language.

The two genuinely open questions I need answers to before starting v0.5 are the
pin-correction one (does it update shared state?) and the address one (stored
or computed?). Neither blocks v0.4.1.
