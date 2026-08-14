package dev.wasil.permit.parking.facilities

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A garage or P+R site the city publishes — a place you *choose to drive to*,
 * which is a different kind of thing from everything else on this map.
 *
 * Every other location the app knows is somewhere you already are: the car's
 * pin, your position, the tariff area under your feet. A facility is the first
 * entry that answers *where could I go instead*, and that is why it earns its
 * own layer rather than joining the zone overlay.
 */
data class Facility(
    val name: String,
    val kind: FacilityKind,
    val lat: Double,
    val lng: Double,
    /** Street address as the council publishes it. */
    val address: String?,
    /** The council's own page — where the authoritative price lives. */
    val url: String?,
    /**
     * The operator's published rate lines, or empty.
     *
     * **Empty for 20 of the 37, and that is said on screen rather than left as a
     * blank.** Rates come from the national register, which covers roughly half
     * of these; a parking sheet with no price reads as *free* to anyone moving
     * quickly, which is the expensive direction to be misread in.
     */
    val rates: List<RateLine>,
    /** When the register last refreshed this facility's rates. Null when it has none. */
    val ratesUpdated: Long?,
)

enum class FacilityKind {
    /** Hourly, and priced by how long you stay. */
    GARAGE,

    /**
     * A flat day rate that assumes you continue by public transport, and the
     * cheapest answer in the city by a wide margin — P+R Sloterdijk is
     * "1,00 per 24u" against €8,05 an hour on a Centrum street. Its own kind
     * because that difference is the whole reason to show any of this.
     */
    PARK_AND_RIDE,
}

/**
 * One published rate, **quoted rather than computed**.
 *
 * This is a reversal, and the reason is worth keeping. The register also
 * publishes structured `intervalRates`, and the obvious move is to evaluate
 * them and render a number. They cannot be evaluated. P3 Mikado's hourly entry
 * carries three bands over *overlapping* durations — `1 per 24min [0,24)`,
 * `1 per 25min [24,∞)` and `1 per 20min [0,∞)` — which cannot all apply, and
 * the description says the third. Summing them quoted €130 for a day at a
 * garage whose own day ticket is €30.
 *
 * So there is no garage rate engine. [text] is the operator's own sentence,
 * shipped verbatim, and it cannot be wrong in a way this app invented.
 *
 * It is in Dutch, deliberately. These are published prices; translating one
 * risks changing a claim about money, and the app already shows Molenwijk
 * rather than "Mill District".
 */
data class RateLine(
    val text: String,
    /**
     * ISO weekdays (Monday = 1) this line applies to. Empty means every day,
     * which is the common case — the weekday-only lines are the P+R morning
     * surcharge, "Eerste 24u 8,00".
     */
    val days: Set<Int> = emptySet(),
) {
    fun appliesOn(isoWeekday: Int): Boolean = days.isEmpty() || isoWeekday in days
}

/**
 * Amsterdam's published garages and P+R sites, bundled rather than fetched.
 *
 * **Why these 37 and not the 75 that exist.** Three registers publish Amsterdam
 * facilities and they disagree. Reconciling them yields 75 distinct places, but
 * the municipal entries in the national register carry a name and nothing else —
 * no coordinate, and that register's address table holds the council's own
 * postbus rather than the garage. Placing those means geocoding a name, and a
 * geocoder given "P+R RAI" returns P+R Muiden, eleven kilometres away; that was
 * measured, not assumed. A pin is a claim about where something is, so the ones
 * that ship are the ones the council itself positioned.
 */
class FacilityRegistry(val facilities: List<Facility>) {

    /** Nearest facility, or null when none is within [withinMetres]. */
    fun nearest(lat: Double, lng: Double, withinMetres: Double = Double.MAX_VALUE): Facility? =
        facilities.minByOrNull { distanceM(lat, lng, it.lat, it.lng) }
            ?.takeIf { distanceM(lat, lng, it.lat, it.lng) <= withinMetres }

    /** Facilities within [metres], nearest first. */
    fun near(lat: Double, lng: Double, metres: Double): List<Facility> =
        facilities.filter { distanceM(lat, lng, it.lat, it.lng) <= metres }
            .sortedBy { distanceM(lat, lng, it.lat, it.lng) }

    companion object {
        /** Null on anything unreadable — the layer simply does not appear. */
        fun parse(json: String): FacilityRegistry? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val list = root.getValue("facilities").jsonArray.mapNotNull { entry ->
                val o = entry.jsonObject
                val name = o.string("n") ?: return@mapNotNull null
                val lat = o["y"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lng = o["x"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                Facility(
                    name = name,
                    kind = if (o["k"]?.jsonPrimitive?.intOrNull == 1) {
                        FacilityKind.PARK_AND_RIDE
                    } else {
                        FacilityKind.GARAGE
                    },
                    lat = lat,
                    lng = lng,
                    address = o.string("a"),
                    url = o.string("u"),
                    rates = o["t"]?.jsonArray?.mapNotNull { r ->
                        val ro = r.jsonObject
                        val text = ro.string("r") ?: return@mapNotNull null
                        RateLine(
                            text = text,
                            days = ro["d"]?.jsonArray
                                ?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet()
                                .orEmpty(),
                        )
                    }.orEmpty(),
                    ratesUpdated = o["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                )
            }
            FacilityRegistry(list).takeIf { list.isNotEmpty() }
        }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/** Metres between two WGS84 points. Haversine; the distances here are urban. */
internal fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return 2 * r * Math.asin(Math.sqrt(a))
}
