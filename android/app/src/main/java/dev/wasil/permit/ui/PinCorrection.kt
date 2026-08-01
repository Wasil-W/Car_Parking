package dev.wasil.permit.ui

import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.ZoneResolver

/**
 * The furthest a correction may move the car pin.
 *
 * This is a fat-finger guard, not a GPS one. A mis-tap that keeps the car in a
 * paid area harms nothing — the other phone's guard reads the `parkedOutside`
 * flag, not the coordinates. A mis-tap that lands inside a free zone flips that
 * flag to false, which tells the other phone it is free to claim while the car
 * is actually on a paid street with no permit. That is a fine, and it lands on
 * whoever's car was left behind.
 *
 * 300 m is far beyond any accepted fix (`ParkDecisionEngine` rejects accuracy
 * worse than 25 m) and far short of a different neighbourhood. A tap further
 * away is not a correction at all — it is a different parking spot, and
 * detection will pick that up on its own.
 */
const val CORRECTION_MAX_M = 300.0

/** Whether re-resolving the zone changed the paid/free answer. */
enum class Flip { NONE, NOW_PAID, NOW_FREE }

sealed interface CorrectionResult {
    data class TooFar(val distanceM: Double) : CorrectionResult

    /** Not yet applied — the caller writes these to the store on confirm. */
    data class Ok(
        val point: GeoPoint,
        val zoneCode: String?,
        val parkedOutside: Boolean,
        val flip: Flip,
    ) : CorrectionResult
}

/**
 * What moving the car pin from [from] to [to] would mean.
 *
 * The zone is re-resolved rather than carried over, because that is the whole
 * point: 40 m is the difference between a paid street and the free zone around
 * the corner, so a correction can change whether the permit should be held at
 * all. Deciding is all this does — it never claims, releases or writes.
 */
fun correctionFor(
    from: GeoPoint,
    to: GeoPoint,
    wasParkedOutside: Boolean,
    resolver: ZoneResolver,
): CorrectionResult {
    val distanceM = distanceMeters(from, to)
    if (distanceM > CORRECTION_MAX_M) return CorrectionResult.TooFar(distanceM)

    val zone = resolver.resolve(to)
    val parkedOutside = zone is ZoneInfo.Paid
    return CorrectionResult.Ok(
        point = to,
        zoneCode = (zone as? ZoneInfo.Paid)?.area?.code,
        parkedOutside = parkedOutside,
        flip = when {
            parkedOutside == wasParkedOutside -> Flip.NONE
            parkedOutside -> Flip.NOW_PAID
            else -> Flip.NOW_FREE
        },
    )
}
