# Handoff — working agreement

An Android app that passes one shared Amsterdam visitor parking permit between
two cars.

## Read this first, every time

**[`docs/TIMELINE.md`](docs/TIMELINE.md) is the single source of truth for where
the project is.** Read it before planning or building anything. It holds what
shipped, what is planned, what is deliberately parked, what is blocked on a
decision, and which past ideas turned out to be wrong.

**Update it in the same commit as every version bump.** A release that did not
update the timeline is not finished. Ideas that live only in a conversation are
lost when that conversation ends — that is the whole reason the file exists.

## Rules that have been paid for

These come from real defects. Breaking one costs a fine, a wrong claim, or a
release spent undoing it.

- **Look at the screen before claiming something works.** Every significant
  defect in this project was caught by eye, not by tests or review — black-on-black
  text, an identity colour leaking app-wide, an invisible slider, a tariff
  overlay that drew a uniform tint, a walking time of 30 km/h. Hundreds of
  passing tests were blind to all of them. Build, install, screenshot, look.
- **Never publish a guess as a fact.** A failed GPS read means "we do not know",
  not "the car is not on a paid street". The expensive direction to be wrong in
  is the one that tells the other phone it may claim.
- **Verify a claim before acting on it**, including claims in this repo's own
  documents. One "hazard" was carried for weeks after it had been fixed.
- **The Firebase database URL is never committed.** The repo and the APKs are
  public.
- **Identity colour (Wasil blue, Walid terracotta) never enters a generic
  `ColorScheme` slot.** It renders on both phones; blue would come to mean
  "interactive" rather than "Wasil".
- **Map colours are mode-independent.** The tiles stay light whatever the app
  theme does.
- **Obligation is not settlement.** Where you parked decides whether anything is
  owed; what you hold decides how it is paid. Keep them apart — see
  [`docs/USE-CASES.md`](docs/USE-CASES.md).

## Versioning

Features take a minor bump. Bug fixes, layout and polish take a patch bump.
Work on a branch; merge and tag only when asked.

## Tests

`cd android && ./gradlew testDebugUnitTest` — all must pass before a release.
Pure logic lives outside Android so it can be tested on the JVM; that separation
is deliberate and worth preserving.
