# What comes after v0.5.3 — a brainstorm

Written 2026-08-02, after shipping v0.4.1 → v0.5.3 in one night, for Wasil to
argue with. Nothing here is decided.

Shipped today: `v0.4.1` the quietening pass · `v0.4.2` the cached-fix fallback ·
`v0.5.0` correctable pin and tariff overlay · `v0.5.1` the overlay made legible ·
`v0.5.2` the map screen tidied · `v0.5.3` the walk drawn in-app.

---

## v0.5.4 — pay the debt, because it is now blocking things

The honest recommendation, and not a glamorous one. Three of these have been
carried since v0.4 and one of them is a genuine hazard.

### 1. A pending decision never expires — HAZARD

The app can raise "your brother's car is parked, claim anyway?" and leave it
sitting there after he has driven off. Acting on it then does something you no
longer want, and the app has no idea the question went stale.

Worse now than when it was first noted: v0.5.0 added a second way to reach the
claim path (the correction flip), so there are more routes into a decision and
still no route out of a dead one.

Fix: `PendingDecision` already stores `raisedAtMs`. Expire on age, and re-check
the other phone's state before acting on it rather than trusting the snapshot
that raised it.

### 2. The app has two voices

In-app copy is calm and sentence-cased. Notifications still shout: `WARNING:`,
`FAILED`, `NOT`, `WITHOUT`, `check the website!`. That was frozen deliberately
through the layout releases; there is nothing left to hide behind now. It is
also the first thing anyone sees, since a notification usually arrives before
the app is opened.

"Permit switch failed — the permit did not move" carries the same urgency
without the capitals.

### 3. Two couplings that fail silently

Both flagged by review twice and still there:

- The main button finds its target by matching a **display string**
  (`options.first { it.label == action.target.label() }`), and `holderFor` maps
  any unrecognised label to Walid. A typo lights the wrong brother's arc.
- The Settings health row dispatches on the row's **label text**
  (`when (fix) { "Grant" -> …; else -> battery }`), so a third row silently
  inherits the battery intent. This is already why the "car not paired" row has
  no button.

One shared `MyCar ↔ PlateOption` mapping and one sealed `FixAction` retire both,
and the compiler starts catching what review has to.

### 4. Smaller things noticed while building v0.5

- **Your own position is read once**, when the app starts. Open the app an hour
  later and the walk route is drawn from where you were, not where you are.
  Re-read when the Map tab is opened.
- **The route does not survive a tab switch**, because it lives in `MapScreen`'s
  own state. Minor, but it makes the feature feel fragile.
- **Notification icons do not reflect who holds the permit** — deferred from
  v0.4 and still true.

---

## v0.6.0 — deepening the two-mainframe split

The direction is in [`BACKLOG.md`](BACKLOG.md) and the row-by-row detail in
[`USE-CASES.md`](USE-CASES.md). What follows is what building v0.5 taught that
changes the plan.

### The tariff overlay proved the data is the wrong shape

v0.5 drew the 29 bundled areas and they turned out to be **rate regions, not
parking zones** — 3 km across, 21 of the 29 in several disjoint pieces. They
answer "what does an hour cost around here", which is not the question a
standalone parking app asks. The question is "which zone am I in, and what is
its code" — and that needs the `parkeerzones` layer with `gebruiksdoel`, plus
the payment machines for the nearest-neighbour lookup.

So the zone registry is not merely the foundation of v0.6; **v0.5's overlay is
a placeholder for it**, and the drawing and hit-testing code written for v0.5
is the part that survives.

### The blocker is unchanged and it is the whole risk

**Can a paid session be started programmatically at all?** Confirmed by Wasil as
the thing to chase first, before designing anything on top. Everything in the
`D` and `E` sections of `USE-CASES.md` assumes yes. Real money and real
liability, unlike the permit, which is free and reversible.

Worth being blunt: if the answer is no, v0.6.0 becomes "show me what I would
owe, and let me pay it myself in one tap elsewhere" — still useful, much
smaller, and honest.

### The questionnaire, for tomorrow

Wasil's idea, and the right one: ask real people rather than guess. Draft in
[`ZONE-QUESTIONNAIRE.md`](ZONE-QUESTIONNAIRE.md). It asks only about how a
permit *behaves* — never for anyone's login, which is not needed to answer any
of it.

---

## Learning the UI side

Wasil asked where to learn app UI so he can make the stylistic calls himself.
The four that would actually change his eye, in the order worth reading:

1. **Refactoring UI** (Adam Wathan & Steve Schoger) — the single highest-value
   one. Practical, example-driven rules: spacing, hierarchy, why greys look
   muddy, how to make something feel designed without a designer. Most of what
   v0.4.1 did to this app is in that book.
2. **Material 3 guidelines** (m3.material.io) — the system this app is built on.
   The colour-roles and typography pages are the ones that pay off, because they
   explain *why* the slots exist, which is what stops an identity colour leaking
   into every button (the exact bug v0.3.1 shipped).
3. **Steve Schoger's "Design Tips"** (@steveschoger) and the Refactoring UI
   archive — before/after pairs, one idea each. Good for building the instinct
   to notice what is off before being able to name it.
4. **Mobbin** (mobbin.com) — real screenshots of real apps, organised by flow.
   Best used with a specific question: "how do five map apps show a bottom
   control bar", not as browsing.

**What would help this project most:** not more theory, but Wasil pointing at a
screen and saying "this looks off" — which has caught more real defects here
than tests and review combined. The books are for turning *"it looks off"* into
*"the spacing is inconsistent and the label competes with the value"*.
