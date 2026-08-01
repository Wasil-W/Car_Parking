package dev.wasil.permit.parking.route

import dev.wasil.permit.parking.GeoPoint
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A walking route back to the car: the line to draw, and how far and how long. */
data class WalkRoute(
    val points: List<GeoPoint>,
    val distanceM: Double,
    val durationS: Double,
)

/**
 * Walking routes from OSRM's public demo server.
 *
 * Chosen for the same reason osmdroid was chosen over the Maps SDK: it needs no
 * API key and no billing account. It is a courtesy service with no uptime
 * promise, which is survivable here because a failed route is not a failed
 * feature — the caller falls back to a straight line, which for a car parked a
 * few streets away is most of the value anyway.
 */
object OsrmRoute {

    /**
     * Note the coordinate order: OSRM takes **lng,lat**, like GeoJSON and
     * unlike almost every Android API. Getting this backwards routes you across
     * the Indian Ocean rather than failing loudly, so it is worth a test.
     */
    fun url(from: GeoPoint, to: GeoPoint): String =
        "https://router.project-osrm.org/route/v1/foot/" +
            "${from.lng},${from.lat};${to.lng},${to.lat}" +
            "?overview=full&geometries=geojson"

    /** Null for anything unparseable, a routing failure, or an empty line. */
    fun parse(json: String): WalkRoute? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        if (root["code"]?.jsonPrimitive?.content != "Ok") return null
        val route = root.getValue("routes").jsonArray.firstOrNull()?.jsonObject ?: return null
        val coords = route.getValue("geometry").jsonObject.getValue("coordinates").jsonArray
        val points = coords.mapNotNull { pos ->
            val arr = pos.jsonArray
            val lng = arr[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val lat = arr[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            GeoPoint(lat, lng, 0f)
        }
        if (points.size < 2) return null
        WalkRoute(
            points = points,
            distanceM = route["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            durationS = route["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }.getOrNull()
}

/** An unhurried city walking pace, 5 km/h. */
const val WALK_SPEED_MPS = 1.39

/**
 * "12 min · 984 m".
 *
 * The time is computed from the distance rather than taken from the routing
 * service, and that is deliberate. OSRM's public demo server does not reliably
 * serve the `foot` profile and quietly answers with driving times instead: a
 * 984 m walk came back as "2 min", which is 30 km/h. A number that wrong is
 * worse than no number, and it was only caught by reading it on screen.
 *
 * Formatted in the device's locale on purpose, so a Dutch phone reads "1,4 km"
 * — matching the comma decimals Amsterdam's own tariff data uses ("€8,05/h").
 */
fun walkSummary(route: WalkRoute): String {
    val distance = if (route.distanceM >= 1000) {
        String.format(Locale.getDefault(), "%.1f km", route.distanceM / 1000)
    } else {
        String.format(Locale.getDefault(), "%.0f m", route.distanceM)
    }
    if (route.distanceM <= 0) return distance
    val minutes = Math.max(1, Math.round(route.distanceM / WALK_SPEED_MPS / 60).toInt())
    return "$minutes min · $distance"
}
