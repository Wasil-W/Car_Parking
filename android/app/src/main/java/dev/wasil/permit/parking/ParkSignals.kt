package dev.wasil.permit.parking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ActivityType { IN_VEHICLE, STILL, ON_FOOT, OTHER }

data class ActivitySample(val type: ActivityType, val confidence: Int, val elapsedMs: Long)

data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Float)

/**
 * How old the phone's cached fix may be and still stand in for a fresh one.
 *
 * Two minutes, and the tightness is the point. Detection only ever runs just
 * after the car's Bluetooth drops, and the phone was under open sky getting
 * fixes seconds earlier — so a genuinely useful cached fix is almost always
 * only seconds old. Anything much older is more likely to be from earlier in
 * the drive, and *that* is the dangerous direction: a stale fix from the start
 * of the journey resolves to the home zone, the app quietly decides no permit
 * is needed, and the car collects a fine in town.
 *
 * Being wrong in the other direction costs nothing — when no fix is usable the
 * app falls back to asking, exactly as it did before.
 */
const val CACHED_FIX_MAX_AGE_MS = 2 * 60_000L

/**
 * The phone's last known position, but only if it is recent enough to be
 * believable. Negative ages (a clock that moved) are rejected rather than
 * treated as "brand new".
 */
fun cachedFixIfFresh(point: GeoPoint?, ageMs: Long): GeoPoint? =
    point?.takeIf { ageMs in 0..CACHED_FIX_MAX_AGE_MS }

/** Haversine great-circle distance. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}
