package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffNow
import dev.wasil.permit.parking.zones.TariffAreas
import dev.wasil.permit.parking.zones.ZonePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZonesTest {
    private val home = FreeZone(52.3702, 4.8952, radiusM = 60.0, label = "Home")
    // Free zones are neighbourhoods since v0.6.8. Each fixture gets a square
    // boundary standing in for the buurt polygon the app would look up.
    private val zoneA = FreeZone(52.3800, 4.9000, radiusM = 50.0, buurt = "A")
    private val zoneB = FreeZone(52.3900, 4.9100, radiusM = 50.0, buurt = "B")
    private val shapes: (String) -> List<ZonePolygon>? = { name ->
        when (name) {
            "A" -> squareAround(52.3800, 4.9000)
            "B" -> squareAround(52.3900, 4.9100)
            "big" -> squareAround(52.0, 4.0, halfLat = 0.0020)
            "small" -> squareAround(52.0009, 4.0, halfLat = 0.0004)
            else -> null
        }
    }

    /** A square of [halfLat] degrees either side, widened for longitude at 52 N. */
    private fun squareAround(lat: Double, lng: Double, halfLat: Double = 0.00045) =
        listOf(ZonePolygon(outer = listOf(
            LatLng(lat - halfLat, lng - halfLat * 1.63), LatLng(lat - halfLat, lng + halfLat * 1.63),
            LatLng(lat + halfLat, lng + halfLat * 1.63), LatLng(lat + halfLat, lng - halfLat * 1.63),
        )))

    @Test
    fun `tap inside the home zone hits Home`() {
        assertEquals(ZoneRef.Home, zoneHitAt(GeoPoint(52.3702, 4.8952, 0f), home, emptyList()))
    }

    @Test
    fun `tap inside a free zone hits its index`() {
        assertEquals(
            ZoneRef.Free(1),
            zoneHitAt(GeoPoint(52.3900, 4.9100, 0f), home, listOf(zoneA, zoneB), shapes),
        )
    }

    @Test
    fun `tap outside every zone hits nothing`() {
        assertNull(zoneHitAt(GeoPoint(10.0, 10.0, 0f), home, listOf(zoneA, zoneB), shapes))
    }

    @Test
    fun `no home zone means a tap there hits nothing`() {
        assertNull(zoneHitAt(GeoPoint(52.3702, 4.8952, 0f), null, emptyList()))
    }

    @Test
    fun `overlapping zones — the nearest centre wins`() {
        val big = FreeZone(52.0, 4.0, radiusM = 200.0, buurt = "big")
        // ~100 m north of big, inside both.
        val small = FreeZone(52.0009, 4.0, radiusM = 30.0, buurt = "small")
        val tap = GeoPoint(52.0009, 4.0, 0f) // dead centre of the small zone
        assertEquals(ZoneRef.Free(1), zoneHitAt(tap, null, listOf(big, small), shapes))
    }

    @Test
    fun `radius clamps to the 30 to 200 metre range`() {
        assertEquals(30.0, clampZoneRadius(10.0), 0.0)
        assertEquals(200.0, clampZoneRadius(500.0), 0.0)
        assertEquals(75.0, clampZoneRadius(75.0), 0.0)
    }

    // --- sizing a zone by number rather than by drag (v0.6.8) ---

    @Test
    fun `stepping moves five metres at a time`() {
        assertEquals(65.0, stepZoneRadius(60.0, 1), 0.0)
        assertEquals(55.0, stepZoneRadius(60.0, -1), 0.0)
    }

    @Test
    fun `a dragged value is snapped onto the step grid before it is stepped`() {
        // Dragging leaves 63.4 m; one press of + should read 65, not 68.4.
        assertEquals(65.0, stepZoneRadius(63.4, 1), 0.0)
        assertEquals(60.0, stepZoneRadius(63.4, -1), 0.0)
    }

    @Test
    fun `stepping past either end stops rather than storing an unshowable number`() {
        assertEquals(200.0, stepZoneRadius(198.0, 1), 0.0)
        assertEquals(30.0, stepZoneRadius(31.0, -1), 0.0)
    }

    @Test
    fun `a typed radius is read as metres`() {
        assertEquals(80.0, parseZoneRadius("80")!!, 0.0)
        assertEquals(80.0, parseZoneRadius(" 80 m ")!!, 0.0)
        assertEquals(80.5, parseZoneRadius("80,5")!!, 0.0)
    }

    @Test
    fun `a half-typed radius is not a number yet`() {
        assertNull(parseZoneRadius(""))
        assertNull(parseZoneRadius("m"))
        assertNull(parseZoneRadius("-"))
        assertNull(parseZoneRadius("0"))
    }

    @Test
    fun `typing does not clamp, because clamping mid-keystroke rewrites what you typed`() {
        // "3" on the way to "30" must stay 3 here. clampZoneRadius is applied
        // once, on confirm — not on every character.
        assertEquals(3.0, parseZoneRadius("3")!!, 0.0)
        assertEquals(900.0, parseZoneRadius("900")!!, 0.0)
    }

    @Test
    fun `the field shows whole metres, with no unit and no locale surprises`() {
        assertEquals("60", radiusFieldText(60.0))
        assertEquals("63", radiusFieldText(63.4))
        assertEquals("64", radiusFieldText(63.5))
    }

    // --- the list of zones you have (v0.6.8) ---

    @Test
    fun `home comes first and the free zones keep their store order`() {
        // The index inside ZoneRef.Free addresses the store directly, so a list
        // that sorted itself would rename or delete the wrong zone.
        val entries = zoneEntries(home, listOf(zoneB, zoneA))
        assertEquals(listOf(ZoneRef.Home, ZoneRef.Free(0), ZoneRef.Free(1)), entries.map { it.ref })
        assertEquals(zoneB, entries[1].zone)
    }

    @Test
    fun `with no home zone the list is the free zones alone`() {
        assertEquals(listOf(ZoneRef.Free(0)), zoneEntries(null, listOf(zoneA)).map { it.ref })
        assertTrue(zoneEntries(null, emptyList()).isEmpty())
    }

    @Test
    fun `a nameless zone is listed by its coordinates rather than as a blank row`() {
        assertEquals("52.38000, 4.90000", zoneEntries(null, listOf(zoneA)).single().label)
    }

    @Test
    fun `a zone's radius travels with its row`() {
        assertEquals(50.0, zoneEntries(null, listOf(zoneA)).single().radiusM, 0.0)
    }

    @Test
    fun `the menu counts the zones, and says the bare noun when there are none`() {
        assertEquals("Your zones", zoneListMenuLabel(0))
        assertEquals("Your zones · 1", zoneListMenuLabel(1))
        assertEquals("Your zones · 12", zoneListMenuLabel(12))
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
        val hit = mapHitAt(tap, home, listOf(zoneA, zoneB), listOf(paidArea), shapes)
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
    fun `an empty tariff list is how a build with no bundled areas behaves`() {
        // It used to be how the *overlay being off* behaved too — MapScreen
        // passed the drawn areas here, which are empty whenever the layer is
        // hidden, so tapping your own area did nothing and the week that
        // v0.6.6 shipped was unreachable. The list passed in is now every area
        // regardless of what is drawn; an empty one now means only that the
        // bundled asset is missing or corrupt.
        val tap = GeoPoint(52.3650, 4.8850, 0f) // inside paidArea, outside every zone
        assertNull(mapHitAt(tap, home, emptyList(), emptyList()))
    }

    @Test
    fun `a tap outside everything hits nothing`() {
        assertNull(mapHitAt(GeoPoint(10.0, 10.0, 0f), home, listOf(zoneA), listOf(paidArea), shapes))
    }

    @Test
    fun `zone precedence still uses the nearest centre inside mapHitAt`() {
        val big = FreeZone(52.0, 4.0, radiusM = 200.0, buurt = "big")
        val small = FreeZone(52.0009, 4.0, radiusM = 30.0, buurt = "small")
        assertEquals(
            MapHit.Zone(ZoneRef.Free(1)),
            mapHitAt(GeoPoint(52.0009, 4.0, 0f), null, listOf(big, small), emptyList(), shapes),
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
    fun `a schedule that starts with its hours is not cut at the first day`() {
        // T17N is "Basistarief TC7 19-06, niet za op zo". Cutting at the first
        // day token gave "za op zo", which says the opposite of "niet za op zo".
        val nightly = paidArea.copy(name = "Basistarief TC7 19-06, niet za op zo")
        assertEquals("19-06, niet za op zo", tariffHours(nightly))
    }

    @Test
    fun `every bundled area keeps its negation words if it has any`() {
        val areas = TariffAreas.parse(java.io.File("src/main/assets/amsterdam_tarieven.json").readText())
        areas.filter { "niet" in it.name }.forEach {
            assertTrue("lost 'niet' from ${it.code}: ${tariffHours(it)}", "niet" in tariffHours(it))
        }
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

    // --- what it costs right now (v0.6.0) ---

    @Test
    fun `charging reads as the rate and when it stops`() {
        val now = TariffNow.Charging("€3,01/h", endsInMin = 8 * 60 + 30)
        // Tuesday 10:30, ending the same day, so no day letter is spent.
        assertEquals("€3,01/h · until 19:00", tariffNowText(now, 1, 10 * 60 + 30))
    }

    @Test
    fun `all day is reserved for charging that never stops`() {
        assertEquals(
            "€8,05/h · all day",
            tariffNowText(TariffNow.Charging("€8,05/h", null), 2, 13 * 60),
        )
    }

    // --- the day on the boundary (v0.7.0) ---

    @Test
    fun `a boundary later today is named without a day`() {
        // Tuesday 07:00, charging from 09:00 — two hours, same day, no letter.
        assertEquals("Free · from 09:00", tariffNowText(TariffNow.Free(2 * 60), 1, 7 * 60))
    }

    @Test
    fun `crossing only midnight is already another day`() {
        // Tuesday 20:00 plus thirteen hours is Wednesday morning. The old
        // string here was a bare "09:00", and this test previously asserted it
        // under the name "wrapping past midnight" — the wrap was noticed and
        // then thrown away by clock()'s modulo.
        assertEquals("Free · from wo 09:00", tariffNowText(TariffNow.Free(13 * 60), 1, 20 * 60))
    }

    @Test
    fun `a boundary on another day is named with it`() {
        // The T17N Saturday case. Twenty-nine hours out, and the old string was
        // "Free · from 19:00" — which reads as tonight at seven.
        assertEquals(
            "Free · from zo 19:00",
            tariffNowText(TariffNow.Free(29 * 60), dayOfWeek = 5, minuteOfDay = 14 * 60),
        )
    }

    @Test
    fun `a charge run ending tomorrow morning says so`() {
        assertEquals(
            "€1,72/h · until di 06:00",
            tariffNowText(
                TariffNow.Charging("€1,72/h", 10 * 60),
                dayOfWeek = 0,
                minuteOfDay = 20 * 60,
            ),
        )
    }

    @Test
    fun `a boundary that wraps past Sunday lands back on Monday`() {
        assertEquals("ma 06:00", clockAhead(dayOfWeek = 6, minuteOfDay = 23 * 60, offsetMin = 7 * 60))
    }

    @Test
    fun `midnight tonight is 24 00 today, not 00 00 tomorrow`() {
        // The six areas charging "900-2400" stop at midnight and do not resume.
        // Naming that boundary "ma 00:00" on a Sunday evening is technically
        // right and reads as a day away.
        assertEquals("24:00", clockAhead(dayOfWeek = 6, minuteOfDay = 20 * 60, offsetMin = 4 * 60))
        assertEquals(
            "€6,98/h · until 24:00",
            tariffNowText(TariffNow.Charging("€6,98/h", 4 * 60), dayOfWeek = 6, minuteOfDay = 20 * 60),
        )
    }

    @Test
    fun `a midnight further out is named on the day that ends`() {
        // Monday 22:00 plus 26 hours is the midnight closing Tuesday.
        assertEquals("di 24:00", clockAhead(dayOfWeek = 0, minuteOfDay = 22 * 60, offsetMin = 26 * 60))
    }

    @Test
    fun `nothing scheduled says so instead of inventing a time`() {
        assertEquals("Free · no paid hours", tariffNowText(TariffNow.Free(null), 2, 12 * 60))
    }
}
