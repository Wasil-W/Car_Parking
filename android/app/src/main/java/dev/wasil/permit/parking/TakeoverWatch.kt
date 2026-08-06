package dev.wasil.permit.parking

import dev.wasil.permit.parking.shared.PermitClaim

/**
 * Watching for the other phone taking the permit — the two rules that decide
 * it, kept out of the workers so they can be held still by a test.
 *
 * The watch used to live entirely inside the heartbeat, and the heartbeat runs
 * only while this phone is parked outside. That left the whole drive
 * unwatched — see `USE-CASES.md` C6 — which is the one stretch where you most
 * want to know the permit has moved, because you are about to park with it.
 */

/**
 * The shortest gap between two takeover reads while the car's Bluetooth is up.
 *
 * The live-location sampler already wakes every 20 s during a drive, so this is
 * a throttle rather than a schedule: one network read every ninth poll. Short
 * enough that a takeover surfaces well before the end of an ordinary drive,
 * long enough that a half-hour drive costs ten reads rather than ninety.
 */
const val TAKEOVER_CHECK_INTERVAL_MS: Long = 3 * 60 * 1000L

/**
 * Whether the watch may run now.
 *
 * A negative gap means the clock moved backwards under us — a timezone change,
 * an NTP correction, a user setting the date. Left alone that would stall the
 * watch until real time caught up, so it counts as due.
 */
fun shouldWatchForTakeover(
    lastCheckMs: Long,
    nowMs: Long,
    intervalMs: Long = TAKEOVER_CHECK_INTERVAL_MS,
): Boolean {
    val since = nowMs - lastCheckMs
    return since >= intervalMs || since < 0
}

/**
 * Who has taken the permit and needs saying so, or null when nothing does.
 *
 * Null covers three separate "nothing to report" cases and they are worth
 * naming: no claim has ever been written; this phone does not know which car it
 * is, so it cannot tell "someone else" from "me"; or the permit is on our own
 * car. The last guard is [lastAlertedClaimMs] — a claim already reported is not
 * news, and without it every tick of the watch would re-raise the same alert.
 */
fun takeoverBy(permit: PermitClaim?, me: MyCar?, lastAlertedClaimMs: Long): MyCar? {
    if (permit == null || me == null) return null
    if (permit.holder == me.key()) return null
    if (permit.claimedAtMs <= lastAlertedClaimMs) return null
    return me.other()
}
