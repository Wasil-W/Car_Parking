package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffAreas
import dev.wasil.permit.parking.zones.ZonePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZonesTest {
    private val home = FreeZone(52.3702, 4.8952, radiusM = 60.0, label = "Home")
    private val zoneA = FreeZone(52.3800, 4.9000, radiusM = 50.0)
    private val zoneB = FreeZone(52.3900, 4.9100, radiusM = 50.0)

    @Test
    fun `tap inside the home zone hits Home`() {
        assertEquals(ZoneRef.Home, zoneHitAt(GeoPoint(52.3702, 4.8952, 0f), home, emptyList()))
    }

    @Test
    fun `tap inside a free zone hits its index`() {
        assertEquals(
            ZoneRef.Free(1),
            zoneHitAt(GeoPoint(52.3900, 4.9100, 0f), home, listOf(zoneA, zoneB)),
        )
    }

    @Test
    fun `tap outside every zone hits nothing`() {
        assertNull(zoneHitAt(GeoPoint(10.0, 10.0, 0f), home, listOf(zoneA, zoneB)))
    }

    @Test
    fun `no home zone means a tap there hits nothing`() {
        assertNull(zoneHitAt(GeoPoint(52.3702, 4.8952, 0f), null, emptyList()))
    }

    @Test
    fun `overlapping zones — the nearest centre wins`() {
        val big = FreeZone(52.0, 4.0, radiusM = 200.0)
        val small = FreeZone(52.0009, 4.0, radiusM = 30.0) // ~100 m north of big, inside both
        val tap = GeoPoint(52.0009, 4.0, 0f) // dead centre of the small zone
        assertEquals(ZoneRef.Free(1), zoneHitAt(tap, null, listOf(big, small)))
    }

    @Test
    fun `radius clamps to the 30 to 200 metre range`() {
        assertEquals(30.0, clampZoneRadius(10.0), 0.0)
        assertEquals(200.0, clampZoneRadius(500.0), 0.0)
        assertEquals(75.0, clampZoneRadius(75.0), 0.0)
    }

    // --- mapHitAt: the one precedence rule for a tap on bare map (v0.5) ---

    /** Covers 52.36..52.38 N, 4.88..4.91 E — deliberately big enough to
     * contain the home zone, so the precedence test is a real collision. */
    private val paidArea = TariffArea(
        code = "T13B",
        name = "Basistarief TC3 ma-za 09-24",
        tariffText = "€5,37/h",
        polygons = listOf(
            ZonePolygon(
                listOf(
                    LatLng(52.3600, 4.8800),
                    LatLng(52.3600, 4.9100),
                    LatLng(52.3800, 4.9100),
                    LatLng(52.3800, 4.8800),
                ),
            ),
        ),
    )

    @Test
    fun `a tap inside both a zone and a tariff area hits the zone`() {
        val tap = GeoPoint(52.3702, 4.8952, 0f) // centre of home, inside paidArea
        assertEquals(MapHit.Zone(ZoneRef.Home), mapHitAt(tap, home, emptyList(), listOf(paidArea)))
    }

    @Test
    fun `a tap on a tariff area with no zone under it hits the tariff area`() {
        val tap = GeoPoint(52.3650, 4.8850, 0f)
        val hit = mapHitAt(tap, home, listOf(zoneA, zoneB), listOf(paidArea))
        assertEquals(paidArea, (hit as MapHit.Tariff).hit.area)
    }

    @Test
    fun `a multi-part area yields only the part the point is in`() {
        // 21 of the 29 real areas are multi-part; T13B alone is 16 disjoint
        // pieces. Highlighting the whole area lit up every piece at once.
        val far = ZonePolygon(
            listOf(
                LatLng(52.4000, 4.9500),
                LatLng(52.4000, 4.9600),
                LatLng(52.4100, 4.9600),
                LatLng(52.4100, 4.9500),
            ),
        )
        val twoPart = paidArea.copy(polygons = paidArea.polygons + far)
        val hit = tariffHitAt(GeoPoint(52.3650, 4.8850, 0f), listOf(twoPart))!!
        assertEquals(twoPart, hit.area)
        assertEquals(paidArea.polygons[0], hit.ring)
        assertTrue(hit.ring != far)
    }

    @Test
    fun `an empty tariff list is how the overlay is switched off`() {
        val tap = GeoPoint(52.3650, 4.8850, 0f) // inside paidArea, outside every zone
        assertNull(mapHitAt(tap, home, emptyList(), emptyList()))
    }

    @Test
    fun `a tap outside everything hits nothing`() {
        assertNull(mapHitAt(GeoPoint(10.0, 10.0, 0f), home, listOf(zoneA), listOf(paidArea)))
    }

    @Test
    fun `zone precedence still uses the nearest centre inside mapHitAt`() {
        val big = FreeZone(52.0, 4.0, radiusM = 200.0)
        val small = FreeZone(52.0009, 4.0, radiusM = 30.0)
        assertEquals(
            MapHit.Zone(ZoneRef.Free(1)),
            mapHitAt(GeoPoint(52.0009, 4.0, 0f), null, listOf(big, small), emptyList()),
        )
    }

    @Test
    fun `tariffAreaAt finds the containing area`() {
        assertEquals(paidArea, tariffAreaAt(GeoPoint(52.3650, 4.8850, 0f), listOf(paidArea)))
        assertNull(tariffAreaAt(GeoPoint(10.0, 10.0, 0f), listOf(paidArea)))
    }

    @Test
    fun `tariff summary leads with the rate then the description`() {
        assertEquals("€5,37/h · Basistarief TC3 ma-za 09-24", tariffSummary(paidArea))
    }

    @Test
    fun `tariff summary omits a missing rate rather than showing a stray separator`() {
        assertEquals("Basistarief TC3 ma-za 09-24", tariffSummary(paidArea.copy(tariffText = "")))
    }

    @Test
    fun `the compact form drops the rate-class prefix and keeps the schedule`() {
        assertEquals("€5,37/h · ma-za 09-24", tariffShort(paidArea))
        assertEquals("ma-za 09-24", tariffHours(paidArea))
    }

    @Test
    fun `an area with no schedule in its name keeps the whole name`() {
        val odd = paidArea.copy(name = "Tarief 4 start tarief 7")
        assertEquals("Tarief 4 start tarief 7", tariffHours(odd))
    }

    @Test
    fun `every bundled area produces a compact form no longer than its summary`() {
        val areas = TariffAreas.parse(java.io.File("src/main/assets/amsterdam_tarieven.json").readText())
        assertTrue(areas.all { tariffShort(it).length <= tariffSummary(it).length })
        assertTrue(areas.all { tariffShort(it).isNotBlank() })
    }

    @Test
    fun `the real bundled asset still parses to the expected shape`() {
        // Guards a bad asset swap: the overlay and the claim decision both read
        // this file, and an empty parse silently disables both.
        val areas = TariffAreas.parse(java.io.File("src/main/assets/amsterdam_tarieven.json").readText())
        assertEquals(29, areas.size)
        assertTrue(areas.all { area -> area.polygons.all { it.outer.size >= 3 } })
    }
}
