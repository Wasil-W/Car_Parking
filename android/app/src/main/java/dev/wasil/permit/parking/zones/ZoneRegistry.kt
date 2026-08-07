package dev.wasil.permit.parking.zones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One of Amsterdam's 107 permit-parking zones — `parkeerzones`, every one of
 * them `gebruiksdoel = VERGUNP`. This layer says **where permit parking exists
 * at all**; there is no `BETAALDP` in the collection, so it is not a
 * permit-versus-paid switch and must never be read as one.
 */
data class PermitZone(
    /** `gebiedscode` — "AN05C". */
    val code: String,
    /** `gebiedsnaam` — "Noord 5c NDSM". 44 of the 107 carry a place name; the rest are numbered. */
    val name: String,
    val polygons: List<ZonePolygon>,
)

/**
 * One of the 518 `buurten` — the finest named areas the city publishes, and
 * the ones Wasil saw on the council's map: "Molenwijk", "NDSM terrein",
 * "Waterloopleinbuurt".
 */
data class Neighbourhood(
    val name: String,
    /** The stadsdeel above it: "Centrum", "Noord", "Zuidoost". */
    val district: String,
    val polygons: List<ZonePolygon>,
)

/**
 * What the registry knows about a position. Any field may be null on its own:
 * the two layers are independent, so a spot can sit in a buurt with no permit
 * zone over it (most of Noord) or — in principle — the other way round.
 */
data class ZonePlace(
    val zoneCode: String?,
    val zoneName: String?,
    val neighbourhood: String?,
    val district: String?,
)

/**
 * Amsterdam's real parking zones and neighbourhoods, bundled rather than
 * fetched.
 *
 * **Why this exists.** The app resolved "which area am I in" against the 29
 * bundled tariff regions, which are neighbourhood blobs up to 3 km across.
 * They answer *what an hour costs around here* — a different question from
 * *where am I*, and the reason the map was "not specific enough per area".
 * That was a data problem, not a styling one.
 *
 * **What it deliberately does not do.** It takes no part in the claim
 * decision. [ZoneResolver] still decides Paid/Home/FreeStreet from the tariff
 * polygons alone, and nothing here is wired into it. Where you parked decides
 * whether anything is owed; this only says what the place is called.
 *
 * **Why bundled.** The app works offline and a parking decision must not wait
 * on a network call. Both collections were fetched with `Accept-Crs:
 * EPSG:4326` (the default is RD New, EPSG:28992 — one header, not the hazard
 * an earlier draft called it), entries with an `eindGeldigheid` dropped, and
 * rings re-encoded as polylines. See the asset's own `fetched`, `source` and
 * `note` fields for provenance.
 */
class ZoneRegistry(
    val zones: List<PermitZone>,
    val neighbourhoods: List<Neighbourhood>,
) {
    /**
     * Null when the point is in neither layer — outside Amsterdam, or in the
     * water. The caller falls back to the geocoder, which is the honest answer
     * rather than the nearest guess.
     *
     * First match wins. Both layers tile rather than overlap: 3,000 random
     * points across the city's bounding box produced no double hit in either
     * collection, so there is no tie to break.
     */
    /**
     * The boundary of a neighbourhood by name, or null if this build does not
     * have one under that name.
     *
     * Added in v0.6.8, when a free zone stopped being a circle and became a
     * neighbourhood. Names are the key rather than indices — see
     * [dev.wasil.permit.parking.FreeZone.buurt] — and a name that no longer
     * resolves returns null rather than the nearest match, because a zone
     * silently moving to a different neighbourhood is worse than one that stops
     * matching.
     *
     * Ambiguity is real and settled by taking all of them: the city has more
     * than one buurt called "Amstel III deel A/B/C" and similar, and a person
     * who marked one of those free almost certainly meant the place, not one of
     * its administrative halves.
     */
    fun shapeOf(neighbourhood: String): List<ZonePolygon>? =
        neighbourhoods.filter { it.name.equals(neighbourhood, ignoreCase = true) }
            .flatMap { it.polygons }
            .takeIf { it.isNotEmpty() }

    fun resolve(point: LatLng): ZonePlace? {
        val zone = zones.firstOrNull { it.polygons.any { p -> pointInPolygon(point, p) } }
        val hood = neighbourhoods.firstOrNull { it.polygons.any { p -> pointInPolygon(point, p) } }
        if (zone == null && hood == null) return null
        return ZonePlace(zone?.code, zone?.name, hood?.name, hood?.district)
    }

    companion object {
        /** Null on anything unreadable — a missing name is better than a wrong one. */
        fun parse(json: String): ZoneRegistry? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val districts = root.getValue("districts").jsonArray
                .map { it.jsonPrimitive.content }
            val zones = root.getValue("zones").jsonArray.mapNotNull { entry ->
                val obj = entry.jsonObject
                val code = obj.string("c") ?: return@mapNotNull null
                val name = obj.string("n") ?: return@mapNotNull null
                PermitZone(code, name, obj.geometry() ?: return@mapNotNull null)
            }
            val hoods = root.getValue("buurten").jsonArray.mapNotNull { entry ->
                val obj = entry.jsonObject
                val name = obj.string("n") ?: return@mapNotNull null
                val district = districts.getOrNull(obj["d"]?.jsonPrimitive?.intOrNull ?: -1)
                    ?: return@mapNotNull null
                Neighbourhood(name, district, obj.geometry() ?: return@mapNotNull null)
            }
            ZoneRegistry(zones, hoods).takeIf { zones.isNotEmpty() && hoods.isNotEmpty() }
        }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        /** `g` is a list of polygons, each a list of rings; the first ring is the outer one. */
        private fun JsonObject.geometry(): List<ZonePolygon>? {
            val polygons = getValue("g").jsonArray.map { poly ->
                val rings = poly.jsonArray.map { decodePolyline(it.jsonPrimitive.content) }
                ZonePolygon(outer = rings.first(), holes = rings.drop(1))
            }
            return polygons.takeIf { it.isNotEmpty() && it.all { p -> p.outer.size >= 3 } }
        }
    }
}

/**
 * Google's encoded-polyline format, precision 5 (~1 m), latitude then
 * longitude, with the ring's repeated closing point dropped — ray casting does
 * not need it.
 *
 * Chosen over plain coordinate arrays purely for size: the two collections are
 * 35,000 vertices, which is 1.9 MB as fetched and 123 KB like this, at full
 * source precision. Nothing is simplified, so no boundary moves and no sliver
 * opens up between two neighbourhoods that share an edge.
 */
fun decodePolyline(encoded: String): List<LatLng> {
    val points = ArrayList<LatLng>(encoded.length / 6 + 1)
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var byte: Int
        do {
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        shift = 0
        result = 0
        do {
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        points.add(LatLng(lat / 1e5, lng / 1e5))
    }
    return points
}
