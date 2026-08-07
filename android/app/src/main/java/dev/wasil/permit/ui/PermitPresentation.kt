package dev.wasil.permit.ui

import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.Vehicle
import dev.wasil.permit.parking.zones.TariffNow
import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.tariffNow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Roster slot 0 is always the left arc, slot 1 the right — identical on both
 * phones, because slot order is derived from the permit account rather than
 * from which car is "mine".
 */
enum class Side { LEFT, RIGHT }

/**
 * How many arcs the mark draws.
 *
 * [PAIR] is the two-body mark: one resource, two possible homes, a dot that
 * travels. [SOLE] is what it becomes when there is no second phone to hand the
 * permit to — and the arc that goes is the *empty* one, not a dimmed one. A
 * greyed arc says "there is somewhere else and it is not holding it"; with one
 * car that is false, and the mark should not imply a place that does not exist.
 */
enum class MarkArcs { PAIR, SOLE }

data class MarkState(val lit: Side?, val dot: Side?, val arcs: MarkArcs = MarkArcs.PAIR)

fun markStateFor(identitySlot: Int?): MarkState = when (identitySlot) {
    0 -> MarkState(Side.LEFT, Side.LEFT)
    1 -> MarkState(Side.RIGHT, Side.RIGHT)
    // No holder, or a roster too large for hue to identify anything: the mark
    // becomes a wordmark and stops carrying state. That is the same drawing
    // `holder == null` has always produced, so the N ≥ 3 path needed no new
    // rendering code at all.
    else -> MarkState(null, null)
}

/**
 * The same mark with its second arc dropped. Keeps the holder's colour, because
 * the one arc still belongs to a specific car.
 */
fun soleMarkStateFor(identitySlot: Int?): MarkState =
    markStateFor(identitySlot).copy(arcs = MarkArcs.SOLE)

data class PrimaryAction(val label: String, val target: Vehicle)

/**
 * With exactly two cars the permit can only ever move to one place, so the
 * screen needs one button rather than two.
 *
 * Null at any other arity, and the caller draws no button. That is not a
 * degraded two-car screen: with three cars "hand it over" is a question — *to
 * whom?* — and the answer is a picker this release deliberately does not build,
 * because there is no third car to build it for. See `docs/TIMELINE.md`,
 * Parked: "Three or more cars".
 */
fun primaryActionFor(roster: Roster, mine: Vehicle, holder: Vehicle?): PrimaryAction? {
    if (roster.size != Roster.PAIR) return null
    val other = roster.other(mine.id) ?: return null
    return when (holder?.id) {
        mine.id -> PrimaryAction("Hand to ${other.name}", other)
        null -> PrimaryAction("Claim it", mine)
        else -> PrimaryAction("Take it back", mine)
    }
}

/**
 * What this spot demands, independently of how it might be settled.
 *
 * This is the *obligation* half of the split Wasil asked for: "permit and
 * tariff paying should become separate but still the same mainframe". Where the
 * car is parked decides whether anything is owed; who holds the permit is a
 * separate question, answered by the card below it. Keeping them apart is what
 * lets a paying action slot in later without the permit having to know.
 */
sealed interface SpotDemand {
    /**
     * Whether the app treats this spot as paid — which is to say, whether the
     * claim path takes the permit here.
     *
     * This exists because the screen and the decision had drifted apart in two
     * places, and both were on the expensive side of the line. Inside a paid
     * polygon outside its charging hours the strip read "Free · from 09:00"
     * while the permit moved; with a tariff file that would not parse it read
     * "Nothing to pay — outside a paid zone" while the permit moved. Neither
     * was a claim bug — the claim is right both times — but a screen may be
     * short of an answer and may not contradict the rest of the app.
     *
     * Every value below fixes this to the one test [dev.wasil.permit.parking.
     * zones.ZoneResolver] makes, so a test can assert the two agree for every
     * shape of zone rather than for the cases someone remembered.
     */
    val paidSpot: Boolean

