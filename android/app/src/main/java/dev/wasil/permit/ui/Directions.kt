package dev.wasil.permit.ui

import dev.wasil.permit.parking.GeoPoint
import java.util.Locale

/**
 * `google.navigation` walking-mode URI — opens turn-by-turn walking
 * directions directly if Google Maps is installed. `Locale.US` is explicit:
 * the device's default locale (e.g. Dutch, "52,370216") would otherwise put a
 * comma where the URI needs a decimal point, corrupting it.
 */
fun walkingDirectionsUri(point: GeoPoint): String =
    String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", point.lat, point.lng)

/** `geo:` URI — understood by any maps app, the fallback for when Google Maps itself is absent. */
fun geoFallbackUri(point: GeoPoint, label: String = "Parked car"): String =
    String.format(
        Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
        point.lat, point.lng, point.lat, point.lng, label,
    )
