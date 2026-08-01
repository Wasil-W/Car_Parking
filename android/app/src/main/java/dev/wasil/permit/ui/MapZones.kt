package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.pointInPolygon

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

/**
 * What a tap on bare map landed on.
 *
 * The car marker is absent on purpose: osmdroid hit-tests markers itself, and
 * the car marker's own click listener consumes the tap before the map events
 * overlay ever sees it.
 */
sealed interface MapHit {
    data class Zone(val ref: ZoneRef) : MapHit
    data class Tariff(val area: TariffArea) : MapHit
}

/**
 * The single precedence rule for a tap on bare map:
 *
 *  1. Zone circles — home and free — by the existing nearest-centre rule.
 *  2. Tariff areas, and only the ones passed in.
 *  3. Nothing.
 *
 * Zones beat tariff areas because a zone is something placed by hand, while a
 * tariff area is neighbourhood-sized and will almost always be under the tap as
 * well: the specific thing beats the ambient thing.
 *
 * Callers pass an empty [tariffAreas] when the overlay is switched off, so the
 * toggle needs no branch of its own. Callers must not call this at all while
 * placing a zone candidate or moving the car pin — in those modes every tap is
 * a placement, and that is decided before this is reached.
 */
fun mapHitAt(
    point: GeoPoint,
    home: FreeZone?,
    freeZones: List<FreeZone>,
    tariffAreas: List<TariffArea>,
): MapHit? {
    zoneHitAt(point, home, freeZones)?.let { return MapHit.Zone(it) }
    return tariffAreaAt(point, tariffAreas)?.let { MapHit.Tariff(it) }
}

/**
 * The first tariff area containing [point] — matching how
 * [dev.wasil.permit.parking.zones.ZoneResolver] picks one, so the map can never
 * disagree with the claim decision about which area you are in.
 */
fun tariffAreaAt(point: GeoPoint, areas: List<TariffArea>): TariffArea? {
    val p = LatLng(point.lat, point.lng)
    return areas.firstOrNull { area -> area.polygons.any { pointInPolygon(p, it) } }
}

/**
 * Rate first, then the description that carries the hours — the rate is the
 * part worth reading at a glance. Amsterdam's own comma decimal is kept.
 */
fun tariffSummary(area: TariffArea): String =
    listOf(area.tariffText, area.name).filter { it.isNotBlank() }.joinToString(" · ")
