package dev.wasil.permit.parking

import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.zones.pointInPolygon
import kotlin.math.cos
import kotlinx.serialization.Serializable

/**
 * A circle with a centre and a radius, or a named neighbourhood — depending
 * entirely on which of the two things is using it.
 *
 * **A home zone is always the circle.** A point you own: your street, your
 * house, 30–200 m, and a locked decision in `BACKLOG.md`.
 *
 * **A free zone is always the neighbourhood.** [buurt] set, boundary published
 * by the city, nothing dragged and therefore nothing approximate. Wasil's own
 * answer, 2026-08-08, pointing at the council's map: *"do you see the molenwijk.
 * We could do that we can put those for the free zones, with outline."*
 *
 * That is the distinction that stops the two feeling identical, and it is a real
 * one rather than a styling choice: a home is a point, an area is an area.
 *
 * **Free zones are area-backed only** — there is no circle fallback outside
 * Amsterdam, and that is deliberate rather than unfinished. Wasil: *"Utrecht
 * will get its own update hahaha."* The app has always been Amsterdam-only —
 * Amsterdam tariffs, Amsterdam buurten, an Amsterdam visitor permit — and free
 * zones were the one feature that happened to work anywhere, only because a
 * circle does not need to know what city it is in. Coverage is the extent of the
 * bundled polygons, so "can I place one here" is answered by containment rather
 * than by a city name; see `NO_NEIGHBOURHOOD_HERE`.
 *
 * **One type with an optional field rather than two types**, because two types
 * would need polymorphic serialisation for no benefit: the home zone needs the
 * circle fields regardless, so a free zone that never uses them costs a nullable
 * key in the JSON and nothing else.
 *
 * [lat] and [lng] mean "where to look" on a free zone rather than "where the
 * zone is": they are the point that was tapped, and they are what the zone list
 * flies the camera to. [radiusM] is not read for one at all.
 */
@Serializable
data class FreeZone(
    val lat: Double,
    val lng: Double,
    val radiusM: Double = 60.0,
    val label: String = "",
    /**
     * The neighbourhood whose boundary is this zone, or null for a plain circle.
     *
     * Stored as the buurt's **name** rather than an index into the bundled
     * asset, because the name is what the city publishes, what the user picked,
     * and what still means something if the asset is ever refetched with the
     * entries in a different order. An index would silently point at a different
     * neighbourhood after any such refresh.
     */
    val buurt: String? = null,
) {
    /** True when this zone's shape comes from the city rather than from a slider. */
    val isArea: Boolean get() = buurt != null
}

/**
 * Whether [point] falls in any of [zones], with [areaShape] supplying the
 * boundary for the ones backed by a neighbourhood.
 *
 * **An area-backed zone whose boundary cannot be found does not match.** That is
 * the direction that cannot cost a fine: a zone we cannot test is not evidence
 * that parking is free, and failing to match simply lets the tariff polygons
 * decide, which is what the app did before any free zone existed. The opposite
 * default — "we could not check, so assume free" — is the one that ends with a
 * car unpermitted on a paid street.
 */
fun isInFreeZone(
    point: GeoPoint,
    zones: List<FreeZone>,
    areaShape: (String) -> List<ZonePolygon>? = { null },
): Boolean = zones.any { containsPoint(it, point, areaShape) }

internal fun containsPoint(
    zone: FreeZone,
    point: GeoPoint,
    areaShape: (String) -> List<ZonePolygon>?,
): Boolean {
    // No circle branch. A free zone with no neighbourhood cannot be created and
    // is not loaded — see [FreeZoneStore.all] — so one reaching here is a zone
    // the app cannot test, and an untestable zone is not evidence that parking
    // is free.
    val buurt = zone.buurt ?: return false
    val shape = areaShape(buurt) ?: return false
    val p = LatLng(point.lat, point.lng)
    return shape.any { pointInPolygon(p, it) }
}

/**
 * Roughly how much ground a set of rings covers, in square kilometres.
 *
 * Shown when a neighbourhood is offered as a free zone, and the reason is not
 * decoration. Marking an area free tells the app never to claim the permit
 * anywhere inside it, so a large one is a strong claim — and buurten vary from a
 * few streets to most of a district. If the choice is wrong it is wrong in the
 * expensive direction, so the size belongs in front of the person making it
 * rather than in a document nobody reads.
 *
 * The shoelace formula on degrees, with longitude scaled by cos(latitude) so a
 * shape in Amsterdam is not reported 40% too large. Good to well under a percent
 * over a neighbourhood, which is far more precision than "is this bigger than I
 * meant" needs. Holes are subtracted; a ring is unsigned, so orientation does
 * not matter.
 */
fun areaSqKm(polygons: List<ZonePolygon>): Double =
    polygons.sumOf { polygon ->
        (ringSqKm(polygon.outer) - polygon.holes.sumOf { ringSqKm(it) }).coerceAtLeast(0.0)
    }

private const val KM_PER_DEGREE_LAT = 111.32

private fun ringSqKm(ring: List<LatLng>): Double {
    if (ring.size < 3) return 0.0
    val midLat = ring.sumOf { it.lat } / ring.size
    val lngScale = cos(Math.toRadians(midLat))
    var twiceArea = 0.0
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        twiceArea += (b.lng - a.lng) * (b.lat + a.lat)
        j = i
    }
    val degSq = kotlin.math.abs(twiceArea) / 2.0
    return degSq * lngScale * KM_PER_DEGREE_LAT * KM_PER_DEGREE_LAT
}

/**
 * "0.6 km²", or "8 ha" for anything small enough that square kilometres would
 * round to nothing. A person can picture eight hectares; they cannot picture
 * 0.08 km².
 */
fun areaSizeText(sqKm: Double): String = when {
    sqKm >= 1.0 -> "%.1f km²".format(sqKm)
    sqKm >= 0.1 -> "%.2f km²".format(sqKm)
    else -> "%.0f ha".format(sqKm * 100)
}

interface FreeZoneStore {
    /**
     * Every free zone, and every one of them area-backed.
     *
     * Implementations drop anything without a [FreeZone.buurt]. Zones saved as
     * circles by v0.6.7 therefore fall away on upgrade rather than lingering as
     * entries the app lists but no longer honours, which is the one outcome
     * worse than losing them. Wasil, asked about carrying them over: *"Dont
     * really need them as i only have one for my home zone, 1sec fix"* — his
     * single circle is the *home* zone, which is stored separately and is
     * untouched by any of this.
     */
    fun all(): List<FreeZone>
    fun add(zone: FreeZone)
    fun removeAt(index: Int)
    /** Rename only — position, radius and area are untouched. Lets a hand-typed name replace a geocoded or coordinate one. */
    fun updateLabel(index: Int, label: String)

    /**
     * Replace one zone outright, so a zone can be resized after it was placed.
     *
     * Kept alongside [updateLabel] rather than replacing it: the geocoder's
     * late-arriving address is a rename of *whatever the zone is now*, and
     * writing a whole zone back from a closure captured before the user
     * resized it would silently undo their change.
     */
    fun replaceAt(index: Int, zone: FreeZone)
}
