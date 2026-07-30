# Ideas backlog

Brainstormed 2026-07-30. Nothing here is committed to — it's a menu, deliberately
over-broad, with honest assessments so the bad ideas are visible as bad rather
than quietly omitted.

**Ranked by one question: does this prevent a real loss, or remove a real
annoyance?** Not by how interesting it would be to build. The app currently
works; the biggest risk to it now is feature bloat making it less reliable than
it already is.

---

## Two questions that could reorder everything below

### 1. Does your permit have an hour budget?

Amsterdam visitor permits (*bezoekersvergunning*) usually come with a **limited
number of hours per year**, not unlimited use. The app currently doesn't know
this exists. If yours has a budget:

- Every claim spends from a shared pot, and neither of you can see the balance.
- "Claim anyway" isn't just a fairness question, it's spending your brother's
  hours.
- Running out mid-November with the permit still nominally valid would be a
  genuinely nasty surprise.
- Tracking remaining hours would arguably become the app's **most valuable
  feature** — more than anything else on this list.

If it's unlimited, ignore all of that. Worth checking before v0.5 gets planned,
because it changes what the app is for.

### 2. What happens on 2026-11-19?

That's the permit's expiry date. Right now nothing in the app knows it. If it
lapses, every switch starts failing and the app's diagnosis will be a generic
API error — you'd be debugging the wrong thing. A stored expiry date with a
two-week reminder is perhaps an hour of work and removes a guaranteed future
confusion.

---

## Tier 1 — prevents actual loss

**Fine-risk alert.** The real failure mode isn't a failed switch, it's a car
sitting in a paid zone with no permit and nobody noticing. The app already knows
both cars' positions, zones, and who holds the permit — it has everything needed
to say "Walid's car has been unpermitted in a €4,19/h zone for 25 minutes".
That's the notification that actually saves money, and it doesn't exist yet.

**Permit expiry reminder.** See above.

**Hour-budget tracking**, if applicable. See above.

**A working health check.** Detection failing silently is the worst outcome,
because you only discover it via a fine. Phase 2 failed this way for days. Ideas:
a "last successful detection" timestamp on the main screen; a warning if no park
has been detected in N days despite the car's Bluetooth connecting; a "simulate a
park" button that runs the whole pipeline against a fake trigger and reports
where it broke. The last one is the most useful and the least glamorous.

**Silent-phone alert.** If the other phone hasn't published state in 12 hours,
say so. Right now a dead phone just quietly reads as "not parked", which
disables the collision guard exactly when you'd want it.

---

## Tier 2 — removes real friction

**QR code for the database URL.** Typing a 60-character Firebase URL into a
phone keyboard is miserable, and it's the one setup step most likely to be
mistyped. One phone shows a QR code, the other scans it. Also the natural way to
onboard any future third car.

**Home-screen widget.** Already in the backlog. Shows who holds the permit
without opening anything — the honest answer to what I wrongly claimed the
launcher icon could do. This is the right surface for glanceable state.

**Quick Settings tile.** Swipe down, tap, permit switched. Cheap to build
(`TileService`), and arguably the fastest possible path to the app's core action.
Genuinely underrated.

**NFC tag in the car.** A £2 sticker on the dashboard. Tap the phone to it and
the app knows you've arrived or left, with no Bluetooth, no GPS, no Activity
Recognition, no battery cost, and no false positives. This is also the clean
answer to the "cars without Bluetooth" problem raised back in Phase 2 — and it's
more reliable than what we built, not less.

**Fairness ledger.** "You've held the permit 71% of the time this month."
Two brothers sharing one permit is a fairness problem wearing a technical
costume; a number nobody can argue with may prevent more friction than any
feature here.

**Parking duration.** "Held for 3h 24m" on the hero card. Trivial, and it's the
thing you actually want to know when deciding whether to hand it over.

**Walking directions back to the car.** The map knows where the car is. On a
dark street after a long day this is worth more than it sounds.

---

## Tier 3 — polish, once the above is done

- **Animate the dot travelling** on a switch. The mark was designed for this; it
  currently just jumps. Cheap, and it makes the app feel finished.
- **Haptic tick** on a confirmed claim. Confirmation without looking.
- **Dark map tiles** matching the theme. osmdroid supports alternative tile
  sources; the current bright map is jarring in dark mode.
- **Per-brother notification sounds**, so you know who did what without reading.
- **Switch history**, a simple log. Useful for arguments and for debugging.
- **Zone statistics** — where you park most, what you'd have paid. Mildly
  interesting, no real use.

---

## Tier 4 — probably don't, and why

- **Three or more cars.** The entire design leans on there being exactly two:
  one button, two arcs, "the other car". Supporting three means a car picker, a
  priority policy, and a redesigned mark — and you'd be rebuilding the app to
  serve a case that doesn't exist. Wait until there's a third car, then decide.
- **In-app payment for parking.** Huge scope, real money, regulatory surface.
  This is what killed the tariff-comparison idea already, correctly.
- **Chat between the phones.** You have phones. They have messaging.
- **Machine-learned parking prediction.** "You usually park here on Tuesdays" is
  a fun demo and a support nightmare when it's wrong. The Bluetooth trigger is
  already near-deterministic; don't add guessing to something that currently
  knows.
- **Wear OS app.** Cost is high, and the widget plus notifications already cover
  glanceability.
- **Automatic tariff-data updates.** Boundaries change roughly never. A manual
  re-download every few years is fine.
- **Live Updates / progress notifications** (Android 16). The persistent status
  notification already does this job; a fancier one adds API-level branching for
  no gain.

---

## Two structural ideas worth considering separately

**Replace the guard's guesswork with an explicit request.** Today the app infers
whether taking the permit is acceptable, from position and staleness. An
alternative: let the other person *ask*. "Walid requests the permit — he's
parked in a €4,19/h zone" with accept/decline, expiring after N minutes. It
turns a heuristic into a conversation, and heuristics are what generate the
awkward cases. It doesn't replace the automatic path — it gives the ambiguous
cases somewhere better to go than a blocked notification.

**Reconsider the paid-hours decision.** It was settled that tariff time windows
are display-only, on the grounds that holding the permit is free so you may as
well claim. That logic is sound *unless* there's an hour budget (question 1). If
hours are limited, then claiming at 20:00 in a 09:00–19:00 zone spends hours for
nothing, and the whole decision inverts. Flagging it because the reasoning behind
a locked decision depends on an unanswered question.

---

## Suggested order, if it were mine

1. **v0.3.2** — the two bugs. Already agreed.
2. **v0.4** — tappable notifications. Already designed.
3. **Answer the hour-budget and expiry questions.** Cheap, and they may reshuffle
   everything after this point.
4. **v0.5** — the fine-risk alert and the silent-phone alert. Small, and they
   protect against the failures that actually cost money.
5. **v0.6** — the map rework, with the QR-code onboarding folded in.
6. **v0.7** — widget and Quick Settings tile together; they share the same
   "state outside the app" plumbing.
7. NFC whenever a car without Bluetooth actually turns up.

The dot animation can slot in anywhere — it's an afternoon, and it's the single
cheapest thing here that would make the app feel considered.
