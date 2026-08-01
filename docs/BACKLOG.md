# Backlog

Running list of deferred work. **Read this before designing or planning any
update** — it records decisions already made and problems already diagnosed, so
they don't get re-litigated or forgotten.

Versioning convention agreed 2026-07-29: layout/visual-only releases get a
patch bump (`0.3.1`), releases that change behaviour get a minor bump (`0.4`).

---

## v0.3.1 — layout and branding, shipped

Shipped the identity and layout described in
`docs/superpowers/specs/2026-07-30-phase31-ui-design.md`. No functional
changes: detection, claiming, shared state and the collision guard are
exactly as Phase 3 left them.

- Brand identity: name **Handoff**, the two-arc mark with a travelling dot,
  flat charcoal icon field.
- **Identity colour is slate blue for Wasil and terracotta for Walid** — not
  the sage-teal/clay-ochre pairing an earlier draft of this file described
  (see "Locked decisions" below for why that was rejected). Identity has
  three roles, each with its own value per light and dark mode — nothing is
  shared between the two palettes:

  | Role | Dark | Light | Where |
  |---|---|---|---|
  | Wasil strong | `#5A7D9A` | `#45657F` | Lit arc, hero card border, hand-over button |
  | Wasil container | `#1E2A33` | `#E8EDF2` | Hero card background only |
  | Wasil on-container | `#A8C0D4` | `#2F4A5F` | Text/plate inside that card |
  | Walid strong | `#B07B55` | `#8A5C39` | Same three uses, mirrored |
  | Walid container | `#2C2118` | `#F3E9E0` | Hero card background only |
  | Walid on-container | `#D9B48F` | `#6B4529` | Text/plate inside that card |

  Whole-branch review (2026-07-30) found that Material's *generic* slots
  (`primary`, `primaryContainer`, `onPrimaryContainer`, `inversePrimary`,
  `surfaceTint`) had been wired to Wasil's blue in both colour schemes, so
  every stock `Button`, `Switch`, `Slider`, `RadioButton`, text-field focus
  ring and progress indicator rendered in his colour — on **both** phones.
  Colour would have meant "interactive", not "Wasil". Fixed: those generic
  slots are now neutral, drawn from the same warm grey/cream family as the
  rest of the palette. Identity colour appears **only** where code asks for
  it explicitly, via `HandoffColors.strongFor` / `containerFor` /
  `onContainerFor` — the hand-over button is the one generic-looking control
  that legitimately carries identity, and it does so through that API, not
  through a ColorScheme slot.
- Real Compose theme: light + dark colour schemes, typography, shapes,
  replacing the bare `MaterialTheme {}` (previously baseline purple,
  light-only).
- Replaced the legacy `android:Theme.Material.Light.NoActionBar` XML parent.
- Adaptive launcher icon (previously `@android:drawable/sym_def_app_icon`,
  the stock Android robot), plus a redrawn notification small icon that
  actually fills its 24x24 viewport.
- Main screen: single "hand it over" action instead of two plate buttons,
  colour-coded hero card, icon row for map/refresh/settings.
- Settings restructured — one-time setup moved into a first-run flow; a
  System health row set covers what that flow can skip (permissions,
  battery, car pairing, sync configuration, home zone), and the Setup
  summary's tick is derived from that same state rather than hard-coded.
- Edge-to-edge (`enableEdgeToEdge()`), required from Android 15, with insets
  handled on every screen so headings and bottom buttons stay clear of the
  system bars.

---

## v0.4 — from real use, 2026-07-31

Four things Wasil hit driving with v0.3.4 installed. The first three are one
root cause plus one omission; the fourth is a design change.

### 1. A failed GPS fix erases the car's location — HIGH

`ParkDetectionUseCase.confirmedPark()` writes the location unconditionally:

```kotlin
stateStore.lastParkLocation = point   // point may be null
if (point == null) { … askManualDecision() }
```

When the fix fails, `null` is written over a perfectly good stored location.
Two of Wasil's complaints come from this single line:

- *"Sometimes the car disappears when I left him for too long, even though the
  location shouldn't be changed."* Every park without a fix wipes the pin.
