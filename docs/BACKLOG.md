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

## v0.4 — tappable notifications

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

## v0.5 — the map becomes the home for everything location-based

All location actions should happen where you can see them on a map, instead of
being buried as buttons in Settings:

- Show the home zone on the map as a circle, with its radius draggable, rather
  than the current blind "set home to current location" button.
- Show free zones as circles on the map; add and remove them there.
- Show the parked car and allow correcting its position by dragging the pin
  (GPS drift currently has no manual override).
- Show the Amsterdam tariff-area polygons as an overlay, and let a zone be
  selected or drawn by hand — including entering a tariff area code directly.

Needs real interaction design before implementation — see
`docs/superpowers/specs/2026-07-30-v04-design.md`'s open questions (drawing a
free zone, selecting a tariff area, whether correcting the parked pin updates
shared state, where home-zone editing lives).

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

This also partly answers `IDEAS.md`'s open question about an hour budget: the
budget question changes *how much* this matters, but deferring is the correct
design either way.

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
