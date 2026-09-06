package dev.wasil.permit.parking.zones

/**
 * A reminder the app is willing to send about a spot it is parked in.
 *
 * The whole feature exists because of one asymmetry: **the app can prevent a
 * fine, but only before the clock turns.** Afterwards it can only report one.
 * Everything below is about the minutes before a boundary, and nothing here
 * needs the ability to take money — which is what makes it the last useful
 * thing this app can do (see `USE-CASES.md` section D, and 2026-08-31).
 */
sealed interface Reminder {
    /** Minutes from now until the boundary this reminder is about. */
    val inMin: Int

    /**
     * Free now, charging soon, and nothing is settling it. The one that can
     * actually save money — D7.
     */
    data class StartsOwing(override val inMin: Int, val rateText: String) : Reminder

    /**
     * Charging now, free soon — D8. Lower stakes on purpose: being told late
     * about D7 costs a fine, being told late about this costs a few minutes of
     * meter.
     */
    data class BecomesFree(override val inMin: Int) : Reminder
}

/**
 * How long before a boundary each reminder wants to arrive.
 *
 * **They are deliberately different.** Starting to owe is something you may need
 * to act on — move the car, pay, ask for the permit — and thirty minutes is
 * roughly the time it takes to walk back to a car in a city. Becoming free needs
 * only enough warning not to pay for the tail of it, so ten minutes is plenty
 * and a longer lead would just be a notification you cannot use yet.
 */
const val OWING_LEAD_MIN = 30
const val FREE_LEAD_MIN = 10

/**
 * Whether to schedule a reminder for a parked car, and which.
 *
 * Pure and separate from Android so the conditions can be tested without a
 * device — the same separation the rest of this package keeps, and the reason
 * every rule below is a line in a test rather than a hope about a worker.
 *
 * Returns null far more often than not, and that is the design. Four things
 * must all hold, and the fourth is the one that is easy to miss:
 *
 * 1. **The car is parked.** Nothing is owed about a car being driven.
 * 2. **The spot is a paid area.** Home, a marked free zone, a free street and
 *    anywhere outside Amsterdam's published data all owe nothing, ever.
 * 3. **A boundary is close enough to be worth saying.**
 * 4. **The permit is not already settling it.** `USE-CASES` B6 claims on
 *    geometry regardless of the hour, so a permit holder who got the permit
 *    owes nothing — reminding them would be noise about a problem the app
 *    already solved. This is the obligation/settlement split doing its job:
 *    *where you parked* decides whether anything is owed, *what you hold*
 *    decides how it is paid, and a reminder is only ever about the first.
 *
 * @param permitSettles the permit is on this car and covers this spot.
 * @param now what the schedule engine says about this moment.
 */
fun reminderFor(
    parked: Boolean,
    inPaidArea: Boolean,
    permitSettles: Boolean,
    now: TariffNow,
): Reminder? {
    if (!parked || !inPaidArea || permitSettles) return null
    return when (now) {
        // Free, and charging is coming. `startsInMin` is null for an area with
        // no schedule at all, which has no boundary to warn about.
        is TariffNow.Free -> now.startsInMin
            ?.takeIf { it in 0..OWING_LEAD_MIN }
            ?.let { Reminder.StartsOwing(it, rateAfterFree(now)) }
        // Charging, and it stops. `endsInMin` is null only for the three areas
        // that charge every minute of the week — nothing to wait for there.
        is TariffNow.Charging -> now.endsInMin
            ?.takeIf { it in 0..FREE_LEAD_MIN }
            ?.let { Reminder.BecomesFree(it) }
    }
}

/**
 * The rate a [TariffNow.Free] period is about to turn into.
 *
 * [TariffNow.Free] carries no rate — it is describing the absence of one — so
 * the caller supplies it from [tariffNext] where it can. Empty rather than
 * invented when it cannot: a reminder that says *when* without *how much* is
 * still worth sending, and a made-up figure is not.
 */
private fun rateAfterFree(now: TariffNow.Free): String = ""

/**
 * The same decision, with the next span's rate folded in.
 *
 * Kept separate from [reminderFor] so the condition logic stays testable
 * without constructing a [TariffNext], and so a missing rate can never stop a
 * reminder being sent — it only makes it shorter.
 */
fun reminderFor(
    parked: Boolean,
    inPaidArea: Boolean,
    permitSettles: Boolean,
    now: TariffNow,
    next: TariffNext?,
): Reminder? = when (val r = reminderFor(parked, inPaidArea, permitSettles, now)) {
    is Reminder.StartsOwing -> r.copy(rateText = next?.rateText.orEmpty())
    else -> r
}

/**
 * Minutes to wait before asking [reminderFor] again, or null when there is
 * nothing ahead worth waking for.
 *
 * A parked car needs a *chain* of wake-ups, not one. Park at 07:00 in a 09:00
 * zone and the interesting moments are 08:30 (you are about to owe) and 18:50
 * (it is about to stop) — and after that the next morning. Booking only the
 * first would leave the second unsaid, and booking a repeating check would wake
 * the phone all night to learn nothing. So each run books exactly one
 * successor, the same shape [dev.wasil.permit.parking.android.LiveLocationWorker]
 * already uses for the drive.
 *
 * Two candidates, because [TariffNow] can only see as far as the boundary it is
 * standing on. [TariffNext] supplies the one after it, which is the whole reason
 * that function exists — and it counts from *now* too, so the two are directly
 * comparable without any date arithmetic here.
 *
 * Only strictly-positive candidates count. A candidate at or below zero is a
 * boundary we are already inside the lead window for, which means the caller has
 * just notified about it: booking it again would be an immediate second wake-up
 * saying the same thing.
 *
 * Null for the three round-the-clock areas and for an area with no windows —
 * both have no boundary ahead, and a chain that keeps waking to rediscover that
 * is a battery cost with nothing on the other end.
 */
fun nextCheckInMin(now: TariffNow, next: TariffNext?): Int? {
    val candidates = buildList {
        when (now) {
            is TariffNow.Free -> now.startsInMin?.let { add(it - OWING_LEAD_MIN) }
            is TariffNow.Charging -> now.endsInMin?.let { add(it - FREE_LEAD_MIN) }
        }
        // The span after this one: if it charges, we want to catch its end; if
        // it is the free gap, we want to catch the charging that follows it.
        next?.let { add(it.endsInMin - if (it.charging) FREE_LEAD_MIN else OWING_LEAD_MIN) }
    }
    return candidates.filter { it > 0 }.minOrNull()
}
