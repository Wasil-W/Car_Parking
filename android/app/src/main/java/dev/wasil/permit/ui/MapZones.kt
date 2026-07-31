package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters

/** The smallest and largest a zone circle can be, and the size a fresh one starts at.
 *
 * Matches the existing Home zone slider range (see docs/BACKLOG.md's locked
 * decision: "Home zone is a small circle (30-200 m), deliberately not an
 * Amsterdam neighbourhood polygon"). Free zones get the same range for the
 * same reason — a marked-free spot that sprawls stops meaning "here".
 */
const val ZONE_RADIUS_MIN_M = 30.0
const val ZONE_RADIUS_MAX_M = 200.0
const val ZONE_RADIUS_DEFAULT_M = 60.0

fun clampZoneRadius(radiusM: Double): Double = radiusM.coerceIn(ZONE_RADIUS_MIN_M, ZONE_RADIUS_MAX_M)

/** Identifies one zone circle on the map: the single home zone, or one entry in
 * the free-zone list (by index, matching [dev.wasil.permit.parking.FreeZoneStore]'s
 * own indexing). */
sealed interface ZoneRef {
    data object Home : ZoneRef
    data class Free(val index: Int) : ZoneRef
}

/**
 * Which existing zone circle, if any, contains [point] — used to let a tap on
 * the map remove a zone instead of needing a separate list. When circles
 * overlap, the one whose centre is nearest to the tap wins, so a tap near the
 * shared edge of a big zone and a small zone inside it hits the small one.
 */
fun zoneHitAt(point: GeoPoint, home: FreeZone?, freeZones: List<FreeZone>): ZoneRef? {
    val hits = buildList {
        home?.let { zone -> if (withinZone(point, zone)) add(ZoneRef.Home to zone) }
        freeZones.forEachIndexed { index, zone ->
            if (withinZone(point, zone)) add(ZoneRef.Free(index) to zone)
        }
    }
    return hits.minByOrNull { (_, zone) -> distanceMeters(point, zone.centre()) }?.first
}

private fun withinZone(point: GeoPoint, zone: FreeZone): Boolean =
    distanceMeters(point, zone.centre()) <= zone.radiusM

private fun FreeZone.centre(): GeoPoint = GeoPoint(lat, lng, 0f)
