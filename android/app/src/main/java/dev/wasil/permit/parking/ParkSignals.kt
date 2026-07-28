package dev.wasil.permit.parking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ActivityType { IN_VEHICLE, STILL, ON_FOOT, OTHER }

data class ActivitySample(val type: ActivityType, val confidence: Int, val elapsedMs: Long)

data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Float)

/** Haversine great-circle distance. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}
