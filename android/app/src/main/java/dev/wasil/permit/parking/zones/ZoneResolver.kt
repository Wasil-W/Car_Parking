package dev.wasil.permit.parking.zones

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.containsPoint
import dev.wasil.permit.parking.distanceMeters

sealed interface ZoneInfo {
    data object Home : ZoneInfo
    data class ManualFree(val label: String) : ZoneInfo
    /** area == null: tariff data unavailable — assume paid (claiming is the safe bias). */
    data class Paid(val area: TariffArea?) : ZoneInfo
    data object FreeStreet : ZoneInfo
}

class ZoneResolver(
    private val home: FreeZone?,
    private val manualZones: List<FreeZone>,
    private val areas: List<TariffArea>?,
    /**
     * Where an area-backed free zone gets its boundary — Amsterdam's own buurt
     * layer, bundled since v0.6.6.
     *
     * Defaulted to null, and a null one is not a hole. A free zone that names a
     * neighbourhood the registry cannot supply simply does not match, so the
     * tariff polygons decide as they always did. The alternative — treating an
     * uncheckable zone as free — would say "nothing owed here" on the strength
     * of a missing asset, which is the direction that costs a fine.
     */
    private val areaShape: (String) -> List<ZonePolygon>? = { null },
) {
    fun resolve(point: GeoPoint): ZoneInfo {
        // The home zone is a circle and stays one. It is a point you own — your
        // street, your house — deliberately 30–200 m, and a locked decision in
        // BACKLOG.md. A free zone is an area you know about, and areas have
        // published edges; that is the whole distinction v0.6.8 draws between
        // the two, and it is why only one of them lost its slider.
        if (home != null && inCircle(point, home)) return ZoneInfo.Home
        manualZones.firstOrNull { containsPoint(it, point, areaShape) }
            ?.let { return ZoneInfo.ManualFree(it.label) }
        val loaded = areas ?: return ZoneInfo.Paid(null)
        val p = LatLng(point.lat, point.lng)
        loaded.firstOrNull { area -> area.polygons.any { pointInPolygon(p, it) } }
            ?.let { return ZoneInfo.Paid(it) }
        return ZoneInfo.FreeStreet
    }

    private fun inCircle(point: GeoPoint, zone: FreeZone): Boolean =
        distanceMeters(point, GeoPoint(zone.lat, zone.lng, 0f)) <= zone.radiusM
}
