package dev.wasil.permit.parking.zones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TariffArea(
    val code: String,
    val name: String,
    val tariffText: String,
    val polygons: List<ZonePolygon>,
    /** The charging windows behind [tariffText], for answering "right now". */
    val windows: List<TariffWindow> = emptyList(),
)

/** Parser for Amsterdam's tarieven.json (maps.amsterdam.nl parking-rate areas, CC-BY). */
object TariffAreas {

    fun parse(json: String): List<TariffArea> = runCatching {
        Json.parseToJsonElement(json).jsonObject.mapNotNull { (code, value) ->
            runCatching { parseArea(code, value.jsonObject) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseArea(code: String, obj: JsonObject): TariffArea {
        val name = obj["description"]?.jsonObject?.values?.firstOrNull()
            ?.jsonPrimitive?.content ?: code
        val location = obj.getValue("location").jsonObject
        val coords = location.getValue("coordinates").jsonArray
        val polygons = when (location.getValue("type").jsonPrimitive.content) {
            "Polygon" -> listOf(parsePolygon(coords))
            "MultiPolygon" -> coords.map { parsePolygon(it.jsonArray) }
            else -> error("unknown geometry")
        }
        require(polygons.isNotEmpty() && polygons.all { it.outer.size >= 3 })
        return TariffArea(code, name, tariffText(obj), polygons, windows(obj))
    }

    /** GeoJSON: first ring is the outer boundary, the rest are holes; [lng, lat] order. */
    private fun parsePolygon(rings: JsonArray): ZonePolygon {
        val parsed = rings.map { ring ->
            ring.jsonArray.mapNotNull { pos ->
                val arr = pos.jsonArray
                val lng = arr[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val lat = arr[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                LatLng(lat, lng)
            }
        }
        return ZonePolygon(outer = parsed.first(), holes = parsed.drop(1))
    }

    /**
     * Every `{rate: {"900-1900": "ma-vrij"}}` pair flattened into windows.
     * Anything unparseable is dropped rather than guessed at — a missing window
     * shows as "free", which is the honest answer when we cannot read the rule.
     */
    private fun windows(obj: JsonObject): List<TariffWindow> {
        val tarieven = obj["tarieven"] ?: return emptyList()
        val blocks = when (tarieven) {
            is JsonArray -> tarieven.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(tarieven)
            else -> return emptyList()
        }
        return blocks.flatMap { block ->
            block.entries.flatMap { (rate, spec) ->
                val priced = rateFor(rate)
                (spec as? JsonObject)?.entries.orEmpty().mapNotNull { (range, days) ->
                    val parts = range.split('-')
                    if (parts.size != 2) return@mapNotNull null
                    val start = parseHhmm(parts[0]) ?: return@mapNotNull null
                    val end = parseHhmm(parts[1]) ?: return@mapNotNull null
                    val onDays = parseDays(days.jsonPrimitive.content)
                    if (start >= end || onDays.isEmpty()) return@mapNotNull null
                    TariffWindow(priced.label, start, end, onDays, priced.stepNote)
                }
            }
        }
    }

    /** A rate as it should be shown, and what it does not fit on one line. */
    internal data class PricedRate(val label: String, val stepNote: String?)

    /**
     * `"1,72"`, or `"1,72[0-180];4,19[180-999999]"`, read properly.
     *
     * **Everything after the bracket used to be discarded.** Three of the 29
     * areas price by how long you stay, and the app showed the opening tier as
     * though it were the whole story:
     *
     * - `T17_UB01` is `0,10[0-180];1,72[180-…]`, shown as **€0,10/h**. Nine
     *   hours reads as ninety cents against a real €10,60 or so.
     * - `T14_UA01` is `1,72[0-180];4,19[180-…]`, shown as **€1,72/h**, which is
     *   two and a half times under after the third hour.
     * - `T17F` is `1,72[…];1,72[…]` — two tiers at one price, so genuinely flat.
     *   It gets no note, because a note that says nothing changes is noise.
     *
     * The label becomes **"from €1,72/h"** when tiers differ. That is one word,
     * it fits where the old string fitted, and it stops the chip asserting a
     * flat price it cannot honour — which matters more than the exact wording,
     * because understating a rate is the direction that costs money.
     */
    internal fun rateFor(key: String): PricedRate {
        if ("[" !in key) return PricedRate("€$key/h", null)
        val tiers = key.split(';').mapNotNull { tier ->
            val amount = tier.substringBefore("[").trim().ifEmpty { return@mapNotNull null }
            val from = tier.substringAfter("[", "").substringBefore("-").toIntOrNull()
            amount to (from ?: 0)
        }
        val opening = tiers.firstOrNull()?.first ?: key.substringBefore("[")
        val distinct = tiers.map { it.first }.distinct()
        if (distinct.size < 2) return PricedRate("€$opening/h", null)

        val second = tiers[1]
        return PricedRate(
            label = "from €$opening/h",
            stepNote = "First ${spanText(second.second)} €$opening/h, then €${second.first}/h",
        )
    }

    /** `180` becomes "3 h"; `90` becomes "90 min". The boundaries are whole hours today. */
    private fun spanText(minutes: Int): String =
        if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

    private fun tariffText(obj: JsonObject): String {
        val tarieven = obj["tarieven"] ?: return ""
        val entry = when (tarieven) {
            is JsonArray -> tarieven.firstOrNull() as? JsonObject
            is JsonObject -> tarieven
            else -> null
        } ?: return ""
        val key = entry.keys.firstOrNull() ?: return ""
        val priced = rateFor(key)
        // The note rather than the old bare "(stepped)": a reader who is told a
        // price changes and not what it changes to has been given a worry
        // instead of an answer.
        return priced.stepNote?.let { "${priced.label} · $it" } ?: priced.label
    }
}
