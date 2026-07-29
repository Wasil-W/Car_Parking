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
        return TariffArea(code, name, tariffText(obj), polygons)
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
