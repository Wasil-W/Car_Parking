package dev.wasil.permit.parking.zones

data class LatLng(val lat: Double, val lng: Double)

data class ZonePolygon(
    val outer: List<LatLng>,
    val holes: List<List<LatLng>> = emptyList(),
)

/** Even-odd ray casting; horizontal ray toward +lng. */
private fun pointInRing(p: LatLng, ring: List<LatLng>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.lat > p.lat) != (b.lat > p.lat)) {
            val lngAtLat = (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
            if (p.lng < lngAtLat) inside = !inside
        }
        j = i
    }
    return inside
}

fun pointInPolygon(p: LatLng, polygon: ZonePolygon): Boolean =
    pointInRing(p, polygon.outer) && polygon.holes.none { pointInRing(p, it) }
