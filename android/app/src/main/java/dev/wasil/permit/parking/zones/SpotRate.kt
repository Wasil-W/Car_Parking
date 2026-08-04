package dev.wasil.permit.parking.zones

/**
 * What the spot under a car costs right now, in whole cents per hour.
 *
 * This is the only thing about a parked position that leaves the phone. The
 * two brothers share one permit and one Firebase node with no server and no
 * per-user auth, so anything published is readable by the other phone
 * outright — which is why the permit decision is built on a *price* rather
 * than a position. A price answers "which of us should keep the permit"
 * completely, and answers "where is my brother" not at all.
 *
 * Three distinct answers, and the difference between the last two is the whole
 * safety argument:
 *
 * - **0** — costs nothing to stand here right now. Home, a hand-marked free
 *   zone, a street outside every tariff area, or a paid area outside its
 *   charging hours.
 * - **a positive number** — this is the hourly rate being charged at this
 *   moment.
 * - **null** — *unreadable*, never "free". Either the bundled tariff data is
 *   missing (matching [ZoneInfo.Paid] with a null area, which the app already
 *   treats as paid on purpose) or the rate key in that data did not parse.
 *   A spot we cannot price might be the expensive one, and the cost of that
 *   mistake is asymmetric: claiming a permit that turned out to be unnecessary
 *   costs nothing, failing to claim one that was necessary costs a fine.
 *
 * Pure and clock-free — the caller supplies [dayOfWeek] (Monday 0, matching
 * [tariffNow]) and [minuteOfDay] — so every branch is unit-testable without a
 * device, a network or a wall clock.
 */
fun spotRateCents(zone: ZoneInfo, dayOfWeek: Int, minuteOfDay: Int): Int? = when (zone) {
    ZoneInfo.Home, is ZoneInfo.ManualFree, ZoneInfo.FreeStreet -> 0
    is ZoneInfo.Paid -> when (val area = zone.area) {
        null -> null
        else -> when (val now = tariffNow(area.windows, dayOfWeek, minuteOfDay)) {
            is TariffNow.Free -> 0
            is TariffNow.Charging -> now.rateCents
        }
    }
}
