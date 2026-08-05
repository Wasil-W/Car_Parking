# Use cases — every situation, and what the app does about it

Started 2026-08-02 at Wasil's request: *"we need to make a md file where we
write all the use-cases and also what the following actions are for that use
case. Like home zone → don't ask. or within permit range → switch to permit. in
paying area → start paying tariff."*

**This is a living decision table.** When behaviour changes, change the row
first and the code second — a row here is the specification, and the argument
belongs next to it rather than buried in a commit message. Rows marked
**PLANNED** are not built yet; rows marked **OPEN** cannot be decided until a
real question is answered, and each says which one.

**Re-read against the code at `v0.6.3`, 2026-08-05.** Every row was checked
against the file that implements it, not against the previous version of this
document. That is not ceremony: this repo has twice carried a "hazard" that had
already been fixed, purely because nobody re-opened the code. Three rows turned
out to describe behaviour the app no longer has, and the **Notes** column now
carries anything that is wrong rather than merely undocumented. Notes are
findings, not decisions — what to do about them is Wasil's call.

---

## The two mainframes, and where the line between them goes

The app currently does one thing: it moves a shared permit between two cars.
Wasil wants it to also stand on its own — to handle *paying* for parking where
no permit applies — without the two halves getting in each other's way.

The line that keeps them apart is this:

> **An obligation is not a settlement.**
> *Where you parked* decides whether you owe anything.
> *What you own* decides how that debt gets paid.

Two independent questions, answered by two independent layers:

| Layer | Question | Depends on | Does **not** depend on |
|---|---|---|---|
| **Obligation** | Does this spot require paying at all, and how much? | Position, zone geometry, time of day | Whether you hold a permit, who your brother is, whether a permit exists |
| **Settlement** | How is that obligation discharged? | Permits you hold, their scope, money | Where the car is (that is already answered above) |

`v0.6.1` made the split visible — "This spot" sits above the permit card and
answers the obligation question on its own. The *decision* path is still fused:
`ParkDetectionUseCase` resolves the zone and then assumes the only possible
settlement is "claim the shared permit". That assumption is the thing to remove.
**The permit is one settlement method, not the app's purpose.**

Once they are separate, both of Wasil's goals fall out for free:

- The permit switcher keeps working exactly as it does, as *one* settlement
  method with an unusual property — it is free, and there is only one of it.
- Someone with no shared permit at all still gets a useful app: the obligation
  layer alone tells them what zone they are in and what it costs, and the
  settlement layer offers to pay.
- Neither half needs to know the other exists.

**Settlement methods, ranked by preference:** free (nothing owed) → permit
(costs nothing, but there is only one and it may be contested) → paid session
(always available, costs money). The app should never spend money when a free
option is available, and never take a contested resource when nothing is owed.

---

## What the app can know

Every rule below is written against these inputs and nothing else. If a
proposed rule needs an input that is not on this list, that is the first thing
to notice about it.

| Input | Source | Reliability |
|---|---|---|
| The phone is in the car | The car's Bluetooth link, from connect to disconnect | Exact, and it gates everything below it |
| Where the car was during the drive | A position sampled every 20 s while that link is up | Deliberately unusable on its own — it is a `LiveLocation`, and nothing on the claim path accepts one |
| Car is parked | Bluetooth disconnect, then activity recognition and GPS displacement | Good; `Unclear` after 90 s |
| Where it parked | A fix taken at the disconnect; else the phone's cached fix if under 2 min old; else the drive's last sample if under 3 min old | Usually available since `v0.6.2`, **but can still be absent**. No accuracy floor — the 25 m bar applies only to the walked-away test |
| Which zone that is | `ZoneResolver` — home circle, then free circles, then 29 tariff polygons | Exact given a position. Nothing outside Amsterdam |
| What the zone costs, right now | Bundled `amsterdam_tarieven.json`, read through the `v0.6.0` schedule engine | Correct for the moment asked. **Display only** — no claim decision reads it |
| Who holds the permit | Live read of the permit site | Authoritative, needs network |
| Whether the other car is on a paid street | Firebase: `parkedOutside`, `parkedAtMs`, `heartbeatAtMs` | Stale after 6 h — then untrusted. **Not *where* it is:** coordinates, zone code and rate all left the wire in `v0.6.2`–`v0.6.3` |
| Auto-claim on or off | Local setting, default on | Exact |

