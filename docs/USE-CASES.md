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

Today `ParkDetectionUseCase` fuses them: it resolves the zone and then assumes
the only possible settlement is "claim the shared permit". That assumption is
the thing to remove. **The permit is one settlement method, not the app's
purpose.**

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
| Car is parked | Bluetooth disconnect + activity recognition + GPS displacement | Good; `Unclear` after 90 s |
| Where it parked | Fresh GPS fix, else a cached fix under 2 minutes old | Accurate to ~25 m; **can be absent** |
| Which zone that is | `ZoneResolver` — home circle, free circles, then 29 tariff polygons | Exact given a position |
| What the zone costs | Bundled `amsterdam_tarieven.json` | Static; informational only |
| Who holds the permit | Live read of the permit site | Authoritative, needs network |
| Where the other car is | Firebase, `parkedOutside` + heartbeat | Stale after 6 h — then untrusted |
| Auto-claim on or off | Local setting, default on | Exact |

Three things the app **cannot** know today, each of which blocks a row below:
whether a given permit is valid in a given zone (it assumes city-wide), whether
a zone is permit-only or pay-only (`gebruiksdoel` — see
[`v0.6-zone-registry.md`](v0.6-zone-registry.md)), and whether anyone has
already paid for this spot by other means.

---

## The table

### A. Not parked, or not sure

| # | Situation | Action | Why | Status |
|---|---|---|---|---|
| A1 | Bluetooth dropped, then driving resumes | Nothing | A blip in traffic must never move the permit | **BUILT** |
| A2 | Parked, no signal resolves within 90 s | Ask | An unanswerable question beats a wrong assumption | **BUILT** |
| A3 | Parked, no position available at all | Ask, and keep the old pin | A failed fix means "we don't know where you are", not "the car is nowhere" (v0.4.2) | **BUILT** |
| A4 | App not set up (no car chosen) | Nothing | Nothing can be decided yet | **BUILT** |

### B. Parked where nothing is owed

The obligation layer answers "nothing", so the settlement layer is never
consulted. **This is the section where the app must stay silent.**

| # | Situation | Action | Why | Status |
|---|---|---|---|---|
| B1 | **In the home zone** | Don't ask, don't claim, don't pay. Show a quiet status. | Wasil's rule. Parking at home is the most common thing that happens and must never generate a question | **BUILT** |
| B2 | In a hand-marked free zone | Same as B1, named after the zone | The user has asserted this spot is free; trust it over geometry | **BUILT** |
| B3 | On a street outside every paid polygon | Same as B1 | Free street parking is free | **BUILT** |
| B4 | Any of B1–B3 **while holding the permit** | Additionally offer it back | The other car may be waiting for a resource this one no longer needs | **BUILT** |
| B5 | Outside Amsterdam entirely | Treat as B3, and say so | No tariff data means no obligation the app can see. Saying "no paid zone here" without admitting it only knows Amsterdam would be a lie by omission | **PLANNED** |

### C. Parked where something is owed — settled by the permit

| # | Situation | Action | Why | Status |
|---|---|---|---|---|
| C1 | **In a paid area, permit already on my plate** | Nothing but a status | There is nothing to decide. Being asked anyway is what made the app feel broken (v0.4) | **BUILT** |
| C2 | In a paid area, permit on the other car, other car **not** parked outside | Claim it | Free to take, nobody is relying on it | **BUILT** |
| C3 | In a paid area, permit on the other car, other car **is** parked outside | Do **not** claim silently. Warn, and require an explicit "claim anyway" | Taking it strands their car unpermitted — a real fine, paid by someone who did not choose it | **BUILT** |
| C4 | As C3, but their status is over 6 h old | Treat as C2 | A stale heartbeat is not evidence they are still there | **BUILT** |
| C5 | In a paid area, auto-claim switched off | Ask, unless it is already ours (C1) | The setting means "ask me", not "ask me things I already know" | **BUILT** |
| C6 | Permit claimed while the other brother is mid-drive | Notify them it moved | Silent takeover of a shared resource is how trust in the app dies | **BUILT** |
| C7 | The pin is corrected and the zone flips free → paid | Offer to claim. Never claim automatically. | The user is standing next to the car and just supplied better information than GPS had; asking costs one tap (v0.5.0) | **BUILT** |
| C8 | The pin is corrected and the zone flips paid → free | Offer to hand back | Same reasoning, mirrored | **BUILT** |

