package dev.wasil.permit.ui

import java.util.Locale

/**
 * The handful of fields a reverse-geocode result might supply, extracted from
 * `android.location.Address` by the caller — see
 * [dev.wasil.permit.parking.android.reverseGeocodeAddress]. Kept as this
 * project's own plain type rather than passing `Address` itself through,
 * because `Address`'s getters are unavailable outside a device/emulator
 * (there's no Robolectric in this project's unit tests), and the whole point
 * of [formatAddress] is to be testable without either.
 */
data class GeocodedAddress(
    val thoroughfare: String? = null,
    val subThoroughfare: String? = null,
    val locality: String? = null,
    val firstAddressLine: String? = null,
    /** Neighbourhood or district — "Nieuwmarkt", "Oud-West". */
    val subLocality: String? = null,
)

/**
 * A place name for a whole area rather than a doorstep: "Nieuwmarkt", falling
 * back to the city.
 *
 * Wasil, 2026-08-02: "dont use codes like t14b and so use their real names of
 * the areas". The bundled tariff file genuinely has no place names in it — its
 * `description` is a rate class ("Basistarief TC1 ma-zo 00-24"), not a
 * neighbourhood — so the name has to come from geocoding a point inside the
 * area. Deliberately not [formatAddress]: a street and house number names a
 * doorstep, and these regions are kilometres across.
 */
fun formatAreaName(address: GeocodedAddress): String? =
    address.subLocality?.takeIf { it.isNotBlank() }
        ?: address.locality?.takeIf { it.isNotBlank() }

/**
 * A short, human place name from a reverse-geocode result — "Damstraat 14"
 * over "Damstraat, Amsterdam" over a full postal address. Preference order:
 * street + house number, then street + city, then street alone, then just
 * the city, then whatever the geocoder's own formatted first line says (some
 * providers don't fill the individual component fields but do fill this).
 * Null when there's nothing usable at all — the caller falls back to
 * coordinates.
 */
fun formatAddress(address: GeocodedAddress): String? {
    val street = address.thoroughfare.blankToNull()
    val number = address.subThoroughfare.blankToNull()
    val locality = address.locality.blankToNull()
    return when {
        street != null && number != null -> "$street $number"
        street != null && locality != null -> "$street, $locality"
        street != null -> street
        locality != null -> locality
        else -> address.firstAddressLine.blankToNull()
    }
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * The "52.37021, 4.89516" fallback shown when there's no address at all —
 * either geocoding failed/returned nothing, or (older data) it was never
 * attempted. Five decimal places is about 1 m of precision, plenty for a
 * parking zone.
 */
fun formatCoordinates(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.5f, %.5f", lat, lng)