Three things the app **cannot** know today, each of which blocks a row below:

- **Whether a given permit is valid in a given zone.** It assumes city-wide.
  Partly narrowed since this was written: the city publishes 107 permit-parking
  zones, so *where permit parking exists at all* is now answerable from open
  data — but whether Wasil's permit covers all of them is a question about his
  permit, not about the city. See [`v0.6-zone-registry.md`](v0.6-zone-registry.md).
- **Whether a zone is permit-only or pay-only.** Also moved: the `parkeerzones`
  layer is `gebruiksdoel=VERGUNP` for all 107 entries and carries no `BETAALDP`
  at all, so it is a permit-coverage layer rather than the either/or switch this
  line originally assumed. None of it is in the app yet.
- **Whether anyone has already paid for this spot by other means.**

---

## The table

### A. Driving, not parked, or not sure

The drive itself is now part of this table. `v0.6.2` made the Bluetooth link a
first-class input rather than just the thing that starts detection.

| # | Situation | Action | Why | Status | Notes |
|---|---|---|---|---|---|
| A1 | Bluetooth dropped, then driving resumes | Nothing | A blip in traffic must never move the permit | **BUILT** | A confident `IN_VEHICLE` sample beats every other signal, and a real reconnect cancels the whole pending chain (A5) |
| A2 | Parked, 90 s go by with no verdict either way | Resolve the position anyway. If the spot is **not** paid: quiet status, no question. A paid spot — or no position — still asks | Weak signals mean indoors, and indoors correlates with home, so "always ask" fired hardest where there was nothing to decide | **BUILT** | **Changed in `v0.6.2`.** This row used to read "Ask" and that is no longer what happens. Only ever the quiet direction: unclear is never grounds to claim |
| A3 | Parked, no position at all — no fix now, nothing usable from the drive | Ask. Both screens say the location is unknown. The use case publishes no verdict about the spot | A failed fix means "we don't know where you are", not "the car is nowhere" | **BUILT** | Two corrections. **The old pin is not kept** — it was cleared when the car connected (`v0.3.4`), so there is nothing to keep and the map says "Parked — but the location is unknown". And **the other phone is still told `parkedOutside = false`**: the connect handler wrote that at the start of the drive and this branch does not overwrite it, so the value on the wire is the one `v0.6.2` set out to stop publishing. `parkedOutsideKnown` records the difference locally and **nothing reads it** — `SyncStateWorker` publishes three fields and that is not one of them |
| A4 | App not set up (no car chosen) | Nothing | Nothing can be decided yet | **BUILT** | Sampling (A5) is gated on the car's Bluetooth address being set, not on the permit being configured, so an app with a paired car and no chosen driver samples positions it will never use |
| A5 | **The car's Bluetooth connects** — a drive begins | Cancel any pending detection, claim or give-back; clear the parked pin, the previous drive's trail and the paid-street flag; publish "not parked outside"; start sampling every 20 s | Getting back in the car un-answers every question the last park raised. The pin especially: left standing it pointed at the previous spot for the whole drive | **BUILT** | The permit is **not** handed back on driving off — it moves only when the other car claims it, or when this one parks somewhere free (B4) |
| A6 | **The car's Bluetooth disconnects** — a drive ends | Mark the link down *first*, then stop sampling, then start detection | Order is the safety story: the link flag is what unlocks the drive's last position, and a poll still in flight is the one thing that could write a position after the car stopped | **BUILT** | |
| A7 | The drive's last sample is more than 3 minutes old at the disconnect | Refuse it. The park is treated as having no position — i.e. A3 | A trail dead that long is evidence of where the phone last saw the sky, not where the car stopped. The oldest thing it can hold is the driveway you left, and sealing *that* would say "parked at home" while the car sits in town | **BUILT** | The true worst case is five minutes, not three: a sample is stamped when taken, and what is taken may itself be a cached fix up to 2 min old. Left that way deliberately |
| A8 | A sample lands after the car stopped, or the link comes back mid-detection | Nothing can come of it. The claim path will not accept a driving position | Not a convention — a type. A `LiveLocation` never yields its coordinates, and the one door out refuses while the link is up, so a late poll does not compile into a claim | **BUILT** | The structural half of the promise made in `v0.6.2`: only the disconnect-moment position may ever trigger a zone lookup, a switch or a purchase |