- *"I think we broke the auto-claim, it asks always."* Every park without a fix
  takes the no-GPS branch, which asks — bypassing `autoClaim` entirely.

**Auto-claim itself is not broken.** The Settings toggle writes to the store
correctly and the stored default is `true` (`prefs.getBoolean("auto_claim",
true)`). Checked, because the obvious theory pointed at the wrong fix.

Fix: never overwrite a known location with `null`. A missing fix means "we don't
know where you are now", not "the car is nowhere". Keep the previous pin and
mark it stale if needed.

### 2. It asks to claim a permit that is already yours — HIGH

*"When the permit was on my name it still asked, and I genuinely thought: why
would that happen?"*

Right. None of the four paths that call `askManualDecision()` check who holds
the permit first. If it is already on your plate there is nothing to decide.

Fix: read the active plate before asking. Already yours → show the ongoing
"permit on your car" status and ask nothing. This also removes most of the
prompts caused by bug 1, since the common case is that you already hold it.

### 3. Detection is slower than it should be — MEDIUM

Current thresholds in `ParkDecisionEngine`:

| Constant | Value | Path |
|---|---|---|
| `MIN_STILL_DELAY_MS` | 5 s | Activity Recognition says STILL |
| `WALK_DISTANCE_M` | 10 m | you moved away from the car |
| `MAX_ACCURACY_M` | 25 m | gate on both fixes |
| `TIMEOUT_MS` | 90 s | neither fired → ask |

Wasil asked for "instant, or 5 seconds, or 2 metres". The still path is
**already** 5 seconds; the 10 m rule is only the fallback when Activity
Recognition is silent — which is likely what he is hitting, since Settings
currently reports permissions missing and `ACTIVITY_RECOGNITION` is one of them.

**Do not drop `WALK_DISTANCE_M` to 2 m.** City GPS runs 5–25 m and the code
accepts fixes up to 25 m, so two consecutive fixes from a motionless phone
routinely differ by more than 2 m. At that threshold it would fire while still
sitting in the car — claiming before parking, which is the failure mode that
actually costs money.

Better: tighten `MAX_ACCURACY_M` to ~10 m so a smaller displacement is
trustworthy, then 5 m means something. And check first whether the real problem
is a missing permission rather than a threshold.

### 4. Zones — see `docs/v0.6-zone-registry.md`

The zone work outgrew a backlog entry. **Full detail lives in
[`docs/v0.6-zone-registry.md`](v0.6-zone-registry.md)**: how the idea arrived,
the two wrong turns, the reframing that fixed it, every data source with real
field names, the coordinate-system traps, and how to build it.

The short version, so nobody re-litigates it here:

- **Not a containment problem.** Wasil's correction: parking apps let you buy the
  *nearest* zone, they never test whether you are inside its true shape. So the
  circle-versus-polygon argument is moot — there is no circle.
- **RDW is a dead end for geometry.** Checked twice, two different routes. It has
  zone IDs with no coordinates. Do not propose it again.
- **Amsterdam publishes the real thing**, documented and versioned:
  `api.data.amsterdam.nl/v1/parkeerzones` carries geometry, validity dates, and
  `gebruiksdoel` — the permit-versus-paid distinction this app currently cannot
  make at all.
- **It does not replace today's claim logic.** The permit is free; there is
  nothing to buy. This is the foundation of in-app payment, a later and separate
  product section.

---|---|
| Which zone do I buy? | nearest point — Wasil's model |
| Does this spot need the permit at all? | paid-vs-free, which the bundled polygons already answer correctly |

The permit is free, so there is nothing to buy yet. The nearest-point registry is
the foundation of **in-app payment** — the public-product direction — not a
rewrite of the current claim logic.

Wasil's architecture note, worth keeping: the app grows two sections over one
shared core. Permit holders switching between cars, and drivers with no permit
paying per spot. Detect the park, find the nearest zone, act — only the final
action differs. Design the lookup once with both in mind.

