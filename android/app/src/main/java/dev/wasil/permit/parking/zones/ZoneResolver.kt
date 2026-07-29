package dev.wasil.permit.parking.zones

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
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
) {
    fun resolve(point: GeoPoint): ZoneInfo {
        if (home != null && inCircle(point, home)) return ZoneInfo.Home
        manualZones.firstOrNull { inCircle(point, it) }
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