### B. Parked where nothing is owed

The obligation layer answers "nothing", so the settlement layer is never
consulted. **This is the section where the app must stay silent.**

| # | Situation | Action | Why | Status | Notes |
|---|---|---|---|---|---|
| B1 | **In the home zone** | Don't ask, don't claim, don't pay. Show a quiet status | Wasil's rule. Parking at home is the most common thing that happens and must never generate a question | **BUILT** | |
| B2 | In a hand-marked free zone | Same as B1, named after the zone | The user has asserted this spot is free; trust it over geometry | **BUILT** | Also reachable on demand: "Free here" on the prompt marks a 60 m zone at a *freshly read* position and drops the paid-street flag |
| B3 | On a street outside every paid polygon | Same as B1 | Free street parking is free | **BUILT** | |
| B4 | Any of B1–B3 **while holding the permit** | With auto-claim **on**, hand it back without asking. With auto-claim **off**, ask — and only when the other car is actually parked outside on a fresh heartbeat | The other car may be waiting for a resource this one no longer needs | **BUILT** | This row used to say "offer it back". With auto-claim on there is no offer, it simply moves. Either way nothing happens unless the other car is parked outside *and* the permit is on this plate |
| B5 | Outside Amsterdam entirely | Treat as B3, and say so | No tariff data means no obligation the app can see. Saying "no paid zone here" without admitting it only knows Amsterdam would be a lie by omission | **PLANNED** | Unchanged: `ZoneResolver` returns `FreeStreet` for Utrecht exactly as it does for a free Amsterdam street, and nothing distinguishes them on screen |
| B6 | Inside a paid polygon but **outside its charging hours** — 20:00 in a `09-19` zone, or a Sunday | Claims the permit anyway, and publishes "on a paid street". The strip reads "Free · from 09:00" while the permit moves | The claim decision is geometry only; the clock is display | **BUILT**, undecided | Two honest readings. For an overnight park it is probably right — the car is still there at 09:00 and nothing would wake the app to claim then. But it takes a contested resource while nothing is owed, and the flag it publishes **blocks the other car** (C3) during hours when this one owes nothing. Nowhere is either reading written down. Worth deciding rather than leaving to the polygon |

### C. Parked where something is owed — settled by the permit

| # | Situation | Action | Why | Status | Notes |
|---|---|---|---|---|---|
| C1 | **In a paid area, permit already on my plate** | Nothing but a status | There is nothing to decide. Being asked anyway is what made the app feel broken (`v0.4`) | **BUILT** | |
| C2 | In a paid area, permit on the other car, other car **not** parked outside | Claim it | Free to take, nobody is relying on it | **BUILT** | Same when the other phone has no state at all |
| C3 | In a paid area, permit on the other car, other car **is** parked outside | Do **not** claim silently. Warn, and require an explicit "claim anyway" | Taking it strands their car unpermitted — a real fine, paid by someone who did not choose it | **BUILT** | Re-checked against a live read when the notification is opened, so a brother who has since driven off does not leave a dead warning on screen |
| C4 | As C3, but their status is over 6 h old | Treat as C2 | A stale heartbeat is not evidence they are still there | **BUILT** | The heartbeat is stamped by the server on every write, so the two phones' clocks never enter into it |
| C5 | In a paid area, auto-claim switched off | Ask, unless it is already ours (C1) | The setting means "ask me", not "ask me things I already know" | **BUILT** | |
| C6 | The permit is claimed away from a car that is **parked in a paid area** | Notify that phone it moved — within 15 min, or the moment the app is next opened | Silent takeover of a shared resource is how trust in the app dies | **BUILT** | This row used to say "while the other brother is mid-drive", and that is the one case where **no** notification arrives: the takeover check lives in the heartbeat, and the heartbeat is cancelled while driving. Mid-drive it is also the case that matters least — they are not parked, so nothing is exposed — but the row was describing behaviour the app does not have |
| C7 | The pin is corrected and the zone flips free → paid | Offer to claim. Never claim automatically | The user is standing next to the car and just supplied better information than GPS had; asking costs one tap (`v0.5.0`) | **BUILT** | The paid-street flag is written and published on **confirm**, before the permit question is answered. Answer "Not now" and the other phone is blocked from claiming by a car that does not hold the permit |
| C8 | The pin is corrected and the zone flips paid → free | Offer to hand back | Same reasoning, mirrored | **BUILT** | Mirrored here too: "Not now" leaves this car holding the permit while the other phone has already been told the spot is free |
| C9 | The pin is corrected and paid/free does not change | Move the pin, re-sync, ask nothing | Nothing about the obligation changed, so there is nothing to put to the user | **BUILT** | |
| C10 | A correction more than 300 m from **where detection put the car** | Refuse it, and say how far | That is not a correction, it is a different parking spot — and detection will find that one on its own. Anchored to the detected point so the cap cannot be walked around 300 m at a time | **BUILT** | |
| C11 | In a paid area, but the **tariff data cannot be read at all** | Claim as normal; the notification says "paid area (zone data unavailable)" | Claiming a free permit costs nothing; not claiming costs a fine. The bias is deliberate | **BUILT**, half wrong | The claim is right and the screen is not: with no readable area the "This spot" strip reads **"Nothing to pay — outside a paid zone"** while the app is claiming the permit for a paid one. The code comment next to it says a guess in that direction costs a fine; the code returns the guess anyway |
| C12 | In a paid area, and the permit holder cannot be read (network down) | Ask | An unanswerable question beats a wrong assumption, and assuming it is already ours is the expensive direction | **BUILT** | |