### D. Parked where something is owed — settled by paying

**None of this exists yet.** This is the standalone half, and it is what makes
the app useful to someone who has no shared permit at all.

| # | Situation | Action | Why | Status |
|---|---|---|---|---|
| D1 | In a paid area, **no permit configured** | Show the zone, the rate and the hours; offer to start a paid session | The whole point of standalone mode. Today the app would say "not configured" and stop | **PLANNED** |
| D2 | In a paid area, permit exists but **is not valid here** | Skip the permit entirely; offer to pay | The permit is not a settlement method for this obligation, so it should not appear as one | **OPEN** — needs Q1 |
| D3 | In a paid area, permit valid but **already in use by the other car** | Offer to pay rather than contest it | This is the case that makes the two mainframes worth combining: today C3 offers only "strand your brother or do nothing". Paying is the third answer, and it is the correct one | **OPEN** — needs Q1, Q3 |
| D4 | A paid session is running and the car leaves | Stop the session | Paying for an empty space is the clearest possible waste | **PLANNED** |
| D5 | A paid session is running and the tariff window ends | Stop the session, say what it cost | Tariff windows are display-only today because the permit has no hour budget. Money changes that: `ma-za 09-19` means something the moment a meter is running | **PLANNED** |
| D6 | Free parking begins shortly (e.g. 18:55 in a `09-19` zone) | Say so before charging anything | Charging for five minutes of a free evening is exactly the kind of small betrayal that gets an app deleted | **PLANNED** |

### E. Where the two mainframes touch

The only rows where both layers are in play at once. **Everything else in this
document belongs to exactly one of them** — which is the point of the split.

| # | Situation | Action | Why | Status |
|---|---|---|---|---|
| E1 | Both settlements available, permit is free | Prefer the permit | Never spend money when a free option exists | **PLANNED** |
| E2 | Permit available but contested (C3), paying available | Offer both, with the cost of each stated — a fine risk versus a real hourly rate | The user is the only one who can weigh "my brother gets a fine" against "I pay €5.37/h" | **OPEN** — needs Q3 |
| E3 | Paid session running, permit becomes free | Offer to switch and stop paying | Money already spent is gone; money about to be spent is not | **PLANNED** |
| E4 | Two brothers, two cars, both in paid areas, one permit | Whoever claims first keeps it; the other is offered paying | Today the second one simply loses. This is the concrete win from combining the halves | **OPEN** — needs Q1, Q3 |

---

## What has to be answered before D and E can be built

These are the "test subjects" Wasil is after. **The questions are about how
their permits behave, not about their accounts** — no one's login should ever
change hands, and none is needed to answer any of this.

**Q1 — How does a permit's scope vary?**
Wasil's visitor permit covers the whole of Amsterdam and can be switched
between plates freely. That may be unusual. Needed from someone whose permit
differs: is it restricted to a district or a specific zone code? Does it name
one plate permanently, or can it move? Is there an hour budget, and does
activating cost anything? Every one of these changes what D2 and D3 mean, and
two of them (scope, budget) would invalidate assumptions the current app makes
silently.

**Q2 — Which zones are permit-only and which are pay-only?**
The bundled tariff file cannot tell them apart; all 29 areas are base-rate paid
regions. Amsterdam's `parkeerzones` API carries `gebruiksdoel` (`VERGUNP` /
`BETAALDP`), which is exactly this distinction. Full detail in
[`v0.6-zone-registry.md`](v0.6-zone-registry.md). Without it, D2 cannot even be
detected, let alone acted on.

*A trap worth not falling into twice:* the `V` in tariff codes like `T11V` means
**round-the-clock**, not *vergunning*. Every `V` code carries `ma-zo 00-24` and
no other code does. It is not a hidden permit marker.

**Q3 — Can a paid session actually be started programmatically?**
Everything in D and E assumes so. The permit site is one thing; a payment
provider is another, with real money and real liability. Until there is a known
route, D and E are design only. **This is the single biggest unknown in the
whole plan** — it is the difference between v0.6.0 being a real release and
being a document.

**The ideal test subject:** someone who both holds a permit *and* pays for
parking in Noord, since they can answer Q1 and Q2 from the same trip. Wasil
raised exactly this.

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

Related: [`BACKLOG.md`](BACKLOG.md) for sequencing,
[`v0.6-zone-registry.md`](v0.6-zone-registry.md) for the zone data that Q2 and
most of D depend on.
