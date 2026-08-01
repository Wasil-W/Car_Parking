# Permit questionnaire — what to ask, and why each answer matters

Wasil's idea, 2026-08-02: *"maybe we can do a questionnaire about them so we
definitely know what we will do for that feature."* Right instinct — every
`OPEN` row in [`USE-CASES.md`](USE-CASES.md) is blocked on a guess about how
other people's permits behave, and guessing is what produced the two wrong turns
already recorded in [`v0.6-zone-registry.md`](v0.6-zone-registry.md).

**Nobody's login is needed to answer any of this, and none should be asked for
or accepted.** Every question below is about how a permit *behaves*. If someone
offers their account details, the answer is no — the questions work without
them.

**The ideal respondent** both holds a permit and pays for parking in Noord, so
they can answer sections A and B from the same trip.

---

## A. How the permit behaves

1. **Where does your permit work?** The whole city, one district, or a specific
   zone code? If it is limited, which?
   *Why:* Wasil's covers all of Amsterdam. If that is unusual, the settlement
   layer cannot assume a permit is valid wherever you park — that is row D2.

2. **Can you change which car it is on?** If so: from an app or the website,
   how many times a day, and is there a wait between switches?
   *Why:* the entire handoff idea rests on this. A daily cap or cooldown would
   change the auto-claim rules, not just the copy.

3. **Is there an hour budget?** A number of hours per day, month or year — or
   is it unlimited while valid?
   *Why:* Wasil's has none, which is why claiming on arrival costs nothing and
   tariff time windows are display-only. A budget makes every claim a spend, and
   turns the whole app into a rationing problem.

4. **Does activating it cost anything?** Per activation, per day, or nothing?

5. **What happens if you park outside where it works?** Do you pay the normal
   tariff, or is there a reduced rate for permit holders?
   *Why:* decides whether "pay" and "permit" are alternatives or can combine.

6. **How do you know it is active?** A screen, an email, a text, nothing?
   *Why:* this app verifies by reading the plate back. If other systems have no
   readback, the same trick will not port.

## B. Paying in Noord (or anywhere without the permit)

7. **What do you use to pay?** The council's own app, a payment machine, a
   third-party app? Which one?

8. **How do you tell it where you are?** Typing a zone code, picking from a map,
   the app finding you automatically?
   *Why:* Wasil's own reframing was that parking apps let you buy the *nearest*
   zone rather than testing containment. Confirming how that actually looks in
   practice decides the whole interaction.

9. **Where does the zone code come from?** Printed on the machine, on a sign, in
   the app?
   *Why:* if the code is only on a physical sign, no dataset replaces reading
   it, and the app's job becomes "help me find the code" rather than "know it".

10. **Do you start and stop a session, or buy a block of time up front?**
    *Why:* start/stop needs the app to notice you left — which detection already
    does. Buying a block needs a guess about how long you will stay, which it
    cannot make.

11. **What happens if you forget to stop it?** Charged until when?

## C. The one that decides whether v0.6.0 exists

12. **Is there any way to start a parking session other than by hand?** A
    website you could automate, a public API, a link that pre-fills the zone?
    *Why:* this is the blocker. Everything in sections D and E of `USE-CASES.md`
    assumes yes. If the honest answer is no, v0.6.0 shrinks to "show me what I
    owe and where to pay it", which is still worth building — just much smaller.

---

## What to do with the answers

Fill in the `OPEN` rows of [`USE-CASES.md`](USE-CASES.md) — D2, D3, E2, E4 —
and record who answered and when, since permit rules change between years and an
undated answer becomes a guess again. If two people disagree, both answers go in:
that disagreement is itself the finding, and means permit scope varies, which is
exactly what question 1 exists to detect.
