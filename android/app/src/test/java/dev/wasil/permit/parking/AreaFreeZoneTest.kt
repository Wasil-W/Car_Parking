package dev.wasil.permit.parking

import dev.wasil.permit.data.api.PermitJson
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.zones.ZoneResolver
import dev.wasil.permit.ui.NO_NEIGHBOURHOOD_HERE
import dev.wasil.permit.ui.ZONE_AREA_CONSEQUENCE
import dev.wasil.permit.ui.zoneAreaOffer
import dev.wasil.permit.ui.zoneAreaSizeLine
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A free zone that is a neighbourhood rather than a circle.
 *
 * Wasil, 2026-08-08, looking at the council's map of Molenwijk: *"do you see the
 * molenwijk. We could do that we can put those for the free zones, with
 * outline."* The complaint behind it was that home zones and free zones felt
 * identical — same circle, same radius slider — and that sizing an area by
 * dragging over a map is not precise. An area has published edges, so neither
 * problem survives.
 */
class AreaFreeZoneTest {

    /** A square roughly 1.1 km on a side, around 52.40 N. */
    private val square = listOf(
        ZonePolygon(
            outer = listOf(
                LatLng(52.400, 4.900), LatLng(52.400, 4.916),
                LatLng(52.410, 4.916), LatLng(52.410, 4.900),
            ),
        ),
    )
    private val shapes: (String) -> List<ZonePolygon>? =
        { name -> square.takeIf { name == "Molenwijk" } }

    private val inside = GeoPoint(52.405, 4.908, 5f)
    private val outside = GeoPoint(52.430, 4.908, 5f)
    private val molenwijk = FreeZone(52.405, 4.908, radiusM = 60.0, label = "Molenwijk", buurt = "Molenwijk")

    @Test
    fun `an area zone covers its whole neighbourhood, not a sixty-metre circle`() {
        // A kilometre from the tapped point, and still inside the boundary. The
        // circle this replaced would have stopped 60 m out.
        val farCorner = GeoPoint(52.4095, 4.9155, 5f)
        assertTrue(isInFreeZone(farCorner, listOf(molenwijk), shapes))
        assertTrue(distanceMeters(farCorner, GeoPoint(molenwijk.lat, molenwijk.lng, 0f)) > 700)
    }

    @Test
    fun `and stops at the boundary rather than at a radius`() {
        assertFalse(isInFreeZone(outside, listOf(molenwijk), shapes))
    }

    /**
     * The direction this is allowed to be wrong in.
     *
     * A zone naming a neighbourhood the app cannot find is not evidence that
     * parking there is free. Matching anyway would say "nothing owed" on the
     * strength of a missing asset, which ends with a car unpermitted on a paid
     * street; not matching just lets the tariff polygons decide, exactly as they
     * did before free zones existed.
     */
    @Test
    fun `a neighbourhood whose boundary is missing does not count as free`() {
        assertFalse(isInFreeZone(inside, listOf(molenwijk), areaShape = { null }))
    }

    // --- free zones are area-backed only ---

    /**
     * A free zone with no neighbourhood does not match anything, ever.
     *
     * There is no circle fallback: one shape, one rule. Wasil, on the rest of
     * the country, 2026-08-08: *"Utrecht will get its own update hahaha."* The
     * store drops these on read, so a zone in this state should not exist at
     * all — and if one does, the safe reading of it is "not evidence that
     * parking here is free" rather than a 60 m circle nobody chose.
     */
    @Test
    fun `a free zone with no neighbourhood matches nothing`() {
        val circle = FreeZone(52.405, 4.908, radiusM = 60.0, label = "Mum's street")
        assertFalse(isInFreeZone(GeoPoint(52.4052, 4.908, 5f), listOf(circle), shapes))
        assertFalse(isInFreeZone(outside, listOf(circle), shapes))
    }

    /**
     * A zone written by v0.6.7 has no `buurt` key, so it decodes to a circle —
     * and circles are no longer free zones.
     *
     * Dropping them is deliberate and was cleared with Wasil: *"Dont really need
     * them as i only have one for my home zone, 1sec fix."* His one circle is
     * the **home** zone, which is stored separately and is untouched. Filtering
     * on read rather than leaving them listed is the important half: a row in
     * "Your zones" that the claim decision ignores would be worse than no row.
     */
    @Test
    fun `a circle stored before v0_6_8 decodes, and is not a free zone`() {
        val old = """[{"lat":52.405,"lng":4.908,"radiusM":80.0,"label":"Mum's street"}]"""
        val zones = PermitJson.decodeFromString<List<FreeZone>>(old)
        assertNull(zones.single().buurt)
        assertFalse(zones.single().isArea)
        assertFalse(isInFreeZone(GeoPoint(52.4054, 4.908, 5f), zones, shapes))
    }