### D. Parked where something is owed — settled by paying

**None of this exists yet** — verified, not assumed: there is no payment code in
the app. This is the standalone half, and it is what makes the app useful to
someone who has no shared permit at all.

| # | Situation | Action | Why | Status | Notes |
|---|---|---|---|---|---|
| D1 | In a paid area, **no permit configured** | Show the zone, the rate and the hours; offer to start a paid session | The whole point of standalone mode. Today the app would say "not configured" and stop | **PLANNED** | The showing half is closer than this row implies: `v0.6.0`–`v0.6.1` already put the live rate on screen. It is the offering half that is blocked |
| D2 | In a paid area, permit exists but **is not valid here** | Skip the permit entirely; offer to pay | The permit is not a settlement method for this obligation, so it should not appear as one | **OPEN** — needs Q1 | Detecting it is no longer hypothetical: the city's 107 permit zones say where permit parking exists at all. What is still unknown is whether *this* permit covers a given one |
| D3 | In a paid area, permit valid but **already in use by the other car** | Offer to pay rather than contest it | This is the case that makes the two mainframes worth combining: today C3 offers only "strand your brother or do nothing". Paying is the third answer, and it is the correct one | **OPEN** — needs Q1, Q3 | |
| D4 | A paid session is running and the car leaves | Stop the session | Paying for an empty space is the clearest possible waste | **PLANNED** | The trigger already exists and is reliable — A5 |
| D5 | A paid session is running and the tariff window ends | Stop the session, say what it cost | Tariff windows are display-only today because the permit has no hour budget. Money changes that: `ma-za 09-19` means something the moment a meter is running | **PLANNED** | The schedule engine answers this already; nothing acts on the answer |
| D6 | Free parking begins shortly (e.g. 18:55 in a `09-19` zone) | Say so before charging anything | Charging for five minutes of a free evening is exactly the kind of small betrayal that gets an app deleted | **PLANNED** | Same engine, same answer — "until 19:00" is on screen today. See also B6, which is the free-permit version of this and is decided the other way |

### E. Where the two mainframes touch

The only rows where both layers are in play at once. **Everything else in this
document belongs to exactly one of them** — which is the point of the split.

