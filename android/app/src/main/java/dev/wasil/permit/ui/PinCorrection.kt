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

/**
 * Whether re-resolving the zone changed the paid/free answer.
 *
 * [UNKNOWN_BEFORE] is not "no change" — it is *there was nothing to change
 * from*. A park the app could not place never resolved a zone, so it never
 * published an answer about the spot; the first placement is the first finding,
 * not a correction to one.
 *
 * Keeping it distinct from [NONE] matters because they lead different places.
 * [NONE] means say nothing (USE-CASES C9: the obligation did not change, so
 * there is nothing to put to the user). [UNKNOWN_BEFORE] must always state what
 * it resolved and always offer the claim or hand-back question — otherwise a
 * first placement landing in a free zone would compute [NONE] against the
 * `parkedOutside = false` that `CarBluetoothReceiver` writes at the *start of
 * every drive*, ask nothing, and leave `parkedOutsideKnown` false forever.
 */
enum class Flip { NONE, NOW_PAID, NOW_FREE, UNKNOWN_BEFORE }

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
    /**
     * Where the pin is now, or **null when the park never had a position at
     * all**.
     *
     * Null means the 300 m cap does not apply, and that is deliberate rather
     * than a hole: the cap exists to stop a *correction* wandering away from a
     * detected point, and here there is no detected point to wander from. The
     * caller writes this first placement into `detectedParkLocation`, so every
     * subsequent move is capped against it — the guard is postponed by one
     * placement, never removed.
     */
    from: GeoPoint?,
    to: GeoPoint,
    /** Null when the park never resolved a zone, so no answer was ever given. */
    wasParkedOutside: Boolean?,
    resolver: ZoneResolver,
): CorrectionResult {
    if (from != null) {
        val distanceM = distanceMeters(from, to)
        if (distanceM > CORRECTION_MAX_M) return CorrectionResult.TooFar(distanceM)
    }

    val zone = resolver.resolve(to)
    val parkedOutside = zone is ZoneInfo.Paid
    return CorrectionResult.Ok(
        point = to,
        zoneCode = (zone as? ZoneInfo.Paid)?.area?.code,
        parkedOutside = parkedOutside,
        flip = when {
            wasParkedOutside == null -> Flip.UNKNOWN_BEFORE
            parkedOutside == wasParkedOutside -> Flip.NONE
            parkedOutside -> Flip.NOW_PAID
            else -> Flip.NOW_FREE
        },
    )
}
