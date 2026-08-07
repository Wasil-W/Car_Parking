package dev.wasil.permit.parking.android

import android.content.Context
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.ui.PlaceLabel
import dev.wasil.permit.ui.formatPlaceLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a place is called, asked of Amsterdam's own data first and the geocoder
 * only when that has nothing.
 *
 * Every screen that names a spot goes through here — the map header, the Now
 * screen, and the history record written at park time — so the three cannot
 * describe one place differently. That invariant already existed; this only
 * changes what sits behind it.
 *
 * Inside Amsterdam the answer is a stadsdeel over a buurt ("Noord" /
 * "Molenwijk"), read from the bundled registry: offline, instant, no rate
 * limit, and the names Wasil actually asked for. Outside it — Utrecht, a drive
 * to Germany — the bundled layers hold nothing and [reverseGeocodePlace] takes
 * over exactly as before. Null from both means no name, which the callers all
 * already handle.
 */
suspend fun placeLabelAt(context: Context, point: GeoPoint): PlaceLabel? {
    val registry = (context.applicationContext as? PermitApp)?.zoneRegistry
    val bundled = registry?.let {
        // Ray casting over 35,000 vertices: fast, but not main-thread work, and
        // the first call is also where the asset is parsed.
        withContext(Dispatchers.Default) { it.resolve(LatLng(point.lat, point.lng)) }
    }
    return bundled?.let { formatPlaceLabel(it) } ?: reverseGeocodePlace(context, point)
}