    /** Nothing is parked, so nothing is owed and nothing needs settling. */
    data object Unparked : SpotDemand {
        override val paidSpot = false
    }

    /**
     * Parked, but with no position — so what is owed cannot be said at all.
     *
     * Previously collapsed into [Unparked], which put "Not parked" in the strip
     * directly above a card reading "Parked — location unknown". Same screen,
     * two beliefs.
     */
    data object LocationUnknown : SpotDemand {
        override val paidSpot = false
    }

    /** Parked somewhere nothing is charged — home, a marked free zone, a free street. */
    data class Free(val reason: String) : SpotDemand {
        override val paidSpot = false
    }

    /** Parked where money is due now. [nowText] already reads as "€3,01/h · until 19:00". */
    data class Payable(val nowText: String) : SpotDemand {
        override val paidSpot = true
    }

    /**
     * A paid zone, outside its charging hours. Nothing is owed this minute and
     * the permit is taken anyway.
     *
     * That is deliberate and was decided against the alternative: park on a
     * Sunday night in a `ma-za 09-24` zone and you are still there at 09:00 on
     * Monday, with nothing to wake the app and claim then. [untilClock] is when
     * charging resumes, or null for an area that carries no windows at all.
     */
    data class FreeForNow(val untilClock: String?) : SpotDemand {
        override val paidSpot = true
    }

    /** A paid zone whose tariff data would not parse. Claimed, and said so. */
    data object RateUnknown : SpotDemand {
        override val paidSpot = true
    }
}

/**
 * What this spot demands, from the same zone the claim path resolved.
 *
 * Takes a [ZoneInfo] rather than the two loose strings it used to, because the
 * strings were where the disagreement got in: "no free-zone reason and no rate
 * text" was indistinguishable from "outside every paid polygon", so an
 * unreadable tariff file rendered as "Nothing to pay". A zone cannot be
 * mistaken for its own absence.
 *
 * [zone] is null when the park resolved no position at all — a gap, not a
 * finding, and the one thing this function must never turn into "nothing owed".
 */
fun spotDemandFor(
    parked: Boolean,
    zone: ZoneInfo?,
    dayIndex: Int,
    minuteOfDay: Int,
): SpotDemand = when {
    !parked -> SpotDemand.Unparked
    zone == null -> SpotDemand.LocationUnknown
    zone !is ZoneInfo.Paid -> SpotDemand.Free(freeReasonFor(zone))
    zone.area == null -> SpotDemand.RateUnknown
    else -> when (val now = tariffNow(zone.area.windows, dayIndex, minuteOfDay)) {
        is TariffNow.Charging -> SpotDemand.Payable(tariffNowText(now, minuteOfDay))
        is TariffNow.Free -> SpotDemand.FreeForNow(
            untilClock = now.startsInMin?.let { clock(minuteOfDay + it) },
        )
    }
}

/**
 * Why a spot owes nothing, in the words the parked-without-claiming
 * notification already uses — so the screen and the notification cannot
 * describe the same place differently.
 */
