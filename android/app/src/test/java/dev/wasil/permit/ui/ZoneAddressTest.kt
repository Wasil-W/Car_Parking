package dev.wasil.permit.ui

import dev.wasil.permit.parking.zones.ZonePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoneAddressTest {

    @Test fun `street and house number - short form preferred over everything else`() {
        val address = GeocodedAddress(
            thoroughfare = "Damstraat", subThoroughfare = "14",
            locality = "Amsterdam", firstAddressLine = "Damstraat 14, 1012 Amsterdam",
        )
        assertEquals("Damstraat 14", formatAddress(address))
    }

    @Test fun `street and locality when there's no house number`() {
        val address = GeocodedAddress(thoroughfare = "Damstraat", locality = "Amsterdam")
        assertEquals("Damstraat, Amsterdam", formatAddress(address))
    }

    @Test fun `street alone when neither number nor locality is known`() {
        val address = GeocodedAddress(thoroughfare = "Damstraat")
        assertEquals("Damstraat", formatAddress(address))
    }

    @Test fun `falls back to the geocoder's own formatted line when no street is known`() {
        val address = GeocodedAddress(firstAddressLine = "1012 JS Amsterdam, Netherlands")
        assertEquals("1012 JS Amsterdam, Netherlands", formatAddress(address))
    }

    @Test fun `blank fields are treated the same as missing ones`() {
        val address = GeocodedAddress(thoroughfare = "  ", subThoroughfare = "14", locality = "Amsterdam")
        assertEquals("Amsterdam", formatAddress(address))
    }

    @Test fun `nothing usable at all returns null so the caller can fall back to coordinates`() {
        assertNull(formatAddress(GeocodedAddress()))
        assertNull(formatAddress(GeocodedAddress(thoroughfare = "  ", firstAddressLine = "   ")))
    }

    @Test fun `house number alone without a street is not used - a number means nothing on its own`() {
        val address = GeocodedAddress(subThoroughfare = "14")
        assertNull(formatAddress(address))
    }

    @Test fun `coordinates format to five decimal places, comma-separated`() {
        assertEquals("52.37021, 4.89516", formatCoordinates(52.370210, 4.895160))
    }

    @Test fun `coordinates keep five decimal places even with more precision available`() {
        assertEquals("52.37023, 4.89518", formatCoordinates(52.3702345, 4.8951789))
    }

    // --- two-level place label (v0.5.4) ---

    @Test
    fun `the district leads and the street sits under it`() {
        val label = formatPlaceLabel(
            GeocodedAddress(subLocality = "Amsterdam-Noord", thoroughfare = "Molenwijkpad"),
        )!!
        assertEquals("Amsterdam-Noord", label.district)
        assertEquals("Molenwijkpad", label.detail)
    }

    @Test
    fun `the city stands in when there is no district`() {
        val label = formatPlaceLabel(GeocodedAddress(locality = "Amsterdam"))!!
        assertEquals("Amsterdam", label.district)
        assertNull(label.detail)
    }

    @Test
    fun `a street identical to the district is not repeated underneath it`() {
        val label = formatPlaceLabel(
            GeocodedAddress(subLocality = "Molenwijk", thoroughfare = "Molenwijk"),
        )!!
        assertEquals("Molenwijk", label.district)
        assertNull(label.detail)
    }

    @Test
    fun `nothing usable gives no label at all, so the caller can fall back`() {
        assertNull(formatPlaceLabel(GeocodedAddress(thoroughfare = "Damstraat")))
    }

    // --- the same two levels, from the bundled registry (v0.6 zone registry) ---

    @Test
    fun `the stadsdeel leads and the neighbourhood sits under it`() {
        val label = formatPlaceLabel(
            ZonePlace(zoneCode = "AN05C", zoneName = "Noord 5c NDSM",
                neighbourhood = "NDSM terrein", district = "Noord"),
        )!!
        assertEquals("Noord", label.district)
        assertEquals("NDSM terrein", label.detail)
    }

    @Test
    fun `a permit zone with no neighbourhood over it still names the stadsdeel`() {
        val label = formatPlaceLabel(
            ZonePlace("CE01", "Centrum 1", neighbourhood = null, district = null),
        )
        assertNull(label)
    }

    @Test
    fun `a neighbourhood with no stadsdeel leads on its own`() {
        val label = formatPlaceLabel(ZonePlace(null, null, "Molenwijk", null))!!
        assertEquals("Molenwijk", label.district)
        assertNull(label.detail)
    }

    @Test
    fun `a neighbourhood named after its stadsdeel is not repeated underneath it`() {
        val label = formatPlaceLabel(ZonePlace(null, null, "Weesp", "Weesp"))!!
        assertEquals("Weesp", label.district)
        assertNull(label.detail)
    }
}
