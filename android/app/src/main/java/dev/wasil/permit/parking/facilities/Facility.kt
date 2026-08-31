package dev.wasil.permit.parking.facilities

import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
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
    fun nearest(lat: Double, lng: Double, withinMetres: Double = Double.MAX_VALUE): Facility? {
        val from = GeoPoint(lat, lng, 0f)
        return facilities
            .minByOrNull { distanceMeters(from, it.point()) }
            ?.takeIf { distanceMeters(from, it.point()) <= withinMetres }
    }

    /**
     * Facilities within [metres], nearest first.
     *
     * Each facility is measured exactly once. The obvious spelling —
     * `filter { d(it) <= m }.sortedBy { d(it) }` — measures every entry in the
     * filter and then re-measures the survivors on every comparison, because
     * `sortedBy` evaluates its selector per comparison rather than once per
     * element. Harmless at 37 entries and not harmless if this is ever called
     * per frame from a map update, which is the direction this data is heading.
     */
    fun near(lat: Double, lng: Double, metres: Double): List<Facility> {
        val from = GeoPoint(lat, lng, 0f)
        return facilities
            .map { it to distanceMeters(from, it.point()) }
            .filter { (_, d) -> d <= metres }
            .sortedBy { (_, d) -> d }
            .map { (facility, _) -> facility }
    }

    companion object {
        /** Null on anything unreadable — the layer simply does not appear. */
        fun parse(json: String): FacilityRegistry? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val list = root.getValue("facilities").jsonArray.mapNotNull { entry ->
                val o = entry.jsonObject
                val name = o.string("n") ?: return@mapNotNull null
                val lat = o["y"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lng = o["x"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                if (!plausiblyAmsterdam(lat, lng)) return@mapNotNull null
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
                        val dayList = ro["d"]?.jsonArray
                        val days = dayList
                            ?.mapNotNull { it.jsonPrimitive.intOrNull }
                            ?.filter { it in 1..7 }
                            ?.toSet()
                            .orEmpty()
                        // A `d` that was present but yielded nothing usable is
                        // bad data, and the line is dropped rather than kept.
                        // Keeping it would read as `days.isEmpty()`, which this
                        // type defines as **every day** — so a corrupt weekday
                        // would silently widen a price from five days to seven.
                        // Losing one published line beats broadcasting a wider
                        // claim than the operator made.
                        if (dayList != null && dayList.isNotEmpty() && days.isEmpty()) {
                            return@mapNotNull null
                        }
                        RateLine(text = text, days = days)
                    }.orEmpty(),
                    ratesUpdated = o["ts"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                )
            }
            FacilityRegistry(list).takeIf { list.isNotEmpty() }
        }.getOrNull()

        /**
         * A data-integrity check on the bundled asset, **not** a scope test.
         *
         * The distinction matters and this repo has a rule about it: *coverage
         * is containment, never a name* — whether a spot is in scope is
         * [dev.wasil.permit.parking.zones.ZoneRegistry] finding a match for a
         * point, with no latitude ranges anywhere. Nothing here is ever asked
         * about the user's position. This box is only asked whether a line in
         * a file the app ships is a coordinate at all.
         *
         * It exists because the class KDoc above makes a promise — that the
         * facilities which ship are the ones the council itself positioned —
         * and until now nothing enforced it. 28 facilities were excluded from
         * this release precisely because their positions could not be trusted,
         * while the parser would have accepted a swapped `lat`/`lng` and drawn
         * every plate in the Indian Ocean. A pin is the most confident sentence
         * this app writes, so the guarantee belongs in the code and not only in
         * a test over today's data.
         *
         * Generous on purpose: the municipality reaches Weesp in the south-east
         * and Westpoort in the west, and the box has room around both. A real
         * facility rejected here would be a bug in the box, so it is drawn to
         * catch corruption rather than to be tight.
         */
        private fun plausiblyAmsterdam(lat: Double, lng: Double): Boolean =
            lat.isFinite() && lng.isFinite() && lat in 52.20..52.50 && lng in 4.65..5.15

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/**
 * This facility as a [GeoPoint], so distances go through the app's one
 * haversine ([distanceMeters], `ParkSignals.kt`) rather than a second copy.
 *
 * There *was* a second copy here until v0.7.6 — same earth radius, same
 * formula, written because this class holds bare doubles rather than points.
 * Two haversines in one module drift apart the first time one is corrected, and
 * the existing one is the better of the two anyway: it uses `atan2(√h, √(1-h))`
 * where the duplicate used `asin(√a)`, which loses precision near antipodal
 * points. Irrelevant at urban distances, and exactly the kind of difference
 * nobody would notice had diverged.
 *
 * `accuracyM` is 0 because it is not part of the distance; [GeoPoint] simply
 * has no two-argument constructor.
 */
private fun Facility.point(): GeoPoint = GeoPoint(lat, lng, 0f)
