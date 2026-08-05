package dev.wasil.permit.ui

import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.other

/** Wasil is always the left arc, Walid the right — identical on both phones. */
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

fun markStateFor(holder: MyCar?): MarkState = when (holder) {
    MyCar.WASIL -> MarkState(Side.LEFT, Side.LEFT)
    MyCar.WALID -> MarkState(Side.RIGHT, Side.RIGHT)
    null -> MarkState(null, null)
}

/**
 * The same mark with its second arc dropped. Keeps the holder's colour, because
 * the one arc still belongs to a specific car.
 */
fun soleMarkStateFor(holder: MyCar?): MarkState =
    markStateFor(holder).copy(arcs = MarkArcs.SOLE)

data class PrimaryAction(val label: String, val target: MyCar)

/**
 * With exactly two cars the permit can only ever move to one place, so the
 * screen needs one button rather than two.
 */
fun primaryActionFor(myCar: MyCar, holder: MyCar?): PrimaryAction = when (holder) {
    myCar -> PrimaryAction("Hand to ${myCar.other().label()}", myCar.other())
    null -> PrimaryAction("Claim it", myCar)
    else -> PrimaryAction("Take it back", myCar)
}

fun holderFor(activeVrn: String?, options: List<PlateOption>): MyCar? =
    options.firstOrNull { it.vrn == activeVrn }?.car

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
    /** Nothing is parked, so nothing is owed and nothing needs settling. */
    data object Unparked : SpotDemand

    /** Parked somewhere nothing is charged — home, a marked free zone, a free street. */
    data class Free(val reason: String) : SpotDemand

    /** Parked where money is due. [nowText] already reads as "€3,01/h · until 19:00". */
    data class Payable(val nowText: String) : SpotDemand
}

/**
 * [zoneReason] is null when the spot is chargeable; it carries the wording for
 * a free spot ("at home", "in a free zone") that the notifier already uses, so
 * the screen and the notification cannot describe the same place differently.
 */
fun spotDemandFor(parked: Boolean, zoneReason: String?, nowText: String?): SpotDemand = when {
    !parked -> SpotDemand.Unparked
    zoneReason != null -> SpotDemand.Free(zoneReason)
    nowText != null -> SpotDemand.Payable(nowText)
    // Parked outside a known free zone, but with no readable tariff: the app
    // does not know what is owed, and saying "free" would be a guess that
    // could cost a fine.
    else -> SpotDemand.Free("outside a paid zone")
}

/** One line for the strip above the permit card. */
fun spotDemandText(demand: SpotDemand): String = when (demand) {
    SpotDemand.Unparked -> "Not parked"
    is SpotDemand.Free -> "Nothing to pay — ${demand.reason}"
    is SpotDemand.Payable -> demand.nowText
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
 */
sealed interface PermitView {
    /** Two phones, one permit: the hero card, the travelling dot, one button. */
    data object Shared : PermitView

    /** A permit and nowhere to send it. Same card, no button, one arc. */
    data object Sole : PermitView

    /** No permit at all. The obligation half alone, and nothing calling it broken. */
    data object NoPermit : PermitView
}

fun permitViewFor(permitAdded: Boolean, hasSecondPlate: Boolean): PermitView = when {
    !permitAdded -> PermitView.NoPermit
    hasSecondPlate -> PermitView.Shared
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
        is SpotDemand.Free -> SpotHeadline(
            "Nothing to pay",
            listOfNotNull(here, demand.reason).joinToString(" · "),
        )
        is SpotDemand.Payable -> {
            val (rate, until) = splitRateLine(demand.nowText)
            SpotHeadline(rate, listOfNotNull(here, until).joinToString(" · "))
        }
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
