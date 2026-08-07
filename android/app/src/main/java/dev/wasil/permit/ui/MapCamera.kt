package dev.wasil.permit.ui

/**
 * The one thing the map is asked to do to its camera in a given update pass.
 *
 * [OVERVIEW] pulls back to a zoom where tariff-area boundaries exist on screen,
 * keeping the centre. [LOCATE] centres on your own position. [FRAME] fits the
 * car and you into one view. [NONE] leaves whatever the user panned to alone,
 * which is the answer far more often than the other three.
 */
enum class MapCameraCommand { NONE, OVERVIEW, LOCATE, FRAME, CAR, SPOT }

/**
 * Which camera move wins when more than one is pending in the same pass.
 *
 * This exists because more than one *is* pending, routinely. Tapping "locate
 * me" re-reads the position, so the new fix arrives in the same recomposition
 * as the request that asked for it — and a changed position is also exactly
 * what triggers the automatic car-and-me framing. Left to run in source order
 * the automatic fit would quietly undo the button the user just pressed, and
 * not even reliably: the bounding-box fit has to be posted to the view, so
 * "whichever line runs last" is not the same as "whichever move lands last".
 *
 * The order below is "what was asked for beats what was inferred":
 *
 *  1. [OVERVIEW] — the tariff overlay is useless at parking zoom, so switching
 *     it on is a request about the camera as much as about the layer.
 *  2. [SPOT] — "show me this zone", from a row in the zone list. Ranked first
 *     among the taps because it is the only one naming a place the user cannot
 *     see: the whole reason for tapping it is that the zone is off screen.
 *  3. [LOCATE] — an explicit tap, and only when there is a position to centre
 *     on. Without one the honest answer is to do nothing and say so; moving the
 *     map to a stale or invented point would be publishing a guess.
 *  4. [CAR] — the third stop of the focus cycle, and only when a car pin
 *     exists. Ranked above framing for the same reason as LOCATE: it is a tap.
 *  5. [FRAME] — the explicit tap, then the automatic first fit, which are the
 *     same move for different reasons.
 *
 * Pure and outside the composable so the precedence can be held still by a
 * test: it is the kind of rule that reads obviously correct and is wrong.
 */
fun mapCameraCommand(
    overviewPending: Boolean,
    locatePending: Boolean,
    haveMyPosition: Boolean,
    framePending: Boolean,
    carPending: Boolean,
    haveCar: Boolean,
    autoFramePending: Boolean,
    spotPending: Boolean = false,
    haveSpot: Boolean = false,
): MapCameraCommand = when {
    overviewPending -> MapCameraCommand.OVERVIEW
    spotPending && haveSpot -> MapCameraCommand.SPOT
    locatePending && haveMyPosition -> MapCameraCommand.LOCATE
    carPending && haveCar -> MapCameraCommand.CAR
    framePending || autoFramePending -> MapCameraCommand.FRAME
    else -> MapCameraCommand.NONE
}