*"Instead of radius, is there a way we use the parking street areas? They are
still small and you could just press them where it is. Radius is very
inaccurate."*

A fair complaint: a circle centred on a tap covers the wrong side of the street
and half a canal. He wants to select a real place, not approximate one.

The bundled Amsterdam tariff polygons are **not** the answer — they are
neighbourhood-sized, so tapping one selects far too much.

Options worth pricing before choosing:

- **OSM parking features via Overpass** — real car parks and street segments,
  tappable, correctly shaped. Needs a network call and an external dependency
  the project has so far avoided.
- **Snap to the tapped street segment** — a corridor along the road rather than
  a circle. Closest to "press it where it is", but road geometry has to come
  from somewhere.
- **Keep circles, drop the slider** — pick from two or three fixed sizes
  ("this spot" / "this street" / "this block"). Much less work, and removes the
  radius fiddling without needing new data.

**Open — Wasil's call.** The third is cheapest and might be enough.

---

## v0.3.2 — the two bugs

### 1. Claim fails in a retry loop when the permit is already yours — HIGH

Reported 2026-07-29 from real use: switching works once, but parking again
while the permit is already on your own plate produces a constant failure
notification and endless retries.

Cause: `PermitRepository.switchTo()` (`android/app/src/main/java/dev/wasil/permit/data/PermitRepository.kt`)
calls `api.activate(vrn)` unconditionally. When that plate already holds the
session the API rejects the call, `ClaimPermit.claim()` catches it and returns
`ParkOutcome.SwitchFailed`, and `ClaimPermitWorker` maps that to
`Result.retry()` — so WorkManager retries on exponential backoff indefinitely,
notifying on every attempt.

Fix: make `switchTo` idempotent — read `activePlate()` first and return
`Confirmed(vrn)` immediately when it already equals the target, without calling
activate. Add a test for "target already active → Confirmed, activate never
called".

### 2. The car's location sometimes gets left behind — MEDIUM, needs diagnosis

Reported 2026-07-30: the map's car position is sometimes stale, showing a
previous parking spot rather than the current one. Not yet root-caused.

Candidates, in order of suspicion:

- `lastParkLocation` is only written on a *confirmed* park. The `Unclear`
  timeout path and the no-GPS-fix path both leave the previous value in place,
  so a park that ends in a manual-decision notification keeps yesterday's pin.
- The park may be confirmed before the GPS fix settles, storing a coarse or
  stale fix from early in the detection window rather than the final one.
- Nothing clears the pin when the car is driven away — Bluetooth reconnect
  clears `parked` but not `lastParkLocation`, so the pin outlives its truth.

Worth fixing before walking-directions-back-to-the-car is built on top of it: a
feature that navigates you confidently to the wrong street is worse than no
feature. Reproduce first, then fix — do not guess between those three.

### 3. No cap on claim retries — MEDIUM

`ClaimPermitWorker` returns `Result.retry()` for any `SwitchFailed` with no
attempt limit. Any persistent failure (bad credentials, API change) loops
forever. Fix: give up after ~5 attempts (`runAttemptCount`) with one final
notification that says retries stopped.

---

## v0.4 — tappable notifications  *(built, on branch `v04-bugs-notifications`)*

Done and committed: a persisted pending decision, every notification carrying a
content intent, a full-screen decision view, an immediate takeover check when
the app is foregrounded, zones drawn and managed on the map, walking directions,
and the marker info-window suppressed.

**Still open before it can ship:**

- **Addresses instead of coordinates.** Home reads `52.37021, 4.89516` and free
  zones are named after the day they were marked. Android's built-in `Geocoder`
  is free and needs no key. Open question from `ROADMAP.md`: store the address
  when the zone is created, or look it up each time? Stored is fast and works
  offline but goes stale.
- **Tell the other phone when you take the permit.** Wasil asked for this
  explicitly, and explicitly *not* as a silent alert — both of them are often at
  work with the phone charging in the car. Today the other phone finds out on its
  next heartbeat, up to 15 minutes later; foregrounding the app now checks
  immediately, but a backgrounded phone still waits. Genuinely instant needs FCM.
