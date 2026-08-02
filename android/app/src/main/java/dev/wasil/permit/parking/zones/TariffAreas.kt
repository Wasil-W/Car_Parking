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
                val label = rateLabel(rate)
                (spec as? JsonObject)?.entries.orEmpty().mapNotNull { (range, days) ->
                    val parts = range.split('-')
                    if (parts.size != 2) return@mapNotNull null
                    val start = parseHhmm(parts[0]) ?: return@mapNotNull null
                    val end = parseHhmm(parts[1]) ?: return@mapNotNull null
                    val onDays = parseDays(days.jsonPrimitive.content)
                    if (start >= end || onDays.isEmpty()) return@mapNotNull null
                    TariffWindow(label, start, end, onDays)
                }
            }
        }
    }

    private fun rateLabel(key: String): String =
        if ("[" in key) "€${key.substringBefore("[")}/h" else "€$key/h"

    private fun tariffText(obj: JsonObject): String {
        val tarieven = obj["tarieven"] ?: return ""
        val entry = when (tarieven) {
            is JsonArray -> tarieven.firstOrNull() as? JsonObject
            is JsonObject -> tarieven
            else -> null
        } ?: return ""
        val key = entry.keys.firstOrNull() ?: return ""
        return if ("[" in key) "€${key.substringBefore("[")}/h (stepped)" else "€$key/h"
    }
}
