package dev.wasil.permit.parking

import kotlinx.serialization.Serializable

@Serializable
data class FreeZone(
    val lat: Double,
    val lng: Double,
    val radiusM: Double = 60.0,
    val label: String = "",
)

fun isInFreeZone(point: GeoPoint, zones: List<FreeZone>): Boolean =
    zones.any { distanceMeters(point, GeoPoint(it.lat, it.lng, 0f)) <= it.radiusM }

interface FreeZoneStore {
    fun all(): List<FreeZone>
    fun add(zone: FreeZone)
    fun removeAt(index: Int)
    /** Rename only — position and radius are untouched. Lets a hand-typed name replace a geocoded or coordinate one. */
    fun updateLabel(index: Int, label: String)
}