    @Test
    fun `an area zone survives a round trip through the store's format`() {
        val encoded = PermitJson.encodeToString(listOf(molenwijk))
        assertEquals(listOf(molenwijk), PermitJson.decodeFromString<List<FreeZone>>(encoded))
    }

    // --- the claim decision, which is the only one that costs money ---

    @Test
    fun `parking inside a marked neighbourhood is settled as free, not claimed`() {
        // The whole square is inside a paid tariff area, so without the free
        // zone this point would take the permit.
        val paid = TariffArea(
            "T11V", "Noord", "€3,01/h",
            listOf(ZonePolygon(outer = listOf(
                LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.0),
            ))),
        )
        val resolver = ZoneResolver(null, listOf(molenwijk), listOf(paid), shapes)
        assertEquals(ZoneInfo.ManualFree("Molenwijk"), resolver.resolve(inside))
        assertTrue(resolver.resolve(outside) is ZoneInfo.Paid)
    }

    @Test
    fun `the home zone is still a circle, and that is the distinction`() {
        // Home is a point you own; a free zone is an area you know about. The
        // resolver must never treat a home zone as an area even if one were
        // somehow stored with a buurt on it.
        val home = FreeZone(52.405, 4.908, radiusM = 60.0, label = "Home", buurt = "Molenwijk")
        val resolver = ZoneResolver(home, emptyList(), emptyList(), shapes)
        assertEquals(ZoneInfo.Home, resolver.resolve(GeoPoint(52.4052, 4.908, 5f)))
        // 900 m away: well outside the radius, well inside the buurt. It must
        // fall through to the streets rather than still be called home.
        assertEquals(ZoneInfo.FreeStreet, resolver.resolve(GeoPoint(52.4095, 4.9155, 5f)))
    }

    // --- how big a thing is being marked free ---

    @Test
    fun `the size of an area is worked out in square kilometres`() {
        // 0.010 deg lat x 0.016 deg lng at 52.4 N: 1.113 km x 1.088 km.
        assertEquals(1.21, areaSqKm(square), 0.02)
    }

    @Test
    fun `holes are subtracted rather than counted`() {
        val withHole = listOf(
            square.single().copy(
                holes = listOf(
                    listOf(
                        LatLng(52.402, 4.902), LatLng(52.402, 4.910),
                        LatLng(52.408, 4.910), LatLng(52.408, 4.902),
                    ),
                ),
            ),
        )
        assertTrue(areaSqKm(withHole) < areaSqKm(square))
    }

    /**
     * The digits and the unit are asserted; the decimal separator deliberately
     * is not.
     *
     * [areaSizeText] formats in the phone's own locale, so a Dutch phone reads
     * "1,2 km²" — which is right, and is the same call `parseZoneRadius` already
     * makes in the other direction ("a comma is accepted because this is a Dutch
     * app"). Hard-coding a dot here made the test pass in one country and fail in
     * another, which is a bug in the test rather than in the copy.
     */
    @Test
    fun `sizes are shown in a unit a person can picture`() {
        assertTrue(areaSizeText(1.21).matches(Regex("""1[.,]2 km²""")))
        assertTrue(areaSizeText(0.549).matches(Regex("""0[.,]55 km²""")))
        // Below a tenth of a square kilometre, hectares beat "0,08 km²" —
        // a person can picture eight hectares and cannot picture that.
        assertEquals("8 ha", areaSizeText(0.08))
    }

    // --- how big a thing is being marked free, said where it will be read ---

    /**
     * The size is a line of its own at full strength, not a parenthesis.
     *
     * Marking a buurt free switches off permit claiming across all of it, and
     * the failure is silent — what goes wrong is that nothing happens.
     * Confirmed as a requirement by Wasil: *"last point was good that some
     * buurten are large and that a small indicator is good for it."*
     */
    @Test
    fun `the offer states the size and what it will do`() {
        assertEquals("Mark Molenwijk as free?", zoneAreaOffer("Molenwijk"))
        assertTrue(zoneAreaSizeLine("0.9 km²").startsWith("0.9 km²"))
        assertTrue(ZONE_AREA_CONSEQUENCE.contains("never be claimed"))
    }

    /** No size is no reason to invent one — the offer stands without it. */
    @Test
    fun `an unknown size still names what is being marked`() {
        assertEquals("The whole neighbourhood", zoneAreaSizeLine(null))
    }

    /**
     * Scope is containment, never a name.
     *
     * Weesp is in the municipality and in this data while not taking the
     * "Amsterdam-" prefix, so anything testing coverage by string would be wrong
     * exactly there. The refusal copy therefore talks about *this spot*.
     */
    @Test
    fun `the refusal is about the spot rather than about a city`() {
        assertFalse(NO_NEIGHBOURHOOD_HERE.contains("Amsterdam"))
        assertTrue(NO_NEIGHBOURHOOD_HERE.contains("this spot"))
    }
}