fun freeReasonFor(zone: ZoneInfo): String = when (zone) {
    ZoneInfo.Home -> "at home"
    is ZoneInfo.ManualFree ->
        "in a free zone" + (zone.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    ZoneInfo.FreeStreet -> "free street parking"
    // Unreachable: spotDemandFor never routes a Paid zone here.
    is ZoneInfo.Paid -> ""
}

/** One line for the strip above the permit card. */
fun spotDemandText(demand: SpotDemand): String = when (demand) {
    SpotDemand.Unparked -> "Not parked"
    SpotDemand.LocationUnknown -> "Parked — location unknown"
    is SpotDemand.Free -> "Nothing to pay — ${demand.reason}"
    is SpotDemand.Payable -> demand.nowText
    is SpotDemand.FreeForNow -> demand.untilClock
        ?.let { "Free until $it · permit held" } ?: "No paid hours · permit held"
    SpotDemand.RateUnknown -> "Paid area — rate unavailable"
}

/**
 * The line that explains a permit sitting on a car that owes nothing this
 * minute, or null when there is nothing to explain.
 *
 * The strip alone cannot carry it: "Free until 09:00 · permit held" states both
 * facts but not the connection between them, and a permit taken for a spot the
 * same screen calls free is exactly the thing that needs a sentence. Null for
 * every other state, so no screen grows a line that says nothing.
 */
fun permitHeldNote(demand: SpotDemand): String? = when (demand) {
    is SpotDemand.FreeForNow -> demand.untilClock?.let {
        "Nothing is owed right now. The permit is held because charging starts " +
            "at $it and the car will still be here."
    } ?: "Nothing is owed right now. The permit is held because this is a paid zone."
    SpotDemand.RateUnknown ->
        "This area's rates couldn't be read. The permit is held rather than risk a fine."
    else -> null
}

// --- the three truths this screen has (v0.6.4) ---

/**
 * Which of the three permit screens is the true one.
 *
 * The app has always worked without a permit and without a second phone; only
 * the copy and the setup gate called those states broken. This is where the
 * difference stops being a defect and becomes a rendering.
 *
 * The signal is **two plates on the permit**, not whether the two phones are
 * linked.
 *
 * v0.6.4 keyed this on sharing and that was wrong twice over. Handing the permit
 * over is a call to the permit website, not a message between phones — it works
 * with no sync at all; the other phone simply is not told. And the two-colour
 * identity was never about phones, it is about there being two cars. Reported
 * within a day of shipping: "i liked the 2 colour way in the app. now it is mono
 * coloured", from an install whose sync URL happened to be unset.
 *
 * A permit that lists one plate genuinely has nowhere to send anything, and that
 * is the case `Sole` is for.
 *
 * There is no fourth case for three or more cars, and that is deliberate rather
 * than an omission. `Shared` degrades on its own: the hero card falls back to
 * `surfaceVariant` because [Roster.identitySlotOf] stops assigning a hue, the
 * mark falls back to a wordmark for the same reason, and the hand-over button
 * disappears because [primaryActionFor] returns null. Every one of those
 * branches already existed for `holder == null`.
 */
sealed interface PermitView {
    /** Two phones, one permit: the hero card, the travelling dot, one button. */
    data object Shared : PermitView

    /** A permit and nowhere to send it. Same card, no button, one arc. */
    data object Sole : PermitView

    /** No permit at all. The obligation half alone, and nothing calling it broken. */
    data object NoPermit : PermitView
}

fun permitViewFor(permitAdded: Boolean, roster: Roster): PermitView = when {
    !permitAdded -> PermitView.NoPermit
    // Cars the permit can actually reach. A roster entry with no plate is a
    // seeded slot nobody has filled in, not a second car.
    roster.platedCount >= Roster.PAIR -> PermitView.Shared
    else -> PermitView.Sole
}

/**
 * The two halves of a live tariff line: "€3,01/h · until 19:00" becomes
 * "€3,01/h" and "until 19:00".
 *
 * A split rather than a second field on [SpotDemand.Payable] because
 * [dev.wasil.permit.ui.tariffNowText] is the only thing that builds these
 * strings and it always joins with " · ". That makes this a coupling between
 * two functions in this repo — the kind v0.5.4 started pinning with a test —
 * rather than a guess about someone else's format. Returns the whole line and a
 * null tail if the separator is ever not there, so a format change degrades to
 * "shows too much" instead of "shows nothing".
 */
fun splitRateLine(nowText: String): Pair<String, String?> {
    val at = nowText.indexOf(" · ")
    if (at < 0) return nowText to null
    return nowText.take(at) to nowText.substring(at + 3).takeIf { it.isNotBlank() }
}

/**
 * The headline for the no-permit screen: what this spot costs, big, with the
 * place and the deadline underneath.
 *
 * This is the whole standalone app in one card. Someone with no permit and no
 * brother still gets the answer they came for, and the app never mentions a
 * feature they do not have.
 */
data class SpotHeadline(val big: String, val detail: String)

fun spotHeadlineFor(demand: SpotDemand, place: String?): SpotHeadline {
    val here = place?.takeIf { it.isNotBlank() }
    return when (demand) {
        SpotDemand.Unparked -> SpotHeadline(
            "Not parked",
            "Park anywhere and this says what it costs.",
        )
        // No permit means no "permit held" to explain — this screen answers
        // only the obligation question, and the honest answer is that we
        // cannot see the spot at all.
        SpotDemand.LocationUnknown -> SpotHeadline(
            "Location unknown",
            "Parked, but the phone couldn't work out where.",
        )
        is SpotDemand.Free -> SpotHeadline(
            "Nothing to pay",
            listOfNotNull(here, demand.reason).joinToString(" · "),
        )
        is SpotDemand.Payable -> {
            val (rate, until) = splitRateLine(demand.nowText)
            SpotHeadline(rate, listOfNotNull(here, until).joinToString(" · "))
        }
        is SpotDemand.FreeForNow -> SpotHeadline(
            "Free now",
            listOfNotNull(here, demand.untilClock?.let { "charges from $it" })
                .joinToString(" · ")
                .ifBlank { "No paid hours here." },
        )
        SpotDemand.RateUnknown -> SpotHeadline(
            "Paid area",
            listOfNotNull(here, "rate unavailable").joinToString(" · "),
        )
    }
}

/**
 * The sole-car card's two lines.
 *
 * Pure and here rather than inline in the composable for a specific reason:
 * reaching this state on an emulator needs a live permit session, so these two
 * strings are the one part of the screen that cannot be checked by eye. The
 * next best thing is a test that cannot be skipped.
 *
 * "Covered" rather than a name. With one car there is no other car to
 * distinguish it from, so printing "Wasil's car" would be answering a question
 * nobody asked; what matters is that the spot is covered.
 */
fun soleCardTitle(covered: Boolean): String = if (covered) "Covered" else "No plate active"

// --- what the screen is allowed to say when the site could not be re-read ---

/**
 * The line that admits the permit card is remembered rather than read, or null
 * when there is nothing to admit.
 *
 * Reported 2026-08-08: the permit site had a hiccup, the app dropped to its
 * blank defaults and showed nothing — while the site was fine in a browser. Two
 * failures in one, and only one of them was the network. Losing a connection is
 * not a reason to forget what you were told; but continuing to show it without
 * saying so would be worse, because "Wasil's car" on a screen means *now*.
 *
 * So the card keeps the last holder and this line says how old it is. That is
 * the same rule the app already applies to the other phone's status — "parked
 * (stale — last seen 14:02)" — and to a failed GPS read, pointed at the permit.
 *
 * Three states, and the third is the one that is easiest to get wrong: with
 * nothing ever read there is no fallback, and the honest sentence says so rather
 * than dressing an empty card as a finding.
 */
fun permitFreshnessNote(readFailed: Boolean, holder: String?, readAtMs: Long): String? = when {
    !readFailed -> null
    holder != null && readAtMs > 0L ->
        "Couldn't reach the permit site — showing what it said at ${hm(readAtMs)}."
    else -> "Couldn't reach the permit site, and nothing is stored to fall back on."
}

private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

fun soleCardSubtitle(vrn: String): String = "$vrn · permit active"

/**
 * The quiet line under the sole-car permit card.
 *
 * The mockup's words are "Nothing to do", and they are the point of the screen:
 * with one car holding the permit there is no decision, so the screen should
 * say so rather than leaving an empty space where a button used to be.
 */
fun soleStatusLine(parked: Boolean, place: String?, sinceClock: String?): String {
    if (!parked) return "The permit is on your car. Nothing to do."
    val where = place?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: ""
    val since = sinceClock?.let { " since $it" } ?: ""
    return "Parked$where$since. Nothing to do."
}
