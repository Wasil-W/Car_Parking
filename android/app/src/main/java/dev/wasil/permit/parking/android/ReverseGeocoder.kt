package dev.wasil.permit.parking.android

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.ui.GeocodedAddress
import dev.wasil.permit.ui.formatAddress
import dev.wasil.permit.ui.PlaceLabel
import dev.wasil.permit.ui.formatPlaceLabel
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A short place name for [point] via Android's built-in [Geocoder] — free, no
 * API key, and (per docs/superpowers/specs zone-address decision) looked up
 * once at zone-creation time rather than on every read, so it works offline
 * afterwards. Null on any failure, timeout, empty result, or when nothing
 * usable came back — see [formatAddress] for what "usable" means. The caller
 * falls back to [dev.wasil.permit.ui.formatCoordinates] in that case, same as
 * it always has; a zone with a worse name must still save.
 *
 * Never blocks the calling thread: the API 33+ path is the async callback
 * form (the blocking overload is deprecated there); older API levels use the
 * blocking call, but pushed onto [Dispatchers.IO]. Bounded by a short timeout
 * so a slow or hanging provider can't stall a zone save indefinitely.
 */
/** District and street for a point, for the map header. */
suspend fun reverseGeocodePlace(context: Context, point: GeoPoint): PlaceLabel? {
    if (!Geocoder.isPresent()) return null
    val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
        runCatching { firstAddress(Geocoder(context), point) }.getOrNull()
    } ?: return null
    return address?.let { formatPlaceLabel(it.toGeocodedAddress()) }
}

suspend fun reverseGeocodeAddress(context: Context, point: GeoPoint): String? {
    if (!Geocoder.isPresent()) return null
    val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
        runCatching { firstAddress(Geocoder(context), point) }.getOrNull()
    } ?: return null
    return address?.let { formatAddress(it.toGeocodedAddress()) }
}

private const val GEOCODE_TIMEOUT_MS = 5_000L

private suspend fun firstAddress(geocoder: Geocoder, point: GeoPoint): Address? =
    if (Build.VERSION.SDK_INT >= 33) {
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocation(point.lat, point.lng, 1) { results ->
                if (cont.isActive) cont.resume(results.firstOrNull())
            }
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(point.lat, point.lng, 1)?.firstOrNull()
        }
    }

private fun Address.toGeocodedAddress() = GeocodedAddress(
    thoroughfare = thoroughfare,
    subThoroughfare = subThoroughfare,
    locality = locality,
    firstAddressLine = if (maxAddressLineIndex >= 0) getAddressLine(0) else null,
    subLocality = subLocality,
)