| # | Situation | Action | Why | Status | Notes |
|---|---|---|---|---|---|
| E1 | Both settlements available, permit is free | Prefer the permit | Never spend money when a free option exists | **PLANNED** | |
| E2 | Permit available but contested (C3), paying available | Offer both, with the cost of each stated — a fine risk versus a real hourly rate | The user is the only one who can weigh "my brother gets a fine" against "I pay €5.37/h" | **OPEN** — needs Q3 | Each phone can price its own spot; neither publishes it any more (`v0.6.3`), so stating *their* cost would need the rate back on the wire — and that was removed on purpose |
| E3 | Paid session running, permit becomes free | Offer to switch and stop paying | Money already spent is gone; money about to be spent is not | **PLANNED** | |
| E4 | Two brothers, two cars, both in paid areas, one permit | Whoever claims first keeps it; the other is offered paying | Today the second one simply loses. This is the concrete win from combining the halves | **OPEN** — needs Q1, Q3 | Deciding it by *rate* instead was built in `v0.6.2` and deleted in `v0.6.3`: with no way to pay it would only change which brother is fined. Recoverable from git, but do not rebuild it before Q3 is answered |

---

## What has to be answered before D and E can be built

These are the "test subjects" Wasil is after. **The questions are about how
their permits behave, not about their accounts** — no one's login should ever
change hands, and none is needed to answer any of this.

**Q1 — How does a permit's scope vary? Partly answerable now.**
Wasil's visitor permit covers the whole of Amsterdam and can be switched
between plates freely. That may be unusual. What the open data settled on
2026-08-04 is the city's half: `parkeerzones` lists **107 permit zones**, every
one `gebruiksdoel=VERGUNP`, with real names and geometry — so *where permit
parking exists at all* no longer needs asking anyone. Full detail in
[`v0.6-zone-registry.md`](v0.6-zone-registry.md).

What remains is about the permit rather than the city, and still needs someone
whose permit differs from Wasil's: is it restricted to a district or a single
zone? Does it name one plate permanently, or can it move? Is there an hour
budget, and does activating cost anything? Every one of these changes what D2
and D3 mean, and two of them (scope, budget) would invalidate assumptions the
current app makes silently.

**Q2 — Which zones are permit-only and which are pay-only? Answered, in an
unexpected direction.**
The bundled tariff file cannot tell them apart; all 29 areas are base-rate paid
regions. The expectation was that `gebruiksdoel` would sort them into `VERGUNP`
and `BETAALDP`. It does not — the `parkeerzones` collection is `VERGUNP` for all
107 and contains no `BETAALDP` at all. So the two datasets are complementary
rather than rival: the tariff file says what an hour costs, this one says where
a permit is the currency. That is enough to detect D2. **None of it is in the
app yet** — it is verified data in a document, which is not the same thing.

*A trap worth not falling into twice:* the `V` in tariff codes like `T11V` means
**round-the-clock**, not *vergunning*. Every `V` code carries `ma-zo 00-24` and
no other code does. It is not a hidden permit marker.

**Q3 — Can a paid session actually be started programmatically?**
Everything in D and E assumes so. The permit site is one thing; a payment
provider is another, with real money and real liability. Until there is a known
route, D and E are design only. **This is the single biggest unknown in the
whole plan** — unchanged, and it still blocks every paid row in this file.

**The ideal test subject:** someone who both holds a permit *and* pays for
parking in Noord. Wasil raised exactly this, and it is still the right person —
though Q2 has since been answered from open data, so what they are now worth
asking about is Q1, plus *how* they pay, which is where the Q3 research starts.

---

## How to use this file

- **Adding behaviour:** write the row first. If it will not fit the table
  without a new input, that is the real finding.
- **Fixing a bug:** find the row it violates. If there is no row, the bug is
  that the situation was never decided — add it.
- **Reviewing a change:** every row it touches should still read true
  afterwards.
- **Contradictions are bugs in this file**, and worth more than the code fix
  they imply.
- **A Notes entry is not a decision.** It records that the code and this table
  disagree. Closing one means either changing the code or changing the row —
  and saying which.

Related: [`TIMELINE.md`](TIMELINE.md) for what shipped and what is next,
[`CONDITION-ACTION-AUDIT.md`](CONDITION-ACTION-AUDIT.md) for the same ground
walked from the code side (**read against `v0.6.1`** — its headline bug is fixed,
so check before trusting a row), [`BACKLOG.md`](BACKLOG.md) for sequencing, and
[`v0.6-zone-registry.md`](v0.6-zone-registry.md) for the zone data that Q1, Q2
and most of D depend on.