- **State-aware notification icons** — deferred during the notification work.
- **Clear a pending decision when its situation lapses** — the other car drove
  off, so "claim anyway?" is now a question about nothing. This is a correctness
  problem, not tidiness: a stale decision is still actionable.


Approved in review 2026-07-30. Every notification gains a content intent, so
tapping it opens the app to a full-screen version of the same decision
instead of only offering the cramped notification actions. Highest value on
the blocked-claim notification: show the mark with the dot mid-travel, the
facts (when the other car parked, when it was last heard from, why claiming
is risky), and the same three choices — claim anyway, mark this spot free,
leave it. Needs a `PendingIntent`, a screen, and ViewModel state to carry the
decision, which is why it was cut from the layout-only v0.3.1.

Also here: notification small icons become state-aware, showing which arc is
lit. v0.3.1 only ships the static mark icon (rescaled — see above).

---

## v0.5 — the map becomes the home for everything location-based, built

**Built on branch `v05-design`, not yet released** — it needs looking at on a
real screen first, and the emulator's system image is gone. Spec:
`docs/superpowers/specs/2026-08-01-v05-design.md`. Plan:
`docs/superpowers/plans/2026-08-01-v05-map-beliefs.md`.

Wasil's three decisions, all recorded 2026-08-01: a correction **does** write
through to the other phone; the 300 m cap stands; the overlay ships whole
rather than shrunk, because he wants to use it before judging it.

Half of what was listed here shipped in v0.4: the home zone and free zones are
already circles you place, rename and remove on the map, and Settings keeps only
read-only summary rows that open the map tab. That also settled two of the v0.4
spec's open questions by building them — tap-then-slider beat drag-a-radius, and
zone editing lives on the map.

What is left is the same idea twice — the app holds two beliefs it never shows
you, and one of them is wrong often enough to matter:

- **Correcting the parked pin.** GPS drift has no manual override, so a bad fix
  sends "Walk to car" down the wrong street. Tap the marker, tap the true spot,
  confirm. Re-resolves the zone, because a 40 m error is the difference between
  a paid street and the free zone around the corner.
- **Showing the tariff areas.** The 29 Amsterdam polygons are already bundled,
  parsed at app start and already deciding whether the permit gets claimed —
  they are just invisible. Drawing them is what makes "why did it claim here?"
  answerable without reading the source.

Typing a tariff area code by hand is dropped: nothing reads it. Entering a code
only earns its place once a manual override needs one.

---

## Later / unscheduled

- **Home-screen widget:** the correct way to show live state without opening
  the app. Deliberately waits until after the map rework settles the shared
  visual language for "who has it right now" — see
  `docs/IDEAS.md` for early notes. Not the same as the notification-icon
  swap in v0.3.1, which is static.
- Bluetooth device picker: show paired devices with clearer identification
  (name, whether currently connected) instead of a plain radio list.
- Tariff-area data refresh: the Amsterdam snapshot is bundled and manual.
  Boundaries change rarely, so this is low priority.
- iOS: keep detection behind the `DetectionSignals` interface so a port stays
  possible. No work planned.
- Firebase Auth: current rules leave a room readable to anyone who knows the
  database URL and the room hash. Acceptable for two brothers; revisit if the
  app ever leaves the family.

---

## Decisions on the ideas backlog — 2026-07-30

Wasil's answers to `docs/IDEAS.md`, recorded so the menu doesn't get re-served.

**Wanted:**

- **Tell the other person automatically when you take the permit.** A takeover
  alert already exists, but it rides the 15-minute heartbeat, so it can arrive
  long after the fact. Wanted: promptly. Worth checking whether the existing
  alert is simply too slow rather than building a second mechanism.
- **QR code** for the database URL — the worst setup step, and the natural
  onboarding path for any third car later.
- **Home-screen widget** — confirmed as the right surface for glanceable state.
- **Walking directions back to the car.**
- **Dot animation** on a switch, **haptic tick** on a confirmed claim, and
  **dark map tiles** matching the theme.
- **Time-window awareness** — see the warning below, this one is not simple.

