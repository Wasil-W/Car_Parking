package dev.wasil.permit.ui

import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkRecord
import dev.wasil.permit.parking.Settlement
import dev.wasil.permit.parking.label

/**
 * What a badge is *about*, so the screen can colour it without matching on
 * words. Identity colour is allowed on the two permit kinds and nowhere else:
 * a permit record belongs to a specific car, while "free" and "unsettled" are
 * states of a place and would turn a hue that means "Wasil" into one that means
 * "fine".
 */
enum class BadgeKind {
    PERMIT_WASIL,
    PERMIT_WALID,
    /** Home, a marked free zone, or a street that charges nothing. */
    FREE,
    /** Charging, and nothing covered it. */
    OWED,
    /** No position resolved, so nothing is known either way. */
    UNKNOWN,
}

/**
 * One record, ready to draw. Everything here is a string because every decision
 * that produced it is made below, where a test can hold it still — the badge
 * wording is the one real choice in this whole feature and it should not live
 * inside a composable.
 */
data class HistoryRow(
    val badge: String,
    val kind: BadgeKind,
    val cost: String,
    /** True when [cost] is the absence of a bill rather than one — drawn quieter. */
    val costIsQuiet: Boolean,
    val place: String,
    val whenText: String,
)

/** The badge: how the obligation was met, never where you were. */
fun badgeTextFor(settlement: Settlement, holder: MyCar?): String = when (settlement) {
    Settlement.PERMIT -> holder?.let { "Permit · ${it.label()}" } ?: "Permit"
    Settlement.HOME -> "Home"
    Settlement.FREE_ZONE -> "Free zone"
    Settlement.FREE_STREET -> "Free street"
    Settlement.UNSETTLED -> "Unsettled"
    Settlement.UNKNOWN -> "Not known"
}

fun badgeKindFor(settlement: Settlement, holder: MyCar?): BadgeKind = when (settlement) {
    Settlement.PERMIT -> if (holder == MyCar.WALID) BadgeKind.PERMIT_WALID else BadgeKind.PERMIT_WASIL
    Settlement.HOME, Settlement.FREE_ZONE, Settlement.FREE_STREET -> BadgeKind.FREE
    Settlement.UNSETTLED -> BadgeKind.OWED
    Settlement.UNKNOWN -> BadgeKind.UNKNOWN
}

/**
 * The cost slot — and the one place this deliberately stops short of the
 * mockup.
 *
 * The mockup shows a money total per record ("€5,37"). Computing one needs a
 * rate multiplied by a duration, and the duration needs an end the app only
 * sometimes observes; multiplying a real rate by a half-known duration produces
 * a confident-looking number that is wrong by however long the car sat there
 * unnoticed. This project has a rule about that, and it is the expensive
 * direction to be wrong in — a total that flatters is worse than a rate that is
 * merely true. So an unsettled park shows **the rate that applied when you
 * parked**, which is exactly what the app computed and nothing more.
 */
fun costTextFor(settlement: Settlement, rateText: String?): String = when (settlement) {
    Settlement.PERMIT -> "covered"
    Settlement.HOME, Settlement.FREE_ZONE, Settlement.FREE_STREET -> "nothing owed"
    Settlement.UNSETTLED -> rateText?.let { splitRateLine(it).first } ?: "rate unknown"
    // A dash, not words. The badge on the same line already says "Not known";
    // seen on screen, "Not known … not known" read as a stutter rather than as
    // two facts.
    Settlement.UNKNOWN -> "—"
}

/** Only an unsettled park is a bill; everything else is the absence of one. */
fun costIsQuiet(settlement: Settlement): Boolean = settlement != Settlement.UNSETTLED

/**
 * "Mon 3 Aug · 09:12 → 17:48" when both ends were observed, "Mon 3 Aug · from
 * 09:12" when only the start was.
 *
 * The second form is not a placeholder for the first. The app sees a park begin
 * because detection ran; it sees one end only when the car's Bluetooth comes
 * back up. Where that never happened — no paired car, or the app was installed
 * mid-park — saying "from 09:12" is the whole truth, and inventing the arrow's
 * other side would be the app disagreeing with itself about what it watched.
 */
fun whenTextFor(dayDate: String, startClock: String, endClock: String?): String =
    if (endClock == null) "$dayDate · from $startClock" else "$dayDate · $startClock → $endClock"

/** Falls back rather than blanks: an unnamed spot still happened. */
fun placeTextFor(place: String?): String = place?.takeIf { it.isNotBlank() } ?: "Place not recorded"

/**
 * Assembles a row from a record plus the three date strings the caller
 * formatted, which is where the split sits: locale and timezone are the
 * platform's job, wording is this file's.
 */
fun historyRowFor(
    record: ParkRecord,
    dayDate: String,
    startClock: String,
    endClock: String?,
): HistoryRow = HistoryRow(
    badge = badgeTextFor(record.settlement, record.holder),
    kind = badgeKindFor(record.settlement, record.holder),
    cost = costTextFor(record.settlement, record.rateText),
    costIsQuiet = costIsQuiet(record.settlement),
    place = placeTextFor(record.place),
    whenText = whenTextFor(dayDate, startClock, endClock),
)

/**
 * What the empty tab says.
 *
 * It earns the space, unlike the one-line empty states elsewhere in the app: a
 * tab whose only job is history has to explain when something would appear in
 * it, or an empty one reads as a bug. The second line is the honest limit —
 * this log starts now and does not reconstruct parks from before it shipped.
 */
const val HISTORY_EMPTY_TITLE = "No parks recorded yet"
const val HISTORY_EMPTY_BODY =
    "Every park Handoff detects from now on appears here — where it was, when, " +
        "and what covered it."
