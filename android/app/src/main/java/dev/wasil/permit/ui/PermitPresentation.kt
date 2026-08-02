package dev.wasil.permit.ui

import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.other

/** Wasil is always the left arc, Walid the right — identical on both phones. */
enum class Side { LEFT, RIGHT }

data class MarkState(val lit: Side?, val dot: Side?)

fun markStateFor(holder: MyCar?): MarkState = when (holder) {
    MyCar.WASIL -> MarkState(Side.LEFT, Side.LEFT)
    MyCar.WALID -> MarkState(Side.RIGHT, Side.RIGHT)
    null -> MarkState(null, null)
}

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