**Not wanted:**

- Silent-phone alert. Both phones charge in the car, so a dead phone isn't the
  failure mode it looked like from outside.
- Permit expiry reminder. Renewal keeps the same login, so a lapse is an
  inconvenience rather than a trap.
- Fairness ledger, parking duration on the hero card, per-brother notification
  sounds, chat between phones.
- Automatic tariff-data updates — confirmed as unnecessary.

**Deferred:**

- Switch history: keep it out of the main UI. The permit website already holds
  this, so pull from there if it's ever needed rather than storing a second copy.
- Zone statistics: only interesting later, and only the "what you would have
  paid" part. Not the map-of-where-you-park part.
- Wear OS: later, not never.
- Machine-learned prediction: amusing, not planned.

**Strategic, recorded because it changes what this app is for:**

- **In-app parking payment is the intended direction** for a future public
  version. The reasoning is that almost nobody has a visitor permit, but
  everybody pays for parking — so the automation is worth more to strangers than
  the permit-switching is. `IDEAS.md` rates this "probably don't" on scope and
  regulatory grounds, which stands for *this* app; it does not stand as an
  argument against a separate product.
- **Multiple cars** belongs to that same public version: let a user register N
  cars and map a Bluetooth device to each. Explicitly not wanted here, where the
  two-car assumption earns the one-button design.
- **NFC tag in the car** is a customer-facing idea rather than one for this
  household — but a good one, and the clean answer for cars without Bluetooth.

### ⚠ Time-window awareness — do not implement the obvious version

Agreed: don't claim the permit when parking is already free, e.g. arriving at
20:00 in a zone that charges 09:00–19:00.

**The naive version causes fines.** If you park at 20:00 and are still there at
09:00 the next morning, the car needs the permit from 09:00 — and nothing would
claim it, because the decision was made the previous evening and never revisited.
Today's "claim on arrival regardless" behaviour is crude but safe for exactly
this reason.

Any implementation must therefore **defer** rather than skip: don't claim now,
schedule a claim for when paid hours resume, and re-check whether the car is
still there at that moment. That needs the tariff time windows parsed (currently
display-only text), a scheduled wake-up, and a "still parked?" test — which makes
it a behaviour release of its own, not a small addition to another one.

**Answered 2026-07-30: there is no hour budget.** The permit is valid year-round
with no capped pool of hours, so a claim costs nothing. That settles it in
favour of today's behaviour: claiming on arrival regardless of the clock wastes
nothing, and the existing "time windows are display-only" decision stands
unchanged. It also removes hour-tracking from the ideas list entirely — there is
nothing to track.

What remains is only the tidiness argument for not claiming when parking is
already free, which Wasil wants but explicitly does not want over-engineered
yet ("I have an idea but for now I don't want to overcomplicate it"). Parked
here until that idea arrives. Whatever form it takes, the deferral requirement
above still applies — a car that stays overnight needs the permit when charging
resumes.

---

## Locked decisions — do not re-open without a reason

- Identity colour is slate blue (Wasil) and terracotta (Walid). An earlier
  sage-teal/clay-ochre pairing was tried and rejected: both are desaturated
  toward green and amber, which collided with the status colours (fine =
  green, alert = rust) — a Wasil card and a "success" state read as the same
  colour. Don't reintroduce a hue close enough to `fine`/`alert` to be
  mistaken for either; identity and state must stay separated by hue *and* by
  scale (identity is a large fill, state is a small icon/label).
- Auto-switch is the default, not ask-first.
- Tariff *comparison* between the two cars was explicitly dropped: it only
  matters once the app can pay for parking. Tariff data stays informational.
- Time windows on tariff areas are display-only. Parking at 20:00 in a
  09:00–19:00 area still claims, because holding the permit is free and the car
  will still be there when paid hours resume.
- Home zone is a small circle (30–200 m), deliberately not an Amsterdam
  neighbourhood polygon — those are large enough that a brother parking three
  streets away would read as "at home".
- The two phones run the same APK. "Whose phone is this" makes the UI mirror
  per device; there is no separate build for Walid.
