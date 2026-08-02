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
